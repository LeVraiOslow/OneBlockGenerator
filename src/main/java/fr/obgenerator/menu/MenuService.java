package fr.obgenerator.menu;

import fr.obgenerator.ObGenerator;
import fr.obgenerator.item.ItemSpec;
import fr.obgenerator.item.MenuItem;
import fr.obgenerator.model.Generator;
import fr.obgenerator.model.LevelDef;
import fr.obgenerator.model.Requirement;
import fr.obgenerator.util.Durations;
import fr.obgenerator.util.Text;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;

/** Menu d'amélioration, entièrement décrit par menus.yml. */
public final class MenuService implements Listener {

    private final ObGenerator plugin;

    public MenuService(ObGenerator plugin) {
        this.plugin = plugin;
    }

    public void open(Player player, Generator generator) {
        ConfigurationSection config = plugin.settings().menu(generator.type().menu());
        GeneratorMenu menu = new GeneratorMenu(generator, config);
        Map<String, String> placeholders;
        synchronized (generator) {
            placeholders = plugin.generators().placeholders(generator, System.currentTimeMillis());
        }
        int rows = Math.clamp(config.getInt("rows", 6), 1, 6);
        menu.inventory = Bukkit.createInventory(menu, rows * 9, Text.parse(Text.apply(config.getString("title", "{generator}"), placeholders)));
        render(player, menu);
        player.openInventory(menu.inventory);
        menu.refresher = player.getScheduler().runAtFixedRate(plugin, task -> {
            if (player.getOpenInventory().getTopInventory().getHolder(false) != menu) {
                task.cancel();
            } else if (menu.generator.removed() || !inRange(player, menu.generator)) {
                player.closeInventory();
                task.cancel();
            } else {
                render(player, menu);
            }
        }, null, 20L, 20L);
    }

    private boolean inRange(Player player, Generator generator) {
        double maximum = plugin.settings().config().getDouble("interaction.max-menu-distance", 8);
        Location location = player.getLocation();
        return location.getWorld().getName().equals(generator.world())
            && location.distanceSquared(new Location(location.getWorld(), generator.x() + 0.5, generator.y() + 0.5, generator.z() + 0.5)) <= maximum * maximum;
    }

    private void render(Player player, GeneratorMenu menu) {
        Generator generator = menu.generator;
        Map<Integer, ItemStack> content = new HashMap<>();
        menu.actions.clear();
        synchronized (generator) {
            long now = System.currentTimeMillis();
            Map<String, String> placeholders = plugin.generators().placeholders(generator, now);
            Map<String, List<String>> blocks = Map.of("production", productionLines(menu.config, generator.type().level(generator.level())),
                "next_production", generator.type().next(generator.level()) == null ? List.of()
                    : productionLines(menu.config, generator.type().next(generator.level())));
            MenuItem filler = MenuItem.from(plugin, menu.config.getConfigurationSection("fill"), "BLACK_STAINED_GLASS_PANE");
            if (filler != null) {
                ItemStack pane = filler.build(placeholders, blocks, null);
                for (int slot = 0; slot < menu.inventory.getSize(); slot++) {
                    content.put(slot, pane);
                }
            }
            renderRequirements(player, menu, placeholders, content);
            ConfigurationSection items = menu.config.getConfigurationSection("items");
            if (items != null) {
                for (String key : items.getKeys(false)) {
                    renderItem(player, menu, items.getConfigurationSection(key), placeholders, blocks, content);
                }
            }
        }
        for (int slot = 0; slot < menu.inventory.getSize(); slot++) {
            ItemStack wanted = content.get(slot);
            if (!Objects.equals(menu.inventory.getItem(slot), wanted)) {
                menu.inventory.setItem(slot, wanted);
            }
        }
    }

    private void renderItem(Player player, GeneratorMenu menu, ConfigurationSection section, Map<String, String> placeholders,
                            Map<String, List<String>> blocks, Map<Integer, ItemStack> content) {
        if (section == null) {
            return;
        }
        Generator generator = menu.generator;
        String type = section.getString("type", "NONE").toUpperCase(Locale.ROOT);
        ConfigurationSection visual = section;
        if (type.equals("START")) {
            visual = section.getConfigurationSection(startState(generator));
            if (visual == null) {
                return;
            }
        }
        MenuItem item = MenuItem.from(plugin, visual, "STONE");
        ItemStack stack = item.build(placeholders, blocks, generator.type().block());
        List<Integer> slots = new ArrayList<>(section.getIntegerList("slots"));
        if (section.contains("slot")) {
            slots.add(section.getInt("slot"));
        }
        List<String> commands = section.getStringList("commands");
        Consumer<ClickType> action = switch (type) {
            case "COLLECT" -> click -> plugin.generators().collect(player, generator);
            case "SELL" -> click -> plugin.generators().sell(player, generator);
            case "START" -> click -> plugin.generators().startUpgrade(player, generator);
            case "CLOSE" -> click -> player.closeInventory();
            default -> commands.isEmpty() ? null : click -> runCommands(player, commands, placeholders);
        };
        for (int slot : slots) {
            if (slot >= 0 && slot < menu.inventory.getSize()) {
                content.put(slot, stack);
                if (action != null) {
                    menu.actions.put(slot, action);
                }
            }
        }
    }

