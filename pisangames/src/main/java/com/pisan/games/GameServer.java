package com.pisan.games;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;

import java.util.List;

public record GameServer(String key, String id, String name, Material icon, int slot, List<String> lore) {
    private static final LegacyComponentSerializer L = LegacyComponentSerializer.legacyAmpersand();
    public Component displayName() { return L.deserialize(name); }
    public String plainName() { return name.replaceAll("&[0-9a-fk-orA-FK-OR]", ""); }
    public List<Component> loreComponents() { return lore.stream().map(s -> (Component) L.deserialize(s)).toList(); }
}
