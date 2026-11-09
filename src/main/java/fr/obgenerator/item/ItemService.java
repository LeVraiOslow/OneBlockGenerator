package fr.obgenerator.item;

import fr.obgenerator.ObGenerator;
import fr.obgenerator.model.GeneratorType;
import fr.obgenerator.util.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/** Création et reconnaissance du Moteur et des items de générateur. */
public final class ItemService {

    private final ObGenerator plugin;
    private final NamespacedKey typeKey;
    private final NamespacedKey levelKey;
    private final NamespacedKey unlockedKey;
    private final NamespacedKey depositsKey;
    private final NamespacedKey engineKey;
    private ItemSpec engineNexo;
    private ConfigurationSection engineFallback;

    public ItemService(ObGenerator plugin) {
        this.plugin = plugin;
        typeKey = new NamespacedKey(plugin, "type");
        levelKey = new NamespacedKey(plugin, "level");
        unlockedKey = new NamespacedKey(plugin, "unlocked");
        depositsKey = new NamespacedKey(plugin, "deposits");
        engineKey = new NamespacedKey(plugin, "engine");
    }

    public void load(ConfigurationSection engine) {
        ItemSpec spec = ItemSpec.parse(plugin, engine.getString("item", ""));
        engineNexo = spec != null && spec.isNexo() ? spec : null;
        engineFallback = engine.getConfigurationSection("fallback");
    }

    /** @return l'identifiant Nexo configuré s'il n'existe pas (encore), sinon null */
    public String missingEngineItem() {
        return engineNexo != null && plugin.nexo().available() && plugin.nexo().item(engineNexo.nexoId()) == null ? engineNexo.nexoId() : null;
    }

    public ItemStack engine() {
        if (engineNexo != null) {
            ItemStack nexo = engineNexo.build(1);
            if (nexo != null) {
                return nexo;
            }
        }
        ItemStack stack = new ItemStack(Material.HEAVY_CORE);
        if (engineFallback != null) {
            Material material = Material.matchMaterial(engineFallback.getString("material", "HEAVY_CORE"));
            stack = new ItemStack(material == null ? Material.HEAVY_CORE : material);
        }
        stack.editMeta(meta -> {
            meta.displayName(Text.item(engineFallback == null ? "&6Moteur" : engineFallback.getString("name", "&6Moteur")));
            if (engineFallback != null) {
                meta.lore(engineFallback.getStringList("lore").stream().map(Text::item).toList());
            }
            meta.getPersistentDataContainer().set(engineKey, PersistentDataType.BOOLEAN, true);
        });
        return stack;
    }

    public boolean isEngine(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return false;
        }
        if (engineNexo != null && engineNexo.nexoId().equals(plugin.nexo().id(stack))) {
            return true;
        }
        return stack.hasItemMeta() && stack.getItemMeta().getPersistentDataContainer().has(engineKey);
    }

    public ItemStack generator(GeneratorType type, int level, boolean unlocked, String deposits) {
        ItemStack stack = type.item().build(1);
        if (stack == null) {
            stack = new ItemStack(Material.LODESTONE);
        }
        Map<String, String> placeholders = Map.of(
            "generator", type.name(),
            "level", String.valueOf(level),
            "max_level", String.valueOf(type.maxLevel()),
            "state", plugin.settings().raw(unlocked ? "item-state.unlocked" : "item-state.locked"),
            "price", plugin.settings().money(type.unlockPrice()));
        String displayName = Text.apply(plugin.settings().itemName(), placeholders);
        List<String> lore = type.lore().isEmpty() ? plugin.settings().itemLore() : type.lore();
        List<net.kyori.adventure.text.Component> lines = new ArrayList<>();
        for (String line : lore) {
            lines.add(Text.item(Text.apply(line, placeholders)));
        }
        stack.editMeta(meta -> {
            meta.displayName(Text.item(displayName));
            meta.lore(lines);
            meta.setEnchantmentGlintOverride(true);
            PersistentDataContainer data = meta.getPersistentDataContainer();
            data.set(typeKey, PersistentDataType.STRING, type.id());
            data.set(levelKey, PersistentDataType.INTEGER, level);
            data.set(unlockedKey, PersistentDataType.BOOLEAN, unlocked);
            if (deposits != null && !deposits.isEmpty()) {
                data.set(depositsKey, PersistentDataType.STRING, deposits);
            }
        });
        return stack;
    }

    public boolean isGenerator(ItemStack stack) {
        return typeId(stack) != null;
    }

    public String typeId(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return null;
        }
        return stack.getItemMeta().getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
    }

    public int level(ItemStack stack) {
        return stack.getItemMeta().getPersistentDataContainer().getOrDefault(levelKey, PersistentDataType.INTEGER, 1);
    }

    public boolean unlocked(ItemStack stack) {
        return stack.getItemMeta().getPersistentDataContainer().getOrDefault(unlockedKey, PersistentDataType.BOOLEAN, false);
    }

    public String deposits(ItemStack stack) {
        return stack.getItemMeta().getPersistentDataContainer().getOrDefault(depositsKey, PersistentDataType.STRING, "");
    }
}
