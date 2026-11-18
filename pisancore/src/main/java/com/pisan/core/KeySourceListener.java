package com.pisan.core;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Set;

/**
 * Jenerator disinda anahtar kaynaklari: dusman yaratik oldurmek ve olgun ekin hasadi.
 * Sanslar = jenerator anahtar sanslari x carpan (config: key-sources).
 */
public final class KeySourceListener implements Listener {
    private static final Set<Material> CROPS = Set.of(Material.WHEAT, Material.CARROTS, Material.POTATOES,
            Material.BEETROOTS, Material.NETHER_WART, Material.COCOA, Material.MELON, Material.PUMPKIN);

    private final PisanCore plugin;

    public KeySourceListener(PisanCore plugin) { this.plugin = plugin; }

    private double mult(String path, double def) { return plugin.getConfig().getDouble("key-sources." + path, def); }

    @EventHandler(priority = EventPriority.HIGH)
    public void onKill(EntityDeathEvent e) {
        LivingEntity en = e.getEntity();
        Player k = en.getKiller();
        if (k == null || en instanceof Player || !(en instanceof Enemy)) return;
        if (k.getGameMode() != GameMode.SURVIVAL || plugin.lobbyWorld(en.getWorld())) return;
        double m = mult("mobs", 1.5);
        try { if (en.getEntitySpawnReason() == CreatureSpawnEvent.SpawnReason.SPAWNER) m *= mult("spawner-mobs", 0.25); }
        catch (Throwable ignored) { }
        ItemStack key = plugin.generator().rollKey(k, m);
        if (key != null) e.getDrops().add(key);
    }

    // SkillManager MONITOR'da "konuldu" kaydini sildiginden, ondan once (HIGH) calisir
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHarvest(BlockBreakEvent e) {
        Block b = e.getBlock();
        Material t = b.getType();
        if (!CROPS.contains(t)) return;
        Player p = e.getPlayer();
        if (p.getGameMode() != GameMode.SURVIVAL) return;
        if (b.getBlockData() instanceof Ageable a && a.getAge() < a.getMaximumAge()) return; // olgun degil
        if ((t == Material.MELON || t == Material.PUMPKIN) && plugin.skills().isPlaced(b)) return; // elle konmus
        ItemStack key = plugin.generator().rollKey(p, mult("crops", 0.4));
        if (key != null) b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 0.5, 0.5), key);
    }
}
