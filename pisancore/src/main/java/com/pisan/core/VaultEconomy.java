package com.pisan.core;

import net.milkbowl.vault.economy.AbstractEconomy;
import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.economy.EconomyResponse.ResponseType;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Vault ekonomi saglayicisi. Isim tabanli eski metotlar OfflinePlayer'a cevrilir. */
public final class VaultEconomy extends AbstractEconomy {
    private final PisanCore plugin;
    private final EconomyStore store;

    public VaultEconomy(PisanCore plugin, EconomyStore store) { this.plugin = plugin; this.store = store; }

    @SuppressWarnings("deprecation")
    private UUID id(String name) {
        OfflinePlayer p = Bukkit.getPlayerExact(name);
        if (p == null) p = Bukkit.getOfflinePlayer(name);
        return p.getUniqueId();
    }

    @Override public boolean isEnabled() { return plugin.isEnabled(); }
    @Override public String getName() { return "PisanCore"; }
    @Override public boolean hasBankSupport() { return false; }
    @Override public int fractionalDigits() { return 2; }
    @Override public String format(double v) { return PisanCore.fmt(v) + " " + currencyNamePlural(); }
    @Override public String currencyNamePlural() { return plugin.getConfig().getString("economy.currency-plural", "Coin"); }
    @Override public String currencyNameSingular() { return plugin.getConfig().getString("economy.currency-singular", "Coin"); }

    // --- OfflinePlayer (modern) ---
    @Override public boolean hasAccount(OfflinePlayer p) { return store.has(p.getUniqueId()); }
    @Override public boolean hasAccount(OfflinePlayer p, String w) { return hasAccount(p); }
    @Override public double getBalance(OfflinePlayer p) { return store.get(p.getUniqueId()); }
    @Override public double getBalance(OfflinePlayer p, String w) { return getBalance(p); }
    @Override public boolean has(OfflinePlayer p, double a) { return getBalance(p) >= a; }
    @Override public boolean has(OfflinePlayer p, String w, double a) { return has(p, a); }
    @Override public boolean createPlayerAccount(OfflinePlayer p) { store.create(p.getUniqueId(), p.getName()); return true; }
    @Override public boolean createPlayerAccount(OfflinePlayer p, String w) { return createPlayerAccount(p); }

    @Override public EconomyResponse withdrawPlayer(OfflinePlayer p, double a) {
        if (a < 0) return new EconomyResponse(0, getBalance(p), ResponseType.FAILURE, "Negatif miktar");
        if (!store.withdraw(p.getUniqueId(), a)) return new EconomyResponse(0, getBalance(p), ResponseType.FAILURE, "Yetersiz bakiye");
        return new EconomyResponse(a, getBalance(p), ResponseType.SUCCESS, null);
    }
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer p, String w, double a) { return withdrawPlayer(p, a); }

    @Override public EconomyResponse depositPlayer(OfflinePlayer p, double a) {
        if (a < 0) return new EconomyResponse(0, getBalance(p), ResponseType.FAILURE, "Negatif miktar");
        store.deposit(p.getUniqueId(), a);
        if (p.getName() != null) store.setName(p.getUniqueId(), p.getName());
        return new EconomyResponse(a, getBalance(p), ResponseType.SUCCESS, null);
    }
    @Override public EconomyResponse depositPlayer(OfflinePlayer p, String w, double a) { return depositPlayer(p, a); }

    // --- isim tabanli (eski) ---
    @Override public boolean hasAccount(String n) { return store.has(id(n)); }
    @Override public boolean hasAccount(String n, String w) { return hasAccount(n); }
    @Override public double getBalance(String n) { return store.get(id(n)); }
    @Override public double getBalance(String n, String w) { return getBalance(n); }
    @Override public boolean has(String n, double a) { return getBalance(n) >= a; }
    @Override public boolean has(String n, String w, double a) { return has(n, a); }
    @SuppressWarnings("deprecation")
    @Override public EconomyResponse withdrawPlayer(String n, double a) { return withdrawPlayer(Bukkit.getOfflinePlayer(id(n)), a); }
    @Override public EconomyResponse withdrawPlayer(String n, String w, double a) { return withdrawPlayer(n, a); }
    @SuppressWarnings("deprecation")
    @Override public EconomyResponse depositPlayer(String n, double a) { return depositPlayer(Bukkit.getOfflinePlayer(id(n)), a); }
    @Override public EconomyResponse depositPlayer(String n, String w, double a) { return depositPlayer(n, a); }
    @Override public boolean createPlayerAccount(String n) { store.create(id(n), n); return true; }
    @Override public boolean createPlayerAccount(String n, String w) { return createPlayerAccount(n); }

    // --- banka yok ---
    private EconomyResponse noBank() { return new EconomyResponse(0, 0, ResponseType.NOT_IMPLEMENTED, "Banka yok"); }
    @Override public EconomyResponse createBank(String n, String p) { return noBank(); }
    @Override public EconomyResponse deleteBank(String n) { return noBank(); }
    @Override public EconomyResponse bankBalance(String n) { return noBank(); }
    @Override public EconomyResponse bankHas(String n, double a) { return noBank(); }
    @Override public EconomyResponse bankWithdraw(String n, double a) { return noBank(); }
    @Override public EconomyResponse bankDeposit(String n, double a) { return noBank(); }
    @Override public EconomyResponse isBankOwner(String n, String p) { return noBank(); }
    @Override public EconomyResponse isBankMember(String n, String p) { return noBank(); }
    @Override public List<String> getBanks() { return Collections.emptyList(); }
}
