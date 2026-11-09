package fr.obgenerator.model;

import fr.obgenerator.item.ItemSpec;
import java.util.List;
import java.util.Map;

/** Définition d'un générateur chargée depuis generators/*.yml. */
public record GeneratorType(
    String id,
    String name,
    List<String> lore,
    ItemSpec item,
    ItemSpec block,
    String[] shape,
    Map<Character, ItemSpec> ingredients,
    double unlockPrice,
    List<LevelDef> levels,
    String menu,
    String permission,
    boolean dropItems,
    Map<String, List<String>> hologram
) {

    public int maxLevel() {
        return levels.size();
    }

    public LevelDef level(int level) {
        return levels.get(Math.clamp(level, 1, levels.size()) - 1);
    }

    /** Exigences pour passer du niveau actuel au suivant. */
    public LevelDef next(int level) {
        return level >= maxLevel() ? null : levels.get(level);
    }
}
