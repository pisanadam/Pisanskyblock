package tr.pisan.strike;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.Particle.DustOptions;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarFlag;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.scoreboard.Team.Option;
import org.bukkit.scoreboard.Team.OptionStatus;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

final class Game {
   static final int T = 0;
   static final int CT = 1;
   final StrikePlugin plugin;
   final StrikePlugin.Party party;
   /** parti liderinin sectigi (ya da dereceli eslestirmenin kurdugu) takimlar */
   java.util.Map<java.util.UUID, Integer> preset;
   /** bu macin botlari (bos = botsuz mac) */
   final List<Bot> bots = new ArrayList<>();
   /** T botlarinin bu tur saldiracagi bolge: 0 = A, 1 = B */
   int botSite;
   final Game.Mode mode;
   final StrikeMap map;
   final int slot;
   final int toWin;
   final boolean unlimited;
   final boolean anyItem;
   StrikeMap.Built built;
   final Map<UUID, Game.PS> players = new LinkedHashMap<>();
   final Map<String, int[]> ammo = new HashMap<>();
   final int[] score = new int[2];
   final int[] lossStreak = new int[2];
   final int[] groupSide = new int[]{0, 1};
   Game.Phase phase = Game.Phase.BUILDING;
   int round = 0;
   int ticks = 0;
   Location bomb;
   UUID planter;
   UUID defuser;
   boolean plantedThisRound;
   final BossBar bar;
   final Scoreboard board;
   final List<Game.Grenade> grenades = new ArrayList<>();
   final List<Game.Area> smokes = new ArrayList<>();
   final List<Game.Area> fires = new ArrayList<>();
   final Random rnd = new Random();
   int dmTicks = 0;

   Game(StrikePlugin var1, StrikePlugin.Party var2, Game.Mode var3, StrikeMap var4, int var5) {
      this.plugin = var1;
      this.party = var2;
      this.mode = var3;
      this.map = var4;
      this.slot = var5;
      this.toWin = var3 == Game.Mode.DM ? 30 : var2.toWin;
      this.unlimited = var2.unlimited || var3 == Game.Mode.DM;
      this.anyItem = var2.anyItem;
      this.bar = Bukkit.createBossBar("Harita hazırlanıyor…", BarColor.WHITE, BarStyle.SOLID, new BarFlag[0]);
      this.board = Bukkit.getScoreboardManager().getNewScoreboard();
      Team var6 = this.board.registerNewTeam("t");
      Team var7 = this.board.registerNewTeam("ct");
      Team var8 = this.board.registerNewTeam("ffa");
      var6.setColor(ChatColor.GOLD);
      var7.setColor(ChatColor.AQUA);
      var8.setColor(ChatColor.WHITE);

      for (Team var12 : new Team[]{var6, var7}) {
         var12.setAllowFriendlyFire(false);
         var12.setCanSeeFriendlyInvisibles(true);
         var12.setOption(Option.NAME_TAG_VISIBILITY, OptionStatus.FOR_OWN_TEAM);
      }

      var8.setOption(Option.NAME_TAG_VISIBILITY, OptionStatus.NEVER);
      Objective var13 = this.board.registerNewObjective("strike", Criteria.DUMMY, "§6§lPISAN §f§lSTRIKE");
      var13.setDisplaySlot(DisplaySlot.SIDEBAR);
   }

   void start(List<Player> var1) {
      ArrayList var2 = new ArrayList(var1);
      Collections.shuffle(var2);

      for (int var3 = 0; var3 < var2.size(); var3++) {
         Player var4 = (Player)var2.get(var3);
         Game.PS var5 = new Game.PS(var4);
         var5.group = this.mode == Game.Mode.DM ? -1 : (this.preset != null && this.preset.containsKey(var4.getUniqueId()) ? this.preset.get(var4.getUniqueId()) : var3 % 2);
         if (this.unlimited) {
            var5.money = 16000;
         }

         this.players.put(var4.getUniqueId(), var5);
         var4.setScoreboard(this.board);
         this.bar.addPlayer(var4);
         var4.sendTitle("§6" + this.map.name, "§7Harita hazırlanıyor…", 5, 60, 10);
         this.plugin.resetPlayer(var4);
      }

      if (this.party.bots) {
         this.addBots();
      }

      int var6 = 2000 + this.slot * 300;
      short var7 = 2000;
      World var8 = this.plugin.world;
      StrikeMap.keepLoaded(this.plugin, var8, var6, var7, this.map.w, this.map.h, true);
      this.map.build(this.plugin, var8, var6, var7, var1x -> {
         this.built = var1x;
         this.newRound(true);
      });
   }

   // ------------------------------------------------------------------ botlar
   void addBots() {
      int size = Math.max(1, Math.min(5, this.party.botSize));
      int diff = this.party.botDiff;
      int[] cnt = new int[2];
      for (Game.PS ps : this.players.values()) {
         if (ps.group >= 0) {
            cnt[ps.group]++;
         }
      }

      List<Integer> want = new ArrayList<>();
      if (this.mode == Game.Mode.DM) {
         for (int i = this.players.size(); i < size * 2; i++) {
            want.add(-1);
         }
      } else {
         int per = size;
         for (int grp = 0; grp < 2; grp++) {
            for (int i = cnt[grp]; i < per; i++) {
               want.add(grp);
            }
         }
      }

      List<String> names = Bot.names(want.size(), this.rnd);
      for (int i = 0; i < want.size(); i++) {
         Bot b = new Bot(this, names.get(i), want.get(i), diff);
         if (this.unlimited) {
            b.ps.money = 16000;
         }

         this.bots.add(b);
         this.players.put(b.ps.id, b.ps);
      }
   }

   Bot botOf(org.bukkit.entity.Entity e) {
      for (Bot b : this.bots) {
         if (b.ent != null && b.ent.equals(e)) {
            return b;
         }
      }

      return null;
   }

   /** Zirh hesabi (damage() ile ayni kurallar). */
   double afterArmor(Game.PS v, double dmg, boolean head, double pen) {
      double out = dmg;
      boolean armored = head ? v.helmet && v.armor > 0.0 : v.armor > 0.0;
      if (armored && pen < 1.0) {
         out = dmg * pen;
         double absorbed = (dmg - out) * 0.5;
         if (absorbed > v.armor) {
            out += (absorbed - v.armor) * 2.0;
            absorbed = v.armor;
         }

         v.armor = Math.max(0.0, v.armor - absorbed);
         if (v.armor <= 0.0) {
            v.helmet = false;
         }
      }

      return out;
   }

   /** Botlu maclarda ortak hasar yolu: kurban ya da saldirgan bot olabilir. */
   void hitBy(Game.PS v, Game.PS a, Player ap, Weapon w, double dmg, boolean head, double pen) {
      if (v == null || !v.alive || this.phase == Game.Phase.FREEZE || this.phase == Game.Phase.OVER) {
         return;
      }

      if (a != null && a != v && !this.enemies(a, v)) {
         return;
      }

      if (a != null && a != v && System.currentTimeMillis() < v.spawnSafeUntil) {
         return;
      }

      boolean armoredBefore = v.armor > 0.0;
      v.hp -= this.afterArmor(v, dmg, head, pen);
      if (w != null && w.gun()) {
         Location hl = v.bot != null && v.bot.ent != null ? v.bot.ent.getLocation() : (Bukkit.getPlayer(v.id) != null ? Bukkit.getPlayer(v.id).getLocation() : null);
         if (hl != null) {
            this.ccRaw(hl, head ? (v.helmet ? "combat.bullet.headshot_armor" : "combat.bullet.headshot") : (armoredBefore ? "combat.bullet.kevlar" : "combat.bullet.body"), head ? 1.4F : 1.0F);
         }
      }

      if (ap != null) {
         ap.playSound(ap.getLocation(), head ? Sound.BLOCK_NOTE_BLOCK_BELL : Sound.ENTITY_ARROW_HIT_PLAYER, 0.7F, head ? 1.8F : 1.2F);
      }

      if (v.bot != null) {
         Bot b = v.bot;
         if (b.ent != null) {
            b.ent.playHurtAnimation(0.0F);
            b.ent.getWorld().playSound(b.ent.getLocation(), head ? Sound.ENTITY_ZOMBIE_ATTACK_IRON_DOOR : Sound.ENTITY_PLAYER_HURT, head ? 0.6F : 1.0F, head ? 1.8F : 1.0F);
         }

         if (v.hp <= 0.0) {
            this.botKill(v, a, ap, w, head);
         }
      } else {
         Player p = Bukkit.getPlayer(v.id);
         if (p == null) {
            return;
         }

         p.playHurtAnimation(0.0F);
         p.getWorld().playSound(p.getLocation(), head ? Sound.ENTITY_ZOMBIE_ATTACK_IRON_DOOR : Sound.ENTITY_PLAYER_HURT, head ? 0.6F : 1.0F, head ? 1.8F : 1.0F);
         if (v.hp <= 0.0) {
            this.kill(p, v, ap, a, w, head);
         } else {
            p.setHealth(Math.max(0.5, Math.min(20.0, v.hp / 5.0)));
         }
      }
   }

   void botKill(Game.PS v, Game.PS a, Player ap, Weapon w, boolean head) {
      v.alive = false;
      v.hp = 0.0;
      v.deaths++;
      String wn = w == null ? "dünya" : w.name;
      if (a != null && a != v) {
         a.kills++;
         a.roundKills++;
         if (head) {
            a.hs++;
         }

         int reward = w == null ? 300 : w.reward;
         if (!this.unlimited) {
            a.money = Math.min(16000, a.money + reward);
         }

         if (ap != null) {
            ap.playSound(ap.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0F, 1.4F);
            if (!this.unlimited) {
               ap.sendMessage("§a+$" + reward + " §7(" + wn + " ile öldürme)");
            }
         }
      }

      this.say((a != null && a != v ? this.color(a) + a.name + " " : "") + "§7[" + wn + "]" + (head ? " §c⊕" : "") + " " + this.color(v) + v.name);
      v.bot.die();
      if (this.mode == Game.Mode.DM) {
         v.respawnAt = System.currentTimeMillis() + 2500L;
         if (a != null && a.kills >= this.toWin) {
            this.end(a.name + " " + a.kills + " öldürmeyle kazandı");
         }
      } else {
         this.checkRoundEnd();
      }
   }

   /** Bot bombayi kurdu. */
   void plantAt(Game.PS ps, org.bukkit.block.Block where) {
      where.setType(Material.TNT, false);
      this.bomb = where.getLocation();
      this.planter = ps.id;
      this.plantedThisRound = true;
      if (!this.unlimited) {
         ps.money = Math.min(16000, ps.money + 300);
      }

      this.phase = Game.Phase.PLANTED;
      this.ticks = 800;
      String site = this.siteAt(where.getLocation()) == this.map.siteA ? "A" : "B";
      for (Player p : this.online()) {
         p.sendTitle("§c§lBOMBA KURULDU", "§7" + site + " bölgesi · 40 saniye", 5, 40, 10);
      }

      this.sound(Sound.ENTITY_TNT_PRIMED, 1.0F);
   }

   /** Bot bombayi imha etti. */
   void defuseDone(Game.PS ps) {
      if (this.bomb != null) {
         this.bomb.getBlock().setType(Material.AIR);
         this.bomb = null;
      }

      if (!this.unlimited) {
         ps.money = Math.min(16000, ps.money + 300);
      }

      this.roundOver(ps.group, "bomba imha edildi");
   }

   /** Mac sonu kazanan grup: bir takim tamamen ayrildiysa digeri, yoksa skoru yuksek olan; esitse -1. */
   int winnerGroup() {
      int[] left = new int[2];
      for (Game.PS ps : this.players.values()) {
         if (ps.group >= 0 && org.bukkit.Bukkit.getPlayer(ps.id) != null) {
            left[ps.group]++;
         }
      }

      if (left[0] == 0 && left[1] > 0) {
         return 1;
      } else if (left[1] == 0 && left[0] > 0) {
         return 0;
      } else if (this.score[0] != this.score[1]) {
         return this.score[0] > this.score[1] ? 0 : 1;
      } else {
         return -1;
      }
   }

