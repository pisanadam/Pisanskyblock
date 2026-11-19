package com.pisan.core;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Hypixel tarzi minyonlar: adaya koyulur, chunk yukluyken belli araliklarla urun uretip deposuna koyar.
 * Sag tik: menu (topla / yukselt / minyonu al). /minyon: minyon marketi.
 */
public final class MinionManager implements Listener {

    public record Type(String id, String name, Material head, Material output, int amount, double interval,
                       double price, Color color, Material tool, boolean breaker) { }

    /** Kazici minyonun kirabilecegi bloklar (jenerator ciktilari). */
    private static final Set<Material> MINEABLE = EnumSet.noneOf(Material.class);
    static {
        for (String n : new String[]{"COBBLESTONE", "STONE", "BASALT", "COBBLED_DEEPSLATE", "DEEPSLATE", "ANCIENT_DEBRIS",
                "COAL_ORE", "COPPER_ORE", "IRON_ORE", "GOLD_ORE", "LAPIS_ORE", "REDSTONE_ORE", "DIAMOND_ORE", "EMERALD_ORE",
                "DEEPSLATE_COAL_ORE", "DEEPSLATE_COPPER_ORE", "DEEPSLATE_IRON_ORE", "DEEPSLATE_GOLD_ORE", "DEEPSLATE_LAPIS_ORE",
                "DEEPSLATE_REDSTONE_ORE", "DEEPSLATE_DIAMOND_ORE", "DEEPSLATE_EMERALD_ORE", "NETHER_QUARTZ_ORE", "NETHER_GOLD_ORE"}) {
            Material m = Material.matchMaterial(n);
            if (m != null) MINEABLE.add(m);
        }
    }

    static final class Minion {
        final UUID id; UUID owner; String ownerName; final Location loc; final String type;
        int tier; double progress;
        final LinkedHashMap<Material, Long> items = new LinkedHashMap<>();
        final LinkedHashMap<String, Integer> keys = new LinkedHashMap<>(); // kasa id -> adet (anahtarlar ayri bolmede)
        int keyCount() { int n = 0; for (int v : keys.values()) n += v; return n; }
        long total() { long t = 0; for (long v : items.values()) t += v; return t; }
        void add(Material m, long n) { if (n > 0) items.merge(m, n, Long::sum); }
        void take(Material m, long n) { long v = items.getOrDefault(m, 0L) - n; if (v <= 0) items.remove(m); else items.put(m, v); }
        transient ArmorStand stand;
        Minion(UUID id, UUID owner, String ownerName, Location loc, String type, int tier) {
            this.id = id; this.owner = owner; this.ownerName = ownerName; this.loc = loc; this.type = type; this.tier = tier;
        }
        long chunkKey() { return Chunk.getChunkKey(loc.getBlockX() >> 4, loc.getBlockZ() >> 4); }
    }

    private final class Menu implements InventoryHolder {
        final Minion m; Inventory inv;
        Menu(Minion m) { this.m = m; }
        @Override public Inventory getInventory() { return inv; }
    }

    private final class Shop implements InventoryHolder {
        Inventory inv; final Map<Integer, String> slots = new HashMap<>();
        @Override public Inventory getInventory() { return inv; }
    }

