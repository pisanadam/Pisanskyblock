package com.pisan.core;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/** Kasa anahtarlari: yere koyulamaz, ismi degistirilemez, craftta kullanilamaz. Sag tik = kasayi ac. */
public final class KeyProtectListener implements Listener {
    private final PisanCore plugin;
    private final CrateMenu menu;

    public KeyProtectListener(PisanCore plugin, CrateMenu menu) { this.plugin = plugin; this.menu = menu; }

    private CrateManager cm() { return plugin.crates(); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlace(BlockPlaceEvent e) {
        if (cm().keyId(e.getItemInHand()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        ItemStack it = e.getItem();
        String id = cm().keyId(it);
        if (id == null) return;
        e.setCancelled(true);
        CrateManager.Crate c = cm().crate(id);
        if (c == null) return;
        Player p = e.getPlayer();
        Action a = e.getAction();
        if (a == Action.RIGHT_CLICK_AIR || a == Action.RIGHT_CLICK_BLOCK) cm().open(p, c);
        else if (a == Action.LEFT_CLICK_AIR || a == Action.LEFT_CLICK_BLOCK) menu.openPreview(p, c);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAnvil(PrepareAnvilEvent e) {
        for (ItemStack it : e.getInventory().getContents())
            if (cm().keyId(it) != null) { e.setResult(null); return; }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCraft(PrepareItemCraftEvent e) {
        for (ItemStack it : e.getInventory().getMatrix())
            if (cm().keyId(it) != null) { e.getInventory().setResult(null); return; }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSmith(PrepareSmithingEvent e) {
        for (ItemStack it : e.getInventory().getContents())
            if (cm().keyId(it) != null) { e.setResult(null); return; }
    }
}
