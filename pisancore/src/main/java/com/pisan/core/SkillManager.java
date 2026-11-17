package com.pisan.core;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Hypixel tarzi skill sistemi: Madencilik, Ciftcilik, Odunculuk, Savas, Balikcilik.
 * Oyuncunun koydugu bloklar XP vermez (koy-kir istismari yok). Her seviye kalici bonus + coin odulu verir.
 */
public final class SkillManager implements Listener, CommandExecutor, TabCompleter {

    public enum Skill {
        MADENCILIK("&bMadencilik", Material.IRON_PICKAXE, "Cevher/taş kırarken çift düşme şansı", 1.0),
        CIFTCILIK("&aÇiftçilik", Material.GOLDEN_HOE, "Hasatta çift ürün şansı", 1.0),
        ODUNCULUK("&6Odunculuk", Material.IRON_AXE, "Odun keserken çift düşme şansı", 1.0),
        SAVAS("&cSavaş", Material.IRON_SWORD, "Yaratıklara ekstra hasar", 0.5),
        BALIKCILIK("&9Balıkçılık", Material.FISHING_ROD, "Çift balık yakalama şansı", 1.0);

        final String name; final Material icon; final String perk; final double perLevel;
        Skill(String n, Material i, String p, double pl) { name = n; icon = i; perk = p; perLevel = pl; }
        String plain() { return ChatColor.stripColor(PisanCore.color(name)); }
    }

    public static final int MAX_LEVEL = 50;

