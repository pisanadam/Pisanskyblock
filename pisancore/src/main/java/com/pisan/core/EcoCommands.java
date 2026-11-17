package com.pisan.core;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.*;

public final class EcoCommands implements CommandExecutor, TabCompleter {
    private final PisanCore plugin;
    private final EconomyStore store;

    public EcoCommands(PisanCore plugin, EconomyStore store) { this.plugin = plugin; this.store = store; }

    private String p() { return PisanCore.color(plugin.getConfig().getString("messages.prefix", "")); }

    @SuppressWarnings("deprecation")
    private OfflinePlayer find(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        OfflinePlayer off = Bukkit.getOfflinePlayer(name);
        return (off.hasPlayedBefore() || store.has(off.getUniqueId())) ? off : null;
    }

    private Double amount(String s) {
        try {
            double d = Double.parseDouble(s.replace(",", "."));
            if (Double.isNaN(d) || Double.isInfinite(d) || d <= 0) return null;
            return Math.floor(d * 100) / 100.0;
        } catch (NumberFormatException e) { return null; }
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        switch (cmd.getName().toLowerCase(Locale.ROOT)) {
            case "bal" -> {
                if (a.length >= 1) {
                    OfflinePlayer t = find(a[0]);
                    if (t == null) { s.sendMessage(p() + "§cOyuncu bulunamadı."); return true; }
                    s.sendMessage(p() + "§f" + t.getName() + " §7bakiyesi: §6" + PisanCore.fmt(store.get(t.getUniqueId())) + " Coin");
                } else if (s instanceof Player pl) {
                    s.sendMessage(p() + "Bakiyen: §6" + PisanCore.fmt(store.get(pl.getUniqueId())) + " Coin");
                } else s.sendMessage("Kullanım: /bal <oyuncu>");
            }
            case "pay" -> {
                if (!(s instanceof Player pl)) { s.sendMessage("Sadece oyuncular."); return true; }
                if (a.length < 2) { s.sendMessage(p() + "Kullanım: §f/pay <oyuncu> <miktar>"); return true; }
                OfflinePlayer t = find(a[0]);
                Double amt = amount(a[1]);
                if (t == null) { s.sendMessage(p() + "§cOyuncu bulunamadı."); return true; }
                if (t.getUniqueId().equals(pl.getUniqueId())) { s.sendMessage(p() + "§cKendine para gönderemezsin."); return true; }
                if (amt == null) { s.sendMessage(p() + "§cGeçersiz miktar."); return true; }
                if (!store.withdraw(pl.getUniqueId(), amt)) { s.sendMessage(p() + "§cYeterli Coin'in yok."); return true; }
                store.deposit(t.getUniqueId(), amt);
                s.sendMessage(p() + "§f" + t.getName() + " §7oyuncusuna §6" + PisanCore.fmt(amt) + " Coin §7gönderdin.");
                if (t.getPlayer() != null) t.getPlayer().sendMessage(p() + "§f" + pl.getName() + " §7sana §6" + PisanCore.fmt(amt) + " Coin §7gönderdi.");
            }
            case "baltop" -> {
                s.sendMessage(p() + "§6§lEn Zengin 10 Oyuncu");
                int i = 1;
                for (Map.Entry<UUID, Double> e : store.top(10))
                    s.sendMessage(" §e" + (i++) + ". §f" + store.name(e.getKey()) + " §8- §6" + PisanCore.fmt(e.getValue()));
            }
            case "eco" -> {
                if (a.length < 3) { s.sendMessage(p() + "Kullanım: §f/eco <give|take|set> <oyuncu> <miktar>"); return true; }
                OfflinePlayer t = find(a[1]);
                if (t == null) { s.sendMessage(p() + "§cOyuncu bulunamadı."); return true; }
                double amt;
                try { amt = Double.parseDouble(a[2]); } catch (NumberFormatException e) { s.sendMessage(p() + "§cGeçersiz miktar."); return true; }
                UUID id = t.getUniqueId();
                switch (a[0].toLowerCase(Locale.ROOT)) {
                    case "give" -> store.deposit(id, Math.abs(amt));
                    case "take" -> store.set(id, store.get(id) - Math.abs(amt));
                    case "set" -> store.set(id, amt);
                    default -> { s.sendMessage(p() + "§cgive / take / set"); return true; }
                }
                if (t.getName() != null) store.setName(id, t.getName());
                s.sendMessage(p() + "§f" + t.getName() + " §7yeni bakiye: §6" + PisanCore.fmt(store.get(id)));
            }
            case "pisancore" -> {
                if (a.length >= 1 && a[0].equalsIgnoreCase("reload")) { plugin.reload(); s.sendMessage(p() + "§aYeniden yüklendi."); }
                else s.sendMessage(p() + "/pisancore reload  §7(anahtar vermek: /kasa ver)");
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        String n = cmd.getName().toLowerCase(Locale.ROOT);
        if (n.equals("eco") && a.length == 1) return filter(List.of("give", "take", "set"), a[0]);
        if (n.equals("pisancore") && a.length == 1) return filter(List.of("reload"), a[0]);
        if ((n.equals("bal") || n.equals("pay")) && a.length == 1 || n.equals("eco") && a.length == 2 || n.equals("pisancore") && a.length == 2) {
            List<String> names = new ArrayList<>();
            for (Player pl : Bukkit.getOnlinePlayers()) names.add(pl.getName());
            return filter(names, a[a.length - 1]);
        }
        return Collections.emptyList();
    }

    private static List<String> filter(List<String> l, String pre) {
        List<String> out = new ArrayList<>();
        for (String x : l) if (x.toLowerCase(Locale.ROOT).startsWith(pre.toLowerCase(Locale.ROOT))) out.add(x);
        return out;
    }
}
