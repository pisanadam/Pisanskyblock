package com.pisan.core;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * /takas <oyuncu>, /takas kabul [oyuncu], /takas red [oyuncu]
 * Iki oyuncu ortak bir pencerede esya + coin koyar. Ikisi de "Onayla" deyince 3 sn geri sayim,
 * bu surede herhangi bir degisiklik onaylari sifirlar (dolandiricilik onlemi).
 */
public final class TradeManager implements Listener, CommandExecutor, TabCompleter {

    private static final long REQUEST_MS = 60_000;
    // Sol taraf (istek gonderen = A): sutun 0-3, satir 0-4. Sag taraf (B): sutun 5-8.
    private static final int READY_A = 45, ADD100_A = 46, ADD1K_A = 47, RESET_A = 48;
    private static final int READY_B = 53, ADD100_B = 52, ADD1K_B = 51, RESET_B = 50;

    private final class Trade implements InventoryHolder {
        final Player a, b;
        final Inventory inv;
        boolean readyA, readyB, done;
        double coinsA, coinsB;
        BukkitTask countdown;
        Trade(Player a, Player b) {
            this.a = a; this.b = b;
            this.inv = Bukkit.createInventory(this, 54, PisanCore.color("&8Takas: &0" + a.getName() + " &8⇄ &0" + b.getName()));
        }
        @Override public Inventory getInventory() { return inv; }
        boolean isA(Player p) { return p.getUniqueId().equals(a.getUniqueId()); }
        Player other(Player p) { return isA(p) ? b : a; }
    }

    private final PisanCore plugin;
    private final Map<UUID, Map<UUID, Long>> requests = new HashMap<>();
    private final Map<UUID, Trade> trades = new HashMap<>();

    public TradeManager(PisanCore plugin) { this.plugin = plugin; }

    private String pre() { return PisanCore.color(plugin.getConfig().getString("messages.prefix", "")); }

    static boolean areaA(int slot) { return slot < 45 && slot % 9 <= 3; }
    static boolean areaB(int slot) { return slot < 45 && slot % 9 >= 5; }
    private static boolean own(Trade t, Player p, int slot) { return t.isA(p) ? areaA(slot) : areaB(slot); }

