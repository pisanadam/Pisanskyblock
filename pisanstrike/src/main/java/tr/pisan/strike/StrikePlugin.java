package tr.pisan.strike;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.ClickEvent.Action;
import net.md_5.bungee.api.chat.hover.content.Content;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType.SlotType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;

public final class StrikePlugin extends JavaPlugin implements Listener {
   World world;
   Location lobby;
   NamespacedKey KEY_W;
   NamespacedKey KEY_UID;
   NamespacedKey KEY_UI;
   final Map<UUID, StrikePlugin.Party> partyOf = new HashMap<>();
   final Map<UUID, Set<UUID>> invites = new HashMap<>();
   final Map<UUID, Game> gameOf = new HashMap<>();
   final List<Game> games = new ArrayList<>();
   final Set<Integer> slots = new HashSet<>();
   final Set<UUID> webVerified = new HashSet<>();
   static final List<Material> ITEMS = new ArrayList<>();

   public void onEnable() {
      this.saveDefaultConfig();
      this.KEY_W = new NamespacedKey(this, "weapon");
      this.KEY_UID = new NamespacedKey(this, "uid");
      this.KEY_UI = new NamespacedKey(this, "ui");
      this.world = new WorldCreator("pisan_strike").generator(new ChunkGenerator() {
      }).createWorld();
      this.world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
      this.world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
      this.world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
      this.world.setGameRule(GameRule.NATURAL_REGENERATION, false);
      this.world.setGameRule(GameRule.DO_FIRE_TICK, false);
      this.world.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false);
      this.world.setGameRule(GameRule.DO_IMMEDIATE_RESPAWN, true);
      this.world.setGameRule(GameRule.SHOW_DEATH_MESSAGES, false);
      this.world.setGameRule(GameRule.KEEP_INVENTORY, true);
      this.world.setGameRule(GameRule.FALL_DAMAGE, true);
      this.world.setTime(6000L);
      this.world.setDifficulty(Difficulty.EASY);
      this.world.setPVP(true);
      this.lobby = new Location(this.world, 0.5, 65.0, 0.5, 180.0F, 0.0F);
      this.buildLobby();
      Bukkit.getPluginManager().registerEvents(this, this);
      Bukkit.getScheduler().runTaskTimer(this, () -> {
         for (Game var2x : new ArrayList<>(this.games)) {
            var2x.tick();
         }
      }, 1L, 1L);
      Bukkit.getScheduler().runTaskTimer(this, this::lobbyHud, 20L, 40L);
      this.startWebChannel();

      for (Player var2 : Bukkit.getOnlinePlayers()) {
         this.toLobby(var2);
      }

