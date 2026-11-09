package fr.obgenerator.hook;

import fr.obgenerator.ObGenerator;
import java.lang.reflect.Method;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

/** Accès à Nexo par réflexion : le plugin démarre avec ou sans Nexo, quelle que soit sa version. */
public final class NexoHook {

    private final ObGenerator plugin;
    private Method itemFromId;
    private Method idFromItem;
    private Method placeBlock;
    private Method build;

    public NexoHook(ObGenerator plugin) {
        this.plugin = plugin;
        if (!Bukkit.getPluginManager().isPluginEnabled("Nexo")) {
            return;
        }
        try {
            Class<?> items = Class.forName("com.nexomc.nexo.api.NexoItems", true, Bukkit.getPluginManager().getPlugin("Nexo").getClass().getClassLoader());
            itemFromId = items.getMethod("itemFromId", String.class);
            idFromItem = items.getMethod("idFromItem", ItemStack.class);
            Class<?> blocks = Class.forName("com.nexomc.nexo.api.NexoBlocks", true, items.getClassLoader());
            placeBlock = blocks.getMethod("place", String.class, Location.class);
            registerReloadListener(items.getClassLoader());
            plugin.getLogger().info("Nexo detecte.");
        } catch (ReflectiveOperationException | LinkageError error) {
            itemFromId = null;
            plugin.getLogger().warning("API Nexo introuvable : " + error);
        }
    }

    @SuppressWarnings("unchecked")
    private void registerReloadListener(ClassLoader loader) throws ClassNotFoundException {
        Class<? extends Event> loaded = (Class<? extends Event>) Class.forName("com.nexomc.nexo.api.events.NexoItemsLoadedEvent", true, loader);
        Bukkit.getPluginManager().registerEvent(loaded, new Listener() { }, EventPriority.MONITOR,
            (listener, event) -> plugin.onExternalItemsLoaded(), plugin);
    }

    public boolean available() {
        return itemFromId != null;
    }

    public ItemStack item(String id) {
        if (!available()) {
            return null;
        }
        try {
            Object builder = itemFromId.invoke(null, id);
            if (builder == null) {
                return null;
            }
            if (build == null) {
                build = builder.getClass().getMethod("build");
            }
            return (ItemStack) build.invoke(builder);
        } catch (ReflectiveOperationException error) {
            plugin.getLogger().warning("Item Nexo '" + id + "' illisible : " + error);
            return null;
        }
    }

    public String id(ItemStack stack) {
        if (!available() || stack == null) {
            return null;
        }
        try {
            return (String) idFromItem.invoke(null, stack);
        } catch (ReflectiveOperationException error) {
            return null;
        }
    }

    public boolean place(String id, Location location) {
        if (!available()) {
            return false;
        }
        try {
            placeBlock.invoke(null, id, location);
            return true;
        } catch (ReflectiveOperationException error) {
            plugin.getLogger().warning("Bloc Nexo '" + id + "' impossible a poser : " + error);
            return false;
        }
    }
}
