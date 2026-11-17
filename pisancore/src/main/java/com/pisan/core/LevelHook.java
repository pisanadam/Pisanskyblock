package com.pisan.core;

import org.bukkit.Bukkit;
import org.bukkit.World;

import java.lang.reflect.Method;
import java.util.*;

/** BentoBox Level eklentisine yansima (reflection) ile erisir; eklenti yoksa sessizce 0 dondurur. */
final class LevelHook {
    private static final String WORLD = "bskyblock_world";
    private Object addon, manager;
    private Method getLevel, getRank, getTopTen;
    private long lastTry;

    private boolean ready() {
        if (addon != null) return true;
        if (System.currentTimeMillis() - lastTry < 10_000) return false;
        lastTry = System.currentTimeMillis();
        try {
            if (!Bukkit.getPluginManager().isPluginEnabled("BentoBox")) return false;
            Object bb = Class.forName("world.bentobox.bentobox.BentoBox").getMethod("getInstance").invoke(null);
            Object am = bb.getClass().getMethod("getAddonsManager").invoke(bb);
            Optional<?> opt = (Optional<?>) am.getClass().getMethod("getAddonByName", String.class).invoke(am, "Level");
            if (opt.isEmpty()) return false;
            Object a = opt.get();
            Object m = a.getClass().getMethod("getManager").invoke(a);
            getLevel = m.getClass().getMethod("getIslandLevel", World.class, UUID.class);
            getRank = m.getClass().getMethod("getRank", World.class, UUID.class);
            getTopTen = m.getClass().getMethod("getTopTen", World.class, int.class);
            addon = a; manager = m;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private World world() { return Bukkit.getWorld(WORLD); }

    long level(UUID player) {
        if (!ready() || world() == null) return 0;
        try { return (long) getLevel.invoke(manager, world(), player); } catch (Throwable t) { return 0; }
    }

    int rank(UUID player) {
        if (!ready() || world() == null) return 0;
        try { return (int) getRank.invoke(manager, world(), player); } catch (Throwable t) { return 0; }
    }

    @SuppressWarnings("unchecked")
    Map<String, Long> top(int n) {
        if (!ready() || world() == null) return Map.of();
        try { return (Map<String, Long>) getTopTen.invoke(manager, world(), n); } catch (Throwable t) { return Map.of(); }
    }
}
