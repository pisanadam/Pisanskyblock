package com.pisan.games;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class PisanGames extends JavaPlugin {
    private ExarotonClient api;
    private Connector connector;
    private Auth auth;
    private final Map<String, GameServer> servers = new LinkedHashMap<>();
    /** sunucu anahtari -> son bilinen durum (menude gosterilir) */
    private final Map<String, ExarotonClient.ServerInfo> status = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        load();
        connector = new Connector(this);
        auth = new Auth(this);
        getServer().getPluginManager().registerEvents(auth, this);
        if (getConfig().getString("auth-secret", "").length() < 16) {
            byte[] b = new byte[32]; new java.security.SecureRandom().nextBytes(b);
            getConfig().set("auth-secret", java.util.HexFormat.of().formatHex(b));
            saveConfig();
            getLogger().warning("Yeni auth-secret uretildi; ayni degeri hedef sunucularin PisanHub ayarina koy.");
        }
        LobbyListener listener = new LobbyListener(this);
        getServer().getPluginManager().registerEvents(listener, this);
        Bukkit.getScheduler().runTask(this, () -> {
            setupWorld();
            if (!getConfig().getBoolean("built")) {
                new LobbyBuilder(this).build(spawn());
                getConfig().set("built", true);
                saveConfig();
            } else new LobbyBuilder(this).labels(spawn());
        });
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::refreshStatus, 20L, 20L * 15);
        if (!api.hasToken()) getLogger().warning("config.yml icinde exaroton-token ayarlanmamis!");
    }

    private void load() {
        reloadConfig();
        api = new ExarotonClient(getConfig().getString("exaroton-token", ""));
        servers.clear();
        ConfigurationSection sec = getConfig().getConfigurationSection("servers");
        if (sec != null) for (String k : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(k);
            Material icon = Material.matchMaterial(s.getString("icon", "COMPASS"));
            servers.put(k, new GameServer(k, s.getString("id"), s.getString("name", k),
                    icon == null ? Material.COMPASS : icon, s.getInt("slot", 13), s.getStringList("lore"),
                    s.getStringList("requires")));
        }
    }

    private void setupWorld() {
        World w = spawn().getWorld();
        if (w == null) return;
        w.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        w.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        w.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        w.setGameRule(GameRule.DO_FIRE_TICK, false);
        w.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false);
        w.setGameRule(GameRule.SHOW_DEATH_MESSAGES, false);
        w.setTime(6000);
        w.setStorm(false);
        w.setSpawnLocation(spawn());
        w.setDifficulty(Difficulty.PEACEFUL);
    }

    private void refreshStatus() {
        if (!api.hasToken()) return;
        for (GameServer gs : servers.values()) {
            try { status.put(gs.key(), api.get(gs.id())); }
            catch (Exception e) { /* gecici hata, sonraki turda tekrar */ }
        }
    }

    public ExarotonClient api() { return api; }
    public Connector connector() { return connector; }
    public Auth auth() { return auth; }
    public String secret() { return getConfig().getString("auth-secret", ""); }
    public String probeName() { return getConfig().getString("probe-name", "PisanTest"); }
    @SuppressWarnings("deprecation")
    public int protocol() { return Bukkit.getUnsafe().getProtocolVersion(); }
    public Collection<GameServer> servers() { return servers.values(); }
    public GameServer server(String key) { return servers.get(key); }
    public ExarotonClient.ServerInfo status(String key) { return status.get(key); }

    public Location spawn() {
        ConfigurationSection s = getConfig().getConfigurationSection("spawn");
        World w = Bukkit.getWorld(s == null ? "world" : s.getString("world", "world"));
        if (w == null) w = Bukkit.getWorlds().get(0);
        if (s == null) return new Location(w, 0.5, 151, 0.5);
        return new Location(w, s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                (float) s.getDouble("yaw"), (float) s.getDouble("pitch"));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        if (name.equals("pisangames")) return admin(sender, args);
        if (!(sender instanceof Player p)) { sender.sendMessage("Sadece oyuncular."); return true; }
        if (name.equals("giris") || name.equals("kayit") || name.equals("sifredegistir")) { auth.command(p, name, args); return true; }
        if (!auth.isAuthed(p)) { p.sendMessage(Component.text("Önce giriş yap.", NamedTextColor.RED)); return true; }
        switch (name) {
            case "lobi" -> p.teleport(spawn());
            case "pvp", "skyblock", "sfleague", "strike" -> go(p, name);
            case "oyun" -> {
                if (args.length > 0) go(p, args[0].toLowerCase(Locale.ROOT));
                else GameMenu.open(this, p);
            }
        }
        return true;
    }

    private void go(Player p, String key) {
        GameServer gs = servers.get(key);
        if (gs == null) { p.sendMessage(Component.text("Boyle bir oyun yok. Secenekler: " + String.join(", ", servers.keySet()), NamedTextColor.RED)); return; }
        connector.connect(p, gs);
    }

    private boolean admin(CommandSender sender, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> { load(); sender.sendMessage(Component.text("PisanGames yenilendi.", NamedTextColor.GREEN)); }
            case "setspawn" -> {
                if (!(sender instanceof Player p)) return true;
                Location l = p.getLocation();
                getConfig().set("spawn.world", l.getWorld().getName());
                getConfig().set("spawn.x", l.getX()); getConfig().set("spawn.y", l.getY()); getConfig().set("spawn.z", l.getZ());
                getConfig().set("spawn.yaw", (double) l.getYaw()); getConfig().set("spawn.pitch", (double) l.getPitch());
                saveConfig();
                l.getWorld().setSpawnLocation(l);
                sender.sendMessage(Component.text("Lobi spawn ayarlandi.", NamedTextColor.GREEN));
            }
            case "rebuild" -> {
                new LobbyBuilder(this).build(spawn());
                sender.sendMessage(Component.text("Lobi yeniden kuruldu.", NamedTextColor.GREEN));
            }
            default -> sender.sendMessage(Component.text("/pisangames reload | setspawn | rebuild", NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false));
        }
        return true;
    }
}
