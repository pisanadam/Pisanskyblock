package com.pisan.core;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.*;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Locale;

/**
 * SMP modu: 25.000 x 25.000 sinirli ayri hayatta kalma dunyasi (+ kendi nether ve end'i).
 * Skyblock ile ayni sunucuda; ekonomi, market, kasa, skill, duel, takas vb. aynen gecerli.
 * Oyuncunun SMP'deki son konumu saklanir, /smp ile oraya donulur.
 */
public final class SmpManager implements Listener, CommandExecutor, TabCompleter {

    public static final String WORLD = "pisan_smp", NETHER = "pisan_smp_nether", END = "pisan_smp_the_end";

    private final PisanCore plugin;
    private final NamespacedKey LAST;
    private World world, nether, end;

    public SmpManager(PisanCore plugin) {
        this.plugin = plugin;
        this.LAST = new NamespacedKey(plugin, "smp_last");
    }

    private String pre() { return PisanCore.color(plugin.getConfig().getString("messages.prefix", "")); }
    private double size() { return plugin.getConfig().getDouble("smp.border-size", 25000); }

    public World world() { return world; }

    public boolean isSmp(World w) {
        if (w == null) return false;
        String n = w.getName();
        return n.equals(WORLD) || n.equals(NETHER) || n.equals(END);
    }

