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
 * Lobi dunyasi (pisan_lobi) uc alan icerir:
 *  - Mod Secimi (hub, z=-400): giriste buraya gelinir, menuden / kapidan Skyblock ya da SMP secilir.
 *  - Sky Lobi (z=0): eski lobi, kasalar + SMP lobisine kapi.
 *  - SMP Lobi (z=400): kasalar + SMP dunyasina kapi.
 * Kasa bloguna SAG tik = icerik ve sanslar, SOL tik = anahtar kullanip kasayi ac.
 * Komutlar: /lobi (bulundugun modun lobisi), /menu (mod secimi), /skylobi, /smplobi, /smp.
 * Admin: /lobi kasaayarla <kasa>, /lobi spawnayarla, /lobi yenidenkur [sky|smp|hub]
 */
public final class LobbyManager implements Listener, CommandExecutor, TabCompleter {

    private static final String WORLD = "pisan_lobi";
    private static final int Y = 100, R = 22, CRATE_R = 14;
    static final int SKY_Z = 0, SMP_Z = 400, HUB_Z = -400;

    enum Area { SKY, SMP, HUB }

    /** Platform malzemeleri. */
    private record Theme(Material edge, Material ring, Material center, Material a, Material b, Material slab, Material wall, Material pillar) { }
    private static final Theme SKY_T = new Theme(Material.STONE_BRICKS, Material.SMOOTH_QUARTZ, Material.CHISELED_QUARTZ_BLOCK,
            Material.POLISHED_ANDESITE, Material.SMOOTH_STONE, Material.STONE_BRICK_SLAB, Material.STONE_BRICK_WALL, Material.STONE_BRICKS);
    private static final Theme SMP_T = new Theme(Material.MOSSY_STONE_BRICKS, Material.OAK_PLANKS, Material.CHISELED_STONE_BRICKS,
            Material.MOSS_BLOCK, Material.GRASS_BLOCK, Material.MOSSY_STONE_BRICK_SLAB, Material.MOSSY_STONE_BRICK_WALL, Material.OAK_LOG);
    private static final Theme HUB_T = new Theme(Material.DEEPSLATE_BRICKS, Material.SMOOTH_QUARTZ, Material.CHISELED_QUARTZ_BLOCK,
            Material.POLISHED_DEEPSLATE, Material.DEEPSLATE_TILES, Material.DEEPSLATE_BRICK_SLAB, Material.DEEPSLATE_BRICK_WALL, Material.DEEPSLATE_BRICKS);

    /** Icinden gecilen kapi: ic bosluk x-1..x+1, Y+1..Y+4, z sabit. target: sky | smplobi | smp | hub */
    private record Gate(int x, int z, String target) { }
    private static final List<Gate> GATES = List.of(
            new Gate(-7, HUB_Z - 11, "sky"), new Gate(7, HUB_Z - 11, "smplobi"),
            new Gate(0, SKY_Z + 15, "smplobi"),
            new Gate(0, SMP_Z + 15, "smp"));

    private final PisanCore plugin;
    private final CrateMenu menu;
    private final File file;
    private final NamespacedKey HOLO;
    private final Map<String, String> crateAt = new HashMap<>();   // "x;y;z" -> kasa id
    private final Map<String, Location> crateLoc = new LinkedHashMap<>();
    private final Map<String, String> smpCrateAt = new HashMap<>(); // SMP lobisindeki kasalar (sabit dizilim)
    private final Map<String, Location> smpCrateLoc = new LinkedHashMap<>();
    private final Map<UUID, Long> gateCd = new HashMap<>();
    private Location spawn, smpSpawn, hubSpawn;
    private World world;
    private final Set<String> built = new HashSet<>();

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
    public Location skySpawn() { return spawn; }

    static Area area(Location l) { return l.getZ() < (HUB_Z + SKY_Z) / 2.0 ? Area.HUB : l.getZ() > (SMP_Z + SKY_Z) / 2.0 ? Area.SMP : Area.SKY; }
    private static int cz(Area a) { return a == Area.HUB ? HUB_Z : a == Area.SMP ? SMP_Z : SKY_Z; }
    private Location spawnOf(Area a) { return a == Area.HUB ? hubSpawn : a == Area.SMP ? smpSpawn : spawn; }

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
        for (Area a : Area.values()) {
            int c = cz(a) >> 4;
            for (int cx = -2; cx <= 1; cx++) for (int k = c - 2; k <= c + 1; k++) world.setChunkForceLoaded(cx, k, true);
        }

