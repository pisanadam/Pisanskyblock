package tr.pisan.strike;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

final class StrikeMap {
   static final int CELL = 3;
   static final int FLOOR_Y = 64;
   static final int WALL_H = 6;
   final String id;
   final String name;
   final String desc;
   final Material icon;
   final int w;
   final int h;
   final boolean duel;
   final Material floor;
   final Material wall;
   final Material cap;
   final Material crate;
   final Material siteFloor;
   final Material path;
   final Material[] containers;
   final boolean[][] open;
   final int[][] solid;
   final Material[][] solidMat;
   final List<int[]> tSpawn = new ArrayList<>();
   final List<int[]> ctSpawn = new ArrayList<>();
   int[] siteA;
   int[] siteB;
   int[] tZone;
   int[] ctZone;
   static final Map<String, StrikeMap> ALL = new LinkedHashMap<>();

   StrikeMap(
      String var1,
      String var2,
      String var3,
      Material var4,
      int var5,
      int var6,
      boolean var7,
      Material var8,
      Material var9,
      Material var10,
      Material var11,
      Material var12,
      Material var13,
      Material... var14
   ) {
      this.id = var1;
      this.name = var2;
      this.desc = var3;
      this.icon = var4;
      this.w = var5;
      this.h = var6;
      this.duel = var7;
      this.floor = var8;
      this.wall = var9;
      this.cap = var10;
      this.crate = var11;
      this.siteFloor = var12;
      this.path = var13;
      this.containers = var14;
      this.open = new boolean[var5][var6];
      this.solid = new int[var5][var6];
      this.solidMat = new Material[var5][var6];
   }

   StrikeMap open(int var1, int var2, int var3, int var4) {
      for (int var5 = var1; var5 <= var3; var5++) {
         for (int var6 = var2; var6 <= var4; var6++) {
            this.open[var5][var6] = true;
         }
      }

      return this;
   }

   StrikeMap close(int var1, int var2, int var3, int var4) {
      for (int var5 = var1; var5 <= var3; var5++) {
         for (int var6 = var2; var6 <= var4; var6++) {
            this.open[var5][var6] = false;
         }
      }

      return this;
   }

   StrikeMap crate(int var1, int var2) {
      this.solid[var1][var2] = 2;
      this.solidMat[var1][var2] = this.crate;
      return this;
   }

   StrikeMap container(int var1, int var2, int var3, int var4, int var5) {
      for (int var6 = var1; var6 <= var3; var6++) {
         for (int var7 = var2; var7 <= var4; var7++) {
            this.solid[var6][var7] = 3;
            this.solidMat[var6][var7] = this.containers[var5 % this.containers.length];
         }
      }

      return this;
   }

   StrikeMap sites(int[] var1, int[] var2) {
      this.siteA = var1;
      this.siteB = var2;
      if (var1 != null) {
         this.open(var1[0], var1[1], var1[2], var1[3]);
      }

      if (var2 != null) {
         this.open(var2[0], var2[1], var2[2], var2[3]);
      }

      return this;
   }

   StrikeMap spawns(int[] var1, int[] var2) {
      this.tZone = var1;
      this.ctZone = var2;
      this.open(var1[0], var1[1], var1[2], var1[3]);
      this.open(var2[0], var2[1], var2[2], var2[3]);

      for (int var3 = var1[0]; var3 <= var1[2]; var3++) {
         for (int var4 = var1[1]; var4 <= var1[3]; var4++) {
            this.tSpawn.add(new int[]{var3, var4});
         }
      }

      for (int var5 = var2[0]; var5 <= var2[2]; var5++) {
         for (int var6 = var2[1]; var6 <= var2[3]; var6++) {
            this.ctSpawn.add(new int[]{var5, var6});
         }
      }

      return this;
   }

   static int[] r(int var0, int var1, int var2, int var3) {
      return new int[]{var0, var1, var2, var3};
   }

