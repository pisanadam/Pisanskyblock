package com.pisan.hub.velocity;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.player.CookieReceiveEvent;
import com.velocitypowered.api.event.player.GameProfileRequestEvent;
import com.velocitypowered.api.network.HandshakeIntent;
import com.velocitypowered.api.util.GameProfile;
import net.kyori.adventure.key.Key;
import java.util.concurrent.CompletableFuture;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.io.Reader;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Plugin(id = "pisanhub", name = "PisanHub", version = "1.1.0", authors = {"Pisan"},
        description = "/analobi - Pisan Games ana lobisine donus")
public final class PisanHubVelocity {
    private static final int TRANSFER_MIN_PROTOCOL = 766; // 1.20.5
    private final ProxyServer proxy;
    private final Logger log;
    private final Path dir;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Set<UUID> waiting = ConcurrentHashMap.newKeySet();
    private volatile boolean polling;
    private String token, lobbyId, secret, lobbyAddress;
    private final Properties uuids = new Properties();
    private final Map<UUID, CompletableFuture<byte[]>> cookieWait = new ConcurrentHashMap<>();
    private static final Key TICKET = Key.key("pisan", "ticket");
    private int timeoutSec;

    private record Target(int status, String host, int port, String address) {}

    @Inject
    public PisanHubVelocity(ProxyServer proxy, Logger log, @DataDirectory Path dir) {
        this.proxy = proxy; this.log = log; this.dir = dir;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent e) throws Exception {
        Files.createDirectories(dir);
        Path cfg = dir.resolve("config.properties");
        Properties p = new Properties();
        if (Files.exists(cfg)) try (Reader r = Files.newBufferedReader(cfg)) { p.load(r); }
        p.putIfAbsent("exaroton-token", "BURAYA_TOKEN");
        p.putIfAbsent("lobby-id", "kYqtelwqpRHsIbWp");
        p.putIfAbsent("start-timeout-seconds", "180");
        p.putIfAbsent("ticket-secret", "");
        p.putIfAbsent("lobby-address", "Pisangames.exaroton.me");
        try (Writer w = Files.newBufferedWriter(cfg)) { p.store(w, "PisanHub - exaroton-token GIT'E KOYMA"); }
        token = p.getProperty("exaroton-token"); lobbyId = p.getProperty("lobby-id");
        timeoutSec = Integer.parseInt(p.getProperty("start-timeout-seconds"));
        secret = p.getProperty("ticket-secret"); lobbyAddress = p.getProperty("lobby-address");
        Path up = dir.resolve("uuids.properties");
        if (Files.exists(up)) try (Reader r = Files.newBufferedReader(up)) { uuids.load(r); }
        if (token.startsWith("BURAYA")) log.warn("config.properties icinde exaroton-token ayarlanmamis!");

        proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("analobi").aliases("anamenu", "pisangames").plugin(this).build(),
                (SimpleCommand) inv -> {
                    if (inv.source() instanceof Player pl) analobi(pl);
                    else inv.source().sendMessage(Component.text("Sadece oyuncular."));
                });
    }

    // ------------------------------------------------------------------ lobiden crack giris
    /**
     * Proxy online-mode kalir (dogrudan gelen premium/1.8 oyuncular eskisi gibi).
     * Lobiden transfer ile gelen baglanti ise Mojang dogrulamasi olmadan alinir ve
     * LoginEvent'te lobinin imzali bileti (pisan:ticket cerezi) zorunlu tutulur.
     */
    @Subscribe
    public void onPreLogin(PreLoginEvent e) {
        if (secret == null || secret.isBlank() || !e.getResult().isAllowed()) return;
        if (e.getConnection().getHandshakeIntent() == HandshakeIntent.TRANSFER
                && e.getConnection().getProtocolVersion().getProtocol() >= TRANSFER_MIN_PROTOCOL) {
            e.setResult(PreLoginEvent.PreLoginComponentResult.forceOfflineMode());
        }
    }

    /** Ayni ismin premium girislerdeki UUID'si korunur (istatistikler kaybolmasin). */
    @Subscribe
    public void onProfile(GameProfileRequestEvent e) {
        String k = e.getUsername().toLowerCase(Locale.ROOT);
        if (e.isOnlineMode()) {
            if (!e.getGameProfile().getId().toString().equals(uuids.getProperty(k))) {
                uuids.setProperty(k, e.getGameProfile().getId().toString());
                saveUuids();
            }
            return;
        }
        String v = uuids.getProperty(k);
        if (v != null) e.setGameProfile(e.getGameProfile().withId(UUID.fromString(v)));
    }

    @Subscribe
    public EventTask onLogin(LoginEvent e) {
        Player p = e.getPlayer();
        if (p.isOnlineMode() || !e.getResult().isAllowed()) return null;
        CompletableFuture<byte[]> f = new CompletableFuture<>();
        cookieWait.put(p.getUniqueId(), f);
        p.requestCookie(TICKET);
        return EventTask.resumeWhenComplete(f.completeOnTimeout(null, 5, TimeUnit.SECONDS).handle((data, err) -> {
            cookieWait.remove(p.getUniqueId());
            String who = err == null ? Ticket.verify(secret, data) : null;
            if (who == null || !who.equalsIgnoreCase(p.getUsername())) {
                log.info("{} bilet olmadan girmeye calisti ({})", p.getUsername(), data == null ? "cerez yok" : "gecersiz bilet");
                e.setResult(ResultedEvent.ComponentResult.denied(Component.text("Bu sunucuya Pisan Games lobisinden gir: ", NamedTextColor.RED)
                        .append(Component.text(lobbyAddress, NamedTextColor.AQUA))));
            }
            return null;
        }));
    }

    @Subscribe
    public void onCookie(CookieReceiveEvent e) {
        if (!TICKET.equals(e.getOriginalKey())) return;
        CompletableFuture<byte[]> f = cookieWait.get(e.getPlayer().getUniqueId());
        if (f != null) { f.complete(e.getOriginalData()); e.setResult(CookieReceiveEvent.ForwardResult.handled()); }
    }

    private synchronized void saveUuids() {
        try (Writer w = Files.newBufferedWriter(dir.resolve("uuids.properties"))) { uuids.store(w, "isim -> premium UUID"); }
        catch (Exception ex) { log.warn("uuids.properties yazilamadi: {}", ex.getMessage()); }
    }

    private void analobi(Player p) {
        if (token == null || token.startsWith("BURAYA")) { p.sendMessage(Component.text("Ana lobi ayarlanmamis.", NamedTextColor.RED)); return; }
        if (waiting.contains(p.getUniqueId())) { p.sendMessage(Component.text("Ana lobi aciliyor, bekle...", NamedTextColor.YELLOW)); return; }
        p.sendMessage(Component.text("» Pisan Games lobisine baglaniliyor...", NamedTextColor.LIGHT_PURPLE));
        proxy.getScheduler().buildTask(this, () -> {
            try {
                Target t = status();
                if (t.status() == 1) { send(p, t); return; }
                if (t.status() == 0 || t.status() == 7) call("start/");
                waiting.add(p.getUniqueId());
                p.sendMessage(Component.text("Ana lobi uyuyordu, baslatiliyor. Acilinca otomatik gonderileceksin.", NamedTextColor.YELLOW));
                synchronized (this) { if (!polling) { polling = true; poll(System.currentTimeMillis()); } }
            } catch (Exception ex) {
                log.warn("exaroton hatasi: {}", ex.getMessage());
                p.sendMessage(Component.text("Ana lobiye ulasilamadi.", NamedTextColor.RED));
            }
        }).schedule();
    }

    private void poll(long startedAt) {
        proxy.getScheduler().buildTask(this, () -> {
            if (waiting.isEmpty()) { polling = false; return; }
            Target t = null;
            try { t = status(); } catch (Exception ignored) {}
            if (t != null && t.status() == 1) {
                final Target ft = t;
                proxy.getScheduler().buildTask(this, () -> {
                    for (UUID u : new ArrayList<>(waiting)) { waiting.remove(u); proxy.getPlayer(u).ifPresent(pl -> send(pl, ft)); }
                    polling = false;
                }).delay(3, TimeUnit.SECONDS).schedule();
                return;
            }
            if (System.currentTimeMillis() - startedAt > timeoutSec * 1000L || (t != null && t.status() == 7)) {
                for (UUID u : new ArrayList<>(waiting)) {
                    waiting.remove(u);
                    proxy.getPlayer(u).ifPresent(pl -> pl.sendMessage(Component.text("Ana lobi acilamadi, biraz sonra tekrar dene.", NamedTextColor.RED)));
                }
                polling = false;
                return;
            }
            for (UUID u : waiting) proxy.getPlayer(u).ifPresent(pl -> pl.sendActionBar(Component.text("Ana lobi aciliyor...", NamedTextColor.YELLOW)));
            poll(startedAt);
        }).delay(3, TimeUnit.SECONDS).schedule();
    }

    private void send(Player p, Target t) {
        if (!p.isActive()) return;
        if (clientProtocol(p) < TRANSFER_MIN_PROTOCOL) {
            p.sendMessage(Component.text("Surumun otomatik gecisi desteklemiyor. Sunucu listene ekle: ", NamedTextColor.YELLOW)
                    .append(Component.text(t.address(), NamedTextColor.AQUA).clickEvent(ClickEvent.copyToClipboard(t.address()))));
            return;
        }
        p.sendMessage(Component.text("Ana lobiye gonderiliyorsun...", NamedTextColor.GREEN));
        p.transferToHost(InetSocketAddress.createUnresolved(t.host(), t.port()));
    }

    /** Proxy'de ViaVersion varsa istemcinin gercek surumu, yoksa Velocity'nin gordugu surum. */
    private int clientProtocol(Player p) {
        try {
            Class<?> via = Class.forName("com.viaversion.viaversion.api.Via");
            Object api = via.getMethod("getAPI").invoke(null);
            int v = (int) api.getClass().getMethod("getPlayerVersion", UUID.class).invoke(api, p.getUniqueId());
            if (v > 0) return v;
        } catch (Throwable ignored) {}
        return p.getProtocolVersion().getProtocol();
    }

    private JsonObject call(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.exaroton.com/v1/servers/" + lobbyId + "/" + path))
                .timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + token)
                .header("User-Agent", "PisanHub/1.0").GET().build();
        JsonObject o = JsonParser.parseString(http.send(req, HttpResponse.BodyHandlers.ofString()).body()).getAsJsonObject();
        if (!o.get("success").getAsBoolean()) throw new IllegalStateException(String.valueOf(o.get("error")));
        return o;
    }

    private Target status() throws Exception {
        JsonObject d = call("").getAsJsonObject("data");
        String addr = d.get("address").getAsString();
        String host = d.get("host").isJsonNull() ? addr : d.get("host").getAsString();
        return new Target(d.get("status").getAsInt(), host, d.get("port").getAsInt(), addr);
    }
}
