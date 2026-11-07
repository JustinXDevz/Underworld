package dev.underworld;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * 1. Keeps one netherite knight at every outpost (respawns after a while).
 * 2. Keeps wandering netherite guards patrolling around every player in the Underworld.
 */
public final class GuardTask extends BukkitRunnable {

    private final UnderworldPlugin plugin;
    private final Map<Long, Long> nextKnight = new HashMap<>();
    private final Map<UUID, Double> heading = new HashMap<>();
    private final Random rnd = new Random();

    public GuardTask(UnderworldPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        FileConfiguration cfg = plugin.getConfig();
        World w = Bukkit.getWorld(plugin.underworldName());
        if (w == null) return;

        List<Player> players = new ArrayList<>();
        for (Player p : w.getPlayers()) {
            if (p.getGameMode() != GameMode.SPECTATOR) players.add(p);
        }
        if (players.isEmpty()) return;

        Terrain t = Terrain.get(w.getSeed());
        if (cfg.getBoolean("outpost-knights", true)) knights(w, t, players, cfg);
        if (cfg.getBoolean("patrol-guards", true)) patrol(w, t, players, cfg);
    }

    // ------------------------------------------------------------------ outposts

    private void knights(World w, Terrain t, List<Player> players, FileConfiguration cfg) {
        long now = System.currentTimeMillis();
        long cooldown = cfg.getLong("knight-respawn-minutes", 30) * 60_000L;
        Set<Long> seen = new HashSet<>();

        for (Player p : players) {
            int pcx = Math.floorDiv(p.getLocation().getBlockX(), Terrain.CELL);
            int pcz = Math.floorDiv(p.getLocation().getBlockZ(), Terrain.CELL);
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    int cx = pcx + dx;
                    int cz = pcz + dz;
                    long key = ((long) cx << 32) ^ (cz & 0xffffffffL);
                    if (!seen.add(key)) continue;

                    Terrain.Feature f = t.feature(cx, cz);
                    if (f.type() != Terrain.FeatureType.OUTPOST) continue;
                    if (!w.isChunkLoaded(Math.floorDiv(f.x(), 16), Math.floorDiv(f.z(), 16))) continue;

                    Long next = nextKnight.get(key);
                    if (next != null && now < next) continue;

                    Location spot = new Location(w, f.x() + 4.5, f.baseY() + 1, f.z() + 4.5);
                    boolean alive = false;
                    for (Entity e : w.getNearbyEntities(spot, 24, 24, 24)) {
                        if (plugin.knights().kind(e) == 1) {
                            alive = true;
                            break;
                        }
                    }
                    if (alive) continue;

                    plugin.knights().spawn(spot, true);
                    nextKnight.put(key, now + cooldown);
                }
            }
        }
    }

    // ------------------------------------------------------------------ patrols

    private void patrol(World w, Terrain t, List<Player> players, FileConfiguration cfg) {
        int perPlayer = cfg.getInt("patrol.per-player", 2);
        int maxTotal = cfg.getInt("patrol.max-total", 24);

        List<WitherSkeleton> guards = new ArrayList<>();
        for (WitherSkeleton ws : w.getEntitiesByClass(WitherSkeleton.class)) {
            if (plugin.knights().kind(ws) != 2) continue;
            if (nearestDistance(ws.getLocation(), players) > 170) {
                heading.remove(ws.getUniqueId());
                ws.remove();
                continue;
            }
            guards.add(ws);
        }

        for (Player p : players) {
            if (guards.size() >= maxTotal) break;
            int near = 0;
            for (WitherSkeleton g : guards) {
                if (g.getLocation().distanceSquared(p.getLocation()) < 120.0 * 120.0) near++;
            }
            if (near >= perPlayer) continue;
            Location spot = findSpawn(w, t, p.getLocation());
            if (spot != null) guards.add(plugin.knights().spawn(spot, false));
        }

        if (heading.size() > 200) heading.clear();
        for (WitherSkeleton g : guards) steer(w, t, g, players);
    }

    private double nearestDistance(Location loc, List<Player> players) {
        double best = Double.MAX_VALUE;
        for (Player p : players) {
            best = Math.min(best, p.getLocation().distanceSquared(loc));
        }
        return Math.sqrt(best);
    }

    private Location findSpawn(World w, Terrain t, Location around) {
        for (int i = 0; i < 14; i++) {
            double ang = rnd.nextDouble() * Math.PI * 2;
            double dist = 50 + rnd.nextDouble() * 30;
            int x = around.getBlockX() + (int) (Math.cos(ang) * dist);
            int z = around.getBlockZ() + (int) (Math.sin(ang) * dist);
            if (Math.abs(x) > Terrain.WORLD_HALF - 20 || Math.abs(z) > Terrain.WORLD_HALF - 20) continue;
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            if (!t.walkable(x, z)) continue;
            int y = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            if (y < 5 || w.getBlockAt(x, y, z).getType() == Material.POWDER_SNOW) continue;
            return new Location(w, x + 0.5, y + 1, z + 0.5);
        }
        return null;
    }

    /** Hunts the nearest player, otherwise keeps walking in a slowly turning direction. */
    private void steer(World w, Terrain t, WitherSkeleton g, List<Player> players) {
        Player target = null;
        double best = 48.0 * 48.0;
        for (Player p : players) {
            double d = p.getLocation().distanceSquared(g.getLocation());
            if (d < best) {
                best = d;
                target = p;
            }
        }
        if (target != null) {
            g.setTarget(target);
            return;
        }
        if (g.getPathfinder().getCurrentPath() != null) return;

        double ang = heading.getOrDefault(g.getUniqueId(), rnd.nextDouble() * Math.PI * 2)
                + (rnd.nextDouble() - 0.5) * 1.2;
        Location here = g.getLocation();
        for (int i = 0; i < 6; i++) {
            double dist = 24 + rnd.nextDouble() * 16;
            int x = here.getBlockX() + (int) (Math.cos(ang) * dist);
            int z = here.getBlockZ() + (int) (Math.sin(ang) * dist);
            if (Math.abs(x) < Terrain.WORLD_HALF - 10 && Math.abs(z) < Terrain.WORLD_HALF - 10
                    && w.isChunkLoaded(x >> 4, z >> 4) && t.walkable(x, z)) {
                int y = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
                g.getPathfinder().moveTo(new Location(w, x + 0.5, y, z + 0.5), 1.0);
                heading.put(g.getUniqueId(), ang);
                return;
            }
            ang += 0.9;
        }
        heading.put(g.getUniqueId(), ang);
    }
}
