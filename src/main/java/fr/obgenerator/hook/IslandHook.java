package fr.obgenerator.hook;

import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Player;

public interface IslandHook {

    /** @return l'identifiant de l'île à cet endroit, ou null hors d'une île de joueur */
    UUID islandAt(Location location);

    boolean isMember(Player player, Location location);
}
