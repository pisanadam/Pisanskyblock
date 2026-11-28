package com.pisan.games;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Oyuncuyu hedef exaroton sunucusuna gonderir: kapaliysa baslatir, acilinca transfer eder. */
public final class Connector {
    /** 1.20.5 (transfer paketi gelen ilk surum) protokol numarasi. */
    private static final int TRANSFER_MIN_PROTOCOL = 766;

    private final PisanGames plugin;
    /** sunucu anahtari -> bekleyen oyuncular */
    private final Map<String, Set<UUID>> waiting = new ConcurrentHashMap<>();
    private final Map<String, BossBar> bars = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerTarget = new ConcurrentHashMap<>();

    public Connector(PisanGames plugin) { this.plugin = plugin; }

    public void connect(Player p, GameServer gs) {
        ExarotonClient api = plugin.api();
        if (!api.hasToken()) { p.sendMessage(Component.text("Lobi ayarlanmamis (exaroton-token yok).", NamedTextColor.RED)); return; }
        String prev = playerTarget.get(p.getUniqueId());
        if (gs.key().equals(prev)) { p.sendMessage(Component.text("Zaten " + gs.plainName() + " icin siradasin, bekle...", NamedTextColor.YELLOW)); return; }
        if (prev != null) leave(p.getUniqueId());

        p.sendMessage(Component.text("» ", NamedTextColor.DARK_GRAY).append(gs.displayName()).append(Component.text(" kontrol ediliyor...", NamedTextColor.GRAY)));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                ExarotonClient.ServerInfo info = api.get(gs.id());
                if (ready(gs, info)) {
                    Bukkit.getScheduler().runTask(plugin, () -> send(p, gs, info));
                    return;
                }
                Bukkit.getScheduler().runTask(plugin, () -> enqueue(p, gs));
            } catch (Exception e) {
                plugin.getLogger().warning("exaroton hatasi (" + gs.key() + "): " + e.getMessage());
                Bukkit.getScheduler().runTask(plugin, () ->
                        p.sendMessage(Component.text("Sunucuya ulasilamadi: " + e.getMessage(), NamedTextColor.RED)));
            }
        });
    }

    private void enqueue(Player p, GameServer gs) {
        if (!p.isOnline()) return;
        playerTarget.put(p.getUniqueId(), gs.key());
        Set<UUID> set = waiting.computeIfAbsent(gs.key(), k -> ConcurrentHashMap.newKeySet());
        set.add(p.getUniqueId());
        BossBar bar = bars.computeIfAbsent(gs.key(), k -> BossBar.bossBar(
                gs.displayName().append(Component.text(" baslatiliyor...", NamedTextColor.YELLOW)),
                0f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS));
        p.showBossBar(bar);
        p.sendMessage(Component.text("» ", NamedTextColor.DARK_GRAY).append(gs.displayName())
                .append(Component.text(" hazirlaniyor. Hazir olunca otomatik gonderileceksin.", NamedTextColor.YELLOW)));
        if (set.size() == 1) poll(gs, System.currentTimeMillis());
    }

    private void poll(GameServer gs, long startedAt) {
        long timeoutMs = plugin.getConfig().getInt("start-timeout-seconds", 240) * 1000L;
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            Set<UUID> set = waiting.get(gs.key());
            if (set == null || set.isEmpty()) { removeBar(gs.key()); return; }
            ExarotonClient.ServerInfo info;
            boolean ok = false;
            try { info = plugin.api().get(gs.id()); ok = ready(gs, info); }
            catch (Exception e) { info = null; plugin.getLogger().warning("exaroton durum hatasi: " + e.getMessage()); }
            final boolean allReady = ok;
            long elapsed = System.currentTimeMillis() - startedAt;
            final ExarotonClient.ServerInfo fi = info;
            Bukkit.getScheduler().runTask(plugin, () -> {
                BossBar bar = bars.get(gs.key());
                if (allReady) {
                    // "online" olduktan sonra pluginlerin (ve proxy'nin backend'i listeye eklemesinin) bitmesi icin bekleme
                    Bukkit.getScheduler().runTaskLater(plugin, () -> flush(gs, fi), gs.requires().isEmpty() ? 60L : 140L);
                    if (bar != null) { bar.progress(1f); bar.name(gs.displayName().append(Component.text(" acildi! Gonderiliyorsun...", NamedTextColor.GREEN))); bar.color(BossBar.Color.GREEN); }
                    return;
                }
                if (elapsed > timeoutMs) {
                    for (UUID u : new ArrayList<>(set)) {
                        Player p = Bukkit.getPlayer(u);
                        if (p != null) p.sendMessage(Component.text(gs.plainName() + " acilamadi, biraz sonra tekrar dene.", NamedTextColor.RED));
                        leave(u);
                    }
                    return;
                }
                if (bar != null) {
                    bar.progress(Math.min(0.95f, elapsed / (float) Math.max(1, Math.min(timeoutMs, 90_000L))));
                    bar.name(gs.displayName().append(Component.text(" " + statusText(fi) + "...", NamedTextColor.YELLOW)));
                }
                poll(gs, startedAt);
            });
        }, 60L);
    }

    /**
     * Ana sunucu ve gerekli tum arka sunucular acik mi? Degilse kapali/cokmus olanlari baslatir.
     * Ornek: PvP bir Velocity proxy; backend'ler kapaliyken oyuncu gelirse proxy
     * "Ag kisa sureli bakimda" diye atiyor. Bu yuzden hepsi acilmadan aktarma yapilmaz.
     */
    private boolean ready(GameServer gs, ExarotonClient.ServerInfo main) throws Exception {
        boolean ok = startIfDown(gs.id(), main.status()) && main.status() == ExarotonClient.ONLINE;
        for (String dep : gs.requires()) {
            ExarotonClient.ServerInfo d = plugin.api().get(dep);
            if (!startIfDown(dep, d.status()) || d.status() != ExarotonClient.ONLINE) ok = false;
        }
        return ok;
    }

    /** Kapali/cokmusse baslatir; false = su an acik degil. */
    private boolean startIfDown(String id, int status) throws Exception {
        if (status == ExarotonClient.OFFLINE || status == ExarotonClient.CRASHED) {
            plugin.api().start(id);
            return false;
        }
        return status == ExarotonClient.ONLINE;
    }

    private void flush(GameServer gs, ExarotonClient.ServerInfo info) {
        Set<UUID> set = waiting.remove(gs.key());
        removeBar(gs.key());
        if (set == null) return;
        for (UUID u : set) {
            playerTarget.remove(u);
            Player p = Bukkit.getPlayer(u);
            if (p != null) send(p, gs, info);
        }
    }

    private void send(Player p, GameServer gs, ExarotonClient.ServerInfo info) {
        if (!p.isOnline()) return;
        int proto = clientProtocol(p);
        if (proto >= 0 && proto < TRANSFER_MIN_PROTOCOL) {
            // Eski istemciler (1.20.4 ve alti) transfer paketini desteklemiyor
            p.sendMessage(Component.text("» ", NamedTextColor.DARK_GRAY).append(gs.displayName())
                    .append(Component.text(" acik! ", NamedTextColor.GREEN))
                    .append(Component.text("Surumun otomatik gecisi desteklemiyor, sunucu listesine su adresi ekle: ", NamedTextColor.GRAY))
                    .append(Component.text(info.address(), NamedTextColor.AQUA)
                            .clickEvent(ClickEvent.copyToClipboard(info.address()))
                            .hoverEvent(HoverEvent.showText(Component.text("Kopyalamak icin tikla")))));
            return;
        }
        p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1.2f);
        p.sendMessage(Component.text("» ", NamedTextColor.DARK_GRAY).append(gs.displayName()).append(Component.text(" sunucusuna gonderiliyorsun...", NamedTextColor.GREEN)));
        p.transfer(info.connectHost(), info.port());
    }

    public void leave(UUID u) {
        String key = playerTarget.remove(u);
        if (key == null) return;
        Set<UUID> set = waiting.get(key);
        if (set != null) set.remove(u);
        Player p = Bukkit.getPlayer(u);
        BossBar bar = bars.get(key);
        if (p != null && bar != null) p.hideBossBar(bar);
    }

    private void removeBar(String key) {
        BossBar bar = bars.remove(key);
        if (bar != null) for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(bar);
    }

    private static String statusText(ExarotonClient.ServerInfo i) {
        if (i == null) return "bekleniyor";
        return switch (i.status()) {
            case ExarotonClient.ONLINE -> "oyun sunuculari aciliyor";
            case ExarotonClient.STARTING -> "baslatiliyor";
            case ExarotonClient.LOADING -> "yukleniyor";
            case ExarotonClient.PREPARING, ExarotonClient.PENDING -> "hazirlaniyor";
            case ExarotonClient.STOPPING, ExarotonClient.SAVING -> "kapaniyordu, yeniden aciliyor";
            default -> "bekleniyor";
        };
    }

    /** ViaVersion varsa istemcinin gercek protokolu, yoksa sunucu protokolu. -1 = bilinmiyor. */
    private static int clientProtocol(Player p) {
        try {
            Class<?> via = Class.forName("com.viaversion.viaversion.api.Via");
            Object api = via.getMethod("getAPI").invoke(null);
            return (int) api.getClass().getMethod("getPlayerVersion", UUID.class).invoke(api, p.getUniqueId());
        } catch (Throwable t) {
            try { return p.getProtocolVersion(); } catch (Throwable t2) { return -1; }
        }
    }
}
