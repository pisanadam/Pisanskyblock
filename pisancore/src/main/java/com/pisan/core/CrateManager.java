package com.pisan.core;

import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/** Sanal anahtarli kasa sistemi. Kasalar crates.yml'den, anahtar bakiyeleri keys.yml'den. */
public final class CrateManager {

    public record Reward(String id, double weight, ItemStack item, double coins, List<String> commands,
                         String displayName, boolean broadcast) { }

    public record Crate(String id, String name, Material icon, int slot, List<String> lore, List<Reward> rewards) {
        double totalWeight() { double t = 0; for (Reward r : rewards) t += r.weight(); return t; }
    }

    private final PisanCore plugin;
    private final File cratesFile, keysFile;
    private final Map<String, Crate> crates = new LinkedHashMap<>();
    private final Map<UUID, Map<String, Integer>> keys = new ConcurrentHashMap<>();
    private volatile boolean dirty;

    public CrateManager(PisanCore plugin) {
        this.plugin = plugin;
        this.cratesFile = new File(plugin.getDataFolder(), "crates.yml");
        this.keysFile = new File(plugin.getDataFolder(), "keys.yml");
    }

    // ---------------- yukleme ----------------
    public void load() {
        if (!cratesFile.exists()) plugin.saveResource("crates.yml", false);
        crates.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(cratesFile);
        ConfigurationSection cs = y.getConfigurationSection("crates");
        if (cs != null) for (String id : cs.getKeys(false)) {
            ConfigurationSection c = cs.getConfigurationSection(id);
            List<Reward> rewards = new ArrayList<>();
            ConfigurationSection rs = c.getConfigurationSection("rewards");
            if (rs != null) for (String rid : rs.getKeys(false)) {
                try { rewards.add(parseReward(rid, rs.getConfigurationSection(rid))); }
                catch (Exception ex) { plugin.getLogger().warning("Kasa " + id + " odul " + rid + " hatali: " + ex.getMessage()); }
            }
            Material icon = Material.matchMaterial(c.getString("icon", "CHEST"));
            crates.put(id, new Crate(id, c.getString("name", id), icon == null ? Material.CHEST : icon,
                    c.getInt("slot", -1), c.getStringList("lore"), rewards));
        }
        plugin.getLogger().info(crates.size() + " kasa yuklendi.");

        keys.clear();
        if (keysFile.exists()) {
            YamlConfiguration k = YamlConfiguration.loadConfiguration(keysFile);
            for (String u : k.getKeys(false)) {
                try {
                    Map<String, Integer> m = new ConcurrentHashMap<>();
                    ConfigurationSection s = k.getConfigurationSection(u);
                    if (s != null) for (String kid : s.getKeys(false)) m.put(kid, s.getInt(kid));
                    keys.put(UUID.fromString(u), m);
                } catch (IllegalArgumentException ignored) { }
            }
        }
    }

    public void reloadCrates() {
        crates.clear();
        Map<UUID, Map<String, Integer>> backup = new HashMap<>(keys);
        load();
        keys.clear(); keys.putAll(backup);
    }

