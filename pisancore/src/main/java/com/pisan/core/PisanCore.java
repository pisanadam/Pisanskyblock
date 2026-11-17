package com.pisan.core;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class PisanCore extends JavaPlugin {

    private EconomyStore store;
    private GeneratorListener generator;
    private CrateManager crates;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        store = new EconomyStore(this);
        store.load();

        // Vault ekonomisi olarak kaydol (diger pluginler: market, pazar, BentoBox bunu kullanir)
        Bukkit.getServicesManager().register(Economy.class, new VaultEconomy(this, store), this, ServicePriority.Highest);

        crates = new CrateManager(this);
        crates.load();
        CrateMenu menu = new CrateMenu(this, crates);
        getCommand("kasa").setExecutor(menu);
        getCommand("kasa").setTabCompleter(menu);
        Bukkit.getPluginManager().registerEvents(menu, this);

        EcoCommands eco = new EcoCommands(this, store);
        for (String c : new String[]{"bal", "pay", "baltop", "eco", "pisancore"}) {
            getCommand(c).setExecutor(eco);
            getCommand(c).setTabCompleter(eco);
        }

        generator = new GeneratorListener(this);
        Bukkit.getPluginManager().registerEvents(generator, this);
        Bukkit.getPluginManager().registerEvents(new JoinListener(this, store), this);

        // 5 dakikada bir kaydet
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> { store.saveAsync(); crates.saveKeys(false); }, 6000L, 6000L);
        getLogger().info("PisanCore aktif.");
    }

    @Override
    public void onDisable() {
        if (store != null) store.saveSync();
        if (crates != null) crates.saveKeys(true);
    }

    public void reload() {
        reloadConfig();
        generator.reload();
        crates.reloadCrates();
    }

    public String msg(String path) {
        return color(getConfig().getString("messages.prefix", "") + getConfig().getString("messages." + path, ""));
    }

    public static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }

    public EconomyStore store() { return store; }
    public CrateManager crates() { return crates; }

    public void giveKey(java.util.UUID player, String key, int amount) {
        crates.addKeys(player, key, amount);
    }

    public static String fmt(double d) {
        if (d == Math.floor(d) && Math.abs(d) < 1e15) return String.format("%,d", (long) d).replace(',', '.');
        return String.format("%,.2f", d);
    }
}
