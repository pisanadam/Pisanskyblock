package com.pisan.core;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Locale;

/**
 * Spawnerlar: konan spawner esyadaki turu korur, Silk Touch kazmayla kirilinca turuyle duser,
 * spawn yumurtasiyla tur degistirilemez (ucuz spawner alip pahaliya cevirme engeli).
 */
public final class SpawnerListener implements Listener {
    private final PisanCore plugin;

    public SpawnerListener(PisanCore plugin) { this.plugin = plugin; }

    private String pre() { return PisanCore.color(plugin.getConfig().getString("messages.prefix", "")); }

    private static EntityType typeOf(ItemStack it) {
        if (it == null || it.getType() != Material.SPAWNER || !(it.getItemMeta() instanceof BlockStateMeta bsm)) return null;
        if (!bsm.hasBlockState() || !(bsm.getBlockState() instanceof CreatureSpawner cs)) return null;
        return cs.getSpawnedType();
    }

    public static ItemStack item(EntityType type) {
        ItemStack it = new ItemStack(Material.SPAWNER);
        BlockStateMeta bsm = (BlockStateMeta) it.getItemMeta();
        CreatureSpawner cs = (CreatureSpawner) bsm.getBlockState();
        cs.setSpawnedType(type);
        bsm.setBlockState(cs);
        bsm.setDisplayName(PisanCore.color("&e" + CrateManager.pretty(type.name()) + " Spawner"));
        bsm.setLore(List.of(PisanCore.color("&7Tür: &f" + CrateManager.pretty(type.name()))));
        it.setItemMeta(bsm);
        return it;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (e.getBlockPlaced().getType() != Material.SPAWNER) return;
        EntityType t = typeOf(e.getItemInHand());
        if (t == null) return;
        if (e.getBlockPlaced().getState() instanceof CreatureSpawner cs && cs.getSpawnedType() != t) {
            cs.setSpawnedType(t);
            cs.update(true, false);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (b.getType() != Material.SPAWNER) return;
        Player p = e.getPlayer();
        if (p.getGameMode() != GameMode.SURVIVAL) return;
        ItemStack tool = p.getInventory().getItemInMainHand();
        if (!tool.getType().name().endsWith("_PICKAXE") || !tool.containsEnchantment(Enchantment.SILK_TOUCH)) {
            p.sendMessage(pre() + "§eSpawner'ı almak için §fSilk Touch §ekazma kullanmalısın!");
            e.setCancelled(true);
            return;
        }
        if (!(b.getState() instanceof CreatureSpawner cs) || cs.getSpawnedType() == null) return;
        e.setExpToDrop(0);
        e.setDropItems(false);
        b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 0.5, 0.5), item(cs.getSpawnedType()));
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onEgg(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null || e.getClickedBlock().getType() != Material.SPAWNER) return;
        ItemStack it = e.getItem();
        if (it == null || !it.getType().name().toUpperCase(Locale.ROOT).endsWith("_SPAWN_EGG")) return;
        if (e.getPlayer().hasPermission("pisancore.admin") && e.getPlayer().getGameMode() == GameMode.CREATIVE) return;
        e.setCancelled(true);
        e.getPlayer().sendMessage(pre() + "§cSpawner türü yumurtayla değiştirilemez.");
    }
}