   void end(String var1) {
      if (this.phase != Game.Phase.OVER) {
         this.phase = Game.Phase.OVER;

         for (Game.Grenade var3 : this.grenades) {
            var3.item.remove();
         }

         this.grenades.clear();
         this.smokes.clear();
         this.fires.clear();
         if (this.bomb != null) {
            this.bomb.getBlock().setType(Material.AIR);
            this.bomb = null;
         }

         if (this.party.ranked > 0) {
            this.plugin.ranked.finish(this, this.winnerGroup());
         }

         ArrayList<Game.PS> var6 = new ArrayList<>(this.players.values());
         var6.sort((var0, var1x) -> var1x.kills - var0.kills);
         StringBuilder var7 = new StringBuilder("§6§lMaç bitti §r§7— " + var1 + "\n§7Skor tablosu:");

         for (Game.PS var5 : var6) {
            var7.append("\n §f")
               .append(var5.name)
               .append(" §a")
               .append(var5.kills)
               .append("§7/§c")
               .append(var5.deaths)
               .append(" §7(kafa ")
               .append(var5.hs)
               .append(")");
         }

         for (Player var10 : this.online()) {
            var10.sendMessage(var7.toString());
            this.bar.removePlayer(var10);
            this.plugin.toLobby(var10);
         }

         if (this.built != null) {
            for (Entity var11 : this.built.world.getEntities()) {
               if (var11 instanceof Item && this.built.inMap(var11.getLocation())) {
                  var11.remove();
               }
            }

            StrikeMap.keepLoaded(this.plugin, this.built.world, this.built.ox, this.built.oz, this.map.w, this.map.h, false);
         }

         for (Bot var12 : this.bots) {
            var12.despawn();
         }

         this.bots.clear();
         this.players.clear();
         this.plugin.gameOver(this);
      }
   }

   void leave(Player var1) {
      Game.PS var2 = this.players.remove(var1.getUniqueId());
      if (var2 != null) {
         this.bar.removePlayer(var1);
         if (var2.alive) {
            this.dropAll(var1, var2);
         }

         this.say("§7" + var1.getName() + " maçtan ayrıldı.");
         if (this.phase != Game.Phase.OVER) {
            if (this.players.isEmpty() || this.online().isEmpty()) {
               this.end("herkes ayrıldı");
            } else {
               if (this.mode != Game.Mode.DM) {
                  boolean var3 = this.players.values().stream().anyMatch(var0 -> var0.group == 0);
                  boolean var4 = this.players.values().stream().anyMatch(var0 -> var0.group == 1);
                  if (!var3 || !var4) {
                     this.end((var3 ? this.teamName(0) : this.teamName(1)) + " kazandı (rakip kalmadı)");
                     return;
                  }

                  this.checkRoundEnd();
               } else if (this.players.size() < 2) {
                  this.end("rakip kalmadı");
               }
            }
         }
      }
   }

   int sideOf(Game.PS var1) {
      return var1.group < 0 ? -1 : this.groupSide[var1.group];
   }

   String teamName(int var1) {
      return this.groupSide[var1] == 0 ? "§6Teröristler§r" : "§bAnti-Teröristler§r";
   }

   int roundTicks() {
      return this.mode == Game.Mode.DUEL ? 1500 : 2300;
   }

   void newRound(boolean var1) {
      if (this.phase != Game.Phase.OVER) {
         this.round++;
         this.plantedThisRound = false;
         this.planter = null;
         this.defuser = null;
         if (this.bomb != null) {
            this.bomb.getBlock().setType(Material.AIR);
            this.bomb = null;
         }

         for (Game.Grenade var3 : this.grenades) {
            var3.item.remove();
         }

         this.grenades.clear();
         this.smokes.clear();
         this.fires.clear();

         for (Entity var18 : this.built.world.getEntities()) {
            if (var18 instanceof Item && this.built.inMap(var18.getLocation())) {
               var18.remove();
            }
         }

         if (this.mode == Game.Mode.DM) {
            for (Game.PS var20 : this.players.values()) {
               if (var20.bot == null) {
                  this.spawnDM(var20, true);
               }
            }

            List<Location> var23 = this.built.anywhere();
            int var24 = 0;
            for (Bot var25 : this.bots) {
               var25.roundStart(var23.get(var24++ % var23.size()), false);
            }

            this.phase = Game.Phase.LIVE;
            this.ticks = 12000;
            this.updateBoard();
         } else {
            List var16 = this.built.spawns(false);
            List var19 = this.built.spawns(true);
            int var4 = 0;
            int var5 = 0;
            ArrayList var6 = new ArrayList();

            for (Game.PS var8 : this.players.values()) {
               Player var9 = Bukkit.getPlayer(var8.id);
               if (var9 != null) {
                  boolean var10 = this.sideOf(var8) == 1;
                  Location var11 = var10 ? (Location)var19.get(var5++ % var19.size()) : (Location)var16.get(var4++ % var16.size());
                  boolean var12 = var8.alive && !var1;
                  var8.alive = true;
                  var8.hp = 100.0;
                  var8.roundKills = 0;
                  var8.scoped = false;
                  var8.spray = 0;
                  var8.plantTicks = var8.defuseTicks = 0;
                  if (!var12) {
                     var8.armor = 0.0;
                     var8.helmet = false;
                     var8.kit = false;
                     var9.getInventory().clear();
                  } else {
                     ItemStack var13 = this.firstOf(var9.getInventory(), Weapon.Slot.BOMB);
                     if (var13 != null) {
                        var9.getInventory().remove(var13);
                     }
                  }

                  var9.teleport(var11);
                  var9.setGameMode(GameMode.ADVENTURE);
                  var9.setFallDistance(0.0F);
                  var9.setHealth(20.0);
                  var9.setFoodLevel(20);
                  var9.setFireTicks(0);

                  for (PotionEffect var14 : var9.getActivePotionEffects()) {
                     var9.removePotionEffect(var14.getType());
                  }

                  this.board.getTeam(var10 ? "ct" : "t").addEntry(var9.getName());
                  this.kit(var9, var8, var10);
                  if (!var10) {
                     var6.add(var8);
                  }
               }
            }

            List<Bot> var26 = new ArrayList<>();
            int var27 = 0;
            for (Bot var28 : this.bots) {
               boolean var29 = this.sideOf(var28.ps) == 1;
               Location var30 = var29 ? (Location)var19.get(var5++ % var19.size()) : (Location)var16.get(var4++ % var16.size());
               boolean var31 = var28.ps.alive && !var1;
               var28.siteChoice = var27++;
               var28.roundStart(var30, var31);
               if (!var29) {
                  var26.add(var28);
               }
            }

            this.botSite = this.rnd.nextInt(2);
            if (this.mode == Game.Mode.COMP && (!var6.isEmpty() || !var26.isEmpty()) && this.map.siteA != null) {
               int var32 = this.rnd.nextInt(var6.size() + var26.size());
               if (var32 >= var6.size()) {
                  var26.get(var32 - var6.size()).hasBomb = true;
               }

               Game.PS var21 = var32 < var6.size() ? (Game.PS)var6.get(var32) : null;
               Player var22 = var21 == null ? null : Bukkit.getPlayer(var21.id);
               if (var22 != null) {
                  var22.getInventory().setItem(4, this.plugin.item(Weapon.get("c4"), null));
                  var22.sendMessage("§6§lBomba sende! §7Bir bölgeye (A ya da B) git ve sağ tıkı basılı tutarak kur.");
               }
            }

            this.phase = Game.Phase.FREEZE;
            this.ticks = 20 * (this.mode == Game.Mode.DUEL ? 5 : 10);
            this.say("§e§lTur " + this.round + " §7— satın alma süresi. §fMarket: zümrüt (9. slot) ya da §e/market");
            this.updateBoard();
         }
      }
   }

   void kit(Player var1, Game.PS var2, boolean var3) {
      PlayerInventory var4 = var1.getInventory();
      if (this.firstOf(var4, Weapon.Slot.KNIFE) == null) {
         var4.setItem(2, this.plugin.item(Weapon.get("knife"), null));
      }

      if (this.firstOf(var4, Weapon.Slot.SECONDARY) == null) {
         var4.setItem(1, this.newWeapon(Weapon.get(var3 ? "usp" : "glock")));
      }

      var4.setItem(8, this.plugin.shopItem());
      ItemStack var5 = new ItemStack(Material.LEATHER_CHESTPLATE);
      LeatherArmorMeta var6 = (LeatherArmorMeta)var5.getItemMeta();
      var6.setColor(var3 ? Color.fromRGB(3829413) : Color.fromRGB(13145388));
      var6.setUnbreakable(true);
      var6.setDisplayName(var3 ? "§bAnti-Terörist" : "§6Terörist");
      var5.setItemMeta(var6);
      var4.setChestplate(var5);
      var4.setHelmet(var2.helmet ? new ItemStack(Material.IRON_HELMET) : null);
      var4.setHeldItemSlot(1);
      this.speed(var1);
   }

   ItemStack newWeapon(Weapon var1) {
      String var2 = UUID.randomUUID().toString();
      if (var1.gun()) {
         this.ammo.put(var2, new int[]{var1.mag, var1.reserve});
      }

      return this.plugin.item(var1, var2);
   }

   void tick() {
      if (this.phase != Game.Phase.BUILDING && this.phase != Game.Phase.OVER) {
         long var1 = System.currentTimeMillis();

         for (Game.PS var4 : new ArrayList<>(this.players.values())) {
            Player var5 = Bukkit.getPlayer(var4.id);
            if (var5 != null) {
               this.tickPlayer(var5, var4, var1);
            }
         }

         for (Bot var7 : new ArrayList<>(this.bots)) {
            var7.tick(var1);
         }

         this.tickGrenades();
         if (this.ticks > 0) {
            this.ticks--;
         }

         switch (this.phase) {
            case FREEZE:
               if (this.ticks <= 0) {
                  this.phase = Game.Phase.LIVE;
                  this.ticks = this.roundTicks();
                  this.say("§a§lBAŞLA!");
                  this.sound(Sound.BLOCK_NOTE_BLOCK_BELL, 1.2F);
               }
               break;
            case LIVE:
               if (this.mode == Game.Mode.DM) {
                  if (this.ticks <= 0) {
                     Game.PS var6 = this.players.values().stream().max((var0, var1x) -> var0.kills - var1x.kills).orElse(null);
                     this.end("süre doldu" + (var6 != null ? ", kazanan " + var6.name : ""));
                     return;
                  }
               } else if (this.ticks <= 0) {
                  if (this.mode == Game.Mode.DUEL) {
                     this.roundOver(-1, "süre doldu, berabere");
                  } else {
                     this.roundOver(this.groupOfSide(1), "süre doldu");
                  }
               }
               break;
            case PLANTED:
               this.tickBomb();
               break;
            case ROUND_END:
               if (this.ticks <= 0) {
                  this.afterRound();
               }
         }

         if (this.ticks % 10 == 0) {
            this.updateBoard();
         }
      }
   }

   int groupOfSide(int var1) {
      return this.groupSide[0] == var1 ? 0 : 1;
   }

