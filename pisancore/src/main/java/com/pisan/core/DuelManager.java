package com.pisan.core;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.inventory.ItemStack;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.entity.Projectile;

import java.util.*;

/**
 * /duel <oyuncu>  -> istek gonderir
 * /duel kabul [oyuncu] / /duel red [oyuncu] / /duel pes
 * Oyuncular uzerlerindeki esyalarla savasir, kazanan kaybedenin TUM esyalarini alir.
 * Arena ayri bos bir dunyada otomatik insa edilir; ayni anda birden fazla duel olabilir.
 */
public final class DuelManager implements Listener, CommandExecutor, TabCompleter {

    static final String WORLD = "pisan_duel";
    private static final int SPACING = 300, HALF = 15, FLOOR_Y = 100, WALL = 8;
    private static final long REQUEST_MS = 60_000, MAX_DUEL_TICKS = 20L * 60 * 5;

    private record Request(UUID from, long time) { }

    private final class Duel {
        final Player a, b;
        final Location backA, backB;
        final int slot;
        boolean started, ended;
        int timeoutTask = -1;
        Duel(Player a, Player b, int slot) { this.a = a; this.b = b; this.slot = slot; backA = a.getLocation(); backB = b.getLocation(); }
        Player other(Player p) { return p.equals(a) ? b : a; }
        Location back(Player p) { return p.equals(a) ? backA : backB; }
    }

    private final PisanCore plugin;
    private final Map<UUID, Map<UUID, Request>> requests = new HashMap<>(); // hedef -> (gonderen -> istek)
    private final Map<UUID, Duel> duels = new HashMap<>();
    private final Set<Integer> usedSlots = new HashSet<>();
    private final Map<UUID, Location> pendingRespawn = new HashMap<>();
    private World world;

    public DuelManager(PisanCore plugin) { this.plugin = plugin; }

    private String pre() { return PisanCore.color(plugin.getConfig().getString("messages.prefix", "")); }

    // ---------------- dunya / arena ----------------
    private World world() {
        if (world != null) return world;
        world = Bukkit.getWorld(WORLD);
        if (world == null) {
            world = new WorldCreator(WORLD).generator(new ChunkGenerator() { }).environment(World.Environment.NORMAL)
                    .generateStructures(false).createWorld();
        }
        world.setPVP(true);
        world.setDifficulty(Difficulty.PEACEFUL);
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        world.setGameRule(GameRule.KEEP_INVENTORY, false);
        world.setTime(6000);
        return world;
    }

    private Location center(int slot) { return new Location(world(), slot * SPACING + 0.5, FLOOR_Y + 1, 0.5); }

    private void buildArena(int slot) {
        World w = world();
        int cx = slot * SPACING;
        for (int x = -HALF - 1; x <= HALF + 1; x++)
            for (int z = -HALF - 1; z <= HALF + 1; z++) {
                boolean edge = Math.abs(x) == HALF + 1 || Math.abs(z) == HALF + 1;
                w.getBlockAt(cx + x, FLOOR_Y, z).setType(edge ? Material.BARRIER : ((x + z) % 2 == 0 ? Material.SMOOTH_STONE : Material.POLISHED_ANDESITE), false);
                for (int y = FLOOR_Y + 1; y <= FLOOR_Y + WALL; y++)
                    w.getBlockAt(cx + x, y, z).setType(edge ? Material.BARRIER : Material.AIR, false);
                w.getBlockAt(cx + x, FLOOR_Y + WALL + 1, z).setType(Material.BARRIER, false);
            }
    }

    private int freeSlot() { int i = 0; while (usedSlots.contains(i)) i++; usedSlots.add(i); return i; }