    // ======================= kurulum =======================
    public void init() {
        world = load(new WorldCreator(WORLD).environment(World.Environment.NORMAL).generateStructures(true));
        long seed = world.getSeed();
        nether = load(new WorldCreator(NETHER).environment(World.Environment.NETHER).seed(seed).generateStructures(true));
        end = load(new WorldCreator(END).environment(World.Environment.THE_END).seed(seed).generateStructures(true));
        Difficulty d = Difficulty.NORMAL;
        try { d = Difficulty.valueOf(plugin.getConfig().getString("smp.difficulty", "NORMAL").toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException ignored) { }
        for (World w : List.of(world, nether, end)) w.setDifficulty(d);
        border(world, size());
        border(nether, size() / 8.0);
        border(end, size());
        plugin.getLogger().info("SMP dunyasi hazir: " + WORLD + " (" + (long) size() + "x" + (long) size() + ")");
    }

    private World load(WorldCreator c) {
        World w = Bukkit.getWorld(c.name());
        return w != null ? w : c.createWorld();
    }

    private static void border(World w, double size) {
        WorldBorder b = w.getWorldBorder();
        b.setCenter(0, 0);
        b.setSize(size);
        b.setWarningDistance(32);
        b.setDamageBuffer(2);
    }

    public Location spawn() {
        Location l = world.getSpawnLocation().clone();
        if (!world.getWorldBorder().isInside(l)) l = new Location(world, 0.5, 0, 0.5);
        Location top = world.getHighestBlockAt(l).getLocation();
        return new Location(world, l.getBlockX() + 0.5, top.getY() + 1, l.getBlockZ() + 0.5, l.getYaw(), 0);
    }

    // ======================= giris / son konum =======================
    private void saveLast(Player p, Location l) {
        if (l == null || !isSmp(l.getWorld())) return;
        p.getPersistentDataContainer().set(LAST, PersistentDataType.STRING, l.getWorld().getName() + ";" + l.getX() + ";" + l.getY() + ";" + l.getZ() + ";" + l.getYaw() + ";" + l.getPitch());
    }

    private Location last(Player p) {
        String s = p.getPersistentDataContainer().get(LAST, PersistentDataType.STRING);
        if (s == null) return null;
        try {
            String[] a = s.split(";");
            World w = Bukkit.getWorld(a[0]);
            if (w == null || !isSmp(w)) return null;
            Location l = new Location(w, Double.parseDouble(a[1]), Double.parseDouble(a[2]), Double.parseDouble(a[3]), Float.parseFloat(a[4]), Float.parseFloat(a[5]));
            return w.getWorldBorder().isInside(l) ? l : null;
        } catch (RuntimeException ex) { return null; }
    }

    /** Oyuncuyu SMP dunyasina (son konumuna, yoksa spawn'a) gonderir. */
    public void enter(Player p) {
        if (world == null) { p.sendMessage(pre() + "§cSMP dünyası henüz yüklenmedi."); return; }
        if (isSmp(p.getWorld())) { p.sendMessage(pre() + "§7Zaten SMP dünyasındasın."); return; }
        Location to = last(p);
        boolean first = to == null;
        if (first) to = spawn();
        p.setFallDistance(0);
        p.teleportAsync(to).thenAccept(ok -> {
            if (!ok || !p.isOnline()) return;
            p.sendTitle(PisanCore.color("&a&lSMP"), PisanCore.color(first ? "&7Hoş geldin! Dünya: &f" + (long) size() + " x " + (long) size() : "&7Kaldığın yerdesin"), 5, 40, 10);
            if (first) p.sendMessage(pre() + "§aSMP'ye hoş geldin! §7Market, pazar, kasalar, skill, duel ve takas burada da geçerli. §e/lobi §7ile SMP lobisine, §e/menu §7ile mod seçimine dönersin.");
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (isSmp(e.getFrom().getWorld()) && (e.getTo() == null || !isSmp(e.getTo().getWorld()))) saveLast(e.getPlayer(), e.getFrom());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) { saveLast(e.getPlayer(), e.getPlayer().getLocation()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) { saveLast(e.getPlayer(), e.getPlayer().getLocation()); }

    // ======================= dogma =======================
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent e) {
        if (world == null) return;
        boolean diedInSmp = isSmp(e.getPlayer().getWorld());
        boolean toSmp = isSmp(e.getRespawnLocation().getWorld());
        if (diedInSmp && !toSmp) e.setRespawnLocation(spawn());          // SMP'de yatak yoksa SMP spawn
        else if (!diedInSmp && toSmp) e.setRespawnLocation(plugin.lobby().skySpawn()); // skyblock'ta olup SMP yatagina dogmasin
    }

    // ======================= portallar (SMP <-> SMP nether / end) =======================
    private Location portalTarget(Location from, PlayerTeleportEvent.TeleportCause cause, Player p) {
        World w = from.getWorld();
        if (!isSmp(w)) return null;
        if (cause == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL) {
            if (w.equals(world)) return clamp(new Location(nether, from.getX() / 8.0, from.getY(), from.getZ() / 8.0, from.getYaw(), from.getPitch()));
            if (w.equals(nether)) return clamp(new Location(world, from.getX() * 8.0, from.getY(), from.getZ() * 8.0, from.getYaw(), from.getPitch()));
        } else if (cause == PlayerTeleportEvent.TeleportCause.END_PORTAL) {
            if (w.equals(world)) { endPlatform(); return new Location(end, 100.5, 49, 0.5, 90, 0); }
            if (w.equals(end)) {
                Location bed = p == null ? null : p.getRespawnLocation();
                return bed != null && isSmp(bed.getWorld()) ? bed : spawn();
            }
        }
        return null;
    }

    private static Location clamp(Location l) {
        WorldBorder b = l.getWorld().getWorldBorder();
        double half = b.getSize() / 2.0 - 16;
        l.setX(Math.max(b.getCenter().getX() - half, Math.min(b.getCenter().getX() + half, l.getX())));
        l.setZ(Math.max(b.getCenter().getZ() - half, Math.min(b.getCenter().getZ() + half, l.getZ())));
        l.setY(Math.max(l.getWorld().getMinHeight() + 8, Math.min(l.getWorld().getEnvironment() == World.Environment.NETHER ? 120 : l.getWorld().getMaxHeight() - 8, l.getY())));
        return l;
    }

    /** Vanilla gibi end giris platformu (100,48,0). */
    private void endPlatform() {
        for (int x = 98; x <= 102; x++) for (int z = -2; z <= 2; z++) {
            end.getBlockAt(x, 48, z).setType(Material.OBSIDIAN, false);
            for (int y = 49; y <= 51; y++) if (!end.getBlockAt(x, y, z).getType().isAir()) end.getBlockAt(x, y, z).setType(Material.AIR, false);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent e) {
        Location to = portalTarget(e.getFrom(), e.getCause(), e.getPlayer());
        if (to == null) return;
        e.setTo(to);
        if (e.getCause() == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL) {
            e.setSearchRadius(to.getWorld().getEnvironment() == World.Environment.NETHER ? 16 : 128);
            e.setCanCreatePortal(true);
            e.setCreationRadius(16);
        } else {
            e.setCanCreatePortal(false);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent e) {
        if (!isSmp(e.getFrom().getWorld())) return;
        PlayerTeleportEvent.TeleportCause cause;
        if (e.getPortalType() == PortalType.NETHER) cause = PlayerTeleportEvent.TeleportCause.NETHER_PORTAL;
        else if (e.getPortalType() == PortalType.ENDER) cause = PlayerTeleportEvent.TeleportCause.END_PORTAL;
        else return; // end gateway vb. ayni dunyada kalir
        Location to = portalTarget(e.getFrom(), cause, null);
        if (to == null) { e.setCancelled(true); return; }
        e.setTo(to);
    }

    // ======================= komut =======================
    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!(s instanceof Player p)) { s.sendMessage("Sadece oyuncular."); return true; }
        if (a.length >= 1 && p.hasPermission("pisancore.admin")) {
            switch (a[0].toLowerCase(Locale.ROOT)) {
                case "spawnayarla" -> {
                    if (!p.getWorld().equals(world)) { p.sendMessage(pre() + "§cSMP ana dünyasında olmalısın."); return true; }
                    Location l = p.getLocation();
                    world.setSpawnLocation(l);
                    p.sendMessage(pre() + "§aSMP spawn noktası ayarlandı.");
                    return true;
                }
                case "spawn" -> { p.teleportAsync(spawn()); return true; }
                case "bilgi" -> {
                    p.sendMessage(pre() + "§7Dünya: §f" + WORLD + " §7Sınır: §f" + (long) world.getWorldBorder().getSize() + " §7Seed: §f" + world.getSeed());
                    return true;
                }
                default -> { }
            }
        }
        enter(p);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission("pisancore.admin") || a.length != 1) return List.of();
        return List.of("spawnayarla", "spawn", "bilgi").stream().filter(x -> x.startsWith(a[0].toLowerCase(Locale.ROOT))).toList();
    }
}
