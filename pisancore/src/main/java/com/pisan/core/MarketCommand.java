package com.pisan.core;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.*;

/**
 * /market <esya> : EconomyShopGUI'de o esyanin satin alma ekranini dogrudan acar.
 * /market (bos) : /shop menusunu acar. Turkce kisa adlar da calisir (elmas, demir, huni...).
 * EconomyShopGUI API'sine yansima ile erisilir (derleme bagimliligi yok).
 */
public final class MarketCommand implements CommandExecutor, TabCompleter {

    private static final Map<String, String> TR = new HashMap<>();
    static {
        String[][] a = {
            {"elmas", "DIAMOND"}, {"demir", "IRON_INGOT"}, {"altin", "GOLD_INGOT"}, {"kömür", "COAL"}, {"komur", "COAL"},
            {"zumrut", "EMERALD"}, {"zümrüt", "EMERALD"}, {"bakir", "COPPER_INGOT"}, {"lapis", "LAPIS_LAZULI"},
            {"tas", "COBBLESTONE"}, {"taş", "COBBLESTONE"}, {"kiriktas", "COBBLESTONE"}, {"odun", "OAK_LOG"}, {"kalas", "OAK_PLANKS"},
            {"huni", "HOPPER"}, {"sandik", "CHEST"}, {"sandık", "CHEST"}, {"varil", "BARREL"}, {"firin", "FURNACE"}, {"fırın", "FURNACE"},
            {"cam", "GLASS"}, {"kum", "SAND"}, {"toprak", "DIRT"}, {"cimen", "GRASS_BLOCK"}, {"çimen", "GRASS_BLOCK"},
            {"su", "WATER_BUCKET"}, {"lav", "LAVA_BUCKET"}, {"kova", "BUCKET"}, {"ekmek", "BREAD"}, {"bugday", "WHEAT"},
            {"tohum", "WHEAT_SEEDS"}, {"seker", "SUGAR_CANE"}, {"kemik", "BONE_MEAL"}, {"fidan", "OAK_SAPLING"},
            {"piston", "PISTON"}, {"tekrarlayici", "REPEATER"}, {"kiyaslayici", "COMPARATOR"}, {"meşale", "TORCH"}, {"mesale", "TORCH"},
            {"inci", "ENDER_PEARL"}, {"obsidyen", "OBSIDIAN"}, {"spawner", "SPAWNER"},
            {"elmaskilic", "DIAMOND_SWORD"}, {"elmaskazma", "DIAMOND_PICKAXE"}, {"demirkazma", "IRON_PICKAXE"},
            {"kitap", "BOOK"}, {"masa", "CRAFTING_TABLE"}, {"ors", "ANVIL"}, {"örs", "ANVIL"}, {"buyumasasi", "ENCHANTING_TABLE"},
        };
        for (String[] x : a) TR.put(x[0], x[1]);
    }

    private final PisanCore plugin;
    private Method getShopItem, isBuyAble, openScreen;
    private Object buyMode;
    private List<String> cache;
    private long cacheTime;

    public MarketCommand(PisanCore plugin) { this.plugin = plugin; }

    private String pre() { return PisanCore.color(plugin.getConfig().getString("messages.prefix", "")); }

    private boolean hook() {
        if (getShopItem != null) return true;
        try {
            Plugin es = Bukkit.getPluginManager().getPlugin("EconomyShopGUI");
            if (es == null || !es.isEnabled()) return false;
            ClassLoader cl = es.getClass().getClassLoader();
            Class<?> hook = Class.forName("me.gypopo.economyshopgui.api.EconomyShopGUIHook", true, cl);
            Class<?> shopItem = Class.forName("me.gypopo.economyshopgui.objects.ShopItem", true, cl);
            Class<?> mode = Class.forName("me.gypopo.economyshopgui.util.Transaction$Mode", true, cl);
            getShopItem = hook.getMethod("getShopItem", Player.class, ItemStack.class);
            isBuyAble = hook.getMethod("isBuyAble", shopItem);
            openScreen = hook.getMethod("openTransactionScreen", Player.class, shopItem, mode);
            buyMode = mode.getField("BUY").get(null);
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("EconomyShopGUI API baglanamadi: " + t);
            getShopItem = null;
            return false;
        }
    }

    private static Material resolve(String in) {
        String k = in.toLowerCase(Locale.ROOT).replace("minecraft:", "");
        if (TR.containsKey(k)) return Material.matchMaterial(TR.get(k));
        Material m = Material.matchMaterial(k);
        if (m == null) m = Material.matchMaterial(k.replace(' ', '_'));
        return m;
    }

    private Object shopItem(Player p, Material m) throws Exception {
        if (m == null || !m.isItem() || m.isAir()) return null;
        return getShopItem.invoke(null, p, new ItemStack(m));
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!(s instanceof Player p)) { s.sendMessage("Sadece oyuncular."); return true; }
        if (a.length == 0) { p.performCommand("shop"); return true; }
        if (!hook()) { p.sendMessage(pre() + "§cMarket şu an kullanılamıyor."); return true; }
        String q = String.join("_", a);
        Material m = resolve(q);
        if (m == null) { p.sendMessage(pre() + "§cBöyle bir eşya yok: §f" + q + " §7(ör: /market elmas, /market hopper)"); return true; }
        try {
            Object si = shopItem(p, m);
            if (si == null) { p.sendMessage(pre() + "§c" + CrateManager.pretty(m.name()) + " markette satılmıyor."); return true; }
            if (!(boolean) isBuyAble.invoke(null, si)) { p.sendMessage(pre() + "§c" + CrateManager.pretty(m.name()) + " marketten satın alınamaz (sadece satılabilir)."); return true; }
            openScreen.invoke(null, p, si, buyMode);
        } catch (Throwable t) {
            p.sendMessage(pre() + "§cSatın alma ekranı açılamadı.");
            plugin.getLogger().warning("/market hata: " + t);
        }
        return true;
    }

    private List<String> buyable(Player p) {
        if (cache != null && System.currentTimeMillis() - cacheTime < 300_000) return cache;
        List<String> l = new ArrayList<>();
        if (hook()) for (Material m : Material.values()) {
            try {
                if (m.isLegacy() || !m.isItem() || m.isAir()) continue;
                Object si = shopItem(p, m);
                if (si != null && (boolean) isBuyAble.invoke(null, si)) l.add(m.name().toLowerCase(Locale.ROOT));
            } catch (Throwable ignored) { }
        }
        for (var e : TR.entrySet()) if (l.contains(e.getValue().toLowerCase(Locale.ROOT))) l.add(e.getKey());
        Collections.sort(l);
        cache = l; cacheTime = System.currentTimeMillis();
        return l;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length != 1 || !(s instanceof Player p)) return List.of();
        String pre = a[0].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String x : buyable(p)) if (x.startsWith(pre)) { out.add(x); if (out.size() >= 80) break; }
        return out;
    }
}
