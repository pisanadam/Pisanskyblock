package com.pisan.core;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Arkadas sistemi: /arkadas ekle|kabul|red|sil|git|liste
 * Menude: sol tik = arkadasin adasina git, sag tik = arkadasin SENIN adandaki yetkisini degistir
 * (Ziyaretci -> Guvenilir -> Ortak), shift+sag tik = arkadasliktan cikar.
 * Rutbelerin neler yapabilecegi BentoBox'un /is settings menusunden ayarlanir.
 */
public final class FriendManager implements Listener, CommandExecutor, TabCompleter {

    private static final long REQUEST_MS = 120_000;
    private static final int MAX_FRIENDS = 45;

    private final PisanCore plugin;
    private final File file;
    private final Map<UUID, Set<UUID>> friends = new HashMap<>();
    private final Map<UUID, Map<UUID, Long>> requests = new HashMap<>(); // hedef -> gonderen
    private boolean dirty;

    private final class Menu implements InventoryHolder {
        Inventory inv; final Map<Integer, UUID> slots = new HashMap<>();
        @Override public Inventory getInventory() { return inv; }
    }

    public FriendManager(PisanCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "friends.yml");
    }

    private String pre() { return PisanCore.color(plugin.getConfig().getString("messages.prefix", "")); }

    public void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String k : y.getKeys(false)) {
            try {
                Set<UUID> s = new LinkedHashSet<>();
                for (String f : y.getStringList(k)) s.add(UUID.fromString(f));
                friends.put(UUID.fromString(k), s);
            } catch (IllegalArgumentException ignored) { }
        }
    }

    public void save(boolean force) {
        if (!dirty && !force) return;
        YamlConfiguration y = new YamlConfiguration();
        for (var e : friends.entrySet()) {
            List<String> l = new ArrayList<>();
            for (UUID u : e.getValue()) l.add(u.toString());
            if (!l.isEmpty()) y.set(e.getKey().toString(), l);
        }
        try { plugin.getDataFolder().mkdirs(); y.save(file); dirty = false; }
        catch (IOException ex) { plugin.getLogger().severe("friends.yml kaydedilemedi: " + ex.getMessage()); }
    }

    private Set<UUID> of(UUID id) { return friends.computeIfAbsent(id, k -> new LinkedHashSet<>()); }

    public boolean areFriends(UUID a, UUID b) { return of(a).contains(b); }

    private static String name(UUID id) {
        OfflinePlayer o = Bukkit.getOfflinePlayer(id);
        return o.getName() != null ? o.getName() : id.toString().substring(0, 8);
    }

    static String rankName(int r) {
        if (r >= IslandHook.MEMBER) return "&6Ekip Üyesi";
        if (r >= IslandHook.TRUSTED) return "&aGüvenilir";
        if (r >= IslandHook.COOP) return "&bOrtak";
        if (r < 0) return "&7(Adan yok)";
        return "&7Ziyaretçi";
    }

    // ======================= komutlar =======================
    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!(s instanceof Player p)) { s.sendMessage("Sadece oyuncular."); return true; }
        if (a.length == 0) { openMenu(p); return true; }
        String sub = a[0].toLowerCase(Locale.ROOT);
        String arg = a.length > 1 ? a[1] : null;
        switch (sub) {
            case "ekle", "add" -> { if (arg == null) help(p); else request(p, arg); }
            case "kabul", "accept" -> accept(p, arg);
            case "red", "deny" -> deny(p, arg);
            case "sil", "cikar", "remove" -> { if (arg == null) help(p); else remove(p, arg); }
            case "git", "tp", "ziyaret" -> { if (arg == null) help(p); else visit(p, arg); }
            case "liste", "list" -> list(p);
            case "izinler", "ayarlar" -> p.performCommand("is settings");
            default -> help(p);
        }
        return true;
    }

    private void help(Player p) {
        p.sendMessage(pre() + "§6Arkadaş komutları:");
        p.sendMessage(" §e/arkadas §7- Arkadaş menüsü");
        p.sendMessage(" §e/arkadas ekle <oyuncu> §7- Arkadaşlık isteği gönder");
        p.sendMessage(" §e/arkadas kabul|red [oyuncu] §7- İsteği kabul/red et");
        p.sendMessage(" §e/arkadas git <oyuncu> §7- Arkadaşının adasına git");
        p.sendMessage(" §e/arkadas sil <oyuncu> §7- Arkadaşlıktan çıkar");
        p.sendMessage(" §e/arkadas izinler §7- Adandaki rütbelerin izinlerini ayarla");
    }

    @SuppressWarnings("deprecation")
    private UUID findKnown(Player p, String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online.getUniqueId();
        for (UUID f : of(p.getUniqueId())) if (name.equalsIgnoreCase(name(f))) return f;
        return null;
    }

    private void request(Player p, String name) {
        Player t = Bukkit.getPlayerExact(name);
        if (t == null) { p.sendMessage(pre() + "§cOyuncu çevrimiçi değil."); return; }
        if (t.equals(p)) { p.sendMessage(pre() + "§cKendini ekleyemezsin."); return; }
        if (areFriends(p.getUniqueId(), t.getUniqueId())) { p.sendMessage(pre() + "§eZaten arkadaşsınız."); return; }
        if (of(p.getUniqueId()).size() >= MAX_FRIENDS) { p.sendMessage(pre() + "§cEn fazla " + MAX_FRIENDS + " arkadaşın olabilir."); return; }
        // karsi taraf da bize istek gonderdiyse direkt kabul
        Map<UUID, Long> mine = requests.get(p.getUniqueId());
        if (mine != null && mine.remove(t.getUniqueId()) != null) { makeFriends(p.getUniqueId(), t.getUniqueId()); return; }
        requests.computeIfAbsent(t.getUniqueId(), k -> new HashMap<>()).put(p.getUniqueId(), System.currentTimeMillis());
        p.sendMessage(pre() + "§f" + t.getName() + " §7oyuncusuna arkadaşlık isteği gönderildi.");
        t.sendMessage(pre() + "§6" + p.getName() + " §esana arkadaşlık isteği gönderdi!");
        t.sendMessage(" §7Kabul: §a/arkadas kabul " + p.getName() + " §7| Red: §c/arkadas red " + p.getName());
        t.playSound(t.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 2f);
    }

    private UUID takeRequest(Player p, String from) {
        Map<UUID, Long> m = requests.get(p.getUniqueId());
        if (m == null) return null;
        m.values().removeIf(t -> System.currentTimeMillis() - t > REQUEST_MS);
        UUID id;
        if (from == null) id = m.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        else { Player f = Bukkit.getPlayerExact(from); id = f != null && m.containsKey(f.getUniqueId()) ? f.getUniqueId() : null; }
        if (id != null) m.remove(id);
        return id;
    }

    private void accept(Player p, String from) {
        UUID id = takeRequest(p, from);
        if (id == null) { p.sendMessage(pre() + "§cBekleyen arkadaşlık isteği yok."); return; }
        if (of(p.getUniqueId()).size() >= MAX_FRIENDS) { p.sendMessage(pre() + "§cArkadaş listen dolu."); return; }
        makeFriends(p.getUniqueId(), id);
    }

    private void makeFriends(UUID a, UUID b) {
        of(a).add(b); of(b).add(a); dirty = true;
        for (UUID x : List.of(a, b)) {
            Player pl = Bukkit.getPlayer(x);
            if (pl != null) {
                pl.sendMessage(pre() + "§a" + name(x.equals(a) ? b : a) + " ile artık arkadaşsınız! §7(/arkadas)");
                pl.playSound(pl.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.6f);
            }
        }
    }

    private void deny(Player p, String from) {
        UUID id = takeRequest(p, from);
        if (id == null) { p.sendMessage(pre() + "§cBekleyen arkadaşlık isteği yok."); return; }
        p.sendMessage(pre() + "§7İstek reddedildi.");
        Player f = Bukkit.getPlayer(id);
        if (f != null) f.sendMessage(pre() + "§c" + p.getName() + " arkadaşlık isteğini reddetti.");
    }

    private void remove(Player p, String name) {
        UUID id = findKnown(p, name);
        if (id == null || !areFriends(p.getUniqueId(), id)) { p.sendMessage(pre() + "§cBu oyuncu arkadaşın değil."); return; }
        removeFriend(p, id);
    }

    private void removeFriend(Player p, UUID id) {
        of(p.getUniqueId()).remove(id); of(id).remove(p.getUniqueId()); dirty = true;
        // birbirlerinin adasinda verdikleri ozel yetkileri geri al
        for (UUID[] pair : new UUID[][]{{p.getUniqueId(), id}, {id, p.getUniqueId()}}) {
            int r = IslandHook.rank(pair[0], pair[1]);
            if (r > IslandHook.VISITOR && r < IslandHook.MEMBER) IslandHook.setRank(pair[0], pair[1], IslandHook.VISITOR);
        }
        p.sendMessage(pre() + "§7" + name(id) + " arkadaşlıktan çıkarıldı.");
        Player o = Bukkit.getPlayer(id);
        if (o != null) o.sendMessage(pre() + "§7" + p.getName() + " seni arkadaşlıktan çıkardı.");
    }

    private void visit(Player p, String name) {
        UUID id = findKnown(p, name);
        if (id == null || !areFriends(p.getUniqueId(), id)) { p.sendMessage(pre() + "§cSadece arkadaşlarının adasına gidebilirsin. §7(/arkadas ekle)"); return; }
        visit(p, id);
    }

    private void visit(Player p, UUID id) {
        if (IslandHook.banned(id, p.getUniqueId())) { p.sendMessage(pre() + "§c" + name(id) + " seni adasından yasaklamış."); return; }
        Location home = IslandHook.home(id);
        if (home == null) { p.sendMessage(pre() + "§c" + name(id) + " oyuncusunun adası yok."); return; }
        p.teleportAsync(home).thenAccept(ok -> {
            if (ok) p.sendMessage(pre() + "§a" + name(id) + " §7oyuncusunun adasına ışınlandın.");
            else p.sendMessage(pre() + "§cIşınlanılamadı.");
        });
    }

    private void list(Player p) {
        Set<UUID> fs = of(p.getUniqueId());
        if (fs.isEmpty()) { p.sendMessage(pre() + "§7Henüz arkadaşın yok. §e/arkadas ekle <oyuncu>"); return; }
        p.sendMessage(pre() + "§6Arkadaşların (" + fs.size() + "):");
        for (UUID f : fs) {
            boolean on = Bukkit.getPlayer(f) != null;
            p.sendMessage(PisanCore.color(" " + (on ? "&a● " : "&7● ") + "&f" + name(f) + " &8- &7senin adanda: " + rankName(IslandHook.rank(p.getUniqueId(), f))));
        }
    }

    // ======================= menu =======================
    public void openMenu(Player p) {
        Menu h = new Menu();
        h.inv = Bukkit.createInventory(h, 54, PisanCore.color("&8Arkadaşlar"));
        ItemStack pane = icon(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 45; i < 54; i++) h.inv.setItem(i, pane);
        int slot = 0;
        for (UUID f : of(p.getUniqueId())) {
            if (slot >= 45) break;
            OfflinePlayer op = Bukkit.getOfflinePlayer(f);
            boolean on = op.isOnline();
            int myRank = IslandHook.rank(p.getUniqueId(), f);
            int theirRank = IslandHook.rank(f, p.getUniqueId());
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta sm = (SkullMeta) head.getItemMeta();
            sm.setOwningPlayer(op);
            sm.setDisplayName(PisanCore.color((on ? "&a● " : "&7● ") + "&f" + name(f)));
            sm.setLore(List.of(
                    PisanCore.color(on ? "&aÇevrimiçi" : "&7Çevrimdışı"),
                    "",
                    PisanCore.color("&7Senin adanda yetkisi: " + rankName(myRank)),
                    PisanCore.color("&7Onun adasında senin yetkin: " + rankName(theirRank)),
                    "",
                    PisanCore.color("&eSol tık &7» Adasına git"),
                    PisanCore.color("&eSağ tık &7» Adandaki yetkisini değiştir"),
                    PisanCore.color("&cShift + Sağ tık &7» Arkadaşlıktan çıkar")));
            head.setItemMeta(sm);
            h.inv.setItem(slot, head);
            h.slots.put(slot, f);
            slot++;
        }
        h.inv.setItem(48, icon(Material.WRITABLE_BOOK, "&eArkadaş ekle", List.of("&7Komut: &f/arkadas ekle <oyuncu>")));
        h.inv.setItem(49, icon(Material.COMPARATOR, "&6Ada izin ayarları", List.of(
                "&7Ziyaretçi, Ortak ve Güvenilir",
                "&7rütbelerinin adanda neler",
                "&7yapabileceğini ayarla.",
                "", "&eTıkla &7» Ayarları aç")));
        h.inv.setItem(50, icon(Material.BOOK, "&bRütbeler", List.of(
                "&7Ziyaretçi &8- &7sadece gezebilir",
                "&bOrtak &8- &7geçici yardımcı",
                "&aGüvenilir &8- &7kalıcı yardımcı",
                "&7(İzinleri &6Ada izin ayarları&7'ndan değiştir)")));
        p.openInventory(h.inv);
    }

    private static ItemStack icon(Material m, String name, List<String> lore) {
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

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getView().getTopInventory()) return;
        int slot = e.getRawSlot();
        if (slot == 49) { p.closeInventory(); p.performCommand("is settings"); return; }
        if (slot == 48) { p.closeInventory(); p.sendMessage(pre() + "§eArkadaş eklemek için: §f/arkadas ekle <oyuncu>"); return; }
        UUID f = h.slots.get(slot);
        if (f == null) return;
        if (e.getClick() == ClickType.SHIFT_RIGHT) { removeFriend(p, f); openMenu(p); return; }
        if (e.isRightClick()) {
            int r = IslandHook.rank(p.getUniqueId(), f);
            if (r < 0) { p.sendMessage(pre() + "§cÖnce bir adan olmalı. §7(/is)"); return; }
            if (r >= IslandHook.MEMBER) { p.sendMessage(pre() + "§eBu oyuncu ada ekibinde, yetkisi ekip ayarlarından yönetilir."); return; }
            int next = r >= IslandHook.TRUSTED ? IslandHook.VISITOR : r >= IslandHook.COOP ? IslandHook.TRUSTED : IslandHook.COOP;
            if (IslandHook.setRank(p.getUniqueId(), f, next)) {
                p.sendMessage(pre() + "§f" + name(f) + " §7artık adanda: " + PisanCore.color(rankName(next)));
                Player o = Bukkit.getPlayer(f);
                if (o != null) o.sendMessage(pre() + "§f" + p.getName() + " §7seni adasında " + PisanCore.color(rankName(next)) + " §7yaptı.");
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1.2f);
            } else p.sendMessage(pre() + "§cYetki değiştirilemedi.");
            openMenu(p);
            return;
        }
        p.closeInventory();
        visit(p, f);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) { if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true); }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        for (UUID f : of(p.getUniqueId())) {
            Player o = Bukkit.getPlayer(f);
            if (o != null && !o.equals(p)) o.sendMessage(pre() + "§a" + p.getName() + " §7oyuna girdi.");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) out.addAll(List.of("ekle", "kabul", "red", "sil", "git", "liste", "izinler"));
        else if (a.length == 2 && s instanceof Player p) {
            String sub = a[0].toLowerCase(Locale.ROOT);
            if (sub.equals("sil") || sub.equals("git")) for (UUID f : of(p.getUniqueId())) out.add(name(f));
            else for (Player o : Bukkit.getOnlinePlayers()) if (!o.equals(s)) out.add(o.getName());
        }
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
