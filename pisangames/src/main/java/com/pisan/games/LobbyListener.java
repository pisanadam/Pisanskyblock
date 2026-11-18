package com.pisan.games;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.*;

public final class LobbyListener implements Listener {
    private final PisanGames plugin;
    private final Map<UUID, Long> plateCooldown = new HashMap<>();
    private final NamespacedKey compassKey;

    public LobbyListener(PisanGames plugin) {
        this.plugin = plugin;
        this.compassKey = new NamespacedKey(plugin, "menu");
    }

    private boolean builder(Player p) { return p.isOp() && p.getGameMode() == GameMode.CREATIVE; }

    private ItemStack compass() {
        ItemStack it = GameMenu.item(Material.COMPASS,
                Component.text("Oyun Sec", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false)
                        .append(Component.text(" (Sag Tik)", NamedTextColor.GRAY).decoration(TextDecoration.BOLD, false)),
                List.of(Component.text("PvP, Skyblock, SF League", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        it.editMeta(m -> m.getPersistentDataContainer().set(compassKey, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1));
        return it;
    }

    private boolean isCompass(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(compassKey);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        e.joinMessage(null);
        if (!p.isOp() || p.getGameMode() != GameMode.CREATIVE) p.setGameMode(GameMode.ADVENTURE);
        p.getInventory().clear();
        p.getInventory().setItem(4, compass());
        p.getInventory().setHeldItemSlot(4);
        p.setFoodLevel(20);
        p.teleport(plugin.spawn());
        p.showTitle(Title.title(Component.text("PISAN GAMES", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD),
                Component.text("Pusulayla bir oyun sec", NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(2), Duration.ofMillis(600))));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        e.quitMessage(null);
        plugin.connector().leave(e.getPlayer().getUniqueId());
        plateCooldown.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (e.getAction() == Action.PHYSICAL) {
            Block b = e.getClickedBlock();
            if (b == null || b.getType() != Material.HEAVY_WEIGHTED_PRESSURE_PLATE) return;
            String key = plateKey(b);
            if (key == null) return;
            long now = System.currentTimeMillis();
            Long last = plateCooldown.get(p.getUniqueId());
            if (last != null && now - last < 4000) return;
            plateCooldown.put(p.getUniqueId(), now);
            GameServer gs = plugin.server(key);
            if (gs != null) plugin.connector().connect(p, gs);
            return;
        }
        if (isCompass(e.getItem()) && (e.getAction().isRightClick() || e.getAction().isLeftClick())) {
            e.setCancelled(true);
            GameMenu.open(plugin, p);
            return;
        }
        if (!builder(p) && e.getAction() == Action.RIGHT_CLICK_BLOCK) e.setCancelled(true);
    }

    private String plateKey(Block b) {
        var sec = plugin.getConfig().getConfigurationSection("plates");
        if (sec == null) return null;
        String here = b.getWorld().getName() + "," + b.getX() + "," + b.getY() + "," + b.getZ();
        for (String k : sec.getKeys(false)) if (here.equals(sec.getString(k))) return k;
        return null;
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent e) {
        if (e.getInventory().getHolder() instanceof GameMenu menu) {
            e.setCancelled(true);
            if (e.getClickedInventory() != e.getView().getTopInventory()) return;
            String key = menu.keyAt(e.getRawSlot());
            if (key == null || !(e.getWhoClicked() instanceof Player p)) return;
            p.closeInventory();
            GameServer gs = plugin.server(key);
            if (gs != null) plugin.connector().connect(p, gs);
            return;
        }
        if (e.getWhoClicked() instanceof Player p && !builder(p)) e.setCancelled(true);
    }

    @EventHandler public void onDrag(InventoryDragEvent e) { if (e.getWhoClicked() instanceof Player p && !builder(p)) e.setCancelled(true); }
    @EventHandler public void onDrop(PlayerDropItemEvent e) { if (!builder(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void onSwap(PlayerSwapHandItemsEvent e) { if (!builder(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void onBreak(BlockBreakEvent e) { if (!builder(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void onPlace(BlockPlaceEvent e) { if (!builder(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void onFood(FoodLevelChangeEvent e) { e.setCancelled(true); e.getEntity().setFoodLevel(20); }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        e.setCancelled(true);
        if (e.getCause() == EntityDamageEvent.DamageCause.VOID) p.teleport(plugin.spawn());
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (e.getTo().getBlockY() == e.getFrom().getBlockY()) return;
        Location sp = plugin.spawn();
        if (e.getTo().getWorld() == sp.getWorld() && e.getTo().getY() < sp.getY() - 25) e.getPlayer().teleport(sp);
    }

    @EventHandler public void onWeather(org.bukkit.event.weather.WeatherChangeEvent e) { if (e.toWeatherState()) e.setCancelled(true); }
}
