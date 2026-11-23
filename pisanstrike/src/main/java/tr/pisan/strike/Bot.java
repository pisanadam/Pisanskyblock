package tr.pisan.strike;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * Sunucu tarafli bot oyuncu. Gorunus olarak takim renginde zirhli, elinde 3D silah tutan bir zombi
 * (yapay zekasi kapatilir, hareketi ve nisani bu sinif yonetir). Oyun mantiginda normal bir oyuncu
 * gibi Game.players icinde bir PS olarak yer alir; skor, tur bitisi ve oldurme akisi ayni kalir.
 */
final class Bot {
   static final String TAG = "pstrike_bot";
   static final String[] DIFF_NAMES = {"Kolay", "Orta", "Zor", "Uzman"};
   static final String[] DIFF_COLORS = {"§a", "§e", "§6", "§c"};
   /** ilk gorusten ilk atisa kadar gecen sure (ms) */
   static final int[] REACTION = {950, 600, 340, 190};
   /** temel isabet olasiligi (yakin mesafe, hedef duruyorken) */
   static final double[] ACCURACY = {0.20, 0.36, 0.52, 0.70};
   static final double[] HEADSHOT = {0.05, 0.12, 0.22, 0.36};
   static final double[] SIGHT = {32.0, 45.0, 60.0, 80.0};
   static final double[] SPEED = {0.25, 0.28, 0.30, 0.31};
   static final String[] NAMES = {"Ahmet", "Mehmet", "Ayşe", "Can", "Deniz", "Ece", "Emre", "Kerem", "Zeynep", "Mert", "Selin", "Burak", "Elif", "Kaan", "Derya", "Onur"};

   final Game g;
   final Game.PS ps;
   final int diff;
   final Random rnd = new Random();
   Zombie ent;
   Weapon gun;
   int ammo;
   long reloadUntil;
   long nextShot;
   long reactAt;
   UUID target;
   boolean hasBomb;
   int plantTicks;
   int defuseTicks;
   int siteChoice;
   int thinkTick;
   Location goal;
   long strafeUntil;
   int strafeDir = 1;

   Bot(Game g, String name, int group, int diff) {
      this.g = g;
      this.diff = Math.max(0, Math.min(3, diff));
      this.ps = new Game.PS(UUID.randomUUID(), "BOT " + name);
      this.ps.group = group;
      this.ps.bot = this;
   }

   String display() {
      return DIFF_COLORS[this.diff] + "[BOT] §f" + this.ps.name.substring(4);
   }

