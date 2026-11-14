package dev.underworld;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.trait.LookClose;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Friendly Citizens NPCs (quest givers and merchants) living in structures. */
public final class NpcManager extends BukkitRunnable implements Listener {

    private static final String KEY = "underworld-npc";

    private final UnderworldPlugin plugin;
    private final Map<Long, Integer> spawned = new HashMap<>();
    private boolean cleaned;

    public NpcManager(UnderworldPlugin plugin) {
        this.plugin = plugin;
    }

    /** Which survivor lives in which kind of structure. */
    static Role roleFor(Terrain.Feature f) {
        switch (f.type()) {
            case RUIN:
                switch (f.zone()) {
                    case CRYSTAL: return Role.SEEKER;
                    case CRACK: return Role.CARTOGRAPHER;
                    case SNOW: return Role.SEER;
                    case DARK: return Role.GRAVEKEEPER;
                    default: return Role.HERMIT;
                }
            case HOUSE:
                return f.zone() == Terrain.Zone.SNOW ? Role.HUNTER : Role.MERCHANT;
            case TOWER:
                return Role.MERCHANT;
            default:
                return null;
        }
    }

    @Override
    public void run() {
        if (!cleaned) {
            cleanup();
            cleaned = true;
        }
        World w = Bukkit.getWorld(plugin.underworldName());
        if (w == null) return;
        List<Player> players = new ArrayList<>();
        for (Player p : w.getPlayers()) {
            if (p.getGameMode() != GameMode.SPECTATOR) players.add(p);
        }
        if (players.isEmpty()) return;

        Terrain t = Terrain.get(w.getSeed());
        NPCRegistry reg = CitizensAPI.getNPCRegistry();
        for (Player p : players) {
            int px = p.getLocation().getBlockX();
            int pz = p.getLocation().getBlockZ();
            int pcx = Math.floorDiv(px, Terrain.STRUCT_CELL);
            int pcz = Math.floorDiv(pz, Terrain.STRUCT_CELL);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Terrain.Feature f = t.structure(pcx + dx, pcz + dz);
                    Role role = roleFor(f);
                    if (role != null) ensure(w, reg, f.x(), f.z(), f.baseY(), role, 0, 0);
                }
            }
            Terrain.Feature m = t.mega(px, pz);
            if (m.type() != Terrain.FeatureType.NONE
                    && Math.max(Math.abs(px - m.x()), Math.abs(pz - m.z())) < m.a() + 60) {
                if (m.type() == Terrain.FeatureType.KINGDOM) {
                    ensure(w, reg, m.x(), m.z(), m.baseY(), Role.HERALD, 0, 12);
                    ensure(w, reg, m.x(), m.z(), m.baseY(), Role.MERCHANT, 10, 12);
                } else {
                    ensure(w, reg, m.x(), m.z(), m.baseY(), Role.ARCHIVIST, 0, 42);
                    ensure(w, reg, m.x(), m.z(), m.baseY(), Role.MERCHANT, 8, 42);
                }
            }
        }
    }

    private void ensure(World w, NPCRegistry reg, int x, int z, int baseY, Role role, int ox, int oz) {
        long key = (((long) x << 32) ^ (z & 0xffffffffL)) * 16 + role.ordinal();
        Integer id = spawned.get(key);
        if (id != null && reg.getById(id) != null) return;
        if (!w.isChunkLoaded((x + ox) >> 4, (z + oz) >> 4)) return;

        Location spot = findStand(w, x + ox, z + oz, baseY);
        if (spot == null) return;
        NPC npc = reg.createNPC(EntityType.PLAYER, role.coloredName());
        npc.data().setPersistent(KEY, role.name());
        LookClose look = npc.getOrAddTrait(LookClose.class);
        look.lookClose(true);
        if (!npc.spawn(spot)) {
            npc.destroy();
            return;
        }
        spawned.put(key, npc.getId());
    }

    /** First free standing spot near the centre, scanning upwards from the floor so roofs do not matter. */
    private Location findStand(World w, int cx, int cz, int baseY) {
        for (int r = 0; r <= 7; r++) {
            int steps = r == 0 ? 1 : 8;
            for (int a = 0; a < steps; a++) {
                double ang = a * Math.PI / 4;
                int x = cx + (int) Math.round(Math.cos(ang) * r);
                int z = cz + (int) Math.round(Math.sin(ang) * r);
                if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = baseY + 1; y <= baseY + 8; y++) {
                    Block feet = w.getBlockAt(x, y, z);
                    if (feet.isPassable() && !feet.isLiquid() && w.getBlockAt(x, y + 1, z).isPassable()
                            && !w.getBlockAt(x, y - 1, z).isPassable()) {
                        return new Location(w, x + 0.5, y, z + 0.5);
                    }
                }
            }
        }
        return null;
    }

    private void cleanup() {
        List<NPC> old = new ArrayList<>();
        for (NPC n : CitizensAPI.getNPCRegistry()) {
            if (n.data().has(KEY)) old.add(n);
        }
        for (NPC n : old) n.destroy();
    }

    public void shutdown() {
        NPCRegistry reg = CitizensAPI.getNPCRegistry();
        for (Integer id : new ArrayList<>(spawned.values())) {
            NPC npc = reg.getById(id);
            if (npc != null) npc.destroy();
        }
        spawned.clear();
    }

    @EventHandler
    public void onClick(NPCRightClickEvent e) {
        Object v = e.getNPC().data().get(KEY);
        if (v == null) return;
        try {
            plugin.menus().openNpc(e.getClicker(), Role.valueOf(v.toString()));
        } catch (IllegalArgumentException ignored) {
            // unknown role
        }
    }
}
