package com.pisan.core;

import org.bukkit.*;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.*;

/**
 * Sureli sunucu etkinlikleri (op):
 *  /event satis <carpan> <dk>     -> /sat ve market satislarinda ekstra para
 *  /event kazma <seviye> <dk>     -> herkese Haste (hizli kazma)
 *  /event anahtar <carpan> <dk>   -> anahtar dusme sansi carpani
 *  /event gorev <kaz|oldur|hasat> <adet> <dk>  -> tamamlayana ELINDEKI esya odul
 *  /event durdur <tur|hepsi>     /event  -> aktif etkinlikler
 * Bossbar + sohbet duyurulari; yeniden baslatmada devam eder (events.yml).
 */
public final class EventManager implements Listener, CommandExecutor, TabCompleter {

    enum Kind {
        SATIS("&6Satış Bonusu", BarColor.YELLOW), KAZMA("&bHızlı Kazma", BarColor.BLUE),
        ANAHTAR("&dAnahtar Şansı", BarColor.PINK), GOREV("&aGörev", BarColor.GREEN), HUNI("&eHızlı Huni", BarColor.YELLOW);
        final String title; final BarColor color;
        Kind(String t, BarColor c) { title = t; color = c; }
        static Kind of(String s) {
            return switch (s.toLowerCase(Locale.ROOT)) {
                case "satis", "satış", "sell" -> SATIS; case "kazma", "hiz", "haste" -> KAZMA;
                case "anahtar", "key" -> ANAHTAR; case "gorev", "görev", "quest" -> GOREV; case "huni", "hopper" -> HUNI; default -> null;
            };
        }
    }

    static final class Ev {
        final Kind kind; final double value; final long start, end;
        String task; int target; ItemStack reward;
        final Map<UUID, Integer> progress = new HashMap<>();
        final Set<UUID> done = new HashSet<>();
        transient BossBar bar; transient long lastAnnounceMin = -1;
        Ev(Kind k, double v, long s, long e) { kind = k; value = v; start = s; end = e; }
    }

    private static final Set<Material> MINE = EnumSet.noneOf(Material.class);
    private static final Set<Material> CROPS = Set.of(Material.WHEAT, Material.CARROTS, Material.POTATOES,
            Material.BEETROOTS, Material.NETHER_WART, Material.COCOA, Material.MELON, Material.PUMPKIN);
    static {
        for (Material m : Material.values()) {
            String n = m.name();
            if (!m.isLegacy() && (n.endsWith("_ORE") || n.equals("COBBLESTONE") || n.equals("STONE") || n.equals("BASALT")
                    || n.equals("ANCIENT_DEBRIS") || n.equals("DEEPSLATE") || n.equals("COBBLED_DEEPSLATE"))) MINE.add(m);
        }
    }

    private final PisanCore plugin;
    private final File file;
    private final EnumMap<Kind, Ev> active = new EnumMap<>(Kind.class);

