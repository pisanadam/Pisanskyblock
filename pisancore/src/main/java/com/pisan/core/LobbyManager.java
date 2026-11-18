package com.pisan.core;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.BlockFace;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.*;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Lobi: bos bir dunyada otomatik insa edilen platform + kasa bolgesi.
 * Kasa bloguna SAG tik = icerik ve sanslar, SOL tik = anahtar kullanip kasayi ac.
 * /lobi (/spawn) ile isinlanilir. Admin: /lobi kasaayarla <kasa>, /lobi spawnayarla, /lobi yenidenkur
 */
public final class LobbyManager implements Listener, CommandExecutor, TabCompleter {

    private static final String WORLD = "pisan_lobi";
    private static final int Y = 100, R = 22, CRATE_R = 14;

    private final PisanCore plugin;
    private final CrateMenu menu;
    private final File file;
    private final NamespacedKey HOLO;
    private final Map<String, String> crateAt = new HashMap<>();   // "x;y;z" -> kasa id
    private final Map<String, Location> crateLoc = new LinkedHashMap<>();
    private Location spawn;
    private World world;

    public LobbyManager(PisanCore plugin, CrateMenu menu) {
        this.plugin = plugin;
        this.menu = menu;
        this.file = new File(plugin.getDataFolder(), "lobby.yml");
        this.HOLO = new NamespacedKey(plugin, "lobby_holo");
    }

    private String pre() { return PisanCore.color(plugin.getConfig().getString("messages.prefix", "")); }
    private CrateManager cm() { return plugin.crates(); }
    public World world() { return world; }
    public boolean inLobby(Entity e) { return world != null && e.getWorld().equals(world); }

    // ======================= kurulum =======================
    public void init() {
        world = Bukkit.getWorld(WORLD);
        if (world == null) world = new WorldCreator(WORLD).generator(new ChunkGenerator() { })
                .environment(World.Environment.NORMAL).generateStructures(false).createWorld();
        world.setPVP(false);
        world.setDifficulty(Difficulty.PEACEFUL);
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        world.setGameRule(GameRule.DO_FIRE_TICK, false);
        world.setGameRule(GameRule.FALL_DAMAGE, false);
        world.setTime(6000);
        world.setStorm(false);
        for (int cx = -2; cx <= 1; cx++) for (int cz = -2; cz <= 1; cz++) world.setChunkForceLoaded(cx, cz, true);

        boolean fresh = !file.exists();
        load();
        if (fresh) build();
        world.setSpawnLocation(spawn);
        spawnHolograms();
    }

    private static String key(Location l) { return l.getBlockX() + ";" + l.getBlockY() + ";" + l.getBlockZ(); }