        boolean fresh = !file.exists();
        load();
        if (fresh) build();
        boolean changed = false;
        if (!built.contains("hub")) { buildHub(); changed = true; }
        if (!built.contains("smp")) { buildSmp(); changed = true; }
        if (!built.contains("skygate")) { gate(GATES.get(2), Material.MOSSY_STONE_BRICKS, Material.OAK_LOG); built.add("skygate"); changed = true; }
        layoutSmpCrates();
        if (changed) save();
        world.setSpawnLocation(hubSpawn);
        spawnHolograms();
        Bukkit.getScheduler().runTaskTimer(plugin, this::gateParticles, 40L, 10L);
    }

    private static String key(Location l) { return l.getBlockX() + ";" + l.getBlockY() + ";" + l.getBlockZ(); }

    private void load() {
        crateAt.clear(); crateLoc.clear();
        spawn = new Location(world, 0.5, Y + 1, SKY_Z + 0.5, 180, 0);
        smpSpawn = new Location(world, 0.5, Y + 1, SMP_Z + 0.5, 0, 0);
        hubSpawn = new Location(world, 0.5, Y + 1, HUB_Z + 0.5, 180, 0);
        built.clear();
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        spawn = readLoc(y, "spawn", spawn);
        smpSpawn = readLoc(y, "smp-spawn", smpSpawn);
        hubSpawn = readLoc(y, "hub-spawn", hubSpawn);
        built.addAll(y.getStringList("built"));
        var cs = y.getConfigurationSection("crates");
        if (cs != null) for (String id : cs.getKeys(false)) {
            Location l = new Location(world, cs.getInt(id + ".x"), cs.getInt(id + ".y"), cs.getInt(id + ".z"));
            crateLoc.put(id, l);
            crateAt.put(key(l), id);
        }
    }

    private Location readLoc(YamlConfiguration y, String k, Location def) {
        if (!y.isSet(k + ".x")) return def;
        return new Location(world, y.getDouble(k + ".x"), y.getDouble(k + ".y"), y.getDouble(k + ".z"), (float) y.getDouble(k + ".yaw"), (float) y.getDouble(k + ".pitch"));
    }

    private static void writeLoc(YamlConfiguration y, String k, Location l) {
        y.set(k + ".x", l.getX()); y.set(k + ".y", l.getY()); y.set(k + ".z", l.getZ());
        y.set(k + ".yaw", l.getYaw()); y.set(k + ".pitch", l.getPitch());
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        writeLoc(y, "spawn", spawn);
        writeLoc(y, "smp-spawn", smpSpawn);
        writeLoc(y, "hub-spawn", hubSpawn);
        y.set("built", new ArrayList<>(built));
        for (var e : crateLoc.entrySet()) {
            y.set("crates." + e.getKey() + ".x", e.getValue().getBlockX());
            y.set("crates." + e.getKey() + ".y", e.getValue().getBlockY());
            y.set("crates." + e.getKey() + ".z", e.getValue().getBlockZ());
        }
        try { plugin.getDataFolder().mkdirs(); y.save(file); } catch (IOException ex) { plugin.getLogger().severe("lobby.yml: " + ex.getMessage()); }
    }

    private void set(int x, int y, int z, Material m) { world.getBlockAt(x, y, z).setType(m, false); }

    /** Yuvarlak platform (merkez 0,cz). */
    private void platform(int cz, Theme t) {
        for (int x = -R - 3; x <= R + 3; x++) for (int z = cz - R - 3; z <= cz + R + 3; z++) for (int y = Y - 3; y <= Y + 8; y++)
            if (world.getBlockAt(x, y, z).getType() != Material.AIR) set(x, y, z, Material.AIR);
        for (int x = -R; x <= R; x++) for (int dz = -R; dz <= R; dz++) {
            int z = cz + dz;
            double d = Math.sqrt(x * x + dz * dz);
            if (d > R + 0.5) continue;
            Material m;
            if (d > R - 1.5) m = t.edge();
            else if (d > 5.5 && d < 7.5) m = t.ring();
            else if (d <= 2.5) m = t.center();
            else m = ((x + dz) & 1) == 0 ? t.a() : t.b();
            set(x, Y, z, m);
            set(x, Y - 1, z, t.edge());
            if (d > R - 2.5) set(x, Y - 2, z, t.slab());
            if (d > R - 0.5) set(x, Y + 1, z, t.wall());
        }
        for (int a = 0; a < 360; a += 30) {
            int x = (int) Math.round(10 * Math.cos(Math.toRadians(a))), z = (int) Math.round(10 * Math.sin(Math.toRadians(a)));
            set(x, Y, cz + z, Material.SEA_LANTERN);
            int x2 = (int) Math.round((R - 1) * Math.cos(Math.toRadians(a + 15))), z2 = (int) Math.round((R - 1) * Math.sin(Math.toRadians(a + 15)));
            set(x2, Y + 1, cz + z2, t.pillar());
            set(x2, Y + 2, cz + z2, Material.LANTERN);
        }
        set(0, Y, cz, Material.SEA_LANTERN);
    }

    private static final Material[] CRATE_BASE = {Material.LIME_CONCRETE, Material.LIGHT_GRAY_CONCRETE, Material.YELLOW_CONCRETE, Material.WHITE_CONCRETE,
            Material.LIGHT_BLUE_CONCRETE, Material.BLUE_CONCRETE, Material.PURPLE_CONCRETE, Material.RED_CONCRETE,
            Material.ORANGE_CONCRETE, Material.MAGENTA_CONCRETE};

    /** Kasalari platformun kuzeyinde (−Z) yay seklinde dizer; konumlari out'a yazar. */
    private void crateArc(int cz, boolean place, Map<String, Location> out, Map<String, String> at) {
        out.clear(); at.clear();
        List<CrateManager.Crate> list = new ArrayList<>(cm().crates());
        int n = list.size();
        for (int i = 0; i < n; i++) {
            double ang = Math.toRadians(195 + (150.0 * i / Math.max(1, n - 1)));
            int x = (int) Math.round(CRATE_R * Math.cos(ang)), z = cz + (int) Math.round(CRATE_R * Math.sin(ang));
            Block b = world.getBlockAt(x, Y + 1, z);
            if (place) {
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) set(x + dx, Y, z + dz, CRATE_BASE[i % CRATE_BASE.length]);
                set(x, Y, z, Material.GLOWSTONE);
                b.setType(i < 5 ? Material.CHEST : Material.ENDER_CHEST, false);
                BlockData bd = b.getBlockData();
                if (bd instanceof Directional dir) {
                    double fx = -x, fz = cz - z;
                    BlockFace f = Math.abs(fx) > Math.abs(fz) ? (fx > 0 ? BlockFace.EAST : BlockFace.WEST) : (fz > 0 ? BlockFace.SOUTH : BlockFace.NORTH);
                    dir.setFacing(f);
                    b.setBlockData(dir, false);
                }
            }
            out.put(list.get(i).id(), b.getLocation());
            at.put(key(b.getLocation()), list.get(i).id());
        }
    }

    /** Kapi cercevesi: ic bosluk 3 genis x 4 yuksek. */
    private void gate(Gate g, Material frame, Material corner) {
        for (int dx = -2; dx <= 2; dx++) for (int y = Y + 1; y <= Y + 5; y++) {
            boolean edge = Math.abs(dx) == 2 || y == Y + 5;
            boolean cor = Math.abs(dx) == 2 && (y == Y + 1 || y == Y + 5);
            set(g.x() + dx, y, g.z(), edge ? (cor ? corner : frame) : Material.AIR);
        }
        for (int dx = -1; dx <= 1; dx++) set(g.x() + dx, Y, g.z(), Material.GLOWSTONE);
    }

    /** Sky lobiyi sifirdan insa eder ve kasalari yay seklinde dizer. */
    public void build() {
        platform(SKY_Z, SKY_T);
        crateArc(SKY_Z, true, crateLoc, crateAt);
        gate(GATES.get(2), Material.MOSSY_STONE_BRICKS, Material.OAK_LOG);
        built.add("skygate");
        save();
        spawnHolograms();
    }

    public void buildSmp() {
        platform(SMP_Z, SMP_T);
        crateArc(SMP_Z, true, smpCrateLoc, smpCrateAt);
        gate(GATES.get(3), Material.CRYING_OBSIDIAN, Material.OBSIDIAN);
        built.add("smp");
    }

    public void buildHub() {
        platform(HUB_Z, HUB_T);
        gate(GATES.get(0), Material.LIGHT_BLUE_CONCRETE, Material.SEA_LANTERN);
        gate(GATES.get(1), Material.MOSSY_STONE_BRICKS, Material.OAK_LOG);
        built.add("hub");
    }

    private void layoutSmpCrates() { crateArc(SMP_Z, false, smpCrateLoc, smpCrateAt); }

    private void gateParticles() {
        if (world == null || world.getPlayers().isEmpty()) return;
        for (Gate g : GATES) {
            Particle pt = g.target().equals("smp") ? Particle.PORTAL : Particle.END_ROD;
            world.spawnParticle(pt, g.x() + 0.5, Y + 3, g.z() + 0.5, pt == Particle.PORTAL ? 40 : 6, 0.7, 1.0, 0.1, pt == Particle.PORTAL ? 0.3 : 0.01);
        }
    }

    private void spawnHolograms() {
        for (Entity e : world.getEntities())
            if (e instanceof TextDisplay && e.getPersistentDataContainer().has(HOLO, PersistentDataType.BYTE)) e.remove();
        for (Map<String, Location> m : List.of(crateLoc, smpCrateLoc)) for (var e : m.entrySet()) {
            CrateManager.Crate c = cm().crate(e.getKey());
            if (c == null) continue;
            holo(e.getValue().clone().add(0.5, 1.9, 0.5), c.name() + "\n&7Sol tık: &aAnahtarla aç\n&7Sağ tık: &eİçerik ve şanslar", 1.0f);
        }
        // Sky lobi
        holo(new Location(world, 0.5, Y + 3.6, SKY_Z + 0.5),
                "&6&l✦ PISAN SKYBLOCK ✦\n&fHoş geldin!\n\n&e/is &7» Adana git\n&e/shop &7» Market   &e/ah &7» Pazar\n&e/minyon &8• &e/skill &8• &e/arkadas\n&e/duel &8• &e/takas &8• &e/siralama\n&e/menu &7» Mod seçimi", 1.1f);
        holo(new Location(world, 0.5, Y + 2.2, SKY_Z - 9.5), "&d&lKASALAR\n&7Anahtarlar taş jeneratöründen düşer", 1.4f);
        holo(new Location(world, 0.5, Y + 6.4, SKY_Z + 15.5), "&a&lSMP LOBİ\n&7İçinden geç!", 1.3f);
        // SMP lobi
        holo(new Location(world, 0.5, Y + 3.6, SMP_Z + 0.5),
                "&a&l✦ PISAN SMP ✦\n&f25.000 x 25.000 hayatta kalma dünyası\n\n&e/smp &7» Dünyaya gir (kaldığın yer)\n&e/shop &7» Market   &e/ah &7» Pazar\n&e/skill &8• &e/duel &8• &e/takas &8• &e/arkadas\n&e/menu &7» Mod seçimi", 1.1f);
        holo(new Location(world, 0.5, Y + 2.2, SMP_Z - 9.5), "&d&lKASALAR\n&7Anahtarlar SMP'de de düşer", 1.4f);
        holo(new Location(world, 0.5, Y + 6.4, SMP_Z + 15.5), "&a&lSMP DÜNYASI\n&7İçinden geç!", 1.3f);
        // Mod secimi
        holo(new Location(world, 0.5, Y + 3.4, HUB_Z + 0.5),
                "&6&l✦ PISAN SKYBLOCK/SMP ✦\n&fBir mod seç!\n\n&7Kapıdan geç ya da &e/menu", 1.2f);
        holo(new Location(world, -6.5, Y + 6.4, HUB_Z - 10.5), "&b&lSKYBLOCK\n&7Ada, minyon, jeneratör\n&eİçinden geç!", 1.3f);
        holo(new Location(world, 7.5, Y + 6.4, HUB_Z - 10.5), "&a&lSMP\n&725K x 25K dünya\n&eİçinden geç!", 1.3f);
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

    public void teleport(Player p) { teleport(p, Area.SKY); }

    public void teleport(Player p, Area a) {
        if (world == null) return;
        p.setFallDistance(0);
        p.teleportAsync(spawnOf(a));
    }

    // ======================= mod secim menusu =======================
    private static final class ModMenu implements org.bukkit.inventory.InventoryHolder {
        org.bukkit.inventory.Inventory inv;
        @Override public org.bukkit.inventory.Inventory getInventory() { return inv; }
    }

    private org.bukkit.inventory.ItemStack icon(Material m, String name, String... lore) {
        var it = new org.bukkit.inventory.ItemStack(m);
        var meta = it.getItemMeta();
        meta.setDisplayName(PisanCore.color(name));
        List<String> l = new ArrayList<>();
        for (String x : lore) l.add(PisanCore.color(x));
        meta.setLore(l);
        meta.addItemFlags(org.bukkit.inventory.ItemFlag.values());
        it.setItemMeta(meta);
        return it;
    }

    public void openMenu(Player p) {
        int sky = 0, smp = 0;
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (plugin.smp() != null && plugin.smp().isSmp(o.getWorld())) smp++;
            else if (!inLobby(o)) sky++;
        }
        ModMenu h = new ModMenu();
        h.inv = Bukkit.createInventory(h, 27, PisanCore.color("&8Mod Seç &7» &6Pisan"));
        var glass = icon(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 27; i++) h.inv.setItem(i, glass);
        h.inv.setItem(11, icon(Material.GRASS_BLOCK, "&b&l☁ SKYBLOCK", "&7Adanı kur, jeneratör, minyonlar,", "&7kasalar ve ada sıralaması.", "",
                "&7Oyunda: &f" + sky, "", "&eTıkla » &fSky Lobi"));
        h.inv.setItem(13, icon(Material.COMPASS, "&6&lMod Seçimi", "&7Market, pazar, coin, kasa anahtarları,", "&7skill ve arkadaşlar iki modda ortak.", "", "&8/menu ile tekrar aç"));
        h.inv.setItem(15, icon(Material.DIAMOND_PICKAXE, "&a&l⛏ SMP", "&725.000 x 25.000 hayatta kalma dünyası", "&7(nether ve end dahil).", "",
                "&7Oyunda: &f" + smp, "", "&eSol tık » &fSMP Lobi", "&eSağ tık » &fDirekt SMP dünyası"));
        p.openInventory(h.inv);
    }

    @EventHandler
    public void onMenuClick(org.bukkit.event.inventory.InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof ModMenu) || !(e.getWhoClicked() instanceof Player p)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getInventory()) return;
        if (e.getRawSlot() == 11) { p.closeInventory(); go(p, "sky"); }
        else if (e.getRawSlot() == 15) { p.closeInventory(); go(p, e.isRightClick() ? "smp" : "smplobi"); }
    }

    @EventHandler
    public void onMenuDrag(org.bukkit.event.inventory.InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof ModMenu) e.setCancelled(true);
    }

    private void go(Player p, String target) {
        p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.7f, 1.2f);
        switch (target) {
            case "sky" -> {
                teleport(p, Area.SKY);
                p.sendTitle(PisanCore.color("&b&lSKYBLOCK"), PisanCore.color(IslandHook.hasIsland(p.getUniqueId()) ? "&e/is &7ile adana git" : "&e/is &7yazarak adanı oluştur"), 5, 40, 10);
            }
            case "smplobi" -> {
                teleport(p, Area.SMP);
                p.sendTitle(PisanCore.color("&a&lSMP LOBİ"), PisanCore.color("&7Kapıdan geç ya da &e/smp"), 5, 40, 10);
            }
            case "smp" -> { if (plugin.smp() != null) plugin.smp().enter(p); }
            case "hub" -> { teleport(p, Area.HUB); }
            default -> { }
        }
    }

    // ======================= kasa etkilesimi =======================
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getClickedBlock() == null || !inLobby(e.getPlayer())) return;
        String k = key(e.getClickedBlock().getLocation());
        String id = crateAt.getOrDefault(k, smpCrateAt.get(k));
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
        if (!inLobby(e.getPlayer())) return;
        Location to = e.getTo();
        if (to.getY() < Y - 30) { teleport(e.getPlayer(), area(to)); return; }
        if (to.getBlockX() == e.getFrom().getBlockX() && to.getBlockY() == e.getFrom().getBlockY() && to.getBlockZ() == e.getFrom().getBlockZ()) return;
        int bx = to.getBlockX(), by = to.getBlockY(), bz = to.getBlockZ();
        if (by < Y + 1 || by > Y + 4) return;
        for (Gate g : GATES) {
            if (bz != g.z() || bx < g.x() - 1 || bx > g.x() + 1) continue;
            Player p = e.getPlayer();
            long now = System.currentTimeMillis();
            if (gateCd.getOrDefault(p.getUniqueId(), 0L) > now) return;
            gateCd.put(p.getUniqueId(), now + 2000);
            go(p, g.target());
            return;
        }
    }

    // Her giriste mod secimine (hub) gelinir ve secim menusu acilir
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!plugin.getConfig().getBoolean("hub.on-join", true)) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            teleport(p, Area.HUB);
            Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline() && inLobby(p)) openMenu(p); }, 15L);
        }, 5L);
    }

    @EventHandler
    public void onQuitCd(PlayerQuitEvent e) { gateCd.remove(e.getPlayer().getUniqueId()); }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent e) {
        if (world != null && e.getRespawnLocation().getWorld().equals(Bukkit.getWorlds().get(0))) e.setRespawnLocation(spawn);
    }

    // ======================= komut =======================
    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!(s instanceof Player p)) { s.sendMessage("Sadece oyuncular."); return true; }
        String c = cmd.getName().toLowerCase(Locale.ROOT);
        switch (c) {
            case "menu" -> { if (!inLobby(p)) teleport(p, Area.HUB); openMenu(p); return true; }
            case "skylobi" -> { go(p, "sky"); return true; }
            case "smplobi" -> { go(p, "smplobi"); return true; }
            default -> { }
        }
        if (a.length >= 1 && p.hasPermission("pisancore.admin")) {
            switch (a[0].toLowerCase(Locale.ROOT)) {
                case "spawnayarla" -> {
                    if (!inLobby(p)) { p.sendMessage(pre() + "§cLobide olmalısın."); return true; }
                    Area ar = area(p.getLocation());
                    if (ar == Area.HUB) hubSpawn = p.getLocation(); else if (ar == Area.SMP) smpSpawn = p.getLocation(); else spawn = p.getLocation();
                    save(); world.setSpawnLocation(hubSpawn);
                    p.sendMessage(pre() + "§a" + ar + " lobi spawn noktası ayarlandı."); return true;
                }
                case "yenidenkur" -> {
                    String w = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "sky";
                    if (w.equals("smp")) buildSmp(); else if (w.equals("hub")) buildHub(); else build();
                    save(); spawnHolograms();
                    p.sendMessage(pre() + "§a" + w + " lobisi yeniden kuruldu."); return true;
                }
                case "kasaayarla" -> {
                    if (a.length < 2 || cm().crate(a[1]) == null) { p.sendMessage(pre() + "§c/lobi kasaayarla <kasa>  (baktığın blok kasa olur)"); return true; }
                    Block b = p.getTargetBlockExact(6);
                    if (b == null || !inLobby(p) || area(b.getLocation()) != Area.SKY) { p.sendMessage(pre() + "§cSky lobide bir bloğa bak."); return true; }
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
        // /lobi: bulundugun modun lobisi
        boolean smp = plugin.smp() != null && plugin.smp().isSmp(p.getWorld());
        if (inLobby(p) && area(p.getLocation()) == Area.SMP) smp = true;
        teleport(p, smp ? Area.SMP : Area.SKY);
        p.sendMessage(pre() + "§7" + (smp ? "SMP" : "Sky") + " lobiye ışınlandın. §8(/menu: mod seçimi)");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission("pisancore.admin") || !cmd.getName().equalsIgnoreCase("lobi")) return List.of();
        List<String> out = new ArrayList<>();
        if (a.length == 1) out.addAll(List.of("spawnayarla", "yenidenkur", "kasaayarla"));
        else if (a.length == 2 && a[0].equalsIgnoreCase("yenidenkur")) out.addAll(List.of("sky", "smp", "hub"));
        else if (a.length == 2 && a[0].equalsIgnoreCase("kasaayarla")) for (CrateManager.Crate c : cm().crates()) out.add(c.id());
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
