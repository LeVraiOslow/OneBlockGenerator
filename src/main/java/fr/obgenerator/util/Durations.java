package fr.obgenerator.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Durations {

    private static final Pattern PART = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(ms|d|h|m|s)?", Pattern.CASE_INSENSITIVE);

    private Durations() {
    }

    /** "90", "90s", "5m", "1h30m", "2d" vers millisecondes ; un nombre seul = secondes. */
    public static long parse(String value, long fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        Matcher matcher = PART.matcher(value.trim());
        long total = 0;
        boolean found = false;
        while (matcher.find()) {
            found = true;
            double amount = Double.parseDouble(matcher.group(1));
            String unit = matcher.group(2) == null ? "s" : matcher.group(2).toLowerCase();
            total += (long) (amount * switch (unit) {
                case "ms" -> 1L;
                case "m" -> 60_000L;
                case "h" -> 3_600_000L;
                case "d" -> 86_400_000L;
                default -> 1_000L;
            });
        }
        return found ? total : fallback;
    }

    public static String format(long millis) {
        long seconds = Math.max(0, (millis + 999) / 1000);
        long days = seconds / 86400;
        long hours = seconds % 86400 / 3600;
        long minutes = seconds % 3600 / 60;
        long rest = seconds % 60;
        StringBuilder out = new StringBuilder();
        if (days > 0) {
            out.append(days).append("j ");
        }
        if (hours > 0) {
            out.append(hours).append("h ");
        }
        if (minutes > 0) {
            out.append(minutes).append("m ");
        }
        if (rest > 0 || out.isEmpty()) {
            out.append(rest).append('s');
        }
        return out.toString().trim();
    }
}
