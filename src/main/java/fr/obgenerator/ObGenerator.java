package fr.obgenerator;

import fr.obgenerator.command.GeneratorCommand;
import fr.obgenerator.hook.EssentialsHook;
import fr.obgenerator.hook.GeneratorExpansion;
import fr.obgenerator.hook.IslandHook;
import fr.obgenerator.hook.NexoHook;
import fr.obgenerator.hook.RoseStackerHook;
import fr.obgenerator.hook.SuperiorSkyblockHook;
import fr.obgenerator.hook.VaultHook;
import fr.obgenerator.item.ItemService;
import fr.obgenerator.listener.GeneratorListener;
import fr.obgenerator.manager.GeneratorManager;
import fr.obgenerator.manager.RecipeService;
import fr.obgenerator.manager.Settings;
import fr.obgenerator.menu.MenuService;
import fr.obgenerator.storage.Storage;
import fr.obgenerator.util.Text;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class ObGenerator extends JavaPlugin {

    private final ItemService items = new ItemService(this);
    private final VaultHook vault = new VaultHook();
    private final Settings settings = new Settings(this);
    private final Storage storage = new Storage(this);
    private final RecipeService recipes = new RecipeService(this);
    private final MenuService menus = new MenuService(this);
    private GeneratorManager generators;
    private NexoHook nexo;
    private EssentialsHook essentials;
    private RoseStackerHook roseStacker;
    private IslandHook island;

    @Override
    public void onEnable() {
        nexo = new NexoHook(this);
        essentials = new EssentialsHook(this);
        roseStacker = new RoseStackerHook(this);
        settings.load();
        ConfigurationSection database = settings.config().getConfigurationSection("storage");
        try {
            storage.open(database == null ? new MemoryConfiguration() : database);
        } catch (SQLException error) {
            getLogger().severe("Base de donnees inaccessible, plugin desactive : " + error.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        generators = new GeneratorManager(this, storage);
        generators.loadAll();

        if (Bukkit.getPluginManager().isPluginEnabled("SuperiorSkyblock2")) {
            island = new SuperiorSkyblockHook(this);
        }
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new GeneratorExpansion(this).register();
        }
        if (!vault.available()) {
            getLogger().warning("Aucune economie Vault detectee pour l'instant : deverrouillage et monnaie indisponibles.");
        }
        getServer().getPluginManager().registerEvents(new GeneratorListener(this), this);
        getServer().getPluginManager().registerEvents(recipes, this);
        getServer().getPluginManager().registerEvents(menus, this);

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            GeneratorCommand command = new GeneratorCommand(this);
            event.registrar().register(command.build(), "Gestion des generateurs", command.aliases());
        });
        long autosave = Math.max(10, settings.config().getLong("storage.autosave-seconds", 60));
        Bukkit.getAsyncScheduler().runAtFixedRate(this, task -> generators.saveDirty(), autosave, autosave, TimeUnit.SECONDS);
    }

    @Override
    public void onDisable() {
        Bukkit.getAsyncScheduler().cancelTasks(this);
        Bukkit.getGlobalRegionScheduler().cancelTasks(this);
        if (generators != null) {
            generators.removeDisplays();
            generators.saveDirty();
        }
        storage.close();
    }

    public void reloadAll() {
        settings.load();
        generators.rebindTypes();
        recipes.reload();
    }

    /** Nexo charge ses items après le démarrage et à chaque /nexo reload. */
    public void onExternalItemsLoaded() {
        Bukkit.getGlobalRegionScheduler().execute(this, this::reloadAll);
    }

    public void send(Player player, String key, Map<String, String> placeholders) {
        if (!settings.raw(key).isBlank()) {
            player.sendMessage(Text.parse(settings.prefixed(key, placeholders)));
        }
    }

    public void playSound(Player player, String key) {
        String sound = settings.sound(key);
        if (!sound.isBlank()) {
            player.playSound(player.getLocation(), sound, 1f, 1f);
        }
    }

    public ItemService items() { return items; }
    public Settings settings() { return settings; }
    public GeneratorManager generators() { return generators; }
    public RecipeService recipes() { return recipes; }
    public MenuService menus() { return menus; }
    public VaultHook vault() { return vault; }
    public NexoHook nexo() { return nexo; }
    public EssentialsHook essentials() { return essentials; }
    public RoseStackerHook roseStacker() { return roseStacker; }
    public IslandHook island() { return island; }
}
