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

    public static final int VISITOR = 0, COOP = 200, TRUSTED = 400, MEMBER = 500;

    /** Oyuncunun adasinin ev noktasi (yoksa null). */
    static org.bukkit.Location home(UUID owner) {
        if (!present()) return null;
        try { return Impl.home(owner); } catch (Throwable t) { return null; }
    }

    static boolean hasIsland(UUID owner) {
        if (!present()) return false;
        try { return Impl.island(owner) != null; } catch (Throwable t) { return false; }
    }

    static boolean banned(UUID owner, UUID visitor) {
        if (!present()) return false;
        try { var i = Impl.island(owner); return i != null && i.isBanned(visitor); } catch (Throwable t) { return false; }
    }

    /** owner'in adasinda other'in rutbesi (-1 = ada yok). */
    static int rank(UUID owner, UUID other) {
        if (!present()) return -1;
        try { var i = Impl.island(owner); return i == null ? -1 : i.getRank(other); } catch (Throwable t) { return -1; }
    }

    /** owner'in adasinda other'a rutbe ver. Ekip uyelerine dokunmaz. */
    static boolean setRank(UUID owner, UUID other, int rank) {
        if (!present()) return false;
        try {
            var i = Impl.island(owner);
            if (i == null || i.getRank(other) >= MEMBER) return false;
            if (rank <= VISITOR) i.removeMember(other); else i.setRank(other, rank);
            try { world.bentobox.bentobox.managers.IslandsManager.updateIsland(i); } catch (Throwable ignored) { }
            return true;
        } catch (Throwable t) { return false; }
    }

    private static final class Impl {
        static org.bukkit.World world() {
            var w = Bukkit.getWorld("bskyblock_world");
            return w;
        }

        static world.bentobox.bentobox.database.objects.Island island(UUID owner) {
            var bb = world.bentobox.bentobox.BentoBox.getInstance();
            if (bb == null || world() == null) return null;
            return bb.getIslands().getIsland(world(), owner);
        }

        static org.bukkit.Location home(UUID owner) {
            var i = island(owner);
            if (i == null) return null;
            var bb = world.bentobox.bentobox.BentoBox.getInstance();
            org.bukkit.Location l = null;
            try { l = bb.getIslands().getHomeLocation(i); } catch (Throwable ignored) { }
            if (l == null) {
                org.bukkit.Location c = i.getProtectionCenter();
                l = c.getWorld().getHighestBlockAt(c).getLocation().add(0.5, 1, 0.5);
            }
            return l;
        }

        static Boolean isMember(Location loc, UUID player) {
            var bb = world.bentobox.bentobox.BentoBox.getInstance();
            if (bb == null || !bb.getIWM().inWorld(loc)) return null;
            return bb.getIslands().getProtectedIslandAt(loc).map(i -> i.getMemberSet().contains(player)).orElse(false);
        }
    }
}
