package com.pisan.core;

import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.*;

public final class MinionCommand implements CommandExecutor, TabCompleter {
    private final PisanCore plugin;
    private final MinionManager mm;

    public MinionCommand(PisanCore plugin, MinionManager mm) { this.plugin = plugin; this.mm = mm; }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String pre = PisanCore.color(plugin.getConfig().getString("messages.prefix", ""));
        if (a.length >= 1 && a[0].equalsIgnoreCase("ver")) {
            if (!s.hasPermission("pisancore.admin")) { s.sendMessage(pre + "§cYetkin yok."); return true; }
            if (a.length < 3) { s.sendMessage(pre + "/minyon ver <oyuncu> <tür> [seviye] [adet]"); return true; }
            Player t = Bukkit.getPlayerExact(a[1]);
            if (t == null) { s.sendMessage(pre + "§cOyuncu çevrimiçi değil."); return true; }
            MinionManager.Type type = mm.type(a[2]);
            if (type == null) { s.sendMessage(pre + "§cTür yok. Türler: " + String.join(", ", ids())); return true; }
            int tier = 1, amt = 1;
            try { if (a.length >= 4) tier = Math.max(1, Math.min(mm.maxTier(), Integer.parseInt(a[3]))); if (a.length >= 5) amt = Integer.parseInt(a[4]); }
            catch (NumberFormatException ex) { s.sendMessage(pre + "§cGeçersiz sayı."); return true; }
            mm.give(t, type, tier, amt);
            s.sendMessage(pre + "§a" + t.getName() + " oyuncusuna " + amt + "x " + PisanCore.color(type.name()) + " §averildi.");
            return true;
        }
        if (s instanceof Player p) mm.openShop(p);
        else s.sendMessage("/minyon ver <oyuncu> <tür> [seviye] [adet]");
        return true;
    }

    private List<String> ids() { List<String> l = new ArrayList<>(); for (MinionManager.Type t : mm.types()) l.add(t.id()); return l; }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission("pisancore.admin")) return List.of();
        List<String> out = new ArrayList<>();
        if (a.length == 1) out.add("ver");
        else if (a.length == 2) for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        else if (a.length == 3) out.addAll(ids());
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