    public EventManager(PisanCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "events.yml");
    }

    private String pre() { return PisanCore.color(plugin.getConfig().getString("messages.prefix", "")); }

    // ======================= disari acik =======================
    public double keyMultiplier() { Ev e = active.get(Kind.ANAHTAR); return e == null ? 1.0 : e.value; }
    public double sellMultiplier() { Ev e = active.get(Kind.SATIS); return e == null ? 1.0 : e.value; }

    // ======================= yasam dongusu =======================
    public void start() {
        load();
        Ev h = active.get(Kind.HUNI);
        if (h != null) { int[] c = hopperFor(h.value); writeHopper(c[0], c[1]); }
        hookShop();
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void shutdown() {
        save();
        for (Ev e : active.values()) if (e.bar != null) e.bar.removeAll();
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String k : y.getKeys(false)) {
            Kind kind = Kind.of(k);
            ConfigurationSection s = y.getConfigurationSection(k);
            if (kind == null || s == null || s.getLong("end") <= System.currentTimeMillis()) continue;
            Ev e = new Ev(kind, s.getDouble("value"), s.getLong("start"), s.getLong("end"));
            e.task = s.getString("task"); e.target = s.getInt("target"); e.reward = s.getItemStack("reward");
            ConfigurationSection pr = s.getConfigurationSection("progress");
            if (pr != null) for (String u : pr.getKeys(false)) e.progress.put(UUID.fromString(u), pr.getInt(u));
            for (String u : s.getStringList("done")) e.done.add(UUID.fromString(u));
            active.put(kind, e);
        }
        if (!active.isEmpty()) plugin.getLogger().info(active.size() + " etkinlik devam ediyor.");
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Ev e : active.values()) {
            String k = e.kind.name().toLowerCase(Locale.ROOT);
            y.set(k + ".value", e.value); y.set(k + ".start", e.start); y.set(k + ".end", e.end);
            if (e.kind == Kind.GOREV) {
                y.set(k + ".task", e.task); y.set(k + ".target", e.target); y.set(k + ".reward", e.reward);
                for (var p : e.progress.entrySet()) y.set(k + ".progress." + p.getKey(), p.getValue());
                List<String> d = new ArrayList<>(); for (UUID u : e.done) d.add(u.toString()); y.set(k + ".done", d);
            }
        }
        try { plugin.getDataFolder().mkdirs(); y.save(file); } catch (IOException ex) { plugin.getLogger().severe("events.yml: " + ex.getMessage()); }
    }

    // ======================= metinler =======================
    private static String num(double d) { return d == Math.floor(d) ? String.valueOf((long) d) : String.format(Locale.ROOT, "%.1f", d); }

    private String describe(Ev e) {
        return switch (e.kind) {
            case SATIS -> "Satışlarda &f" + num(e.value) + "x &7para";
            case KAZMA -> "Herkese &fHaste " + CrateManager.roman((int) e.value) + " &7(hızlı kazma)";
            case ANAHTAR -> "Anahtar düşme şansı &f" + num(e.value) + " kat";
            case GOREV -> taskText(e) + " &7» Ödül: &f" + rewardName(e.reward);
            case HUNI -> "Huniler &f" + num(e.value) + "x &7hızlı eşya taşıyor";
        };
    }

    private static String taskText(Ev e) {
        String what = switch (e.task) { case "oldur" -> "canavar öldür"; case "hasat" -> "olgun ekin hasat et"; default -> "taş/cevher kaz"; };
        return "&f" + e.target + " &7" + what;
    }

    private static String rewardName(ItemStack it) {
        if (it == null) return "?";
        var m = it.getItemMeta();
        String n = m != null && m.hasDisplayName() ? m.getDisplayName() : CrateManager.pretty(it.getType().name());
        return (it.getAmount() > 1 ? it.getAmount() + "x " : "") + n;
    }

    private static String left(long ms) {
        long s = Math.max(0, ms / 1000);
        if (s >= 86400) return (s / 86400) + " gün " + ((s % 86400) / 3600) + " sa";
        if (s >= 3600) return (s / 3600) + " sa " + ((s % 3600) / 60) + " dk";
        return s >= 60 ? (s / 60) + " dk " + (s % 60) + " sn" : s + " sn";
    }

    private void broadcast(String msg) { Bukkit.broadcastMessage(pre() + PisanCore.color(msg)); }

    // ======================= tick =======================
    private void tick() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Iterator<Ev> it = active.values().iterator(); it.hasNext(); ) {
            Ev e = it.next();
            if (now >= e.end) {
                if (e.bar != null) e.bar.removeAll();
                if (e.kind == Kind.HUNI) writeHopper(8, 1);
                broadcast("&7" + PisanCore.color(e.kind.title) + " &7etkinliği sona erdi."
                        + (e.kind == Kind.GOREV ? " &8(" + e.done.size() + " kişi tamamladı)" : ""));
                it.remove(); changed = true;
                continue;
            }
            if (e.kind == Kind.HUNI) continue; // uzun sureli: bossbar/duyuru yok, giriste bilgi verilir
            if (e.bar == null) e.bar = Bukkit.createBossBar("", e.kind.color, BarStyle.SEGMENTED_10);
            double frac = (double) (e.end - now) / Math.max(1, e.end - e.start);
            e.bar.setProgress(Math.max(0, Math.min(1, frac)));
            e.bar.setTitle(PisanCore.color(e.kind.title + " &8| &7" + describe(e) + " &8| &e" + left(e.end - now)));
            for (Player p : Bukkit.getOnlinePlayers()) if (!e.bar.getPlayers().contains(p)) e.bar.addPlayer(p);
            if (e.kind == Kind.KAZMA)
                for (Player p : Bukkit.getOnlinePlayers())
                    p.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, 60, Math.max(0, (int) e.value - 1), true, false, true));
            long minLeft = (e.end - now) / 60000;
            // tam dakika isaretinden hemen once (or. 9:59 kala) "Kalan 10 dk" de; 5 dakikada bir ve son 1 dakika
            if ((minLeft % 5 == 4 || minLeft == 0) && e.lastAnnounceMin != minLeft && (e.end - now) % 60000 > 58500 && now - e.start > 5000) {
                e.lastAnnounceMin = minLeft;
                broadcast(e.kind.title + " &7» " + describe(e) + " &8| &eKalan: " + (minLeft + 1) + " dk");
            }
        }
        if (changed) save();
    }

    // ======================= huni (spigot.yml) =======================
    /** kat -> {hopper-transfer, hopper-amount}. 16x = her tick 2 esya (normal: 8 tickte 1). */
    private static int[] hopperFor(double mult) {
        if (mult >= 16) return new int[]{1, 2};
        if (mult >= 8) return new int[]{1, 1};
        if (mult >= 4) return new int[]{2, 1};
        return new int[]{4, 1};
    }

    private boolean writeHopper(int transfer, int amount) {
        try {
            File f = new File(Bukkit.getWorldContainer(), "spigot.yml");
            String t = java.nio.file.Files.readString(f.toPath());
            String n = t.replaceFirst("(?m)^(\\s+hopper-transfer:)\\s*\\d+", "$1 " + transfer)
                        .replaceFirst("(?m)^(\\s+hopper-amount:)\\s*\\d+", "$1 " + amount);
            if (!n.equals(t)) java.nio.file.Files.writeString(f.toPath(), n);
            plugin.getLogger().info("Huni ayari: hopper-transfer=" + transfer + " hopper-amount=" + amount + " (yeniden baslatinca gecerli)");
            return true;
        } catch (Exception ex) {
            plugin.getLogger().warning("spigot.yml yazilamadi: " + ex.getMessage());
            return false;
        }
    }

    @EventHandler
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent e) {
        if (active.isEmpty()) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!e.getPlayer().isOnline()) return;
            long now = System.currentTimeMillis();
            for (Ev ev : active.values())
                e.getPlayer().sendMessage(pre() + PisanCore.color(ev.kind.title + " &7etkinliği aktif! " + describe(ev) + " &8| &e" + left(ev.end - now)));
        }, 60L);
    }

    // ======================= etkiler =======================
    /** EconomyShopGUI satis sonrasi: ekstra para (yansima ile, derleme bagimliligi yok). */
    private void hookShop() {
        try {
            Plugin es = Bukkit.getPluginManager().getPlugin("EconomyShopGUI");
            if (es == null) return;
            @SuppressWarnings("unchecked")
            Class<? extends Event> cls = (Class<? extends Event>) Class.forName("me.gypopo.economyshopgui.api.events.PostTransactionEvent", true, es.getClass().getClassLoader());
            Method type = cls.getMethod("getTransactionType"), result = cls.getMethod("getTransactionResult"),
                    price = cls.getMethod("getPrice"), player = cls.getMethod("getPlayer");
            Bukkit.getPluginManager().registerEvent(cls, this, EventPriority.MONITOR, (l, ev) -> {
                if (!cls.isInstance(ev)) return;
                double mult = sellMultiplier();
                if (mult <= 1.0) return;
                try {
                    String t = type.invoke(ev).toString().toUpperCase(Locale.ROOT);
                    String r = result.invoke(ev).toString().toUpperCase(Locale.ROOT);
                    if (!t.contains("SELL") || !r.startsWith("SUCCESS")) return;
                    double bonus = Math.floor((double) price.invoke(ev) * (mult - 1) * 100) / 100.0;
                    Player p = (Player) player.invoke(ev);
                    if (bonus <= 0 || p == null) return;
                    plugin.store().deposit(p.getUniqueId(), bonus);
                    p.sendMessage(pre() + "§6Etkinlik bonusu: §e+" + PisanCore.fmt(bonus) + " Coin §7(" + num(mult) + "x satış)");
                } catch (Throwable ignored) { }
            }, plugin);
            plugin.getLogger().info("Etkinlik: satis bonusu EconomyShopGUI'ye baglandi.");
        } catch (Throwable t) {
            plugin.getLogger().warning("Etkinlik: EconomyShopGUI satis olayina baglanilamadi: " + t);
        }
    }

    private void count(Player p, String task) {
        Ev e = active.get(Kind.GOREV);
        if (e == null || !task.equals(e.task) || e.done.contains(p.getUniqueId()) || p.getGameMode() != GameMode.SURVIVAL) return;
        int n = e.progress.merge(p.getUniqueId(), 1, Integer::sum);
        if (n >= e.target) {
            e.done.add(p.getUniqueId());
            e.progress.remove(p.getUniqueId());
            if (e.reward != null)
                for (ItemStack left : p.getInventory().addItem(e.reward.clone()).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
            p.sendTitle(PisanCore.color("&a&lGÖREV TAMAM!"), PisanCore.color("&7Ödül: &f" + rewardName(e.reward)), 5, 50, 10);
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            broadcast("&a" + p.getName() + " &7görevi tamamladı ve &f" + rewardName(e.reward) + " &7kazandı!");
            save();
        } else if (n % 10 == 0 || e.target - n <= 5) {
            p.sendActionBar(PisanCore.color("&aGörev: &f" + n + "&7/&f" + e.target + " &8(" + taskText(e) + "&8)"));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!active.containsKey(Kind.GOREV)) return;
        Block b = e.getBlock(); Material t = b.getType();
        if (MINE.contains(t)) {
            if (!e.getPlayer().getInventory().getItemInMainHand().getType().name().endsWith("_PICKAXE")) return;
            if (plugin.skills().isPlaced(b)) return; // elle konan blok sayilmaz
            count(e.getPlayer(), "kaz");
        } else if (CROPS.contains(t)) {
            if (b.getBlockData() instanceof Ageable a && a.getAge() < a.getMaximumAge()) return;
            if ((t == Material.MELON || t == Material.PUMPKIN) && plugin.skills().isPlaced(b)) return;
            count(e.getPlayer(), "hasat");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(EntityDeathEvent e) {
        if (!active.containsKey(Kind.GOREV) || !(e.getEntity() instanceof Enemy) || e.getEntity().getKiller() == null) return;
        try { if (e.getEntity().getEntitySpawnReason() == org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.SPAWNER) return; }
        catch (Throwable ignored) { }
        count(e.getEntity().getKiller(), "oldur");
    }

    // ======================= komut =======================
    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length == 0) { list(s); return true; }
        if (!s.hasPermission("pisancore.admin")) { list(s); return true; }
        String sub = a[0].toLowerCase(Locale.ROOT);
        if (sub.equals("durdur") || sub.equals("stop") || sub.equals("bitir")) {
            String which = a.length > 1 ? a[1] : "hepsi";
            List<Kind> ks = which.equalsIgnoreCase("hepsi") ? new ArrayList<>(active.keySet()) : Optional.ofNullable(Kind.of(which)).map(List::of).orElse(List.of());
            if (ks.isEmpty()) { s.sendMessage(pre() + "§cDurdurulacak etkinlik yok."); return true; }
            for (Kind k : ks) { Ev e = active.remove(k); if (e != null && e.bar != null) e.bar.removeAll();
                if (k == Kind.HUNI && e != null) { writeHopper(8, 1); s.sendMessage(pre() + "§7Huni hızı sonraki yeniden başlatmada normale döner."); } if (e != null) broadcast("&7" + PisanCore.color(k.title) + " &7etkinliği durduruldu."); }
            save();
            return true;
        }
        Kind k = Kind.of(sub);
        if (k == null) { help(s); return true; }
        try {
            Ev e;
            long now = System.currentTimeMillis();
            if (k == Kind.HUNI) {
                if (a.length < 3) { help(s); return true; }
                double v = Double.parseDouble(a[1]), days = Double.parseDouble(a[2].replace(",", "."));
                if (v <= 1 || days <= 0) { help(s); return true; }
                int[] c = hopperFor(v);
                v = (8.0 / c[0]) * c[1];
                e = new Ev(k, v, now, now + (long) (days * 86400000L));
                boolean ok = writeHopper(c[0], c[1]);
                s.sendMessage(pre() + (ok ? "§aspigot.yml güncellendi: hopper-transfer=" + c[0] + ", hopper-amount=" + c[1]
                        + ". §eSunucu yeniden başlayınca aktif olur." : "§cspigot.yml yazılamadı!"));
            } else if (k == Kind.GOREV) {
                if (a.length < 4) { help(s); return true; }
                String task = switch (a[1].toLowerCase(Locale.ROOT)) { case "kaz", "maden" -> "kaz"; case "oldur", "öldür", "kill" -> "oldur"; case "hasat", "tarim" -> "hasat"; default -> null; };
                int target = Integer.parseInt(a[2]); double min = Double.parseDouble(a[3]);
                if (task == null || target < 1 || min <= 0) { help(s); return true; }
                if (!(s instanceof Player p) || p.getInventory().getItemInMainHand().getType().isAir()) {
                    s.sendMessage(pre() + "§cÖdül olarak verilecek eşyayı eline al ve komutu oyunda yaz."); return true;
                }
                e = new Ev(k, 0, now, now + (long) (min * 60000));
                e.task = task; e.target = target; e.reward = p.getInventory().getItemInMainHand().clone();
            } else {
                if (a.length < 3) { help(s); return true; }
                double v = Double.parseDouble(a[1].replace(",", ".")), min = Double.parseDouble(a[2].replace(",", "."));
                if (v <= 0 || min <= 0) { help(s); return true; }
                if (k == Kind.KAZMA) v = Math.max(1, Math.min(5, Math.round(v)));
                e = new Ev(k, v, now, now + (long) (min * 60000));
            }
            Ev old = active.put(k, e);
            if (old != null && old.bar != null) old.bar.removeAll();
            save();
            Bukkit.broadcastMessage("");
            broadcast("&6&l✦ ETKİNLİK BAŞLADI! ✦");
            broadcast(e.kind.title + " &7» " + describe(e) + " &8| &e" + left(e.end - now));
            Bukkit.broadcastMessage("");
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.sendTitle(PisanCore.color(e.kind.title), PisanCore.color("&7" + describe(e)), 10, 60, 15);
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
            }
        } catch (NumberFormatException ex) { help(s); }
        return true;
    }

    private void list(CommandSender s) {
        if (active.isEmpty()) { s.sendMessage(pre() + "§7Şu an aktif etkinlik yok."); return; }
        s.sendMessage(pre() + "§6Aktif etkinlikler:");
        long now = System.currentTimeMillis();
        for (Ev e : active.values()) {
            String extra = "";
            if (e.kind == Kind.GOREV && s instanceof Player p)
                extra = e.done.contains(p.getUniqueId()) ? " &a(tamamladın)" : " &8(senin: " + e.progress.getOrDefault(p.getUniqueId(), 0) + "/" + e.target + ")";
            s.sendMessage(PisanCore.color(" " + e.kind.title + " &7» " + describe(e) + " &8| &e" + left(e.end - now) + extra));
        }
    }

    private void help(CommandSender s) {
        s.sendMessage(pre() + "§6Etkinlik komutları:");
        s.sendMessage(" §e/event satis <çarpan> <dakika> §7- ör: /event satis 2 30");
        s.sendMessage(" §e/event kazma <seviye 1-5> <dakika> §7- herkese Haste");
        s.sendMessage(" §e/event anahtar <çarpan> <dakika> §7- ör: /event anahtar 3 15");
        s.sendMessage(" §e/event gorev <kaz|oldur|hasat> <adet> <dakika> §7- ödül: elindeki eşya");
        s.sendMessage(" §e/event huni <kat 2-16> <gün> §7- ör: /event huni 16 30 (yeniden başlatınca aktif)");
        s.sendMessage(" §e/event durdur <tür|hepsi> §7- etkinliği bitir");
        s.sendMessage(" §e/event §7- aktif etkinlikler");
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission("pisancore.admin")) return List.of();
        List<String> out = new ArrayList<>();
        if (a.length == 1) out.addAll(List.of("satis", "kazma", "anahtar", "gorev", "huni", "durdur"));
        else if (a.length == 2 && a[0].equalsIgnoreCase("gorev")) out.addAll(List.of("kaz", "oldur", "hasat"));
        else if (a.length == 2 && a[0].equalsIgnoreCase("durdur")) out.addAll(List.of("hepsi", "satis", "kazma", "anahtar", "gorev", "huni"));
        else if (a.length == 2 && a[0].equalsIgnoreCase("huni")) out.addAll(List.of("2", "4", "8", "16"));
        else if (a.length == 2) out.addAll(List.of("2", "3"));
        else if (a.length == 3 && a[0].equalsIgnoreCase("gorev")) out.addAll(List.of("100", "500", "1000"));
        else if (a.length == 3 || a.length == 4) out.addAll(List.of("15", "30", "60"));
        String last = a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.startsWith(last));
        return out;
    }
}
