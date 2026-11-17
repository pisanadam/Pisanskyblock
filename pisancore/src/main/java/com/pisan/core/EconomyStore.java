package com.pisan.core;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class EconomyStore {
    private final PisanCore plugin;
    private final File file;
    private final Map<UUID, Double> balances = new ConcurrentHashMap<>();
    private final Map<UUID, String> names = new ConcurrentHashMap<>();
    private volatile boolean dirty;

    public EconomyStore(PisanCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "balances.yml");
    }

    public void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String k : y.getKeys(false)) {
            try {
                UUID id = UUID.fromString(k);
                balances.put(id, y.getDouble(k + ".balance"));
                String n = y.getString(k + ".name");
                if (n != null) names.put(id, n);
            } catch (IllegalArgumentException ignored) { }
        }
    }

    private synchronized void write() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Double> e : balances.entrySet()) {
            y.set(e.getKey() + ".balance", e.getValue());
            String n = names.get(e.getKey());
            if (n != null) y.set(e.getKey() + ".name", n);
        }
        try {
            plugin.getDataFolder().mkdirs();
            y.save(file);
            dirty = false;
        } catch (IOException ex) {
            plugin.getLogger().severe("balances.yml kaydedilemedi: " + ex.getMessage());
        }
    }

    public void saveAsync() { if (dirty) write(); }
    public void saveSync() { write(); }

    public double starting() { return plugin.getConfig().getDouble("economy.starting-balance", 500); }

    public boolean has(UUID id) { return balances.containsKey(id); }

    public void create(UUID id, String name) {
        balances.putIfAbsent(id, starting());
        if (name != null) names.put(id, name);
        dirty = true;
    }

    public void setName(UUID id, String name) { if (name != null) { names.put(id, name); dirty = true; } }

    public double get(UUID id) { return balances.getOrDefault(id, 0.0); }

    public void set(UUID id, double v) { balances.put(id, Math.max(0, v)); dirty = true; }

    public synchronized boolean withdraw(UUID id, double amt) {
        double cur = get(id);
        if (amt < 0 || cur < amt) return false;
        set(id, cur - amt);
        return true;
    }

    public synchronized void deposit(UUID id, double amt) {
        if (!balances.containsKey(id)) balances.put(id, starting());
        set(id, get(id) + amt);
    }

    public List<Map.Entry<UUID, Double>> top(int n) {
        List<Map.Entry<UUID, Double>> l = new ArrayList<>(balances.entrySet());
        l.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        return l.subList(0, Math.min(n, l.size()));
    }

    public String name(UUID id) { return names.getOrDefault(id, id.toString().substring(0, 8)); }
}
