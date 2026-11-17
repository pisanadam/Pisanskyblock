package com.pisan.core;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public final class JoinListener implements Listener {
    private final PisanCore plugin;
    private final EconomyStore store;

    public JoinListener(PisanCore plugin, EconomyStore store) { this.plugin = plugin; this.store = store; }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        boolean isNew = !store.has(p.getUniqueId());
        store.create(p.getUniqueId(), p.getName());
        store.setName(p.getUniqueId(), p.getName());
        plugin.crates().deliverPending(p);
        if (!isNew || !plugin.getConfig().getBoolean("starter-keys.enabled", true)) return;

        ConfigurationSection keys = plugin.getConfig().getConfigurationSection("starter-keys.keys");
        if (keys == null) return;
        for (String k : keys.getKeys(false)) plugin.giveKey(p.getUniqueId(), k, keys.getInt(k, 1));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            p.sendMessage(PisanCore.color("&6&lHoş geldin! &eSana başlangıç için &a3 Çaylak Anahtarı &everdik. &7/kasa ile aç."));
        }, 60L);
    }
}