   // ------------------------------------------------------------------ dogma / gorunum
   void spawn(Location at) {
      this.despawn();
      World w = at.getWorld();
      this.ent = w.spawn(at, Zombie.class, z -> {
         z.setAdult();
         z.setShouldBurnInDay(false);
         z.setCanPickupItems(false);
         z.setSilent(true);
         z.setRemoveWhenFarAway(false);
         z.setPersistent(false);
         z.addScoreboardTag(TAG);
         z.setCustomName(this.g.color(this.ps) + this.ps.name.substring(4) + " " + DIFF_COLORS[this.diff] + "[" + DIFF_NAMES[this.diff] + "]");
         z.setCustomNameVisible(true);
         if (z.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED) != null) {
            z.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).setBaseValue(SPEED[this.diff]);
         }

         if (z.getAttribute(Attribute.GENERIC_FOLLOW_RANGE) != null) {
            z.getAttribute(Attribute.GENERIC_FOLLOW_RANGE).setBaseValue(128.0);
         }

         if (z.getAttribute(Attribute.GENERIC_KNOCKBACK_RESISTANCE) != null) {
            z.getAttribute(Attribute.GENERIC_KNOCKBACK_RESISTANCE).setBaseValue(1.0);
         }
      });
      Bukkit.getMobGoals().removeAllGoals(this.ent);
      this.dress();
   }

   void dress() {
      if (this.ent == null) {
         return;
      }

      boolean ct = this.g.sideOf(this.ps) == 1;
      Color c = this.ps.group < 0 ? Color.GRAY : (ct ? Color.fromRGB(60, 120, 200) : Color.fromRGB(205, 125, 40));
      EntityEquipment eq = this.ent.getEquipment();
      eq.setHelmet(this.leather(Material.LEATHER_HELMET, c));
      eq.setChestplate(this.leather(Material.LEATHER_CHESTPLATE, c));
      eq.setLeggings(this.leather(Material.LEATHER_LEGGINGS, Color.fromRGB(45, 45, 45)));
      eq.setBoots(this.leather(Material.LEATHER_BOOTS, Color.fromRGB(30, 30, 30)));
      eq.setItemInMainHand(this.gun == null ? null : this.g.plugin.item(this.gun, null));
      eq.setHelmetDropChance(0.0F);
      eq.setChestplateDropChance(0.0F);
      eq.setLeggingsDropChance(0.0F);
      eq.setBootsDropChance(0.0F);
      eq.setItemInMainHandDropChance(0.0F);
   }

   ItemStack leather(Material m, Color c) {
      ItemStack it = new ItemStack(m);
      LeatherArmorMeta meta = (LeatherArmorMeta) it.getItemMeta();
      meta.setColor(c);
      it.setItemMeta(meta);
      return it;
   }

   void despawn() {
      if (this.ent != null) {
         this.ent.remove();
         this.ent = null;
      }
   }

   boolean on() {
      return this.ps.alive && this.ent != null && this.ent.isValid();
   }

   // ------------------------------------------------------------------ tur basi
   /** Tur basinda para durumuna gore silah ve zirh alir (CS ekonomisinin basit hali). */
   void buy(boolean keep) {
      boolean ct = this.g.sideOf(this.ps) == 1;
      if (this.g.mode == Game.Mode.DM) {
         String[] dm = {"ak47", "m4a4", "awp", "mp9", "deagle", "galil", "famas", "ump"};
         this.gun = Weapon.get(dm[this.rnd.nextInt(dm.length)]);
         this.ps.armor = 100.0;
         this.ps.helmet = true;
      } else {
         if (!keep || this.gun == null || this.gun.slot != Weapon.Slot.PRIMARY) {
            this.gun = Weapon.get(ct ? "usp" : "glock");
            int m = this.ps.money;
            String pick = null;
            if (m >= 5700 && this.diff >= 2 && this.rnd.nextInt(4) == 0) {
               pick = "awp";
            } else if (m >= 3700) {
               pick = ct ? (this.rnd.nextBoolean() ? "m4a4" : "m4a1s") : "ak47";
            } else if (m >= 2800) {
               pick = ct ? "famas" : "galil";
            } else if (m >= 1800) {
               pick = ct ? "mp9" : "mac10";
            } else if (m >= 1500 && this.rnd.nextBoolean()) {
               pick = "p250";
            }

            if (pick != null && Weapon.get(pick) != null && Weapon.get(pick).price <= m) {
               this.gun = Weapon.get(pick);
               if (!this.g.unlimited) {
                  this.ps.money -= this.gun.price;
               }
            }
         }

         if (this.ps.armor < 50.0 && (this.g.unlimited || this.ps.money >= 1000)) {
            this.ps.armor = 100.0;
            this.ps.helmet = true;
            if (!this.g.unlimited) {
               this.ps.money -= 1000;
            }
         }

         if (ct && !this.ps.kit && (this.g.unlimited || this.ps.money >= 400) && this.rnd.nextInt(3) == 0) {
            this.ps.kit = true;
            if (!this.g.unlimited) {
               this.ps.money -= 400;
            }
         }
      }

      this.ammo = this.gun.mag;
      this.reloadUntil = 0L;
      this.dress();
   }

   void roundStart(Location spawn, boolean keep) {
      this.ps.alive = true;
      this.ps.hp = 100.0;
      this.ps.roundKills = 0;
      this.plantTicks = 0;
      this.defuseTicks = 0;
      this.hasBomb = false;
      this.target = null;
      this.goal = null;
      if (!keep) {
         this.ps.armor = 0.0;
         this.ps.helmet = false;
         this.ps.kit = false;
         this.gun = null;
      }

      this.spawn(spawn);
      this.buy(keep);
   }

   // ------------------------------------------------------------------ dusunme
   void tick(long now) {
      if (this.g.mode == Game.Mode.DM && !this.ps.alive && this.ps.respawnAt > 0L && now >= this.ps.respawnAt && this.g.phase == Game.Phase.LIVE) {
         this.ps.respawnAt = 0L;
         List<Location> l = this.g.built.anywhere();
         this.ps.alive = true;
         this.ps.hp = 100.0;
         this.spawn(l.get(0));
         this.buy(false);
         this.ps.spawnSafeUntil = now + 1500L;
      }

      if (!this.on()) {
         return;
      }

      if (this.g.phase != Game.Phase.LIVE && this.g.phase != Game.Phase.PLANTED) {
         this.ent.getPathfinder().stopPathfinding();
         return;
      }

      this.thinkTick++;
      Target t = (this.thinkTick % 4 == 0 || this.target == null) ? this.findTarget() : this.current();
      if (t != null) {
         if (!t.id.equals(this.target)) {
            this.target = t.id;
            this.reactAt = now + REACTION[this.diff] + this.rnd.nextInt(250);
         }

         this.engage(t, now);
      } else {
         this.target = null;
         this.objective(now);
      }
   }

   static final class Target {
      UUID id;
      Game.PS ps;
      Entity e;
      Location eye;
   }

   Target targetOf(Game.PS o) {
      if (o == null || !o.alive || !this.g.enemies(this.ps, o) || o == this.ps) {
         return null;
      }

      Target t = new Target();
      t.id = o.id;
      t.ps = o;
      if (o.bot != null) {
         if (!o.bot.on()) {
            return null;
         }

         t.e = o.bot.ent;
         t.eye = o.bot.ent.getEyeLocation();
      } else {
         Player p = Bukkit.getPlayer(o.id);
         if (p == null || p.getGameMode() == GameMode.SPECTATOR) {
            return null;
         }

         t.e = p;
         t.eye = p.getEyeLocation();
      }

      return t;
   }

   Target current() {
      Game.PS o = this.target == null ? null : this.g.players.get(this.target);
      Target t = this.targetOf(o);
      return t != null && this.visible(t) ? t : null;
   }

   Target findTarget() {
      Target best = null;
      double bd = SIGHT[this.diff];
      for (Game.PS o : this.g.players.values()) {
         Target t = this.targetOf(o);
         if (t != null) {
            double d = t.eye.distance(this.ent.getEyeLocation());
            if (d < bd && this.visible(t)) {
               best = t;
               bd = d;
            }
         }
      }

      return best;
   }

   boolean visible(Target t) {
      Location eye = this.ent.getEyeLocation();
      Vector dir = t.eye.toVector().subtract(eye.toVector());
      double d = dir.length();
      if (d > SIGHT[this.diff] || d < 0.01) {
         return d < 0.01;
      }

      for (Game.Area s : this.g.smokes) {
         if (s.at != null && distToSegment(s.at.toVector(), eye.toVector(), t.eye.toVector()) < 3.2) {
            return false;
         }
      }

      RayTraceResult r = eye.getWorld().rayTraceBlocks(eye, dir.normalize(), d, FluidCollisionMode.NEVER, true);
      return r == null;
   }

   static double distToSegment(Vector p, Vector a, Vector b) {
      Vector ab = b.clone().subtract(a);
      double len = ab.lengthSquared();
      double t = len == 0.0 ? 0.0 : Math.max(0.0, Math.min(1.0, p.clone().subtract(a).dot(ab) / len));
      return a.clone().add(ab.multiply(t)).distance(p);
   }

   // ------------------------------------------------------------------ catisma
   void engage(Target t, long now) {
      this.ent.lookAt(t.eye);
      double dist = t.eye.distance(this.ent.getEyeLocation());
      boolean move = this.diff >= 1 && dist > 6.0;
      if (move && now < this.strafeUntil) {
         Vector side = t.eye.toVector().subtract(this.ent.getLocation().toVector()).setY(0).normalize().crossProduct(new Vector(0, 1, 0)).multiply(this.strafeDir * 2.5);
         this.ent.getPathfinder().moveTo(this.ent.getLocation().add(side), 1.0);
      } else {
         this.ent.getPathfinder().stopPathfinding();
         if (move && this.rnd.nextInt(30) == 0) {
            this.strafeDir = -this.strafeDir;
            this.strafeUntil = now + 600L + this.rnd.nextInt(600);
         }
      }

      if (now < this.reactAt || now < this.nextShot || this.g.phase == Game.Phase.FREEZE) {
         return;
      }

      if (now < this.reloadUntil) {
         return;
      }

      if (this.ammo <= 0) {
         this.reloadUntil = now + (long) (this.gun.reload * 1000.0);
         this.ammo = this.gun.mag;
         this.ent.getWorld().playSound(this.ent.getLocation(), Sound.BLOCK_IRON_DOOR_OPEN, 0.6F, 1.6F);
         return;
      }

      this.shoot(t, dist, now);
   }

   void shoot(Target t, double dist, long now) {
      Weapon w = this.gun;
      this.ammo--;
      long gap = w.shotMillis();
      if (!w.auto) {
         gap = Math.max(gap, 220L) + this.rnd.nextInt(180);
      }

      this.nextShot = now + gap + (3 - this.diff) * 40L;
      World world = this.ent.getWorld();
      Location from = this.ent.getEyeLocation();
      world.playSound(from, w.kind == Weapon.Kind.SNIPER ? Sound.ENTITY_GENERIC_EXPLODE : Sound.ENTITY_FIREWORK_ROCKET_BLAST, w.kind == Weapon.Kind.SNIPER ? 1.2F : 2.0F, w.kind == Weapon.Kind.PISTOL ? 1.6F : 1.2F);
      // isabet: zorluk x mesafe x hedefin hareketi x silahin sapmasi
      double acc = ACCURACY[this.diff];
      acc *= dist < 10.0 ? 1.0 : Math.max(0.35, 1.0 - (dist - 10.0) / 90.0);
      if (t.e.getVelocity().setY(0).lengthSquared() > 0.004) {
         acc *= 0.8;
      }

      if (w.scope) {
         acc *= dist > 15.0 ? 1.25 : 0.7;
      }

      int pellets = Math.max(1, w.pellets);
      int hits = 0;
      for (int i = 0; i < pellets; i++) {
         if (this.rnd.nextDouble() < acc * (pellets > 1 ? 0.75 : 1.0)) {
            hits++;
         }
      }

      boolean head = hits > 0 && this.rnd.nextDouble() < HEADSHOT[this.diff];
      Vector to = t.eye.toVector().add(new Vector(0.0, head ? 0.0 : -0.6, 0.0));
      if (hits == 0) {
         to.add(new Vector(this.rnd.nextGaussian() * 0.9, this.rnd.nextGaussian() * 0.6, this.rnd.nextGaussian() * 0.9));
      }

      this.g.tracer(world, from.toVector(), to);
      if (hits > 0) {
         double dmg = w.damage * Math.pow(w.rangeMod, dist * 40.0 / 500.0) * hits;
         if (head) {
            dmg *= w.kind == Weapon.Kind.SHOTGUN ? 2.0 : 4.0;
         }

         world.spawnParticle(Particle.BLOCK_CRACK, to.toLocation(world), 8, 0.05, 0.05, 0.05, Material.REDSTONE_BLOCK.createBlockData());
         this.g.hitBy(t.ps, this.ps, null, w, dmg, head, w.armorPen);
      }
   }

   // ------------------------------------------------------------------ gorev
   void objective(long now) {
      if (this.thinkTick % 10 != 1) {
         this.objectiveAction(now);
         return;
      }

      Location to = this.objectiveLocation();
      if (to != null && to.distanceSquared(this.ent.getLocation()) > 4.0) {
         this.ent.getPathfinder().moveTo(to, 1.0);
         this.goal = to;
      } else {
         this.ent.getPathfinder().stopPathfinding();
      }

      this.objectiveAction(now);
   }

   Location objectiveLocation() {
      StrikeMap.Built b = this.g.built;
      StrikeMap m = this.g.map;
      if (this.g.mode == Game.Mode.DM || this.g.mode == Game.Mode.DUEL || m.siteA == null) {
         Location near = null;
         double best = Double.MAX_VALUE;
         for (Game.PS o : this.g.players.values()) {
            Target t = this.targetOf(o);
            if (t != null) {
               double d = t.eye.distanceSquared(this.ent.getLocation());
               if (d < best) {
                  best = d;
                  near = t.e.getLocation();
               }
            }
         }

         if (near != null) {
            return near;
         }

         if (this.goal == null || this.goal.distanceSquared(this.ent.getLocation()) < 4.0) {
            return b.anywhere().get(0);
         }

         return this.goal;
      }

      boolean ct = this.g.sideOf(this.ps) == 1;
      if (this.g.phase == Game.Phase.PLANTED && this.g.bomb != null) {
         return this.g.bomb.clone().add(0.5, 0.0, 0.5);
      }

      int[] site = this.g.botSite == 0 ? m.siteA : m.siteB;
      if (ct) {
         site = this.siteChoice % 2 == 0 ? m.siteA : m.siteB;
      }

      if (this.goal != null && b.inside(this.goal, site)) {
         return this.goal;
      }

      int cx = site[0] + this.rnd.nextInt(site[2] - site[0] + 1);
      int cz = site[1] + this.rnd.nextInt(site[3] - site[1] + 1);
      if (!m.open[cx][cz] || m.solid[cx][cz] != 0) {
         return b.center(site);
      }

      return b.cell(cx, cz);
   }

   void objectiveAction(long now) {
      if (this.g.mode != Game.Mode.COMP) {
         return;
      }

      boolean ct = this.g.sideOf(this.ps) == 1;
      if (!ct && this.hasBomb && this.g.phase == Game.Phase.LIVE && this.g.siteAt(this.ent.getLocation()) != null && this.ent.isOnGround()) {
         this.ent.getPathfinder().stopPathfinding();
         this.plantTicks++;
         if (this.plantTicks % 8 == 1) {
            this.ent.getWorld().playSound(this.ent.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1.0F, 1.6F);
         }

         if (this.plantTicks >= 64) {
            this.plantTicks = 0;
            this.hasBomb = false;
            this.g.plantAt(this.ps, this.ent.getLocation().getBlock());
         }
      } else if (ct && this.g.phase == Game.Phase.PLANTED && this.g.bomb != null
         && this.ent.getLocation().distance(this.g.bomb.clone().add(0.5, 0.0, 0.5)) <= 2.5
         && (this.g.defuser == null || this.g.defuser.equals(this.ps.id))) {
         this.ent.getPathfinder().stopPathfinding();
         this.g.defuser = this.ps.id;
         int need = this.ps.kit ? 100 : 200;
         this.defuseTicks++;
         if (this.defuseTicks == 1) {
            this.g.say("§b" + this.ps.name + " bombayı imha ediyor" + (this.ps.kit ? " (kitli)" : ""));
         }

         if (this.defuseTicks % 10 == 1) {
            this.ent.getWorld().playSound(this.ent.getLocation(), Sound.BLOCK_CHAIN_PLACE, 1.0F, 1.4F);
         }

         if (this.defuseTicks >= need) {
            this.defuseTicks = 0;
            this.g.defuser = null;
            this.g.defuseDone(this.ps);
         }
      } else {
         if (this.defuseTicks > 0 && this.ps.id.equals(this.g.defuser)) {
            this.g.defuser = null;
         }

         this.defuseTicks = 0;
         this.plantTicks = 0;
      }
   }

   /** Bot oldugunde: varligi kaldirir, bombayi yere birakir. */
   void die() {
      if (this.ent != null) {
         Location l = this.ent.getLocation();
         l.getWorld().spawnParticle(Particle.CLOUD, l.clone().add(0.0, 1.0, 0.0), 12, 0.3, 0.5, 0.3, 0.02);
         if (this.hasBomb && this.g.phase != Game.Phase.OVER) {
            l.getWorld().dropItemNaturally(l.clone().add(0.0, 0.5, 0.0), this.g.plugin.item(Weapon.get("c4"), null));
            this.g.say("§6Bomba düştü!");
         }
      }

      this.hasBomb = false;
      this.despawn();
   }

   static List<String> names(int n, Random rnd) {
      List<String> l = new ArrayList<>(List.of(NAMES));
      java.util.Collections.shuffle(l, rnd);
      while (l.size() < n) {
         l.add("Bot" + (l.size() + 1));
      }

      return l.subList(0, n);
   }

   static boolean isBot(Entity e) {
      return e != null && e.getScoreboardTags().contains(TAG);
   }

   Block feet() {
      return this.ent.getLocation().getBlock();
   }
}
