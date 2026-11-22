package tr.pisan.strike;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;

final class Weapon {
   final String id;
   final String name;
   final Weapon.Kind kind;
   final Weapon.Slot slot;
   final Weapon.Side side;
   final int price;
   final int reward;
   final double damage;
   final double armorPen;
   final double rangeMod;
   final int rpm;
   final int mag;
   final int reserve;
   final int pellets;
   final double reload;
   final double spreadStand;
   final double spreadMove;
   final double spreadJump;
   final double recoil;
   final int speed;
   final boolean auto;
   final boolean scope;
   final Material item;
   final int model;
   static final Map<String, Weapon> ALL = new LinkedHashMap<>();
   static final List<Weapon> SHOP = new ArrayList<>();

   private Weapon(
      String var1,
      String var2,
      Weapon.Kind var3,
      Weapon.Side var4,
      int var5,
      int var6,
      double var7,
      double var9,
      double var11,
      int var13,
      int var14,
      int var15,
      int var16,
      double var17,
      double var19,
      double var21,
      double var23,
      double var25,
      int var27,
      boolean var28,
      boolean var29,
      Material var30,
      int var31
   ) {
      this.id = var1;
      this.name = var2;
      this.kind = var3;
      this.side = var4;
      this.price = var5;
      this.reward = var6;
      this.damage = var7;
      this.armorPen = var9;
      this.rangeMod = var11;
      this.rpm = var13;
      this.mag = var14;
      this.reserve = var15;
      this.pellets = var16;
      this.reload = var17;
      this.spreadStand = var19;
      this.spreadMove = var21;
      this.spreadJump = var23;
      this.recoil = var25;
      this.speed = var27;
      this.auto = var28;
      this.scope = var29;
      this.item = var30;
      this.model = var31;

      this.slot = switch (var3) {
         case PISTOL -> Weapon.Slot.SECONDARY;
         default -> Weapon.Slot.PRIMARY;
         case KNIFE -> Weapon.Slot.KNIFE;
         case GRENADE -> Weapon.Slot.GRENADE;
         case GEAR, TASER -> Weapon.Slot.GEAR;
         case BOMB -> Weapon.Slot.BOMB;
      };
   }

   boolean gun() {
      return this.mag > 0;
   }

   boolean grenade() {
      return this.kind == Weapon.Kind.GRENADE;
   }

   long shotMillis() {
      return this.rpm <= 0 ? 1000L : Math.round(60000.0 / (double)this.rpm);
   }

   private static Weapon gun(
      String var0,
      String var1,
      Weapon.Kind var2,
      Weapon.Side var3,
      int var4,
      int var5,
      double var6,
      double var8,
      double var10,
      int var12,
      int var13,
      int var14,
      double var15,
      double var17,
      double var19,
      double var21,
      double var23,
      int var25,
      boolean var26,
      boolean var27,
      int var28
   ) {
      Weapon var29 = new Weapon(
         var0,
         var1,
         var2,
         var3,
         var4,
         var5,
         var6,
         var8,
         var10,
         var12,
         var13,
         var14,
         1,
         var15,
         var17,
         var19,
         var21,
         var23,
         var25,
         var26,
         var27,
         Material.CROSSBOW,
         var28
      );
      ALL.put(var0, var29);
      SHOP.add(var29);
      return var29;
   }

   private static Weapon shotgun(
      String var0,
      String var1,
      Weapon.Side var2,
      int var3,
      double var4,
      double var6,
      int var8,
      int var9,
      int var10,
      int var11,
      double var12,
      double var14,
      boolean var16,
      int var17,
      int var18
   ) {
      Weapon var19 = new Weapon(
         var0,
         var1,
         Weapon.Kind.SHOTGUN,
         var2,
         var3,
         900,
         var4,
         var6,
         0.7,
         var8,
         var9,
         var10,
         var11,
         var12,
         var14,
         var14 + 1.5,
         var14 + 4.0,
         0.6,
         var17,
         var16,
         false,
         Material.CROSSBOW,
         var18
      );
      ALL.put(var0, var19);
      SHOP.add(var19);
      return var19;
   }

   private static Weapon thing(String var0, String var1, Weapon.Kind var2, Weapon.Side var3, int var4, Material var5, int var6) {
      Weapon var7 = new Weapon(
         var0,
         var1,
         var2,
         var3,
         var4,
         var2 == Weapon.Kind.KNIFE ? 1500 : 300,
         0.0,
         0.0,
         1.0,
         0,
         0,
         0,
         1,
         0.0,
         0.0,
         0.0,
         0.0,
         0.0,
         var2 == Weapon.Kind.KNIFE ? 250 : 245,
         false,
         false,
         var5,
         var6
      );
      ALL.put(var0, var7);
      if (var4 > 0) {
         SHOP.add(var7);
      }

      return var7;
   }

