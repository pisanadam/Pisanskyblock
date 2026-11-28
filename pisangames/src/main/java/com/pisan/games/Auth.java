package com.pisan.games;

import io.papermc.paper.connection.PlayerCommonConnection;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.*;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.File;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * Lobi giris sistemi (online-mode=false icin). 1.21.6+ istemcilere oyuna girmeden once
 * kayit/giris penceresi (dialog) gosterilir; daha eski surumlere sohbetten /kayit ve /giris.
 * Hesaplar plugins/PisanGames/accounts.yml (PBKDF2 ozetleri). Ayni IP'den kisa sure icinde
 * tekrar gelen (or. oyundan /analobi ile donen) oyuncuya tekrar sifre sorulmaz.
 */
public final class Auth implements Listener {
    private static final int DIALOG_PROTOCOL = 771; // 1.21.6
    private static final Key OK = Key.key("pisangames", "auth_ok"), CANCEL = Key.key("pisangames", "auth_cancel");
    private static final TextColor GOLD = TextColor.color(0xF0A040);

    private final PisanGames plugin;
    private final File file;
    private final YamlConfiguration db;
    private final Set<UUID> authed = ConcurrentHashMap.newKeySet();
    /** dialog bekleyen baglantilar */
    private final Map<UUID, CompletableFuture<Map<String, String>>> pending = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> chatTries = new ConcurrentHashMap<>();
    private final SecureRandom rng = new SecureRandom();

