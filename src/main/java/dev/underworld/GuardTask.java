package dev.underworld;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Keeps geared wither skeletons around fortresses, towers and mazes while players are nearby.
 * Protection II diamond armour and a Sharpness I diamond sword, like the real Underworld.
 */
public final class GuardTask extends BukkitRunnable {

    private final UnderworldPlugin plugin;
    private final Map<Long, Long> lastSpawn = new HashMap<>();

    public GuardTask(UnderworldPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        FileConfiguration cfg = plugin.getConfig();
        if (!cfg.getBoolean("guard-mobs", false)) return;
        World w = Bukkit.getWorld(plugin.underworldName());
        if (w == null || w.getPlayers().isEmpty()) return;

        long now = System.currentTimeMillis();
        long cooldown = cfg.getLong("guards.respawn-minutes", 10) * 60_000L;
        Terrain t = Terrain.get(w.getSeed());
        Set<Long> seen = new HashSet<>();

        for (Player p : w.getPlayers()) {
            int pcx = Math.floorDiv(p.getLocation().getBlockX(), Terrain.CELL);
            int pcz = Math.floorDiv(p.getLocation().getBlockZ(), Terrain.CELL);
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    int cx = pcx + dx;
                    int cz = pcz + dz;
                    long key = ((long) cx << 32) ^ (cz & 0xffffffffL);
                    if (!seen.add(key)) continue;
                    handleCell(w, t, cfg, key, cx, cz, now, cooldown);
                }
            }
        }
    }

    private void handleCell(World w, Terrain t, FileConfiguration cfg, long key, int cx, int cz, long now, long cooldown) {
        Terrain.Feature f = t.feature(cx, cz);
        int want;
        int rMin;
        int rMax;
        switch (f.type()) {
            case FORTRESS -> { want = cfg.getInt("guards.fortress", 6); rMin = 8; rMax = 11; }
            case TOWER -> { want = cfg.getInt("guards.tower", 3); rMin = 0; rMax = 2; }
            case MAZE -> { want = cfg.getInt("guards.maze", 3); rMin = 0; rMax = 2; }
            default -> { return; }
        }
        if (want <= 0) return;
        if (!w.isChunkLoaded(Math.floorDiv(f.x(), 16), Math.floorDiv(f.z(), 16))) return;

        Long last = lastSpawn.get(key);
        if (last != null && now - last < cooldown) return;

        Location center = new Location(w, f.x() + 0.5, f.baseY() + 1, f.z() + 0.5);
        int have = w.getNearbyEntities(center, 20, 25, 20, en -> en instanceof WitherSkeleton).size();
        if (have >= want) return;

        ThreadLocalRandom r = ThreadLocalRandom.current();
        int spawned = 0;
        for (int i = 0; i < (want - have) * 4 && spawned < want - have; i++) {
            double ang = r.nextDouble() * Math.PI * 2;
            double rad = rMin + r.nextDouble() * (rMax - rMin);
            Location loc = center.clone().add(Math.cos(ang) * rad, 0, Math.sin(ang) * rad);
            if (!loc.getBlock().isPassable() || !loc.clone().add(0, 1, 0).getBlock().isPassable()) continue;
            w.spawn(loc, WitherSkeleton.class, GuardTask::equip);
            spawned++;
        }
        if (spawned > 0) lastSpawn.put(key, now);
    }

    private static void equip(WitherSkeleton ws) {
        EntityEquipment eq = ws.getEquipment();
        eq.setHelmet(armor(Material.DIAMOND_HELMET));
        eq.setChestplate(armor(Material.DIAMOND_CHESTPLATE));
        eq.setLeggings(armor(Material.DIAMOND_LEGGINGS));
        eq.setBoots(armor(Material.DIAMOND_BOOTS));
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        sword.addEnchantment(Enchantment.SHARPNESS, 1);
        eq.setItemInMainHand(sword);
        eq.setHelmetDropChance(0.03f);
        eq.setChestplateDropChance(0.03f);
        eq.setLeggingsDropChance(0.03f);
        eq.setBootsDropChance(0.03f);
        eq.setItemInMainHandDropChance(0.03f);
        ws.setRemoveWhenFarAway(false);
    }

    private static ItemStack armor(Material m) {
        ItemStack item = new ItemStack(m);
        item.addEnchantment(Enchantment.PROTECTION, 2);
        return item;
    }
}