    private final PisanCore plugin;
    private final File file, placedFile;
    private final Map<UUID, EnumMap<Skill, Double>> xp = new ConcurrentHashMap<>();
    private final Set<String> placed = Collections.newSetFromMap(new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) { return size() > 500_000; }
    });
    private final Map<String, Skill> pendingDouble = new HashMap<>(); // kirilan dogal blok -> skill (drop eventi icin)
    private boolean dirty;

    private static final Map<Material, Double> MINING = new EnumMap<>(Material.class);
    private static final Map<Material, Double> FARMING = new EnumMap<>(Material.class);
    private static final Map<EntityType, Double> COMBAT = new EnumMap<>(EntityType.class);
    static {
        put(MINING, 1, "COBBLESTONE", "STONE", "DEEPSLATE", "COBBLED_DEEPSLATE", "BASALT", "NETHERRACK", "END_STONE");
        put(MINING, 5, "COAL_ORE", "DEEPSLATE_COAL_ORE", "NETHER_QUARTZ_ORE");
        put(MINING, 4, "COPPER_ORE", "DEEPSLATE_COPPER_ORE");
        put(MINING, 7, "IRON_ORE", "DEEPSLATE_IRON_ORE");
        put(MINING, 8, "REDSTONE_ORE", "DEEPSLATE_REDSTONE_ORE", "NETHER_GOLD_ORE");
        put(MINING, 9, "LAPIS_ORE", "DEEPSLATE_LAPIS_ORE");
        put(MINING, 10, "GOLD_ORE", "DEEPSLATE_GOLD_ORE");
        put(MINING, 20, "DIAMOND_ORE", "DEEPSLATE_DIAMOND_ORE");
        put(MINING, 25, "EMERALD_ORE", "DEEPSLATE_EMERALD_ORE");
        put(MINING, 40, "ANCIENT_DEBRIS");
        put(MINING, 3, "OBSIDIAN");
        put(FARMING, 2, "WHEAT", "CARROTS", "POTATOES", "BEETROOTS", "COCOA");
        put(FARMING, 3, "NETHER_WART", "MELON", "PUMPKIN");
        put(FARMING, 1, "SUGAR_CANE", "CACTUS", "BAMBOO");
        for (EntityType t : EntityType.values()) {
            Class<?> c = t.getEntityClass();
            if (c == null) continue;
            if (Monster.class.isAssignableFrom(c) || Slime.class.isAssignableFrom(c) || Ghast.class.isAssignableFrom(c) || Phantom.class.isAssignableFrom(c)) COMBAT.put(t, 6.0);
            else if (Animals.class.isAssignableFrom(c)) COMBAT.put(t, 2.0);
        }
        putE(10, "CREEPER", "WITCH"); putE(15, "ENDERMAN", "BLAZE", "GUARDIAN", "PIGLIN_BRUTE", "BREEZE");
        putE(4, "SLIME", "MAGMA_CUBE"); putE(60, "ELDER_GUARDIAN", "WARDEN", "RAVAGER");
        putE(500, "WITHER"); putE(1000, "ENDER_DRAGON");
    }
    private static void put(Map<Material, Double> m, double v, String... names) {
        for (String n : names) { Material mat = Material.matchMaterial(n); if (mat != null) m.put(mat, v); }
    }
    private static void putE(double v, String... names) {
        for (String n : names) try { COMBAT.put(EntityType.valueOf(n), v); } catch (IllegalArgumentException ignored) { }
    }
    private static boolean isLog(Material m) {
        String n = m.name();
        return (n.endsWith("_LOG") || n.endsWith("_STEM") || n.endsWith("_HYPHAE") || (n.endsWith("_WOOD") && !n.contains("PLANKS")))
                && !n.startsWith("STRIPPED_");
    }

    public SkillManager(PisanCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "skills.yml");
        this.placedFile = new File(plugin.getDataFolder(), "placed-blocks.yml");
    }

    private String pre() { return PisanCore.color(plugin.getConfig().getString("messages.prefix", "")); }

    // ======================= veri =======================
    public void load() {
        if (file.exists()) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
            for (String k : y.getKeys(false)) {
                try {
                    EnumMap<Skill, Double> m = new EnumMap<>(Skill.class);
                    for (Skill s : Skill.values()) m.put(s, y.getDouble(k + "." + s.name(), 0));
                    xp.put(UUID.fromString(k), m);
                } catch (IllegalArgumentException ignored) { }
            }
        }
        if (placedFile.exists()) placed.addAll(YamlConfiguration.loadConfiguration(placedFile).getStringList("placed"));
    }

    public synchronized void save(boolean force) {
        if (!dirty && !force) return;
        YamlConfiguration y = new YamlConfiguration();
        for (var e : xp.entrySet()) for (var s : e.getValue().entrySet()) if (s.getValue() > 0) y.set(e.getKey() + "." + s.getKey().name(), Math.round(s.getValue() * 10) / 10.0);
        YamlConfiguration p = new YamlConfiguration();
        synchronized (placed) { p.set("placed", new ArrayList<>(placed)); }
        try { plugin.getDataFolder().mkdirs(); y.save(file); p.save(placedFile); dirty = false; }
        catch (IOException ex) { plugin.getLogger().severe("skills.yml kaydedilemedi: " + ex.getMessage()); }
    }

    private EnumMap<Skill, Double> data(UUID id) {
        return xp.computeIfAbsent(id, k -> { EnumMap<Skill, Double> m = new EnumMap<>(Skill.class); for (Skill s : Skill.values()) m.put(s, 0.0); return m; });
    }

    /** level L -> L+1 icin gereken XP */
    public static double need(int level) { return Math.round(100 * Math.pow(level + 1, 1.6)); }

    public static int levelOf(double total) {
        int l = 0;
        while (l < MAX_LEVEL && total >= need(l)) { total -= need(l); l++; }
        return l;
    }

    /** mevcut seviyedeki ilerleme [xp, gereken] */
    public static double[] progress(double total) {
        int l = 0;
        while (l < MAX_LEVEL && total >= need(l)) { total -= need(l); l++; }
        return new double[]{total, l >= MAX_LEVEL ? 0 : need(l)};
    }

    public int level(UUID id, Skill s) { return levelOf(data(id).get(s)); }

    public double average(UUID id) {
        double t = 0; for (Skill s : Skill.values()) t += level(id, s);
        return t / Skill.values().length;
    }

    public double bonus(UUID id, Skill s) { return level(id, s) * s.perLevel; }

    public void addXp(Player p, Skill s, double amount) {
        if (amount <= 0 || p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return;
        EnumMap<Skill, Double> m = data(p.getUniqueId());
        int before = levelOf(m.get(s));
        m.put(s, m.get(s) + amount);
        dirty = true;
        int after = levelOf(m.get(s));
        double[] pr = progress(m.get(s));
        p.sendActionBar(PisanCore.color("&3+" + fmt(amount) + " " + s.name + " XP &8(" + (after >= MAX_LEVEL ? "MAKS" : fmt(pr[0]) + "/" + fmt(pr[1])) + ")"));
        if (after > before) levelUp(p, s, after);
    }

    private void levelUp(Player p, Skill s, int level) {
        double coins = 250.0 * level;
        plugin.store().deposit(p.getUniqueId(), coins);
        p.sendTitle(PisanCore.color(s.name + " &e" + level), PisanCore.color("&7Seviye atladın!"), 5, 50, 10);
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 0.8f);
        p.sendMessage(PisanCore.color("&8&m                                        "));
        p.sendMessage(PisanCore.color("  " + s.name + " &fseviye &e" + level + "&f!"));
        p.sendMessage(PisanCore.color("  &7" + s.perk + ": &a%" + fmt(level * s.perLevel)));
        p.sendMessage(PisanCore.color("  &7Ödül: &6+" + PisanCore.fmt(coins) + " Coin"));
        p.sendMessage(PisanCore.color("&8&m                                        "));
        if (level % 10 == 0)
            Bukkit.broadcastMessage(pre() + PisanCore.color("&6" + p.getName() + " &e" + s.plain() + " seviyesi &6" + level + " &eoldu!"));
    }

    private static String fmt(double d) { return d == Math.floor(d) ? String.valueOf((long) d) : String.format(Locale.ROOT, "%.1f", d); }

    // ======================= bloklar =======================
    private static String key(Block b) { return b.getWorld().getName() + ";" + b.getX() + ";" + b.getY() + ";" + b.getZ(); }

    private boolean tracked(Material m) {
        return MINING.containsKey(m) && m != Material.COBBLESTONE && m != Material.STONE && m != Material.BASALT
                || isLog(m) || m == Material.MELON || m == Material.PUMPKIN || m == Material.SUGAR_CANE || m == Material.CACTUS || m == Material.BAMBOO
                || m == Material.COBBLESTONE || m == Material.STONE;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (tracked(e.getBlockPlaced().getType())) synchronized (placed) { placed.add(key(e.getBlockPlaced())); dirty = true; }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        Material m = b.getType();
        boolean wasPlaced;
        synchronized (placed) { wasPlaced = placed.remove(key(b)); }
        if (wasPlaced) { dirty = true; return; }
        Player p = e.getPlayer();
        Skill s = null; double amount = 0;
        if (MINING.containsKey(m)) { s = Skill.MADENCILIK; amount = MINING.get(m); }
        else if (isLog(m)) { s = Skill.ODUNCULUK; amount = 4; }
        else if (FARMING.containsKey(m)) {
            if (b.getBlockData() instanceof Ageable a && m != Material.SUGAR_CANE && m != Material.CACTUS && m != Material.BAMBOO
                    && a.getAge() < a.getMaximumAge()) return;
            s = Skill.CIFTCILIK; amount = FARMING.get(m);
        }
        if (s == null) return;
        addXp(p, s, amount);
        if (ThreadLocalRandom.current().nextDouble() * 100 < bonus(p.getUniqueId(), s)) pendingDouble.put(key(b), s);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(BlockDropItemEvent e) {
        Skill s = pendingDouble.remove(key(e.getBlock()));
        if (s == null || e.getItems().isEmpty()) return;
        for (Item it : new ArrayList<>(e.getItems()))
            e.getBlock().getWorld().dropItemNaturally(e.getBlock().getLocation().add(0.5, 0.5, 0.5), it.getItemStack().clone());
        e.getPlayer().playSound(e.getBlock().getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.6f, 1.8f);
    }

    // ======================= savas / balik =======================
    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(EntityDeathEvent e) {
        LivingEntity en = e.getEntity();
        Player k = en.getKiller();
        if (k == null || en instanceof Player) return;
        Double v = COMBAT.get(en.getType());
        if (v == null) return;
        try { if (en.getEntitySpawnReason() == org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.SPAWNER) v *= 0.25; }
        catch (Throwable ignored) { }
        addXp(k, Skill.SAVAS, v);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p) || e.getEntity() instanceof Player || !(e.getEntity() instanceof LivingEntity)) return;
        double b = bonus(p.getUniqueId(), Skill.SAVAS);
        if (b > 0) e.setDamage(e.getDamage() * (1 + b / 100.0));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        if (e.getState() != PlayerFishEvent.State.CAUGHT_FISH || !(e.getCaught() instanceof Item caught)) return;
        Player p = e.getPlayer();
        addXp(p, Skill.BALIKCILIK, 15);
        if (ThreadLocalRandom.current().nextDouble() * 100 < bonus(p.getUniqueId(), Skill.BALIKCILIK)) {
            for (ItemStack left : p.getInventory().addItem(caught.getItemStack().clone()).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
            p.sendActionBar(PisanCore.color("&9Çift yakalama!"));
        }
    }

    // ======================= menu / komut =======================
    private final class Menu implements InventoryHolder {
        Inventory inv; @Override public Inventory getInventory() { return inv; }
    }

    public void open(Player viewer, OfflinePlayer target) {
        Menu h = new Menu();
        h.inv = Bukkit.createInventory(h, 27, PisanCore.color("&8Skiller: " + target.getName()));
        ItemStack pane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta pm = pane.getItemMeta(); pm.setDisplayName(" "); pane.setItemMeta(pm);
        for (int i = 0; i < 27; i++) h.inv.setItem(i, pane);
        int[] slots = {11, 12, 13, 14, 15};
        int i = 0;
        for (Skill s : Skill.values()) {
            double total = data(target.getUniqueId()).get(s);
            int lvl = levelOf(total);
            double[] pr = progress(total);
            ItemStack it = new ItemStack(s.icon);
            ItemMeta m = it.getItemMeta();
            m.setDisplayName(PisanCore.color(s.name + " &e" + lvl));
            List<String> lore = new ArrayList<>();
            if (lvl >= MAX_LEVEL) lore.add(PisanCore.color("&6&lMAKSİMUM SEVİYE"));
            else {
                lore.add(PisanCore.color("&7İlerleme: &e" + fmt(pr[0]) + "&7/&e" + fmt(pr[1])));
                lore.add(PisanCore.color(bar(pr[0] / pr[1])));
            }
            lore.add("");
            lore.add(PisanCore.color("&7" + s.perk + ": &a%" + fmt(lvl * s.perLevel)));
            lore.add(PisanCore.color("&7Toplam XP: &f" + fmt(total)));
            m.setLore(lore);
            m.addItemFlags(ItemFlag.values());
            it.setItemMeta(m);
            h.inv.setItem(slots[i++], it);
        }
        ItemStack avg = new ItemStack(Material.NETHER_STAR);
        ItemMeta am = avg.getItemMeta();
        am.setDisplayName(PisanCore.color("&dSkill Ortalaması: &f" + fmt(Math.round(average(target.getUniqueId()) * 10) / 10.0)));
        am.setLore(List.of(PisanCore.color("&7Her seviye atlayışta &6250 x seviye &7Coin."), PisanCore.color("&7Maksimum seviye: &e" + MAX_LEVEL)));
        avg.setItemMeta(am);
        h.inv.setItem(22, avg);
        viewer.openInventory(h.inv);
    }

    private static String bar(double f) {
        int n = (int) Math.round(Math.max(0, Math.min(1, f)) * 20);
        return "&a" + "|".repeat(n) + "&7" + "|".repeat(20 - n) + " &e%" + (int) (f * 100);
    }

    @EventHandler public void onClick(InventoryClickEvent e) { if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true); }
    @EventHandler public void onDrag(InventoryDragEvent e) { if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true); }

    @SuppressWarnings("deprecation")
    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!(s instanceof Player p)) { s.sendMessage("Sadece oyuncular."); return true; }
        if (a.length >= 1) {
            OfflinePlayer t = Bukkit.getPlayerExact(a[0]);
            if (t == null) t = Bukkit.getOfflinePlayer(a[0]);
            if (!xp.containsKey(t.getUniqueId())) { p.sendMessage(pre() + "§cOyuncunun skill verisi yok."); return true; }
            open(p, t);
        } else open(p, p);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase(Locale.ROOT).startsWith(a[0].toLowerCase(Locale.ROOT))) out.add(p.getName());
        return out;
    }

    public List<Map.Entry<UUID, Double>> topAverage(int n) {
        List<Map.Entry<UUID, Double>> l = new ArrayList<>();
        for (UUID id : xp.keySet()) l.add(Map.entry(id, average(id)));
        l.sort((x, y) -> Double.compare(y.getValue(), x.getValue()));
        return l.subList(0, Math.min(n, l.size()));
    }
}
