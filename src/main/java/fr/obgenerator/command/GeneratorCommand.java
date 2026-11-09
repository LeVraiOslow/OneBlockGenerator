package fr.obgenerator.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import fr.obgenerator.ObGenerator;
import fr.obgenerator.model.Generator;
import fr.obgenerator.model.GeneratorType;
import fr.obgenerator.util.Text;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import java.util.List;
import java.util.Map;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** /generateur (alias /generator, /gen, /obgen), réservée aux administrateurs. */
public final class GeneratorCommand {

    private final ObGenerator plugin;

    public GeneratorCommand(ObGenerator plugin) {
        this.plugin = plugin;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("generateur")
            .requires(source -> source.getSender().hasPermission("obgenerator.admin"))
            .executes(this::help);
        root.then(Commands.literal("reload").executes(context -> {
            plugin.reloadAll();
            reply(context, "reloaded", Map.of("count", String.valueOf(plugin.settings().types().size())));
            return Command.SINGLE_SUCCESS;
        }));
        root.then(Commands.literal("list").executes(context -> {
            String ids = String.join(", ", plugin.settings().types().keySet());
            context.getSource().getSender().sendMessage(Text.parse("&7" + ids));
            return Command.SINGLE_SUCCESS;
        }));
        root.then(Commands.literal("give")
            .then(Commands.argument("joueur", ArgumentTypes.player())
                .then(Commands.argument("type", StringArgumentType.word())
                    .suggests((context, builder) -> {
                        plugin.settings().types().keySet().stream()
                            .filter(id -> id.startsWith(builder.getRemainingLowerCase())).forEach(builder::suggest);
                        return builder.buildFuture();
                    })
                    .executes(context -> give(context, 1, 1))
                    .then(Commands.argument("quantite", IntegerArgumentType.integer(1, 64))
                        .executes(context -> give(context, IntegerArgumentType.getInteger(context, "quantite"), 1))
                        .then(Commands.argument("niveau", IntegerArgumentType.integer(1, 100))
                            .executes(context -> give(context, IntegerArgumentType.getInteger(context, "quantite"),
                                IntegerArgumentType.getInteger(context, "niveau"))))))));
        root.then(Commands.literal("moteur")
            .then(Commands.argument("joueur", ArgumentTypes.player())
                .executes(context -> giveEngine(context, 1))
                .then(Commands.argument("quantite", IntegerArgumentType.integer(1, 64))
                    .executes(context -> giveEngine(context, IntegerArgumentType.getInteger(context, "quantite"))))));
        root.then(Commands.literal("info").executes(context -> {
            Generator generator = target(context);
            if (generator != null) {
                context.getSource().getSender().sendMessage(Text.parse(
                    "&7" + generator.typeId() + " &8| &7Niveau &f" + generator.level() + " &8| &7Proprietaire &f" + generator.ownerName()
                        + " &8| &7" + (generator.unlocked() ? "&aDeverrouille" : "&cVerrouille")));
            }
            return Command.SINGLE_SUCCESS;
        }));
        root.then(Commands.literal("setlevel")
            .then(Commands.argument("niveau", IntegerArgumentType.integer(1, 100)).executes(context -> {
                Generator generator = target(context);
                if (generator != null && generator.type() != null) {
                    plugin.generators().setLevel(generator, IntegerArgumentType.getInteger(context, "niveau"));
                    reply(context, "level-set", Map.of("level", String.valueOf(generator.level())));
                }
                return Command.SINGLE_SUCCESS;
            })));
        return root.build();
    }

    public List<String> aliases() {
        return List.of("generator", "gen", "obgen");
    }

    private int help(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        plugin.settings().rawList("help").forEach(line -> sender.sendMessage(Text.parse(line)));
        return Command.SINGLE_SUCCESS;
    }

    private void reply(CommandContext<CommandSourceStack> context, String key, Map<String, String> placeholders) {
        context.getSource().getSender().sendMessage(Text.parse(plugin.settings().prefixed(key, placeholders)));
    }

    private Player player(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return context.getArgument("joueur", PlayerSelectorArgumentResolver.class).resolve(context.getSource()).getFirst();
    }

    private int give(CommandContext<CommandSourceStack> context, int amount, int level) throws CommandSyntaxException {
        Player player = player(context);
        GeneratorType type = plugin.settings().type(StringArgumentType.getString(context, "type"));
        if (type == null) {
            reply(context, "unknown-type", Map.of());
            return 0;
        }
        ItemStack item = plugin.items().generator(type, Math.min(level, type.maxLevel()), false, "");
        item.setAmount(amount);
        deliver(player, item);
        reply(context, "given", Map.of("player", player.getName(), "generator", type.name(), "amount", String.valueOf(amount)));
        return Command.SINGLE_SUCCESS;
    }

    private int giveEngine(CommandContext<CommandSourceStack> context, int amount) throws CommandSyntaxException {
        Player player = player(context);
        ItemStack item = plugin.items().engine();
        item.setAmount(amount);
        deliver(player, item);
        reply(context, "given", Map.of("player", player.getName(), "generator", "Moteur", "amount", String.valueOf(amount)));
        return Command.SINGLE_SUCCESS;
    }

    private void deliver(Player player, ItemStack item) {
        player.getScheduler().run(plugin, task -> player.getInventory().addItem(item)
            .values().forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left)), null);
    }

    private Generator target(CommandContext<CommandSourceStack> context) {
        if (!(context.getSource().getExecutor() instanceof Player player)) {
            reply(context, "players-only", Map.of());
            return null;
        }
        Block block = player.getTargetBlockExact(6);
        Generator generator = block == null ? null : plugin.generators().at(block);
        if (generator == null) {
            reply(context, "no-target", Map.of());
        }
        return generator;
    }
}
