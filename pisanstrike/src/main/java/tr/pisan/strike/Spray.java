package tr.pisan.strike;

import java.util.HashMap;
import java.util.Map;
import org.bukkit.Material;

/**
 * CS tarzi sprey ve duvar delme.
 *
 * Sprey: her silahin sabit (rastgele olmayan) bir deseni vardir. N'inci mermi, nisan noktasina gore
 * desenin N'inci noktasina gider; oyuncu bunu ogrenip fareyi ters yone cekerek kontrol eder.
 * Desenler CS ailesindeki bilinen bicimlere gore burada uretilir (Valve'in verisi kopyalanmaz):
 *  - Tufekler: ilk ~9 mermi dikine yukselir, sonra saga, sola, tekrar saga yilan gibi kayar.
 *  - SMG/agir: daha alcak yukselis, daha genis/sik salinim.
 *  - Tabancalar: her atista yukari teper (tap atislarda sayac hizla sifirlanir).
 *  - Keskin nisanci ve pompalilar: desen yok, sadece sapma.
 *
 * Duvar delme: silahin delme gucu, malzemenin sertligi ve mermi blogun icinden ne kadar gectigi
 * (kalinlik) hesaba katilir; her duvarda guc ve hasar kaybolur, en fazla 4 yuzey delinir.
 */
final class Spray {
   private static final Map<String, double[][]> CACHE = new HashMap<>();

   /** Desen bicimi: [maks. dikey (derece), yukselis mermisi, yatay genlik, salinim uzunlugu, ilk yon] */
   static double[] shape(Weapon w) {
      switch (w.id) {
         case "ak47":
            return new double[]{6.4, 10, 2.6, 7, 1};
         case "m4a4":
            return new double[]{5.0, 11, 2.0, 8, -1};
         case "m4a1s":
            return new double[]{4.4, 10, 1.6, 8, -1};
         case "galil":
            return new double[]{5.2, 10, 2.2, 7, 1};
         case "famas":
            return new double[]{4.8, 9, 1.9, 7, -1};
         case "sg553":
         case "aug":
            return new double[]{5.0, 10, 1.8, 8, 1};
         case "negev":
            return new double[]{4.2, 12, 3.2, 9, 1};
         case "m249":
            return new double[]{4.6, 12, 3.0, 9, -1};
         case "mac10":
         case "mp9":
            return new double[]{3.2, 8, 2.0, 5, 1};
         case "mp7":
         case "ump":
            return new double[]{3.6, 9, 1.8, 6, -1};
         case "p90":
            return new double[]{3.0, 10, 1.6, 7, 1};
         default:
      }

      switch (w.kind) {
         case PISTOL:
            return new double[]{w.id.equals("deagle") ? 7.0 : 3.4, 6, 0.6, 3, 1};
         case SNIPER:
         case SHOTGUN:
            return null;
         default:
            return new double[]{4.0, 10, 1.8, 7, 1};
      }
   }

   /** n'inci mermi icin (yatay, dikey) derece kaymasi; 0. mermi tam isabet. */
   static double[] offset(Weapon w, int n) {
      double[][] p = CACHE.computeIfAbsent(w.id, k -> build(w));
      if (p == null || n <= 0) {
         return new double[]{0.0, 0.0};
      }

      return p[Math.min(n, p.length - 1)];
   }

   static double[][] build(Weapon w) {
      double[] s = shape(w);
      if (s == null) {
         return null;
      }

      int len = Math.max(w.mag, 31);
      double[][] out = new double[len][2];
      double rise = s[0];
      int riseN = (int) s[1];
      double amp = s[2];
      int period = (int) s[3];
      double dir = s[4];
      for (int i = 1; i < len; i++) {
         // dikey: hizla yukselir, riseN'den sonra hafifce oynar ama dusmez
         double t = Math.min(1.0, (double) i / riseN);
         double v = rise * (1.0 - Math.pow(1.0 - t, 1.8));
         if (i > riseN) {
            v += 0.25 * Math.sin((i - riseN) * 0.9);
         }

         // yatay: yukselis bitince yon degistiren yilan hareketi (ucgen dalga, giderek biraz genisler)
         double h = 0.0;
         if (i > riseN * 0.7) {
            double k = (i - riseN * 0.7) / period;
            double tri = 2.0 * Math.abs(2.0 * (k / 2.0 - Math.floor(k / 2.0 + 0.5))) - 1.0;
            h = dir * amp * (0.8 + 0.02 * i) * -tri;
         }

         out[i][0] = h;
         out[i][1] = v;
      }

      return out;
   }

   // ------------------------------------------------------------------ duvar delme
   /** Silahin delme gucu (CS'teki penetration power'a karsilik). */
   static double power(Weapon w) {
      if (w.id.equals("awp")) {
         return 3.0;
      } else if (w.id.equals("deagle") || w.id.equals("ssg08")) {
         return 2.2;
      }

      switch (w.kind) {
         case RIFLE:
         case HEAVY:
            return 2.0;
         case SNIPER:
            return 2.5;
         case SMG:
            return 1.0;
         case SHOTGUN:
            return 0.5;
         default:
            return 1.0;
      }
   }

   /** Malzeme sertligi: blogun 1 blokluk kalinligi bu kadar guc yer. Delinemezse +sonsuz. */
   static double hardness(Material m) {
      String n = m.name();
      if (m == Material.BARRIER || m == Material.BEDROCK || n.contains("OBSIDIAN") || n.contains("DEEPSLATE_BRICKS") && n.contains("POLISHED")) {
         return Double.POSITIVE_INFINITY;
      }

      if (n.contains("GLASS") || n.contains("LEAVES") || n.contains("WOOL") || n.contains("CARPET") || n.contains("PANE")
         || n.contains("BAMBOO") || n.equals("HAY_BLOCK") || n.contains("BANNER")) {
         return 0.25;
      }

      if (n.contains("FENCE") || n.contains("TRAPDOOR") || n.contains("DOOR") && !n.contains("IRON")) {
         return 0.4;
      }

      if (n.contains("PLANKS") || n.contains("LOG") || n.contains("WOOD") || n.equals("BARREL") || n.contains("CHEST")
         || n.contains("BOOKSHELF") || n.contains("CRAFTING")) {
         return 0.7;
      }

      if (n.contains("IRON") || n.contains("ANVIL") || n.contains("COPPER") || n.contains("NETHERITE") || n.contains("GOLD_BLOCK")) {
         return 2.6;
      }

      if (n.contains("CONCRETE") && !n.contains("POWDER") || n.contains("STONE") || n.contains("BRICK") || n.contains("ANDESITE")
         || n.contains("DIORITE") || n.contains("GRANITE") || n.contains("QUARTZ") || n.contains("BLACKSTONE") || n.contains("DEEPSLATE")
         || n.contains("PURPUR") || n.contains("PRISMARINE") || n.contains("ICE")) {
         return 1.6;
      }

      if (n.contains("TERRACOTTA") || n.contains("SANDSTONE") || n.contains("MUD") || n.contains("CLAY")) {
         return 1.2;
      }

      if (n.contains("SAND") || n.contains("DIRT") || n.contains("GRASS") || n.contains("SNOW") || n.contains("GRAVEL") || n.contains("MOSS")) {
         return 0.9;
      }

      return 1.3;
   }
}
