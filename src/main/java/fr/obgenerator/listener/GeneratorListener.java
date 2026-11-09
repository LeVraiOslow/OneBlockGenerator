package fr.obgenerator.listener;

import fr.obgenerator.ObGenerator;
import fr.obgenerator.manager.GeneratorManager;
import fr.obgenerator.model.Generator;
import fr.obgenerator.model.GeneratorType;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;

public final class GeneratorListener implements Listener {

    private final ObGenerator plugin;
    /** Vrai pendant le BlockPlaceEvent que ce plugin déclenche pour interroger les protections. */
    private final ThreadLocal<Boolean> checkingProtection = ThreadLocal.withInitial(() -> false);

    public GeneratorListener(ObGenerator plugin) {
        this.plugin = plugin;
    }

    private GeneratorManager generators() {
        return plugin.generators();
    }

    private ConfigurationSection config() {
        return plugin.settings().config();
    }

    @EventHandler
    public void onServerLoad(ServerLoadEvent event) {
        plugin.recipes().reload();
        generators().startLoadedChunks();
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        generators().startChunk(event.getWorld(), event.getChunk().getX(), event.getChunk().getZ());
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        generators().stopChunk(event.getChunk());
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        Player player = event.getPlayer();
        Block clicked = event.getClickedBlock();
        Generator generator = generators().at(clicked);
        String typeId = plugin.items().typeId(event.getItem());
        if (generator != null && !(typeId != null && player.isSneaking())) {
            event.setCancelled(true);
            if (event.getHand() == EquipmentSlot.HAND) {
                generators().rightClick(player, generator, clicked);
            }
            return;
        }
        if (typeId == null) {
            return;
        }
        event.setCancelled(true);
        place(player, event.getItem(), typeId, clicked, event.getBlockFace(), event.getHand());
    }

    private void place(Player player, ItemStack item, String typeId, Block clicked, BlockFace face, EquipmentSlot hand) {
        GeneratorType type = plugin.settings().type(typeId);
        if (type == null) {
            plugin.send(player, "unknown-type", Map.of());
            return;
        }
        Block target = clicked.isReplaceable() ? clicked : clicked.getRelative(face);
        World world = target.getWorld();
        boolean occupied = !world.getNearbyEntities(BoundingBox.of(target), LivingEntity.class::isInstance).isEmpty();
        if (!target.isReplaceable() || generators().at(target) != null || occupied) {
            return;
        }
        if (!isAllowedLocation(player, target, type)) {
            plugin.playSound(player, "error");
            return;
        }
        // Comme en vanilla, le bloc existe pendant l'événement : les compteurs de blocs d'île restent justes.
        BlockState replaced = target.getState();
        Material vanilla = type.block().vanillaMaterial();
        target.setType(vanilla != null && vanilla.isBlock() ? vanilla : Material.LODESTONE, false);
        BlockPlaceEvent check = new BlockPlaceEvent(target, replaced, clicked, item, player, true, hand);
        checkingProtection.set(true);
        try {
            check.callEvent();
        } finally {
            checkingProtection.set(false);
        }
        if (check.isCancelled() || !check.canBuild()) {
            replaced.update(true, false);
            plugin.send(player, "cannot-place-here", Map.of());
            return;
        }
        ItemStack placed = item.clone();
        if (player.getGameMode() != GameMode.CREATIVE) {
            item.setAmount(item.getAmount() - 1);
        }
        generators().place(player, target, type, placed);
    }

    private boolean isAllowedLocation(Player player, Block target, GeneratorType type) {
        List<String> worlds = config().getStringList("placement.allowed-worlds");
        if (!worlds.isEmpty() && !worlds.contains(target.getWorld().getName())) {
            plugin.send(player, "cannot-place-here", Map.of());
            return false;
        }
        if (player.hasPermission("obgenerator.admin")) {
            return true;
        }
        if (plugin.island() == null) {
            long limit = config().getLong("limits.per-player", 0);
            if (limit > 0 && generators().countOwned(player.getUniqueId()) >= limit) {
                plugin.send(player, "limit-reached", Map.of("limit", String.valueOf(limit)));
                return false;
            }
            return true;
        }
        UUID island = plugin.island().islandAt(target.getLocation());
        if (island == null) {
            if (config().getBoolean("placement.require-island", true)) {
                plugin.send(player, "must-be-on-island", Map.of());
                return false;
            }
            return true;
        }
        if (!plugin.island().isMember(player, target.getLocation())) {
            plugin.send(player, "not-member", Map.of());
            return false;
        }
        long total = config().getLong("limits.per-island", 0);
        if (total > 0 && generators().countOnIsland(island) >= total) {
            plugin.send(player, "limit-reached", Map.of("limit", String.valueOf(total)));
            return false;
        }
        long perType = config().getLong("limits.per-type-per-island", 0);
        if (perType > 0 && generators().countOnIsland(island, type.id()) >= perType) {
            plugin.send(player, "limit-reached", Map.of("limit", String.valueOf(perType)));
            return false;
        }
        return true;
    }

    /** Le refus d'un clic ne suffit pas à tous les plugins : la pose vanilla reste interdite. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlace(BlockPlaceEvent event) {
        if (!checkingProtection.get() && plugin.items().isGenerator(event.getItemInHand())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Generator generator = generators().at(event.getBlock());
        if (generator == null) {
            return;
        }
        Player player = event.getPlayer();
        event.setCancelled(true);
        if (generator.type() == null) {
            if (player.hasPermission("obgenerator.admin")) {
                generators().remove(generator);
                event.setCancelled(false);
            }
            return;
        }
        if (!generators().canManage(player, generator, event.getBlock().getLocation())) {
            plugin.send(player, "not-member", Map.of());
            return;
        }
        boolean owner = player.getUniqueId().equals(generator.owner()) || player.hasPermission("obgenerator.admin");
        if (config().getBoolean("interaction.pickup-only-owner", false) && !owner) {
            plugin.send(player, "pickup-owner-only", Map.of());
            return;
        }
        if (config().getBoolean("interaction.pickup-require-sneak", true) && !player.isSneaking()) {
            plugin.send(player, "pickup-sneak", Map.of());
            return;
        }
        if (generator.upgrading()) {
            plugin.send(player, "pickup-upgrading", Map.of());
            return;
        }
        ItemStack item = generators().pickUp(player, generator);
        if (item == null) {
            return;
        }
        event.setCancelled(false);
        event.setDropItems(false);
        event.setExpToDrop(0);
        player.getInventory().addItem(item).values().forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
    }

    private void protect(List<Block> blocks) {
        blocks.removeIf(block -> generators().at(block) != null);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        protect(event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        protect(event.blockList());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(block -> generators().at(block) != null)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(block -> generators().at(block) != null)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        event.setCancelled(generators().at(event.getBlock()) != null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFade(BlockFadeEvent event) {
        event.setCancelled(generators().at(event.getBlock()) != null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDecay(LeavesDecayEvent event) {
        event.setCancelled(generators().at(event.getBlock()) != null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        event.setCancelled(generators().at(event.getToBlock()) != null);
    }

    /** Couvre sable et gravier (chute), enderman, ravageur. */
    @EventHandler(ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent event) {
        event.setCancelled(generators().at(event.getBlock()) != null);
    }
}
