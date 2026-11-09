package fr.obgenerator.hook;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

/** Le fournisseur d'économie est recherché à chaque usage : il peut s'enregistrer après ce plugin. */
public final class VaultHook {

    private Economy economy;

    private Economy economy() {
        if (economy == null) {
            RegisteredServiceProvider<Economy> provider = Bukkit.getServicesManager().getRegistration(Economy.class);
            if (provider != null) {
                economy = provider.getProvider();
            }
        }
        return economy;
    }

    public boolean available() {
        return economy() != null;
    }

    public double balance(OfflinePlayer player) {
        Economy current = economy();
        return current == null ? 0 : current.getBalance(player);
    }

    public boolean withdraw(OfflinePlayer player, double amount) {
        Economy current = economy();
        return current != null && current.withdrawPlayer(player, amount).transactionSuccess();
    }

    public boolean deposit(OfflinePlayer player, double amount) {
        Economy current = economy();
        return current != null && current.depositPlayer(player, amount).transactionSuccess();
    }
}
