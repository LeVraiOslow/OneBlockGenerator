package fr.obgenerator.hook;

import fr.obgenerator.ObGenerator;
import java.lang.reflect.Method;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** Fait apparaître mobs et items en piles RoseStacker quand le plugin est présent. */
public final class RoseStackerHook {

    private final ObGenerator plugin;
    private Object api;
    private Method preStack;
    private Method dropItem;

    public RoseStackerHook(ObGenerator plugin) {
        this.plugin = plugin;
        Plugin found = Bukkit.getPluginManager().getPlugin("RoseStacker");
        if (found == null || !found.isEnabled()) {
            return;
        }
        try {
            Class<?> type = Class.forName("dev.rosewood.rosestacker.api.RoseStackerAPI", true, found.getClass().getClassLoader());
            api = type.getMethod("getInstance").invoke(null);
            preStack = type.getMethod("preStackEntities", EntityType.class, int.class, Location.class, SpawnReason.class);
            dropItem = type.getMethod("dropItemStack", ItemStack.class, int.class, Location.class, boolean.class);
            plugin.getLogger().info("RoseStacker detecte.");
        } catch (ReflectiveOperationException | LinkageError error) {
            api = null;
            plugin.getLogger().warning("API RoseStacker introuvable : " + error);
        }
    }

    public boolean available() {
        return api != null;
    }

    public boolean spawn(EntityType type, int amount, Location location) {
        if (!available()) {
            return false;
        }
        try {
            preStack.invoke(api, type, amount, location, SpawnReason.SPAWNER);
            return true;
        } catch (ReflectiveOperationException error) {
            plugin.getLogger().warning("RoseStacker a refuse le spawn de " + type + " : " + error.getCause());
            return false;
        }
    }

    public boolean drop(ItemStack stack, int amount, Location location) {
        if (!available()) {
            return false;
        }
        try {
            dropItem.invoke(api, stack, amount, location, false);
            return true;
        } catch (ReflectiveOperationException error) {
            return false;
        }
    }
}