    // ---------------- komut ----------------
    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!(s instanceof Player p)) { s.sendMessage("Sadece oyuncular."); return true; }
        if (a.length == 0) {
            p.sendMessage(pre() + "§6Duel komutları:");
            p.sendMessage(" §e/duel <oyuncu> §7- Duel isteği gönder");
            p.sendMessage(" §e/duel kabul [oyuncu] §7- İsteği kabul et");
            p.sendMessage(" §e/duel red [oyuncu] §7- İsteği reddet");
            p.sendMessage(" §e/duel pes §7- Teslim ol (eşyaların rakibe gider)");
            p.sendMessage(" §c⚠ Kazanan, kaybedenin TÜM eşyalarını alır!");
            return true;
        }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "kabul", "accept" -> accept(p, a.length > 1 ? a[1] : null);
            case "red", "deny" -> deny(p, a.length > 1 ? a[1] : null);
            case "pes", "forfeit" -> {
                Duel d = duels.get(p.getUniqueId());
                if (d == null) p.sendMessage(pre() + "§cDuelde değilsin.");
                else finish(d, d.other(p), p, "teslim oldu");
            }
            default -> request(p, a[0]);
        }
        return true;
    }

    private void request(Player p, String name) {
        Player t = Bukkit.getPlayerExact(name);
        if (t == null) { p.sendMessage(pre() + "§cOyuncu çevrimiçi değil."); return; }
        if (t.equals(p)) { p.sendMessage(pre() + "§cKendinle duel yapamazsın."); return; }
        if (duels.containsKey(p.getUniqueId()) || duels.containsKey(t.getUniqueId())) { p.sendMessage(pre() + "§cSen ya da rakip zaten duelde."); return; }
        if (!plugin.invs().sameMode(p, t)) { p.sendMessage(pre() + "§cFarklı moddasınız (Skyblock / SMP envanterleri ayrı)."); return; }
        requests.computeIfAbsent(t.getUniqueId(), k -> new HashMap<>()).put(p.getUniqueId(), new Request(p.getUniqueId(), System.currentTimeMillis()));
        p.sendMessage(pre() + "§f" + t.getName() + " §7oyuncusuna duel isteği gönderildi. §8(60 sn)");
        t.sendMessage(pre() + "§6" + p.getName() + " §esana duel isteği gönderdi!");
        t.sendMessage(" §7Kabul: §a/duel kabul " + p.getName() + " §7| Red: §c/duel red " + p.getName());
        t.sendMessage(" §c⚠ Eşyalarınla savaşırsın, kaybeden tüm eşyalarını kaybeder!");
        t.playSound(t.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.5f);
    }

    private Request take(Player target, String fromName) {
        Map<UUID, Request> m = requests.get(target.getUniqueId());
        if (m == null) return null;
        m.values().removeIf(r -> System.currentTimeMillis() - r.time() > REQUEST_MS);
        Request r;
        if (fromName == null) r = m.values().stream().max(Comparator.comparingLong(Request::time)).orElse(null);
        else { Player f = Bukkit.getPlayerExact(fromName); r = f == null ? null : m.get(f.getUniqueId()); }
        if (r != null) m.remove(r.from());
        return r;
    }

    private void deny(Player p, String from) {
        Request r = take(p, from);
        if (r == null) { p.sendMessage(pre() + "§cBekleyen istek yok."); return; }
        p.sendMessage(pre() + "§7İstek reddedildi.");
        Player f = Bukkit.getPlayer(r.from());
        if (f != null) f.sendMessage(pre() + "§c" + p.getName() + " duel isteğini reddetti.");
    }

    private void accept(Player p, String from) {
        Request r = take(p, from);
        if (r == null) { p.sendMessage(pre() + "§cBekleyen istek yok (ya da süresi doldu)."); return; }
        Player f = Bukkit.getPlayer(r.from());
        if (f == null) { p.sendMessage(pre() + "§cRakip çıkış yapmış."); return; }
        if (duels.containsKey(p.getUniqueId()) || duels.containsKey(f.getUniqueId())) { p.sendMessage(pre() + "§cSen ya da rakip zaten duelde."); return; }
        for (Player x : List.of(p, f)) if (x.getGameMode() != GameMode.SURVIVAL || x.isDead()) { p.sendMessage(pre() + "§cİki oyuncu da hayatta kalma modunda olmalı."); return; }
        if (!plugin.invs().sameMode(p, f)) { p.sendMessage(pre() + "§cFarklı moddasınız (Skyblock / SMP envanterleri ayrı)."); return; }
        start(f, p);
    }

    // ---------------- duel akisi ----------------
    private void start(Player a, Player b) {
        int slot = freeSlot();
        buildArena(slot);
        Duel d = new Duel(a, b, slot);
        duels.put(a.getUniqueId(), d);
        duels.put(b.getUniqueId(), d);
        Location c = center(slot);
        Location la = c.clone().add(-10, 0, 0); la.setYaw(-90);
        Location lb = c.clone().add(10, 0, 0);  lb.setYaw(90);
        for (Player x : List.of(a, b)) {
            x.closeInventory();
            x.setFallDistance(0);
            x.setFireTicks(0);
            var hp = x.getAttribute(Attribute.MAX_HEALTH);
            x.setHealth(hp != null ? hp.getValue() : 20);
            x.setFoodLevel(20);
            x.setSaturation(10);
        }
        a.teleport(la);
        b.teleport(lb);
        final int[] count = {5};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            if (d.ended) { task.cancel(); return; }
            for (Player x : List.of(a, b)) {
                if (count[0] > 0) { x.sendTitle("§e" + count[0], "§7" + d.other(x).getName() + " ile duel", 0, 22, 0); x.playSound(x.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1f); }
                else { x.sendTitle("§c§lSAVAŞ!", "", 0, 20, 10); x.playSound(x.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.6f, 1.4f); }
            }
            if (count[0]-- <= 0) { d.started = true; task.cancel(); }
        }, 0L, 20L);
        d.timeoutTask = Bukkit.getScheduler().runTaskLater(plugin, () -> draw(d), MAX_DUEL_TICKS + 100).getTaskId();
        Bukkit.broadcastMessage(pre() + "§6" + a.getName() + " §7ve §6" + b.getName() + " §7duele başladı!");
    }

    /** Kaybedenin esyalarini kazanana verir. drops verilirse (olum) onlari kullanir, yoksa envanteri bosaltir. */
    private void finish(Duel d, Player winner, Player loser, String reason) {
        if (d.ended) return;
        d.ended = true;
        cleanup(d);
        List<ItemStack> loot = new ArrayList<>();
        for (ItemStack it : loser.getInventory().getContents()) if (it != null && !it.getType().isAir()) loot.add(it.clone());
        loser.getInventory().clear();
        loser.updateInventory();
        giveLoot(winner, loot, d.back(winner));
        healAndReturn(winner, d.back(winner));
        if (!loser.isDead()) healAndReturn(loser, d.back(loser));
        announce(winner, loser, reason, loot.size());
    }

    private void giveLoot(Player winner, List<ItemStack> loot, Location back) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!winner.isOnline()) {
                for (ItemStack it : loot) back.getWorld().dropItemNaturally(back, it);
                return;
            }
            for (ItemStack it : loot)
                for (ItemStack left : winner.getInventory().addItem(it).values())
                    winner.getWorld().dropItemNaturally(winner.getLocation(), left);
        }, 25L);
    }

    private void healAndReturn(Player p, Location back) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline() || p.isDead()) return;
            var hp = p.getAttribute(Attribute.MAX_HEALTH);
            p.setHealth(hp != null ? hp.getValue() : 20);
            p.setFireTicks(0);
            p.setFallDistance(0);
            p.teleport(back);
        }, 20L);
    }

    private void announce(Player w, Player l, String reason, int items) {
        Bukkit.broadcastMessage(pre() + "§6" + w.getName() + " §7duelde §c" + l.getName() + " §7oyuncusunu yendi! §8(" + reason + ")");
        w.sendTitle("§a§lKAZANDIN!", "§7" + items + " eşya senin oldu", 5, 50, 10);
        if (l.isOnline()) l.sendTitle("§c§lKAYBETTİN", "§7Eşyaların " + w.getName() + " oyuncusuna geçti", 5, 50, 10);
        w.playSound(w.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
    }

    private void draw(Duel d) {
        if (d.ended) return;
        d.ended = true;
        cleanup(d);
        for (Player x : List.of(d.a, d.b)) {
            x.sendMessage(pre() + "§eSüre doldu, duel berabere! Eşyalar kimseye geçmedi.");
            healAndReturn(x, d.back(x));
        }
    }

    private void cleanup(Duel d) {
        duels.remove(d.a.getUniqueId());
        duels.remove(d.b.getUniqueId());
        if (d.timeoutTask != -1) Bukkit.getScheduler().cancelTask(d.timeoutTask);
        Bukkit.getScheduler().runTaskLater(plugin, () -> usedSlots.remove(d.slot), 60L);
    }

    public void shutdown() {
        for (Duel d : new HashSet<>(duels.values())) {
            d.ended = true;
            d.a.teleport(d.backA);
            d.b.teleport(d.backB);
        }
        duels.clear();
    }

    public void init() { world(); }

    // ---------------- olaylar ----------------
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent e) {
        Player loser = e.getEntity();
        Duel d = duels.get(loser.getUniqueId());
        if (d == null) return;
        d.ended = true;
        cleanup(d);
        Player winner = d.other(loser);
        List<ItemStack> loot = new ArrayList<>(e.getDrops());
        e.getDrops().clear();
        e.setKeepLevel(true);
        e.setDroppedExp(0);
        e.setKeepInventory(false);
        pendingRespawn.put(loser.getUniqueId(), d.back(loser));
        giveLoot(winner, loot, d.back(winner));
        healAndReturn(winner, d.back(winner));
        announce(winner, loser, "öldü", loot.size());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Location back = pendingRespawn.remove(e.getPlayer().getUniqueId());
        if (back != null) e.setRespawnLocation(back);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        requests.remove(p.getUniqueId());
        Duel d = duels.get(p.getUniqueId());
        if (d == null) return;
        Player w = d.other(p);
        finish(d, w, p, "oyundan çıktı");
        p.teleport(d.back(p));
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Duel d = duels.get(p.getUniqueId());
        if (d != null && !d.started) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPvp(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Player attacker = null;
        if (e.getDamager() instanceof Player p) attacker = p;
        else if (e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player p) attacker = p;
        if (attacker == null || attacker.equals(victim)) return;
        Duel dv = duels.get(victim.getUniqueId());
        Duel da = duels.get(attacker.getUniqueId());
        if (dv != null || da != null) {
            // duel icindeyken sadece rakibe vurulabilir; disaridan karisma yok
            e.setCancelled(dv == null || dv != da || !dv.started);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (!duels.containsKey(e.getPlayer().getUniqueId())) return;
        String c = e.getMessage().toLowerCase(Locale.ROOT);
        if (c.startsWith("/duel") || c.startsWith("/msg") || c.startsWith("/tell") || c.startsWith("/r ")) return;
        e.setCancelled(true);
        e.getPlayer().sendMessage(pre() + "§cDuel sırasında komut kullanamazsın. §7(/duel pes ile teslim olabilirsin)");
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        Duel d = duels.get(e.getPlayer().getUniqueId());
        if (d == null) return;
        // arena disina isinlanmayi engelle (ender incisi arena icinde serbest)
        if (e.getTo() == null || e.getTo().getWorld() != world()) {
            e.setCancelled(true);
            return;
        }
        Location c = center(d.slot);
        if (Math.abs(e.getTo().getX() - c.getX()) > HALF + 1 || Math.abs(e.getTo().getZ() - c.getZ()) > HALF + 1) e.setCancelled(true);
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length != 1 && !(a.length == 2 && (a[0].equalsIgnoreCase("kabul") || a[0].equalsIgnoreCase("red")))) return List.of();
        List<String> out = new ArrayList<>();
        if (a.length == 1) out.addAll(List.of("kabul", "red", "pes"));
        for (Player p : Bukkit.getOnlinePlayers()) if (!p.equals(s)) out.add(p.getName());
        String last = a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
