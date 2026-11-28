package com.pisan.hub;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Pisan Games lobisinden gelen imzali bileti (pisan:ticket cerezi) dogrular.
 * require-ticket acikken (online-mode=false sunucular) bileti olmayan baglanti reddedilir:
 * boylece crack oyuncular lobide bir kez sifre girer, burada tekrar sorulmaz; lobiyi atlayip
 * baskasinin ismiyle girmek ise mumkun olmaz.
 * Eski online-mode verisi korunur: isim -> UUID eslemesi (ilk acilista usercache.json'dan).
 */
final class TicketGate implements Listener {
    private final PisanHub plugin;
    private final File mapFile;
    private final YamlConfiguration map;

    TicketGate(PisanHub plugin) {
        this.plugin = plugin;
        this.mapFile = new File(plugin.getDataFolder(), "uuids.yml");
        boolean fresh = !mapFile.exists();
        this.map = YamlConfiguration.loadConfiguration(mapFile);
        if (fresh) seedFromUsercache();
    }

    private String secret() { return plugin.getConfig().getString("ticket-secret", ""); }
    private boolean required() { return plugin.getConfig().getBoolean("require-ticket", false); }
    private String probe() { return plugin.getConfig().getString("probe-name", "PisanTest"); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        if (!required()) return;
        byte[] data = cookie(e);
        String who = Ticket.verify(secret(), data);
        if (who == null || !who.equalsIgnoreCase(e.getName())) {
            e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, "§cBu sunucuya §dPisan Games§c lobisinden gir:\n§b"
                    + plugin.getConfig().getString("lobby-address", "Pisangames.exaroton.me"));
            plugin.getLogger().info(e.getName() + " bilet olmadan girmeye calisti (" + (data == null ? "cerez yok" : "gecersiz bilet") + ")");
            return;
        }
        UUID id = uuidFor(e.getName());
        if (!id.equals(e.getUniqueId())) e.setPlayerProfile(Bukkit.createProfile(id, e.getName()));
    }

    /** Paper 1.20.5+ : event.getConnection().retrieveCookie(key). Eski API'de derlendigi icin yansima. */
    private byte[] cookie(AsyncPlayerPreLoginEvent e) {
        try {
            Object conn = e.getClass().getMethod("getConnection").invoke(e);
            @SuppressWarnings("unchecked")
            CompletableFuture<byte[]> f = (CompletableFuture<byte[]>) conn.getClass().getMethod("retrieveCookie", NamespacedKey.class)
                    .invoke(conn, new NamespacedKey(Ticket.NAMESPACE, Ticket.KEY));
            return f.get(5, TimeUnit.SECONDS);
        } catch (Throwable t) {
            return null;
        }
    }

    private synchronized UUID uuidFor(String name) {
        String k = name.toLowerCase(Locale.ROOT);
        String v = map.getString(k);
        if (v != null) try { return UUID.fromString(v); } catch (IllegalArgumentException ignored) {}
        UUID id = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        map.set(k, id.toString());
        save();
        return id;
    }

    private void seedFromUsercache() {
        File uc = new File(Bukkit.getWorldContainer(), "usercache.json");
        int n = 0;
        if (uc.exists()) try {
            for (var el : com.google.gson.JsonParser.parseString(Files.readString(uc.toPath())).getAsJsonArray()) {
                var o = el.getAsJsonObject();
                map.set(o.get("name").getAsString().toLowerCase(Locale.ROOT), o.get("uuid").getAsString());
                n++;
            }
        } catch (Exception ex) { plugin.getLogger().warning("usercache.json okunamadi: " + ex.getMessage()); }
        save();
        plugin.getLogger().info("uuids.yml olusturuldu: " + n + " eski oyuncunun UUID'si korunuyor.");
    }

    private void save() {
        try { plugin.getDataFolder().mkdirs(); map.save(mapFile); } catch (Exception ex) { plugin.getLogger().warning("uuids.yml yazilamadi: " + ex.getMessage()); }
    }

    // test oyuncusu (lobinin giris testi) sessizce girip cikar
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent e) {
        if (!e.getPlayer().getName().equals(probe())) return;
        e.setJoinMessage(null);
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (e.getPlayer().isOnline()) e.getPlayer().kickPlayer("test"); }, 40L);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent e) { if (e.getPlayer().getName().equals(probe())) e.setQuitMessage(null); }
}
