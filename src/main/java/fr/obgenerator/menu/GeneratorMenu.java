package fr.obgenerator.menu;

import fr.obgenerator.model.Generator;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

final class GeneratorMenu implements InventoryHolder {

    final Generator generator;
    final ConfigurationSection config;
    final Map<Integer, Consumer<ClickType>> actions = new HashMap<>();
    Inventory inventory;
    ScheduledTask refresher;

    GeneratorMenu(Generator generator, ConfigurationSection config) {
        this.generator = generator;
        this.config = config;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
