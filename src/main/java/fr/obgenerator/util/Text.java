package fr.obgenerator.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** Couleurs : &c, &l, &#RRGGBB, #RRGGBB, <#RRGGBB>, et toutes les balises MiniMessage (<gradient:...>). */
public final class Text {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String COLORS = "0123456789abcdef";
    private static final String[] NAMES = {"black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple",
        "gold", "gray", "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white"};

    private Text() {
    }

    public static Component parse(String input) {
        if (input == null || input.isEmpty()) {
            return Component.empty();
        }
        String mini = toMiniMessage(input);
        try {
            return MM.deserialize(mini);
        } catch (RuntimeException invalidTag) {
            return Component.text(input);
        }
    }

    /** Texte pour un nom ou une lore d'item : sans l'italique imposé par le client. */
    public static Component item(String input) {
        return Component.empty().decoration(TextDecoration.ITALIC, false).append(parse(input));
    }

    public static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    public static String apply(String text, Map<String, String> placeholders) {
        if (text == null || text.indexOf('{') < 0) {
            return text;
        }
        String result = text;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    /** Remplace les placeholders ; une ligne composée d'une clé de {@code blocks} devient plusieurs lignes. */
    public static List<String> apply(List<String> lines, Map<String, String> placeholders, Map<String, List<String>> blocks) {
        List<String> result = new ArrayList<>(lines.size());
        for (String line : lines) {
            String key = line.trim();
            if (key.length() > 2 && key.startsWith("{") && key.endsWith("}")
                && blocks.containsKey(key.substring(1, key.length() - 1))) {
                result.addAll(blocks.get(key.substring(1, key.length() - 1)));
            } else {
                result.add(apply(line, placeholders));
            }
        }
        return result;
    }

    static String toMiniMessage(String input) {
        StringBuilder out = new StringBuilder(input.length() + 16);
        boolean decorated = false;
        int length = input.length();
        for (int i = 0; i < length; i++) {
            char c = input.charAt(i);
            boolean code = (c == '&' || c == '§') && i + 1 < length;
            if (code && input.charAt(i + 1) == '#' && isHex(input, i + 2, i + 8)) {
                if (decorated) {
                    out.append("<reset>");
                    decorated = false;
                }
                out.append("<#").append(input, i + 2, i + 8).append('>');
                i += 7;
                continue;
            }
            if (code && Character.toLowerCase(input.charAt(i + 1)) == 'x' && isSpigotHex(input, i + 2)) {
                if (decorated) {
                    out.append("<reset>");
                    decorated = false;
                }
                out.append("<#");
                for (int j = 3; j <= 13; j += 2) {
                    out.append(input.charAt(i + j));
                }
                out.append('>');
                i += 13;
                continue;
            }
            if (code) {
                char next = Character.toLowerCase(input.charAt(i + 1));
                int color = COLORS.indexOf(next);
                if (color >= 0) {
                    if (decorated) {
                        out.append("<reset>");
                        decorated = false;
                    }
                    out.append('<').append(NAMES[color]).append('>');
                    i++;
                    continue;
                }
                String decoration = switch (next) {
                    case 'l' -> "bold";
                    case 'm' -> "strikethrough";
                    case 'n' -> "underlined";
                    case 'o' -> "italic";
                    case 'k' -> "obfuscated";
                    default -> null;
                };
                if (decoration != null) {
                    out.append('<').append(decoration).append('>');
                    decorated = true;
                    i++;
                    continue;
                }
                if (next == 'r') {
                    out.append("<reset>");
                    decorated = false;
                    i++;
                    continue;
                }
            }
            boolean insideTagValue = i > 0 && (input.charAt(i - 1) == '<' || input.charAt(i - 1) == ':');
            if (c == '#' && !insideTagValue && isHex(input, i + 1, i + 7)) {
                if (decorated) {
                    out.append("<reset>");
                    decorated = false;
                }
                out.append("<#").append(input, i + 1, i + 7).append('>');
                i += 6;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    private static boolean isHex(String input, int from, int to) {
        if (to > input.length()) {
            return false;
        }
        for (int i = from; i < to; i++) {
            if (Character.digit(input.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    /** Format Spigot : &x&r&r&g&g&b&b. */
    private static boolean isSpigotHex(String input, int from) {
        if (from + 12 > input.length()) {
            return false;
        }
        for (int i = from; i < from + 12; i += 2) {
            char marker = input.charAt(i);
            if ((marker != '&' && marker != '§') || Character.digit(input.charAt(i + 1), 16) < 0) {
                return false;
            }
        }
        return true;
    }
}
