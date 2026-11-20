package com.pisan.core;

import org.bukkit.*;
import org.bukkit.block.ShulkerBox;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.util.*;

/**
 * /sat (/sell): satis penceresi. Fiyatlar EconomyShopGUI dukkan dosyalarindan okunur.
 *  - Normal esya: dukkandaki birim satis fiyati
 *  - Buyulu kitap: icindeki her buyunun kitap fiyati toplami (cok buyulu kitaplar dahil)
 *  - Buyulu esya: esya fiyati (dayaniklilik oraninda) + buyulerin degeri
 * Satilamayanlar (anahtar, minyon, spawner, dolu shulker...) geri verilir. /sat el = elindekini sat.
 */
public final class SellManager implements Listener, CommandExecutor, TabCompleter {

    private static final Set<String> PLAIN_KEYS = Set.of("material", "buy", "sell", "stack-size", "displayname", "slot", "hidden", "name", "lore");

    private final PisanCore plugin;
    private final Map<Material, Double> unit = new EnumMap<>(Material.class);
    private final Map<Enchantment, TreeMap<Integer, Double>> books = new HashMap<>();

    private static final class Holder implements InventoryHolder {
        Inventory inv; @Override public Inventory getInventory() { return inv; }
    }

    public SellManager(PisanCore plugin) { this.plugin = plugin; }

    private String pre() { return PisanCore.color(plugin.getConfig().getString("messages.prefix", "")); }

