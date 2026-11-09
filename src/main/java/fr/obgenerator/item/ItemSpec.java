package fr.obgenerator.item;

import fr.obgenerator.ObGenerator;
import java.util.Locale;
import org.bukkit.Material;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Référence d'item dans la configuration :
 * STONE, nexo:mon_item, spawner:ZOMBIE, ou engine (le Moteur).
 */
public final class ItemSpec {

    private enum Kind { VANILLA, NEXO, SPAWNER, ENGINE }

    private final ObGenerator plugin;
    private final Kind kind;
    private final String key;
    private final Material material;
    private final String nexoId;
    private final EntityType entity;
    private ItemStack cache;

    private ItemSpec(ObGenerator plugin, Kind kind, String key, Material material, String nexoId, EntityType entity) {
        this.plugin = plugin;
        this.kind = kind;
        this.key = key;
        this.material = material;
        this.nexoId = nexoId;
        this.entity = entity;
    }

    /** @return la référence, ou null si elle est invalide */
    public static ItemSpec parse(ObGenerator plugin, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.equals("engine") || lower.equals("moteur")) {
            return new ItemSpec(plugin, Kind.ENGINE, "engine", null, null, null);
        }
        if (lower.startsWith("nexo:")) {
            return new ItemSpec(plugin, Kind.NEXO, "nexo:" + text.substring(5), null, text.substring(5), null);
        }
        if (lower.startsWith("spawner:")) {
            try {
                EntityType type = EntityType.valueOf(text.substring(8).toUpperCase(Locale.ROOT));
                return new ItemSpec(plugin, Kind.SPAWNER, "spawner:" + type.name(), Material.SPAWNER, null, type);
            } catch (IllegalArgumentException unknown) {
                return null;
            }
        }
        Material found = Material.matchMaterial(text);
        return found == null || !found.isItem() ? null : new ItemSpec(plugin, Kind.VANILLA, found.name(), found, null, null);
    }

    public String key() {
        return key;
    }

    public boolean isNexo() {
        return kind == Kind.NEXO;
    }

    public String nexoId() {
        return nexoId;
    }

    public Material vanillaMaterial() {
        return material;
    }

    /** Matériau réel : indispensable pour déclarer une recette. */
    public Material baseMaterial() {
        ItemStack stack = build(1);
        return stack == null ? null : stack.getType();
    }

    public int maxStack() {
        ItemStack stack = build(1);
        return stack == null ? 64 : stack.getMaxStackSize();
    }

    /** Nom affichable : traduit par le client pour les items vanilla. */
    public String displayName() {
        return switch (kind) {
            case VANILLA -> "<lang:" + material.translationKey() + ">";
            case SPAWNER -> "<lang:block.minecraft.spawner> (<lang:" + entity.translationKey() + ">)";
            case ENGINE -> "<lang:block.minecraft.heavy_core>";
            case NEXO -> {
                ItemStack stack = build(1);
                yield stack != null && stack.hasItemMeta() && stack.getItemMeta().hasDisplayName()
                    ? net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().serialize(stack.getItemMeta().displayName())
                    : nexoId;
            }
        };
    }

    /** @return une copie de l'item, ou null si l'item Nexo n'existe pas */
    public ItemStack build(int amount) {
        if (cache == null) {
            cache = create();
        }
        if (cache == null) {
            return null;
        }
        ItemStack copy = cache.clone();
        copy.setAmount(amount);
        return copy;
    }

    private ItemStack create() {
        return switch (kind) {
            case VANILLA -> new ItemStack(material);
            case NEXO -> plugin.nexo().item(nexoId);
            case ENGINE -> plugin.items().engine();
            case SPAWNER -> {
                ItemStack stack = new ItemStack(Material.SPAWNER);
                if (stack.getItemMeta() instanceof BlockStateMeta meta && meta.getBlockState() instanceof CreatureSpawner spawner) {
                    spawner.setSpawnedType(entity);
                    meta.setBlockState(spawner);
                    stack.setItemMeta(meta);
                }
                yield stack;
            }
        };
    }

    public boolean matches(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return false;
        }
        return switch (kind) {
            case VANILLA -> stack.getType() == material && isPlain(stack);
            case NEXO -> nexoId.equals(plugin.nexo().id(stack));
            case ENGINE -> plugin.items().isEngine(stack);
            case SPAWNER -> stack.getType() == Material.SPAWNER && entity == spawnerType(stack) && !plugin.items().isGenerator(stack);
        };
    }

    /** Une pierre renommée, un générateur ou un item Nexo ne comptent pas comme de la pierre. */
    private boolean isPlain(ItemStack stack) {
        if (!stack.hasItemMeta()) {
            return true;
        }
        ItemMeta meta = stack.getItemMeta();
        return !meta.hasDisplayName() && !meta.hasLore() && meta.getPersistentDataContainer().isEmpty()
            && !plugin.items().isGenerator(stack) && plugin.nexo().id(stack) == null;
    }

    private static EntityType spawnerType(ItemStack stack) {
        if (stack.getItemMeta() instanceof BlockStateMeta meta && meta.hasBlockState()
            && meta.getBlockState() instanceof CreatureSpawner spawner) {
            return spawner.getSpawnedType();
        }
        return null;
    }

}
