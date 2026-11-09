package fr.obgenerator.manager;

import fr.obgenerator.ObGenerator;
import fr.obgenerator.hook.GeneratorExpansion;
import fr.obgenerator.item.ItemSpec;
import fr.obgenerator.model.Generator;
import fr.obgenerator.model.GeneratorType;
import fr.obgenerator.model.LevelDef;
import fr.obgenerator.model.Requirement;
import fr.obgenerator.storage.Storage;
import fr.obgenerator.util.Durations;
import fr.obgenerator.util.Text;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;

/** Cycle de vie, production, déverrouillage et amélioration des générateurs posés. */
public final class GeneratorManager {

    private record BlockKey(String world, int x, int y, int z) { }

    private record ChunkKey(String world, int x, int z) { }

    private final ObGenerator plugin;
    private final Storage storage;
    private final Map<UUID, Generator> byId = new ConcurrentHashMap<>();
    private final Map<BlockKey, Generator> byBlock = new ConcurrentHashMap<>();
    private final Map<ChunkKey, Set<Generator>> byChunk = new ConcurrentHashMap<>();
    private final Map<ChunkKey, ScheduledTask> tickers = new ConcurrentHashMap<>();

    public GeneratorManager(ObGenerator plugin, Storage storage) {
        this.plugin = plugin;
        this.storage = storage;
    }

    private Settings settings() {
        return plugin.settings();
    }

    private ConfigurationSection config() {
        return settings().config();
    }

    // ---------------------------------------------------------------- Index

    public void loadAll() {
        for (Generator generator : storage.loadAll()) {
            generator.setType(settings().type(generator.typeId()));
            if (generator.type() == null) {
                plugin.getLogger().warning("Generateur " + generator.id() + " : type inconnu '" + generator.typeId() + "', inactif.");
            }
            generator.setDirty(false);
            index(generator);
        }
        plugin.getLogger().info(byId.size() + " generateurs charges.");
    }

    public void rebindTypes() {
        for (Generator generator : byId.values()) {
            synchronized (generator) {
                generator.setType(settings().type(generator.typeId()));
            }
        }
    }

    private void index(Generator generator) {
        byId.put(generator.id(), generator);
        byBlock.put(new BlockKey(generator.world(), generator.x(), generator.y(), generator.z()), generator);
        byChunk.computeIfAbsent(chunkKey(generator), key -> ConcurrentHashMap.newKeySet()).add(generator);
    }

    private static ChunkKey chunkKey(Generator generator) {
        return new ChunkKey(generator.world(), generator.x() >> 4, generator.z() >> 4);
    }

    public Generator at(Block block) {
        return byBlock.get(new BlockKey(block.getWorld().getName(), block.getX(), block.getY(), block.getZ()));
    }

    public List<Generator> ownedBy(UUID owner) {
        return byId.values().stream().filter(generator -> owner.equals(generator.owner())).toList();
    }

    public long countOnIsland(UUID island) {
        return byId.values().stream().filter(generator -> island.equals(generator.island())).count();
    }

    public long countOnIsland(UUID island, String typeId) {
        return byId.values().stream().filter(generator -> island.equals(generator.island()) && typeId.equals(generator.typeId())).count();
    }

    public long countOwned(UUID owner) {
        return byId.values().stream().filter(generator -> owner.equals(generator.owner())).count();
    }

    public int size() {
        return byId.size();
    }

    // ---------------------------------------------------------------- Ticking par chunk

    /** Une seule tâche par chunk contenant des générateurs : compatible Paper et Folia. */
    public void startChunk(World world, int chunkX, int chunkZ) {
        ChunkKey key = new ChunkKey(world.getName(), chunkX, chunkZ);
        if (!byChunk.containsKey(key)) {
            return;
        }
        tickers.computeIfAbsent(key, ignored -> Bukkit.getRegionScheduler().runAtFixedRate(plugin, world, chunkX, chunkZ,
            task -> tickChunk(key, task), 1L, Math.max(1, config().getInt("tick-seconds", 1)) * 20L));
    }

    public void stopChunk(Chunk chunk) {
        ChunkKey key = new ChunkKey(chunk.getWorld().getName(), chunk.getX(), chunk.getZ());
        ScheduledTask task = tickers.remove(key);
        if (task != null) {
            task.cancel();
        }
        Set<Generator> generators = byChunk.get(key);
        if (generators != null) {
            for (Generator generator : generators) {
                synchronized (generator) {
                    generator.setActive(false);
                    generator.setDisplay(null, null);
                }
            }
        }
    }

