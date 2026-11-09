package fr.obgenerator.item;

import fr.obgenerator.ObGenerator;
import fr.obgenerator.util.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;

/** Item d'affichage configurable : material (vanilla ou nexo:id), name, lore, amount, glow, item-model, custom-model-data. */
public final class MenuItem {

    private final ObGenerator plugin;
    private final String material;
    private final String name;
    private final List<String> lore;
    private final int amount;
    private final boolean glow;
    private final int customModelData;
    private final String itemModel;

    private MenuItem(ObGenerator plugin, ConfigurationSection section, String defaultMaterial) {
        this.plugin = plugin;
        this.material = section.getString("material", section.getString("item", defaultMaterial));
        this.name = section.getString("name");
        this.lore = section.getStringList("lore");
        this.amount = Math.max(1, section.getInt("amount", 1));
        this.glow = section.getBoolean("glow", false);
        this.customModelData = section.getInt("custom-model-data", 0);
        this.itemModel = section.getString("item-model");
    }

    public static MenuItem from(ObGenerator plugin, ConfigurationSection section, String defaultMaterial) {
        return section == null ? null : new MenuItem(plugin, section, defaultMaterial);
    }

    public ItemStack build(Map<String, String> placeholders, Map<String, List<String>> blocks, ItemSpec dynamicItem) {
        ItemStack stack = resolveBase(placeholders, dynamicItem);
        stack.setAmount(Math.min(amount, stack.getMaxStackSize()));
        stack.editMeta(meta -> {
            if (name != null) {
                meta.displayName(Text.item(Text.apply(name, placeholders)));
            }
            if (!lore.isEmpty()) {
                List<net.kyori.adventure.text.Component> lines = new ArrayList<>();
                for (String line : Text.apply(lore, placeholders, blocks)) {
                    lines.add(Text.item(line));
                }
                meta.lore(lines);
            }
            if (glow) {
                meta.setEnchantmentGlintOverride(true);
            }
            if (customModelData != 0) {
                meta.setCustomModelData(customModelData);
            }
            if (itemModel != null && !itemModel.isBlank()) {
                NamespacedKey key = NamespacedKey.fromString(itemModel);
                if (key != null) {
                    meta.setItemModel(key);
                }
            }
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        });
        return stack;
    }

    private ItemStack resolveBase(Map<String, String> placeholders, ItemSpec dynamicItem) {
        if ("{item}".equalsIgnoreCase(material) && dynamicItem != null) {
            ItemStack dynamic = dynamicItem.build(1);
            if (dynamic != null) {
                return dynamic;
            }
        }
        ItemSpec spec = ItemSpec.parse(plugin, Text.apply(material, placeholders));
        ItemStack stack = spec == null ? null : spec.build(1);
        return stack == null ? new ItemStack(Material.BARRIER) : stack;
    }
}