    // ======================= fiyat tablosu =======================
    @SuppressWarnings("deprecation")
    private static Enchantment ench(String name) {
        Enchantment e = null;
        try { e = Enchantment.getByName(name.toUpperCase(Locale.ROOT)); } catch (Throwable ignored) { }
        if (e == null) e = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(name.toLowerCase(Locale.ROOT)));
        return e;
    }

    public void load() {
        unit.clear(); books.clear();
        File dir = new File(plugin.getDataFolder().getParentFile(), "EconomyShopGUI/shops");
        List<File> files = new ArrayList<>();
        collect(dir, files);
        for (File f : files) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
            ConfigurationSection pages = y.getConfigurationSection("pages");
            if (pages == null) continue;
            for (String pg : pages.getKeys(false)) {
                ConfigurationSection items = pages.getConfigurationSection(pg + ".items");
                if (items == null) continue;
                for (String k : items.getKeys(false)) {
                    ConfigurationSection it = items.getConfigurationSection(k);
                    if (it == null || it.isSet("section")) continue;
                    double sell = it.getDouble("sell", -1);
                    Material m = Material.matchMaterial(it.getString("material", ""));
                    if (m == null || sell <= 0) continue;
                    double per = sell / Math.max(1, it.getInt("stack-size", 1));
                    List<String> en = it.getStringList("enchantments");
                    if (m == Material.ENCHANTED_BOOK && en.size() == 1) {
                        String[] p = en.get(0).split(":");
                        Enchantment e = ench(p[0]);
                        if (e != null) books.computeIfAbsent(e, x -> new TreeMap<>()).merge(Integer.parseInt(p[1].trim()), per, Math::max);
                        continue;
                    }
                    if (!en.isEmpty() || !PLAIN_KEYS.containsAll(it.getKeys(false))) continue; // iksir vb. ozel esyalar
                    unit.merge(m, per, Math::max);
                }
            }
        }
        plugin.getLogger().info("Satis fiyatlari: " + unit.size() + " esya, " + books.size() + " buyu yuklendi.");
    }

    private static void collect(File d, List<File> out) {
        File[] fs = d.listFiles();
        if (fs == null) return;
        for (File f : fs) if (f.isDirectory()) collect(f, out); else if (f.getName().endsWith(".yml")) out.add(f);
    }

    /** Tek bir buyu seviyesinin degeri. Tabloda yoksa en yakin seviyeden olceklenir (max ustu seviye basina x1.5). */
    double enchantValue(Enchantment e, int level) {
        TreeMap<Integer, Double> t = books.get(e);
        if (t == null || t.isEmpty()) return 0;
        Double exact = t.get(level);
        if (exact != null) return exact;
        Map.Entry<Integer, Double> ref = t.floorEntry(level);
        if (ref == null) ref = t.firstEntry();
        double v = ref.getValue() * level / ref.getKey();
        int max = e.getMaxLevel();
        if (level > max) v *= Math.pow(1.5, level - Math.max(max, ref.getKey()));
        return v;
    }

    /** Esyanin toplam satis degeri (0 = satilamaz). */
    public double price(ItemStack it) {
        if (it == null || it.getType().isAir()) return 0;
        ItemMeta meta = it.getItemMeta();
        if (meta != null && !meta.getPersistentDataContainer().getKeys().isEmpty()) return 0; // anahtar, minyon vb.
        Material m = it.getType();
        if (m == Material.SPAWNER) return 0;
        if (meta instanceof BlockStateMeta bsm && bsm.hasBlockState() && bsm.getBlockState() instanceof ShulkerBox sb) {
            for (ItemStack c : sb.getInventory().getContents()) if (c != null && !c.getType().isAir()) return 0; // dolu shulker satilmaz
        }
        double ench = 0;
        if (meta instanceof EnchantmentStorageMeta esm) for (var e : esm.getStoredEnchants().entrySet()) ench += enchantValue(e.getKey(), e.getValue());
        if (meta != null) for (var e : meta.getEnchants().entrySet()) ench += enchantValue(e.getKey(), e.getValue());
        double base = 0;
        if (m == Material.ENCHANTED_BOOK) base = 0;
        else {
            Double u = unit.get(m);
            if (u != null) {
                base = u;
                if (meta instanceof Damageable d && m.getMaxDurability() > 0 && d.hasDamage())
                    base *= Math.max(0, 1.0 - (double) d.getDamage() / m.getMaxDurability());
            }
        }
        if (base <= 0 && ench <= 0) return 0;
        return (base + ench) * it.getAmount();
    }

    // ======================= satis =======================
    private double mult() { return plugin.events() != null ? plugin.events().sellMultiplier() : 1.0; }

    public void openGui(Player p) {
        Holder h = new Holder();
        h.inv = Bukkit.createInventory(h, 54, PisanCore.color("&8Satış &7- eşyaları koy ve kapat"));
        p.openInventory(h.inv);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder) || !(e.getPlayer() instanceof Player p)) return;
        double total = 0; int sold = 0, returned = 0;
        for (ItemStack it : e.getInventory().getContents()) {
            if (it == null || it.getType().isAir()) continue;
            double v = price(it);
            if (v > 0) { total += v; sold += it.getAmount(); }
            else {
                returned += it.getAmount();
                for (ItemStack left : p.getInventory().addItem(it).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
            }
        }
        e.getInventory().clear();
        finish(p, total, sold, returned);
    }

    private void finish(Player p, double total, int sold, int returned) {
        if (sold == 0) {
            if (returned > 0) p.sendMessage(pre() + "§cBu eşyalar satılamıyor, geri verildi.");
            return;
        }
        double m = mult();
        double pay = Math.floor(total * m * 100) / 100.0;
        plugin.store().deposit(p.getUniqueId(), pay);
        p.sendMessage(pre() + "§a" + sold + " eşya satıldı: §6+" + PisanCore.fmt(pay) + " Coin"
                + (m > 1 ? " §7(etkinlik " + (m == Math.floor(m) ? String.valueOf((long) m) : String.valueOf(m)) + "x)" : "")
                + (returned > 0 ? " §8| §c" + returned + " satılamayan eşya geri verildi" : ""));
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
        plugin.getLogger().info("Satis: " + p.getName() + " " + sold + " esya -> " + pay + " coin");
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!(s instanceof Player p)) { s.sendMessage("Sadece oyuncular."); return true; }
        if (a.length >= 1 && (a[0].equalsIgnoreCase("el") || a[0].equalsIgnoreCase("hand"))) {
            ItemStack it = p.getInventory().getItemInMainHand();
            double v = price(it);
            if (v <= 0) { p.sendMessage(pre() + "§cElindeki eşya satılamıyor."); return true; }
            int n = it.getAmount();
            p.getInventory().setItemInMainHand(null);
            finish(p, v, n, 0);
            return true;
        }
        if (a.length >= 1 && (a[0].equalsIgnoreCase("fiyat") || a[0].equalsIgnoreCase("deger"))) {
            ItemStack it = p.getInventory().getItemInMainHand();
            double v = price(it);
            p.sendMessage(pre() + (v > 0 ? "§7Elindekinin değeri: §6" + PisanCore.fmt(Math.floor(v * mult() * 100) / 100.0) + " Coin" : "§cElindeki eşya satılamıyor."));
            return true;
        }
        openGui(p);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length != 1) return List.of();
        List<String> out = new ArrayList<>(List.of("el", "fiyat"));
        out.removeIf(x -> !x.startsWith(a[0].toLowerCase(Locale.ROOT)));
        return out;
    }
}
