package fr.obgenerator.model;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.entity.TextDisplay;

/**
 * Générateur posé. Sur Folia, le joueur et le chunk peuvent être sur deux threads :
 * toute lecture composée ou écriture se fait dans synchronized(generator).
 */
public final class Generator {

    private final UUID id;
    private final String typeId;
    private final String world;
    private final int x;
    private final int y;
    private final int z;
    private UUID owner;
    private String ownerName;
    private UUID island;
    private GeneratorType type;
    private volatile int level = 1;
    private volatile boolean unlocked;
    private long lastCycle;
    private long upgradeEndsAt;
    private final Map<String, Long> deposits = new HashMap<>();
    private final Map<String, Long> storedItems = new LinkedHashMap<>();
    private double storedMoney;
    private long storedXp;
    private long storedCycles;
    private boolean dirty = true;
    private boolean removed;
    private boolean active;
    private TextDisplay display;
    private String displayedText;

    public Generator(UUID id, String typeId, String world, int x, int y, int z) {
        this.id = id;
        this.typeId = typeId;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public UUID id() { return id; }
    public String typeId() { return typeId; }
    public String world() { return world; }
    public int x() { return x; }
    public int y() { return y; }
    public int z() { return z; }
    public UUID owner() { return owner; }
    public String ownerName() { return ownerName; }
    public UUID island() { return island; }
    public GeneratorType type() { return type; }
    public int level() { return level; }
    public boolean unlocked() { return unlocked; }
    public long lastCycle() { return lastCycle; }
    public long upgradeEndsAt() { return upgradeEndsAt; }
    public boolean upgrading() { return upgradeEndsAt > 0; }
    public Map<String, Long> deposits() { return deposits; }
    public Map<String, Long> storedItems() { return storedItems; }
    public double storedMoney() { return storedMoney; }
    public long storedXp() { return storedXp; }
    public long storedCycles() { return storedCycles; }
    public boolean dirty() { return dirty; }
    public boolean removed() { return removed; }
    public boolean active() { return active; }
    public TextDisplay display() { return display; }
    public String displayedText() { return displayedText; }

    public void setOwner(UUID owner, String ownerName) { this.owner = owner; this.ownerName = ownerName; dirty = true; }
    public void setIsland(UUID island) { this.island = island; dirty = true; }
    public void setType(GeneratorType type) { this.type = type; }
    public void setLevel(int level) { this.level = level; dirty = true; }
    public void setUnlocked(boolean unlocked) { this.unlocked = unlocked; dirty = true; }
    public void setLastCycle(long lastCycle) { this.lastCycle = lastCycle; dirty = true; }
    public void setUpgradeEndsAt(long upgradeEndsAt) { this.upgradeEndsAt = upgradeEndsAt; dirty = true; }
    public void setStoredMoney(double storedMoney) { this.storedMoney = storedMoney; dirty = true; }
    public void setStoredXp(long storedXp) { this.storedXp = storedXp; dirty = true; }
    public void setStoredCycles(long storedCycles) { this.storedCycles = storedCycles; dirty = true; }
    public void setDirty(boolean dirty) { this.dirty = dirty; }
    public void setRemoved() { this.removed = true; }
    public void setActive(boolean active) { this.active = active; }
    public void setDisplay(TextDisplay display, String text) { this.display = display; this.displayedText = text; }

    public long storedItemCount() {
        long total = 0;
        for (long amount : storedItems.values()) {
            total += amount;
        }
        return total;
    }

    /** Dépôts sous forme texte, réutilisée par la base et par l'item ramassé. */
    public String depositsText() {
        return encode(deposits);
    }

    public static String encode(Map<String, Long> values) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Long> entry : values.entrySet()) {
            if (!out.isEmpty()) {
                out.append('|');
            }
            out.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return out.toString();
    }

    public static void decode(String text, Map<String, Long> into) {
        if (text == null || text.isEmpty()) {
            return;
        }
        for (String part : text.split("\\|")) {
            int separator = part.lastIndexOf('=');
            if (separator > 0) {
                try {
                    into.put(part.substring(0, separator), Long.parseLong(part.substring(separator + 1)));
                } catch (NumberFormatException ignored) {
                    // Entrée corrompue : on l'ignore plutôt que de perdre le générateur.
                }
            }
        }
    }
}
