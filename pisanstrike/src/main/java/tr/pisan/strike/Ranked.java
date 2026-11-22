package tr.pisan.strike;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Dereceli mod: 1v1 ... 5v5 tek kisilik kuyruklar, ELO puani ve rutbeler.
 * Kuyrukta 2N kisi olunca en uzun bekleyenler alinir, puana gore yilan siralamasiyla
 * dengeli iki takima bolunur ve mac baslar. Mac bitince takim ortalamasina gore ELO degisir.
 */
final class Ranked {
   static final int START_ELO = 1000;
   static final int K = 32;
   static final String[] TIER_NAMES = {"Demir", "Bronz", "Gümüş", "Altın", "Platin", "Elmas", "Usta", "Efsane"};
   static final String[] TIER_COLORS = {"§8", "§6", "§7", "§e", "§3", "§b", "§d", "§c"};
   static final int[] TIER_MIN = {0, 800, 950, 1100, 1250, 1400, 1550, 1700};

   final StrikePlugin plugin;
   /** format (1..5) -> kuyruktaki oyuncular (giris sirasina gore) */
   final Map<Integer, LinkedHashSet<UUID>> queues = new HashMap<>();
   final Map<UUID, Long> queuedAt = new HashMap<>();
   final Map<UUID, Stats> stats = new HashMap<>();
   private final File file;

   static final class Stats {
      String name;
      int elo = START_ELO;
      int wins;
      int losses;
      int draws;
   }

   Ranked(StrikePlugin plugin) {
      this.plugin = plugin;
      for (int i = 1; i <= 5; i++) {
         this.queues.put(i, new LinkedHashSet<>());
      }

      this.file = new File(plugin.getDataFolder(), "ranked.yml");
      this.load();
   }

   // ------------------------------------------------------------------ kayit
   void load() {
      if (!this.file.exists()) {
         return;
      }

      YamlConfiguration y = YamlConfiguration.loadConfiguration(this.file);
      ConfigurationSection sec = y.getConfigurationSection("players");
      if (sec != null) {
         for (String k : sec.getKeys(false)) {
            try {
               Stats s = new Stats();
               s.name = sec.getString(k + ".name", "?");
               s.elo = sec.getInt(k + ".elo", START_ELO);
               s.wins = sec.getInt(k + ".wins");
               s.losses = sec.getInt(k + ".losses");
               s.draws = sec.getInt(k + ".draws");
               this.stats.put(UUID.fromString(k), s);
            } catch (IllegalArgumentException ignored) {
            }
         }
      }
   }

   void save() {
      YamlConfiguration y = new YamlConfiguration();
      for (Map.Entry<UUID, Stats> e : this.stats.entrySet()) {
         String p = "players." + e.getKey() + ".";
         Stats s = e.getValue();
         y.set(p + "name", s.name);
         y.set(p + "elo", s.elo);
         y.set(p + "wins", s.wins);
         y.set(p + "losses", s.losses);
         y.set(p + "draws", s.draws);
      }

      try {
         this.plugin.getDataFolder().mkdirs();
         y.save(this.file);
      } catch (IOException e) {
         this.plugin.getLogger().warning("ranked.yml kaydedilemedi: " + e.getMessage());
      }
   }

   Stats of(UUID id, String name) {
      Stats s = this.stats.computeIfAbsent(id, k -> new Stats());
      if (name != null) {
         s.name = name;
      }

      return s;
   }

   static int tierIndex(int elo) {
      int t = 0;
      for (int i = 0; i < TIER_MIN.length; i++) {
         if (elo >= TIER_MIN[i]) {
            t = i;
         }
      }

      return t;
   }

   static String tier(int elo) {
      int t = tierIndex(elo);
      return TIER_COLORS[t] + TIER_NAMES[t];
   }

   // ------------------------------------------------------------------ kuyruk
   int queueOf(UUID id) {
      for (Map.Entry<Integer, LinkedHashSet<UUID>> e : this.queues.entrySet()) {
         if (e.getValue().contains(id)) {
            return e.getKey();
         }
      }

      return 0;
   }

