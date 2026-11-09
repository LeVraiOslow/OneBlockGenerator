package fr.obgenerator.model;

import fr.obgenerator.item.ItemSpec;
import java.util.List;
import java.util.Map;
import org.bukkit.entity.EntityType;

/** Production d'un niveau, et ce qu'il faut pour atteindre ce niveau depuis le précédent. */
public record LevelDef(
    int level,
    long intervalMillis,
    long capacity,
    Map<ItemSpec, Integer> items,
    double money,
    long xp,
    Map<EntityType, Integer> mobs,
    List<String> commands,
    long upgradeMillis,
    List<Requirement> requirements
) {

    public long itemsPerCycle() {
        long total = 0;
        for (int amount : items.values()) {
            total += amount;
        }
        return total;
    }
}