    private static final String[] ROMAN = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};

    private final PisanCore plugin;
    private final File file;
    private final NamespacedKey ENTITY, ITEM_TYPE, ITEM_TIER;
    private final Map<String, Type> types = new LinkedHashMap<>();
    private final Map<UUID, Minion> minions = new HashMap<>();
    private int maxPerPlayer;
    private List<Double> tierSpeed, tierStorage, tierCost;
    private boolean dirty;

    public MinionManager(PisanCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "minions.yml");
        ENTITY = new NamespacedKey(plugin, "minion_id");
        ITEM_TYPE = new NamespacedKey(plugin, "minion_type");
        ITEM_TIER = new NamespacedKey(plugin, "minion_tier");
    }

    private String pre() { return PisanCore.color(plugin.getConfig().getString("messages.prefix", "")); }

    // ======================= yukleme / kaydetme =======================
    public void loadConfig() {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("minions");
        types.clear();
        if (c == null) return;
        maxPerPlayer = c.getInt("max-per-player", 8);
        tierSpeed = c.getDoubleList("tier-speed");
        tierStorage = c.getDoubleList("tier-storage");
        tierCost = c.getDoubleList("tier-upgrade-cost");
        ConfigurationSection ts = c.getConfigurationSection("types");
        if (ts != null) for (String id : ts.getKeys(false)) {
            ConfigurationSection t = ts.getConfigurationSection(id);
            Material head = Material.matchMaterial(t.getString("head", "COBBLESTONE"));
            Material out = Material.matchMaterial(t.getString("output", "COBBLESTONE"));
            Material tool = Material.matchMaterial(t.getString("tool", "WOODEN_PICKAXE"));
            if (head == null || out == null) { plugin.getLogger().warning("Minyon turu hatali: " + id); continue; }
            Color col;
            try { col = Color.fromRGB(Integer.parseInt(t.getString("color", "888888").replace("#", ""), 16)); }
            catch (Exception ex) { col = Color.GRAY; }
            types.put(id, new Type(id, t.getString("name", id), head, out, Math.max(1, t.getInt("amount", 1)),
                    Math.max(1, t.getDouble("interval", 20)), t.getDouble("price", 1000), col, tool,
                    "break".equalsIgnoreCase(t.getString("mode", "produce"))));
        }
    }

    int maxTier() { return Math.min(tierSpeed.size(), tierStorage.size()); }
    double speedMul(int tier) { return tierSpeed.get(Math.max(0, Math.min(tier, maxTier()) - 1)); }
    long capacity(int tier) { return (long) (double) tierStorage.get(Math.max(0, Math.min(tier, maxTier()) - 1)); }
    double upgradeCost(Type t, int tier) { return tier - 1 < tierCost.size() ? t.price() * tierCost.get(tier - 1) : -1; }
    double interval(Type t, int tier) { return t.interval() * speedMul(tier); }

    public void load() {
        loadConfig();
        minions.clear();
        if (!file.exists()) { loaded = true; return; }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String k : y.getKeys(false)) {
            ConfigurationSection s = y.getConfigurationSection(k);
            World w = Bukkit.getWorld(s.getString("world", ""));
            if (w == null) { plugin.getLogger().warning("Minyon dunyasi yok: " + k); continue; }
            Minion m = new Minion(UUID.fromString(k), UUID.fromString(s.getString("owner")), s.getString("owner-name", "?"),
                    new Location(w, s.getDouble("x"), s.getDouble("y"), s.getDouble("z"), (float) s.getDouble("yaw"), 0),
                    s.getString("type"), s.getInt("tier", 1));
            ConfigurationSection is = s.getConfigurationSection("items");
            if (is != null) for (String mk : is.getKeys(false)) { Material mm = Material.matchMaterial(mk); if (mm != null) m.add(mm, is.getLong(mk)); }
            else if (s.getLong("stored") > 0 && types.containsKey(m.type)) m.add(types.get(m.type).output(), s.getLong("stored"));
            ConfigurationSection ks = s.getConfigurationSection("keys");
            if (ks != null) for (String kid : ks.getKeys(false)) if (ks.getInt(kid) > 0) m.keys.put(kid, ks.getInt(kid));
            m.progress = s.getDouble("progress");
            if (!types.containsKey(m.type)) { plugin.getLogger().warning("Bilinmeyen minyon turu: " + m.type); continue; }
            minions.put(m.id, m);
        }
        loaded = true;
        plugin.getLogger().info(minions.size() + " minyon yuklendi.");
    }

    private boolean loaded;

    public void save(boolean force) {
        if (!loaded) return; // veriler henuz okunmadiysa dosyanin ustune yazma
        if (!dirty && !force) return;
        YamlConfiguration y = new YamlConfiguration();
        for (Minion m : minions.values()) {
            String k = m.id.toString();
            y.set(k + ".owner", m.owner.toString());
            y.set(k + ".owner-name", m.ownerName);
            y.set(k + ".world", m.loc.getWorld().getName());
            y.set(k + ".x", m.loc.getX()); y.set(k + ".y", m.loc.getY()); y.set(k + ".z", m.loc.getZ());
            y.set(k + ".yaw", m.loc.getYaw());
            y.set(k + ".type", m.type);
            y.set(k + ".tier", m.tier);
            for (var it : m.items.entrySet()) y.set(k + ".items." + it.getKey().name(), it.getValue());
            for (var it : m.keys.entrySet()) y.set(k + ".keys." + it.getKey(), it.getValue());
            y.set(k + ".progress", m.progress);
        }
        try { plugin.getDataFolder().mkdirs(); y.save(file); dirty = false; }
        catch (IOException ex) { plugin.getLogger().severe("minions.yml kaydedilemedi: " + ex.getMessage()); }
    }

    public void start() {
        for (World w : Bukkit.getWorlds()) for (Chunk ch : w.getLoadedChunks()) onChunk(ch);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(plugin, () -> save(false), 6000L, 6000L);
    }

    public void shutdown() {
        save(true);
        for (Minion m : minions.values()) if (m.stand != null) m.stand.remove();
    }

    // ======================= varlik (armor stand) =======================
    private boolean isLoaded(Minion m) { return m.loc.getWorld().isChunkLoaded(m.loc.getBlockX() >> 4, m.loc.getBlockZ() >> 4); }

    private void spawn(Minion m) {
        if (m.stand != null && m.stand.isValid()) return;
        Type t = types.get(m.type);
        m.stand = m.loc.getWorld().spawn(m.loc, ArmorStand.class, as -> {
            as.setSmall(true);
            as.setArms(true);
            as.setBasePlate(false);
            as.setGravity(false);
            as.setInvulnerable(true);
            as.setPersistent(false);
            as.setCanPickupItems(false);
            as.setCustomNameVisible(true);
            as.getPersistentDataContainer().set(ENTITY, PersistentDataType.STRING, m.id.toString());
            EntityEquipment eq = as.getEquipment();
            eq.setHelmet(new ItemStack(t.head()));
            eq.setChestplate(leather(Material.LEATHER_CHESTPLATE, t.color()));
            eq.setLeggings(leather(Material.LEATHER_LEGGINGS, t.color()));
            eq.setBoots(leather(Material.LEATHER_BOOTS, t.color()));
            eq.setItemInMainHand(new ItemStack(t.tool()));
            for (EquipmentSlot s : EquipmentSlot.values()) {
                try { as.addEquipmentLock(s, ArmorStand.LockType.REMOVING_OR_CHANGING); as.addEquipmentLock(s, ArmorStand.LockType.ADDING_OR_CHANGING); }
                catch (Exception ignored) { }
            }
        });
        updateName(m);
    }

    private static ItemStack leather(Material mat, Color c) {
        ItemStack i = new ItemStack(mat);
        LeatherArmorMeta lm = (LeatherArmorMeta) i.getItemMeta();
        lm.setColor(c);
        i.setItemMeta(lm);
        return i;
    }

    private void updateName(Minion m) {
        if (m.stand == null) return;
        Type t = types.get(m.type);
        long cap = capacity(m.tier);
        long tot = m.total();
        String fill = tot >= cap ? "&c" : "&a";
        m.stand.setCustomName(PisanCore.color(t.name() + " &8[&e" + ROMAN[Math.min(m.tier, 10)] + "&8] " + fill + tot + "&7/" + cap
                + (m.keys.isEmpty() ? "" : " &6🗝" + m.keyCount())));
    }

    private void onChunk(Chunk ch) {
        // eski kalinti standlari temizle
        for (Entity en : ch.getEntities())
            if (en instanceof ArmorStand && en.getPersistentDataContainer().has(ENTITY, PersistentDataType.STRING)) {
                String id = en.getPersistentDataContainer().get(ENTITY, PersistentDataType.STRING);
                Minion m = minions.get(UUID.fromString(id));
                if (m == null || m.stand != en) en.remove();
            }
        long key = ch.getChunkKey();
        for (Minion m : minions.values())
            if (m.loc.getWorld().equals(ch.getWorld()) && m.chunkKey() == key) { m.stand = null; spawn(m); }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        Bukkit.getScheduler().runTask(plugin, () -> { if (e.getChunk().isLoaded()) onChunk(e.getChunk()); });
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent e) {
        long key = e.getChunk().getChunkKey();
        for (Minion m : minions.values())
            if (m.loc.getWorld().equals(e.getWorld()) && m.chunkKey() == key && m.stand != null) { m.stand.remove(); m.stand = null; }
    }

    // ======================= uretim =======================
    private void tick() {
        for (Minion m : minions.values()) {
            if (!isLoaded(m)) continue;
            if (m.stand == null || !m.stand.isValid()) { m.stand = null; spawn(m); }
            Type t = types.get(m.type);
            long cap = capacity(m.tier);
            if (m.total() > 0) pushToContainer(m, t);
            if (m.total() >= cap) continue;
            m.progress += 1.0 / interval(t, m.tier);
            if (m.progress >= 1) {
                int n = (int) m.progress;
                m.progress -= n;
                if (t.breaker()) {
                    if (!mine(m, t, cap)) { m.progress = Math.min(m.progress + n, 1.0); continue; } // kazacak blok yok, bekle
                } else {
                    m.add(t.output(), Math.min(cap - m.total(), (long) n * t.amount()));
                    for (int i = 0; i < n; i++) minionKeyRoll(m, t);
                }
                dirty = true;
                updateName(m);
                if (m.stand != null) {
                    m.stand.swingMainHand();
                    m.loc.getWorld().spawnParticle(Particle.BLOCK, m.loc.clone().add(0, 0.3, 0), 8, 0.2, 0.1, 0.2,
                            t.head().isBlock() ? t.head().createBlockData() : Material.STONE.createBlockData());
                }
            }
        }
    }

    // ======================= huni / sandik =======================
    private static final org.bukkit.block.BlockFace[] FACES = {
            org.bukkit.block.BlockFace.DOWN, org.bukkit.block.BlockFace.NORTH, org.bukkit.block.BlockFace.SOUTH,
            org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.WEST};

    /** Minyonun altinda (once) veya yaninda huni/sandik/varil varsa onu dondurur. */
    private Inventory container(Minion m) {
        Block base = m.loc.getBlock();
        for (org.bukkit.block.BlockFace f : FACES) {
            Block b = base.getRelative(f);
            Material t = b.getType();
            if (t == Material.HOPPER || t == Material.CHEST || t == Material.TRAPPED_CHEST || t == Material.BARREL) {
                if (b.getState(false) instanceof org.bukkit.block.Container c) return c.getInventory();
            }
        }
        return null;
    }

    private void pushToContainer(Minion m, Type t) {
        Inventory inv = container(m);
        if (inv == null) return;
        if (!m.keys.isEmpty()) { // anahtarlar once
            var ke = m.keys.entrySet().iterator().next();
            CrateManager.Crate c = plugin.crates().crate(ke.getKey());
            if (c == null) { m.keys.remove(ke.getKey()); return; }
            int n = Math.min(64, ke.getValue());
            int notFit = inv.addItem(plugin.crates().keyItem(c, n)).values().stream().mapToInt(ItemStack::getAmount).sum();
            int moved = n - notFit;
            if (moved > 0) { if (ke.getValue() - moved <= 0) m.keys.remove(ke.getKey()); else ke.setValue(ke.getValue() - moved); dirty = true; updateName(m); }
            return;
        }
        if (m.items.isEmpty()) return;
        Map.Entry<Material, Long> e = m.items.entrySet().iterator().next();
        int n = (int) Math.min(e.getKey().getMaxStackSize(), e.getValue());
        Map<Integer, ItemStack> left = inv.addItem(new ItemStack(e.getKey(), n));
        int moved = n - left.values().stream().mapToInt(ItemStack::getAmount).sum();
        if (moved > 0) { m.take(e.getKey(), moved); dirty = true; updateName(m); }
    }

    /** Minyon anahtar zari (config: key-sources.minions). Cikarsa minyonun anahtar bolmesine eklenir. */
    private boolean minionKeyRoll(Minion m, Type t) {
        double mult = plugin.getConfig().getDouble("key-sources.minions", 0.5);
        String id = plugin.generator().rollKeyForMinion(m.owner, m.ownerName, PisanCore.color(t.name()), mult);
        if (id == null) return false;
        m.keys.merge(id, 1, Integer::sum);
        dirty = true;
        return true;
    }

    /** Kazici: yanindaki (once baktigi yon) jenerator blogunu kirar, dusenleri depoya koyar. */
    private boolean mine(Minion m, Type t, long cap) {
        Block base = m.loc.getBlock();
        org.bukkit.block.BlockFace front = yawFace(m.loc.getYaw());
        { // sadece baktigi yondeki blok
            org.bukkit.block.BlockFace f = front;
            Block b = base.getRelative(f);
            if (!MINEABLE.contains(b.getType())) return false;
            ItemStack tool = new ItemStack(t.tool());
            Material bt = b.getType();
            boolean cobble = bt == Material.COBBLESTONE || bt == Material.STONE || bt == Material.BASALT;
            boolean gotKey = cobble && minionKeyRoll(m, t); // oyuncudaki gibi: anahtar cikarsa tas dusmez
            if (!gotKey) for (ItemStack d : b.getDrops(tool)) m.add(d.getType(), Math.min(d.getAmount(), Math.max(0, cap - m.total())));
            b.getWorld().spawnParticle(Particle.BLOCK, b.getLocation().add(0.5, 0.5, 0.5), 12, 0.3, 0.3, 0.3, b.getBlockData());
            b.getWorld().playSound(b.getLocation(), b.getBlockData().getSoundGroup().getBreakSound(), 0.6f, 1f);
            b.setType(Material.AIR, true); // sivilar akar, jenerator yeniden uretir
            return true;
        }
    }

    private static org.bukkit.block.BlockFace yawFace(float yaw) {
        float y = ((yaw % 360) + 360) % 360;
        if (y >= 315 || y < 45) return org.bukkit.block.BlockFace.SOUTH;
        if (y < 135) return org.bukkit.block.BlockFace.WEST;
        if (y < 225) return org.bukkit.block.BlockFace.NORTH;
        return org.bukkit.block.BlockFace.EAST;
    }




    // ======================= esya =======================
    public ItemStack item(Type t, int tier) {
        ItemStack it = new ItemStack(Material.ARMOR_STAND);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(PisanCore.color(t.name() + " &8[&e" + ROMAN[Math.min(tier, 10)] + "&8]"));
        double perHour = 3600.0 / interval(t, tier) * t.amount();
        m.setLore(List.of(
                PisanCore.color(t.breaker() ? "&7Baktığı yöndeki taş/cevher bloğunu kazar" : "&7Üretir: &f" + CrateManager.pretty(t.output().name())),
                PisanCore.color("&7Hız: &e" + fmt1(interval(t, tier)) + " sn &8(saatte ~" + (long) perHour + ")"),
                PisanCore.color("&7Depo: &e" + capacity(tier)),
                "",
                PisanCore.color("&eAdanda bir bloğa sağ tıklayarak koy.")));
        m.setEnchantmentGlintOverride(true);
        try { m.setItemModel(t.head().getKey()); } catch (Throwable ignored) { }
        m.getPersistentDataContainer().set(ITEM_TYPE, PersistentDataType.STRING, t.id());
        m.getPersistentDataContainer().set(ITEM_TIER, PersistentDataType.INTEGER, tier);
        it.setItemMeta(m);
        return it;
    }

    private static String fmt1(double d) { return String.format(Locale.ROOT, "%.1f", d); }

    private String itemType(ItemStack it) {
        if (it == null || it.getType() != Material.ARMOR_STAND || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(ITEM_TYPE, PersistentDataType.STRING);
    }

    private int itemTier(ItemStack it) {
        Integer t = it.getItemMeta().getPersistentDataContainer().get(ITEM_TIER, PersistentDataType.INTEGER);
        return t == null ? 1 : t;
    }

    public Type type(String id) { return types.get(id.toLowerCase(Locale.ROOT)); }
    public Collection<Type> types() { return types.values(); }

    public void give(Player p, Type t, int tier, int amount) {
        for (int i = 0; i < amount; i++)
            for (ItemStack left : p.getInventory().addItem(item(t, tier)).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
    }

    private int count(UUID owner) { int n = 0; for (Minion m : minions.values()) if (m.owner.equals(owner)) n++; return n; }

    // ======================= yerlestirme =======================
    @EventHandler(priority = EventPriority.LOW)
    public void onPlace(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        String tid = itemType(e.getItem());
        if (tid == null) return;
        e.setCancelled(true);
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        Player p = e.getPlayer();
        Type t = types.get(tid);
        if (t == null) { p.sendMessage(pre() + "§cBu minyon türü artık yok."); return; }

        Block target = e.getClickedBlock().getRelative(e.getBlockFace());
        if (!target.getType().isAir() && !target.isPassable()) { p.sendMessage(pre() + "§cBurada yer yok."); return; }
        Location loc = target.getLocation().add(0.5, 0, 0.5);
        // Kazici oyuncunun baktigi yone bakar (arkasinda durup jeneratore bakarak koy); digerleri oyuncuya doner
        loc.setYaw(Math.round((p.getLocation().getYaw() + (t.breaker() ? 0 : 180)) / 90f) * 90f);

        Boolean member = IslandHook.isMember(loc, p.getUniqueId());
        if (member == null) { p.sendMessage(pre() + "§cMinyonlar sadece adanda çalışır. §7(/is)"); return; }
        if (!member) { p.sendMessage(pre() + "§cBu ada senin değil."); return; }
        for (Minion o : minions.values())
            if (o.loc.getWorld().equals(loc.getWorld()) && o.loc.distanceSquared(loc) < 0.5) { p.sendMessage(pre() + "§cBurada zaten minyon var."); return; }
        if (count(p.getUniqueId()) >= maxPerPlayer && !p.hasPermission("pisancore.admin")) {
            p.sendMessage(pre() + "§cEn fazla " + maxPerPlayer + " minyonun olabilir."); return;
        }
        int tier = itemTier(e.getItem());
        e.getItem().setAmount(e.getItem().getAmount() - 1);
        Minion m = new Minion(UUID.randomUUID(), p.getUniqueId(), p.getName(), loc, t.id(), tier);
        minions.put(m.id, m);
        dirty = true;
        spawn(m);
        p.playSound(loc, Sound.ENTITY_ITEM_PICKUP, 1f, 0.8f);
        p.sendMessage(pre() + "§a" + PisanCore.color(t.name()) + " §ayerleştirildi! §7(" + count(p.getUniqueId()) + "/" + maxPerPlayer + ")");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockPlace(BlockPlaceEvent e) { if (itemType(e.getItemInHand()) != null) e.setCancelled(true); }

    @EventHandler
    public void onDispense(BlockDispenseEvent e) { if (itemType(e.getItem()) != null) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAnvil(PrepareAnvilEvent e) {
        for (ItemStack it : e.getInventory().getContents()) if (itemType(it) != null) { e.setResult(null); return; }
    }

    // ======================= etkilesim =======================
    private Minion of(Entity en) {
        if (!(en instanceof ArmorStand)) return null;
        String id = en.getPersistentDataContainer().get(ENTITY, PersistentDataType.STRING);
        return id == null ? null : minions.get(UUID.fromString(id));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onManipulate(PlayerArmorStandManipulateEvent e) {
        if (e.getRightClicked().getPersistentDataContainer().has(ENTITY, PersistentDataType.STRING)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractAt(PlayerInteractAtEntityEvent e) { handleInteract(e); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractEntity(PlayerInteractEntityEvent e) { handleInteract(e); }

    private void handleInteract(PlayerInteractEntityEvent e) {
        Minion m = of(e.getRightClicked());
        if (m == null) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        if (!IslandHook.canManage(p, m.loc, m.owner)) { p.sendMessage(pre() + "§cBu minyon " + m.ownerName + " oyuncusuna ait."); return; }
        openMenu(p, m);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) { if (of(e.getEntity()) != null) e.setCancelled(true); }

    // ======================= menu =======================
    private static ItemStack icon(Material mat, String name, List<String> lore) {
        ItemStack i = new ItemStack(mat);
        ItemMeta m = i.getItemMeta();
        m.setDisplayName(PisanCore.color(name));
        List<String> l = new ArrayList<>();
        for (String s : lore) l.add(PisanCore.color(s));
        m.setLore(l);
        m.addItemFlags(ItemFlag.values());
        i.setItemMeta(m);
        return i;
    }

    private void openMenu(Player p, Minion m) {
        Menu h = new Menu(m);
        h.inv = Bukkit.createInventory(h, 27, PisanCore.color("&8" + ChatColor.stripColor(PisanCore.color(types.get(m.type).name()))));
        render(h);
        p.openInventory(h.inv);
    }

    private void render(Menu h) {
        Minion m = h.m;
        Type t = types.get(m.type);
        Inventory inv = h.inv;
        ItemStack pane = icon(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < 27; i++) inv.setItem(i, pane);
        long cap = capacity(m.tier);
        boolean linked = container(m) != null;
        List<String> depo = new ArrayList<>();
        depo.add("&7Depoda: &f" + m.total() + "&7/" + cap);
        for (var ke : m.keys.entrySet()) {
            CrateManager.Crate c = plugin.crates().crate(ke.getKey());
            if (c != null) depo.add(" &6🗝 &f" + ke.getValue() + "x " + plugin.crates().keyName(c));
        }
        int shown = 0;
        for (var it : m.items.entrySet()) { if (shown++ >= 8) { depo.add("&8..."); break; } depo.add(" &8- &f" + it.getValue() + "x " + CrateManager.pretty(it.getKey().name())); }
        depo.add(""); depo.add("&eTıkla &7» Envanterine al");
        inv.setItem(11, icon(t.breaker() ? Material.CHEST : t.output(), "&aDepoyu topla", depo));
        inv.setItem(20, icon(Material.HOPPER, linked ? "&aHuni/Sandık bağlı" : "&7Huni/Sandık bağlı değil", List.of(
                "&7Minyonun &faltına &7ya da &fyanına &7bir",
                "&7huni, sandık veya varil koyarsan",
                "&7ürettiklerini otomatik oraya aktarır.",
                "", linked ? "&aDurum: Aktarılıyor ✔" : "&cDurum: Bağlı değil")));
        double perHour = 3600.0 / interval(t, m.tier) * t.amount();
        inv.setItem(13, icon(t.head(), t.name() + " &8[&e" + ROMAN[Math.min(m.tier, 10)] + "&8]", List.of(
                "&7Sahibi: &f" + m.ownerName,
                "&7Hız: &e" + fmt1(interval(t, m.tier)) + " sn &8(saatte ~" + (long) perHour + ")",
                "&7Depo: &e" + cap)));
        if (m.tier < maxTier()) {
            double cost = upgradeCost(t, m.tier);
            inv.setItem(15, icon(Material.EXPERIENCE_BOTTLE, "&bYükselt &8» &e" + ROMAN[m.tier + 1], List.of(
                    "&7Yeni hız: &e" + fmt1(interval(t, m.tier + 1)) + " sn",
                    "&7Yeni depo: &e" + capacity(m.tier + 1),
                    "", "&7Fiyat: &6" + PisanCore.fmt(cost) + " Coin", "&eTıkla &7» Yükselt")));
        } else inv.setItem(15, icon(Material.NETHER_STAR, "&6&lMaksimum seviye", List.of()));
        inv.setItem(22, icon(Material.BARRIER, "&cMinyonu al", List.of("&7Minyonu ve deposunu", "&7envanterine geri al.")));
    }

    private void collect(Player p, Minion m) {
        Type t = types.get(m.type);
        long given = 0;
        boolean full = false;
        for (String kid : new ArrayList<>(m.keys.keySet())) {
            CrateManager.Crate c = plugin.crates().crate(kid);
            if (c == null) { m.keys.remove(kid); continue; }
            while (m.keys.getOrDefault(kid, 0) > 0) {
                int n = Math.min(64, m.keys.get(kid));
                int notFit = p.getInventory().addItem(plugin.crates().keyItem(c, n)).values().stream().mapToInt(ItemStack::getAmount).sum();
                int left = m.keys.get(kid) - (n - notFit);
                if (left <= 0) m.keys.remove(kid); else m.keys.put(kid, left);
                given += (n - notFit);
                if (notFit > 0) { full = true; break; }
            }
            if (full) break;
        }
        for (Material mat : new ArrayList<>(m.items.keySet())) {
            if (full) break;
            while (m.items.getOrDefault(mat, 0L) > 0) {
                int n = (int) Math.min(mat.getMaxStackSize(), m.items.get(mat));
                Map<Integer, ItemStack> left = p.getInventory().addItem(new ItemStack(mat, n));
                int notFit = left.values().stream().mapToInt(ItemStack::getAmount).sum();
                m.take(mat, n - notFit);
                given += (n - notFit);
                if (notFit > 0) { full = true; break; }
            }
            if (full) break;
        }
        dirty = true;
        updateName(m);
        if (given > 0) p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1f, 1.2f);
        if (m.total() > 0 || !m.keys.isEmpty()) p.sendMessage(pre() + "§eEnvanterin doldu, §f" + (m.total() + m.keyCount()) + " §eeşya depoda kaldı.");
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        InventoryHolder holder = e.getInventory().getHolder();
        if (holder instanceof Shop s) { e.setCancelled(true); shopClick(e, s); return; }
        if (!(holder instanceof Menu h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getView().getTopInventory()) return;
        Minion m = h.m;
        if (!minions.containsKey(m.id)) { p.closeInventory(); return; }
        Type t = types.get(m.type);
        switch (e.getRawSlot()) {
            case 11 -> { collect(p, m); render(h); }
            case 15 -> {
                if (m.tier >= maxTier()) return;
                double cost = upgradeCost(t, m.tier);
                if (!plugin.store().withdraw(p.getUniqueId(), cost)) { p.sendMessage(pre() + "§cYeterli Coin'in yok. §7(" + PisanCore.fmt(cost) + ")"); return; }
                m.tier++;
                dirty = true;
                updateName(m);
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.5f);
                p.sendMessage(pre() + "§aMinyon §e" + ROMAN[m.tier] + " §aseviyesine yükseltildi!");
                render(h);
            }
            case 22 -> {
                collect(p, m);
                if (m.total() > 0 || !m.keys.isEmpty()) { p.sendMessage(pre() + "§cÖnce envanterinde yer aç."); return; }
                minions.remove(m.id);
                dirty = true;
                if (m.stand != null) m.stand.remove();
                give(p, t, m.tier, 1);
                p.closeInventory();
                p.sendMessage(pre() + "§7Minyon envanterine alındı.");
            }
            default -> { }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Menu || e.getInventory().getHolder() instanceof Shop) e.setCancelled(true);
    }

    // ======================= market =======================
    public void openShop(Player p) {
        Shop s = new Shop();
        s.inv = Bukkit.createInventory(s, 36, PisanCore.color("&8Minyon Marketi"));
        ItemStack pane = icon(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < 36; i++) s.inv.setItem(i, pane);
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
        int i = 0;
        for (Type t : types.values()) {
            if (i >= slots.length) break;
            ItemStack it = item(t, 1);
            ItemMeta meta = it.getItemMeta();
            List<String> lore = new ArrayList<>(meta.getLore());
            lore.add(PisanCore.color("&7Fiyat: &6" + PisanCore.fmt(t.price()) + " Coin"));
            lore.add(PisanCore.color("&eTıkla &7» Satın al"));
            meta.setLore(lore);
            it.setItemMeta(meta);
            s.inv.setItem(slots[i], it);
            s.slots.put(slots[i], t.id());
            i++;
        }
        s.inv.setItem(31, icon(Material.BOOK, "&eMinyonların: &f" + count(p.getUniqueId()) + "/" + maxPerPlayer, List.of(
                "&7Minyonlar sadece adanda çalışır", "&7ve chunk yüklüyken üretir.")));
        p.openInventory(s.inv);
    }

    private void shopClick(InventoryClickEvent e, Shop s) {
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getView().getTopInventory()) return;
        String id = s.slots.get(e.getRawSlot());
        if (id == null) return;
        Type t = types.get(id);
        if (!plugin.store().withdraw(p.getUniqueId(), t.price())) {
            p.sendMessage(pre() + "§cYeterli Coin'in yok. §7(" + PisanCore.fmt(t.price()) + ")");
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return;
        }
        give(p, t, 1, 1);
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
        p.sendMessage(pre() + "§a" + PisanCore.color(t.name()) + " §asatın alındı!");
    }
}
