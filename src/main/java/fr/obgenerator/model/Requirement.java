package fr.obgenerator.model;

import fr.obgenerator.item.ItemSpec;
import fr.obgenerator.item.MenuItem;
import org.bukkit.Statistic;

/** Une condition d'amélioration, identifiée par sa clé dans la configuration. */
public record Requirement(
    String id,
    Type type,
    long amount,
    int slot,
    ItemSpec item,
    Statistic statistic,
    String permission,
    String placeholder,
    String label,
    MenuItem icon
) {

    public enum Type {
        /** Items prélevés dans l'inventaire. */
        ITEM(true),
        /** Monnaie Vault prélevée. */
        MONEY(true),
        /** Niveaux d'expérience prélevés. */
        XP(true),
        /** Temps de jeu du joueur, vérifié mais non consommé. */
        PLAYTIME(false),
        /** Statistique Minecraft, vérifiée mais non consommée. */
        STATISTIC(false),
        /** Permission, vérifiée. */
        PERMISSION(false),
        /** Valeur numérique PlaceholderAPI, vérifiée. */
        PLACEHOLDER(false);

        private final boolean consumed;

        Type(boolean consumed) {
            this.consumed = consumed;
        }

        public boolean consumed() {
            return consumed;
        }
    }
}