   void roundOver(int var1, String var2) {
      if (this.phase != Game.Phase.ROUND_END && this.phase != Game.Phase.OVER) {
         this.phase = Game.Phase.ROUND_END;
         this.ticks = 100;
         if (var1 >= 0) {
            this.score[var1]++;
            int var3 = 1 - var1;
            boolean var4 = var2.contains("bomba");

            for (Game.PS var6 : this.players.values()) {
               if (var6.group == var1) {
                  var6.money = var6.money + (!var4 && !var2.contains("imha") ? 3250 : 3500);
               } else {
                  var6.money = var6.money + Math.min(3400, 1400 + 500 * this.lossStreak[var3]);
                  if (this.groupSide[var6.group] == 0 && this.plantedThisRound) {
                     var6.money += 800;
                  }
               }

               if (!this.unlimited) {
                  var6.money = Math.min(16000, var6.money);
               } else {
                  var6.money = 16000;
               }
            }

            this.lossStreak[var3] = Math.min(4, this.lossStreak[var3] + 1);
            this.lossStreak[var1] = Math.max(0, this.lossStreak[var1] - 1);

            for (Player var10 : this.online()) {
               var10.sendTitle(this.teamName(var1) + " §fkazandı", "§7" + var2, 5, 50, 10);
            }

            this.sound(this.groupSide[var1] == 0 ? Sound.ENTITY_WITHER_SPAWN : Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0F);
         } else {
            for (Player var8 : this.online()) {
               var8.sendTitle("§7Berabere", "§7" + var2, 5, 50, 10);
            }
         }

         this.say(
            "§eTur "
               + this.round
               + " bitti: §f"
               + (var1 >= 0 ? this.teamName(var1) + " kazandı" : "berabere")
               + " §7("
               + var2
               + ")  §6"
               + this.score[this.groupOfSide(0)]
               + " §7- §b"
               + this.score[this.groupOfSide(1)]
         );
         this.updateBoard();
      }
   }

   void afterRound() {
      for (int var1 = 0; var1 < 2; var1++) {
         if (this.score[var1] >= this.toWin) {
            this.end(this.teamName(var1) + " " + this.score[var1] + "-" + this.score[1 - var1] + " kazandı");
            return;
         }
      }

      int var5 = 2 * (this.toWin - 1);
      if (this.round >= var5) {
         this.end("berabere " + this.score[0] + "-" + this.score[1]);
      } else {
         if (this.mode == Game.Mode.COMP && this.round == this.toWin - 1) {
            int var2 = this.groupSide[0];
            this.groupSide[0] = this.groupSide[1];
            this.groupSide[1] = var2;
            this.lossStreak[0] = this.lossStreak[1] = 0;

            for (Game.PS var4 : this.players.values()) {
               var4.money = this.unlimited ? 16000 : 800;
               var4.alive = false;
            }

            for (Player var7 : this.online()) {
               var7.sendTitle("§e§lDEVRE ARASI", "§7Taraflar değişti", 5, 50, 10);
               var7.getInventory().clear();
            }

            this.board.getTeam("t").getEntries().forEach(var1x -> this.board.getTeam("t").removeEntry(var1x));
            this.board.getTeam("ct").getEntries().forEach(var1x -> this.board.getTeam("ct").removeEntry(var1x));
         }

         this.newRound(false);
      }
   }

   void checkRoundEnd() {
      if (this.mode != Game.Mode.DM && (this.phase == Game.Phase.LIVE || this.phase == Game.Phase.PLANTED || this.phase == Game.Phase.FREEZE)) {
         int var1 = 0;
         int var2 = 0;

         for (Game.PS var4 : this.players.values()) {
            if (var4.alive) {
               if (this.sideOf(var4) == 0) {
                  var1++;
               } else {
                  var2++;
               }
            }
         }

         if (this.phase == Game.Phase.PLANTED) {
            if (var2 == 0) {
               this.roundOver(this.groupOfSide(0), "Anti-Teröristler öldü, bomba kurulu");
            }
         } else {
            if (var1 == 0 && var2 == 0) {
               this.roundOver(-1, "iki taraf da öldü");
            } else if (var1 == 0) {
               this.roundOver(this.groupOfSide(1), "Teröristler yok edildi");
            } else if (var2 == 0) {
               this.roundOver(this.groupOfSide(0), "Anti-Teröristler yok edildi");
            }
         }
      }
   }

   void tickPlayer(Player var1, Game.PS var2, long var3) {
      if (this.mode == Game.Mode.DM && !var2.alive && var2.respawnAt > 0L && var3 >= var2.respawnAt) {
         this.spawnDM(var2, false);
      } else if (!var2.alive) {
         if (this.ticks % 10 == 0) {
            this.hud(var1, var2);
         }
      } else if (!this.built.inMap(var1.getLocation())) {
         this.damage(var1, var2, null, null, 1000.0, false, 1.0);
      } else {
         ItemStack var5 = var1.getInventory().getItemInMainHand();
         Weapon var6 = this.plugin.weaponOf(var5);
         String var7 = this.plugin.uidOf(var5);
         if (var2.reloadingItem != null && var3 >= var2.reloadUntil) {
            int[] var8 = this.ammo.get(var2.reloadingItem);
            Weapon var9 = this.weaponByUid(var1, var2.reloadingItem);
            if (var8 != null && var9 != null) {
               int var10 = Math.min(var9.mag - var8[0], var8[1]);
               var8[0] += var10;
               var8[1] -= var10;
            }

            var2.reloadingItem = null;
            this.restoreModels(var1);
            if (!this.ccSound(var1.getLocation(), var6, "clip_in", 0.9F)) {
               var1.playSound(var1.getLocation(), Sound.ITEM_CROSSBOW_LOADING_END, 0.8F, 1.3F);
            }
         }

         if (var2.reloadingItem != null && (var7 == null || !var7.equals(var2.reloadingItem))) {
            var2.reloadingItem = null;
            this.restoreModels(var1);
         }

         // Otomatik silah: tetik basili kaldikca (istemci ~200 ms'de bir sinyal yollar) ates eder.
         // Tek atislik silah: her tiklama bir atis istegidir; silahin atis hizi dolunca o istek atilir.
         boolean var39 = var6 != null && var6.gun() && var7 != null && this.canShoot()
            && (var6.auto ? var3 < var2.triggerUntil : var2.shotRequests > 0 && var3 - var2.lastTrigger < 400L);
         if (!var39 && var6 != null && !var6.auto && var3 - var2.lastTrigger >= 400L) {
            var2.shotRequests = 0;
         }

         if (var39) {
            // sunucu saniyede 20 kez guncellenir; 600 mermi/dk'dan hizli silahlar ayni guncellemede
            // birden fazla mermi atabilsin diye sonraki atis zamani birikimli tutulur
            for (int var45 = 0; var45 < (var6.auto ? 3 : 1) && var3 >= var2.nextShot && var2.reloadingItem == null; var45++) {
               int[] var11 = this.ammo.computeIfAbsent(var7, var1x -> new int[]{var6.mag, var6.reserve});
               if (var11[0] <= 0) {
                  var1.playSound(var1.getLocation(), Sound.BLOCK_LEVER_CLICK, 1.0F, 1.8F);
                  this.reload(var1, var2, var6, var7);
                  var2.shotRequests = 0;
                  break;
               } else {
                  var11[0]--;
                  this.fire(var1, var2, var6);
                  var2.nextShot = Math.max(var2.nextShot, var3 - 60L) + var6.shotMillis();
                  var2.spray++;
                  if (!var6.auto) {
                     var2.shotRequests = Math.max(0, var2.shotRequests - 1);
                  }
               }
            }
         } else if (var3 > Math.max(var2.triggerUntil, var2.lastTrigger + 150L) + 250L) {
            var2.spray = Math.max(0, var2.spray - 1);
         }

         if (var2.plantTicks > 0 || var3 - var2.plantPress < 300L) {
            this.tickPlant(var1, var2, var3);
         }

         if (var2.defuseTicks > 0 || var3 - var2.defusePress < 300L) {
            this.tickDefuse(var1, var2, var3);
         }

         if (var2.scoped && (var6 == null || !var6.scope)) {
            this.unscope(var1, var2);
         }

         if (this.ticks % 4 == 0) {
            this.hud(var1, var2);
         }

         var2.lastLoc = var1.getLocation();
      }
   }

   boolean canShoot() {
      return this.phase == Game.Phase.LIVE || this.phase == Game.Phase.PLANTED || this.phase == Game.Phase.ROUND_END;
   }

   Weapon weaponByUid(Player var1, String var2) {
      for (ItemStack var6 : var1.getInventory().getContents()) {
         if (var6 != null && var2.equals(this.plugin.uidOf(var6))) {
            return this.plugin.weaponOf(var6);
         }
      }

      return null;
   }

   void trigger(Player var1) {
      Game.PS var2 = this.players.get(var1.getUniqueId());
      if (var2 != null && var2.alive) {
         long var3 = System.currentTimeMillis();
         var2.lastTrigger = var3;
         // istemci basili tutarken ~200 ms'de bir sinyal yollar; gecikmeye pay birak
         var2.triggerUntil = var3 + 270L;
         // tek atislik silahlarda ust uste tiklamalar birikmesin (en fazla 2 bekleyen atis)
         var2.shotRequests = Math.min(2, var2.shotRequests + 1);
      }
   }

   void reload(Player var1, Game.PS var2, Weapon var3, String var4) {
      int[] var5 = this.ammo.get(var4);
      if (var5 != null && var5[0] < var3.mag && var5[1] > 0 && var2.reloadingItem == null) {
         var2.reloadingItem = var4;
         var2.reloadUntil = System.currentTimeMillis() + (long)(var3.reload * 1000.0);
         if (var2.scoped) {
            this.unscope(var1, var2);
         }

         // sarjor degistirme pozu (kaynak paketindeki CustomModelData + 500 modeli)
         this.setModel(var1.getInventory().getItemInMainHand(), var3.model + RELOAD_MODEL_OFFSET);
         if (!this.ccSound(var1.getLocation(), var3, "clip_out", 0.9F)) {
            var1.getWorld().playSound(var1.getLocation(), Sound.ITEM_CROSSBOW_LOADING_START, 0.8F, 1.2F);
         }
      }
   }

