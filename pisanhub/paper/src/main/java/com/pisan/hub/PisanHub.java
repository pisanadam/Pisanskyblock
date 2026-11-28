package com.pisan.hub;

import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.protocols.v1_20_3to1_20_5.Protocol1_20_3To1_20_5;
import com.viaversion.viaversion.protocols.v1_20_3to1_20_5.packet.ClientboundPackets1_20_5;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.*;

public final class PisanHub extends JavaPlugin {
    private static final int TRANSFER_MIN_PROTOCOL = 766; // 1.20.5
    private LobbyApi api;
    private final Set<UUID> waiting = Collections.synchronizedSet(new HashSet<>());
    private boolean polling;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        api = new LobbyApi(getConfig().getString("exaroton-token"), getConfig().getString("lobby-id", "kYqtelwqpRHsIbWp"));
        getServer().getPluginManager().registerEvents(new TicketGate(this), this);
        if (!api.ready()) getLogger().warning("config.yml icinde exaroton-token ayarlanmamis!");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Sadece oyuncular."); return true; }
        if (!api.ready()) { p.sendMessage(ChatColor.RED + "Ana lobi ayarlanmamis."); return true; }
        if (waiting.contains(p.getUniqueId())) { p.sendMessage(ChatColor.YELLOW + "Ana lobi aciliyor, bekle..."); return true; }
        p.sendMessage(ChatColor.DARK_GRAY + "» " + ChatColor.LIGHT_PURPLE + "Pisan Games" + ChatColor.GRAY + " lobisine baglaniliyor...");
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                LobbyApi.Target t = api.status();
                if (t.status() == 1) { Bukkit.getScheduler().runTask(this, () -> send(p, t)); return; }
                if (t.status() == 0 || t.status() == 7) api.start();
                waiting.add(p.getUniqueId());
                Bukkit.getScheduler().runTask(this, () -> {
                    p.sendMessage(ChatColor.YELLOW + "Ana lobi uyuyordu, baslatiliyor. Acilinca otomatik gonderileceksin.");
                    if (!polling) { polling = true; poll(System.currentTimeMillis()); }
                });
            } catch (Exception e) {
                getLogger().warning("exaroton hatasi: " + e.getMessage());
                Bukkit.getScheduler().runTask(this, () -> p.sendMessage(ChatColor.RED + "Ana lobiye ulasilamadi."));
            }
        });
        return true;
    }

    private void poll(long startedAt) {
        Bukkit.getScheduler().runTaskLaterAsynchronously(this, () -> {
            LobbyApi.Target t = null;
            try { t = api.status(); } catch (Exception ignored) {}
            final LobbyApi.Target ft = t;
            boolean timeout = System.currentTimeMillis() - startedAt > getConfig().getInt("start-timeout-seconds", 180) * 1000L;
            Bukkit.getScheduler().runTask(this, () -> {
                List<UUID> list;
                synchronized (waiting) { list = new ArrayList<>(waiting); }
                if (list.isEmpty()) { polling = false; return; }
                if (ft != null && ft.status() == 1) {
                    Bukkit.getScheduler().runTaskLater(this, () -> {
                        for (UUID u : list) { waiting.remove(u); Player p = Bukkit.getPlayer(u); if (p != null) send(p, ft); }
                        polling = false;
                    }, 60L);
                    return;
                }
                if (timeout || (ft != null && ft.status() == 7)) {
                    for (UUID u : list) { waiting.remove(u); Player p = Bukkit.getPlayer(u); if (p != null) p.sendMessage(ChatColor.RED + "Ana lobi acilamadi, biraz sonra tekrar dene."); }
                    polling = false;
                    return;
                }
                for (UUID u : list) { Player p = Bukkit.getPlayer(u); if (p != null) p.sendActionBar(ChatColor.YELLOW + "Ana lobi aciliyor..."); }
                poll(startedAt);
            });
        }, 60L);
    }

    private void send(Player p, LobbyApi.Target t) {
        if (!p.isOnline()) return;
        // 1) Sunucu 1.20.5+ ise Paper'in kendi transfer metodu
        try {
            Method m = Player.class.getMethod("transfer", String.class, int.class);
            if (clientProtocol(p) >= TRANSFER_MIN_PROTOCOL) {
                p.sendMessage(ChatColor.GREEN + "Ana lobiye gonderiliyorsun...");
                m.invoke(p, t.host(), t.port());
                return;
            }
        } catch (NoSuchMethodException ignored) {
            // 2) Eski sunucu (1.20.1 vb.) + yeni istemci: transfer paketini ViaVersion ile gonder
            if (viaTransfer(p, t)) return;
        } catch (Exception e) {
            getLogger().warning("transfer hatasi: " + e);
        }
        p.sendMessage(ChatColor.YELLOW + "Surumun otomatik gecisi desteklemiyor. Sunucu listene ekle: " + ChatColor.AQUA + t.address());
    }

    private boolean viaTransfer(Player p, LobbyApi.Target t) {
        if (Bukkit.getPluginManager().getPlugin("ViaVersion") == null) return false;
        try {
            if (Via.getAPI().getPlayerVersion(p.getUniqueId()) < TRANSFER_MIN_PROTOCOL) return false;
            UserConnection user = Via.getAPI().getConnection(p.getUniqueId());
            if (user == null) return false;
            PacketWrapper w = PacketWrapper.create(ClientboundPackets1_20_5.TRANSFER, user);
            w.write(Types.STRING, t.host());
            w.write(Types.VAR_INT, t.port());
            p.sendMessage(ChatColor.GREEN + "Ana lobiye gonderiliyorsun...");
            w.send(Protocol1_20_3To1_20_5.class);
            return true;
        } catch (Throwable e) {
            getLogger().warning("ViaVersion transfer hatasi: " + e);
            return false;
        }
    }

    private int clientProtocol(Player p) {
        if (Bukkit.getPluginManager().getPlugin("ViaVersion") != null) {
            try { return Via.getAPI().getPlayerVersion(p.getUniqueId()); } catch (Throwable ignored) {}
        }
        try { return (int) Player.class.getMethod("getProtocolVersion").invoke(p); } catch (Throwable e) { return Integer.MAX_VALUE; }
    }
}