    /** Reprend les chunks déjà chargés avant la fin du démarrage. */
    public void startLoadedChunks() {
        for (ChunkKey key : byChunk.keySet()) {
            World world = Bukkit.getWorld(key.world());
            try {
                if (world != null && world.isChunkLoaded(key.x(), key.z())) {
                    startChunk(world, key.x(), key.z());
                }
            } catch (RuntimeException notOwned) {
                // Folia : le ChunkLoadEvent prendra le relais.
            }
        }
    }

    private void tickChunk(ChunkKey key, ScheduledTask task) {
        Set<Generator> generators = byChunk.get(key);
        World world = Bukkit.getWorld(key.world());
        if (generators == null || generators.isEmpty() || world == null || !world.isChunkLoaded(key.x(), key.z())) {
            task.cancel();
            tickers.remove(key);
            return;
        }
        long now = System.currentTimeMillis();
        for (Generator generator : generators) {
            try {
                tick(world, generator, now);
            } catch (RuntimeException error) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "Erreur sur le generateur " + generator.id(), error);
            }
        }
    }

    private void tick(World world, Generator generator, long now) {
        synchronized (generator) {
            if (generator.removed() || generator.type() == null) {
                return;
            }
            if (world.getBlockAt(generator.x(), generator.y(), generator.z()).getType().isAir()) {
                delete(generator);
                return;
            }
            if (!generator.active()) {
                generator.setActive(true);
                creditOfflineTime(generator, now);
            }
            if (generator.upgrading() && now >= generator.upgradeEndsAt()) {
                finishUpgrade(generator, now);
            }
            if (generator.unlocked() && (!generator.upgrading() || config().getBoolean("upgrade.produce-while-upgrading", true))) {
                produce(world, generator, now);
            }
            updateHologram(world, generator, now);
        }
    }

    /** Le temps passé chunk déchargé compte dans la limite de offline.max-duration. */
    private void creditOfflineTime(Generator generator, long now) {
        if (!generator.unlocked()) {
            return;
        }
        long maximum = config().getBoolean("offline.enabled", true) ? Durations.parse(config().getString("offline.max-duration"), 0) : 0;
        long elapsed = Math.max(0, now - generator.lastCycle());
        long credited = (long) (Math.min(elapsed, maximum) * config().getDouble("offline.multiplier", 1));
        generator.setLastCycle(now - credited);
    }

    // ---------------------------------------------------------------- Production

    public boolean usesStorage(GeneratorType type, LevelDef level) {
        boolean storedItems = !level.items().isEmpty() && !type.dropItems();
        boolean storedMoney = level.money() > 0 && !config().getBoolean("delivery.direct-money", false);
        return storedItems || storedMoney || level.xp() > 0;
    }

    public long storedAmount(Generator generator, LevelDef level) {
        return !level.items().isEmpty() && !generator.type().dropItems() ? generator.storedItemCount() : generator.storedCycles();
    }

    public boolean isFull(Generator generator, LevelDef level) {
        return usesStorage(generator.type(), level) && storedAmount(generator, level) >= level.capacity();
    }

    private void produce(World world, Generator generator, long now) {
        GeneratorType type = generator.type();
        LevelDef level = type.level(generator.level());
        long elapsed = now - generator.lastCycle();
        if (elapsed < level.intervalMillis()) {
            return;
        }
        if (isFull(generator, level) || ownerIsAfk(generator)) {
            generator.setLastCycle(now);
            return;
        }
        long cycles = elapsed / level.intervalMillis();
        if (usesStorage(type, level)) {
            long perCycle = !level.items().isEmpty() && !type.dropItems() ? level.itemsPerCycle() : 1;
            long free = level.capacity() - storedAmount(generator, level);
            cycles = Math.min(cycles, Math.max(1, (free + perCycle - 1) / perCycle));
        }
        generator.setLastCycle(generator.lastCycle() + cycles * level.intervalMillis());

        if (!type.dropItems()) {
            for (Map.Entry<ItemSpec, Integer> output : level.items().entrySet()) {
                generator.storedItems().merge(output.getKey().key(), output.getValue() * cycles, Long::sum);
            }
            long overflow = generator.storedItemCount() - level.capacity();
            if (overflow > 0 && !level.items().isEmpty()) {
                String lastKey = null;
                for (ItemSpec spec : level.items().keySet()) {
                    lastKey = spec.key();
                }
                generator.storedItems().merge(lastKey, -overflow, Long::sum);
                generator.storedItems().entrySet().removeIf(entry -> entry.getValue() <= 0);
            }
        }
        if (level.money() > 0) {
            double amount = level.money() * cycles;
            if (config().getBoolean("delivery.direct-money", false) && generator.owner() != null) {
                plugin.vault().deposit(Bukkit.getOfflinePlayer(generator.owner()), amount);
            } else {
                generator.setStoredMoney(generator.storedMoney() + amount);
            }
        }
        generator.setStoredXp(generator.storedXp() + level.xp() * cycles);
        generator.setStoredCycles(generator.storedCycles() + cycles);

        Location center = new Location(world, generator.x() + 0.5, generator.y() + 1.1, generator.z() + 0.5);
        if (type.dropItems()) {
            dropItems(world, center, level);
        }
        spawnMobs(world, center, level);
        for (String command : level.commands()) {
            String line = command.replace("{owner}", String.valueOf(generator.ownerName())).replace("{level}", String.valueOf(generator.level()));
            Bukkit.getGlobalRegionScheduler().execute(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line));
        }
    }

    private boolean ownerIsAfk(Generator generator) {
        return config().getBoolean("essentials.pause-when-owner-afk", false) && generator.owner() != null
            && plugin.essentials().isAfk(generator.owner());
    }

    private void dropItems(World world, Location center, LevelDef level) {
        int maximum = config().getInt("delivery.max-nearby-items", 16);
        if (world.getNearbyEntitiesByType(Item.class, center, 2).size() >= maximum) {
            return;
        }
        for (Map.Entry<ItemSpec, Integer> output : level.items().entrySet()) {
            ItemStack stack = output.getKey().build(output.getValue());
            if (stack == null) {
                continue;
            }
            if (!config().getBoolean("rosestacker.use-stacks", true) || !plugin.roseStacker().drop(stack, output.getValue(), center)) {
                world.dropItem(center, stack, item -> item.setVelocity(new org.bukkit.util.Vector()));
            }
        }
    }

    private void spawnMobs(World world, Location center, LevelDef level) {
        double radius = config().getDouble("mobs.radius", 8);
        int maximum = config().getInt("mobs.max-nearby", 12);
        for (Map.Entry<EntityType, Integer> mob : level.mobs().entrySet()) {
            EntityType type = mob.getKey();
            if (world.getNearbyEntities(center, radius, radius, radius, entity -> entity.getType() == type).size() >= maximum) {
                continue;
            }
            if (config().getBoolean("rosestacker.use-stacks", true) && plugin.roseStacker().spawn(type, mob.getValue(), center)) {
                continue;
            }
            for (int i = 0; i < mob.getValue(); i++) {
                world.spawnEntity(center, type, CreatureSpawnEvent.SpawnReason.SPAWNER);
            }
        }
    }

    // ---------------------------------------------------------------- Hologramme

    private List<String> hologramLines(Generator generator, String state) {
        List<String> custom = generator.type().hologram().get(state);
        return custom != null ? custom : config().getStringList("hologram.lines." + state);
    }

    private String hologramState(Generator generator) {
        if (!generator.unlocked()) {
            return "locked";
        }
        if (generator.upgrading()) {
            return "upgrading";
        }
        return isFull(generator, generator.type().level(generator.level())) ? "full" : "running";
    }

    public void updateHologram(World world, Generator generator, long now) {
        if (!config().getBoolean("hologram.enabled", true)) {
            return;
        }
        Map<String, String> placeholders = placeholders(generator, now);
        StringBuilder text = new StringBuilder();
        for (String line : hologramLines(generator, hologramState(generator))) {
            text.append(Text.apply(line, placeholders)).append('\n');
        }
        String rendered = text.toString();
        TextDisplay display = generator.display();
        if (display != null && display.isValid()) {
            if (!rendered.equals(generator.displayedText())) {
                display.text(render(rendered));
                generator.setDisplay(display, rendered);
            }
            return;
        }
        Location location = new Location(world, generator.x() + 0.5, generator.y() + 1 + config().getDouble("hologram.y-offset", 0.35), generator.z() + 0.5);
        TextDisplay spawned = world.spawn(location, TextDisplay.class, entity -> {
            entity.setPersistent(false);
            entity.setBillboard(Display.Billboard.valueOf(config().getString("hologram.billboard", "CENTER").toUpperCase(java.util.Locale.ROOT)));
            entity.setShadowed(config().getBoolean("hologram.shadow", true));
            entity.setSeeThrough(config().getBoolean("hologram.see-through", false));
            entity.setAlignment(TextDisplay.TextAlignment.CENTER);
            entity.setViewRange((float) config().getDouble("hologram.view-range", 0.5));
            entity.setLineWidth(config().getInt("hologram.line-width", 200));
            float scale = (float) config().getDouble("hologram.scale", 1);
            Transformation base = entity.getTransformation();
            entity.setTransformation(new Transformation(base.getTranslation(), base.getLeftRotation(), new Vector3f(scale), base.getRightRotation()));
            String background = config().getString("hologram.background", "");
            if (background.length() == 9 && background.startsWith("#")) {
                entity.setBackgroundColor(Color.fromARGB((int) Long.parseLong(background.substring(1), 16)));
            }
            entity.text(render(rendered));
        });
        generator.setDisplay(spawned, rendered);
    }

    private static Component render(String text) {
        Component result = Component.empty();
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            result = result.append(Text.parse(lines[i]));
            if (i + 1 < lines.length) {
                result = result.append(Component.newline());
            }
        }
        return result;
    }

    private void removeDisplay(Generator generator) {
        TextDisplay display = generator.display();
        generator.setDisplay(null, null);
        if (display != null) {
            display.getScheduler().run(plugin, task -> display.remove(), null);
        }
    }

    // ---------------------------------------------------------------- Placeholders

    public Map<String, String> placeholders(Generator generator, long now) {
        GeneratorType type = generator.type();
        LevelDef level = type.level(generator.level());
        LevelDef next = type.next(generator.level());
        Map<String, String> values = new HashMap<>();
        values.put("generator", type.name());
        values.put("id", type.id());
        values.put("level", String.valueOf(generator.level()));
        values.put("next_level", next == null ? String.valueOf(generator.level()) : String.valueOf(next.level()));
        values.put("max_level", String.valueOf(type.maxLevel()));
        values.put("owner", generator.ownerName() == null ? "?" : generator.ownerName());
        values.put("price", settings().money(type.unlockPrice()));
        values.put("interval", Durations.format(level.intervalMillis()));
        values.put("items_per_cycle", settings().number(level.itemsPerCycle()));
        values.put("money_per_cycle", settings().money(level.money()));
        values.put("xp_per_cycle", settings().number(level.xp()));
        values.put("next_interval", next == null ? "-" : Durations.format(next.intervalMillis()));
        values.put("next_items_per_cycle", next == null ? "-" : settings().number(next.itemsPerCycle()));
        values.put("next_money_per_cycle", next == null ? "-" : settings().money(next.money()));
        long stored = storedAmount(generator, level);
        values.put("stored", settings().number(stored));
        values.put("capacity", settings().number(level.capacity()));
        values.put("stored_bar", settings().bar((double) stored / level.capacity()));
        values.put("stored_money", settings().money(generator.storedMoney()));
        values.put("stored_xp", settings().number(generator.storedXp()));
        long nextCycle = Math.max(0, generator.lastCycle() + level.intervalMillis() - now);
        values.put("next_cycle", Durations.format(nextCycle));
        long remaining = generator.upgrading() ? Math.max(0, generator.upgradeEndsAt() - now) : 0;
        values.put("remaining", Durations.format(remaining));
        double total = next == null ? 1 : Math.max(1, next.upgradeMillis());
        values.put("upgrade_bar", settings().bar(generator.upgrading() ? 1 - remaining / total : 0));
        values.put("upgrade_duration", next == null ? "-" : Durations.format(next.upgradeMillis()));
        return values;
    }

    // ---------------------------------------------------------------- Actions joueur

    public boolean canManage(Player player, Generator generator, Location location) {
        if (player.hasPermission("obgenerator.admin")) {
            return true;
        }
        if (plugin.island() != null && generator.island() != null) {
            return plugin.island().isMember(player, location);
        }
        return player.getUniqueId().equals(generator.owner());
    }

    public void place(Player player, Block target, GeneratorType type, ItemStack item) {
        boolean ok = type.block().isNexo()
            ? plugin.nexo().place(type.block().nexoId(), target.getLocation())
            : setVanillaBlock(target, type);
        if (!ok) {
            target.setType(Material.LODESTONE);
        }
        Generator generator = new Generator(UUID.randomUUID(), type.id(), target.getWorld().getName(), target.getX(), target.getY(), target.getZ());
        generator.setType(type);
        generator.setOwner(player.getUniqueId(), player.getName());
        generator.setIsland(plugin.island() == null ? null : plugin.island().islandAt(target.getLocation()));
        generator.setLevel(Math.clamp(plugin.items().level(item), 1, type.maxLevel()));
        generator.setUnlocked(plugin.items().unlocked(item));
        generator.setLastCycle(System.currentTimeMillis());
        Generator.decode(plugin.items().deposits(item), generator.deposits());
        generator.setActive(true);
        index(generator);
        startChunk(target.getWorld(), target.getX() >> 4, target.getZ() >> 4);
        updateHologram(target.getWorld(), generator, System.currentTimeMillis());
        plugin.send(player, "placed", Map.of("generator", type.name()));
        plugin.playSound(player, "place");
    }

    private static boolean setVanillaBlock(Block target, GeneratorType type) {
        Material material = type.block().vanillaMaterial();
        if (material == null || !material.isBlock()) {
            return false;
        }
        target.setType(material, false);
        return true;
    }

    public void rightClick(Player player, Generator generator, Block block) {
        startChunk(block.getWorld(), block.getX() >> 4, block.getZ() >> 4);
        if (generator.type() == null) {
            plugin.send(player, "unknown-type", Map.of());
            return;
        }
        if (!canManage(player, generator, block.getLocation())) {
            plugin.send(player, "not-member", Map.of());
            plugin.playSound(player, "error");
            return;
        }
        if (!generator.unlocked()) {
            unlock(player, generator, block.getWorld());
            return;
        }
        if (player.isSneaking() && config().getBoolean("interaction.sneak-collect", true)) {
            collect(player, generator);
            return;
        }
        plugin.menus().open(player, generator);
    }

    private void unlock(Player player, Generator generator, World world) {
        double price = generator.type().unlockPrice();
        synchronized (generator) {
            if (generator.unlocked()) {
                return;
            }
            if (!plugin.vault().available()) {
                plugin.send(player, "no-economy", Map.of());
                return;
            }
            if (plugin.vault().balance(player) < price || !plugin.vault().withdraw(player, price)) {
                plugin.send(player, "unlock-no-money", Map.of("price", settings().money(price)));
                plugin.playSound(player, "error");
                return;
            }
            generator.setUnlocked(true);
            generator.setLastCycle(System.currentTimeMillis());
            updateHologram(world, generator, System.currentTimeMillis());
        }
        plugin.send(player, "unlocked", Map.of("generator", generator.type().name(), "price", settings().money(price)));
        plugin.playSound(player, "unlock");
    }

    /** Vide le stockage dans l'inventaire ; ce qui ne rentre pas reste dans le générateur. */
    public void collect(Player player, Generator generator) {
        collect(player, generator, false);
    }

    private void collect(Player player, Generator generator, boolean silentWhenEmpty) {
        double money;
        long xp;
        long items = 0;
        boolean leftovers = false;
        synchronized (generator) {
            for (var iterator = generator.storedItems().entrySet().iterator(); iterator.hasNext();) {
                Map.Entry<String, Long> entry = iterator.next();
                ItemSpec spec = ItemSpec.parse(plugin, entry.getKey());
                if (spec == null) {
                    iterator.remove();
                    continue;
                }
                long left = entry.getValue();
                int stackSize = spec.maxStack();
                while (left > 0) {
                    int amount = (int) Math.min(left, stackSize);
                    ItemStack stack = spec.build(amount);
                    if (stack == null) {
                        break;
                    }
                    int refused = player.getInventory().addItem(stack).values().stream().mapToInt(ItemStack::getAmount).sum();
                    left -= amount - refused;
                    items += amount - refused;
                    if (refused > 0) {
                        leftovers = true;
                        break;
                    }
                }
                if (left <= 0) {
                    iterator.remove();
                } else {
                    entry.setValue(left);
                }
            }
            money = generator.storedMoney();
            xp = generator.storedXp();
            generator.setStoredMoney(0);
            generator.setStoredXp(0);
            generator.setStoredCycles(leftovers ? generator.storedCycles() : 0);
            generator.setDirty(true);
        }
        if (money > 0 && !plugin.vault().deposit(player, money)) {
            synchronized (generator) {
                generator.setStoredMoney(generator.storedMoney() + money);
            }
            money = 0;
        }
        if (xp > 0) {
            player.giveExp((int) Math.min(Integer.MAX_VALUE, xp));
        }
        if (items == 0 && money <= 0 && xp == 0) {
            if (leftovers || !silentWhenEmpty) {
                plugin.send(player, leftovers ? "inventory-full" : "collect-empty", Map.of());
            }
            return;
        }
        plugin.send(player, leftovers ? "collected-partial" : "collected", Map.of(
            "items", settings().number(items), "money", settings().money(money), "xp", settings().number(xp)));
        plugin.playSound(player, "collect");
    }

    /** Vend le stockage aux prix du worth.yml d'EssentialsX. */
    public void sell(Player player, Generator generator) {
        if (!plugin.essentials().available() || !plugin.vault().available()) {
            plugin.send(player, "sell-unavailable", Map.of());
            return;
        }
        double total = 0;
        long sold = 0;
        synchronized (generator) {
            for (var iterator = generator.storedItems().entrySet().iterator(); iterator.hasNext();) {
                Map.Entry<String, Long> entry = iterator.next();
                ItemSpec spec = ItemSpec.parse(plugin, entry.getKey());
                ItemStack sample = spec == null ? null : spec.build(1);
                double price = sample == null ? 0 : plugin.essentials().price(sample);
                if (price > 0) {
                    total += price * entry.getValue();
                    sold += entry.getValue();
                    iterator.remove();
                }
            }
            generator.setDirty(true);
        }
        if (sold == 0) {
            plugin.send(player, "sell-nothing", Map.of());
            return;
        }
        plugin.vault().deposit(player, total);
        plugin.send(player, "sold", Map.of("items", settings().number(sold), "money", settings().money(total)));
        plugin.playSound(player, "collect");
    }

    public long deposited(Generator generator, Requirement requirement) {
        return generator.deposits().getOrDefault(requirement.id(), 0L);
    }

    public boolean complete(Generator generator, Requirement requirement) {
        return deposited(generator, requirement) >= requirement.amount();
    }

    /** Clic droit sur une exigence : dépose ce que le joueur possède, ou valide une condition. */
    public void deposit(Player player, Generator generator, Requirement requirement) {
        synchronized (generator) {
            if (generator.upgrading() || complete(generator, requirement)) {
                return;
            }
            long missing = requirement.amount() - deposited(generator, requirement);
            long given = switch (requirement.type()) {
                case ITEM -> takeItems(player, requirement.item(), missing);
                case MONEY -> takeMoney(player, missing);
                case XP -> takeLevels(player, missing);
                case PLAYTIME -> player.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20L >= requirement.amount() ? missing : 0;
                case STATISTIC -> player.getStatistic(requirement.statistic()) >= requirement.amount() ? missing : 0;
                case PERMISSION -> player.hasPermission(requirement.permission()) ? missing : 0;
                case PLACEHOLDER -> placeholderValue(player, requirement) >= requirement.amount() ? missing : 0;
            };
            if (given <= 0) {
                plugin.send(player, "requirement-missing", Map.of("requirement", plugin.menus().label(requirement)));
                plugin.playSound(player, "error");
                return;
            }
            generator.deposits().merge(requirement.id(), given, Long::sum);
            generator.setDirty(true);
        }
        plugin.playSound(player, "deposit");
    }

    private double placeholderValue(Player player, Requirement requirement) {
        if (requirement.placeholder() == null || !Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            return 0;
        }
        try {
            return Double.parseDouble(GeneratorExpansion.apply(player, requirement.placeholder()).replace(",", ".").trim());
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }

    private long takeItems(Player player, ItemSpec spec, long wanted) {
        long taken = 0;
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int slot = 0; slot < contents.length && taken < wanted; slot++) {
            ItemStack stack = contents[slot];
            if (spec.matches(stack)) {
                int used = (int) Math.min(stack.getAmount(), wanted - taken);
                stack.setAmount(stack.getAmount() - used);
                player.getInventory().setItem(slot, stack.getAmount() <= 0 ? null : stack);
                taken += used;
            }
        }
        return taken;
    }

    private long takeMoney(Player player, long wanted) {
        if (!plugin.vault().available()) {
            return 0;
        }
        long amount = (long) Math.min(wanted, Math.floor(plugin.vault().balance(player)));
        return amount > 0 && plugin.vault().withdraw(player, amount) ? amount : 0;
    }

    private static long takeLevels(Player player, long wanted) {
        long amount = Math.min(wanted, player.getLevel());
        if (amount > 0) {
            player.setLevel(player.getLevel() - (int) amount);
        }
        return amount;
    }

    public boolean ready(Generator generator, LevelDef next) {
        return next != null && next.requirements().stream().allMatch(requirement -> complete(generator, requirement));
    }

    public void startUpgrade(Player player, Generator generator) {
        synchronized (generator) {
            LevelDef next = generator.type().next(generator.level());
            if (generator.upgrading() || !ready(generator, next)) {
                plugin.playSound(player, "error");
                return;
            }
            generator.deposits().clear();
            generator.setUpgradeEndsAt(System.currentTimeMillis() + Math.max(1, next.upgradeMillis()));
        }
        plugin.send(player, "upgrade-started", Map.of("generator", generator.type().name(),
            "duration", Durations.format(generator.upgradeEndsAt() - System.currentTimeMillis())));
        plugin.playSound(player, "upgrade-start");
    }

    private void finishUpgrade(Generator generator, long now) {
        generator.setUpgradeEndsAt(0);
        generator.setLevel(Math.min(generator.type().maxLevel(), generator.level() + 1));
        if (!config().getBoolean("upgrade.produce-while-upgrading", true)) {
            generator.setLastCycle(now);
        }
        Player owner = generator.owner() == null ? null : Bukkit.getPlayer(generator.owner());
        if (owner != null) {
            String name = generator.type().name();
            String level = String.valueOf(generator.level());
            owner.getScheduler().run(plugin, task -> {
                plugin.send(owner, "upgrade-finished", Map.of("generator", name, "level", level));
                plugin.playSound(owner, "upgrade-finish");
            }, null);
        }
    }

    public void setLevel(Generator generator, int level) {
        synchronized (generator) {
            generator.setUpgradeEndsAt(0);
            generator.deposits().clear();
            generator.setLevel(Math.clamp(level, 1, generator.type().maxLevel()));
            generator.setUnlocked(true);
        }
    }

    // ---------------------------------------------------------------- Retrait

    /**
     * Ramassage : le stockage va au joueur, l'item conserve niveau, verrou et dépôts.
     *
     * @return l'item, ou null si l'inventaire ne peut pas recevoir tout le stockage
     */
    public ItemStack pickUp(Player player, Generator generator) {
        collect(player, generator, true);
        synchronized (generator) {
            if (!generator.storedItems().isEmpty()) {
                return null;
            }
            ItemStack item = plugin.items().generator(generator.type(), generator.level(), generator.unlocked(), generator.depositsText());
            delete(generator);
            return item;
        }
    }

    private void delete(Generator generator) {
        generator.setRemoved();
        removeDisplay(generator);
        byId.remove(generator.id());
        byBlock.remove(new BlockKey(generator.world(), generator.x(), generator.y(), generator.z()));
        Set<Generator> generators = byChunk.get(chunkKey(generator));
        if (generators != null) {
            generators.remove(generator);
        }
        storage.delete(generator.id());
    }

    public void remove(Generator generator) {
        synchronized (generator) {
            delete(generator);
        }
    }

    public void removeIsland(UUID island) {
        List<Generator> matching = new ArrayList<>();
        for (Generator generator : byId.values()) {
            if (island.equals(generator.island())) {
                matching.add(generator);
            }
        }
        matching.forEach(this::remove);
    }

    // ---------------------------------------------------------------- Sauvegarde

    public void saveDirty() {
        List<Storage.Row> rows = new ArrayList<>();
        for (Generator generator : byId.values()) {
            synchronized (generator) {
                if (generator.dirty()) {
                    rows.add(Storage.Row.of(generator));
                    generator.setDirty(false);
                }
            }
        }
        storage.save(rows);
    }

    /** Les hologrammes ne sont jamais sauvegardés : ce retrait ne sert qu'aux rechargements à chaud. */
    public void removeDisplays() {
        for (Generator generator : byId.values()) {
            TextDisplay display = generator.display();
            if (display != null) {
                try {
                    display.remove();
                } catch (RuntimeException otherRegion) {
                    // Folia à l'arrêt : l'entité non persistante disparaît avec le serveur.
                }
            }
        }
    }
}
