package dev.underworld;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.GameMode;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Falling out of the bottom of the Nether drops you into the Underworld; flying
 * above the top of the Underworld brings you back. Inventory is never touched.
 */
public final class TransitionListener implements Listener {

    private final UnderworldPlugin plugin;
    private final Set<UUID> busy = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> immuneUntil = new ConcurrentHashMap<>();

    private double fallY;
    private double returnY;
    private int arrivalY;
    private int slowFallSeconds;
    private int immunitySeconds;
    private int fireResSeconds;

    public TransitionListener(UnderworldPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        var cfg = plugin.getConfig();
        fallY = cfg.getDouble("fall.trigger-y", -8);
        returnY = cfg.getDouble("return.trigger-y", 312);
        arrivalY = cfg.getInt("fall.arrival-y", 280);
        slowFallSeconds = cfg.getInt("fall.slow-fall-seconds", 8);
        immunitySeconds = cfg.getInt("fall.fall-immunity-seconds", 40);
        fireResSeconds = cfg.getInt("return.fire-resistance-seconds", 15);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        double y = e.getTo().getY();
        if (y > fallY && y < returnY) return; // fast exit for 99.9% of moves

        Player p = e.getPlayer();
        if (p.getGameMode() == GameMode.SPECTATOR || busy.contains(p.getUniqueId())) return;

        String world = e.getTo().getWorld().getName();
        if (y <= fallY && world.equals(plugin.netherName())) {
            sendDown(p);
        } else if (y >= returnY && world.equals(plugin.underworldName())) {
            sendUp(p);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && e.getCause() == EntityDamageEvent.DamageCause.FALL) {
            Long until = immuneUntil.get(p.getUniqueId());
            if (until != null) {
                if (System.currentTimeMillis() < until) e.setCancelled(true);
                else immuneUntil.remove(p.getUniqueId());
            }
        }
    }

    // ------------------------------------------------------------------ down

    public void sendDown(Player p) {
        World under = Bukkit.getWorld(plugin.underworldName());
        if (under == null) {
            p.sendMessage(Component.text("The Underworld is not loaded.", NamedTextColor.RED));
            return;
        }
        UUID id = p.getUniqueId();
        if (!busy.add(id)) return;

        Location from = p.getLocation();
        int x = from.getBlockX();
        int z = from.getBlockZ();

        under.getChunkAtAsync(x >> 4, z >> 4).thenAccept(ignored ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!p.isOnline()) {
                        busy.remove(id);
                        return;
                    }
                    // Wrap the Nether position into the hub so you always land in the safe middle area
                    int hubX = Math.floorMod(x, 700) - 350;
                    int hubZ = Math.floorMod(z, 700) - 350;
                    int[] spot = findLanding(Terrain.get(under.getSeed()), hubX, hubZ);
                    Location dest = new Location(under, spot[0] + 0.5, arrivalY, spot[1] + 0.5, from.getYaw(), 0f);
                    p.teleportAsync(dest).thenAccept(ok ->
                            Bukkit.getScheduler().runTask(plugin, () -> finishDescent(p, ok)));
                })
        ).exceptionally(ex -> {
            busy.remove(id);
            return null;
        });
    }

    private void finishDescent(Player p, boolean ok) {
        UUID id = p.getUniqueId();
        if (ok && p.isOnline()) {
            p.setFallDistance(0f);
            immuneUntil.put(id, System.currentTimeMillis() + immunitySeconds * 1000L);
            if (slowFallSeconds > 0) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, slowFallSeconds * 20, 0, false, false, true));
            }
            p.showTitle(Title.title(
                    Component.text("The Underworld", NamedTextColor.DARK_RED),
                    Component.text("You fell beyond the void", NamedTextColor.GRAY)));
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> busy.remove(id), 40L);
    }

    /** Looks for a column that is not lava, spiralling out from the fall position. */
    private int[] findLanding(Terrain t, int x, int z) {
        if (t.isLand(x, z) && t.zone(x, z) == Terrain.Zone.BASE) return new int[] {x, z};
        for (int r = 12; r <= 120; r += 12) {
            for (int a = 0; a < 8; a++) {
                double ang = a * Math.PI / 4.0;
                int nx = x + (int) Math.round(Math.cos(ang) * r);
                int nz = z + (int) Math.round(Math.sin(ang) * r);
                if (t.isLand(nx, nz) && t.zone(nx, nz) == Terrain.Zone.BASE) return new int[] {nx, nz};
            }
        }
        return new int[] {0, 0};
    }

    // ------------------------------------------------------------------ up

    public void sendUp(Player p) {
        World nether = Bukkit.getWorld(plugin.netherName());
        if (nether == null) return;
        UUID id = p.getUniqueId();
        if (!busy.add(id)) return;

        int x = p.getLocation().getBlockX() / 8;
        int z = p.getLocation().getBlockZ() / 8;

        nether.getChunkAtAsync(x >> 4, z >> 4).thenAccept(ignored ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!p.isOnline()) {
                        busy.remove(id);
                        return;
                    }
                    Location dest = findNetherSpot(nether, x, z);
                    p.teleportAsync(dest).thenAccept(ok ->
                            Bukkit.getScheduler().runTask(plugin, () -> finishAscent(p, ok)));
                })
        ).exceptionally(ex -> {
            busy.remove(id);
            return null;
        });
    }

    private void finishAscent(Player p, boolean ok) {
        UUID id = p.getUniqueId();
        if (ok && p.isOnline()) {
            p.setFallDistance(0f);
            immuneUntil.put(id, System.currentTimeMillis() + 10_000L);
            if (fireResSeconds > 0) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, fireResSeconds * 20, 0, false, false, true));
            }
            p.showTitle(Title.title(
                    Component.text("The Nether", NamedTextColor.GOLD),
                    Component.text("You climbed back up", NamedTextColor.GRAY)));
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> busy.remove(id), 40L);
    }

    private Location findNetherSpot(World nether, int x, int z) {
        for (int y = 120; y > 5; y--) {
            Block feet = nether.getBlockAt(x, y, z);
            if (!feet.isPassable() || feet.isLiquid()) continue;
            Block head = nether.getBlockAt(x, y + 1, z);
            if (!head.isPassable() || head.isLiquid()) continue;
            Block below = nether.getBlockAt(x, y - 1, z);
            Material m = below.getType();
            if (m.isSolid() && m != Material.MAGMA_BLOCK && m != Material.BEDROCK) {
                return new Location(nether, x + 0.5, y, z + 0.5);
            }
        }
        return nether.getSpawnLocation();
    }
}
