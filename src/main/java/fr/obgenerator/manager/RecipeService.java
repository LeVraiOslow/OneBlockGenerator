package fr.obgenerator.manager;

import fr.obgenerator.ObGenerator;
import fr.obgenerator.item.ItemSpec;
import fr.obgenerator.model.GeneratorType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.HumanEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;

/**
 * Une recette Bukkit par motif de matériaux (huit spawners zombie et huit spawners squelette ont le même motif).
 * Le vrai générateur est choisi dans PrepareItemCraftEvent en vérifiant le Moteur, l'id Nexo et le mob du spawner.
 */
public final class RecipeService implements Listener {

    private final ObGenerator plugin;
    private final List<NamespacedKey> registered = new ArrayList<>();
    private final Map<NamespacedKey, List<GeneratorType>> candidates = new HashMap<>();
    private String lastSignature = null;

    public RecipeService(ObGenerator plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        Bukkit.getGlobalRegionScheduler().execute(plugin, this::register);
    }

    private void register() {
        String missing = plugin.items().missingEngineItem();
        if (missing != null) {
            plugin.getLogger().warning("Item Nexo du Moteur introuvable : '" + missing + "'. Le Moteur de secours est utilise.");
        }
        // Une seule recette Bukkit par motif (forme + symboles utilises). Les materiaux de tous les generateurs
        // du meme motif sont regroupes dans un MaterialChoice ; le vrai generateur est tranche dans onPrepare.
        // Chaque Bukkit.addRecipe() recharge recettes et advancements de tous les joueurs : il faut en faire peu.
        Map<String, Map<Character, Set<Material>>> groupMaterials = new TreeMap<>();
        Map<String, GeneratorType> groupResult = new HashMap<>();
        Map<String, List<GeneratorType>> groupTypes = new HashMap<>();
        for (GeneratorType type : plugin.settings().types().values()) {
            if (type.ingredients().isEmpty()) {
                continue;
            }
            Map<Character, Material> materials = new TreeMap<>();
            boolean valid = true;
            for (Map.Entry<Character, ItemSpec> ingredient : type.ingredients().entrySet()) {
                Material material = ingredient.getValue().baseMaterial();
                if (material == null) {
                    plugin.getLogger().warning("Recette de '" + type.id() + "' ignoree : item introuvable " + ingredient.getValue().key());
                    valid = false;
                    break;
                }
                materials.put(ingredient.getKey(), material);
            }
            if (!valid) {
                continue;
            }
            String group = String.join("/", type.shape()) + materials.keySet();
            Map<Character, Set<Material>> choices = groupMaterials.computeIfAbsent(group, ignored -> new TreeMap<>());
            materials.forEach((symbol, material) -> choices.computeIfAbsent(symbol, ignored -> new TreeSet<>()).add(material));
            groupResult.putIfAbsent(group, type);
            groupTypes.computeIfAbsent(group, ignored -> new ArrayList<>()).add(type);
        }

        // Cles stables (ordre trie) : si rien n'a change, on ne touche pas aux recettes.
        Map<String, NamespacedKey> keys = new LinkedHashMap<>();
        StringBuilder signature = new StringBuilder();
        for (Map.Entry<String, Map<Character, Set<Material>>> group : groupMaterials.entrySet()) {
            keys.put(group.getKey(), new NamespacedKey(plugin, "generator_" + keys.size()));
            signature.append(group.getKey()).append('=').append(group.getValue()).append(';');
        }
        boolean changed = !signature.toString().equals(lastSignature);

        if (changed) {
            registered.forEach(key -> Bukkit.removeRecipe(key, false));
            registered.clear();
            for (Map.Entry<String, Map<Character, Set<Material>>> group : groupMaterials.entrySet()) {
                GeneratorType type = groupResult.get(group.getKey());
                NamespacedKey key = keys.get(group.getKey());
                ShapedRecipe recipe = new ShapedRecipe(key, plugin.items().generator(type, 1, false, ""));
                recipe.shape(type.shape());
                group.getValue().forEach((symbol, set) ->
                    recipe.setIngredient(symbol, new RecipeChoice.MaterialChoice(set.toArray(Material[]::new))));
                Bukkit.addRecipe(recipe, false);
                registered.add(key);
            }
            lastSignature = signature.toString();
        }

        candidates.clear();
        groupTypes.forEach((group, types) -> candidates.put(keys.get(group), types));
        if (changed) {
            Bukkit.updateRecipes();
        }
        plugin.getLogger().info(candidates.values().stream().mapToInt(List::size).sum() + " recettes de generateurs actives ("
            + registered.size() + " recettes Bukkit" + (changed ? "" : ", inchangees") + ").");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepare(PrepareItemCraftEvent event) {
        CraftingInventory inventory = event.getInventory();
        Recipe recipe = event.getRecipe();
        List<GeneratorType> types = recipe instanceof Keyed keyed ? candidates.get(keyed.getKey()) : null;
        if (types == null) {
            if (recipe != null && containsProtectedItem(inventory.getMatrix())) {
                inventory.setResult(null);
            }
            return;
        }
        HumanEntity crafter = event.getView().getPlayer();
        ItemStack result = null;
        if (crafter.hasPermission("obgenerator.craft")) {
            for (GeneratorType type : types) {
                if ((type.permission() == null || crafter.hasPermission(type.permission())) && matches(type, inventory.getMatrix())) {
                    result = plugin.items().generator(type, 1, false, "");
                    break;
                }
            }
        }
        inventory.setResult(result);
    }

    /** Un générateur en pierre ne doit pas servir à fabriquer des escaliers. */
    private boolean containsProtectedItem(ItemStack[] matrix) {
        for (ItemStack stack : matrix) {
            if (plugin.items().isGenerator(stack) || plugin.items().isEngine(stack)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(GeneratorType type, ItemStack[] matrix) {
        int side = (int) Math.round(Math.sqrt(matrix.length));
        int top = side;
        int bottom = -1;
        int left = side;
        int right = -1;
        for (int row = 0; row < side; row++) {
            for (int column = 0; column < side; column++) {
                ItemStack stack = matrix[row * side + column];
                if (stack != null && !stack.getType().isAir()) {
                    top = Math.min(top, row);
                    bottom = Math.max(bottom, row);
                    left = Math.min(left, column);
                    right = Math.max(right, column);
                }
            }
        }
        String[] shape = type.shape();
        int height = bottom - top + 1;
        int width = right - left + 1;
        if (height != shape.length || width != shape[0].length()) {
            return false;
        }
        return matches(type, matrix, side, top, left, false) || matches(type, matrix, side, top, left, true);
    }

    private static boolean matches(GeneratorType type, ItemStack[] matrix, int side, int top, int left, boolean mirrored) {
        String[] shape = type.shape();
        for (int row = 0; row < shape.length; row++) {
            int width = shape[row].length();
            for (int column = 0; column < width; column++) {
                char symbol = shape[row].charAt(mirrored ? width - 1 - column : column);
                ItemStack stack = matrix[(top + row) * side + left + column];
                ItemSpec spec = type.ingredients().get(symbol);
                boolean empty = stack == null || stack.getType().isAir();
                if (spec == null ? !empty : !spec.matches(stack)) {
                    return false;
                }
            }
        }
        return true;
    }
}