    private void load() {
        crateAt.clear(); crateLoc.clear();
        spawn = new Location(world, 0.5, Y + 1, 0.5, 180, 0);
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        if (y.isSet("spawn.x")) spawn = new Location(world, y.getDouble("spawn.x"), y.getDouble("spawn.y"), y.getDouble("spawn.z"),
                (float) y.getDouble("spawn.yaw"), (float) y.getDouble("spawn.pitch"));
        var cs = y.getConfigurationSection("crates");
        if (cs != null) for (String id : cs.getKeys(false)) {
            Location l = new Location(world, cs.getInt(id + ".x"), cs.getInt(id + ".y"), cs.getInt(id + ".z"));
            crateLoc.put(id, l);
            crateAt.put(key(l), id);
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("spawn.x", spawn.getX()); y.set("spawn.y", spawn.getY()); y.set("spawn.z", spawn.getZ());
        y.set("spawn.yaw", spawn.getYaw()); y.set("spawn.pitch", spawn.getPitch());
        for (var e : crateLoc.entrySet()) {
            y.set("crates." + e.getKey() + ".x", e.getValue().getBlockX());
            y.set("crates." + e.getKey() + ".y", e.getValue().getBlockY());
            y.set("crates." + e.getKey() + ".z", e.getValue().getBlockZ());
        }
        try { plugin.getDataFolder().mkdirs(); y.save(file); } catch (IOException ex) { plugin.getLogger().severe("lobby.yml: " + ex.getMessage()); }
    }

    private void set(int x, int y, int z, Material m) { world.getBlockAt(x, y, z).setType(m, false); }

    /** Lobiyi sifirdan insa eder ve kasalari yay seklinde dizer. */
    public void build() {
        // temizle
        for (int x = -R - 3; x <= R + 3; x++) for (int z = -R - 3; z <= R + 3; z++) for (int y = Y - 3; y <= Y + 8; y++)
            if (world.getBlockAt(x, y, z).getType() != Material.AIR) set(x, y, z, Material.AIR);
        // zemin
        for (int x = -R; x <= R; x++) for (int z = -R; z <= R; z++) {
            double d = Math.sqrt(x * x + z * z);
            if (d > R + 0.5) continue;
            Material m;
            if (d > R - 1.5) m = Material.STONE_BRICKS;
            else if (d > 5.5 && d < 7.5) m = Material.SMOOTH_QUARTZ;
            else if (d <= 2.5) m = Material.CHISELED_QUARTZ_BLOCK;
            else m = ((x + z) & 1) == 0 ? Material.POLISHED_ANDESITE : Material.SMOOTH_STONE;
            set(x, Y, z, m);
            set(x, Y - 1, z, Material.STONE_BRICKS);
            if (d > R - 2.5) set(x, Y - 2, z, Material.STONE_BRICK_SLAB);
            if (d > R - 0.5) set(x, Y + 1, z, Material.STONE_BRICK_WALL);
        }
        // isiklar
        for (int a = 0; a < 360; a += 30) {
            int x = (int) Math.round(10 * Math.cos(Math.toRadians(a))), z = (int) Math.round(10 * Math.sin(Math.toRadians(a)));
            set(x, Y, z, Material.SEA_LANTERN);
            int x2 = (int) Math.round((R - 1) * Math.cos(Math.toRadians(a + 15))), z2 = (int) Math.round((R - 1) * Math.sin(Math.toRadians(a + 15)));
            set(x2, Y + 1, z2, Material.STONE_BRICKS);
            set(x2, Y + 2, z2, Material.LANTERN);
        }
        set(0, Y, 0, Material.SEA_LANTERN);

        // kasalar: kuzey tarafinda (−Z) yay
        crateLoc.clear(); crateAt.clear();
        List<CrateManager.Crate> list = new ArrayList<>(cm().crates());
        int n = list.size();
        Material[] base = {Material.LIME_CONCRETE, Material.LIGHT_GRAY_CONCRETE, Material.YELLOW_CONCRETE, Material.WHITE_CONCRETE,
                Material.LIGHT_BLUE_CONCRETE, Material.BLUE_CONCRETE, Material.PURPLE_CONCRETE, Material.RED_CONCRETE,
                Material.ORANGE_CONCRETE, Material.MAGENTA_CONCRETE};
        for (int i = 0; i < n; i++) {
            double ang = Math.toRadians(195 + (150.0 * i / Math.max(1, n - 1)));
            int x = (int) Math.round(CRATE_R * Math.cos(ang)), z = (int) Math.round(CRATE_R * Math.sin(ang));
            // 3x3 renkli taban
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) set(x + dx, Y, z + dz, base[i % base.length]);
            set(x, Y, z, Material.GLOWSTONE);
            Material crateBlock = i < 5 ? Material.CHEST : Material.ENDER_CHEST;
            Block b = world.getBlockAt(x, Y + 1, z);
            b.setType(crateBlock, false);
            BlockData bd = b.getBlockData();
            if (bd instanceof Directional dir) {
                // merkeze baksin
                double fx = -x, fz = -z;
                BlockFace f = Math.abs(fx) > Math.abs(fz) ? (fx > 0 ? BlockFace.EAST : BlockFace.WEST) : (fz > 0 ? BlockFace.SOUTH : BlockFace.NORTH);
                dir.setFacing(f);
                b.setBlockData(dir, false);
            }
            Location l = b.getLocation();
            crateLoc.put(list.get(i).id(), l);
            crateAt.put(key(l), list.get(i).id());
        }
        save();
        spawnHolograms();
    }

    private void spawnHolograms() {
        for (Entity e : world.getEntities())
            if (e instanceof TextDisplay && e.getPersistentDataContainer().has(HOLO, PersistentDataType.BYTE)) e.remove();
        for (var e : crateLoc.entrySet()) {
            CrateManager.Crate c = cm().crate(e.getKey());
            if (c == null) continue;
            holo(e.getValue().clone().add(0.5, 1.9, 0.5), c.name() + "\n&7Sol tık: &aAnahtarla aç\n&7Sağ tık: &eİçerik ve şanslar", 1.0f);
        }
        holo(new Location(world, 0.5, Y + 3.6, 0.5),
                "&6&l✦ PISAN SKYBLOCK ✦\n&fHoş geldin!\n\n&e/is &7» Adana git\n&e/shop &7» Market   &e/ah &7» Pazar\n&e/minyon &8• &e/skill &8• &e/arkadas\n&e/duel &8• &e/takas &8• &e/siralama", 1.1f);
        holo(new Location(world, 0.5, Y + 2.2, -9.5), "&d&lKASALAR\n&7Anahtarlar taş jeneratöründen düşer", 1.4f);
    }

