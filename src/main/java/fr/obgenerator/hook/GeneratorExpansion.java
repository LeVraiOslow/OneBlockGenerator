package fr.obgenerator.hook;

import fr.obgenerator.ObGenerator;
import fr.obgenerator.model.Generator;
import java.util.List;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

/** %obgenerator_placed%, %obgenerator_unlocked%, %obgenerator_levels%, %obgenerator_max_level_count%. */
public final class GeneratorExpansion extends PlaceholderExpansion {

    private final ObGenerator plugin;

    public GeneratorExpansion(ObGenerator plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "obgenerator";
    }

    @Override
    public String getAuthor() {
        return "ObGenerator";
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (player == null) {
            return "";
        }
        List<Generator> owned = plugin.generators().ownedBy(player.getUniqueId());
        return switch (params) {
            case "placed" -> String.valueOf(owned.size());
            case "unlocked" -> String.valueOf(owned.stream().filter(Generator::unlocked).count());
            case "levels" -> String.valueOf(owned.stream().mapToInt(Generator::level).sum());
            case "max_level_count" -> String.valueOf(owned.stream()
                .filter(generator -> generator.type() != null && generator.level() >= generator.type().maxLevel()).count());
            default -> null;
        };
    }

    public static String apply(org.bukkit.entity.Player player, String text) {
        return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, text);
    }
}
