package com.pisan.games;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;

import java.util.List;

/** Spawn etrafina havada duran lobi platformu ve oyun istasyonlari kurar. */
public final class LobbyBuilder {
    public static final String TAG = "pisangames";
    private static final int R = 14;
    private final PisanGames plugin;

    public LobbyBuilder(PisanGames plugin) { this.plugin = plugin; }

    public void build(Location spawn) {
        World w = spawn.getWorld();
        int cx = spawn.getBlockX(), cy = spawn.getBlockY() - 1, cz = spawn.getBlockZ();
        w.getChunkAt(spawn).load();
        for (Entity e : w.getNearbyEntities(spawn, 30, 30, 30)) if (e.getScoreboardTags().contains(TAG)) e.remove();

        // Temizle + zemin
        for (int dx = -R - 2; dx <= R + 2; dx++) for (int dz = -R - 2; dz <= R + 2; dz++) {
            for (int dy = 0; dy <= 12; dy++) set(w, cx + dx, cy + dy, cz + dz, Material.AIR);
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d > R + 0.5) { set(w, cx + dx, cy, cz + dz, Material.AIR); continue; }
            Material m;
            if (d > R - 0.7) m = Material.PURPLE_CONCRETE;
            else if (d > R - 1.7) m = Material.POLISHED_BLACKSTONE_BRICKS;
            else if (d < 2.5) m = (d < 1) ? Material.SEA_LANTERN : Material.AMETHYST_BLOCK;
            else if (((int) Math.floor(d)) % 4 == 0) m = Material.PURPUR_BLOCK;
            else m = Material.SMOOTH_QUARTZ;
            set(w, cx + dx, cy, cz + dz, m);
            set(w, cx + dx, cy - 1, cz + dz, Material.BLACKSTONE);
            if (d > R - 0.7) set(w, cx + dx, cy + 1, cz + dz, Material.PURPLE_STAINED_GLASS);
        }

        // Istasyonlar (kuzey tarafi, spawn kuzeye bakar)
        Material[] pads = {Material.RED_CONCRETE, Material.LIME_CONCRETE, Material.LIGHT_BLUE_CONCRETE};
        Material[] glass = {Material.RED_STAINED_GLASS, Material.LIME_STAINED_GLASS, Material.LIGHT_BLUE_STAINED_GLASS};
        List<GameServer> list = plugin.servers().stream().toList();
        int n = list.size();
        for (int i = 0; i < n; i++) {
            GameServer gs = list.get(i);
            int sx = cx + (i - (n - 1) / 2) * 7 + ((n % 2 == 0) ? 3 : 0);
            int sz = cz - 8;
            Material pad = pads[i % pads.length], gl = glass[i % glass.length];
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) set(w, sx + dx, cy, sz + dz, pad);
            set(w, sx, cy, sz, Material.GLOWSTONE);
            // arka kemer
            for (int dy = 1; dy <= 4; dy++) { set(w, sx - 2, cy + dy, sz - 2, gl); set(w, sx + 2, cy + dy, sz - 2, gl); }
            for (int dx = -2; dx <= 2; dx++) set(w, sx + dx, cy + 5, sz - 2, gl);
            for (int dx = -1; dx <= 1; dx++) for (int dy = 1; dy <= 4; dy++) set(w, sx + dx, cy + dy, sz - 2, Material.BLACK_STAINED_GLASS);
            Block plate = w.getBlockAt(sx, cy + 1, sz);
            plate.setType(Material.HEAVY_WEIGHTED_PRESSURE_PLATE, false);
            plugin.getConfig().set("plates." + gs.key(), w.getName() + "," + sx + "," + (cy + 1) + "," + sz);

            TextDisplay td = w.spawn(new Location(w, sx + 0.5, cy + 3.2, sz + 0.5), TextDisplay.class);
            td.text(gs.displayName().appendNewline().append(Component.text("Plakaya bas!", NamedTextColor.GRAY)));
            td.setBillboard(Display.Billboard.CENTER);
            td.addScoreboardTag(TAG);
            td.setPersistent(true);
        }

        // Baslik
        TextDisplay title = w.spawn(new Location(w, cx + 0.5, cy + 6.5, cz - 12 + 0.5), TextDisplay.class);
        title.text(Component.text("✦ PISAN GAMES ✦", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD).appendNewline()
                .append(Component.text("Bir oyun sec: plakaya bas ya da pusulayi kullan", NamedTextColor.WHITE)));
        title.setBillboard(Display.Billboard.CENTER);
        title.addScoreboardTag(TAG);
        title.setPersistent(true);
        title.setTransformation(new org.bukkit.util.Transformation(
                new org.joml.Vector3f(), new org.joml.AxisAngle4f(), new org.joml.Vector3f(2f, 2f, 2f), new org.joml.AxisAngle4f()));

        Location s = spawn.clone();
        s.setYaw(180f); s.setPitch(0f);
        plugin.getConfig().set("spawn.yaw", 180.0);
        plugin.saveConfig();
    }

    private static void set(World w, int x, int y, int z, Material m) {
        Block b = w.getBlockAt(x, y, z);
        if (b.getType() != m) b.setType(m, false);
    }
}