   StrikeMap.Built build(Plugin var1, final World var2, final int var3, final int var4, final Consumer<StrikeMap.Built> var5) {
      final StrikeMap.Built var6 = new StrikeMap.Built(this, var2, var3, var4);
      int var7 = this.w * 3;
      int var8 = this.h * 3;
      final ArrayList var9 = new ArrayList(var7 * var8 * 3);
      boolean[][] var10 = new boolean[var7][var8];

      for (int var11 = 0; var11 < var7; var11++) {
         for (int var12 = 0; var12 < var8; var12++) {
            var10[var11][var12] = this.open[var11 / 3][var12 / 3];
         }
      }

      for (int var23 = -1; var23 <= var7; var23++) {
         for (int var25 = -1; var25 <= var8; var25++) {
            boolean var13 = var23 >= 0 && var25 >= 0 && var23 < var7 && var25 < var8;
            int var14 = Math.floorDiv(var23, 3);
            int var15 = Math.floorDiv(var25, 3);
            boolean var16 = var13 && var10[var23][var25];
            Material var17 = this.floor;
            if (var16) {
               if (this.siteA != null && within(var14, var15, this.siteA) || this.siteB != null && within(var14, var15, this.siteB)) {
                  var17 = (var23 + var25) % 4 == 0 ? this.path : this.siteFloor;
               } else if ((var23 * 7 + var25 * 13) % 11 == 0) {
                  var17 = this.path;
               }
            }

            var9.add(new Object[]{var23, 64, var25, var13 ? var17 : this.wall});

            for (int var18 = 65; var18 <= 72; var18++) {
               var9.add(new Object[]{var23, var18, var25, Material.AIR});
            }

            if (!var16) {
               boolean var28 = !var13;

               for (int var29 = -1; var29 <= 1 && !var28; var29++) {
                  for (int var31 = -1; var31 <= 1 && !var28; var31++) {
                     int var33 = var23 + var29;
                     int var34 = var25 + var31;
                     if (var33 >= 0 && var34 >= 0 && var33 < var7 && var34 < var8 && var10[var33][var34]) {
                        var28 = true;
                     }
                  }
               }

               byte var30 = 70;
               if (var28) {
                  for (int var32 = 65; var32 < var30; var32++) {
                     var9.add(new Object[]{var23, var32, var25, var32 == var30 - 1 && (var23 + var25) % 2 == 0 ? this.cap : this.wall});
                  }
               }

               var9.add(new Object[]{var23, Integer.valueOf(var30), var25, this.cap});
               if (!var13) {
                  var9.add(new Object[]{var23, var30 + 1, var25, Material.BARRIER});
               }
            } else if (var13 && this.solid[var14][var15] > 0) {
               int var27 = var23 - var14 * 3;
               int var19 = var25 - var15 * 3;
               int var20 = this.solid[var14][var15];
               boolean var21 = var20 == 3 || var27 >= 0 && var19 >= 0 && var27 <= 2 && var19 <= 2;
               if (var21) {
                  for (int var22 = 65; var22 <= 64 + var20; var22++) {
                     var9.add(new Object[]{var23, var22, var25, this.solidMat[var14][var15]});
                  }
               }
            }
         }
      }

      for (int var24 = -1; var24 <= var7; var24++) {
         for (int var26 = -1; var26 <= var8; var26++) {
            var9.add(new Object[]{var24, 73, var26, Material.BARRIER});
         }
      }

      (new BukkitRunnable() {
         int i = 0;

         public void run() {
            for (int var1 = Math.min(var9.size(), this.i + 12000); this.i < var1; this.i++) {
               Object[] var2x = (Object[])var9.get(this.i);
               var2.getBlockAt(var3 + (Integer)var2x[0], (Integer)var2x[1], var4 + (Integer)var2x[2]).setType((Material)var2x[3], false);
            }

            if (this.i >= var9.size()) {
               this.cancel();
               var5.accept(var6);
            }
         }
      }).runTaskTimer(var1, 1L, 1L);
      return var6;
   }

   static boolean within(int var0, int var1, int[] var2) {
      return var0 >= var2[0] && var0 <= var2[2] && var1 >= var2[1] && var1 <= var2[3];
   }

   static void keepLoaded(Plugin var0, World var1, int var2, int var3, int var4, int var5, boolean var6) {
      for (int var7 = var2 - 16 >> 4; var7 <= var2 + var4 * 3 + 16 >> 4; var7++) {
         for (int var8 = var3 - 16 >> 4; var8 <= var3 + var5 * 3 + 16 >> 4; var8++) {
            var1.setChunkForceLoaded(var7, var8, var6);
         }
      }
   }

   static void forget() {
      Bukkit.getLogger().fine("maps");
   }

