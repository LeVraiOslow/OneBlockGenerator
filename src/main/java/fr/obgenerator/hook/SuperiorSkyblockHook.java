package fr.obgenerator.hook;

import com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI;
import com.bgsoftware.superiorskyblock.api.events.IslandDisbandEvent;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import fr.obgenerator.ObGenerator;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/** Chargée uniquement si SuperiorSkyblock2 est présent. */
public final class SuperiorSkyblockHook implements IslandHook, Listener {

    private final ObGenerator plugin;

    public SuperiorSkyblockHook(ObGenerator plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("SuperiorSkyblock2 detecte.");
    }

    @Override
    public UUID islandAt(Location location) {
        Island island = SuperiorSkyblockAPI.getIslandAt(location);
        return island == null || island.isSpawn() ? null : island.getUniqueId();
    }

    @Override
    public boolean isMember(Player player, Location location) {
        Island island = SuperiorSkyblockAPI.getIslandAt(location);
        if (island == null) {
            return false;
        }
        SuperiorPlayer superiorPlayer = SuperiorSkyblockAPI.getPlayer(player);
        return island.isMember(superiorPlayer) || island.isCoop(superiorPlayer);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDisband(IslandDisbandEvent event) {
        plugin.generators().removeIsland(event.getIsland().getUniqueId());
    }
}
