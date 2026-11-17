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
 * Bu bloklar kazmayla kirilinca cevher ve anahtar zari atilir.
 * Oyuncunun koydugu bloklar takip edilmez, bu yuzden koy-kir istismari olmaz.
 */
public final class GeneratorListener implements Listener {

    private record Ore(Material item, double chance, int min, int max) { }
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
            Material m = Material.matchMaterial(os.getString(k + ".item", ""));
            if (m == null) { plugin.getLogger().warning("Gecersiz cevher esyasi: " + k); continue; }
            ores.add(new Ore(m, os.getDouble(k + ".chance"), Math.max(1, os.getInt(k + ".min", 1)), Math.max(1, os.getInt(k + ".max", 1))));
        }
        keys = new ArrayList<>();
        ConfigurationSection ks = c.getConfigurationSection("generator.keys");
        if (ks != null) for (String k : ks.getKeys(false))
            keys.add(new Key(k, ks.getDouble(k + ".chance"), ks.getString(k + ".name", k), ks.getBoolean(k + ".broadcast")));
    }

    private static String key(Block b) {
        return b.getWorld().getName() + ";" + b.getX() + ";" + b.getY() + ";" + b.getZ();
    }

    private boolean worldOk(World w) { return worlds.isEmpty() || worlds.contains(w.getName()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onForm(BlockFormEvent e) {
        if (!enabled || !blocks.contains(e.getNewState().getType()) || !worldOk(e.getBlock().getWorld())) return;
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

        ThreadLocalRandom r = ThreadLocalRandom.current();
        Location drop = b.getLocation().add(0.5, 0.5, 0.5);

        // Cevher: tek zar, en fazla bir cevher
        double roll = r.nextDouble() * 100.0, acc = 0;
        for (Ore o : ores) {
            acc += o.chance();
            if (roll < acc) {
                int amt = o.min() + (o.max() > o.min() ? r.nextInt(o.max() - o.min() + 1) : 0);
                b.getWorld().dropItemNaturally(drop, new ItemStack(o.item(), amt));
                String m = plugin.getConfig().getString("messages.ore-drop", "")
                        .replace("%amount%", String.valueOf(amt)).replace("%item%", pretty(o.item()));
                p.sendActionBar(PisanCore.color(m));
                plugin.skills().addXp(p, SkillManager.Skill.MADENCILIK, oreXp(o.item()));
                break;
            }
        }

        // Anahtar: ayri zar, en nadirden baslayarak en fazla bir anahtar
        double kroll = r.nextDouble() * 100.0, kacc = 0;
        List<Key> sorted = new ArrayList<>(keys);
        sorted.sort(Comparator.comparingDouble(Key::chance));
        for (Key k : sorted) {
            kacc += k.chance();
            if (kroll < kacc) {
                CrateManager.Crate crate = plugin.crates().crate(k.id());
                if (crate == null) break;
                b.getWorld().dropItemNaturally(drop, plugin.crates().keyItem(crate, 1));
                p.sendMessage(PisanCore.color(plugin.getConfig().getString("messages.prefix", "")
                        + plugin.getConfig().getString("messages.key-drop", "").replace("%key%", k.name())));
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.4f);
                if (k.broadcast()) Bukkit.broadcastMessage(PisanCore.color(plugin.getConfig().getString("messages.prefix", "")
                        + plugin.getConfig().getString("messages.key-broadcast", "")
                        .replace("%player%", p.getName()).replace("%key%", k.name())));
                break;
            }
        }
    }

    private static double oreXp(Material m) {
        return switch (m.name()) {
            case "COAL" -> 5; case "RAW_COPPER" -> 4; case "RAW_IRON" -> 7; case "LAPIS_LAZULI" -> 9; case "REDSTONE" -> 8;
            case "RAW_GOLD" -> 10; case "DIAMOND" -> 20; case "EMERALD" -> 25; case "ANCIENT_DEBRIS" -> 40; default -> 3;
        };
    }

    private static String pretty(Material m) {
        String s = m.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
