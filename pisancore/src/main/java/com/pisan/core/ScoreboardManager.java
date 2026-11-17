package com.pisan.core;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scoreboard.*;

import java.util.*;

/** Yan panel (sidebar) scoreboard + /siralama komutu. */
public final class ScoreboardManager implements Listener, CommandExecutor, TabCompleter {

    private final PisanCore plugin;
    private final LevelHook levels = new LevelHook();
    private final Set<UUID> hidden = new HashSet<>();
    private final Map<UUID, Scoreboard> boards = new HashMap<>();
    private Map<UUID, Integer> moneyRank = new HashMap<>();

    public ScoreboardManager(PisanCore plugin) { this.plugin = plugin; }

    LevelHook levels() { return levels; }

    public void start() {
        for (Player p : Bukkit.getOnlinePlayers()) setup(p);
        Bukkit.getScheduler().runTaskTimer(plugin, this::updateAll, 20L, 40L);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) { Bukkit.getScheduler().runTaskLater(plugin, () -> setup(e.getPlayer()), 10L); }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { boards.remove(e.getPlayer().getUniqueId()); }

    private void setup(Player p) {
        if (!p.isOnline() || hidden.contains(p.getUniqueId())) return;
        Scoreboard sb = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective o = sb.registerNewObjective("pisan", Criteria.DUMMY, PisanCore.color("&6&l✦ PISAN SKYBLOCK ✦"));
        o.setDisplaySlot(DisplaySlot.SIDEBAR);
        boards.put(p.getUniqueId(), sb);
        p.setScoreboard(sb);
        update(p);
    }

    private void recomputeMoneyRank() {
        Map<UUID, Integer> r = new HashMap<>();
        int i = 1;
        for (Map.Entry<UUID, Double> e : plugin.store().top(Integer.MAX_VALUE)) r.put(e.getKey(), i++);
        moneyRank = r;
    }

    private void updateAll() {
        recomputeMoneyRank();
        for (Player p : Bukkit.getOnlinePlayers()) update(p);
    }

    private static String rank(int r) { return r <= 0 ? "&7-" : "&e#" + r; }

    private void update(Player p) {
        Scoreboard sb = boards.get(p.getUniqueId());
        if (sb == null) return;
        UUID id = p.getUniqueId();
        long islandLevel = levels.level(id);
        int islandRank = levels.rank(id);
        double avg = plugin.skills().average(id);
        List<String> lines = List.of(
                "&8&m-------------------",
                "&f" + p.getName(),
                "",
                "&7Para: &6" + PisanCore.fmt(Math.floor(plugin.store().get(id))) + " ⛁",
                "&7Para Sırası: " + rank(moneyRank.getOrDefault(id, 0)),
                " ",
                "&7Ada Puanı: &a" + PisanCore.fmt(islandLevel),
                "&7Ada Sırası: " + rank(islandRank),
                "  ",
                "&7Skill Ort.: &b" + String.format(Locale.ROOT, "%.1f", avg),
                "&7Çevrimiçi: &f" + Bukkit.getOnlinePlayers().size(),
                "&8&m------------------- ",
                "&ePisanskyblock.exaroton.me");
        Objective o = sb.getObjective("pisan");
        int score = lines.size();
        for (int i = 0; i < lines.size(); i++) {
            String entry = ChatColor.values()[i].toString() + ChatColor.RESET;
            Team t = sb.getTeam("l" + i);
            if (t == null) { t = sb.registerNewTeam("l" + i); t.addEntry(entry); }
            t.setPrefix(PisanCore.color(lines.get(i)));
            o.getScore(entry).setScore(score--);
        }
    }

    // /scoreboard -> ac/kapat ; /siralama [para|ada|skill]
    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String pre = PisanCore.color(plugin.getConfig().getString("messages.prefix", ""));
        if (cmd.getName().equalsIgnoreCase("scoreboard")) {
            if (!(s instanceof Player p)) return true;
            if (hidden.remove(p.getUniqueId())) { setup(p); p.sendMessage(pre + "§aScoreboard açıldı."); }
            else {
                hidden.add(p.getUniqueId());
                boards.remove(p.getUniqueId());
                p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
                p.sendMessage(pre + "§7Scoreboard kapatıldı. §8(/sb ile tekrar aç)");
            }
            return true;
        }
        String what = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "hepsi";
        if (what.equals("para") || what.equals("hepsi")) {
            s.sendMessage(PisanCore.color("&6&l💰 Para Sıralaması"));
            int i = 1;
            for (Map.Entry<UUID, Double> e : plugin.store().top(10))
                s.sendMessage(PisanCore.color(" &e" + (i++) + ". &f" + plugin.store().name(e.getKey()) + " &8- &6" + PisanCore.fmt(Math.floor(e.getValue())) + " Coin"));
            if (s instanceof Player p) s.sendMessage(PisanCore.color(" &7Senin sıran: " + rank(moneyRank.getOrDefault(p.getUniqueId(), 0))));
        }
        if (what.equals("ada") || what.equals("hepsi")) {
            s.sendMessage(PisanCore.color("&a&l🏝 Ada Sıralaması"));
            Map<String, Long> top = levels.top(10);
            if (top.isEmpty()) s.sendMessage(PisanCore.color(" &7Henüz hesaplanmış ada yok. &8(/is level)"));
            int i = 1;
            for (Map.Entry<String, Long> e : top.entrySet())
                s.sendMessage(PisanCore.color(" &e" + (i++) + ". &f" + e.getKey() + " &8- &a" + PisanCore.fmt(e.getValue()) + " puan"));
            if (s instanceof Player p) s.sendMessage(PisanCore.color(" &7Senin sıran: " + rank(levels.rank(p.getUniqueId())) + " &8(puanı güncellemek için /is level)"));
        }
        if (what.equals("skill") || what.equals("hepsi")) {
            s.sendMessage(PisanCore.color("&b&l⚔ Skill Sıralaması &7(ortalama)"));
            int i = 1;
            for (Map.Entry<UUID, Double> e : plugin.skills().topAverage(10))
                s.sendMessage(PisanCore.color(" &e" + (i++) + ". &f" + plugin.store().name(e.getKey()) + " &8- &b" + String.format(Locale.ROOT, "%.1f", e.getValue())));
        }
        if (!List.of("para", "ada", "skill", "hepsi").contains(what)) s.sendMessage(pre + "Kullanım: §f/siralama [para|ada|skill]");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        if (!cmd.getName().equalsIgnoreCase("siralama") || a.length != 1) return List.of();
        List<String> out = new ArrayList<>(List.of("para", "ada", "skill"));
        out.removeIf(x -> !x.startsWith(a[0].toLowerCase(Locale.ROOT)));
        return out;
    }
}