   void toggleScope(Player var1) {
      Game.PS var2 = this.players.get(var1.getUniqueId());
      if (var2 != null && var2.alive) {
         Weapon var3 = this.plugin.weaponOf(var1.getInventory().getItemInMainHand());
         if (var3 != null && var3.scope) {
            if (var2.scoped) {
               this.unscope(var1, var2);
            } else {
               var2.scoped = true;
               var1.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, 1000000, 3, false, false, false));
               var1.getInventory().setHelmet(scopeItem());
               this.setModel(var1.getInventory().getItemInMainHand(), 1098);
               var1.playSound(var1.getLocation(), Sound.ITEM_SPYGLASS_USE, 1.0F, 1.0F);
               this.speed(var1);
            }
         }
      }
   }

   /** Durbun ekrani: kaynak paketi oyma balkabaginin ekran katmanini CS2 tarzi durbunle degistirir;
    *  CustomModelData 1099 kafadaki balkabagini baskalarina gorunmez yapar. */
   static ItemStack scopeItem() {
      ItemStack it = new ItemStack(Material.CARVED_PUMPKIN);
      org.bukkit.inventory.meta.ItemMeta m = it.getItemMeta();
      m.setCustomModelData(1099);
      m.setDisplayName("§7Dürbün");
      it.setItemMeta(m);
      return it;
   }

   void unscope(Player var1, Game.PS var2) {
      var2.scoped = false;
      ItemStack var3 = var1.getInventory().getHelmet();
      if (var3 != null && var3.getType() == Material.CARVED_PUMPKIN) {
         var1.getInventory().setHelmet(var2.helmet ? new ItemStack(Material.IRON_HELMET) : null);
      }

      for (ItemStack var4 : var1.getInventory().getContents()) {
         if (var4 != null && var4.hasItemMeta() && var4.getItemMeta().hasCustomModelData() && var4.getItemMeta().getCustomModelData() == 1098) {
            Weapon var5 = this.plugin.weaponOf(var4);
            this.setModel(var4, var5 == null ? 0 : var5.model);
         }
      }

      var1.removePotionEffect(PotionEffectType.SLOW);
      this.speed(var1);
   }

   static final int RELOAD_MODEL_OFFSET = 500;

   /** Sarjor degistirme pozundaki silahlari normal modele geri dondurur. */
   void restoreModels(Player p) {
      for (ItemStack it : p.getInventory().getContents()) {
         if (it != null && it.hasItemMeta() && it.getItemMeta().hasCustomModelData()) {
            int c = it.getItemMeta().getCustomModelData();
            if (c > RELOAD_MODEL_OFFSET + 1000 && c < RELOAD_MODEL_OFFSET + 1100) {
               Weapon w = this.plugin.weaponOf(it);
               this.setModel(it, w == null ? c - RELOAD_MODEL_OFFSET : w.model);
            }
         }
      }
   }

   /** Atis efektleri: namlu alevi (susturucuda sadece duman), kivilcim ve sagdan firlayan kovan. */
   void muzzleEffects(World w, Location muzzle, Vector dir, Weapon wpn, boolean silenced) {
      w.spawnParticle(Particle.SMOKE_NORMAL, muzzle, silenced ? 3 : 2, 0.02, 0.02, 0.02, 0.01);
      if (!silenced) {
         boolean big = wpn.kind == Weapon.Kind.SNIPER || wpn.kind == Weapon.Kind.SHOTGUN || wpn.kind == Weapon.Kind.HEAVY;
         float size = big ? 1.6F : (wpn.kind == Weapon.Kind.PISTOL ? 0.8F : 1.1F);
         w.spawnParticle(Particle.DUST_COLOR_TRANSITION, muzzle.clone().add(dir.clone().multiply(0.15)), big ? 6 : 4, 0.04, 0.04, 0.04, 0.0,
            new Particle.DustTransition(Color.fromRGB(255, 240, 150), Color.fromRGB(255, 120, 20), size));
         w.spawnParticle(Particle.FLAME, muzzle, big ? 3 : 1, 0.02, 0.02, 0.02, 0.01);
         w.spawnParticle(Particle.CRIT, muzzle, 2, 0.03, 0.03, 0.03, 0.15);
      }

      if (wpn.kind != Weapon.Kind.SHOTGUN || wpn.auto) {
         Vector right = rotate(dir, 0.0, 1.5707963267948966).setY(0);
         if (right.lengthSquared() > 1.0E-6) {
            right.normalize().multiply(-0.18);
            Location shell = muzzle.clone().add(dir.clone().multiply(-0.45)).add(right.clone().multiply(0.6));
            w.spawnParticle(Particle.ITEM_CRACK, shell, 1, right.getX(), 0.12, right.getZ(), 0.25, new ItemStack(Material.GOLD_NUGGET));
         }
      }
   }

   void setModel(ItemStack it, int model) {
      if (it != null && it.hasItemMeta()) {
         org.bukkit.inventory.meta.ItemMeta m = it.getItemMeta();
         m.setCustomModelData(model == 0 ? null : model);
         it.setItemMeta(m);
      }
   }

   void speed(Player var1) {
      Game.PS var2 = this.players.get(var1.getUniqueId());
      Weapon var3 = this.plugin.weaponOf(var1.getInventory().getItemInMainHand());
      int var4 = var3 == null ? 245 : var3.speed;
      var1.setWalkSpeed((float)(0.2 * (double)var4 / 250.0));
      if (var2 != null && this.phase == Game.Phase.FREEZE) {
         var1.setWalkSpeed(0.2F);
      }
   }

   void fire(Player var1, Game.PS var2, Weapon var3) {
      Location var4 = var1.getEyeLocation();
      double var5 = var2.lastLoc == null ? 0.0 : Math.hypot(var1.getLocation().getX() - var2.lastLoc.getX(), var1.getLocation().getZ() - var2.lastLoc.getZ());
      boolean var7 = !var1.isOnGround();
      double var8;
      if (var3.kind == Weapon.Kind.SNIPER && !var2.scoped) {
         var8 = var3.kind == Weapon.Kind.SNIPER && var3.id.equals("awp") ? 6.0 : 3.5;
      } else {
         var8 = var3.spreadStand;
      }

      if (var5 > 0.06) {
         var8 += var3.spreadMove * Math.min(1.0, var5 / 0.2);
      }

      if (var7) {
         var8 += var3.spreadJump;
      }

      if (var1.isSneaking()) {
         var8 *= 0.6;
      }

      // CS tarzi: sapma uzun spreyde biraz artar ama asil kayma sabit desenden gelir
      var8 += var3.recoil * (double)Math.min(var2.spray, 12) * 0.08;
      Vector var10 = var4.getDirection();
      double[] var40 = Spray.offset(var3, var2.spray);
      double var11 = Math.toRadians(var40[1]);
      double var13 = Math.toRadians(var40[0]);
      var10 = rotate(var10, var11, var13);
      this.aimPunch(var1, var3, var2.spray);

      for (int var15 = 0; var15 < var3.pellets; var15++) {
         this.bullet(var1, var2, var3, var4, cone(var10, var8, this.rnd));
      }

      float var16;
      Sound var21;
      switch (var3.kind) {
         case SNIPER:
            var21 = Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST;
            var16 = 0.6F;
            break;
         case SHOTGUN:
            var21 = Sound.ENTITY_GENERIC_EXPLODE;
            var16 = 1.6F;
            break;
         case PISTOL:
            var21 = Sound.ENTITY_FIREWORK_ROCKET_BLAST;
            var16 = 1.7F;
            break;
         case SMG:
            var21 = Sound.ENTITY_FIREWORK_ROCKET_BLAST;
            var16 = 1.5F;
            break;
         default:
            var21 = Sound.ENTITY_FIREWORK_ROCKET_BLAST;
            var16 = 1.1F;
      }

      boolean var17 = var3.id.equals("usp") || var3.id.equals("m4a1s");
      if (!this.ccSound(var4, var3, "fire", var17 ? 1.2F : (var3.kind == Weapon.Kind.SNIPER ? 4.0F : 3.0F))) {
         var1.getWorld().playSound(var4, var21, var17 ? 0.6F : 2.5F, var17 ? var16 + 0.4F : var16);
         var1.getWorld().playSound(var4, Sound.BLOCK_IRON_TRAPDOOR_CLOSE, var17 ? 0.3F : 0.8F, 2.0F);
      }
      Location var18 = var4.clone()
         .add(var10.clone().multiply(0.8))
         .add(rotate(var10, 0.0, 1.5707963267948966).setY(0).normalize().multiply(-0.25))
         .add(0.0, -0.2, 0.0);
      this.muzzleEffects(var1.getWorld(), var18, var10, var3, var17);

      if (var3.kind == Weapon.Kind.SNIPER && var2.scoped) {
         this.unscope(var1, var2);
      }
   }

   static Vector rotate(Vector var0, double var1, double var3) {
      double var5 = Math.atan2(-var0.getX(), var0.getZ());
      double var7 = Math.asin(-var0.getY());
      var7 -= var1;
      var5 += var3;
      return new Vector(-Math.sin(var5) * Math.cos(var7), -Math.sin(var7), Math.cos(var5) * Math.cos(var7));
   }

   // ------------------------------------------------------------------ Counter Craft sesleri
   /** bizim silah id -> Counter Craft ses adi (strike-pack/countercraft/weapon_map.json ile ayni) */
   static final java.util.Map<String, String> CC = java.util.Map.ofEntries(
      java.util.Map.entry("glock", "glock"), java.util.Map.entry("usp", "usp_silencer"), java.util.Map.entry("p250", "p250"),
      java.util.Map.entry("fiveseven", "fiveseven"), java.util.Map.entry("tec9", "tec9"),
      java.util.Map.entry("deagle", "desert_eagle"), java.util.Map.entry("mac10", "mac10"), java.util.Map.entry("mp9", "mp9"),
      java.util.Map.entry("mp7", "mp7"), java.util.Map.entry("ump", "ump45"), java.util.Map.entry("p90", "p90"),
      java.util.Map.entry("galil", "galilar"), java.util.Map.entry("famas", "famas"), java.util.Map.entry("ak47", "ak47"),
      java.util.Map.entry("m4a4", "m4a4"), java.util.Map.entry("m4a1s", "m4a1_silencer"), java.util.Map.entry("sg553", "sg556"),
      java.util.Map.entry("aug", "aug"), java.util.Map.entry("ssg08", "ssg08"), java.util.Map.entry("awp", "awp"),
      java.util.Map.entry("nova", "nova"), java.util.Map.entry("xm1014", "xm1014"), java.util.Map.entry("mag7", "mag7"),
      java.util.Map.entry("sawedoff", "sawedoff"), java.util.Map.entry("negev", "negev"), java.util.Map.entry("m249", "m249"));

   boolean ccOn() {
      return this.plugin.getConfig().getBoolean("cc-sounds", true);
   }

   /** Silah olayi sesi (fire, clip_out, clip_in...). Counter Craft sesi yoksa false doner. */
   boolean ccSound(Location at, Weapon w, String ev, float vol) {
      String n = w == null ? null : CC.get(w.id);
      if (!this.ccOn() || n == null || at == null) {
         return false;
      }

      at.getWorld().playSound(at, "tactical_arsenal:weapon." + n + "." + ev, org.bukkit.SoundCategory.PLAYERS, vol, 1.0F);
      return true;
   }

   void ccRaw(Location at, String key, float vol) {
      if (this.ccOn() && at != null) {
         at.getWorld().playSound(at, "tactical_arsenal:" + key, org.bukkit.SoundCategory.PLAYERS, vol, 1.0F);
      }
   }

   /** Mermi isabet sesi: kafa / kaskli kafa / yelek / vucut. */
   void hitSound(Location at, Game.PS v, boolean head) {
      if (head) {
         this.ccRaw(at, v.helmet ? "combat.bullet.headshot_armor" : "combat.bullet.headshot", 1.4F);
      } else {
         this.ccRaw(at, v.armor > 0.0 ? "combat.bullet.kevlar" : "combat.bullet.body", 1.0F);
      }
   }

   /** Kamera tepmesi: desenin sonraki adiminin yarisi kadar nisani yukari/yana iter (CS'teki aim punch). */
   void aimPunch(Player p, Weapon w, int n) {
      if (!this.plugin.getConfig().getBoolean("aim-punch", true)) {
         return;
      }

      double[] a = Spray.offset(w, n);
      double[] b = Spray.offset(w, n + 1);
      float dYaw = (float) ((b[0] - a[0]) * 0.5);
      float dPitch = (float) ((b[1] - a[1]) * 0.5);
      if (w.kind == Weapon.Kind.SNIPER || w.kind == Weapon.Kind.SHOTGUN) {
         dPitch = w.kind == Weapon.Kind.SNIPER ? 1.6F : 1.2F;
      }

      if (Math.abs(dYaw) < 0.01F && Math.abs(dPitch) < 0.01F) {
         return;
      }

      try {
         Location to = p.getLocation();
         to.setYaw(to.getYaw() - dYaw);
         to.setPitch(Math.max(-90.0F, to.getPitch() - dPitch));
         p.teleport(to,
            io.papermc.paper.entity.TeleportFlag.Relative.X, io.papermc.paper.entity.TeleportFlag.Relative.Y, io.papermc.paper.entity.TeleportFlag.Relative.Z,
            io.papermc.paper.entity.TeleportFlag.Relative.YAW, io.papermc.paper.entity.TeleportFlag.Relative.PITCH);
      } catch (Throwable ignored) {
      }
   }

   static Vector cone(Vector var0, double var1, Random var3) {
      if (var1 <= 0.0) {
         return var0.clone();
      } else {
         double var4 = Math.toRadians(var1) * Math.sqrt(var3.nextDouble());
         double var6 = var3.nextDouble() * 3.141592653589793 * 2.0;
         Vector var8 = var0.getCrossProduct(new Vector(0, 1, 0));
         if (var8.lengthSquared() < 1.0E-6) {
            var8 = new Vector(1, 0, 0);
         }

         var8.normalize();
         Vector var9 = var0.getCrossProduct(var8).normalize();
         return var0.clone()
            .multiply(Math.cos(var4))
            .add(var8.multiply(Math.cos(var6) * Math.sin(var4)))
            .add(var9.multiply(Math.sin(var6) * Math.sin(var4)))
            .normalize();
      }
   }

   static boolean penetrable(Material var0) {
      String var1 = var0.name();
      return var1.contains("GLASS")
         || var1.contains("PLANKS")
         || var1.contains("LEAVES")
         || var1.contains("WOOL")
         || var1.contains("FENCE")
         || var1.contains("DOOR")
         || var1.equals("BARREL")
         || var1.contains("LOG")
         || var1.contains("SLAB")
         || var1.contains("BAMBOO")
         || var1.equals("HAY_BLOCK")
         || var1.contains("TERRACOTTA");
   }

   void bullet(Player var1, Game.PS var2, Weapon var3, Location var4, Vector var5) {
      World var6 = var1.getWorld();
      Location var7 = var4.clone();
      double var8 = 120.0;
      double var10 = 1.0;
      int var12 = 0;
      double var38 = -1.0;

      while (var12 < 4 && var8 > 0.0) {
         RayTraceResult var13 = var6.rayTrace(
            var7, var5, var8, FluidCollisionMode.NEVER, true, 0.05, var2x -> var2x instanceof Player && !var2x.equals(var1) && this.alive((Player)var2x) || Bot.isBot(var2x) && this.botOf(var2x) != null && this.botOf(var2x).ps.alive
         );
         Vector var14 = var13 == null ? var7.toVector().add(var5.clone().multiply(var8)) : var13.getHitPosition();
         if (Boolean.getBoolean("strike.debug")) {
            this.plugin
               .getLogger()
               .info(
                  "shot "
                     + var7.toVector()
                     + " dir "
                     + var5
                     + " -> "
                     + (
                        var13 == null
                           ? "miss"
                           : (
                              var13.getHitEntity() != null
                                 ? "ent " + var13.getHitEntity().getName()
                                 : "block " + var13.getHitBlock().getType() + " at " + var13.getHitBlock().getLocation().toVector()
                           )
                     )
               );
         }

         this.tracer(var6, var7.toVector(), var14);
         if (var13 == null) {
            return;
         }

         Bot var28 = var13.getHitEntity() == null ? null : this.botOf(var13.getHitEntity());
         if (var28 != null) {
            double var29 = var4.toVector().distance(var14);
            double var31 = var14.getY() - var28.ent.getLocation().getY();
            boolean var33 = var31 >= 1.42;
            double var34 = var33 ? (double)(var3.kind == Weapon.Kind.SHOTGUN ? 2 : 4) : (var31 < 0.7 ? 0.75 : (var31 < 1.0 ? 1.25 : 1.0));
            double var36 = var3.damage * Math.pow(var3.rangeMod, var29 * 40.0 / 500.0) * var10 * var34;
            var6.spawnParticle(Particle.BLOCK_CRACK, var14.toLocation(var6), 8, 0.05, 0.05, 0.05, Material.REDSTONE_BLOCK.createBlockData());
            this.hitBy(var28.ps, var2, var1, var3, var36, var33, var3.armorPen);
            return;
         }

         if (var13.getHitEntity() instanceof Player var26) {
            Game.PS var27 = this.players.get(var26.getUniqueId());
            double var17 = var4.toVector().distance(var14);
            double var19 = var14.getY() - var26.getLocation().getY();
            boolean var21 = var19 >= (var26.isSneaking() ? 1.15 : 1.42);
            double var22 = var21 ? (double)(var3.kind == Weapon.Kind.SHOTGUN ? 2 : 4) : (var19 < 0.7 ? 0.75 : (var19 < 1.0 ? 1.25 : 1.0));
            double var24 = var3.damage * Math.pow(var3.rangeMod, var17 * 40.0 / 500.0) * var10 * var22;
            var6.spawnParticle(Particle.BLOCK_CRACK, var14.toLocation(var6), 8, 0.05, 0.05, 0.05, Material.REDSTONE_BLOCK.createBlockData());
            this.damage(var26, var27, var1, var3, var24, var21, var3.armorPen);
            return;
         }

         Block var15 = var13.getHitBlock();
         if (var15 != null) {
            var6.spawnParticle(Particle.BLOCK_CRACK, var14.toLocation(var6), 6, 0.05, 0.05, 0.05, var15.getBlockData());
            var6.spawnParticle(Particle.SMOKE_NORMAL, var14.toLocation(var6), 1, 0.0, 0.0, 0.0, 0.01);
            if (var12 == 0) {
               var6.playSound(var14.toLocation(var6), var15.getBlockData().getSoundGroup().getHitSound(), 0.6F, 1.2F);
            }

            if (var38 < 0.0) {
               var38 = Spray.power(var3);
            }

            // kalinlik: mermi blok(lar)in icinden cikana kadar ilerle
            Vector var41 = var14.clone();
            double var42 = 0.0;
            double var43 = 0.0;
            while (var42 < 3.0) {
               var41.add(var5.clone().multiply(0.1));
               var42 += 0.1;
               Block var44 = var41.toLocation(var6).getBlock();
               if (var44.isPassable()) {
                  break;
               }

               var43 += 0.1 * Spray.hardness(var44.getType());
            }

            if (var42 < 3.0 && var43 <= var38 && var12 < 3) {
               var38 -= var43;
               var10 *= Math.max(0.1, 1.0 - (0.15 + var43 * 0.32));
               var8 -= var7.toVector().distance(var14) + var42;
               var7 = var41.toLocation(var6);
               var6.spawnParticle(Particle.BLOCK_CRACK, var7, 4, 0.05, 0.05, 0.05, var15.getBlockData());
               var12++;
               continue;
            }

            return;
         }

         return;
      }
   }

   void tracer(World var1, Vector var2, Vector var3) {
      Vector var4 = var3.clone().subtract(var2);
      double var5 = var4.length();
      if (!(var5 < 1.0)) {
         var4.normalize();

         for (double var7 = 1.5; var7 < var5; var7++) {
            var1.spawnParticle(
               Particle.REDSTONE,
               var2.getX() + var4.getX() * var7,
               var2.getY() + var4.getY() * var7,
               var2.getZ() + var4.getZ() * var7,
               1,
               0.0,
               0.0,
               0.0,
               0.0,
               new DustOptions(Color.fromRGB(255, 230, 140), 0.45F)
            );
         }
      }
   }

   boolean alive(Player var1) {
      Game.PS var2 = this.players.get(var1.getUniqueId());
      return var2 != null && var2.alive;
   }

   boolean enemies(Game.PS var1, Game.PS var2) {
      return var1 == null || var2 == null || var1 == var2 || this.mode == Game.Mode.DM || var1.group != var2.group;
   }

   void damage(Player var1, Game.PS var2, Player var3, Weapon var4, double var5, boolean var7, double var8) {
      if (var2 != null && var2.alive && this.phase != Game.Phase.FREEZE && this.phase != Game.Phase.OVER) {
         Game.PS var10 = var3 == null ? null : this.players.get(var3.getUniqueId());
         if (var10 == null || var10 == var2 || this.enemies(var10, var2)) {
            if (System.currentTimeMillis() >= var2.spawnSafeUntil || var3 == null || var3 == var1) {
               double var11 = var5;
               boolean var13 = var7 ? var2.helmet && var2.armor > 0.0 : var2.armor > 0.0;
               if (var13 && var8 < 1.0) {
                  var11 = var5 * var8;
                  double var14 = (var5 - var11) * 0.5;
                  if (var14 > var2.armor) {
                     var11 += (var14 - var2.armor) * 2.0;
                     var14 = var2.armor;
                  }

                  var2.armor = Math.max(0.0, var2.armor - var14);
                  if (var2.armor <= 0.0) {
                     var2.helmet = false;
                  }
               }

               var2.hp -= var11;
               if (var4 != null && var4.gun()) {
                  this.ccRaw(var1.getLocation(), var7 ? (var2.helmet ? "combat.bullet.headshot_armor" : "combat.bullet.headshot") : (var13 ? "combat.bullet.kevlar" : "combat.bullet.body"), var7 ? 1.4F : 1.0F);
               }

               var1.playHurtAnimation(0.0F);
               var1.getWorld()
                  .playSound(var1.getLocation(), var7 ? Sound.ENTITY_ZOMBIE_ATTACK_IRON_DOOR : Sound.ENTITY_PLAYER_HURT, var7 ? 0.6F : 1.0F, var7 ? 1.8F : 1.0F);
               if (var3 != null && var3 != var1) {
                  var3.playSound(var3.getLocation(), var7 ? Sound.BLOCK_NOTE_BLOCK_BELL : Sound.ENTITY_ARROW_HIT_PLAYER, 0.7F, var7 ? 1.8F : 1.2F);
               }

               if (var2.hp <= 0.0) {
                  this.kill(var1, var2, var3, var10, var4, var7);
               } else {
                  var1.setHealth(Math.max(0.5, Math.min(20.0, var2.hp / 5.0)));
               }
            }
         }
      }
   }

   void kill(Player var1, Game.PS var2, Player var3, Game.PS var4, Weapon var5, boolean var6) {
      var2.alive = false;
      var2.hp = 0.0;
      var2.deaths++;
      if (var2.scoped) {
         this.unscope(var1, var2);
      }

      String var7 = var5 == null ? "dünya" : var5.name;
      if (var4 != null && var4 != var2) {
         var4.kills++;
         var4.roundKills++;
         if (var6) {
            var4.hs++;
         }

         int var8 = var5 == null ? 300 : var5.reward;
         if (!this.unlimited) {
            var4.money = Math.min(16000, var4.money + var8);
         }

         if (var3 != null) {
            var3.playSound(var3.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0F, 1.4F);
            if (!this.unlimited) {
               var3.sendMessage("§a+$" + var8 + " §7(" + var7 + " ile öldürme)");
            }
         }
      }

      String var10 = var4 == null ? "" : this.color(var4);
      String var9 = this.color(var2);
      this.say((var4 != null && var4 != var2 ? var10 + var4.name + " " : "") + "§7[" + var7 + "]" + (var6 ? " §c⊕" : "") + " " + var9 + var2.name);
      this.dropAll(var1, var2);
      var1.getInventory().clear();
      var1.setHealth(20.0);
      var1.setGameMode(GameMode.SPECTATOR);
      var1.sendTitle("§cÖldün", var3 != null && var3 != var1 ? "§7" + var3.getName() + " · " + var7 + (var6 ? " · kafadan" : "") : "", 5, 40, 10);
      if (this.mode == Game.Mode.DM) {
         var2.respawnAt = System.currentTimeMillis() + 2500L;
         if (var4 != null && var4.kills >= this.toWin) {
            this.end(var4.name + " " + var4.kills + " öldürmeyle kazandı");
         }
      } else {
         this.checkRoundEnd();
      }
   }

   String color(Game.PS var1) {
      return var1.group < 0 ? "§f" : (this.groupSide[var1.group] == 0 ? "§6" : "§b");
   }

   void dropAll(Player var1, Game.PS var2) {
      if (this.phase != Game.Phase.OVER && this.built != null) {
         PlayerInventory var3 = var1.getInventory();
         ItemStack var4 = this.firstOf(var3, Weapon.Slot.PRIMARY);
         ItemStack var5 = this.firstOf(var3, Weapon.Slot.SECONDARY);
         ItemStack var6 = this.firstOf(var3, Weapon.Slot.BOMB);
         ItemStack var7 = var4 != null ? var4 : var5;
         if (var7 != null && this.mode != Game.Mode.DM) {
            var1.getWorld().dropItemNaturally(var1.getLocation().add(0.0, 0.5, 0.0), var7.clone());
         }

         if (var6 != null) {
            var1.getWorld().dropItemNaturally(var1.getLocation().add(0.0, 0.5, 0.0), var6.clone());
            this.say("§6Bomba düştü!");
         }
      }
   }

   ItemStack firstOf(PlayerInventory var1, Weapon.Slot var2) {
      for (ItemStack var6 : var1.getContents()) {
         Weapon var7 = this.plugin.weaponOf(var6);
         if (var7 != null && var7.slot == var2) {
            return var6;
         }
      }

      return null;
   }

   void spawnDM(Game.PS var1, boolean var2) {
      Player var3 = Bukkit.getPlayer(var1.id);
      if (var3 != null) {
         List<Location> var4 = this.built.anywhere();
         Location var5 = (Location)var4.get(0);
         double var6 = -1.0;

         for (Location var9 : var4.subList(0, Math.min(25, var4.size()))) {
            double var10 = 1.7976931348623157E308;

            for (Game.PS var13 : this.players.values()) {
               Player var14 = Bukkit.getPlayer(var13.id);
               if (var13 != var1 && var13.alive && var14 != null) {
                  var10 = Math.min(var10, var14.getLocation().distanceSquared(var9));
               }
            }

            if (var10 > var6) {
               var6 = var10;
               var5 = var9;
            }
         }

         var1.alive = true;
         var1.hp = 100.0;
         var1.armor = 100.0;
         var1.helmet = true;
         var1.respawnAt = 0L;
         var1.spawnSafeUntil = System.currentTimeMillis() + 1500L;
         var3.getInventory().clear();
         var3.teleport(var5);
         var3.setGameMode(GameMode.ADVENTURE);
         var3.setHealth(20.0);
         var3.setFoodLevel(20);
         this.board.getTeam("ffa").addEntry(var3.getName());
         PlayerInventory var15 = var3.getInventory();
         var15.setItem(0, this.newWeapon(Weapon.get(this.rnd.nextBoolean() ? "ak47" : "m4a4")));
         var15.setItem(1, this.newWeapon(Weapon.get("deagle")));
         var15.setItem(2, this.plugin.item(Weapon.get("knife"), null));
         var15.setItem(8, this.plugin.shopItem());
         var15.setHeldItemSlot(0);
         this.speed(var3);
      }
   }

   int[] siteAt(Location var1) {
      if (this.built.inside(var1, this.map.siteA)) {
         return this.map.siteA;
      } else {
         return this.built.inside(var1, this.map.siteB) ? this.map.siteB : null;
      }
   }

   void plantPress(Player var1) {
      Game.PS var2 = this.players.get(var1.getUniqueId());
      if (var2 != null && var2.alive && this.phase == Game.Phase.LIVE && this.mode == Game.Mode.COMP) {
         if (this.siteAt(var1.getLocation()) == null) {
            this.actionbar(var1, "§cBombayı sadece §fA §cya da §fB §cbölgesine kurabilirsin.");
         } else if (var1.isOnGround()) {
            var2.plantPress = System.currentTimeMillis();
         }
      }
   }

   void tickPlant(Player var1, Game.PS var2, long var3) {
      if (var3 - var2.plantPress <= 300L && this.phase == Game.Phase.LIVE) {
         var2.plantTicks++;
         if (var2.plantTicks % 8 == 1) {
            var1.getWorld().playSound(var1.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1.0F, 1.6F);
         }

         this.actionbar(var1, "§6Bomba kuruluyor " + bar((double)var2.plantTicks / 64.0));
         if (var2.plantTicks >= 64) {
            var2.plantTicks = 0;
            ItemStack var5 = this.firstOf(var1.getInventory(), Weapon.Slot.BOMB);
            if (var5 != null) {
               var1.getInventory().remove(var5);
            }

            Block var6 = var1.getLocation().getBlock();
            var6.setType(Material.TNT, false);
            this.bomb = var6.getLocation();
            this.planter = var2.id;
            this.plantedThisRound = true;
            if (!this.unlimited) {
               var2.money = Math.min(16000, var2.money + 300);
            }

            this.phase = Game.Phase.PLANTED;
            this.ticks = 800;
            String var7 = this.siteAt(var1.getLocation()) == this.map.siteA ? "A" : "B";

            for (Player var9 : this.online()) {
               var9.sendTitle("§c§lBOMBA KURULDU", "§7" + var7 + " bölgesi · 40 saniye", 5, 40, 10);
            }

            this.sound(Sound.ENTITY_TNT_PRIMED, 1.0F);
         }
      } else {
         if (var2.plantTicks > 0) {
            this.actionbar(var1, "§cKurulum yarıda kaldı");
         }

         var2.plantTicks = 0;
      }
   }

   void defusePress(Player var1, Block var2) {
      Game.PS var3 = this.players.get(var1.getUniqueId());
      if (var3 != null && var3.alive && this.phase == Game.Phase.PLANTED && this.sideOf(var3) == 1 && this.bomb != null) {
         if (var2 == null || var2.getLocation().equals(this.bomb)) {
            if (!(var1.getLocation().distance(this.bomb.clone().add(0.5, 0.0, 0.5)) > 2.5)) {
               if (this.defuser == null || this.defuser.equals(var3.id)) {
                  this.defuser = var3.id;
                  var3.defusePress = System.currentTimeMillis();
               }
            }
         }
      }
   }

   void tickDefuse(Player var1, Game.PS var2, long var3) {
      if (var3 - var2.defusePress <= 300L && this.phase == Game.Phase.PLANTED) {
         int var5 = var2.kit ? 100 : 200;
         var2.defuseTicks++;
         if (var2.defuseTicks == 1) {
            this.say("§b" + var2.name + " bombayı imha ediyor" + (var2.kit ? " (kitli)" : ""));
         }

         if (var2.defuseTicks % 10 == 1) {
            var1.getWorld().playSound(var1.getLocation(), Sound.BLOCK_CHAIN_PLACE, 1.0F, 1.4F);
         }

         this.actionbar(var1, "§bİmha ediliyor " + bar((double)var2.defuseTicks / (double)var5));
         if (var2.defuseTicks >= var5) {
            var2.defuseTicks = 0;
            this.bomb.getBlock().setType(Material.AIR);
            this.bomb = null;
            if (!this.unlimited) {
               var2.money = Math.min(16000, var2.money + 300);
            }

            this.roundOver(var2.group, "bomba imha edildi");
         }
      } else {
         if (var2.defuseTicks > 0) {
            this.actionbar(var1, "§cİmha yarıda kaldı");
         }

         var2.defuseTicks = 0;
         if (var2.id.equals(this.defuser)) {
            this.defuser = null;
         }
      }
   }

   void tickBomb() {
      if (this.bomb != null) {
         int var1 = this.ticks;
         int var2 = var1 > 400 ? 20 : (var1 > 200 ? 10 : (var1 > 60 ? 5 : 2));
         if (var1 % var2 == 0) {
            this.bomb.getWorld().playSound(this.bomb, Sound.BLOCK_NOTE_BLOCK_PLING, 1.4F, 2.0F);
            this.bomb
               .getWorld()
               .spawnParticle(Particle.REDSTONE, this.bomb.clone().add(0.5, 1.1, 0.5), 3, 0.1, 0.05, 0.1, 0.0, new DustOptions(Color.RED, 1.0F));
         }

         if (var1 <= 0) {
            Location var3 = this.bomb.clone().add(0.5, 0.5, 0.5);
            this.bomb.getBlock().setType(Material.AIR);
            this.bomb = null;
            var3.getWorld().spawnParticle(Particle.EXPLOSION_HUGE, var3, 6, 3.0, 2.0, 3.0, 0.0);
            var3.getWorld().playSound(var3, Sound.ENTITY_GENERIC_EXPLODE, 6.0F, 0.5F);
            Player var4 = this.planter == null ? null : Bukkit.getPlayer(this.planter);

            for (Game.PS var6 : new ArrayList<>(this.players.values())) {
               Player var7 = Bukkit.getPlayer(var6.id);
               if (var7 != null && var6.alive) {
                  double var8 = var7.getLocation().distance(var3);
                  if (var8 < 18.0) {
                     var6.armor = 0.0;
                     this.damageRaw(var7, var6, var4, 500.0 * Math.max(0.0, 1.0 - var8 / 18.0));
                  }
               }
            }

            for (Bot var10 : new ArrayList<>(this.bots)) {
               if (var10.on()) {
                  double var11 = var10.ent.getLocation().distance(var3);
                  if (var11 < 18.0) {
                     var10.ps.armor = 0.0;
                     this.hitBy(var10.ps, null, null, Weapon.get("c4"), 500.0 * Math.max(0.0, 1.0 - var11 / 18.0), false, 1.0);
                  }
               }
            }

            if (this.phase == Game.Phase.PLANTED) {
               this.roundOver(this.groupOfSide(0), "bomba patladı");
            }
         }
      }
   }

   void damageRaw(Player var1, Game.PS var2, Player var3, double var4) {
      Weapon var6 = Weapon.get("c4");
      if (var2.hp - var4 <= 0.0) {
         this.kill(var1, var2, var3, var3 == null ? null : this.players.get(var3.getUniqueId()), var6, false);
      } else {
         var2.hp -= var4;
         var1.setHealth(Math.max(0.5, var2.hp / 5.0));
         var1.playHurtAnimation(0.0F);
      }
   }

   static String bar(double var0) {
      int var2 = (int)Math.round(Math.max(0.0, Math.min(1.0, var0)) * 20.0);
      return "§a" + "|".repeat(var2) + "§7" + "|".repeat(20 - var2);
   }

   void knife(Player var1, Player var2, boolean var3) {
      Game.PS var4 = this.players.get(var1.getUniqueId());
      Game.PS var5 = this.players.get(var2.getUniqueId());
      if (var4 != null && var5 != null && var4.alive && var5.alive && this.canShoot()) {
         long var6 = System.currentTimeMillis();
         if (var6 >= var4.knifeReady) {
            var4.knifeReady = var6 + (long)(var3 ? 1000 : 400);
            Vector var8 = var2.getLocation().getDirection().setY(0);
            Vector var9 = var2.getLocation().toVector().subtract(var1.getLocation().toVector()).setY(0);
            boolean var10 = var8.lengthSquared() > 0.0 && var9.lengthSquared() > 0.0 && var8.normalize().dot(var9.normalize()) > 0.5;
            // Bicak her vurusta tek atar (zirhli rakip dahil). Eski CS2 degerleri: sag tik 65/180, sol tik 40/90.
            double var11 = 1000.0;
            if (this.ccOn()) {
               this.ccRaw(var1.getLocation(), "weapon.knife.hit_flesh", 1.0F);
            } else {
               var1.getWorld().playSound(var1.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0F, 1.4F);
            }
            this.damage(var2, var5, var1, Weapon.get("knife"), var11, false, 0.85);
         }
      }
   }

   /** Bicakla bota vurus: tek atar (bekleme suresi oyuncuyla ayni). */
   void knifeBot(Player var1, Bot var2) {
      Game.PS var3 = this.players.get(var1.getUniqueId());
      if (var3 != null && var3.alive && var2.ps.alive && this.canShoot()) {
         long var4 = System.currentTimeMillis();
         if (var4 >= var3.knifeReady) {
            var3.knifeReady = var4 + 400L;
            if (this.ccOn()) {
               this.ccRaw(var1.getLocation(), "weapon.knife.hit_flesh", 1.0F);
            } else {
               var1.getWorld().playSound(var1.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0F, 1.4F);
            }
            this.hitBy(var2.ps, var3, var1, Weapon.get("knife"), 1000.0, false, 0.85);
         }
      }
   }

   void stab(Player var1) {
      RayTraceResult var2 = var1.getWorld()
         .rayTrace(
            var1.getEyeLocation(),
            var1.getEyeLocation().getDirection(),
            2.0,
            FluidCollisionMode.NEVER,
            true,
            0.2,
            var2x -> var2x instanceof Player && !var2x.equals(var1) && this.alive((Player)var2x) || Bot.isBot(var2x) && this.botOf(var2x) != null && this.botOf(var2x).ps.alive
         );
      if (var2 != null && var2.getHitEntity() instanceof Player var3) {
         this.knife(var1, var3, true);
      } else if (var2 != null && var2.getHitEntity() != null && this.botOf(var2.getHitEntity()) != null) {
         this.knifeBot(var1, this.botOf(var2.getHitEntity()));
      }
   }

   void zeus(Player var1) {
      Game.PS var2 = this.players.get(var1.getUniqueId());
      if (var2 != null && var2.alive && this.canShoot()) {
         ItemStack var3 = this.firstOf(var1.getInventory(), Weapon.Slot.GEAR);
         if (var3 != null) {
            var1.getInventory().remove(var3);
            var1.getWorld().playSound(var1.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 1.0F, 2.0F);
            RayTraceResult var4 = var1.getWorld()
               .rayTrace(
                  var1.getEyeLocation(),
                  var1.getEyeLocation().getDirection(),
                  4.5,
                  FluidCollisionMode.NEVER,
                  true,
                  0.25,
                  var2x -> var2x instanceof Player && !var2x.equals(var1) && this.alive((Player)var2x) || Bot.isBot(var2x) && this.botOf(var2x) != null && this.botOf(var2x).ps.alive
               );
            Vector var5 = var4 == null ? var1.getEyeLocation().toVector().add(var1.getEyeLocation().getDirection().multiply(4.5)) : var4.getHitPosition();
            this.tracer(var1.getWorld(), var1.getEyeLocation().toVector(), var5);
            if (var4 != null && var4.getHitEntity() instanceof Player var6) {
               this.damage(var6, this.players.get(var6.getUniqueId()), var1, Weapon.get("zeus"), 500.0, false, 1.0);
            }
            Bot var7 = var4 == null || var4.getHitEntity() == null ? null : this.botOf(var4.getHitEntity());
            if (var7 != null) {
               this.hitBy(var7.ps, var2, var1, Weapon.get("zeus"), 500.0, false, 1.0);
            }
         }
      }
   }

   void throwGrenade(Player var1, ItemStack var2, Weapon var3) {
      Game.PS var4 = this.players.get(var1.getUniqueId());
      if (var4 != null && var4.alive && this.canShoot()) {
         var2.setAmount(var2.getAmount() - 1);
         ItemStack var5 = new ItemStack(var3.item);
         Item var6 = var1.getWorld().dropItem(var1.getEyeLocation().add(var1.getLocation().getDirection().multiply(0.4)), var5);
         var6.setPickupDelay(2147483647);
         var6.setUnlimitedLifetime(true);
         var6.setVelocity(var1.getLocation().getDirection().multiply(1.45).add(new Vector(0.0, 0.2, 0.0)));
         this.grenades.add(new Game.Grenade(var6, var3, var1.getUniqueId()));
         var1.getWorld().playSound(var1.getLocation(), Sound.ENTITY_SNOWBALL_THROW, 1.0F, 0.7F);
         String var10001 = this.color(var4);
         String var10002 = var4.name;
         String var7 = var3.id;

         this.say(var10001 + var10002 + " §7» " + switch (var7) {
            case "he" -> "§cEl bombası!";
            case "flash" -> "§fFlaş!";
            case "smoke" -> "§7Sis!";
            default -> "§6Molotof!";
         });
      }
   }

   void tickGrenades() {
      Iterator var1 = this.grenades.iterator();

      while (var1.hasNext()) {
         Game.Grenade var2 = (Game.Grenade)var1.next();
         var2.age++;
         if (!var2.item.isValid()) {
            var1.remove();
         } else {
            Location var3 = var2.item.getLocation();
            boolean var4 = var2.item.isOnGround() && var2.age > 8;
            String var5 = var2.w.id;
            switch (var5) {
               case "he":
                  if (var2.age >= 32) {
                     this.explodeHE(var3, var2.owner);
                     var2.item.remove();
                     var1.remove();
                  }
                  break;
               case "flash":
                  if (var2.age >= 30) {
                     this.flash(var3);
                     var2.item.remove();
                     var1.remove();
                  }
                  break;
               case "smoke":
                  if (var4 && var2.item.getVelocity().lengthSquared() < 0.01 || var2.age > 70) {
                     this.smokes.add(new Game.Area(var3, 360, var2.owner, 4.2));
                     this.fires.removeIf(var1x -> var1x.at.distance(var3) < 5.5);
                     var3.getWorld().playSound(var3, Sound.BLOCK_FIRE_EXTINGUISH, 1.5F, 0.6F);
                     var2.item.remove();
                     var1.remove();
                  }
                  break;
               default:
                  if (var4 || var2.age > 50) {
                     var2.item.remove();
                     var1.remove();
                     if (this.smokes.stream().anyMatch(var1x -> var1x.at.distance(var3) < var1x.r + 1.0)) {
                        var3.getWorld().playSound(var3, Sound.BLOCK_FIRE_EXTINGUISH, 1.0F, 1.0F);
                     } else {
                        this.fires.add(new Game.Area(var3, 140, var2.owner, 3.0));
                        var3.getWorld().playSound(var3, Sound.ITEM_FIRECHARGE_USE, 1.5F, 0.8F);
                     }
                  }
            }
         }
      }

      var1 = this.smokes.iterator();

      while (var1.hasNext()) {
         Game.Area var14 = (Game.Area)var1.next();
         var14.left--;
         if (var14.left <= 0) {
            var1.remove();
         } else if (var14.left % 3 == 0) {
            double var16 = var14.left > 320 ? var14.r * (double)(360 - var14.left) / 40.0 : var14.r;

            for (int var21 = 0; var21 < 18; var21++) {
               double var23 = (this.rnd.nextDouble() * 2.0 - 1.0) * var16;
               double var8 = this.rnd.nextDouble() * 2.6;
               double var10 = (this.rnd.nextDouble() * 2.0 - 1.0) * var16;
               if (!(var23 * var23 + var10 * var10 > var16 * var16)) {
                  var14.at
                     .getWorld()
                     .spawnParticle(
                        Particle.CAMPFIRE_COSY_SMOKE, var14.at.getX() + var23, var14.at.getY() + var8, var14.at.getZ() + var10, 0, 0.0, 0.004, 0.0, 1.0
                     );
               }
            }
         }
      }

      var1 = this.fires.iterator();

      while (var1.hasNext()) {
         Game.Area var15 = (Game.Area)var1.next();
         var15.left--;
         if (var15.left <= 0) {
            var1.remove();
         } else {
            if (var15.left % 2 == 0) {
               for (int var17 = 0; var17 < 6; var17++) {
                  double var19 = this.rnd.nextDouble() * 3.141592653589793 * 2.0;
                  double var24 = Math.sqrt(this.rnd.nextDouble()) * var15.r;
                  var15.at
                     .getWorld()
                     .spawnParticle(
                        Particle.FLAME,
                        var15.at.getX() + Math.cos(var19) * var24,
                        var15.at.getY() + 0.1,
                        var15.at.getZ() + Math.sin(var19) * var24,
                        0,
                        0.0,
                        0.05,
                        0.0,
                        1.0
                     );
               }
            }

            if (var15.left % 20 == 0) {
               var15.at.getWorld().playSound(var15.at, Sound.BLOCK_FIRE_AMBIENT, 1.5F, 1.0F);
            }

            if (var15.left % 8 == 0) {
               Player var18 = Bukkit.getPlayer(var15.owner);

               for (Game.PS var22 : new ArrayList<>(this.players.values())) {
                  Player var25 = Bukkit.getPlayer(var22.id);
                  if (var25 != null && var22.alive) {
                     Location var7 = var25.getLocation();
                     if (Math.hypot(var7.getX() - var15.at.getX(), var7.getZ() - var15.at.getZ()) < var15.r && Math.abs(var7.getY() - var15.at.getY()) < 2.0) {
                        this.damage(var25, var22, var18, Weapon.get("molotov"), 16.0, false, 1.0);
                     }
                  }
               }
            }
         }
      }
   }

   void explodeHE(Location var1, UUID var2) {
      for (Bot var12 : new ArrayList<>(this.bots)) {
         if (var12.on()) {
            double var13 = var12.ent.getLocation().add(0.0, 1.0, 0.0).distance(var1);
            if (var13 <= 8.0 && this.sees(var1, var12.ent.getEyeLocation())) {
               this.hitBy(var12.ps, this.players.get(var2), Bukkit.getPlayer(var2), Weapon.get("he"), 98.0 * (1.0 - var13 / 8.0), false, 0.5);
            }
         }
      }

      var1.getWorld().spawnParticle(Particle.EXPLOSION_LARGE, var1, 3, 0.6, 0.4, 0.6, 0.0);
      var1.getWorld().playSound(var1, Sound.ENTITY_GENERIC_EXPLODE, 3.0F, 1.1F);
      Player var3 = Bukkit.getPlayer(var2);

      for (Game.PS var5 : new ArrayList<>(this.players.values())) {
         Player var6 = Bukkit.getPlayer(var5.id);
         if (var6 != null && var5.alive) {
            double var7 = var6.getLocation().add(0.0, 1.0, 0.0).distance(var1);
            if (Boolean.getBoolean("strike.debug")) {
               this.plugin
                  .getLogger()
                  .info(
                     "HE "
                        + var6.getName()
                        + " d="
                        + var7
                        + " sees="
                        + this.sees(var1, var6.getEyeLocation())
                        + "/"
                        + this.sees(var1, var6.getLocation().add(0.0, 0.3, 0.0))
                        + " at="
                        + var1.toVector()
                  );
            }

            if (!(var7 > 8.0) && (this.sees(var1, var6.getEyeLocation()) || this.sees(var1, var6.getLocation().add(0.0, 0.3, 0.0)))) {
               this.damage(var6, var5, var3, Weapon.get("he"), 98.0 * (1.0 - var7 / 8.0), false, 0.5);
            }
         }
      }
   }

   void flash(Location var1) {
      var1.getWorld().spawnParticle(Particle.FLASH, var1, 1);
      var1.getWorld().playSound(var1, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 3.0F, 1.6F);

      for (Game.PS var3 : this.players.values()) {
         Player var4 = Bukkit.getPlayer(var3.id);
         if (var4 != null && var3.alive) {
            double var5 = var4.getEyeLocation().distance(var1);
            if (!(var5 > 28.0) && this.sees(var1, var4.getEyeLocation())) {
               Vector var7 = var1.toVector().subtract(var4.getEyeLocation().toVector()).normalize();
               double var8 = var4.getEyeLocation().getDirection().dot(var7);
               double var10 = var8 > 0.55 ? 4.5 - var5 * 0.12 : (var8 > -0.1 ? 1.8 - var5 * 0.04 : 0.6);
               if (!(var10 <= 0.2)) {
                  var4.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, (int)(var10 * 20.0), 1, false, false, false));
                  var4.sendTitle("§f████████", "", 0, (int)(var10 * 10.0), (int)(var10 * 10.0));
                  var4.playSound(var4.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 0.6F, 2.0F);
               }
            }
         }
      }
   }

   boolean sees(Location var1, Location var2) {
      Vector var3 = var2.toVector().subtract(var1.toVector());
      double var4 = var3.length();
      if (var4 < 0.1) {
         return true;
      } else {
         RayTraceResult var6 = var1.getWorld().rayTraceBlocks(var1, var3.normalize(), var4, FluidCollisionMode.NEVER, true);
         return var6 == null;
      }
   }

   boolean canBuy(Player var1, Game.PS var2, boolean var3) {
      if (var2 != null && var2.alive) {
         if (this.mode != Game.Mode.DM && (!this.anyItem || !this.unlimited)) {
            boolean var4 = this.phase == Game.Phase.FREEZE || this.phase == Game.Phase.LIVE && this.roundTicks() - this.ticks < 400;
            boolean var5 = this.built.inside(var1.getLocation(), grow(this.sideOf(var2) == 1 ? this.map.ctZone : this.map.tZone));
            if (!var4 && var3) {
               var1.sendMessage("§cSatın alma süresi bitti.");
            } else if (!var5 && var3) {
               var1.sendMessage("§cSadece doğduğun bölgede satın alabilirsin.");
            }

            return var4 && var5;
         } else {
            return true;
         }
      } else {
         return false;
      }
   }

   static int[] grow(int[] var0) {
      return new int[]{var0[0] - 1, var0[1] - 1, var0[2] + 1, var0[3] + 1};
   }

   void buy(Player var1, Weapon var2) {
      Game.PS var3 = this.players.get(var1.getUniqueId());
      if (this.canBuy(var1, var3, true)) {
         int var4 = this.sideOf(var3);
         if ((var2.side != Weapon.Side.T || var4 != 1) && (var2.side != Weapon.Side.CT || var4 != 0)) {
            if (!this.unlimited && var3.money < var2.price) {
               var1.sendMessage("§cParan yetmiyor ($" + var2.price + ").");
               var1.playSound(var1.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0F, 1.0F);
            } else {
               PlayerInventory var5 = var1.getInventory();
               String var6 = var2.id;
               byte var7 = -1;
               switch (var6.hashCode()) {
                  case -1220934547:
                     if (var6.equals("helmet")) {
                        var7 = 1;
                     }
                     break;
                  case -1134750271:
                     if (var6.equals("kevlar")) {
                        var7 = 0;
                     }
                     break;
                  case 106198:
                     if (var6.equals("kit")) {
                        var7 = 2;
                     }
                     break;
                  case 3735305:
                     if (var6.equals("zeus")) {
                        var7 = 3;
                     }
               }

               label191: {
                  int var8;
                  int var9;
                  switch (var7) {
                     case 0:
                        if (var3.armor >= 100.0) {
                           var1.sendMessage("§7Zaten yeleğin var.");
                           return;
                        }

                        var3.armor = 100.0;
                        break label191;
                     case 1:
                        if (var3.armor >= 100.0 && var3.helmet) {
                           var1.sendMessage("§7Zaten yelek ve kaskın var.");
                           return;
                        }

                        var3.armor = 100.0;
                        var3.helmet = true;
                        var5.setHelmet(new ItemStack(Material.IRON_HELMET));
                        break label191;
                     case 2:
                        if (var3.kit) {
                           var1.sendMessage("§7Zaten kitin var.");
                           return;
                        }

                        var3.kit = true;
                        var5.setItem(5, this.plugin.item(var2, null));
                        break label191;
                     case 3:
                        if (this.firstOf(var5, Weapon.Slot.GEAR) != null) {
                           var1.sendMessage("§7Zaten bir Zeus'un var.");
                           return;
                        }

                        var5.setItem(6, this.plugin.item(var2, null));
                        break label191;
                     default:
                        if (!var2.grenade()) {
                           var8 = var2.slot == Weapon.Slot.PRIMARY ? 0 : 1;
                           ItemStack var16 = this.firstOf(var5, var2.slot);
                           if (var16 != null) {
                              var5.remove(var16);
                              if (this.mode != Game.Mode.DM) {
                                 var1.getWorld().dropItemNaturally(var1.getLocation().add(0.0, 0.5, 0.0), var16);
                              }
                           }

                           var5.setItem(var8, this.newWeapon(var2));
                           var5.setHeldItemSlot(var8);
                           this.speed(var1);
                           break label191;
                        }

                        var8 = 0;
                        var9 = 0;
                  }

                  for (ItemStack var13 : var5.getContents()) {
                     Weapon var14 = this.plugin.weaponOf(var13);
                     if (var14 != null && var14.grenade()) {
                        var8 += var13.getAmount();
                        if (var14 == var2) {
                           var9 += var13.getAmount();
                        }
                     }
                  }

                  if (var8 >= 4 || var9 >= (var2.id.equals("flash") ? 2 : 1)) {
                     var1.sendMessage("§cDaha fazla taşıyamazsın.");
                     return;
                  }

                  ItemStack var17 = null;

                  for (ItemStack var22 : var5.getContents()) {
                     if (this.plugin.weaponOf(var22) == var2) {
                        var17 = var22;
                     }
                  }

                  if (var17 != null) {
                     var17.setAmount(var17.getAmount() + 1);
                  } else {
                     int var19 = 3;

                     while (var19 < 8 && var5.getItem(var19) != null && var19 != 4) {
                        var19++;
                     }

                     if (var19 == 4) {
                        var19 = 5;
                     }

                     while (var19 < 8 && var5.getItem(var19) != null) {
                        var19++;
                     }

                     if (var19 >= 8) {
                        var1.sendMessage("§cYer yok.");
                        return;
                     }

                     var5.setItem(var19, this.plugin.item(var2, null));
                  }
               }

               if (!this.unlimited) {
                  var3.money = var3.money - var2.price;
               }

               var1.playSound(var1.getLocation(), Sound.ITEM_ARMOR_EQUIP_IRON, 1.0F, 1.2F);
            }
         } else {
            var1.sendMessage("§cBu senin tarafının silahı değil.");
         }
      }
   }

   void hud(Player var1, Game.PS var2) {
      StringBuilder var3 = new StringBuilder();
      var3.append("§a$").append(this.unlimited ? "∞" : String.valueOf(var2.money));
      if (var2.alive) {
         ItemStack var4 = var1.getInventory().getItemInMainHand();
         Weapon var5 = this.plugin.weaponOf(var4);
         String var6 = this.plugin.uidOf(var4);
         if (var5 != null) {
            var3.append("   §f").append(var5.name);
            if (var5.gun() && var6 != null) {
               int[] var7 = this.ammo.get(var6);
               if (var2.reloadingItem != null) {
                  var3.append(" §e[şarjör değişiyor]");
               } else if (var7 != null) {
                  var3.append(" ").append(var7[0] == 0 ? "§c" : (var7[0] <= var5.mag / 4 ? "§e" : "§f")).append(var7[0]).append("§7/").append(var7[1]);
               }

               if (var2.scoped) {
                  var3.append(" §b[dürbün]");
               }
            }
         }

         var3.append("   §c❤ ").append((int)Math.ceil(var2.hp)).append("  §9⛨ ").append((int)var2.armor).append(var2.helmet ? "+K" : "");
         int[] var8 = this.mode == Game.Mode.COMP ? this.siteAt(var1.getLocation()) : null;
         if (var8 != null) {
            var3.append("   §e[").append(var8 == this.map.siteA ? "A" : "B").append(" BÖLGESİ]");
         }
      } else {
         var3.append("   §7ölü — izliyorsun");
      }

      var1.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(var3.toString()));
   }

   void actionbar(Player var1, String var2) {
      var1.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(var2));
   }

   void updateBoard() {
      int var1 = Math.max(0, this.ticks / 20);
      String var2 = var1 / 60 + ":" + String.format("%02d", var1 % 60);
      String var3;
      switch (this.phase) {
         case FREEZE:
            var3 = "§eSatın alma · " + var2;
            this.bar.setColor(BarColor.YELLOW);
            break;
         case LIVE:
         default:
            var3 = (this.mode == Game.Mode.DM ? "§fÖlüm maçı · " : "§fTur " + this.round + " · ") + var2;
            this.bar.setColor(BarColor.GREEN);
            break;
         case PLANTED:
            var3 = "§c§lBOMBA KURULU · " + var2;
            this.bar.setColor(BarColor.RED);
            break;
         case ROUND_END:
            var3 = "§7Tur bitti";
            this.bar.setColor(BarColor.WHITE);
      }

      if (this.mode != Game.Mode.DM) {
         var3 = var3 + "   §6T " + this.score[this.groupOfSide(0)] + " §7- §b" + this.score[this.groupOfSide(1)] + " CT";
      }

      this.bar.setTitle(var3);
      int var4 = this.phase == Game.Phase.FREEZE ? 200 : (this.phase == Game.Phase.PLANTED ? 800 : (this.mode == Game.Mode.DM ? 12000 : this.roundTicks()));
      this.bar.setProgress(Math.max(0.0, Math.min(1.0, (double)this.ticks / (double)var4)));
      Objective var5 = this.board.getObjective("strike");
      if (var5 != null) {
         for (String var7 : new ArrayList<>(this.board.getEntries())) {
            if (var7.startsWith("§r")) {
               this.board.resetScores(var7);
            }
         }

         ArrayList<String> var11 = new ArrayList<>();
         var11.add("§r§7Harita: §f" + this.map.name);

         var11.add("§r§7Mod: §f" + switch (this.mode) {
            case COMP -> "Rekabetçi";
            case DUEL -> "Düello";
            case DM -> "Ölüm maçı";
         });
         if (this.mode != Game.Mode.DM) {
            var11.add("§r§7Tur: §f" + this.round + " §8(ilk " + this.toWin + ")");
            var11.add("§r§6T  §f" + this.score[this.groupOfSide(0)] + "§r");
            var11.add("§r§bCT §f" + this.score[this.groupOfSide(1)] + "§r§r");
         }

         var11.add("§r ");
         ArrayList<Game.PS> var12 = new ArrayList<>(this.players.values());
         var12.sort((var0, var1x) -> var1x.kills - var0.kills);

         for (Game.PS var9 : var12.subList(0, Math.min(6, var12.size()))) {
            var11.add("§r" + this.color(var9) + (var9.alive ? "" : "§m") + var9.name + " §7" + var9.kills + "/" + var9.deaths);
         }

         int var13 = var11.size();

         for (String var10 : var11) {
            var5.getScore(var10.length() > 40 ? var10.substring(0, 40) : var10).setScore(var13--);
         }
      }
   }

   List<Player> online() {
      ArrayList var1 = new ArrayList();

      for (UUID var3 : this.players.keySet()) {
         Player var4 = Bukkit.getPlayer(var3);
         if (var4 != null) {
            var1.add(var4);
         }
      }

      return var1;
   }

   void say(String var1) {
      for (Player var3 : this.online()) {
         var3.sendMessage(var1);
      }
   }

   void sound(Sound var1, float var2) {
      for (Player var4 : this.online()) {
         var4.playSound(var4.getLocation(), var1, 1.0F, var2);
      }
   }

   boolean frozen() {
      return this.phase == Game.Phase.FREEZE;
   }

   static final class Area {
      Location at;
      int left;
      UUID owner;
      double r;

      Area(Location var1, int var2, UUID var3, double var4) {
         this.at = var1;
         this.left = var2;
         this.owner = var3;
         this.r = var4;
      }
   }

   static final class Grenade {
      Item item;
      Weapon w;
      UUID owner;
      int age;

      Grenade(Item var1, Weapon var2, UUID var3) {
         this.item = var1;
         this.w = var2;
         this.owner = var3;
      }
   }

   static enum Mode {
      COMP,
      DUEL,
      DM;
   }

   static final class PS {
      final UUID id;
      final String name;
      int group;
      int money = 800;
      int kills;
      int deaths;
      int hs;
      int roundKills;
      double hp = 100.0;
      double armor;
      boolean helmet;
      boolean kit;
      boolean alive;
      long triggerUntil;
      long lastTrigger;
      long nextShot;
      long reloadUntil;
      long knifeReady;
      long plantPress;
      long defusePress;
      long respawnAt;
      long spawnSafeUntil;
      boolean pressUsed;
      int shotRequests;
      boolean scoped;
      String reloadingItem;
      int spray;
      int plantTicks;
      int defuseTicks;
      Location lastLoc;

      /** bot ise kendisi, gercek oyuncuysa null */
      Bot bot;

      PS(Player var1) {
         this.id = var1.getUniqueId();
         this.name = var1.getName();
      }

      PS(UUID var1, String var2) {
         this.id = var1;
         this.name = var2;
      }
   }

   static enum Phase {
      BUILDING,
      FREEZE,
      LIVE,
      PLANTED,
      ROUND_END,
      OVER;
   }
}
