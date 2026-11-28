package com.pisan.core;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Oyuncu pazari (/ah). Skyblock ve SMP pazarlari tamamen ayridir: ilan, satin alma ve
 * teslim kutusu oyuncunun aktif envanter moduna (InventoryManager) gore calisir.
 * Yasakli esyalar (varsayilan: buyu kitabi) iki pazarda da satisa konamaz.
 */
public final class AuctionManager implements Listener, CommandExecutor, TabCompleter {

    private static final int PER_PAGE = 45;

    private record Listing(UUID id, UUID seller, String sellerName, String mode, double price, ItemStack item, long expires) { }

    private final PisanCore plugin;
    private final File file;
    private final Map<UUID, Listing> listings = new LinkedHashMap<>();
    /** teslim kutusu: oyuncu -> mod -> esyalar (iptal / suresi dolan ilanlar) */
    private final Map<UUID, Map<String, List<ItemStack>>> mailbox = new HashMap<>();
    private boolean dirty;

    public AuctionManager(PisanCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "auction.yml");
    }

    private String pre() { return PisanCore.color(plugin.getConfig().getString("messages.prefix", "")); }
    private String mode(Player p) { return plugin.invs().mode(p); }
    private double tax() { return plugin.getConfig().getDouble("auction.tax", 0.03); }
    private int maxListings() { return plugin.getConfig().getInt("auction.max-listings", 10); }
    private long durationMs() { return plugin.getConfig().getLong("auction.duration-hours", 48) * 3600_000L; }
    private String modeTitle(String m) { return InventoryManager.SMP.equals(m) ? "&aSMP" : "&bSkyblock"; }

    // ======================= kayit =======================
    public void load() {
        listings.clear(); mailbox.clear();
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        var ls = y.getConfigurationSection("listings");
        if (ls != null) for (String k : ls.getKeys(false)) {
            try {
                var s = ls.getConfigurationSection(k);
                ItemStack it = decode(s.getString("item"));
                if (it == null) continue;
                UUID id = UUID.fromString(k);
                listings.put(id, new Listing(id, UUID.fromString(s.getString("seller")), s.getString("name", "?"),
                        s.getString("mode", InventoryManager.SKY), s.getDouble("price"), it, s.getLong("expires")));
            } catch (RuntimeException ex) { plugin.getLogger().warning("Pazar ilani okunamadi: " + k); }
        }
        var mb = y.getConfigurationSection("mailbox");
        if (mb != null) for (String u : mb.getKeys(false)) {
            var ms = mb.getConfigurationSection(u);
            for (String m : ms.getKeys(false)) {
                List<ItemStack> items = new ArrayList<>();
                for (String s : ms.getStringList(m)) { ItemStack it = decode(s); if (it != null) items.add(it); }
                if (!items.isEmpty()) mailbox.computeIfAbsent(UUID.fromString(u), x -> new HashMap<>()).put(m, items);
            }
        }
    }

    public void saveNow() { dirty = true; save(); }

    public void save() {
        if (!dirty) return;
        dirty = false;
        YamlConfiguration y = new YamlConfiguration();
        for (Listing l : listings.values()) {
            String k = "listings." + l.id();
            y.set(k + ".seller", l.seller().toString());
            y.set(k + ".name", l.sellerName());
            y.set(k + ".mode", l.mode());
            y.set(k + ".price", l.price());
            y.set(k + ".item", encode(l.item()));
            y.set(k + ".expires", l.expires());
        }
        for (var e : mailbox.entrySet()) for (var m : e.getValue().entrySet()) {
            if (m.getValue().isEmpty()) continue;
            List<String> out = new ArrayList<>();
            for (ItemStack it : m.getValue()) out.add(encode(it));
            y.set("mailbox." + e.getKey() + "." + m.getKey(), out);
        }
        try { plugin.getDataFolder().mkdirs(); y.save(file); } catch (IOException ex) { plugin.getLogger().severe("auction.yml: " + ex.getMessage()); }
    }

    private static String encode(ItemStack it) { return Base64.getEncoder().encodeToString(it.serializeAsBytes()); }
    private ItemStack decode(String s) {
        try { return s == null ? null : ItemStack.deserializeBytes(Base64.getDecoder().decode(s)); }
        catch (Throwable t) { plugin.getLogger().warning("Pazar esyasi okunamadi: " + t.getMessage()); return null; }
    }

    public void start() {
        load();
        Bukkit.getScheduler().runTaskTimer(plugin, this::expire, 200L, 1200L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::save, 600L, 600L);
    }

    private void expire() {
        long now = System.currentTimeMillis();
        for (Iterator<Listing> i = listings.values().iterator(); i.hasNext(); ) {
            Listing l = i.next();
            if (l.expires() > now) continue;
            i.remove();
            mail(l.seller(), l.mode(), l.item());
            Player s = Bukkit.getPlayer(l.seller());
            if (s != null) s.sendMessage(pre() + "§7Pazardaki §f" + name(l.item()) + " §7ilanının süresi doldu. §e/ah §7» Teslim Kutusu");
            dirty = true;
        }
    }

    private void mail(UUID who, String mode, ItemStack it) {
        mailbox.computeIfAbsent(who, x -> new HashMap<>()).computeIfAbsent(mode, x -> new ArrayList<>()).add(it);
        dirty = true;
    }

    private List<ItemStack> box(UUID who, String mode) {
        var m = mailbox.get(who);
        return m == null ? List.of() : m.getOrDefault(mode, List.of());
    }

    // ======================= yasakli esyalar =======================
    private boolean blocked(ItemStack it) {
        if (it == null) return false;
        List<String> mats = plugin.getConfig().getStringList("auction.blocked-materials");
        if (mats.contains(it.getType().name())) return true;
        ItemMeta meta = it.getItemMeta();
        if (meta instanceof BlockStateMeta bsm && bsm.hasBlockState() && bsm.getBlockState() instanceof org.bukkit.block.Container c)
            for (ItemStack in : c.getInventory().getContents()) if (blocked(in)) return true;
        if (meta instanceof BundleMeta bm) for (ItemStack in : bm.getItems()) if (blocked(in)) return true;
        return false;
    }

    // ======================= GUI =======================
    private enum View { BROWSE, MINE, BOX, CONFIRM }

    private static final class Menu implements InventoryHolder {
        final View view; final int page; final String mode;
        final Map<Integer, UUID> slots = new HashMap<>();
        UUID target;
        Inventory inv;
        Menu(View v, int page, String mode) { this.view = v; this.page = page; this.mode = mode; }
        @Override public Inventory getInventory() { return inv; }
    }

    private static String name(ItemStack it) {
        ItemMeta m = it.getItemMeta();
        String n = m != null && m.hasDisplayName() ? m.getDisplayName() : it.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return (it.getAmount() > 1 ? it.getAmount() + "x " : "") + n;
    }

    private ItemStack button(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(PisanCore.color(name));
        List<String> l = new ArrayList<>();
        for (String s : lore) l.add(PisanCore.color(s));
        meta.setLore(l);
        meta.addItemFlags(ItemFlag.values());
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack display(Listing l, String... extra) {
        ItemStack it = l.item().clone();
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return it;
        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        lore.add(PisanCore.color("&8&m----------------"));
        lore.add(PisanCore.color("&7Fiyat: &6" + PisanCore.fmt(l.price()) + " Coin"));
        lore.add(PisanCore.color("&7Satıcı: &f" + l.sellerName()));
        long left = Math.max(0, l.expires() - System.currentTimeMillis()) / 60000;
        lore.add(PisanCore.color("&7Kalan süre: &f" + (left / 60) + "sa " + (left % 60) + "dk"));
        for (String s : extra) lore.add(PisanCore.color(s));
        meta.setLore(lore);
        it.setItemMeta(meta);
        return it;
    }

    private List<Listing> visible(String mode, UUID onlySeller) {
        long now = System.currentTimeMillis();
        List<Listing> out = new ArrayList<>();
        for (Listing l : listings.values())
            if (l.mode().equals(mode) && l.expires() > now && (onlySeller == null || l.seller().equals(onlySeller))) out.add(l);
        if (onlySeller == null) Collections.reverse(out); // en yeni ilan once
        return out;
    }

    public void open(Player p, View v, int page) {
        String mode = mode(p);
        Menu h = new Menu(v, page, mode);
        String title = switch (v) {
            case BROWSE -> "&8Pazar &7» " + modeTitle(mode);
            case MINE -> "&8İlanlarım &7» " + modeTitle(mode);
            case BOX -> "&8Teslim Kutusu &7» " + modeTitle(mode);
            case CONFIRM -> "&8Satın al?";
        };
        h.inv = Bukkit.createInventory(h, v == View.CONFIRM ? 27 : 54, PisanCore.color(title));
        ItemStack glass = button(Material.BLACK_STAINED_GLASS_PANE, " ");
        if (v == View.BROWSE || v == View.MINE) {
            List<Listing> list = visible(mode, v == View.MINE ? p.getUniqueId() : null);
            int pages = Math.max(1, (list.size() + PER_PAGE - 1) / PER_PAGE);
            int pg = Math.max(0, Math.min(page, pages - 1));
            for (int i = 0; i < PER_PAGE && pg * PER_PAGE + i < list.size(); i++) {
                Listing l = list.get(pg * PER_PAGE + i);
                h.slots.put(i, l.id());
                h.inv.setItem(i, v == View.MINE ? display(l, "", "&cTıkla » İlanı kaldır")
                        : display(l, "", l.seller().equals(p.getUniqueId()) ? "&8(senin ilanın)" : "&eTıkla » Satın al"));
            }
            for (int i = 45; i < 54; i++) h.inv.setItem(i, glass);
            if (pg > 0) h.inv.setItem(45, button(Material.ARROW, "&e« Önceki sayfa"));
            if (pg < pages - 1) h.inv.setItem(53, button(Material.ARROW, "&eSonraki sayfa »"));
            h.inv.setItem(49, button(Material.GOLD_INGOT, modeTitle(mode) + " Pazarı",
                    "&7Sayfa &f" + (pg + 1) + "/" + pages + " &8• &7İlan: &f" + list.size(),
                    "&7Satışa koy: &e/ah sat <fiyat>", "&7Vergi: &f%" + Math.round(tax() * 100),
                    "&8Skyblock ve SMP pazarları ayrıdır.", "&cBüyü kitabı satılamaz."));
            if (v == View.BROWSE) h.inv.setItem(47, button(Material.CHEST, "&aİlanlarım", "&7Kendi ilanlarını gör / kaldır"));
            else h.inv.setItem(47, button(Material.EMERALD, "&aPazara dön"));
            int n = box(p.getUniqueId(), mode).size();
            h.inv.setItem(51, button(n > 0 ? Material.ENDER_CHEST : Material.BARREL, "&dTeslim Kutusu", "&7Kaldırılan / süresi dolan ilanlar", "&7Bekleyen: &f" + n));
        } else if (v == View.BOX) {
            List<ItemStack> b = box(p.getUniqueId(), mode);
            for (int i = 0; i < Math.min(45, b.size()); i++) h.inv.setItem(i, b.get(i));
            for (int i = 45; i < 54; i++) h.inv.setItem(i, glass);
            h.inv.setItem(49, button(Material.HOPPER, "&aHepsini al", "&7Esyaya tıklayarak tek tek de alabilirsin"));
            h.inv.setItem(45, button(Material.EMERALD, "&aPazara dön"));
        }
        p.openInventory(h.inv);
    }

    private void confirm(Player p, Listing l) {
        Menu h = new Menu(View.CONFIRM, 0, l.mode());
        h.target = l.id();
        h.inv = Bukkit.createInventory(h, 27, PisanCore.color("&8Satın al? &7» &6" + PisanCore.fmt(l.price()) + " Coin"));
        ItemStack glass = button(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 27; i++) h.inv.setItem(i, glass);
        h.inv.setItem(13, display(l));
        for (int s : new int[]{10, 11}) h.inv.setItem(s, button(Material.LIME_CONCRETE, "&a&lSATIN AL", "&7" + PisanCore.fmt(l.price()) + " Coin"));
        for (int s : new int[]{15, 16}) h.inv.setItem(s, button(Material.RED_CONCRETE, "&c&lVAZGEÇ"));
        p.openInventory(h.inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu h) || !(e.getWhoClicked() instanceof Player p)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != e.getInventory()) return;
        // menu acikken mod degistiyse (teleport vb.) islem yapma
        if (!h.mode.equals(mode(p))) { p.closeInventory(); return; }
        int slot = e.getRawSlot();
        switch (h.view) {
            case BROWSE, MINE -> {
                if (slot == 45 && e.getCurrentItem() != null && e.getCurrentItem().getType() == Material.ARROW) { open(p, h.view, h.page - 1); return; }
                if (slot == 53 && e.getCurrentItem() != null && e.getCurrentItem().getType() == Material.ARROW) { open(p, h.view, h.page + 1); return; }
                if (slot == 47) { open(p, h.view == View.BROWSE ? View.MINE : View.BROWSE, 0); return; }
                if (slot == 51) { open(p, View.BOX, 0); return; }
                UUID id = h.slots.get(slot);
                if (id == null) return;
                Listing l = listings.get(id);
                if (l == null) { p.sendMessage(pre() + "§cBu ilan artık yok."); open(p, h.view, h.page); return; }
                if (h.view == View.MINE) { cancel(p, l); open(p, View.MINE, h.page); return; }
                if (l.seller().equals(p.getUniqueId())) { p.sendMessage(pre() + "§7Kendi ilanını §eİlanlarım §7bölümünden kaldırabilirsin."); return; }
                confirm(p, l);
            }
            case CONFIRM -> {
                if (slot == 10 || slot == 11) { buy(p, h.target); p.closeInventory(); }
                else if (slot == 15 || slot == 16) open(p, View.BROWSE, 0);
            }
            case BOX -> {
                if (slot == 45) { open(p, View.BROWSE, 0); return; }
                List<ItemStack> b = mailbox.getOrDefault(p.getUniqueId(), Map.of()).get(h.mode);
                if (b == null || b.isEmpty()) return;
                if (slot == 49) {
                    for (Iterator<ItemStack> i = b.iterator(); i.hasNext(); ) {
                        ItemStack it = i.next();
                        if (!fits(p, it)) { p.sendMessage(pre() + "§cEnvanterin dolu."); break; }
                        p.getInventory().addItem(it);
                        i.remove(); dirty = true;
                    }
                } else if (slot < b.size()) {
                    ItemStack it = b.get(slot);
                    if (!fits(p, it)) { p.sendMessage(pre() + "§cEnvanterin dolu."); return; }
                    b.remove(slot); dirty = true;
                    p.getInventory().addItem(it);
                } else return;
                save();
                open(p, View.BOX, 0);
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) { if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true); }

    private static boolean fits(Player p, ItemStack it) {
        int need = it.getAmount();
        for (ItemStack s : p.getInventory().getStorageContents()) {
            if (s == null || s.getType().isAir()) need -= it.getMaxStackSize();
            else if (s.isSimilar(it)) need -= it.getMaxStackSize() - s.getAmount();
            if (need <= 0) return true;
        }
        return false;
    }

    // ======================= islemler =======================
    private void buy(Player p, UUID id) {
        Listing l = listings.get(id);
        if (l == null || l.expires() <= System.currentTimeMillis()) { p.sendMessage(pre() + "§cBu ilan artık yok."); return; }
        if (!l.mode().equals(mode(p))) { p.sendMessage(pre() + "§cBu ilan başka modun pazarında."); return; }
        if (l.seller().equals(p.getUniqueId())) return;
        if (!fits(p, l.item())) { p.sendMessage(pre() + "§cEnvanterinde yer yok."); return; }
        if (!plugin.store().withdraw(p.getUniqueId(), l.price())) { p.sendMessage(pre() + "§cYeterli Coin yok. §7(" + PisanCore.fmt(l.price()) + " Coin)"); return; }
        listings.remove(id);
        dirty = true;
        double gain = l.price() * (1 - tax());
        plugin.store().deposit(l.seller(), gain);
        for (ItemStack left : p.getInventory().addItem(l.item().clone()).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
        save();
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
        p.sendMessage(pre() + "§a" + name(l.item()) + " §asatın alındı! §7(-" + PisanCore.fmt(l.price()) + " Coin)");
        Player s = Bukkit.getPlayer(l.seller());
        if (s != null) s.sendMessage(pre() + "§6" + p.getName() + " §epazardaki §f" + name(l.item()) + " §eilanını aldı! §a+" + PisanCore.fmt(gain) + " Coin");
    }

    private void cancel(Player p, Listing l) {
        if (listings.remove(l.id()) == null) return;
        dirty = true;
        if (fits(p, l.item())) { p.getInventory().addItem(l.item().clone()); p.sendMessage(pre() + "§7İlan kaldırıldı, eşya envanterine eklendi."); }
        else { mail(p.getUniqueId(), l.mode(), l.item()); p.sendMessage(pre() + "§7İlan kaldırıldı, eşya §dTeslim Kutusuna §7gönderildi."); }
        save();
    }

    private void sell(Player p, String priceStr) {
        double price;
        try { price = Double.parseDouble(priceStr.replace(",", ".")); } catch (NumberFormatException ex) { p.sendMessage(pre() + "§cGeçersiz fiyat."); return; }
        double min = plugin.getConfig().getDouble("auction.min-price", 1), max = plugin.getConfig().getDouble("auction.max-price", 1_000_000_000);
        if (!(price >= min) || price > max || Double.isNaN(price)) { p.sendMessage(pre() + "§cFiyat " + PisanCore.fmt(min) + " - " + PisanCore.fmt(max) + " arasında olmalı."); return; }
        price = Math.floor(price * 100) / 100.0;
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand == null || hand.getType().isAir()) { p.sendMessage(pre() + "§cElinde satılacak eşya yok."); return; }
        if (blocked(hand)) { p.sendMessage(pre() + "§cBüyü kitapları pazarda satılamaz."); return; }
        String mode = mode(p);
        if (visible(mode, p.getUniqueId()).size() >= maxListings() && !p.hasPermission("pisancore.admin")) {
            p.sendMessage(pre() + "§cEn fazla " + maxListings() + " ilanın olabilir."); return;
        }
        ItemStack item = hand.clone();
        p.getInventory().setItemInMainHand(null);
        Listing l = new Listing(UUID.randomUUID(), p.getUniqueId(), p.getName(), mode, price, item, System.currentTimeMillis() + durationMs());
        listings.put(l.id(), l);
        dirty = true;
        save();
        p.sendMessage(pre() + "§f" + name(item) + " §a" + PisanCore.fmt(price) + " Coin'e " + PisanCore.color(modeTitle(mode)) + " §apazarına konuldu.");
        for (Player o : Bukkit.getOnlinePlayers())
            if (!o.equals(p) && mode(o).equals(mode))
                o.sendMessage(pre() + "§6" + p.getName() + " §7pazara §f" + name(item) + " §7koydu: §6" + PisanCore.fmt(price) + " Coin §8(/ah)");
    }

    // Giriste teslim kutusu hatirlatmasi
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        var m = mailbox.get(p.getUniqueId());
        if (m == null) return;
        int n = m.values().stream().mapToInt(List::size).sum();
        if (n > 0) Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline())
            p.sendMessage(pre() + "§dTeslim kutunda §f" + n + " §deşya var. §7(/ah » Teslim Kutusu, eşyanın ait olduğu modda)"); }, 80L);
    }

    // ======================= komut =======================
    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!(s instanceof Player p)) { s.sendMessage("Sadece oyuncular."); return true; }
        if (a.length == 0) { open(p, View.BROWSE, 0); return true; }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "sat", "sell" -> { if (a.length < 2) p.sendMessage(pre() + "§cKullanım: §f/ah sat <fiyat> §7(elindeki eşya)"); else sell(p, a[1]); }
            case "ilanlarim", "ilanlar", "listings" -> open(p, View.MINE, 0);
            case "kutu", "teslim", "collect" -> open(p, View.BOX, 0);
            default -> {
                // /ah <fiyat> kisayolu
                try { Double.parseDouble(a[0].replace(",", ".")); sell(p, a[0]); }
                catch (NumberFormatException ex) { p.sendMessage(pre() + "§7/ah §8| §7/ah sat <fiyat> §8| §7/ah ilanlarim §8| §7/ah kutu"); }
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length != 1) return List.of();
        return List.of("sat", "ilanlarim", "kutu").stream().filter(x -> x.startsWith(a[0].toLowerCase(Locale.ROOT))).toList();
    }
}
