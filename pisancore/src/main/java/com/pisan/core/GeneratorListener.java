package com.pisan.core;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Lav+su ile olusan (BlockFormEvent) tas bloklarini takip eder.
 * Olusurken sansa gore cevher bloguna donusur; tas kalirsa kazmayla kirilinca anahtar zari atilir.
 * Oyuncunun koydugu bloklar takip edilmez, bu yuzden koy-kir istismari olmaz.
 */
public final class GeneratorListener implements Listener {

    private record Ore(Material block, double chance) { }
    private record Key(String id, double chance, String name, boolean broadcast) { }

    private static final int MAX_TRACKED = 250_000;

    private final PisanCore plugin;
    private final Set<String> tracked = Collections.newSetFromMap(new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) { return size() > MAX_TRACKED; }
    });

    private boolean enabled;
    private Set<String> worlds = new HashSet<>();
    private Set<Material> blocks = EnumSet.noneOf(Material.class);
    private List<Ore> ores = new ArrayList<>();
    private List<Key> keys = new ArrayList<>();

    public GeneratorListener(PisanCore plugin) { this.plugin = plugin; reload(); }

    public void reload() {
        var c = plugin.getConfig();
        enabled = c.getBoolean("generator.enabled", true);
        worlds = new HashSet<>(c.getStringList("generator.worlds"));
        blocks = EnumSet.noneOf(Material.class);
        for (String b : c.getStringList("generator.blocks")) {
            Material m = Material.matchMaterial(b);
            if (m != null) blocks.add(m);
        }
        ores = new ArrayList<>();
        ConfigurationSection os = c.getConfigurationSection("generator.ores");
        if (os != null) for (String k : os.getKeys(false)) {
            String name = os.getString(k + ".block", null);
            Material m = name != null ? Material.matchMaterial(name) : oreBlockFor(os.getString(k + ".item", ""));
            if (m == null || !m.isBlock()) { plugin.getLogger().warning("Gecersiz cevher blogu: " + k); continue; }
            ores.add(new Ore(m, os.getDouble(k + ".chance")));
        }
        keys = new ArrayList<>();
        ConfigurationSection ks = c.getConfigurationSection("generator.keys");
        if (ks != null) for (String k : ks.getKeys(false))
            keys.add(new Key(k, ks.getDouble(k + ".chance"), ks.getString(k + ".name", k), ks.getBoolean(k + ".broadcast")));
    }

    /** Eski ayar dosyalari icin: esya adindan cevher bloguna. */
    private static Material oreBlockFor(String item) {
        return switch (item.toUpperCase(Locale.ROOT)) {
            case "COAL" -> Material.COAL_ORE; case "RAW_COPPER" -> Material.COPPER_ORE; case "RAW_IRON" -> Material.IRON_ORE;
            case "LAPIS_LAZULI" -> Material.LAPIS_ORE; case "REDSTONE" -> Material.REDSTONE_ORE; case "RAW_GOLD" -> Material.GOLD_ORE;
            case "DIAMOND" -> Material.DIAMOND_ORE; case "EMERALD" -> Material.EMERALD_ORE; case "ANCIENT_DEBRIS" -> Material.ANCIENT_DEBRIS;
            default -> Material.matchMaterial(item);
        };
    }

    private static String key(Block b) {
        return b.getWorld().getName() + ";" + b.getX() + ";" + b.getY() + ";" + b.getZ();
    }

    private boolean worldOk(World w) { return worlds.isEmpty() || worlds.contains(w.getName()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onForm(BlockFormEvent e) {
        if (!enabled || !blocks.contains(e.getNewState().getType()) || !worldOk(e.getBlock().getWorld())) return;
        // Kirik tas yerine sansa gore dogrudan cevher blogu olussun
        double roll = ThreadLocalRandom.current().nextDouble() * 100.0, acc = 0;
        for (Ore o : ores) {
            acc += o.chance();
            if (roll < acc) { e.getNewState().setType(o.block()); return; }
        }
        // Normal tas: anahtar zari icin takip et
        synchronized (tracked) { tracked.add(key(e.getBlock())); }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        synchronized (tracked) { tracked.remove(key(e.getBlockPlaced())); }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        synchronized (tracked) { for (Block b : e.getBlocks()) tracked.remove(key(b)); }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        synchronized (tracked) { for (Block b : e.getBlocks()) tracked.remove(key(b)); }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        boolean wasGen;
        synchronized (tracked) { wasGen = tracked.remove(key(b)); }
        if (!enabled || !wasGen || !blocks.contains(b.getType())) return;

        Player p = e.getPlayer();
        if (p.getGameMode() != GameMode.SURVIVAL) return;
        if (!p.getInventory().getItemInMainHand().getType().name().endsWith("_PICKAXE")) return;

        Location drop = b.getLocation().add(0.5, 0.5, 0.5);

        ItemStack key = rollKey(p, 1.0);
        if (key != null) {
            e.setDropItems(false); // kirik tas yok olur, yerine anahtar duser
            b.getWorld().dropItemNaturally(drop, key);
        }
    }

    /**
     * Anahtar zari. mult = sans carpani (jenerator 1.0). En nadirden baslayarak en fazla bir anahtar.
     * Cikarsa anahtar esyasini dondurur ve mesaj/duyuru yapar; cikmazsa null.
     */
    public ItemStack rollKey(Player p, double mult) {
        Key k = rollKeyDef(mult);
        if (k == null) return null;
        CrateManager.Crate crate = plugin.crates().crate(k.id());
        if (crate == null) return null;
        String pre = plugin.getConfig().getString("messages.prefix", "");
        p.sendMessage(PisanCore.color(pre + plugin.getConfig().getString("messages.key-drop", "").replace("%key%", k.name())));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.4f);
        if (k.broadcast()) Bukkit.broadcastMessage(PisanCore.color(pre + plugin.getConfig().getString("messages.key-broadcast", "")
                .replace("%player%", p.getName()).replace("%key%", k.name())));
        return plugin.crates().keyItem(crate, 1);
    }

    /** Minyon icin anahtar zari: cikarsa kasa id'si (sahibine bildirim + nadirse duyuru). */
    public String rollKeyForMinion(java.util.UUID owner, String ownerName, String minionName, double mult) {
        Key k = rollKeyDef(mult);
        if (k == null || plugin.crates().crate(k.id()) == null) return null;
        String pre = plugin.getConfig().getString("messages.prefix", "");
        Player o = Bukkit.getPlayer(owner);
        if (o != null) o.sendMessage(PisanCore.color(pre + minionName + " &eminyonun &f" + k.name() + " &ebuldu! &7(minyon deposunda)"));
        if (k.broadcast()) Bukkit.broadcastMessage(PisanCore.color(pre + "&6" + ownerName + " &eadlı oyuncunun minyonu &f" + k.name() + " &ebuldu!"));
        return k.id();
    }

    /** Ortak zar: etkinlik carpani dahil. En nadirden baslayarak en fazla bir anahtar. */
    private Key rollKeyDef(double mult) {
        if (plugin.events() != null) mult *= plugin.events().keyMultiplier();
        if (mult <= 0) return null;
        double kroll = ThreadLocalRandom.current().nextDouble() * 100.0, kacc = 0;
        List<Key> sorted = new ArrayList<>(keys);
        sorted.sort(Comparator.comparingDouble(Key::chance));
        for (Key k : sorted) {
            kacc += k.chance() * mult;
            if (kroll < kacc) return k;
        }
        return null;
    }

    private static String pretty(Material m) {
        String s = m.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