   static {
      StrikeMap var0 = new StrikeMap(
         "col",
         "Çöl",
         "Uzun A, kısa A, orta ve B tünelleri. Klasik çöl kasabası.",
         Material.SAND,
         31,
         31,
         false,
         Material.SMOOTH_SANDSTONE,
         Material.SANDSTONE,
         Material.CUT_SANDSTONE,
         Material.BARREL,
         Material.RED_SANDSTONE,
         Material.SAND,
         Material.ORANGE_TERRACOTTA
      );
      var0.open(12, 1, 18, 4)
         .open(9, 2, 12, 3)
         .open(18, 2, 21, 3)
         .open(14, 5, 16, 25)
         .open(16, 10, 24, 11)
         .open(23, 9, 24, 11)
         .open(27, 9, 29, 26)
         .open(20, 24, 29, 26)
         .open(1, 9, 3, 24)
         .open(1, 22, 11, 24)
         .open(4, 14, 9, 18)
         .open(9, 16, 14, 17)
         .open(10, 6, 13, 7);
      var0.sites(r(21, 1, 29, 8), r(1, 1, 9, 8)).spawns(r(11, 25, 19, 29), r(13, 1, 17, 3));
      var0.crate(3, 3)
         .crate(6, 5)
         .crate(4, 6)
         .crate(23, 3)
         .crate(26, 4)
         .crate(25, 6)
         .crate(28, 2)
         .crate(15, 12)
         .crate(28, 15)
         .crate(13, 27)
         .crate(17, 27)
         .crate(2, 18)
         .crate(20, 11)
         .crate(6, 23);
      ALL.put(var0.id, var0);
      var0 = new StrikeMap(
         "liman",
         "Liman",
         "Konteynerlerle dolu açık liman. Uzun görüş hatları, keskin nişancı cenneti.",
         Material.BLUE_CONCRETE,
         31,
         31,
         false,
         Material.GRAY_CONCRETE,
         Material.STONE_BRICKS,
         Material.POLISHED_ANDESITE,
         Material.SPRUCE_PLANKS,
         Material.YELLOW_CONCRETE,
         Material.LIGHT_GRAY_CONCRETE,
         Material.RED_CONCRETE,
         Material.BLUE_CONCRETE,
         Material.GREEN_CONCRETE,
         Material.ORANGE_CONCRETE
      );
      var0.open(1, 1, 29, 29);
      var0.close(10, 6, 10, 11)
         .close(10, 14, 10, 18)
         .close(10, 21, 10, 24)
         .close(20, 6, 20, 8)
         .close(20, 11, 20, 16)
         .close(20, 19, 20, 24)
         .close(1, 9, 6, 9)
         .close(24, 9, 29, 9)
         .close(9, 1, 9, 4)
         .close(21, 1, 21, 4);
      var0.sites(r(22, 1, 29, 8), r(1, 1, 8, 8)).spawns(r(12, 26, 18, 29), r(12, 1, 18, 3));
      var0.container(3, 12, 6, 13, 0)
         .container(4, 16, 5, 20, 1)
         .container(13, 8, 17, 9, 2)
         .container(13, 20, 17, 21, 3)
         .container(24, 12, 25, 16, 0)
         .container(23, 22, 27, 23, 1)
         .container(14, 14, 16, 15, 2)
         .container(2, 25, 4, 26, 3)
         .container(26, 26, 28, 27, 0);
      var0.crate(3, 3).crate(5, 6).crate(25, 3).crate(27, 6).crate(15, 5).crate(7, 27).crate(23, 18);
      ALL.put(var0.id, var0);
      var0 = new StrikeMap(
         "koy",
         "Köy",
         "Köy meydanı ve evler arası dar sokaklar. Yakın mesafe çatışması.",
         Material.OAK_LOG,
         31,
         31,
         false,
         Material.DIRT_PATH,
         Material.STONE_BRICKS,
         Material.MOSSY_STONE_BRICKS,
         Material.OAK_PLANKS,
         Material.COBBLESTONE,
         Material.GRASS_BLOCK,
         Material.HAY_BLOCK
      );
      var0.open(20, 5, 28, 7)
         .open(26, 7, 28, 13)
         .open(26, 17, 28, 25)
         .open(20, 23, 28, 25)
         .open(20, 14, 25, 16)
         .open(12, 12, 18, 18)
         .open(14, 8, 16, 11)
         .open(14, 19, 16, 22)
         .open(5, 14, 11, 16)
         .open(2, 4, 4, 12)
         .open(2, 4, 10, 6)
         .open(2, 18, 4, 26)
         .open(2, 24, 10, 26);
      var0.sites(r(11, 1, 19, 7), r(11, 23, 19, 29)).spawns(r(26, 13, 29, 17), r(1, 13, 4, 17));
      var0.crate(15, 15)
         .crate(13, 3)
         .crate(17, 5)
         .crate(12, 6)
         .crate(13, 25)
         .crate(17, 27)
         .crate(16, 24)
         .crate(24, 6)
         .crate(24, 24)
         .crate(7, 15)
         .container(18, 13, 18, 13, 0)
         .container(12, 17, 12, 17, 0);
      ALL.put(var0.id, var0);
      // --- Yeni bomba haritalari (tools/maps.py ile uretildi ve dogrulandi)
      var0 = new StrikeMap(
         "fabrika",
         "Fabrika",
         "Makine hollerinden geçen orta koridor, uzun A boru hattı ve B depo tüneli.",
         Material.IRON_BLOCK,
         31,
         31,
         false,
         Material.SMOOTH_STONE, Material.GRAY_CONCRETE, Material.IRON_BLOCK, Material.BARREL, Material.YELLOW_TERRACOTTA, Material.STONE, Material.CYAN_TERRACOTTA, Material.LIGHT_GRAY_TERRACOTTA, Material.BROWN_TERRACOTTA
      );
      var0.sites(r(21, 1, 29, 8), r(1, 1, 9, 8))
         .spawns(r(12, 26, 18, 29), r(12, 1, 18, 3))
         .open(19, 27, 28, 28)
         .open(26, 9, 28, 26)
         .open(2, 27, 11, 28)
         .open(2, 9, 4, 26)
         .open(14, 4, 16, 25)
         .open(12, 4, 13, 7)
         .open(10, 6, 11, 7)
         .open(19, 2, 20, 3)
         .open(17, 9, 20, 10)
         .open(17, 15, 25, 16)
         .open(5, 18, 13, 19)
         .open(6, 12, 8, 17)
         .close(15, 12, 15, 13)
         .container(23, 3, 24, 4, 0)
         .container(26, 6, 27, 6, 1)
         .crate(22, 7)
         .crate(28, 2)
         .container(3, 3, 4, 4, 2)
         .container(6, 6, 7, 6, 0)
         .crate(8, 2)
         .crate(2, 7)
         .crate(14, 18)
         .crate(16, 22)
         .crate(27, 18)
         .crate(3, 20)
         .crate(9, 28)
         .crate(21, 28)
         .crate(7, 14);
      ALL.put(var0.id, var0);
      var0 = new StrikeMap(
         "kar",
         "Kar Üssü",
         "Kutuptaki askeri üs. Uzun batı koridoru, güney çevre yolu, buzlu orta.",
         Material.SNOW_BLOCK,
         31,
         31,
         false,
         Material.SNOW_BLOCK, Material.POLISHED_ANDESITE, Material.PACKED_ICE, Material.SPRUCE_PLANKS, Material.BLUE_ICE, Material.WHITE_CONCRETE, Material.LIGHT_BLUE_CONCRETE, Material.WHITE_TERRACOTTA, Material.GRAY_CONCRETE
      );
      var0.sites(r(9, 1, 16, 7), r(22, 10, 29, 17))
         .spawns(r(1, 25, 6, 29), r(24, 1, 29, 4))
         .open(17, 2, 23, 3)
         .open(26, 5, 28, 9)
         .open(2, 6, 4, 24)
         .open(2, 3, 8, 5)
         .open(7, 27, 25, 28)
         .open(23, 18, 25, 26)
         .open(5, 19, 21, 20)
         .open(12, 8, 14, 18)
         .open(15, 13, 21, 14)
         .open(17, 22, 21, 26)
         .close(13, 15, 13, 16)
         .container(11, 2, 12, 3, 0)
         .container(14, 5, 15, 5, 1)
         .crate(16, 2)
         .crate(9, 6)
         .container(24, 12, 25, 13, 2)
         .container(27, 15, 28, 15, 0)
         .crate(23, 16)
         .crate(28, 11)
         .crate(3, 14)
         .crate(19, 24)
         .crate(10, 28)
         .crate(24, 21)
         .crate(18, 19)
         .crate(13, 10)
         .crate(6, 4);
      ALL.put(var0.id, var0);
      var0 = new StrikeMap(
         "tapinak",
         "Tapınak",
         "Orman tapınağı. Ortada avlu, iki yanda bomba bölgeleri, çevrede yosunlu patikalar.",
         Material.MOSSY_COBBLESTONE,
         31,
         31,
         false,
         Material.MOSS_BLOCK, Material.MOSSY_STONE_BRICKS, Material.CHISELED_STONE_BRICKS, Material.JUNGLE_PLANKS, Material.CRACKED_STONE_BRICKS, Material.COARSE_DIRT, Material.JUNGLE_LOG, Material.MOSSY_COBBLESTONE
      );
      var0.sites(r(23, 8, 29, 15), r(1, 8, 7, 15))
         .spawns(r(12, 26, 18, 29), r(12, 1, 18, 3))
         .open(4, 27, 11, 28)
         .open(4, 16, 5, 26)
         .open(4, 4, 12, 5)
         .open(4, 6, 5, 7)
         .open(19, 24, 26, 25)
         .open(25, 16, 26, 23)
         .open(18, 4, 24, 5)
         .open(23, 6, 24, 7)
         .open(19, 26, 19, 26)
         .open(11, 10, 19, 20)
         .open(14, 4, 16, 9)
         .open(14, 21, 16, 25)
         .open(8, 14, 10, 16)
         .open(20, 15, 22, 16)
         .close(13, 13, 17, 17)
         .open(15, 13, 15, 17)
         .container(25, 10, 26, 11, 0)
         .crate(28, 9)
         .crate(24, 14)
         .crate(28, 14)
         .container(3, 12, 4, 13, 1)
         .crate(2, 9)
         .crate(6, 14)
         .crate(6, 9)
         .crate(12, 11)
         .crate(18, 19)
         .crate(12, 19)
         .crate(18, 11)
         .crate(5, 24)
         .crate(25, 21)
         .crate(8, 5)
         .crate(22, 4);
      ALL.put(var0.id, var0);

      var0 = new StrikeMap(
         "arena",
         "Düello Arenası",
         "1v1 için küçük, simetrik arena.",
         Material.IRON_SWORD,
         17,
         9,
         true,
         Material.POLISHED_BLACKSTONE,
         Material.DEEPSLATE_BRICKS,
         Material.POLISHED_DEEPSLATE,
         Material.SPRUCE_PLANKS,
         Material.POLISHED_BLACKSTONE,
         Material.POLISHED_BLACKSTONE,
         Material.CRIMSON_PLANKS,
         Material.WARPED_PLANKS
      );
      var0.open(1, 1, 15, 7).spawns(r(1, 3, 2, 5), r(14, 3, 15, 5));
      var0.crate(5, 2).crate(5, 6).crate(8, 4).crate(11, 2).crate(11, 6).container(8, 1, 8, 1, 0).container(8, 7, 8, 7, 1);
      ALL.put(var0.id, var0);
   }