   static Weapon get(String var0) {
      return var0 == null ? null : ALL.get(var0);
   }

   static {
      gun("glock", "Glock-18", Weapon.Kind.PISTOL, Weapon.Side.T, 200, 300, 30.0, 0.47, 0.85, 400, 20, 120, 2.2, 0.25, 2.2, 6.0, 0.9, 240, false, false, 1001);
      gun("usp", "USP-S", Weapon.Kind.PISTOL, Weapon.Side.CT, 200, 300, 35.0, 0.505, 0.99, 352, 12, 24, 2.2, 0.15, 2.0, 6.0, 0.9, 240, false, false, 1002);
      gun("p250", "P250", Weapon.Kind.PISTOL, Weapon.Side.ANY, 300, 300, 38.0, 0.64, 0.85, 400, 13, 26, 2.2, 0.3, 2.3, 6.0, 1.1, 240, false, false, 1003);
      gun(
         "dualies",
         "Dual Berettas",
         Weapon.Kind.PISTOL,
         Weapon.Side.ANY,
         300,
         300,
         38.0,
         0.575,
         0.79,
         500,
         30,
         120,
         3.8,
         0.4,
         2.5,
         6.0,
         1.0,
         240,
         false,
         false,
         1004
      );
      gun(
         "fiveseven",
         "Five-SeveN",
         Weapon.Kind.PISTOL,
         Weapon.Side.CT,
         500,
         300,
         32.0,
         0.9115,
         0.81,
         400,
         20,
         100,
         2.2,
         0.3,
         2.2,
         6.0,
         0.9,
         240,
         false,
         false,
         1005
      );
      gun("tec9", "Tec-9", Weapon.Kind.PISTOL, Weapon.Side.T, 500, 300, 33.0, 0.906, 0.83, 500, 18, 90, 2.5, 0.4, 2.0, 6.0, 1.0, 240, false, false, 1006);
      gun(
         "deagle",
         "Desert Eagle",
         Weapon.Kind.PISTOL,
         Weapon.Side.ANY,
         700,
         300,
         53.0,
         0.932,
         0.81,
         267,
         7,
         35,
         2.2,
         0.3,
         4.0,
         8.0,
         3.0,
         230,
         false,
         false,
         1007
      );
      gun("mac10", "MAC-10", Weapon.Kind.SMG, Weapon.Side.T, 1050, 600, 29.0, 0.575, 0.8, 800, 30, 100, 3.2, 0.9, 2.5, 6.0, 0.55, 240, true, false, 1011);
      gun("mp9", "MP9", Weapon.Kind.SMG, Weapon.Side.CT, 1250, 600, 26.0, 0.6, 0.87, 857, 30, 120, 2.1, 0.8, 2.4, 6.0, 0.55, 240, true, false, 1012);
      gun("mp7", "MP7", Weapon.Kind.SMG, Weapon.Side.ANY, 1500, 600, 29.0, 0.625, 0.85, 750, 30, 120, 3.1, 0.7, 2.4, 6.0, 0.5, 220, true, false, 1013);
      gun("ump", "UMP-45", Weapon.Kind.SMG, Weapon.Side.ANY, 1200, 600, 35.0, 0.65, 0.75, 666, 25, 100, 3.5, 0.8, 2.6, 6.0, 0.55, 230, true, false, 1014);
      gun("p90", "P90", Weapon.Kind.SMG, Weapon.Side.ANY, 2350, 300, 26.0, 0.69, 0.86, 857, 50, 100, 3.3, 0.9, 2.4, 6.0, 0.45, 230, true, false, 1015);
      gun("galil", "Galil AR", Weapon.Kind.RIFLE, Weapon.Side.T, 1800, 300, 30.0, 0.775, 0.98, 666, 35, 90, 3.0, 0.5, 4.5, 8.0, 0.6, 215, true, false, 1021);
      gun("famas", "FAMAS", Weapon.Kind.RIFLE, Weapon.Side.CT, 2050, 300, 30.0, 0.7, 0.96, 666, 25, 90, 3.3, 0.5, 4.5, 8.0, 0.6, 220, true, false, 1022);
      gun("ak47", "AK-47", Weapon.Kind.RIFLE, Weapon.Side.T, 2700, 300, 36.0, 0.775, 0.98, 600, 30, 90, 2.5, 0.45, 5.0, 9.0, 0.8, 215, true, false, 1023);
      gun("m4a4", "M4A4", Weapon.Kind.RIFLE, Weapon.Side.CT, 3100, 300, 33.0, 0.7, 0.97, 666, 30, 90, 3.1, 0.4, 4.5, 8.0, 0.65, 225, true, false, 1024);
      gun("m4a1s", "M4A1-S", Weapon.Kind.RIFLE, Weapon.Side.CT, 2900, 300, 38.0, 0.7, 0.99, 600, 20, 80, 3.1, 0.35, 4.3, 8.0, 0.6, 225, true, false, 1025);
      gun("sg553", "SG 553", Weapon.Kind.RIFLE, Weapon.Side.T, 3000, 300, 30.0, 1.0, 0.98, 545, 30, 90, 2.8, 0.4, 4.8, 8.0, 0.7, 210, true, true, 1026);
      gun("aug", "AUG", Weapon.Kind.RIFLE, Weapon.Side.CT, 3300, 300, 28.0, 0.9, 0.98, 600, 30, 90, 3.8, 0.4, 4.6, 8.0, 0.65, 220, true, true, 1027);
      gun("ssg08", "SSG 08", Weapon.Kind.SNIPER, Weapon.Side.ANY, 1700, 300, 88.0, 0.85, 0.98, 48, 10, 90, 3.7, 0.1, 3.0, 3.0, 2.0, 230, false, true, 1031);
      gun("awp", "AWP", Weapon.Kind.SNIPER, Weapon.Side.ANY, 4750, 100, 115.0, 0.975, 0.99, 41, 5, 30, 3.7, 0.05, 9.0, 12.0, 3.0, 200, false, true, 1032);
      shotgun("nova", "Nova", Weapon.Side.ANY, 1050, 26.0, 0.5, 68, 8, 32, 9, 4.0, 3.5, false, 220, 1041);
      shotgun("xm1014", "XM1014", Weapon.Side.ANY, 2000, 20.0, 0.8, 171, 7, 32, 6, 4.0, 3.8, true, 215, 1042);
      shotgun("mag7", "MAG-7", Weapon.Side.CT, 1300, 30.0, 0.75, 71, 5, 32, 8, 2.5, 3.0, false, 225, 1043);
      shotgun("sawedoff", "Sawed-Off", Weapon.Side.T, 1100, 32.0, 0.75, 71, 7, 32, 8, 4.0, 4.5, false, 210, 1044);
      gun("negev", "Negev", Weapon.Kind.HEAVY, Weapon.Side.ANY, 1700, 300, 35.0, 0.71, 0.97, 800, 150, 300, 5.7, 1.2, 5.0, 9.0, 0.35, 195, true, false, 1051);
      gun("m249", "M249", Weapon.Kind.HEAVY, Weapon.Side.ANY, 5200, 300, 32.0, 0.8, 0.97, 750, 100, 200, 5.7, 1.0, 5.0, 9.0, 0.45, 195, true, false, 1052);
      thing("knife", "Bıçak", Weapon.Kind.KNIFE, Weapon.Side.ANY, 0, Material.IRON_SWORD, 1061);
      thing("kevlar", "Kevlar Yelek", Weapon.Kind.GEAR, Weapon.Side.ANY, 650, Material.IRON_CHESTPLATE, 1071);
      thing("helmet", "Kevlar + Kask", Weapon.Kind.GEAR, Weapon.Side.ANY, 1000, Material.IRON_HELMET, 1072);
      thing("kit", "İmha Kiti", Weapon.Kind.GEAR, Weapon.Side.CT, 400, Material.SHEARS, 1073);
      thing("zeus", "Zeus x27", Weapon.Kind.TASER, Weapon.Side.ANY, 200, Material.LIGHTNING_ROD, 1074);
      thing("he", "El Bombası", Weapon.Kind.GRENADE, Weapon.Side.ANY, 300, Material.FIREWORK_STAR, 1081);
      thing("flash", "Flaş", Weapon.Kind.GRENADE, Weapon.Side.ANY, 200, Material.GLOWSTONE_DUST, 1082);
      thing("smoke", "Sis Bombası", Weapon.Kind.GRENADE, Weapon.Side.ANY, 300, Material.LIGHT_GRAY_DYE, 1083);
      thing("molotov", "Molotof", Weapon.Kind.GRENADE, Weapon.Side.T, 400, Material.FIRE_CHARGE, 1084);
      thing("incendiary", "Yangın Bombası", Weapon.Kind.GRENADE, Weapon.Side.CT, 500, Material.BLAZE_POWDER, 1085);
      thing("c4", "C4", Weapon.Kind.BOMB, Weapon.Side.T, 0, Material.TNT_MINECART, 1091);
   }

   static enum Kind {
      PISTOL,
      SMG,
      HEAVY,
      RIFLE,
      SNIPER,
      SHOTGUN,
      KNIFE,
      GRENADE,
      GEAR,
      BOMB,
      TASER;
   }

   static enum Side {
      ANY,
      T,
      CT;
   }

   static enum Slot {
      PRIMARY,
      SECONDARY,
      KNIFE,
      GRENADE,
      BOMB,
      GEAR;
   }
}
