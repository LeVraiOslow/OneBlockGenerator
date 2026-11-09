package fr.obgenerator.hook;

import fr.obgenerator.ObGenerator;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** Prix de vente (worth.yml) et état AFK, par réflexion : EssentialsX reste facultatif. */
public final class EssentialsHook {

    private Object essentials;
    private Object worth;
    private Method getPrice;
    private Method getUser;
    private Class<?> essentialsApi;

    public EssentialsHook(ObGenerator plugin) {
        Plugin found = Bukkit.getPluginManager().getPlugin("Essentials");
        if (found == null || !found.isEnabled()) {
            return;
        }
        try {
            essentialsApi = Class.forName("com.earth2me.essentials.IEssentials", true, found.getClass().getClassLoader());
            worth = essentialsApi.getMethod("getWorth").invoke(found);
            getPrice = worth.getClass().getMethod("getPrice", essentialsApi, ItemStack.class);
            getUser = essentialsApi.getMethod("getUser", UUID.class);
            essentials = found;
            plugin.getLogger().info("EssentialsX detecte.");
        } catch (ReflectiveOperationException | LinkageError error) {
            plugin.getLogger().warning("API EssentialsX introuvable : " + error);
        }
    }

    public boolean available() {
        return essentials != null;
    }

    /** @return prix unitaire, ou 0 si l'item n'a pas de prix dans worth.yml */
    public double price(ItemStack stack) {
        if (!available()) {
            return 0;
        }
        try {
            Object price = getPrice.invoke(worth, essentials, stack);
            return price == null ? 0 : Math.max(0, ((BigDecimal) price).doubleValue());
        } catch (ReflectiveOperationException error) {
            return 0;
        }
    }

    public boolean isAfk(UUID player) {
        if (!available()) {
            return false;
        }
        try {
            Object user = getUser.invoke(essentials, player);
            return user != null && (boolean) user.getClass().getMethod("isAfk").invoke(user);
        } catch (ReflectiveOperationException error) {
            return false;
        }
    }
}