    public Auth(PisanGames plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "accounts.yml");
        this.db = YamlConfiguration.loadConfiguration(file);
    }

    public boolean isAuthed(Player p) { return authed.contains(p.getUniqueId()); }

    private static String key(String name) { return name.toLowerCase(Locale.ROOT); }
    private boolean registered(String name) { return db.contains("accounts." + key(name) + ".hash"); }

    // ------------------------------------------------------------------ dialog (yapilandirma asamasi)
    @EventHandler
    public void onConfigure(AsyncPlayerConnectionConfigureEvent e) {
        PlayerConfigurationConnection conn = e.getConnection();
        UUID id = conn.getProfile().getId();
        String name = conn.getProfile().getName();
        if (id == null || name == null) return;
        if (name.equals(plugin.probeName())) { conn.disconnect(Component.text("test")); return; }
        String ip = conn.getClientAddress() == null ? "" : conn.getClientAddress().getAddress().getHostAddress();
        if (session(name, ip)) { authed.add(id); return; }
        if (protocol(id) < DIALOG_PROTOCOL) return; // eski surum: oyunda sohbetten giris

        String error = null;
        for (int attempt = 0; attempt < 5 && conn.isConnected(); attempt++) {
            boolean reg = registered(name);
            CompletableFuture<Map<String, String>> f = new CompletableFuture<>();
            pending.put(id, f);
            conn.getAudience().showDialog(dialog(name, reg, error));
            Map<String, String> r;
            try { r = f.get(3, TimeUnit.MINUTES); }
            catch (TimeoutException ex) { conn.disconnect(Component.text("Giriş süresi doldu.", NamedTextColor.RED)); return; }
            catch (Exception ex) { return; }
            finally { pending.remove(id); }
            if (r == null) { conn.disconnect(Component.text("Giriş iptal edildi.", NamedTextColor.RED)); return; }
            String pw = r.getOrDefault("pw", ""), pw2 = r.getOrDefault("pw2", "");
            error = reg ? login(name, pw) : register(name, pw, pw2, ip);
            if (error == null) {
                authed.add(id);
                rememberSession(name, ip);
                conn.getAudience().closeDialog();
                return;
            }
        }
        conn.disconnect(Component.text("Çok fazla hatalı deneme.", NamedTextColor.RED));
    }

    @EventHandler
    public void onClick(PlayerCustomClickEvent e) {
        PlayerCommonConnection c = e.getCommonConnection();
        UUID id = c instanceof PlayerConfigurationConnection pc ? pc.getProfile().getId()
                : c instanceof io.papermc.paper.connection.PlayerGameConnection gc ? gc.getPlayer().getUniqueId() : null;
        CompletableFuture<Map<String, String>> f = id == null ? null : pending.get(id);
        if (f == null) return;
        if (e.getIdentifier().equals(CANCEL)) { f.complete(null); return; }
        if (!e.getIdentifier().equals(OK) || e.getDialogResponseView() == null) return;
        Map<String, String> m = new HashMap<>();
        String a = e.getDialogResponseView().getText("pw"), b = e.getDialogResponseView().getText("pw2");
        m.put("pw", a == null ? "" : a);
        m.put("pw2", b == null ? "" : b);
        f.complete(m);
    }

    private Dialog dialog(String name, boolean reg, String error) {
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(Component.text(reg
                ? "Tekrar hoş geldin " + name + "! Devam etmek için şifreni yaz."
                : "Oynamaya başlamak için bir şifre belirle ve aynısını tekrar yaz.", NamedTextColor.WHITE), 300));
        if (error != null) body.add(DialogBody.plainMessage(Component.text(error, NamedTextColor.RED), 300));
        List<DialogInput> inputs = new ArrayList<>();
        inputs.add(DialogInput.text("pw", Component.text(reg ? "Şifren:" : "Şifre Belirleyin:")).width(260).maxLength(64).build());
        if (!reg) inputs.add(DialogInput.text("pw2", Component.text("Şifreyi Tekrar Yazın:")).width(260).maxLength(64).build());
        ActionButton yes = ActionButton.builder(Component.text(reg ? "Giriş Yap" : "Onayla ve Kayıt Ol", NamedTextColor.GREEN))
                .width(150).action(DialogAction.customClick(OK, null)).build();
        ActionButton no = ActionButton.builder(Component.text("İptal", NamedTextColor.RED))
                .width(150).action(DialogAction.customClick(CANCEL, null)).build();
        return Dialog.create(b -> b.empty()
                .base(DialogBase.builder(Component.text("Pisan Games " + (reg ? "Giriş" : "Kayıt") + " Sistemi", GOLD, TextDecoration.BOLD))
                        .canCloseWithEscape(false)
                        .afterAction(DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE)
                        .body(body).inputs(inputs).build())
                .type(DialogType.confirmation(yes, no)));
    }

    // ------------------------------------------------------------------ eski surumler: sohbet
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (isAuthed(p)) return;
        boolean reg = registered(p.getName());
        Bukkit.getScheduler().runTaskLater(plugin, () -> prompt(p, reg), 10L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && !isAuthed(p)) p.kick(Component.text("Giriş süresi doldu.", NamedTextColor.RED));
        }, 20L * 120);
    }

    private void prompt(Player p, boolean reg) {
        if (!p.isOnline() || isAuthed(p)) return;
        Component msg = reg ? Component.text("Giriş yap: ", NamedTextColor.YELLOW).append(Component.text("/giris <şifre>", NamedTextColor.AQUA))
                : Component.text("Kayıt ol: ", NamedTextColor.YELLOW).append(Component.text("/kayit <şifre> <şifre>", NamedTextColor.AQUA));
        p.sendMessage(msg);
        p.showTitle(Title.title(Component.text(reg ? "Giriş Yap" : "Kayıt Ol", GOLD, TextDecoration.BOLD), msg,
                Title.Times.times(Duration.ZERO, Duration.ofSeconds(100), Duration.ZERO)));
    }

    /** /kayit, /giris, /sifredegistir */
    public void command(Player p, String cmd, String[] a) {
        String ip = p.getAddress() == null ? "" : p.getAddress().getAddress().getHostAddress();
        if (cmd.equals("sifredegistir")) {
            if (!isAuthed(p)) { p.sendMessage(Component.text("Önce giriş yap.", NamedTextColor.RED)); return; }
            if (a.length < 2) { p.sendMessage(Component.text("/sifredegistir <eski> <yeni>", NamedTextColor.YELLOW)); return; }
            String err = login(p.getName(), a[0]);
            if (err == null) err = validate(a[1]);
            if (err != null) { p.sendMessage(Component.text(err, NamedTextColor.RED)); return; }
            store(p.getName(), a[1], null);
            p.sendMessage(Component.text("Şifren değiştirildi.", NamedTextColor.GREEN));
            return;
        }
        if (isAuthed(p)) { p.sendMessage(Component.text("Zaten giriş yaptın.", NamedTextColor.GRAY)); return; }
        boolean reg = registered(p.getName());
        String err;
        if (cmd.equals("kayit")) {
            if (reg) err = "Bu isim zaten kayıtlı, /giris <şifre> kullan.";
            else err = a.length < 2 ? "/kayit <şifre> <şifre>" : register(p.getName(), a[0], a[1], ip);
        } else {
            if (!reg) err = "Önce kayıt ol: /kayit <şifre> <şifre>";
            else err = a.length < 1 ? "/giris <şifre>" : login(p.getName(), a[0]);
        }
        if (err != null) {
            p.sendMessage(Component.text(err, NamedTextColor.RED));
            if (chatTries.merge(p.getUniqueId(), 1, Integer::sum) >= 5) p.kick(Component.text("Çok fazla hatalı deneme.", NamedTextColor.RED));
            return;
        }
        authed.add(p.getUniqueId());
        rememberSession(p.getName(), ip);
        chatTries.remove(p.getUniqueId());
        p.clearTitle();
        p.sendMessage(Component.text("Giriş başarılı, iyi oyunlar!", NamedTextColor.GREEN));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (isAuthed(e.getPlayer())) return;
        String c = e.getMessage().toLowerCase(Locale.ROOT);
        if (c.startsWith("/giris ") || c.startsWith("/kayit ") || c.startsWith("/login ") || c.startsWith("/register ")
                || c.equals("/giris") || c.equals("/kayit")) return;
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(io.papermc.paper.event.player.AsyncChatEvent e) { if (!isAuthed(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST)
    public void onMove(PlayerMoveEvent e) {
        if (!isAuthed(e.getPlayer()) && e.hasChangedPosition()) e.setTo(e.getFrom().setDirection(e.getTo().getDirection()));
    }
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent e) { if (!isAuthed(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST)
    public void onOpen(InventoryOpenEvent e) { if (e.getPlayer() instanceof Player p && !isAuthed(p)) e.setCancelled(true); }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        authed.remove(e.getPlayer().getUniqueId());
        chatTries.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ hesaplar
    private String validate(String pw) {
        if (pw.length() < 4) return "Şifre en az 4 karakter olmalı.";
        if (pw.length() > 64) return "Şifre çok uzun.";
        return null;
    }

    private synchronized String register(String name, String pw, String pw2, String ip) {
        if (registered(name)) return "Bu isim zaten kayıtlı.";
        String err = validate(pw);
        if (err != null) return err;
        if (!pw.equals(pw2)) return "Şifreler aynı değil.";
        int max = plugin.getConfig().getInt("auth.max-accounts-per-ip", 3);
        long sameIp = Optional.ofNullable(db.getConfigurationSection("accounts")).map(s -> s.getKeys(false).stream()
                .filter(k -> ip.equals(db.getString("accounts." + k + ".reg-ip"))).count()).orElse(0L);
        if (!ip.isEmpty() && sameIp >= max) return "Bu bağlantıdan en fazla " + max + " hesap açılabilir.";
        store(name, pw, ip);
        plugin.getLogger().info(name + " kayit oldu.");
        return null;
    }

    private String login(String name, String pw) {
        String k = "accounts." + key(name);
        String salt = db.getString(k + ".salt"), hash = db.getString(k + ".hash");
        if (salt == null || hash == null) return "Hesap bulunamadı.";
        if (!MessageDigest.isEqual(hash(pw, Base64.getDecoder().decode(salt)).getBytes(), hash.getBytes())) return "Şifre yanlış.";
        return null;
    }

    private synchronized void store(String name, String pw, String ip) {
        byte[] salt = new byte[16];
        rng.nextBytes(salt);
        String k = "accounts." + key(name);
        db.set(k + ".name", name);
        db.set(k + ".salt", Base64.getEncoder().encodeToString(salt));
        db.set(k + ".hash", hash(pw, salt));
        if (ip != null) { db.set(k + ".reg-ip", ip); db.set(k + ".created", System.currentTimeMillis()); }
        save();
    }

    private synchronized void rememberSession(String name, String ip) {
        if (ip.isEmpty()) return;
        db.set("accounts." + key(name) + ".session-ip", ip);
        db.set("accounts." + key(name) + ".session-until", System.currentTimeMillis() + plugin.getConfig().getInt("auth.session-minutes", 720) * 60_000L);
        save();
    }

    private boolean session(String name, String ip) {
        String k = "accounts." + key(name);
        return !ip.isEmpty() && registered(name) && ip.equals(db.getString(k + ".session-ip"))
                && db.getLong(k + ".session-until") > System.currentTimeMillis();
    }

    private void save() {
        String data = db.saveToString();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            synchronized (file) {
                try { java.nio.file.Files.writeString(file.toPath(), data); }
                catch (Exception ex) { plugin.getLogger().warning("accounts.yml yazilamadi: " + ex.getMessage()); }
            }
        });
    }

    private static String hash(String pw, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(pw.toCharArray(), salt, 60000, 256);
            return Base64.getEncoder().encodeToString(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded());
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    static int protocol(UUID id) {
        try {
            Class<?> via = Class.forName("com.viaversion.viaversion.api.Via");
            Object api = via.getMethod("getAPI").invoke(null);
            int v = (int) api.getClass().getMethod("getPlayerVersion", UUID.class).invoke(api, id);
            return v < 0 ? Integer.MAX_VALUE : v;
        } catch (Throwable t) { return Integer.MAX_VALUE; }
    }
}