      this.getLogger().info("Pisan Strike hazır: " + StrikeMap.ALL.size() + " harita, " + Weapon.ALL.size() + " silah ve ekipman.");
   }

   public void onDisable() {
      for (Game var2 : new ArrayList<>(this.games)) {
         var2.end("sunucu kapanıyor");
      }
   }

   void buildLobby() {
      byte var1 = 12;
      byte var2 = 64;

      for (int var3 = -var1; var3 <= var1; var3++) {
         for (int var4 = -var1; var4 <= var1; var4++) {
            boolean var5 = Math.abs(var3) == var1 || Math.abs(var4) == var1;
            this.world.getBlockAt(var3, var2, var4).setType((var3 + var4) % 2 == 0 ? Material.POLISHED_BLACKSTONE : Material.POLISHED_DEEPSLATE, false);

            for (int var6 = 1; var6 <= 5; var6++) {
               this.world
                  .getBlockAt(var3, var2 + var6, var4)
                  .setType(var5 ? (var6 == 1 ? Material.ORANGE_CONCRETE : (var6 == 5 ? Material.CYAN_CONCRETE : Material.TINTED_GLASS)) : Material.AIR, false);
            }

            this.world.getBlockAt(var3, var2 + 6, var4).setType(Material.BARRIER, false);
         }
      }

      for (int var7 = -2; var7 <= 2; var7++) {
         for (int var8 = -2; var8 <= 2; var8++) {
            this.world
               .getBlockAt(var7, var2, var8)
               .setType(Math.abs(var7) != 2 && Math.abs(var8) != 2 ? Material.SEA_LANTERN : Material.ORANGE_CONCRETE, false);
         }
      }

      this.world.setSpawnLocation(0, 65, 0);
   }

   void toLobby(Player var1) {
      Game var2 = this.gameOf.remove(var1.getUniqueId());
      if (var2 != null) {
         var2.leave(var1);
      }

      this.resetPlayer(var1);
      var1.teleport(this.lobby);
      var1.setGameMode(GameMode.ADVENTURE);
      var1.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
      var1.getInventory()
         .setItem(
            0,
            this.ui(Material.NETHER_STAR, "party", "§6§lParti Menüsü §7(sağ tık)", "§7Parti kur, arkadaşlarını davet et,", "§7harita ve mod seçip maçı başlat.")
         );
      var1.getInventory().setItem(4, this.ui(Material.BOOK, "help", "§e§lNasıl oynanır §7(sağ tık)"));
      var1.getInventory().setItem(8, this.ui(Material.DIAMOND_SWORD, "quick", "§b§lHızlı Düello §7(sağ tık)", "§7Partideki biriyle hemen 1v1."));
   }

   void resetPlayer(Player var1) {
      var1.getInventory().clear();

      for (PotionEffect var3 : var1.getActivePotionEffects()) {
         var1.removePotionEffect(var3.getType());
      }

      var1.setHealth(20.0);
      var1.setFoodLevel(20);
      var1.setSaturation(20.0F);
      var1.setFireTicks(0);
      var1.setFallDistance(0.0F);
      var1.setWalkSpeed(0.2F);
      var1.setExp(0.0F);
      var1.setLevel(0);
   }

   void lobbyHud() {
      for (Player var2 : Bukkit.getOnlinePlayers()) {
         if (!this.gameOf.containsKey(var2.getUniqueId())) {
            StrikePlugin.Party var3 = this.partyOf.get(var2.getUniqueId());
            String var4 = var3 == null
               ? "§7Partin yok · §fParti Menüsü§7 ile kur"
               : "§6Parti §f"
                  + var3.members.size()
                  + " kişi §7· "
                  + modeName(var3.mode)
                  + " · "
                  + StrikeMap.ALL.get(var3.map).name
                  + (var3.leader.equals(var2.getUniqueId()) ? " §a(lider)" : "");
            var2.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(var4));
            if (var2.getLocation().getY() < 50.0 && var2.getWorld() == this.world) {
               var2.teleport(this.lobby);
            }
         }
      }
   }

   static String modeName(Game.Mode var0) {
      return switch (var0) {
         case COMP -> "Rekabetçi (bomba)";
         case DUEL -> "1v1 Düello";
         case DM -> "Ölüm maçı";
      };
   }

   ItemStack item(Weapon var1, String var2) {
      ItemStack var3 = new ItemStack(var1.item);
      ItemMeta var4 = var3.getItemMeta();
      var4.setDisplayName("§f" + var1.name);
      ArrayList var5 = new ArrayList();
      if (var1.gun()) {
         var5.add("§7Hasar §f" + (int)var1.damage + (var1.pellets > 1 ? "×" + var1.pellets : "") + "  §7Şarjör §f" + var1.mag + "/" + var1.reserve);
         var5.add("§7Atış hızı §f" + var1.rpm + "/dk  §7Zırh delme §f%" + Math.round(var1.armorPen * 100.0));
         var5.add("§8Sağ tık: ateş · F ya da Q: şarjör" + (var1.scope ? " · Sol tık: dürbün" : ""));
      } else if (var1.grenade()) {
         var5.add("§8Sağ tık: fırlat");
      } else if (var1.kind == Weapon.Kind.KNIFE) {
         var5.add("§8Sol tık: kes · Sağ tık: sapla (arkadan öldürür)");
      } else if (var1.kind == Weapon.Kind.BOMB) {
         var5.add("§8Bölgede sağ tıkı basılı tut: kur");
      } else if (var1.kind == Weapon.Kind.TASER) {
         var5.add("§8Sağ tık: yakın mesafede tek atış");
      }

      var4.setLore(var5);
      var4.setCustomModelData(var1.model);
      var4.setUnbreakable(true);
      var4.addItemFlags(new ItemFlag[]{ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE, ItemFlag.HIDE_ENCHANTS});
      var4.getPersistentDataContainer().set(this.KEY_W, PersistentDataType.STRING, var1.id);
      if (var2 != null) {
         var4.getPersistentDataContainer().set(this.KEY_UID, PersistentDataType.STRING, var2);
      }

      var3.setItemMeta(var4);
      return var3;
   }

   ItemStack shopItem() {
      return this.ui(Material.EMERALD, "shop", "§a§lMarket §7(sağ tık)", "§7Silah, zırh ve bomba al.");
   }

   ItemStack ui(Material var1, String var2, String var3, String... var4) {
      ItemStack var5 = new ItemStack(var1);
      ItemMeta var6 = var5.getItemMeta();
      var6.setDisplayName(var3);
      var6.setLore(Arrays.asList(var4));
      var6.addItemFlags(new ItemFlag[]{ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_POTION_EFFECTS});
      var6.getPersistentDataContainer().set(this.KEY_UI, PersistentDataType.STRING, var2);
      var5.setItemMeta(var6);
      return var5;
   }

   Weapon weaponOf(ItemStack var1) {
      return var1 != null && var1.hasItemMeta()
         ? Weapon.get((String)var1.getItemMeta().getPersistentDataContainer().get(this.KEY_W, PersistentDataType.STRING))
         : null;
   }

   String uidOf(ItemStack var1) {
      return var1 != null && var1.hasItemMeta() ? (String)var1.getItemMeta().getPersistentDataContainer().get(this.KEY_UID, PersistentDataType.STRING) : null;
   }

   String uiOf(ItemStack var1) {
      return var1 != null && var1.hasItemMeta() ? (String)var1.getItemMeta().getPersistentDataContainer().get(this.KEY_UI, PersistentDataType.STRING) : null;
   }

   void startGame(Player var1, StrikePlugin.Party var2) {
      if (var2.game != null) {
         var1.sendMessage("§cPartin zaten oyunda.");
      } else {
         ArrayList<Player> var3 = new ArrayList<>();

         for (UUID var5 : var2.members) {
            Player var6 = Bukkit.getPlayer(var5);
            if (var6 != null && !this.gameOf.containsKey(var5)) {
               var3.add(var6);
            }
         }

         Game.Mode var10 = var2.mode;
         if (var3.size() < 2) {
            var1.sendMessage("§cMaç için en az 2 kişi gerekli. §7Parti Menüsü → Davet et.");
         } else if (var10 == Game.Mode.DUEL && var3.size() != 2) {
            var1.sendMessage("§cDüello için partide tam 2 kişi olmalı (şu an " + var3.size() + ").");
         } else {
            StrikeMap var11 = StrikeMap.ALL.get(var2.map);
            if (var11 == null) {
               var11 = StrikeMap.ALL.get("col");
            }

            if (var11.duel && var10 == Game.Mode.COMP) {
               var11 = StrikeMap.ALL.get("col");
            }

            int var12 = 0;

            while (this.slots.contains(var12)) {
               var12++;
            }

            this.slots.add(var12);
            Game var7 = new Game(this, var2, var10, var11, var12);
            var2.game = var7;
            this.games.add(var7);

            for (Player var9 : var3) {
               this.gameOf.put(var9.getUniqueId(), var7);
            }

            var7.start(var3);
         }
      }
   }

   void gameOver(Game var1) {
      this.games.remove(var1);
      this.slots.remove(var1.slot);
      if (var1.party.game == var1) {
         var1.party.game = null;
      }

      this.gameOf.values().removeIf(var1x -> var1x == var1);
   }

   StrikePlugin.Party create(Player var1) {
      StrikePlugin.Party var2 = this.partyOf.get(var1.getUniqueId());
      if (var2 != null) {
         return var2;
      } else {
         var2 = new StrikePlugin.Party(var1.getUniqueId());
         this.partyOf.put(var1.getUniqueId(), var2);
         var1.sendMessage("§aParti kuruldu. §7Parti Menüsü → §fDavet et §7ile arkadaşlarını çağır.");
         return var2;
      }
   }

   void invite(Player var1, Player var2) {
      StrikePlugin.Party var3 = this.create(var1);
      if (!var3.leader.equals(var1.getUniqueId())) {
         var1.sendMessage("§cSadece parti lideri davet edebilir.");
      } else if (!var2.equals(var1) && !var3.members.contains(var2.getUniqueId())) {
         this.invites.computeIfAbsent(var2.getUniqueId(), var0 -> new LinkedHashSet<>()).add(var1.getUniqueId());
         var1.sendMessage("§a" + var2.getName() + " davet edildi.");
         TextComponent var4 = new TextComponent("§6" + var1.getName() + " §eseni partisine çağırıyor. ");
         TextComponent var5 = new TextComponent("§a§l[KATIL]");
         var5.setClickEvent(new ClickEvent(Action.RUN_COMMAND, "/parti kabul " + var1.getName()));
         var5.setHoverEvent(new HoverEvent(net.md_5.bungee.api.chat.HoverEvent.Action.SHOW_TEXT, new Content[]{new Text("Partiye katıl")}));
         var4.addExtra(var5);
         var2.spigot().sendMessage(var4);
         var2.sendMessage("§7(ya da Parti Menüsü → Davetler)");
         var2.playSound(var2.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0F, 1.5F);
      }
   }

   void accept(Player var1, Player var2) {
      Set var3 = this.invites.get(var1.getUniqueId());
      if (var3 != null && var3.contains(var2.getUniqueId())) {
         StrikePlugin.Party var4 = this.partyOf.get(var2.getUniqueId());
         if (var4 != null && var4.leader.equals(var2.getUniqueId())) {
            this.leaveParty(var1, false);
            var3.remove(var2.getUniqueId());
            var4.members.add(var1.getUniqueId());
            this.partyOf.put(var1.getUniqueId(), var4);
            this.partySay(var4, "§a" + var1.getName() + " partiye katıldı.");
         } else {
            var1.sendMessage("§cO parti artık yok.");
            var3.remove(var2.getUniqueId());
         }
      } else {
         var1.sendMessage("§cBu kişiden davetin yok.");
      }
   }

   void leaveParty(Player var1, boolean var2) {
      StrikePlugin.Party var3 = this.partyOf.remove(var1.getUniqueId());
      if (var3 != null) {
         var3.members.remove(var1.getUniqueId());
         if (var2) {
            var1.sendMessage("§7Partiden ayrıldın.");
         }

         if (!var3.members.isEmpty()) {
            if (var3.leader.equals(var1.getUniqueId())) {
               var3.leader = var3.members.iterator().next();
               Player var4 = Bukkit.getPlayer(var3.leader);
               this.partySay(var3, "§e" + (var4 != null ? var4.getName() : "?") + " artık parti lideri.");
            }

            this.partySay(var3, "§7" + var1.getName() + " partiden ayrıldı.");
         }
      }
   }

   void partySay(StrikePlugin.Party var1, String var2) {
      for (UUID var4 : var1.members) {
         Player var5 = Bukkit.getPlayer(var4);
         if (var5 != null) {
            var5.sendMessage("§6[Parti] §r" + var2);
         }
      }
   }

   Inventory menu(String var1, int var2, int var3, String var4) {
      StrikePlugin.Menu var5 = new StrikePlugin.Menu(var1, var2);
      var5.inv = Bukkit.createInventory(var5, var3, var4);
      return var5.inv;
   }

   void openParty(Player var1) {
      StrikePlugin.Party var2 = this.partyOf.get(var1.getUniqueId());
      Inventory var3 = this.menu("party", 0, 36, "§6Parti");
      if (var2 == null) {
         var3.setItem(11, this.ui(Material.LIME_CONCRETE, "p:create", "§a§lParti Kur", "§7Kendi partini kur, sonra arkadaşlarını davet et."));
         Set<UUID> var11 = this.invites.getOrDefault(var1.getUniqueId(), Set.of());
         ArrayList<String> var12 = new ArrayList<>();

         for (UUID var16 : var11) {
            Player var17 = Bukkit.getPlayer(var16);
            if (var17 != null) {
               var12.add("§f" + var17.getName() + " §7— tıkla, katıl");
            }
         }

         var3.setItem(
            15,
            this.ui(
               Material.WRITABLE_BOOK,
               "p:invites",
               "§e§lDavetler §7(" + var12.size() + ")",
               var12.isEmpty() ? new String[]{"§7Davetin yok."} : var12.toArray(new String[0])
            )
         );
         var1.openInventory(var3);
      } else {
         boolean var4 = var2.leader.equals(var1.getUniqueId());
         int var5 = 0;

         for (UUID var7 : var2.members) {
            if (var5 >= 9) {
               break;
            }

            Player var8 = Bukkit.getPlayer(var7);
            ItemStack var9 = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta var10 = (SkullMeta)var9.getItemMeta();
            var10.setOwningPlayer(Bukkit.getOfflinePlayer(var7));
            var10.setDisplayName((var7.equals(var2.leader) ? "§6★ " : "§f") + (var8 != null ? var8.getName() : "?"));
            if (var4 && !var7.equals(var2.leader)) {
               var10.setLore(List.of("§cTıkla: partiden çıkar"));
            }

            var10.getPersistentDataContainer().set(this.KEY_UI, PersistentDataType.STRING, "p:kick:" + var7);
            var9.setItemMeta(var10);
            var3.setItem(var5++, var9);
         }

         String var13 = var4 ? "§eTıkla: değiştir" : "§8Sadece lider değiştirebilir";
         var3.setItem(
            19,
            this.ui(
               Material.IRON_SWORD,
               "p:mode",
               "§f§lMod: §e" + modeName(var2.mode),
               "§7Rekabetçi: bomba kur/imha et, iki devre.",
               "§71v1 Düello: iki kişi, tur tur.",
               "§7Ölüm maçı: herkes herkese, 30 öldüren kazanır.",
               var13
            )
         );
         StrikeMap var15 = StrikeMap.ALL.get(var2.map);
         var3.setItem(
            20,
            this.ui(
               Material.FILLED_MAP, "p:map", "§f§lHarita: §e" + var15.name, "§7" + var15.desc, var4 ? "§eTıkla: harita seç" : "§8Sadece lider değiştirebilir"
            )
         );
         var3.setItem(
            21,
            this.ui(
               var2.unlimited ? Material.GOLD_BLOCK : Material.GOLD_NUGGET,
               "p:money",
               "§f§lSınırsız para: " + (var2.unlimited ? "§aAÇIK" : "§cKAPALI"),
               "§7Açıkken herkesin parası hep $16000.",
               var13
            )
         );
         var3.setItem(
            22,
            this.ui(
               var2.anyItem ? Material.ENDER_CHEST : Material.CHEST,
               "p:any",
               "§f§lİstediğin item: " + (var2.anyItem ? "§aAÇIK" : "§cKAPALI"),
               "§7Açıkken markette Minecraft'ın",
               "§7bütün eşyaları da olur.",
               var13
            )
         );
         var3.setItem(23, this.ui(Material.CLOCK, "p:rounds", "§f§lKazanmak için tur: §e" + var2.toWin, "§75, 9 ya da 13 (CS2: 13).", var13));
         var3.setItem(24, this.ui(Material.WRITABLE_BOOK, "p:invite", "§a§lDavet et", "§7Lobideki oyuncuları partiye çağır."));
         if (var4) {
            var3.setItem(
               25,
               this.ui(Material.LIME_CONCRETE, "p:start", "§a§lMAÇI BAŞLAT", "§7" + var2.members.size() + " kişi · " + modeName(var2.mode) + " · " + var15.name)
            );
         }

         var3.setItem(31, this.ui(Material.BARRIER, "p:leave", "§c§lPartiden ayrıl"));
         var1.openInventory(var3);
      }
   }

   void openMaps(Player var1) {
      Inventory var2 = this.menu("maps", 0, 27, "§6Harita seç");
      int var3 = 10;

      for (StrikeMap var5 : StrikeMap.ALL.values()) {
         var2.setItem(
            var3++,
            this.ui(
               var5.icon,
               "map:" + var5.id,
               "§e§l" + var5.name,
               "§7" + var5.desc,
               var5.duel ? "§8(düello ve ölüm maçı için)" : "§8Bomba bölgeleri: A ve B",
               "§aTıkla: seç"
            )
         );
      }

      var1.openInventory(var2);
   }

   void openInvite(Player var1) {
      Inventory var2 = this.menu("invite", 0, 54, "§6Davet et");
      int var3 = 0;

      for (Player var5 : Bukkit.getOnlinePlayers()) {
         if (!var5.equals(var1) && !this.gameOf.containsKey(var5.getUniqueId()) && var3 < 54) {
            StrikePlugin.Party var6 = this.partyOf.get(var5.getUniqueId());
            if (var6 == null || var6 != this.partyOf.get(var1.getUniqueId())) {
               ItemStack var7 = new ItemStack(Material.PLAYER_HEAD);
               SkullMeta var8 = (SkullMeta)var7.getItemMeta();
               var8.setOwningPlayer(var5);
               var8.setDisplayName("§f" + var5.getName());
               var8.setLore(List.of(var6 != null ? "§7(başka bir partide)" : "§aTıkla: davet et"));
               var8.getPersistentDataContainer().set(this.KEY_UI, PersistentDataType.STRING, "inv:" + var5.getUniqueId());
               var7.setItemMeta(var8);
               var2.setItem(var3++, var7);
            }
         }
      }

      if (var3 == 0) {
         var2.setItem(22, this.ui(Material.GRAY_DYE, "none", "§7Lobide davet edilecek kimse yok."));
      }

      var1.openInventory(var2);
   }

   void openShop(Player var1) {
      Game var2 = this.gameOf.get(var1.getUniqueId());
      if (var2 == null) {
         var1.sendMessage("§7Market sadece maçta açılır.");
      } else {
         Game.PS var3 = var2.players.get(var1.getUniqueId());
         if (var2.canBuy(var1, var3, true)) {
            int var4 = var2.sideOf(var3);
            Inventory var5 = this.menu("shop", 0, 54, "§aMarket §7· §a$" + (var2.unlimited ? "∞" : var3.money));
            int[] var6 = new int[6];

            for (Weapon var8 : Weapon.SHOP) {
               if ((var8.side != Weapon.Side.T || var4 != 1) && (var8.side != Weapon.Side.CT || var4 != 0)) {
                  byte var9 = switch (var8.kind) {
                     case PISTOL -> 0;
                     case SMG -> 1;
                     case SHOTGUN, HEAVY -> 2;
                     case RIFLE, SNIPER -> 3;
                     default -> 4;
                  };
                  if (var6[var9] < 9) {
                     ItemStack var10 = this.item(var8, null);
                     ItemMeta var11 = var10.getItemMeta();
                     ArrayList var12 = new ArrayList(var11.getLore() == null ? List.of() : var11.getLore());
                     boolean var13 = var2.unlimited || var3.money >= var8.price;
                     var12.add(0, (var13 ? "§a" : "§c") + "$" + var8.price + (var8.reward != 300 && var8.gun() ? " §7· öldürme ödülü §f$" + var8.reward : ""));
                     var11.setLore(var12);
                     var11.getPersistentDataContainer().set(this.KEY_UI, PersistentDataType.STRING, "buy:" + var8.id);
                     var10.setItemMeta(var11);
                     var5.setItem(var9 * 9 + var6[var9]++, var10);
                  }
               }
            }

            if (var2.anyItem) {
               var5.setItem(53, this.ui(Material.ENDER_CHEST, "allitems", "§d§lBütün eşyalar", "§7Minecraft'ın her eşyası (bedava)."));
            }

            var1.openInventory(var5);
         }
      }
   }

   void openAllItems(Player var1, int var2) {
      int var3 = (ITEMS.size() + 44) / 45;
      var2 = Math.max(0, Math.min(var3 - 1, var2));
      Inventory var4 = this.menu("all", var2, 54, "§dBütün eşyalar §7" + (var2 + 1) + "/" + var3);

      for (int var5 = 0; var5 < 45 && var2 * 45 + var5 < ITEMS.size(); var5++) {
         var4.setItem(var5, new ItemStack(ITEMS.get(var2 * 45 + var5)));
      }

      var4.setItem(45, this.ui(Material.ARROW, "all:prev", "§f« Önceki"));
      var4.setItem(49, this.ui(Material.BARRIER, "all:close", "§cKapat", "§7Tık: 1 tane · Shift+tık: deste"));
      var4.setItem(53, this.ui(Material.ARROW, "all:next", "§fSonraki »"));
      var1.openInventory(var4);
   }

   @EventHandler(
      priority = EventPriority.HIGH
   )
   public void click(InventoryClickEvent var1) {
      if (var1.getWhoClicked() instanceof Player var2) {
         Game var15 = this.gameOf.get(var2.getUniqueId());
         if (!(var1.getInventory().getHolder() instanceof StrikePlugin.Menu var4)) {
            if (var15 != null && var1.getSlotType() == SlotType.ARMOR) {
               var1.setCancelled(true);
            }

            if (var15 == null && var2.getGameMode() != GameMode.CREATIVE) {
               var1.setCancelled(true);
            }
         } else {
            var1.setCancelled(true);
            if (var1.getClickedInventory() != null && var1.getClickedInventory() == var1.getInventory()) {
               ItemStack var16 = var1.getCurrentItem();
               if (var16 != null) {
                  String var6 = this.uiOf(var16);
                  StrikePlugin.Party var7 = this.partyOf.get(var2.getUniqueId());
                  boolean var8 = var7 != null && var7.leader.equals(var2.getUniqueId());
                  if (var4.kind.equals("all")) {
                     if ("all:prev".equals(var6)) {
                        this.openAllItems(var2, var4.page - 1);
                     } else if ("all:next".equals(var6)) {
                        this.openAllItems(var2, var4.page + 1);
                     } else if ("all:close".equals(var6)) {
                        var2.closeInventory();
                     } else if (var15 != null && var15.anyItem) {
                        ItemStack var17 = new ItemStack(var16.getType(), var1.isShiftClick() ? var16.getType().getMaxStackSize() : 1);
                        var2.getInventory().addItem(new ItemStack[]{var17});
                        var2.playSound(var2.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.6F, 1.3F);
                     }
                  } else if (var6 != null) {
                     if (var6.startsWith("buy:")) {
                        if (var15 != null) {
                           var15.buy(var2, Weapon.get(var6.substring(4)));
                           this.openShop(var2);
                        }
                     } else if (var6.equals("allitems")) {
                        this.openAllItems(var2, 0);
                     } else if (var6.startsWith("map:")) {
                        if (var8) {
                           var7.map = var6.substring(4);
                           this.partySay(var7, "§eHarita: §f" + StrikeMap.ALL.get(var7.map).name);
                        }

                        this.openParty(var2);
                     } else if (var6.startsWith("inv:")) {
                        Player var9 = Bukkit.getPlayer(UUID.fromString(var6.substring(4)));
                        if (var9 != null) {
                           this.invite(var2, var9);
                        }

                        this.openInvite(var2);
                     } else {
                        switch (var6) {
                           case "p:create":
                              this.create(var2);
                              this.openParty(var2);
                              break;
                           case "p:invites":
                              Set<UUID> var18 = this.invites.getOrDefault(var2.getUniqueId(), Set.of());

                              for (UUID var13 : new ArrayList<>(var18)) {
                                 Player var14 = Bukkit.getPlayer(var13);
                                 if (var14 != null) {
                                    this.accept(var2, var14);
                                    break;
                                 }
                              }

                              this.openParty(var2);
                              break;
                           case "p:mode":
                              if (var8) {
                                 var7.mode = Game.Mode.values()[(var7.mode.ordinal() + 1) % 3];
                                 if (var7.mode == Game.Mode.DUEL && !"arena".equals(var7.map)) {
                                    var7.map = "arena";
                                 } else if (var7.mode == Game.Mode.COMP && "arena".equals(var7.map)) {
                                    var7.map = "col";
                                 }
                              }

                              this.openParty(var2);
                              break;
                           case "p:map":
                              if (var8) {
                                 this.openMaps(var2);
                              }
                              break;
                           case "p:money":
                              if (var8) {
                                 var7.unlimited = !var7.unlimited;
                              }

                              this.openParty(var2);
                              break;
                           case "p:any":
                              if (var8) {
                                 var7.anyItem = !var7.anyItem;
                              }

                              this.openParty(var2);
                              break;
                           case "p:rounds":
                              if (var8) {
                                 var7.toWin = var7.toWin == 5 ? 9 : (var7.toWin == 9 ? 13 : 5);
                              }

                              this.openParty(var2);
                              break;
                           case "p:invite":
                              if (var7 == null) {
                                 this.create(var2);
                              }

                              this.openInvite(var2);
                              break;
                           case "p:start":
                              var2.closeInventory();
                              if (var8) {
                                 this.startGame(var2, var7);
                              }
                              break;
                           case "p:leave":
                              var2.closeInventory();
                              this.leaveParty(var2, true);
                              break;
                           default:
                              if (var6.startsWith("p:kick:") && var8) {
                                 UUID var11 = UUID.fromString(var6.substring(7));
                                 if (!var11.equals(var7.leader)) {
                                    Player var12 = Bukkit.getPlayer(var11);
                                    var7.members.remove(var11);
                                    this.partyOf.remove(var11);
                                    if (var12 != null) {
                                       var12.sendMessage("§cPartiden çıkarıldın.");
                                    }

                                    this.openParty(var2);
                                 }
                              }
                        }
                     }
                  }
               }
            }
         }
      }
   }

   @EventHandler
   public void drag(InventoryDragEvent var1) {
      if (var1.getInventory().getHolder() instanceof StrikePlugin.Menu) {
         var1.setCancelled(true);
      }
   }

   @EventHandler(
      priority = EventPriority.HIGH
   )
   public void interact(PlayerInteractEvent var1) {
      if (var1.getHand() == EquipmentSlot.OFF_HAND) {
         var1.setCancelled(true);
      } else {
         Player var2 = var1.getPlayer();
         ItemStack var3 = var2.getInventory().getItemInMainHand();
         String var4 = this.uiOf(var3);
         boolean var5 = var1.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_AIR
            || var1.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK;
         Game var6 = this.gameOf.get(var2.getUniqueId());
         if (var4 == null) {
            if (var6 == null) {
               if (var2.getGameMode() != GameMode.CREATIVE) {
                  var1.setCancelled(true);
               }
            } else {
               Weapon var7 = this.weaponOf(var3);
               if (var5 && var1.getClickedBlock() != null && var6.bomb != null && var1.getClickedBlock().getLocation().equals(var6.bomb)) {
                  var1.setCancelled(true);
                  var6.defusePress(var2, var1.getClickedBlock());
               } else if (var7 == null) {
                  if (!var6.anyItem || var1.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
                     var1.setCancelled(true);
                     if (var5 && var6.bomb != null && var6.players.get(var2.getUniqueId()) != null) {
                        var6.defusePress(var2, null);
                     }
                  }
               } else {
                  var1.setCancelled(true);
                  if (var5) {
                     if (var7.gun()) {
                        var6.trigger(var2);
                     } else if (var7.grenade()) {
                        var6.throwGrenade(var2, var3, var7);
                     } else if (var7.kind == Weapon.Kind.KNIFE) {
                        var6.stab(var2);
                     } else if (var7.kind == Weapon.Kind.BOMB) {
                        var6.plantPress(var2);
                     } else if (var7.kind == Weapon.Kind.TASER) {
                        var6.zeus(var2);
                     } else if (var7.id.equals("kit") && var6.bomb != null) {
                        var6.defusePress(var2, null);
                     }
                  } else if (var7.scope) {
                     var6.toggleScope(var2);
                  }
               }
            }
         } else {
            var1.setCancelled(true);
            if (var5) {
               switch (var4) {
                  case "party":
                     this.openParty(var2);
                     break;
                  case "help":
                     this.help(var2);
                     break;
                  case "quick":
                     StrikePlugin.Party var9 = this.create(var2);
                     if (var9.members.size() == 2 && var9.leader.equals(var2.getUniqueId())) {
                        var9.mode = Game.Mode.DUEL;
                        var9.map = "arena";
                        this.startGame(var2, var9);
                     } else {
                        var2.sendMessage("§7Hızlı düello: partide tam 2 kişi olun (Parti Menüsü → Davet et), sonra tekrar tıkla.");
                     }
                     break;
                  case "shop":
                     this.openShop(var2);
               }
            }
         }
      }
   }

   @EventHandler
   public void interactEntity(PlayerInteractEntityEvent var1) {
      Game var2 = this.gameOf.get(var1.getPlayer().getUniqueId());
      if (var2 != null) {
         var1.setCancelled(true);
         if (var1.getHand() == EquipmentSlot.HAND) {
            Weapon var3 = this.weaponOf(var1.getPlayer().getInventory().getItemInMainHand());
            if (var3 != null && var3.gun()) {
               var2.trigger(var1.getPlayer());
            } else if (var3 != null && var3.kind == Weapon.Kind.KNIFE && var1.getRightClicked() instanceof Player var4) {
               var2.knife(var1.getPlayer(), var4, true);
            }
         }
      }
   }

   @EventHandler(
      priority = EventPriority.HIGH
   )
   public void hit(EntityDamageByEntityEvent var1) {
      if (var1.getEntity() instanceof Player var2) {
         var1.setCancelled(true);
         if (var1.getDamager() instanceof Player var6) {
            Game var7 = this.gameOf.get(var6.getUniqueId());
            if (var7 != null && var7 == this.gameOf.get(var2.getUniqueId())) {
               Weapon var5 = this.weaponOf(var6.getInventory().getItemInMainHand());
               if (var5 != null && var5.kind == Weapon.Kind.KNIFE) {
                  var7.knife(var6, var2, false);
               } else if (var5 != null && var5.scope) {
                  var7.toggleScope(var6);
               }
            }
         }
      }
   }

   @EventHandler(
      priority = EventPriority.HIGH
   )
   public void damage(EntityDamageEvent var1) {
      if (var1.getEntity() instanceof Player var2 && !(var1 instanceof EntityDamageByEntityEvent)) {
         var1.setCancelled(true);
         Game var5 = this.gameOf.get(var2.getUniqueId());
         if (var5 == null) {
            if (var1.getCause() == DamageCause.VOID) {
               var2.teleport(this.lobby);
            }

            return;
         }

         Game.PS var4 = var5.players.get(var2.getUniqueId());
         if (var4 == null) {
            return;
         }

         switch (var1.getCause()) {
            case FALL:
               if (var1.getDamage() > 2.0) {
                  var5.damage(var2, var4, null, null, (var1.getDamage() - 2.0) * 6.0, false, 1.0);
               }
               break;
            case VOID:
               var5.damage(var2, var4, null, null, 1000.0, false, 1.0);
               break;
            case LAVA:
            case FIRE:
            case FIRE_TICK:
               var2.setFireTicks(0);
               var5.damage(var2, var4, null, null, 4.0, false, 1.0);
               break;
            case DROWNING:
            case SUFFOCATION:
               var5.damage(var2, var4, null, null, 5.0, false, 1.0);
         }

         return;
      }
   }

   @EventHandler
   public void death(PlayerDeathEvent var1) {
      var1.setDeathMessage(null);
      var1.getDrops().clear();
   }

   @EventHandler
   public void respawn(PlayerRespawnEvent var1) {
      if (!this.gameOf.containsKey(var1.getPlayer().getUniqueId())) {
         var1.setRespawnLocation(this.lobby);
      }
   }

   @EventHandler
   public void swap(PlayerSwapHandItemsEvent var1) {
      var1.setCancelled(true);
      this.reloadKey(var1.getPlayer());
   }

   @EventHandler
   public void drop(PlayerDropItemEvent var1) {
      Player var2 = var1.getPlayer();
      Game var3 = this.gameOf.get(var2.getUniqueId());
      if (var3 == null) {
         var1.setCancelled(true);
      } else {
         Weapon var4 = this.weaponOf(var1.getItemDrop().getItemStack());
         if (var4 == null
            || var4.kind != Weapon.Kind.BOMB
               && !var4.grenade()
               && (var4.kind != Weapon.Kind.PISTOL || !var2.isSneaking())
               && (var4.slot != Weapon.Slot.PRIMARY || !var2.isSneaking())) {
            var1.setCancelled(true);
            if (var4 == null && this.uiOf(var1.getItemDrop().getItemStack()) == null && var3.anyItem) {
               var1.setCancelled(false);
            } else {
               this.reloadKey(var2);
            }
         }
      }
   }

   void reloadKey(Player var1) {
      Game var2 = this.gameOf.get(var1.getUniqueId());
      if (var2 != null) {
         Game.PS var3 = var2.players.get(var1.getUniqueId());
         ItemStack var4 = var1.getInventory().getItemInMainHand();
         Weapon var5 = this.weaponOf(var4);
         String var6 = this.uidOf(var4);
         if (var3 != null && var3.alive && var5 != null && var5.gun() && var6 != null) {
            var2.reload(var1, var3, var5, var6);
         }
      }
   }

   @EventHandler
   public void held(PlayerItemHeldEvent var1) {
      Game var2 = this.gameOf.get(var1.getPlayer().getUniqueId());
      if (var2 != null) {
         Bukkit.getScheduler().runTask(this, () -> var2.speed(var1.getPlayer()));
      }
   }

   @EventHandler
   public void pickup(EntityPickupItemEvent var1) {
      if (!(var1.getEntity() instanceof Player var2)) {
         var1.setCancelled(true);
      } else {
         Game var7 = this.gameOf.get(var2.getUniqueId());
         Item var4 = var1.getItem();
         if (var7 == null) {
            var1.setCancelled(true);
         } else {
            Game.PS var5 = var7.players.get(var2.getUniqueId());
            Weapon var6 = this.weaponOf(var4.getItemStack());
            if (var5 != null && var5.alive && var6 != null) {
               var1.setCancelled(true);
               if (var6.kind == Weapon.Kind.BOMB) {
                  if (var7.sideOf(var5) == 0) {
                     var2.getInventory().setItem(4, var4.getItemStack());
                     var4.remove();
                     var2.sendMessage("§6Bombayı aldın.");
                  }
               } else if (var6.slot == Weapon.Slot.PRIMARY || var6.slot == Weapon.Slot.SECONDARY) {
                  if (var7.firstOf(var2.getInventory(), var6.slot) == null) {
                     var2.getInventory().setItem(var6.slot == Weapon.Slot.PRIMARY ? 0 : 1, var4.getItemStack());
                     var4.remove();
                     var2.playSound(var2.getLocation(), Sound.ITEM_ARMOR_EQUIP_CHAIN, 1.0F, 1.2F);
                  }
               }
            } else {
               if (var6 != null || !var7.anyItem) {
                  var1.setCancelled(true);
               }
            }
         }
      }
   }

   @EventHandler
   public void move(PlayerMoveEvent var1) {
      Game var2 = this.gameOf.get(var1.getPlayer().getUniqueId());
      if (var2 != null && var1.getTo() != null) {
         Game.PS var3 = var2.players.get(var1.getPlayer().getUniqueId());
         boolean var4 = var2.frozen() && var3 != null && var3.alive || var3 != null && (var3.plantTicks > 0 || var3.defuseTicks > 0);
         if (var4 && (var1.getFrom().getX() != var1.getTo().getX() || var1.getFrom().getZ() != var1.getTo().getZ())) {
            Location var5 = var1.getFrom().clone();
            var5.setYaw(var1.getTo().getYaw());
            var5.setPitch(var1.getTo().getPitch());
            var1.setTo(var5);
         }
      }
   }

   @EventHandler
   public void place(BlockPlaceEvent var1) {
      Game var2 = this.gameOf.get(var1.getPlayer().getUniqueId());
      if (var1.getPlayer().getGameMode() != GameMode.CREATIVE && (var2 == null || !var2.anyItem)) {
         var1.setCancelled(true);
      }
   }

   @EventHandler
   public void brk(BlockBreakEvent var1) {
      if (var1.getPlayer().getGameMode() != GameMode.CREATIVE) {
         var1.setCancelled(true);
      }
   }

   @EventHandler
   public void food(FoodLevelChangeEvent var1) {
      var1.setCancelled(true);
      var1.getEntity().setFoodLevel(20);
   }

   @EventHandler
   public void weather(WeatherChangeEvent var1) {
      if (var1.toWeatherState()) {
         var1.setCancelled(true);
      }
   }

   @EventHandler
   public void join(PlayerJoinEvent var1) {
      var1.setJoinMessage("§7» §f" + var1.getPlayer().getName() + " §7Pisan Strike'a geldi");
      Player var2 = var1.getPlayer();
      Bukkit.getScheduler().runTask(this, () -> {
         this.toLobby(var2);
         var2.sendTitle("§6§lPISAN §f§lSTRIKE", "§7Parti Menüsü ile maç kur", 10, 60, 20);
      });
      if (var2.getName().startsWith("(mobil)")) {
         Bukkit.getScheduler().runTaskLater(this, () -> {
            if (var2.isOnline() && !this.webVerified.contains(var2.getUniqueId())) {
               var2.kickPlayer("Bu ad sadece tarayıcıdan girenler için.");
            }
         }, 200L);
      }
   }

   @EventHandler
   public void quit(PlayerQuitEvent var1) {
      Player var2 = var1.getPlayer();
      var1.setQuitMessage(null);
      Game var3 = this.gameOf.remove(var2.getUniqueId());
      if (var3 != null) {
         var3.leave(var2);
      }

      this.leaveParty(var2, false);
      this.invites.remove(var2.getUniqueId());
      this.webVerified.remove(var2.getUniqueId());
   }

   void help(Player var1) {
      var1.sendMessage("§6§lPISAN STRIKE §7— CS2 tarzı oyun");
      var1.sendMessage("§e1. §fParti Menüsü (yıldız) → Parti kur → Davet et.");
      var1.sendMessage("§e2. §fMod, harita, sınırsız para, istediğin item ve tur sayısını seç; §aMAÇI BAŞLAT§f.");
      var1.sendMessage("§e3. §fMaçta: §eSağ tık§f ateş (basılı tut: otomatik), §eSol tık§f dürbün, §eF§f ya da §eQ§f şarjör.");
      var1.sendMessage("§e4. §fMarket: zümrüt (9. slot) ya da §e/market§f — sadece doğduğun yerde ve tur başında.");
      var1.sendMessage("§e5. §fTeröristler bombayı A ya da B'ye kurar (sağ tık basılı), Anti-Teröristler bombaya sağ tıkı basılı tutarak imha eder.");
      var1.sendMessage("§7Komutlar: /parti, /market, /ayril");
   }

   public boolean onCommand(CommandSender var1, Command var2, String var3, String[] var4) {
      if (var1 instanceof Player var5) {
         String var6 = var2.getName().toLowerCase(Locale.ROOT);
         switch (var6) {
            case "market":
               this.openShop(var5);
               break;
            case "ayril":
               if (this.gameOf.containsKey(var5.getUniqueId())) {
                  this.toLobby(var5);
               } else {
                  var5.sendMessage("§7Maçta değilsin.");
               }
               break;
            case "parti":
               if (var4.length == 0 || var4[0].equalsIgnoreCase("menu")) {
                  this.openParty(var5);
                  return true;
               }

               StrikePlugin.Party var8 = this.partyOf.get(var5.getUniqueId());
               String var9 = var4[0].toLowerCase(Locale.ROOT);
               switch (var9) {
                  case "kur":
                     this.create(var5);
                     return true;
                  case "davet":
                     Player var12 = var4.length > 1 ? Bukkit.getPlayerExact(var4[1]) : null;
                     if (var12 == null) {
                        var5.sendMessage("§cOyuncu bulunamadı.");
                     } else {
                        this.invite(var5, var12);
                     }

                     return true;
                  case "kabul":
                     Player var11 = var4.length > 1 ? Bukkit.getPlayerExact(var4[1]) : null;
                     if (var11 == null) {
                        var5.sendMessage("§cOyuncu bulunamadı.");
                     } else {
                        this.accept(var5, var11);
                     }

                     return true;
                  case "ayril":
                     this.leaveParty(var5, true);
                     return true;
                  case "baslat":
                     if (var8 != null && var8.leader.equals(var5.getUniqueId())) {
                        this.startGame(var5, var8);
                     } else {
                        var5.sendMessage("§cParti lideri değilsin.");
                     }

                     return true;
                  default:
                     var5.sendMessage("§7/parti [kur|davet <oyuncu>|kabul <lider>|ayril|baslat]");
                     return true;
               }
            default:
               return false;
         }

         return true;
      } else {
         var1.sendMessage("Sadece oyuncular.");
         return true;
      }
   }

   void startWebChannel() {
      Bukkit.getMessenger().registerIncomingPluginChannel(this, "pisan:web", (var1, var2, var3) -> {
         try {
            String var4 = new String(var3, StandardCharsets.UTF_8);
            JsonObject var5 = JsonParser.parseString(var4).getAsJsonObject();
            String var6 = this.getConfig().getString("web-relay-key", "").trim();
            if (var6.length() < 8 || !var5.has("key") || !var6.equals(var5.get("key").getAsString()) || !var2.getName().startsWith("(mobil)")) {
               var2.kickPlayer("Tarayıcı girişi doğrulanamadı.");
               return;
            }

            this.webVerified.add(var2.getUniqueId());
            this.getLogger().info("Tarayıcı oyuncusu doğrulandı: " + var2.getName());
         } catch (RuntimeException var7) {
            var2.kickPlayer("Tarayıcı girişi doğrulanamadı.");
         }
      });
   }

   static Block blockOf(Entity var0) {
      return var0.getLocation().getBlock();
   }

   static {
      for (Material var3 : Material.values()) {
         if (!var3.isLegacy() && var3.isItem() && !var3.isAir()) {
            ITEMS.add(var3);
         }
      }
   }

   static final class Menu implements InventoryHolder {
      final String kind;
      final int page;
      Inventory inv;

      Menu(String var1, int var2) {
         this.kind = var1;
         this.page = var2;
      }

      public Inventory getInventory() {
         return this.inv;
      }
   }

   static final class Party {
      UUID leader;
      final LinkedHashSet<UUID> members = new LinkedHashSet<>();
      Game.Mode mode = Game.Mode.COMP;
      String map = "col";
      boolean unlimited = false;
      boolean anyItem = false;
      int toWin = 9;
      Game game;

      Party(UUID var1) {
         this.leader = var1;
         this.members.add(var1);
      }
   }
}