    private Reward parseReward(String rid, ConfigurationSection r) {
        double weight = r.getDouble("weight", 1);
        double coins = r.getDouble("coins", 0);
        List<String> cmds = r.getStringList("commands");
        ItemStack item = null;
        String itemName = r.getString("item");
        if (itemName != null) {
            Material m = Material.matchMaterial(itemName);
            if (m == null) throw new IllegalArgumentException("bilinmeyen esya " + itemName);
            item = new ItemStack(m, Math.max(1, r.getInt("amount", 1)));
            ItemMeta meta = item.getItemMeta();
            if (r.isString("name")) meta.setDisplayName(PisanCore.color(r.getString("name")));
            if (r.isList("lore")) { List<String> l = new ArrayList<>(); for (String s : r.getStringList("lore")) l.add(PisanCore.color(s)); meta.setLore(l); }
            ConfigurationSection en = r.getConfigurationSection("enchants");
            if (en != null) for (String e : en.getKeys(false)) {
                Enchantment ench = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(e.toLowerCase(Locale.ROOT)));
                if (ench == null) throw new IllegalArgumentException("bilinmeyen buyu " + e);
                if (meta instanceof EnchantmentStorageMeta esm) esm.addStoredEnchant(ench, en.getInt(e), true);
                else meta.addEnchant(ench, en.getInt(e), true);
            }
            if (r.getBoolean("unbreakable")) meta.setUnbreakable(true);
            item.setItemMeta(meta);
        }
        String display = r.getString("display", null);
        if (display == null) display = item != null ? null : (coins > 0 ? "&6" + PisanCore.fmt(coins) + " Coin" : rid);
        if (item == null && coins <= 0 && cmds.isEmpty()) throw new IllegalArgumentException("odul bos");
        return new Reward(rid, weight, item, coins, cmds, display, r.getBoolean("broadcast", false));
    }

    // ---------------- anahtarlar ----------------
    public Collection<Crate> crates() { return crates.values(); }
    public Crate crate(String id) { return crates.get(id.toLowerCase(Locale.ROOT)); }

    public int keys(UUID id, String crate) { return keys.getOrDefault(id, Map.of()).getOrDefault(crate, 0); }

    public void addKeys(UUID id, String crate, int amt) {
        Map<String, Integer> m = keys.computeIfAbsent(id, k -> new ConcurrentHashMap<>());
        m.merge(crate, amt, Integer::sum);
        if (m.get(crate) <= 0) m.remove(crate);
        dirty = true;
    }

    public synchronized void saveKeys(boolean force) {
        if (!dirty && !force) return;
        YamlConfiguration y = new YamlConfiguration();
        for (var e : keys.entrySet()) for (var k : e.getValue().entrySet()) y.set(e.getKey() + "." + k.getKey(), k.getValue());
        try { plugin.getDataFolder().mkdirs(); y.save(keysFile); dirty = false; }
        catch (IOException ex) { plugin.getLogger().severe("keys.yml kaydedilemedi: " + ex.getMessage()); }
    }

    // ---------------- acma ----------------
    public Reward roll(Crate c) {
        double t = c.totalWeight(), x = ThreadLocalRandom.current().nextDouble() * t, acc = 0;
        for (Reward r : c.rewards()) { acc += r.weight(); if (x < acc) return r; }
        return c.rewards().get(c.rewards().size() - 1);
    }

    public boolean open(Player p, Crate c) {
        if (c.rewards().isEmpty()) { p.sendMessage(PisanCore.color("&cBu kasa boş.")); return false; }
        if (keys(p.getUniqueId(), c.id()) <= 0) {
            p.sendMessage(PisanCore.color(plugin.getConfig().getString("messages.prefix", "") + "&cBu kasa için anahtarın yok. &7Taş jeneratöründe taş kırarak bulabilirsin."));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return false;
        }
        addKeys(p.getUniqueId(), c.id(), -1);
        Reward r = roll(c);
        give(p, r);
        String rn = rewardName(r);
        double chance = r.weight() / c.totalWeight() * 100.0;
        p.sendMessage(PisanCore.color(plugin.getConfig().getString("messages.prefix", "") + c.name() + " &7» &f" + rn
                + " &8(%" + String.format(Locale.ROOT, "%.2f", chance) + ")"));
        p.sendTitle(PisanCore.color(c.name()), PisanCore.color("&f" + rn), 5, 40, 10);
        p.playSound(p.getLocation(), chance < 5 ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
        if (r.broadcast() || chance < 2)
            Bukkit.broadcastMessage(PisanCore.color(plugin.getConfig().getString("messages.prefix", "")
                    + "&6" + p.getName() + " &e" + c.name() + " &eaçtı ve &f" + rn + " &ekazandı!"));
        return true;
    }

    private void give(Player p, Reward r) {
        if (r.item() != null) {
            for (ItemStack left : p.getInventory().addItem(r.item().clone()).values())
                p.getWorld().dropItemNaturally(p.getLocation(), left);
        }
        if (r.coins() > 0) plugin.store().deposit(p.getUniqueId(), r.coins());
        for (String cmd : r.commands()) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", p.getName()));
    }

    public String rewardName(Reward r) {
        if (r.displayName() != null) return PisanCore.color(r.displayName());
        ItemStack i = r.item();
        ItemMeta m = i.getItemMeta();
        String base = m != null && m.hasDisplayName() ? m.getDisplayName() : pretty(i.getType().name());
        StringBuilder sb = new StringBuilder();
        if (i.getAmount() > 1) sb.append(i.getAmount()).append("x ");
        sb.append(base);
        Map<Enchantment, Integer> en = m instanceof EnchantmentStorageMeta esm ? esm.getStoredEnchants() : i.getEnchantments();
        if (!en.isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (var e : en.entrySet()) parts.add(pretty(e.getKey().getKey().getKey()) + " " + roman(e.getValue()));
            sb.append(" §7(").append(String.join(", ", parts)).append(")");
        }
        return sb.toString();
    }

    public ItemStack previewIcon(Crate c, Reward r) {
        ItemStack icon = r.item() != null ? r.item().clone()
                : new ItemStack(r.coins() > 0 ? Material.GOLD_INGOT : Material.PAPER);
        ItemMeta m = icon.getItemMeta();
        if (r.item() == null) m.setDisplayName(PisanCore.color(r.displayName()));
        List<String> lore = m.hasLore() ? new ArrayList<>(m.getLore()) : new ArrayList<>();
        lore.add("");
        lore.add(PisanCore.color("&7Şans: &e%" + String.format(Locale.ROOT, "%.2f", r.weight() / c.totalWeight() * 100)));
        m.setLore(lore);
        m.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        icon.setItemMeta(m);
        return icon;
    }

    static String pretty(String s) {
        String[] w = s.toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String x : w) if (!x.isEmpty()) sb.append(Character.toUpperCase(x.charAt(0))).append(x.substring(1)).append(' ');
        return sb.toString().trim();
    }

    static String roman(int n) {
        String[] r = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return n >= 0 && n < r.length ? r[n] : String.valueOf(n);
    }
}
