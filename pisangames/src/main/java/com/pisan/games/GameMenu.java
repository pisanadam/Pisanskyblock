package com.pisan.games;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class GameMenu implements InventoryHolder {
    private final Map<Integer, String> slots = new HashMap<>();
    private Inventory inv;

    public static void open(PisanGames plugin, Player p) {
        GameMenu m = new GameMenu();
        m.inv = Bukkit.createInventory(m, 27, Component.text("Pisan Games", NamedTextColor.DARK_PURPLE, TextDecoration.BOLD));
        ItemStack filler = item(Material.BLACK_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int i = 0; i < 27; i++) m.inv.setItem(i, filler);
        for (GameServer gs : plugin.servers()) {
            List<Component> lore = new ArrayList<>();
            for (Component c : gs.loreComponents()) lore.add(c.decoration(TextDecoration.ITALIC, false));
            lore.add(Component.empty());
            ExarotonClient.ServerInfo st = plugin.status(gs.key());
            if (st == null) lore.add(Component.text("Durum: ?", NamedTextColor.GRAY));
            else if (st.status() == ExarotonClient.ONLINE) {
                lore.add(Component.text("● Acik", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
                lore.add(Component.text("Oyuncu: " + st.players() + "/" + st.maxPlayers(), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            } else lore.add(Component.text("● Uyuyor (tiklayinca acilir)", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.empty());
            lore.add(Component.text("» Oynamak icin tikla!", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
            m.inv.setItem(gs.slot(), item(gs.icon(), gs.displayName().decoration(TextDecoration.ITALIC, false), lore));
            m.slots.put(gs.slot(), gs.key());
        }
        p.openInventory(m.inv);
    }

    public String keyAt(int slot) { return slots.get(slot); }

    @Override public Inventory getInventory() { return inv; }

    static ItemStack item(Material mat, Component name, List<Component> lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(name);
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.values());
        it.setItemMeta(meta);
        return it;
    }
}
