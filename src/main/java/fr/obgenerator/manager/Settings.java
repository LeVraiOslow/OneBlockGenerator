package fr.obgenerator.manager;

import fr.obgenerator.ObGenerator;
import fr.obgenerator.item.ItemSpec;
import fr.obgenerator.model.GeneratorType;
import fr.obgenerator.model.LevelDef;
import fr.obgenerator.model.Requirement;
import fr.obgenerator.util.Durations;
import fr.obgenerator.util.Text;
import java.io.File;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Statistic;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;

/** Toute la configuration, relue d'un bloc par /generateur reload. */
public final class Settings {

    private static final String[] DEFAULT_GENERATORS = {"blocs", "minerais", "agriculture", "mobs", "divers"};

    private final ObGenerator plugin;
    private FileConfiguration config;
    private FileConfiguration messages;
    private FileConfiguration menus;
    private Map<String, GeneratorType> types = new LinkedHashMap<>();
    private DecimalFormat moneyFormat;
    private DecimalFormat numberFormat;

    public Settings(ObGenerator plugin) {
        this.plugin = plugin;
    }

    public void load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        config = plugin.getConfig();
        messages = yaml("messages.yml");
        menus = yaml("menus.yml");
        FileConfiguration profiles = yaml("profiles.yml");
        moneyFormat = new DecimalFormat(config.getString("format.money", "#,##0.##"), DecimalFormatSymbols.getInstance(Locale.FRANCE));
        numberFormat = new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.FRANCE));
        plugin.items().load(section(config, "engine"));

        File folder = new File(plugin.getDataFolder(), "generators");
        if (!folder.exists()) {
            for (String name : DEFAULT_GENERATORS) {
                plugin.saveResource("generators/" + name + ".yml", false);
            }
        }
        Map<String, GeneratorType> loaded = new LinkedHashMap<>();
        File[] files = folder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files != null) {
            Arrays.sort(files, Comparator.comparing(File::getName));
            for (File file : files) {
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
                for (String id : yaml.getKeys(false)) {
                    try {
                        GeneratorType type = parseType(id, section(yaml, id), profiles);
                        if (loaded.put(id, type) != null) {
                            plugin.getLogger().warning("Generateur '" + id + "' defini deux fois : " + file.getName() + " l'emporte.");
                        }
                    } catch (IllegalArgumentException invalid) {
                        plugin.getLogger().warning(file.getName() + " > " + id + " ignore : " + invalid.getMessage());
                    }
                }
            }
        }
        types = loaded;
        plugin.getLogger().info(types.size() + " types de generateurs charges.");
    }

    private FileConfiguration yaml(String name) {
        File file = new File(plugin.getDataFolder(), name);
        if (!file.exists()) {
            plugin.saveResource(name, false);
        }
        return YamlConfiguration.loadConfiguration(file);
    }

    private static ConfigurationSection section(ConfigurationSection parent, String path) {
        ConfigurationSection child = parent.getConfigurationSection(path);
        return child == null ? new MemoryConfiguration() : child;
    }

    private GeneratorType parseType(String id, ConfigurationSection section, FileConfiguration profiles) {
        String ingredient = section.getString("ingredient");
        ConfigurationSection production = section(section, "production");
        Map<String, Integer> baseItems = amounts(section(production, "items"));
        String firstOutput = baseItems.isEmpty() ? ingredient : baseItems.keySet().iterator().next();
        String product = section.getString("upgrade-item", firstOutput);
        ConfigurationSection custom = section.getConfigurationSection("levels");
        String profile = section.getString("profile", "standard");
        ConfigurationSection profileLevels = profiles.getConfigurationSection("profiles." + profile + ".levels");
        if (profileLevels == null && custom == null) {
            throw new IllegalArgumentException("profil '" + profile + "' introuvable");
        }

        List<Integer> numbers = new ArrayList<>();
        for (ConfigurationSection source : new ConfigurationSection[] {profileLevels, custom}) {
            if (source != null) {
                source.getKeys(false).stream().map(Integer::parseInt).filter(n -> !numbers.contains(n)).forEach(numbers::add);
            }
        }
        numbers.sort(Comparator.naturalOrder());
        int total = section.getInt("max-level", numbers.size());
        double costMultiplier = section.getDouble("cost-multiplier", 1);
        List<LevelDef> levels = new ArrayList<>();
        for (int position = 0; position < Math.min(total, numbers.size()); position++) {
            int number = numbers.get(position);
            if (number != position + 1) {
                throw new IllegalArgumentException("niveaux non consecutifs (attendu " + (position + 1) + ", trouve " + number + ")");
            }
            ConfigurationSection merged = merge(
                profileLevels == null ? null : profileLevels.getConfigurationSection(String.valueOf(number)),
                custom == null ? null : custom.getConfigurationSection(String.valueOf(number)));
            levels.add(parseLevel(number, merged, production, baseItems, product, ingredient, costMultiplier));
        }
        if (levels.isEmpty()) {
            throw new IllegalArgumentException("aucun niveau");
        }

        ItemSpec block = ItemSpec.parse(plugin, section.getString("block", firstOutput));
        if (block == null) {
            throw new IllegalArgumentException("block invalide");
        }
        ItemSpec item = ItemSpec.parse(plugin, section.getString("item", block.key()));
        if (item == null) {
            throw new IllegalArgumentException("item invalide");
        }
        String[] shape;
        Map<Character, ItemSpec> ingredients = new HashMap<>();
        ConfigurationSection recipe = section.getConfigurationSection("recipe");
        if (recipe != null) {
            shape = recipe.getStringList("shape").toArray(String[]::new);
            ConfigurationSection keys = section(recipe, "ingredients");
            for (String key : keys.getKeys(false)) {
                ItemSpec spec = ItemSpec.parse(plugin, keys.getString(key));
                if (spec == null || key.length() != 1) {
                    throw new IllegalArgumentException("ingredient de recette invalide : " + key);
                }
                ingredients.put(key.charAt(0), spec);
            }
        } else if (ingredient != null) {
            shape = new String[] {"AAA", "AMA", "AAA"};
            ItemSpec ring = ItemSpec.parse(plugin, ingredient);
            if (ring == null) {
                throw new IllegalArgumentException("ingredient invalide : " + ingredient);
            }
            ingredients.put('A', ring);
            ingredients.put('M', ItemSpec.parse(plugin, "engine"));
        } else {
            shape = new String[0];
        }
        Map<String, List<String>> hologram = new HashMap<>();
        ConfigurationSection lines = section.getConfigurationSection("hologram");
        if (lines != null) {
            lines.getKeys(false).forEach(state -> hologram.put(state, lines.getStringList(state)));
        }
        return new GeneratorType(id, section.getString("name", id), section.getStringList("lore"), item, block, shape, ingredients,
            section.getDouble("unlock-price", 1000), List.copyOf(levels), section.getString("menu", "default"),
            section.getString("permission"), "DROP".equalsIgnoreCase(production.getString("delivery", "STORAGE")), hologram);
    }

    private LevelDef parseLevel(int number, ConfigurationSection level, ConfigurationSection production, Map<String, Integer> baseItems,
                                String product, String ingredient, double costMultiplier) {
        double amount = level.getDouble("amount", 1);
        Map<ItemSpec, Integer> items = new LinkedHashMap<>();
        Map<String, Integer> explicit = amounts(section(level, "items"));
        (explicit.isEmpty() ? scaled(baseItems, amount) : explicit).forEach((key, value) -> {
            ItemSpec spec = ItemSpec.parse(plugin, key);
            if (spec == null) {
                throw new IllegalArgumentException("item produit invalide : " + key);
            }
            items.put(spec, value);
        });
        Map<EntityType, Integer> mobs = new LinkedHashMap<>();
        scaled(amounts(section(production, "mobs")), amount).forEach((key, value) -> {
            try {
                mobs.put(EntityType.valueOf(key.toUpperCase(Locale.ROOT)), value);
            } catch (IllegalArgumentException unknown) {
                throw new IllegalArgumentException("mob invalide : " + key);
            }
        });
        double money = level.contains("money-value") ? level.getDouble("money-value")
            : production.getDouble("money", 0) * level.getDouble("money", 1);
        long xp = Math.round(production.getDouble("xp", 0) * level.getDouble("xp", 1));
        List<Requirement> requirements = new ArrayList<>();
        ConfigurationSection upgrade = section(level, "upgrade");
        ConfigurationSection needs = section(upgrade, "requirements");
        for (String key : needs.getKeys(false)) {
            requirements.add(parseRequirement(key, section(needs, key), product, ingredient, costMultiplier));
        }
        return new LevelDef(number, Math.max(1000, Durations.parse(level.getString("interval"), 60_000)),
            Math.max(1, level.getLong("capacity", 256)), items, money, xp, mobs, production.getStringList("commands"),
            Durations.parse(upgrade.getString("duration"), 0), List.copyOf(requirements));
    }

    private Requirement parseRequirement(String key, ConfigurationSection section, String product, String ingredient, double multiplier) {
        Requirement.Type type;
        try {
            type = Requirement.Type.valueOf(section.getString("type", "ITEM").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("type d'exigence invalide : " + key);
        }
        ItemSpec item = null;
        if (type == Requirement.Type.ITEM) {
            String value = section.getString("item", "{product}")
                .replace("{product}", String.valueOf(product)).replace("{ingredient}", String.valueOf(ingredient));
            item = ItemSpec.parse(plugin, value);
            if (item == null) {
                throw new IllegalArgumentException("item d'exigence invalide : " + value);
            }
        }
        Statistic statistic = null;
        if (type == Requirement.Type.STATISTIC) {
            try {
                statistic = Statistic.valueOf(section.getString("statistic", "").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                throw new IllegalArgumentException("statistique invalide : " + key);
            }
        }
        long amount;
        if (type == Requirement.Type.PLAYTIME) {
            amount = Durations.parse(section.getString("amount"), 0) / 1000;
        } else {
            double raw = section.getDouble("amount", 1);
            boolean scale = section.getBoolean("scale", type == Requirement.Type.MONEY);
            amount = Math.max(1, Math.round(scale ? raw * multiplier : raw));
        }
        return new Requirement(key, type, amount, section.getInt("slot", -1), item, statistic, section.getString("permission"),
            section.getString("placeholder"), section.getString("label"),
            fr.obgenerator.item.MenuItem.from(plugin, section.getConfigurationSection("icon"), "{item}"));
    }

    private static ConfigurationSection merge(ConfigurationSection base, ConfigurationSection override) {
        MemoryConfiguration merged = new MemoryConfiguration();
        for (ConfigurationSection source : new ConfigurationSection[] {base, override}) {
            if (source != null) {
                source.getValues(true).forEach((key, value) -> {
                    if (!(value instanceof ConfigurationSection)) {
                        merged.set(key, value);
                    }
                });
            }
        }
        return merged;
    }

    private static Map<String, Integer> amounts(ConfigurationSection section) {
        Map<String, Integer> result = new LinkedHashMap<>();
        section.getKeys(false).forEach(key -> result.put(key, section.getInt(key, 1)));
        return result;
    }

    private static Map<String, Integer> scaled(Map<String, Integer> values, double factor) {
        Map<String, Integer> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(key, Math.max(1, (int) Math.round(value * factor))));
        return result;
    }

    public Map<String, GeneratorType> types() { return types; }
    public GeneratorType type(String id) { return types.get(id); }
    public FileConfiguration config() { return config; }
    public ConfigurationSection menu(String id) {
        ConfigurationSection menu = menus.getConfigurationSection("menus." + id);
        return menu != null ? menu : section(menus, "menus.default");
    }
    public ConfigurationSection menusRoot() { return menus; }

    public String raw(String key) {
        return messages.getString(key, key);
    }

    public List<String> rawList(String key) {
        return messages.getStringList(key);
    }

    public String prefixed(String key, Map<String, String> placeholders) {
        return Text.apply(messages.getString("prefix", "") + raw(key), placeholders);
    }

    public String itemName() {
        return config.getString("item.name", "{generator} &7(Niv. {level})");
    }

    public List<String> itemLore() {
        return config.getStringList("item.lore");
    }

    public String money(double amount) {
        return Text.apply(config.getString("format.currency", "{amount}$"), Map.of("amount", format(moneyFormat, amount)));
    }

    public String number(long amount) {
        return format(numberFormat, amount);
    }

    /** DecimalFormat n'est pas thread-safe et plusieurs régions Folia l'utilisent. */
    private static String format(DecimalFormat format, double value) {
        synchronized (format) {
            return format.format(value);
        }
    }

    public String bar(double ratio) {
        int size = config.getInt("format.bar.size", 20);
        int filled = (int) Math.round(Math.clamp(ratio, 0, 1) * size);
        String full = config.getString("format.bar.filled", "&a|");
        String empty = config.getString("format.bar.empty", "&8|");
        return full.repeat(filled) + empty.repeat(size - filled);
    }

    /** Identifiant de son (entity.player.levelup), vide pour aucun son. */
    public String sound(String key) {
        return config.getString("sounds." + key, "");
    }
}