    // ---------------- komut ----------------
    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!(s instanceof Player p)) { s.sendMessage("Sadece oyuncular."); return true; }
        if (a.length == 0) {
            p.sendMessage(pre() + "§6Takas komutları:");
            p.sendMessage(" §e/takas <oyuncu> §7- Takas isteği gönder");
            p.sendMessage(" §e/takas kabul [oyuncu] §7- İsteği kabul et");
            p.sendMessage(" §e/takas red [oyuncu] §7- İsteği reddet");
            return true;
        }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "kabul", "accept" -> accept(p, a.length > 1 ? a[1] : null);
            case "red", "deny" -> {
                UUID from = take(p, a.length > 1 ? a[1] : null);
                if (from == null) { p.sendMessage(pre() + "§cBekleyen takas isteği yok."); return true; }
                p.sendMessage(pre() + "§7Takas isteği reddedildi.");
                Player f = Bukkit.getPlayer(from);
                if (f != null) f.sendMessage(pre() + "§c" + p.getName() + " takas isteğini reddetti.");
            }
            default -> request(p, a[0]);
        }
        return true;
    }

    private void request(Player p, String name) {
        Player t = Bukkit.getPlayerExact(name);
        if (t == null) { p.sendMessage(pre() + "§cOyuncu çevrimiçi değil."); return; }
        if (t.equals(p)) { p.sendMessage(pre() + "§cKendinle takas yapamazsın."); return; }
        if (trades.containsKey(p.getUniqueId()) || trades.containsKey(t.getUniqueId())) { p.sendMessage(pre() + "§cSen ya da o zaten takasta."); return; }
        if (!plugin.invs().sameMode(p, t)) { p.sendMessage(pre() + "§cFarklı moddasınız (Skyblock / SMP envanterleri ayrı)."); return; }
        requests.computeIfAbsent(t.getUniqueId(), k -> new HashMap<>()).put(p.getUniqueId(), System.currentTimeMillis());
        p.sendMessage(pre() + "§f" + t.getName() + " §7oyuncusuna takas isteği gönderildi. §8(60 sn)");
        t.sendMessage(pre() + "§6" + p.getName() + " §esana takas isteği gönderdi!");
        t.sendMessage(" §7Kabul: §a/takas kabul " + p.getName() + " §7| Red: §c/takas red " + p.getName());
        t.playSound(t.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.8f);
    }

    private UUID take(Player target, String fromName) {
        Map<UUID, Long> m = requests.get(target.getUniqueId());
        if (m == null) return null;
        m.values().removeIf(time -> System.currentTimeMillis() - time > REQUEST_MS);
        UUID from;
        if (fromName == null) from = m.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        else { Player f = Bukkit.getPlayerExact(fromName); from = f == null || !m.containsKey(f.getUniqueId()) ? null : f.getUniqueId(); }
        if (from != null) m.remove(from);
        return from;
    }

    private void accept(Player p, String fromName) {
        UUID from = take(p, fromName);
        if (from == null) { p.sendMessage(pre() + "§cBekleyen takas isteği yok (ya da süresi doldu)."); return; }
        Player f = Bukkit.getPlayer(from);
        if (f == null) { p.sendMessage(pre() + "§cOyuncu çıkış yapmış."); return; }
        if (trades.containsKey(p.getUniqueId()) || trades.containsKey(f.getUniqueId())) { p.sendMessage(pre() + "§cSen ya da o zaten takasta."); return; }
        if (!plugin.invs().sameMode(p, f)) { p.sendMessage(pre() + "§cFarklı moddasınız (Skyblock / SMP envanterleri ayrı)."); return; }
        Trade t = new Trade(f, p);
        trades.put(f.getUniqueId(), t);
        trades.put(p.getUniqueId(), t);
        render(t);
        f.openInventory(t.inv);
        p.openInventory(t.inv);
    }

    // ---------------- arayuz ----------------
    private static ItemStack icon(Material m, String name, String... lore) {
        ItemStack i = new ItemStack(m);
        ItemMeta meta = i.getItemMeta();
        meta.setDisplayName(PisanCore.color(name));
        List<String> l = new ArrayList<>();
        for (String s : lore) l.add(PisanCore.color(s));
        meta.setLore(l);
        meta.addItemFlags(ItemFlag.values());
        i.setItemMeta(meta);
        return i;
    }

    private void render(Trade t) {
        ItemStack sep = icon(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int r = 0; r < 6; r++) t.inv.setItem(r * 9 + 4, sep);
        t.inv.setItem(READY_A, readyIcon(t.a.getName(), t.readyA, t.coinsA));
        t.inv.setItem(READY_B, readyIcon(t.b.getName(), t.readyB, t.coinsB));
        t.inv.setItem(ADD100_A, icon(Material.GOLD_NUGGET, "&e+100 Coin", "&7Teklifine 100 Coin ekle"));
        t.inv.setItem(ADD100_B, icon(Material.GOLD_NUGGET, "&e+100 Coin", "&7Teklifine 100 Coin ekle"));
        t.inv.setItem(ADD1K_A, icon(Material.GOLD_INGOT, "&6+1.000 Coin", "&7Teklifine 1.000 Coin ekle"));
        t.inv.setItem(ADD1K_B, icon(Material.GOLD_INGOT, "&6+1.000 Coin", "&7Teklifine 1.000 Coin ekle"));
        t.inv.setItem(RESET_A, icon(Material.BARRIER, "&cCoin'i sıfırla", "&7Teklif: &6" + PisanCore.fmt(t.coinsA)));
        t.inv.setItem(RESET_B, icon(Material.BARRIER, "&cCoin'i sıfırla", "&7Teklif: &6" + PisanCore.fmt(t.coinsB)));
    }

    private ItemStack readyIcon(String name, boolean ready, double coins) {
        return icon(ready ? Material.LIME_DYE : Material.GRAY_DYE,
                (ready ? "&a&lONAYLADI" : "&7Onay bekleniyor") + " &8- &f" + name,
                "&7Coin teklifi: &6" + PisanCore.fmt(coins),
                "", ready ? "&eTıkla &7» Onayı geri al" : "&eTıkla &7» Takası onayla");
    }

    /** Herhangi bir degisiklikte onaylari sifirla. */
    private void changed(Trade t) {
        if (t.done) return;
        boolean had = t.readyA || t.readyB;
        t.readyA = t.readyB = false;
        if (t.countdown != null) { t.countdown.cancel(); t.countdown = null; }
        Bukkit.getScheduler().runTask(plugin, () -> { if (!t.done) render(t); });
        if (had) for (Player p : List.of(t.a, t.b)) p.sendMessage(pre() + "§eTeklif değişti, onaylar sıfırlandı.");
    }

    // ---------------- olaylar ----------------
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Trade t) || !(e.getWhoClicked() instanceof Player p)) return;
        if (t.done) { e.setCancelled(true); return; }
        int raw = e.getRawSlot();
        boolean top = raw < 54;
        InventoryAction act = e.getAction();

        if (act == InventoryAction.COLLECT_TO_CURSOR || act == InventoryAction.HOTBAR_SWAP
                || act == InventoryAction.HOTBAR_MOVE_AND_READD || e.getClick() == ClickType.NUMBER_KEY
                || e.getClick() == ClickType.SWAP_OFFHAND) { e.setCancelled(true); return; }

        if (!top) {
            // kendi envanteri: shift-tik ile teklife ekle, digerleri serbest
            if (act == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
                e.setCancelled(true);
                ItemStack it = e.getCurrentItem();
                if (it == null || it.getType().isAir()) return;
                for (int s = 0; s < 45; s++) {
                    if (own(t, p, s) && (t.inv.getItem(s) == null || t.inv.getItem(s).getType().isAir())) {
                        t.inv.setItem(s, it.clone());
                        e.setCurrentItem(null);
                        changed(t);
                        return;
                    }
                }
                p.sendMessage(pre() + "§cTeklif alanın dolu.");
            }
            return;
        }

        // ust pencere
        if (own(t, p, raw)) {
            if (act == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
                e.setCancelled(true);
                ItemStack it = t.inv.getItem(raw);
                if (it == null) return;
                t.inv.setItem(raw, null);
                for (ItemStack left : p.getInventory().addItem(it).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
                changed(t);
                return;
            }
            switch (act) {
                case PICKUP_ALL, PICKUP_HALF, PICKUP_ONE, PICKUP_SOME, PLACE_ALL, PLACE_ONE, PLACE_SOME, SWAP_WITH_CURSOR -> changed(t);
                default -> e.setCancelled(true);
            }
            return;
        }

        e.setCancelled(true);
        boolean isA = t.isA(p);
        if (raw == (isA ? READY_A : READY_B)) {
            if (isA) t.readyA = !t.readyA; else t.readyB = !t.readyB;
            render(t);
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
            if (t.readyA && t.readyB) startCountdown(t);
            else if (t.countdown != null) { t.countdown.cancel(); t.countdown = null; }
        } else if (raw == (isA ? ADD100_A : ADD100_B) || raw == (isA ? ADD1K_A : ADD1K_B)) {
            double add = (raw == ADD100_A || raw == ADD100_B) ? 100 : 1000;
            double cur = isA ? t.coinsA : t.coinsB;
            double bal = plugin.store().get(p.getUniqueId());
            if (cur + add > bal) { p.sendMessage(pre() + "§cYeterli Coin'in yok. §7(Bakiye: " + PisanCore.fmt(bal) + ")"); return; }
            if (isA) t.coinsA += add; else t.coinsB += add;
            changed(t);
        } else if (raw == (isA ? RESET_A : RESET_B)) {
            if (isA) t.coinsA = 0; else t.coinsB = 0;
            changed(t);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getInventory().getHolder() instanceof Trade t) || !(e.getWhoClicked() instanceof Player p)) return;
        boolean touchesTop = false;
        for (int s : e.getRawSlots()) {
            if (s < 54) {
                touchesTop = true;
                if (!own(t, p, s)) { e.setCancelled(true); return; }
            }
        }
        if (touchesTop) changed(t);
    }

    private void startCountdown(Trade t) {
        final int[] n = {3};
        t.countdown = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (t.done || !(t.readyA && t.readyB)) { if (t.countdown != null) t.countdown.cancel(); t.countdown = null; return; }
            if (n[0] <= 0) { t.countdown.cancel(); t.countdown = null; complete(t); return; }
            for (Player p : List.of(t.a, t.b)) {
                p.sendTitle("", "§aTakas " + n[0] + " saniye içinde tamamlanıyor...", 0, 25, 0);
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1.5f);
            }
            n[0]--;
        }, 0L, 20L);
    }

    private List<ItemStack> collect(Trade t, boolean sideA) {
        List<ItemStack> l = new ArrayList<>();
        for (int s = 0; s < 45; s++) {
            if (sideA ? areaA(s) : areaB(s)) {
                ItemStack it = t.inv.getItem(s);
                if (it != null && !it.getType().isAir()) l.add(it);
                t.inv.setItem(s, null);
            }
        }
        return l;
    }

    private void give(Player p, List<ItemStack> items) {
        for (ItemStack it : items)
            for (ItemStack left : p.getInventory().addItem(it).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
    }

    private void complete(Trade t) {
        EconomyStore st = plugin.store();
        if (st.get(t.a.getUniqueId()) < t.coinsA || st.get(t.b.getUniqueId()) < t.coinsB) {
            for (Player p : List.of(t.a, t.b)) p.sendMessage(pre() + "§cBir oyuncunun Coin'i yetmiyor, onaylar sıfırlandı.");
            changed(t);
            return;
        }
        t.done = true;
        end(t);
        if (t.coinsA > 0) { st.withdraw(t.a.getUniqueId(), t.coinsA); st.deposit(t.b.getUniqueId(), t.coinsA); }
        if (t.coinsB > 0) { st.withdraw(t.b.getUniqueId(), t.coinsB); st.deposit(t.a.getUniqueId(), t.coinsB); }
        List<ItemStack> fromA = collect(t, true), fromB = collect(t, false);
        t.a.closeInventory();
        t.b.closeInventory();
        give(t.b, fromA);
        give(t.a, fromB);
        for (Player p : List.of(t.a, t.b)) {
            p.sendMessage(pre() + "§aTakas tamamlandı! §7(" + (t.isA(p) ? fromB.size() : fromA.size()) + " eşya, "
                    + PisanCore.fmt(t.isA(p) ? t.coinsB : t.coinsA) + " Coin aldın)");
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
        }
        plugin.getLogger().info("Takas: " + t.a.getName() + " -> " + t.b.getName() + " " + fromA.size() + " esya/" + t.coinsA
                + " coin | " + t.b.getName() + " -> " + t.a.getName() + " " + fromB.size() + " esya/" + t.coinsB + " coin");
    }

    private void end(Trade t) {
        trades.remove(t.a.getUniqueId());
        trades.remove(t.b.getUniqueId());
        if (t.countdown != null) { t.countdown.cancel(); t.countdown = null; }
    }

    /** Iptal: herkes kendi esyasini geri alir. */
    private void cancel(Trade t, Player by) {
        if (t.done) return;
        t.done = true;
        end(t);
        List<ItemStack> fromA = collect(t, true), fromB = collect(t, false);
        give(t.a, fromA);
        give(t.b, fromB);
        for (Player p : List.of(t.a, t.b)) {
            p.sendMessage(pre() + "§cTakas iptal edildi" + (by != null ? " §7(" + by.getName() + " kapattı)" : "") + ". §7Eşyaların geri verildi.");
            if (!p.equals(by) && p.getOpenInventory().getTopInventory().getHolder() == t)
                Bukkit.getScheduler().runTask(plugin, () -> p.closeInventory());
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder() instanceof Trade t && e.getPlayer() instanceof Player p) cancel(t, p);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        requests.remove(e.getPlayer().getUniqueId());
        Trade t = trades.get(e.getPlayer().getUniqueId());
        if (t != null) cancel(t, e.getPlayer());
    }

    public void shutdown() {
        for (Trade t : new HashSet<>(trades.values())) cancel(t, null);
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length > 2 || (a.length == 2 && !(a[0].equalsIgnoreCase("kabul") || a[0].equalsIgnoreCase("red")))) return List.of();
        List<String> out = new ArrayList<>();
        if (a.length == 1) out.addAll(List.of("kabul", "red"));
        for (Player p : Bukkit.getOnlinePlayers()) if (!p.equals(s)) out.add(p.getName());
        String last = a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