   static final class Built {
      final StrikeMap map;
      final World world;
      final int ox;
      final int oz;

      Built(StrikeMap var1, World var2, int var3, int var4) {
         this.map = var1;
         this.world = var2;
         this.ox = var3;
         this.oz = var4;
      }

      Location cell(int var1, int var2) {
         return new Location(this.world, (double)(this.ox + var1 * 3) + 1.5, 65.0, (double)(this.oz + var2 * 3) + 1.5);
      }

      int[] cellOf(Location var1) {
         return new int[]{Math.floorDiv(var1.getBlockX() - this.ox, 3), Math.floorDiv(var1.getBlockZ() - this.oz, 3)};
      }

      boolean inside(Location var1, int[] var2) {
         if (var2 != null && var1.getWorld() == this.world) {
            int[] var3 = this.cellOf(var1);
            return var3[0] >= var2[0] && var3[0] <= var2[2] && var3[1] >= var2[1] && var3[1] <= var2[3];
         } else {
            return false;
         }
      }

      boolean inMap(Location var1) {
         if (var1.getWorld() != this.world) {
            return false;
         } else {
            int[] var2 = this.cellOf(var1);
            return var2[0] >= 0 && var2[1] >= 0 && var2[0] < this.map.w && var2[1] < this.map.h && var1.getY() > 54.0;
         }
      }

