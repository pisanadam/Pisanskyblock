package com.pisan.core;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

/** /kasa menusu: kasalari listeler, sol tik ac, sag tik icerik. */
public final class CrateMenu implements Listener, CommandExecutor, TabCompleter {

    private static final class Holder implements InventoryHolder {
        final String crate; // null = ana menu
        final Map<Integer, String> slots = new HashMap<>();
        Inventory inv;
        Holder(String crate) { this.crate = crate; }
        @Override public Inventory getInventory() { return inv; }
    }

    private final PisanCore plugin;
    private final CrateManager cm;

    public CrateMenu(PisanCore plugin, CrateManager cm) { this.plugin = plugin; this.cm = cm; }

    public void openMain(Player p) {
        Holder h = new Holder(null);
        Inventory inv = Bukkit.createInventory(h, 36, PisanCore.color("&8Kasalar"));
        h.inv = inv;
        ItemStack pane = named(new ItemStack(Material.BLACK_STAINED_GLASS_PANE), " ", List.of());
        for (int i = 0; i < 36; i++) inv.setItem(i, pane);
        int[] defSlots = {11, 12, 13, 14, 15, 20, 21, 22, 23, 24};
        int i = 0;
        for (CrateManager.Crate c : cm.crates()) {
            int slot = c.slot() >= 0 ? c.slot() : (i < defSlots.length ? defSlots[i] : i);
            i++;
            int k = cm.count(p, c.id());
            List<String> lore = new ArrayList<>();
            for (String l : c.lore()) lore.add(PisanCore.color(l));
            lore.add("");
            lore.add(PisanCore.color("&7Anahtarın: " + (k > 0 ? "&a" : "&c") + k));
            lore.add("");
            lore.add(PisanCore.color("&eSol tık &7» Aç"));
            lore.add(PisanCore.color("&eSağ tık &7» İçeriğe bak"));
            ItemStack it = named(new ItemStack(c.icon(), Math.max(1, Math.min(64, k))), c.name(), lore);
            inv.setItem(slot, it);
            h.slots.put(slot, c.id());
        }
        p.openInventory(inv);
    }

    public void openPreview(Player p, CrateManager.Crate c) {
        Holder h = new Holder(c.id());
        int size = Math.min(54, ((c.rewards().size() + 9) / 9 + 1) * 9);
        Inventory inv = Bukkit.createInventory(h, size, PisanCore.color("&8İçerik: ") + PisanCore.color(c.name()));
        h.inv = inv;
        int i = 0;
        for (CrateManager.Reward r : c.rewards()) { if (i >= size - 9) break; inv.setItem(i++, cm.previewIcon(c, r)); }
        inv.setItem(size - 5, named(new ItemStack(Material.ARROW), "&cGeri", List.of()));
        h.slots.put(size - 5, "__back");
        p.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getView().getTopInventory()) return;
        String id = h.slots.get(e.getRawSlot());
        if (id == null) return;
        if (id.equals("__back")) { openMain(p); return; }
        CrateManager.Crate c = cm.crate(id);
        if (c == null) return;
        if (e.isRightClick()) openPreview(p, c);
        else if (cm.open(p, c)) openMain(p);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) { if (e.getInventory().getHolder() instanceof Holder) e.setCancelled(true); }

    private static ItemStack named(ItemStack i, String name, List<String> lore) {
        ItemMeta m = i.getItemMeta();
        m.setDisplayName(PisanCore.color(name));
        m.setLore(lore);
        m.addItemFlags(ItemFlag.values());
        i.setItemMeta(m);
        return i;
    }

    // /kasa [ac <kasa> | ver <oyuncu> <kasa> [adet] | anahtar]
    @SuppressWarnings("deprecation")
    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String pre = PisanCore.color(plugin.getConfig().getString("messages.prefix", ""));
        if (a.length >= 1 && (a[0].equalsIgnoreCase("ver") || a[0].equalsIgnoreCase("give"))) {
            if (!s.hasPermission("pisancore.admin")) { s.sendMessage(pre + "§cYetkin yok."); return true; }
            if (a.length < 3) { s.sendMessage(pre + "/kasa ver <oyuncu> <kasa> [adet]"); return true; }
            CrateManager.Crate c = cm.crate(a[2]);
            if (c == null) { s.sendMessage(pre + "§cKasa yok: " + a[2]); return true; }
            int amt = 1;
            try { if (a.length >= 4) amt = Integer.parseInt(a[3]); } catch (NumberFormatException ex) { s.sendMessage(pre + "§cGeçersiz adet."); return true; }
            OfflinePlayer t = Bukkit.getPlayerExact(a[1]);
            if (t == null) t = Bukkit.getOfflinePlayer(a[1]);
            cm.addKeys(t.getUniqueId(), c.id(), amt);
            s.sendMessage(pre + "§f" + t.getName() + " §7oyuncusuna §f" + amt + "x " + PisanCore.color(cm.keyName(c)) + " §7verildi"
                    + (t.isOnline() ? "." : " §8(girişte teslim edilecek)"));
            return true;
        }
        if (!(s instanceof Player p)) { s.sendMessage("/kasa ver <oyuncu> <kasa> [adet]"); return true; }
        if (a.length >= 2 && (a[0].equalsIgnoreCase("ac") || a[0].equalsIgnoreCase("aç"))) {
            CrateManager.Crate c = cm.crate(a[1]);
            if (c == null) { s.sendMessage(pre + "§cKasa yok."); return true; }
            cm.open(p, c);
            return true;
        }
        if (a.length >= 1 && a[0].equalsIgnoreCase("anahtar")) {
            s.sendMessage(pre + "§6Anahtarların:");
            for (CrateManager.Crate c : cm.crates())
                s.sendMessage(" " + PisanCore.color(c.name()) + " §8» §f" + cm.count(p, c.id()));
            return true;
        }
        openMain(p);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) { out.addAll(List.of("ac", "anahtar")); if (s.hasPermission("pisancore.admin")) out.add("ver"); }
        else if (a.length == 2 && a[0].equalsIgnoreCase("ac")) for (CrateManager.Crate c : cm.crates()) out.add(c.id());
        else if (a.length == 2 && a[0].equalsIgnoreCase("ver")) for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        else if (a.length == 3 && a[0].equalsIgnoreCase("ver")) for (CrateManager.Crate c : cm.crates()) out.add(c.id());
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