    private String startState(Generator generator) {
        LevelDef next = generator.type().next(generator.level());
        if (generator.upgrading()) {
            return "upgrading";
        }
        if (next == null) {
            return "max";
        }
        return plugin.generators().ready(generator, next) ? "ready" : "waiting";
    }

    private void renderRequirements(Player player, GeneratorMenu menu, Map<String, String> placeholders, Map<Integer, ItemStack> content) {
        Generator generator = menu.generator;
        LevelDef next = generator.type().next(generator.level());
        if (next == null || generator.upgrading()) {
            return;
        }
        ConfigurationSection section = menu.config.getConfigurationSection("requirements");
        if (section == null) {
            return;
        }
        Iterator<Integer> freeSlots = section.getIntegerList("slots").iterator();
        List<String> depositClicks = section.getStringList("deposit-clicks");
        for (Requirement requirement : next.requirements()) {
            int slot = requirement.slot() >= 0 ? requirement.slot() : freeSlots.hasNext() ? freeSlots.next() : -1;
            if (slot < 0 || slot >= menu.inventory.getSize()) {
                continue;
            }
            boolean complete = plugin.generators().complete(generator, requirement);
            long deposited = Math.min(requirement.amount(), plugin.generators().deposited(generator, requirement));
            Map<String, String> values = new HashMap<>(placeholders);
            values.put("requirement", label(requirement));
            values.put("deposited", amount(requirement, deposited));
            values.put("required", amount(requirement, requirement.amount()));
            values.put("missing", amount(requirement, requirement.amount() - deposited));
            values.put("percent", String.valueOf(deposited * 100 / requirement.amount()));
            values.put("bar", plugin.settings().bar((double) deposited / requirement.amount()));
            MenuItem icon = complete ? MenuItem.from(plugin, section.getConfigurationSection("done"), "LIME_DYE") : requirement.icon();
            if (icon == null) {
                icon = MenuItem.from(plugin, section.getConfigurationSection("icons." + requirement.type().name()), "{item}");
            }
            if (icon == null) {
                continue;
            }
            content.put(slot, icon.build(values, Map.of(), requirement.item()));
            menu.actions.put(slot, click -> {
                if (depositClicks.isEmpty() || depositClicks.contains(click.name())) {
                    plugin.generators().deposit(player, generator, requirement);
                }
            });
        }
    }

    private String amount(Requirement requirement, long value) {
        return switch (requirement.type()) {
            case MONEY -> plugin.settings().money(value);
            case PLAYTIME -> Durations.format(value * 1000);
            default -> plugin.settings().number(value);
        };
    }

    public String label(Requirement requirement) {
        if (requirement.label() != null) {
            return requirement.label();
        }
        if (requirement.type() == Requirement.Type.ITEM) {
            return requirement.item().displayName();
        }
        return plugin.settings().raw("requirement-labels." + requirement.type().name());
    }

    private List<String> productionLines(ConfigurationSection config, LevelDef level) {
        ConfigurationSection format = config.getConfigurationSection("production-format");
        List<String> lines = new ArrayList<>();
        if (format == null) {
            return lines;
        }
        for (Map.Entry<ItemSpec, Integer> output : level.items().entrySet()) {
            lines.add(Text.apply(format.getString("item", "{amount}x {name}"),
                Map.of("amount", String.valueOf(output.getValue()), "name", output.getKey().displayName())));
        }
        for (Map.Entry<EntityType, Integer> mob : level.mobs().entrySet()) {
            lines.add(Text.apply(format.getString("mob", "{amount}x {name}"),
                Map.of("amount", String.valueOf(mob.getValue()), "name", "<lang:" + mob.getKey().translationKey() + ">")));
        }
        if (level.money() > 0) {
            lines.add(Text.apply(format.getString("money", "{amount}"), Map.of("amount", plugin.settings().money(level.money()))));
        }
        if (level.xp() > 0) {
            lines.add(Text.apply(format.getString("xp", "{amount} XP"), Map.of("amount", plugin.settings().number(level.xp()))));
        }
        return lines;
    }

    private void runCommands(Player player, List<String> commands, Map<String, String> placeholders) {
        for (String raw : commands) {
            String command = Text.apply(raw, placeholders).replace("{player}", player.getName());
            if (command.equalsIgnoreCase("[close]")) {
                player.closeInventory();
            } else if (command.startsWith("[console] ")) {
                String line = command.substring(10);
                Bukkit.getGlobalRegionScheduler().execute(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line));
            } else {
                player.performCommand(command.startsWith("[player] ") ? command.substring(9) : command);
            }
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof GeneratorMenu menu)
            || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() != menu.inventory) {
            return;
        }
        if (menu.generator.removed()) {
            player.closeInventory();
            return;
        }
        Consumer<ClickType> action = menu.actions.get(event.getSlot());
        if (action != null) {
            action.accept(event.getClick());
            if (player.getOpenInventory().getTopInventory().getHolder(false) == menu) {
                render(player, menu);
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof GeneratorMenu) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder(false) instanceof GeneratorMenu menu && menu.refresher != null) {
            menu.refresher.cancel();
        }
    }

}
