package com.pisan.core;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.UUID;

/** BentoBox varsa ada uyeligini kontrol eder. BentoBox siniflari sadece burada yuklenir. */
final class IslandHook {
    private IslandHook() { }

    static boolean present() { return Bukkit.getPluginManager().isPluginEnabled("BentoBox"); }

    /** null = ada dunyasinda degil / ada yok; true/false = uye mi */
    static Boolean isMember(Location loc, UUID player) {
        if (!present()) return null;
        return Impl.isMember(loc, player);
    }

    static boolean canManage(Player p, Location loc, UUID owner) {
        if (p.getUniqueId().equals(owner) || p.hasPermission("pisancore.admin")) return true;
        Boolean m = isMember(loc, p.getUniqueId());
        return m != null && m;
    }

    private static final class Impl {
        static Boolean isMember(Location loc, UUID player) {
            var bb = world.bentobox.bentobox.BentoBox.getInstance();
            if (bb == null || !bb.getIWM().inWorld(loc)) return null;
            return bb.getIslands().getProtectedIslandAt(loc).map(i -> i.getMemberSet().contains(player)).orElse(false);
        }
    }
}
