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
    private DuelManager duel;
    private TradeManager trade;
    private MinionManager minions;
    private SkillManager skills;
    private ScoreboardManager scoreboard;
    private FriendManager friends;
    private LobbyManager lobby;
    private EventManager events;
    private SellManager sell;
    private SmpManager smp;
    private InventoryManager invs;
    private AuctionManager auction;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
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
        Bukkit.getPluginManager().registerEvents(new KeyProtectListener(this, menu), this);
        invs = new InventoryManager(this);
        Bukkit.getPluginManager().registerEvents(invs, this);
        smp = new SmpManager(this);
        Bukkit.getPluginManager().registerEvents(smp, this);
        getCommand("smp").setExecutor(smp);
        getCommand("smp").setTabCompleter(smp);
        lobby = new LobbyManager(this, menu);
        Bukkit.getPluginManager().registerEvents(lobby, this);
        for (String c : new String[]{"lobi", "menu", "skylobi", "smplobi"}) { getCommand(c).setExecutor(lobby); getCommand(c).setTabCompleter(lobby); }
        Bukkit.getScheduler().runTask(this, () -> { smp.init(); lobby.init(); });

        duel = new DuelManager(this);
        getCommand("duel").setExecutor(duel);
        getCommand("duel").setTabCompleter(duel);
        Bukkit.getPluginManager().registerEvents(duel, this);
        Bukkit.getScheduler().runTask(this, duel::init);

        trade = new TradeManager(this);
        getCommand("takas").setExecutor(trade);
        getCommand("takas").setTabCompleter(trade);
        Bukkit.getPluginManager().registerEvents(trade, this);

        minions = new MinionManager(this);
        minions.loadConfig(); // veriler dunyalar yuklendikten sonra (asagida) okunur
        Bukkit.getPluginManager().registerEvents(minions, this);
        MinionCommand mc = new MinionCommand(this, minions);
        getCommand("minyon").setExecutor(mc);
        getCommand("minyon").setTabCompleter(mc);
        // BentoBox dunyalari yuklendikten sonra baslat
        Bukkit.getScheduler().runTaskLater(this, () -> { minions.load(); minions.start(); }, 40L);

        skills = new SkillManager(this);
        skills.load();
        Bukkit.getPluginManager().registerEvents(skills, this);
        getCommand("skill").setExecutor(skills);
        getCommand("skill").setTabCompleter(skills);
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> skills.save(false), 6000L, 6000L);

        scoreboard = new ScoreboardManager(this);
        Bukkit.getPluginManager().registerEvents(scoreboard, this);
        for (String c : new String[]{"scoreboard", "siralama"}) { getCommand(c).setExecutor(scoreboard); getCommand(c).setTabCompleter(scoreboard); }
        Bukkit.getScheduler().runTaskLater(this, scoreboard::start, 60L);

        friends = new FriendManager(this);
        friends.load();
        Bukkit.getPluginManager().registerEvents(friends, this);
        getCommand("arkadas").setExecutor(friends);
        getCommand("arkadas").setTabCompleter(friends);
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> friends.save(false), 6000L, 6000L);

        Bukkit.getPluginManager().registerEvents(new SpawnerListener(this), this);
        Bukkit.getPluginManager().registerEvents(new KeySourceListener(this), this);

        MarketCommand market = new MarketCommand(this);
        getCommand("market").setExecutor(market);
        getCommand("market").setTabCompleter(market);
        Bukkit.getScheduler().runTaskLater(this, market::warmup, 100L);

        events = new EventManager(this);
        Bukkit.getPluginManager().registerEvents(events, this);
        getCommand("event").setExecutor(events);
        getCommand("event").setTabCompleter(events);
        Bukkit.getScheduler().runTaskLater(this, events::start, 40L);

        sell = new SellManager(this);
        Bukkit.getPluginManager().registerEvents(sell, this);
        getCommand("sat").setExecutor(sell);
        getCommand("sat").setTabCompleter(sell);
        Bukkit.getScheduler().runTaskLater(this, sell::load, 60L);

        auction = new AuctionManager(this);
        Bukkit.getPluginManager().registerEvents(auction, this);
        getCommand("ah").setExecutor(auction);
        getCommand("ah").setTabCompleter(auction);
        Bukkit.getScheduler().runTaskLater(this, auction::start, 20L);

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
        if (auction != null) auction.saveNow();
        if (events != null) events.shutdown();
        if (trade != null) trade.shutdown();
        if (minions != null) minions.shutdown();
        if (duel != null) duel.shutdown();
        if (skills != null) skills.save(true);
        if (friends != null) friends.save(true);
        if (store != null) store.saveSync();
        if (crates != null) crates.saveKeys(true);
    }

    public void reload() {
        reloadConfig();
        generator.reload();
        crates.reloadCrates();
        minions.loadConfig();
        if (sell != null) sell.load();
    }

    public String msg(String path) {
        return color(getConfig().getString("messages.prefix", "") + getConfig().getString("messages." + path, ""));
    }

    public static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }

    public EconomyStore store() { return store; }
    public CrateManager crates() { return crates; }
    public SkillManager skills() { return skills; }
    public GeneratorListener generator() { return generator; }
    public EventManager events() { return events; }
    public SmpManager smp() { return smp; }
    public InventoryManager invs() { return invs; }
    public LobbyManager lobby() { return lobby; }
    public boolean lobbyWorld(org.bukkit.World w) { return lobby != null && lobby.world() != null && lobby.world().equals(w); }

    public void giveKey(java.util.UUID player, String key, int amount) {
        crates.addKeys(player, key, amount);
    }

    public static String fmt(double d) {
        if (d == Math.floor(d) && Math.abs(d) < 1e15) return String.format("%,d", (long) d).replace(',', '.');
        return String.format("%,.2f", d);
    }
}