    private void holo(Location l, String text, float scale) {
        world.spawn(l, TextDisplay.class, t -> {
            t.setText(PisanCore.color(text));
            t.setBillboard(Display.Billboard.CENTER);
            t.setShadowed(true);
            t.setPersistent(false);
            t.setDefaultBackground(false);
            t.setBackgroundColor(Color.fromARGB(90, 0, 0, 0));
            t.setAlignment(TextDisplay.TextAlignment.CENTER);
            t.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(), new org.joml.AxisAngle4f(),
                    new org.joml.Vector3f(scale, scale, scale), new org.joml.AxisAngle4f()));
            t.getPersistentDataContainer().set(HOLO, PersistentDataType.BYTE, (byte) 1);
        });
    }

    public void teleport(Player p) {
        if (world == null) return;
        p.setFallDistance(0);
        p.teleportAsync(spawn);
    }

    // ======================= kasa etkilesimi =======================
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getClickedBlock() == null || !inLobby(e.getPlayer())) return;
        String id = crateAt.get(key(e.getClickedBlock().getLocation()));
        if (id == null) return;
        e.setCancelled(true);
        CrateManager.Crate c = cm().crate(id);
        if (c == null) return;
        Player p = e.getPlayer();
        if (e.getAction() == Action.LEFT_CLICK_BLOCK) {
            if (cm().open(p, c)) world.spawnParticle(Particle.TOTEM_OF_UNDYING, e.getClickedBlock().getLocation().add(0.5, 1.2, 0.5), 30, 0.3, 0.4, 0.3, 0.2);
        } else if (e.getAction() == Action.RIGHT_CLICK_BLOCK) {
            menu.openPreview(p, c);
        }
    }

    // ======================= koruma =======================
    private boolean bypass(Player p) { return p.hasPermission("pisancore.admin") && p.getGameMode() == GameMode.CREATIVE; }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) { if (inLobby(e.getPlayer()) && !bypass(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) { if (inLobby(e.getPlayer()) && !bypass(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent e) { if (inLobby(e.getPlayer()) && !bypass(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) { if (e.getEntity() instanceof Player p && inLobby(p)) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent e) { if (inLobby(e.getEntity())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (e.getTo().getY() < Y - 30 && inLobby(e.getPlayer())) teleport(e.getPlayer());
    }

    // ana dunyaya (eski vanilla dunya) dusen herkes lobiye gelsin
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!p.hasPlayedBefore() || p.getWorld().equals(Bukkit.getWorlds().get(0))) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline()) return;
                teleport(p);
                if (!IslandHook.hasIsland(p.getUniqueId()))
                    p.sendMessage(pre() + "§eAdanı oluşturmak için §f/is §eyaz! §7Kasalar lobide, anahtarlar taş jeneratöründen düşer.");
            }, 5L);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent e) {
        if (world != null && e.getRespawnLocation().getWorld().equals(Bukkit.getWorlds().get(0))) e.setRespawnLocation(spawn);
    }

    // ======================= komut =======================
    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!(s instanceof Player p)) { s.sendMessage("Sadece oyuncular."); return true; }
        if (a.length >= 1 && p.hasPermission("pisancore.admin")) {
            switch (a[0].toLowerCase(Locale.ROOT)) {
                case "spawnayarla" -> { spawn = p.getLocation(); save(); world.setSpawnLocation(spawn); p.sendMessage(pre() + "§aLobi spawn noktası ayarlandı."); return true; }
                case "yenidenkur" -> { build(); p.sendMessage(pre() + "§aLobi yeniden kuruldu."); return true; }
                case "kasaayarla" -> {
                    if (a.length < 2 || cm().crate(a[1]) == null) { p.sendMessage(pre() + "§c/lobi kasaayarla <kasa>  (baktığın blok kasa olur)"); return true; }
                    Block b = p.getTargetBlockExact(6);
                    if (b == null || !inLobby(p)) { p.sendMessage(pre() + "§cLobide bir bloğa bak."); return true; }
                    Location old = crateLoc.remove(a[1]);
                    if (old != null) crateAt.remove(key(old));
                    crateLoc.put(a[1], b.getLocation());
                    crateAt.put(key(b.getLocation()), a[1]);
                    save(); spawnHolograms();
                    p.sendMessage(pre() + "§a" + a[1] + " kasası bu bloğa taşındı.");
                    return true;
                }
                default -> { }
            }
        }
        teleport(p);
        p.sendMessage(pre() + "§7Lobiye ışınlandın.");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission("pisancore.admin")) return List.of();
        List<String> out = new ArrayList<>();
        if (a.length == 1) out.addAll(List.of("spawnayarla", "yenidenkur", "kasaayarla"));
        else if (a.length == 2 && a[0].equalsIgnoreCase("kasaayarla")) for (CrateManager.Crate c : cm().crates()) out.add(c.id());
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
