package com.pisan.core;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Mod basina ayri envanter: "sky" (skyblock + sky lobi) ve "smp" (SMP dunyalari + SMP lobi).
 * Mod degisince envanter, zirh, sol el, ender sandigi, XP, can, aclik ve efektler takas edilir.
 * Mod secimi (hub) ve duel dunyasi tarafsizdir: oradayken envanter degismez.
 * Aktif mod oyuncunun kendi verisinde (PDC) tutulur, diger modun envanteri inventories/<uuid>.yml'de.
 */
public final class InventoryManager implements Listener {

    public static final String SKY = "sky", SMP = "smp";

    private final PisanCore plugin;
    private final NamespacedKey ACTIVE;
    private final File dir;

    public InventoryManager(PisanCore plugin) {
        this.plugin = plugin;
        this.ACTIVE = new NamespacedKey(plugin, "inv_mode");
        this.dir = new File(plugin.getDataFolder(), "inventories");
    }

    private String pre() { return PisanCore.color(plugin.getConfig().getString("messages.prefix", "")); }

    /** Oyuncunun su an kullandigi envanter modu. */
    public String mode(Player p) {
        String m = p.getPersistentDataContainer().get(ACTIVE, PersistentDataType.STRING);
        return SMP.equals(m) ? SMP : SKY;
    }

    public static String modeName(String m) { return SMP.equals(m) ? "SMP" : "Skyblock"; }

    /** Konumun ait oldugu mod; tarafsiz yerlerde null. */
    private String modeAt(Location l) {
        if (l == null || l.getWorld() == null) return null;
        World w = l.getWorld();
        if (plugin.smp() != null && plugin.smp().isSmp(w)) return SMP;
        if (plugin.lobbyWorld(w)) {
            return switch (LobbyManager.area(l)) {
                case SMP -> SMP;
                case SKY -> SKY;
                case HUB -> null;
            };
        }
        if (w.getName().equals(DuelManager.WORLD)) return null;
        return SKY;
    }

    // ======================= olaylar =======================
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) { sync(e.getPlayer(), e.getTo()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorld(PlayerChangedWorldEvent e) { sync(e.getPlayer(), e.getPlayer().getLocation()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) sync(p, p.getLocation()); }, 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) sync(p, p.getLocation()); }, 1L);
    }

    /** Hedef konumun modu aktif moddan farkliysa envanteri takas eder. */
    public void sync(Player p, Location to) {
        String target = modeAt(to);
        if (target == null || p.isDead()) return;
        String cur = mode(p);
        if (cur.equals(target)) return;
        swap(p, cur, target);
    }

    // ======================= takas =======================
    private File file(UUID id) { return new File(dir, id + ".yml"); }

    private void swap(Player p, String from, String to) {
        p.closeInventory(); // acik menu (takas vb.) esyalari geri versin, imlecteki esya envantere donsun
        File f = file(p.getUniqueId());
        YamlConfiguration y = f.exists() ? YamlConfiguration.loadConfiguration(f) : new YamlConfiguration();

        // mevcut durumu "from" profiline yaz
        y.set(from + ".inv", encode(p.getInventory().getContents()));
        y.set(from + ".ender", encode(p.getEnderChest().getContents()));
        y.set(from + ".level", p.getLevel());
        y.set(from + ".exp", (double) p.getExp());
        y.set(from + ".health", p.getHealth());
        y.set(from + ".food", p.getFoodLevel());
        y.set(from + ".sat", (double) p.getSaturation());
        y.set(from + ".effects", new ArrayList<>(p.getActivePotionEffects()));

        // "to" profilini yukle (yoksa bos baslar)
        p.getInventory().clear();
        p.getEnderChest().clear();
        for (PotionEffect pe : p.getActivePotionEffects()) p.removePotionEffect(pe.getType());
        p.setFireTicks(0);
        p.setFallDistance(0);
        boolean has = y.isSet(to + ".inv");
        p.getInventory().setContents(decode(y.getStringList(to + ".inv"), p.getInventory().getSize()));
        p.getEnderChest().setContents(decode(y.getStringList(to + ".ender"), p.getEnderChest().getSize()));
        p.setLevel(y.getInt(to + ".level", 0));
        p.setExp((float) Math.max(0, Math.min(0.999, y.getDouble(to + ".exp", 0))));
        double max = 20;
        try { var a = p.getAttribute(Attribute.MAX_HEALTH); if (a != null) max = a.getValue(); } catch (Throwable ignored) { }
        p.setHealth(Math.max(1, Math.min(max, y.getDouble(to + ".health", max))));
        p.setFoodLevel(y.getInt(to + ".food", 20));
        p.setSaturation((float) y.getDouble(to + ".sat", 5));
        List<?> eff = y.getList(to + ".effects");
        if (eff != null) for (Object o : eff) if (o instanceof PotionEffect pe) p.addPotionEffect(pe);

        // "to" profili artik oyuncunun uzerinde: dosyadan sil (cift kopya olmasin)
        y.set(to, null);
        p.getPersistentDataContainer().set(ACTIVE, PersistentDataType.STRING, to);
        try { dir.mkdirs(); y.save(f); } catch (IOException ex) { plugin.getLogger().severe("Envanter kaydedilemedi " + p.getName() + ": " + ex.getMessage()); }
        try { p.saveData(); } catch (Throwable ignored) { }
        p.updateInventory();
        p.sendMessage(pre() + "§7Envanter: §f" + modeName(to) + (has ? "" : " §8(yeni, boş başladın)"));
    }

    private static List<String> encode(ItemStack[] items) {
        List<String> out = new ArrayList<>(items.length);
        for (ItemStack it : items)
            out.add(it == null || it.getType().isAir() || it.getAmount() <= 0 ? "" : Base64.getEncoder().encodeToString(it.serializeAsBytes()));
        return out;
    }

    private ItemStack[] decode(List<String> list, int size) {
        ItemStack[] out = new ItemStack[size];
        for (int i = 0; i < Math.min(size, list.size()); i++) {
            String s = list.get(i);
            if (s == null || s.isEmpty()) continue;
            try { out[i] = ItemStack.deserializeBytes(Base64.getDecoder().decode(s)); }
            catch (Throwable t) { plugin.getLogger().warning("Esya okunamadi (slot " + i + "): " + t.getMessage()); }
        }
        return out;
    }

    // Lobide esya atma/toplama yok (tarafsiz hub uzerinden modlar arasi esya tasinmasin)
    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (plugin.lobbyWorld(e.getPlayer().getWorld()) && !e.getPlayer().hasPermission("pisancore.admin")) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(org.bukkit.event.entity.EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player && plugin.lobbyWorld(e.getEntity().getWorld())) e.setCancelled(true);
    }

    /** Iki oyuncu ayni envanter modunda mi (duel/takas icin). */
    public boolean sameMode(Player a, Player b) { return mode(a).equals(mode(b)); }
}