   void join(Player p, int n) {
      UUID id = p.getUniqueId();
      if (n < 1 || n > 5) {
         p.sendMessage("§cGeçersiz mod. §71v1 ile 5v5 arası seç.");
         return;
      }

      if (this.plugin.gameOf.containsKey(id)) {
         p.sendMessage("§cMaçtayken kuyruğa giremezsin.");
         return;
      }

      if (this.plugin.partyOf.containsKey(id)) {
         p.sendMessage("§cDereceli kuyruk tek kişilik. §7Önce partiden ayrıl (Parti Menüsü → Partiden ayrıl).");
         return;
      }

      int cur = this.queueOf(id);
      if (cur == n) {
         this.leave(p, true);
         return;
      }

      if (cur != 0) {
         this.queues.get(cur).remove(id);
      }

      this.of(id, p.getName());
      this.queues.get(n).add(id);
      this.queuedAt.put(id, System.currentTimeMillis());
      p.sendMessage("§aDereceli " + n + "v" + n + " kuyruğuna girdin. §7(" + this.queues.get(n).size() + "/" + n * 2 + ")");
      p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0F, 1.6F);
      this.tryMatch(n);
   }

   void leave(Player p, boolean tell) {
      int cur = this.queueOf(p.getUniqueId());
      if (cur != 0) {
         this.queues.get(cur).remove(p.getUniqueId());
         this.queuedAt.remove(p.getUniqueId());
         if (tell) {
            p.sendMessage("§7Dereceli kuyruktan çıktın.");
         }
      } else if (tell) {
         p.sendMessage("§7Kuyrukta değilsin.");
      }
   }

   void remove(UUID id) {
      for (LinkedHashSet<UUID> q : this.queues.values()) {
         q.remove(id);
      }

      this.queuedAt.remove(id);
   }

   void tryMatch(int n) {
      LinkedHashSet<UUID> q = this.queues.get(n);
      q.removeIf(id -> {
         Player p = Bukkit.getPlayer(id);
         return p == null || this.plugin.gameOf.containsKey(id) || this.plugin.partyOf.containsKey(id);
      });
      if (q.size() < n * 2) {
         return;
      }

      List<Player> picked = new ArrayList<>();
      for (UUID id : new ArrayList<>(q)) {
         if (picked.size() == n * 2) {
            break;
         }

         picked.add(Bukkit.getPlayer(id));
      }

      for (Player p : picked) {
         this.remove(p.getUniqueId());
      }

      // puana gore yilan siralamasi: A B B A A B B A A B
      picked.sort((a, b) -> this.of(b.getUniqueId(), b.getName()).elo - this.of(a.getUniqueId(), a.getName()).elo);
      StrikePlugin.Party party = new StrikePlugin.Party(picked.get(0).getUniqueId());
      int[] pattern = {0, 1, 1, 0};
      for (int i = 0; i < picked.size(); i++) {
         UUID id = picked.get(i).getUniqueId();
         party.members.add(id);
         party.team.put(id, pattern[i % 4]);
      }

      party.ranked = n;
      party.toWin = n == 1 ? 5 : 9;
      if (n == 1) {
         party.mode = Game.Mode.DUEL;
         party.map = "arena";
      } else {
         party.mode = Game.Mode.COMP;
         List<String> maps = new ArrayList<>();
         for (StrikeMap m : StrikeMap.ALL.values()) {
            if (!m.duel) {
               maps.add(m.id);
            }
         }

         Collections.shuffle(maps);
         party.map = maps.get(0);
      }

      int[] avg = this.teamAverages(party);
      for (Player p : picked) {
         int g = party.team.get(p.getUniqueId());
         p.sendTitle("§6§lDERECELİ " + n + "v" + n, "§7Rakip bulundu · " + StrikeMap.ALL.get(party.map).name, 5, 50, 10);
         p.sendMessage("§6Dereceli " + n + "v" + n + " başlıyor. §7Takım ortalaması: §f" + avg[g] + " §7— rakip: §f" + avg[1 - g]);
      }

      this.plugin.startGame(picked.get(0), party);
   }

   int[] teamAverages(StrikePlugin.Party party) {
      int[] sum = new int[2];
      int[] cnt = new int[2];
      for (Map.Entry<UUID, Integer> e : party.team.entrySet()) {
         Stats s = this.of(e.getKey(), null);
         sum[e.getValue()] += s.elo;
         cnt[e.getValue()]++;
      }

      return new int[]{cnt[0] == 0 ? START_ELO : sum[0] / cnt[0], cnt[1] == 0 ? START_ELO : sum[1] / cnt[1]};
   }

   /** Mac sonu: kazanan grup (0/1) ya da -1 beraberlik. */
   void finish(Game g, int winner) {
      StrikePlugin.Party party = g.party;
      int[] avg = this.teamAverages(party);
      double expected0 = 1.0 / (1.0 + Math.pow(10.0, (avg[1] - avg[0]) / 400.0));
      double score0 = winner < 0 ? 0.5 : (winner == 0 ? 1.0 : 0.0);
      int delta0 = (int) Math.round(K * (score0 - expected0));
      for (Map.Entry<UUID, Integer> e : party.team.entrySet()) {
         int grp = e.getValue();
         int d = grp == 0 ? delta0 : -delta0;
         Stats s = this.of(e.getKey(), null);
         int before = s.elo;
         s.elo = Math.max(0, s.elo + d);
         if (winner < 0) {
            s.draws++;
         } else if (winner == grp) {
            s.wins++;
         } else {
            s.losses++;
         }

         Player p = Bukkit.getPlayer(e.getKey());
         if (p != null) {
            String res = winner < 0 ? "§eBerabere" : (winner == grp ? "§a§lKAZANDIN" : "§c§lKAYBETTİN");
            p.sendMessage(res + " §7· ELO §f" + before + " → " + s.elo + " " + (d >= 0 ? "§a+" + d : "§c" + d) + " §7· " + tier(s.elo));
            if (tierIndex(before) != tierIndex(s.elo)) {
               p.sendTitle(tierIndex(s.elo) > tierIndex(before) ? "§a§lRÜTBE ATLADIN" : "§c§lRÜTBE DÜŞTÜ", tier(s.elo), 10, 60, 10);
            }
         }
      }

      this.save();
   }

   List<Stats> top(int n) {
      List<Stats> l = new ArrayList<>(this.stats.values());
      l.removeIf(s -> s.wins + s.losses + s.draws == 0);
      l.sort((a, b) -> b.elo - a.elo);
      return l.subList(0, Math.min(n, l.size()));
   }

   // ------------------------------------------------------------------ menu
   void open(Player p) {
      Inventory inv = this.plugin.menu("ranked", 0, 27, "§6Dereceli");
      Stats me = this.of(p.getUniqueId(), p.getName());
      ItemStack head = new ItemStack(Material.PLAYER_HEAD);
      SkullMeta sm = (SkullMeta) head.getItemMeta();
      sm.setOwningPlayer(p);
      sm.setDisplayName("§f" + p.getName() + " §7· " + tier(me.elo));
      sm.setLore(List.of("§7ELO: §f" + me.elo, "§7Galibiyet: §a" + me.wins + " §7Mağlubiyet: §c" + me.losses + " §7Beraberlik: §e" + me.draws));
      head.setItemMeta(sm);
      inv.setItem(4, head);
      int mine = this.queueOf(p.getUniqueId());
      Material[] icons = {Material.IRON_SWORD, Material.GOLDEN_SWORD, Material.DIAMOND_SWORD, Material.NETHERITE_SWORD, Material.NETHER_STAR};
      for (int n = 1; n <= 5; n++) {
         int size = this.queues.get(n).size();
         String state = mine == n ? "§a● Kuyruktasın §7— tıkla: çık" : "§eTıkla: kuyruğa gir";
         String desc = n == 1 ? "§7Düello arenası, 5 tur kazanan." : "§7Rastgele bomba haritası, 9 tur kazanan.";
         inv.setItem(10 + n, this.plugin.ui(icons[n - 1], "r:" + n, "§6§l" + n + "v" + n + " Dereceli", desc, "§7Kuyrukta: §f" + size + "/" + n * 2, state));
      }

      List<String> lore = new ArrayList<>();
      int i = 1;
      for (Stats s : this.top(10)) {
         lore.add("§7" + i++ + ". §f" + s.name + " §7— §f" + s.elo + " " + tier(s.elo));
      }

      if (lore.isEmpty()) {
         lore.add("§7Henüz dereceli maç oynanmadı.");
      }

      inv.setItem(22, this.plugin.ui(Material.GOLD_BLOCK, "r:top", "§e§lSıralama (ilk 10)", lore.toArray(new String[0])));
      String[] ranks = new String[TIER_NAMES.length];
      for (int t = 0; t < TIER_NAMES.length; t++) {
         ranks[t] = TIER_COLORS[t] + TIER_NAMES[t] + " §7" + TIER_MIN[t] + (t + 1 < TIER_MIN.length ? "-" + (TIER_MIN[t + 1] - 1) : "+");
      }

      inv.setItem(18, this.plugin.ui(Material.BOOK, "r:info", "§f§lRütbeler", ranks));
      if (mine != 0) {
         inv.setItem(26, this.plugin.ui(Material.BARRIER, "r:leave", "§c§lKuyruktan çık"));
      }

      p.openInventory(inv);
   }

   /** Kuyruktakilere durum cubugu. */
   void hud() {
      long now = System.currentTimeMillis();
      for (Map.Entry<Integer, LinkedHashSet<UUID>> e : this.queues.entrySet()) {
         int n = e.getKey();
         for (UUID id : e.getValue()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
               long s = (now - this.queuedAt.getOrDefault(id, now)) / 1000L;
               p.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                  net.md_5.bungee.api.chat.TextComponent.fromLegacyText("§6Dereceli " + n + "v" + n + " §7kuyruğu: §f" + e.getValue().size() + "/" + n * 2 + " §7· " + s / 60 + ":" + String.format("%02d", s % 60)));
            }
         }
      }
   }

   static Map<UUID, Integer> copy(Map<UUID, Integer> m) {
      return new LinkedHashMap<>(m);
   }
}