      List<Location> spawns(boolean var1) {
         ArrayList var2 = new ArrayList();

         for (int[] var4 : var1 ? this.map.ctSpawn : this.map.tSpawn) {
            if (this.map.solid[var4[0]][var4[1]] == 0) {
               var2.add(this.facing(this.cell(var4[0], var4[1]), var1));
            }
         }

         Collections.shuffle(var2);
         return var2;
      }

      List<Location> anywhere() {
         ArrayList var1 = new ArrayList();

         for (int var2 = 0; var2 < this.map.w; var2++) {
            for (int var3 = 0; var3 < this.map.h; var3++) {
               if (this.map.open[var2][var3] && this.map.solid[var2][var3] == 0) {
                  var1.add(this.cell(var2, var3));
               }
            }
         }

         Collections.shuffle(var1);
         return var1;
      }

      Location facing(Location var1, boolean var2) {
         int[] var3 = var2 ? this.map.tZone : this.map.ctZone;
         Location var4 = this.cell((var3[0] + var3[2]) / 2, (var3[1] + var3[3]) / 2);
         var1.setDirection(var4.toVector().subtract(var1.toVector()).setY(0));
         return var1;
      }

      Location center(int[] var1) {
         return this.cell((var1[0] + var1[2]) / 2, (var1[1] + var1[3]) / 2);
      }
   }
}
