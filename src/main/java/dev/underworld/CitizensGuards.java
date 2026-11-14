package dev.underworld;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.ai.Navigator;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.trait.trait.Equipment;
import net.citizensnpcs.trait.SkinTrait;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
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
 * Netherite knights as real Citizens NPCs: one at every outpost, plus wandering guards that patrol
 * around players. They chase and hit players, and drop gear + loot when killed.
 * kind 1 = outpost knight, kind 2 = wandering guard.
 */
public final class CitizensGuards extends BukkitRunnable implements Listener {

    private static final String KEY = "underworld-guard";

    private static final class State {
        final int kind;
        final Location home;
        long nextAttack;
        UUID target;
        double heading;

        State(int kind, Location home, double heading) {
            this.kind = kind;
            this.home = home;
            this.heading = heading;
        }
    }

    private final UnderworldPlugin plugin;
    private final Map<Integer, State> states = new HashMap<>();
    private final Map<Long, Integer> outpostNpc = new HashMap<>();
    private final Map<Long, Long> nextKnight = new HashMap<>();
    private final Random rnd = new Random();
    private int tick;
    private boolean cleaned;

    public CitizensGuards(UnderworldPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ main loop

    @Override
    public void run() {
        if (!cleaned) {
            cleanup();
            cleaned = true;
        }
        World w = Bukkit.getWorld(plugin.underworldName());
        if (w == null) return;

        NPCRegistry reg = CitizensAPI.getNPCRegistry();
        List<Player> players = new ArrayList<>();
        for (Player p : w.getPlayers()) {
            if (p.getGameMode() != GameMode.SPECTATOR && !reg.isNPC(p)) players.add(p);
        }
        Terrain t = Terrain.get(w.getSeed());
        FileConfiguration cfg = plugin.getConfig();

        tick++;
        if (tick % 4 == 0 && !players.isEmpty()) {
            if (cfg.getBoolean("outpost-knights", true)) manageKnights(w, t, players, cfg);
            if (cfg.getBoolean("patrol-guards", true)) managePatrol(w, t, players, cfg);
        }
        for (Integer id : new ArrayList<>(states.keySet())) tickNpc(w, t, id, players);
    }

    /** Removes guards left over from an earlier session (Citizens saves its NPCs). */
    private void cleanup() {
        List<NPC> old = new ArrayList<>();
        for (NPC n : CitizensAPI.getNPCRegistry()) {
            if (n.data().has(KEY)) old.add(n);
        }
        for (NPC n : old) n.destroy();
    }

    public void shutdown() {
        NPCRegistry reg = CitizensAPI.getNPCRegistry();
        for (Integer id : new ArrayList<>(states.keySet())) {
            NPC npc = reg.getById(id);
            if (npc != null) npc.destroy();
        }
        states.clear();
    }

    // ------------------------------------------------------------------ spawning

    private void manageKnights(World w, Terrain t, List<Player> players, FileConfiguration cfg) {
        long now = System.currentTimeMillis();
        long cooldown = cfg.getLong("knight-respawn-minutes", 30) * 60_000L;
        NPCRegistry reg = CitizensAPI.getNPCRegistry();
        Set<Long> seen = new HashSet<>();

        for (Player p : players) {
            int pcx = Math.floorDiv(p.getLocation().getBlockX(), Terrain.STRUCT_CELL);
            int pcz = Math.floorDiv(p.getLocation().getBlockZ(), Terrain.STRUCT_CELL);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int cx = pcx + dx;
                    int cz = pcz + dz;
                    long key = ((long) cx << 32) ^ (cz & 0xffffffffL);
                    if (!seen.add(key)) continue;

                    Terrain.Feature f = t.structure(cx, cz);
                    if (f.type() != Terrain.FeatureType.OUTPOST) continue;

                    Integer existing = outpostNpc.get(key);
                    if (existing != null) {
                        if (states.containsKey(existing) && reg.getById(existing) != null) continue;
                        outpostNpc.remove(key);
                    }
                    Long next = nextKnight.get(key);
                    if (next != null && now < next) continue;
                    if (!w.isChunkLoaded(Math.floorDiv(f.x(), 16), Math.floorDiv(f.z(), 16))) continue;

                    Location spot = new Location(w, f.x() + 3.5, f.baseY() + 1, f.z() + 3.5);
                    NPC npc = spawn(spot, 1);
                    if (npc != null) {
                        outpostNpc.put(key, npc.getId());
                        nextKnight.put(key, now + cooldown);
                    }
                }
            }
        }
    }

    private void managePatrol(World w, Terrain t, List<Player> players, FileConfiguration cfg) {
        int perPlayer = cfg.getInt("patrol.per-player", 2);
        int maxTotal = cfg.getInt("patrol.max-total", 24);
        NPCRegistry reg = CitizensAPI.getNPCRegistry();

        List<Location> guards = new ArrayList<>();
        for (Map.Entry<Integer, State> e : states.entrySet()) {
            if (e.getValue().kind != 2) continue;
            NPC npc = reg.getById(e.getKey());
            if (npc != null && npc.isSpawned()) guards.add(npc.getEntity().getLocation());
        }
        for (Player p : players) {
            if (guards.size() >= maxTotal) break;
            int near = 0;
            for (Location g : guards) {
                if (g.getWorld() == p.getWorld() && g.distanceSquared(p.getLocation()) < 120.0 * 120.0) near++;
            }
            if (near >= perPlayer) continue;
            Location spot = findSpawn(w, t, p.getLocation());
            if (spot == null) continue;
            NPC npc = spawn(spot, 2);
            if (npc != null) guards.add(spot);
        }
    }

    private Location findSpawn(World w, Terrain t, Location around) {
        for (int i = 0; i < 14; i++) {
            double ang = rnd.nextDouble() * Math.PI * 2;
            double dist = 50 + rnd.nextDouble() * 30;
            int x = around.getBlockX() + (int) (Math.cos(ang) * dist);
            int z = around.getBlockZ() + (int) (Math.sin(ang) * dist);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            if (!t.walkable(x, z)) continue;
            int y = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            if (y < 5 || w.getBlockAt(x, y, z).getType() == Material.POWDER_SNOW) continue;
            return new Location(w, x + 0.5, y + 1, z + 0.5);
        }
        return null;
    }

    private NPC spawn(Location loc, int kind) {
        NPC npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER,
                kind == 1 ? "\u00a7aOutpost Knight" : "\u00a7aUnderworld Guard");
        npc.setProtected(false);
        npc.data().setPersistent(KEY, kind);

        Equipment eq = npc.getOrAddTrait(Equipment.class);
        eq.set(Equipment.EquipmentSlot.HELMET, Knights.armor(Material.NETHERITE_HELMET));
        eq.set(Equipment.EquipmentSlot.CHESTPLATE, Knights.armor(Material.NETHERITE_CHESTPLATE));
        eq.set(Equipment.EquipmentSlot.LEGGINGS, Knights.armor(Material.NETHERITE_LEGGINGS));
        eq.set(Equipment.EquipmentSlot.BOOTS, Knights.armor(Material.NETHERITE_BOOTS));
        eq.set(Equipment.EquipmentSlot.HAND, Knights.sword());

        String skin = plugin.getConfig().getString("guard-skin", "");
        if (skin != null && !skin.isEmpty()) {
            npc.getOrAddTrait(SkinTrait.class).setSkinName(skin);
        }
        if (!npc.spawn(loc)) {
            npc.destroy();
            return null;
        }
        if (npc.getEntity() instanceof LivingEntity le) {
            AttributeInstance health = le.getAttribute(Attribute.MAX_HEALTH);
            if (health != null) {
                health.setBaseValue(kind == 1 ? 100 : 70);
                le.setHealth(health.getBaseValue());
            }
        }
        npc.getNavigator().getDefaultParameters().range(64f).speedModifier(kind == 1 ? 1.1f : 1.25f);
        states.put(npc.getId(), new State(kind, loc.clone(), rnd.nextDouble() * Math.PI * 2));
        return npc;
    }

    // ------------------------------------------------------------------ behaviour

    private void tickNpc(World w, Terrain t, int id, List<Player> players) {
        NPCRegistry reg = CitizensAPI.getNPCRegistry();
        State st = states.get(id);
        NPC npc = reg.getById(id);
        if (st == null) return;
        if (npc == null) {
            states.remove(id);
            return;
        }
        if (!npc.isSpawned()) {
            if (st.kind == 2) {
                npc.destroy();
                states.remove(id);
            }
            return;
        }
        Entity ent = npc.getEntity();
        Location loc = ent.getLocation();
        if (loc.getWorld() != w) return;

        Player nearest = null;
        double best = Double.MAX_VALUE;
        for (Player p : players) {
            double d = p.getLocation().distanceSquared(loc);
            if (d < best) {
                best = d;
                nearest = p;
            }
        }
        double dist = Math.sqrt(best);

        if (st.kind == 2 && (nearest == null || dist > 170)) {
            npc.destroy();
            states.remove(id);
            return;
        }

        Navigator nav = npc.getNavigator();
        double aggro = st.kind == 1 ? 20 : 30;
        if (nearest != null && dist <= aggro) {
            if (!nearest.getUniqueId().equals(st.target) || !nav.isNavigating()) {
                nav.setTarget(nearest, false);
                st.target = nearest.getUniqueId();
            }
            long now = System.currentTimeMillis();
            if (dist <= 3.3 && now >= st.nextAttack) {
                st.nextAttack = now + 900;
                nearest.damage(plugin.getConfig().getDouble("guard-damage", 12.0), ent);
                nearest.setFireTicks(60);
                if (ent instanceof Player pl) pl.swingMainHand();
            }
            return;
        }
        if (st.target != null) {
            nav.cancelNavigation();
            st.target = null;
        }
        if (nav.isNavigating() || rnd.nextInt(3) != 0) return;

        if (st.kind == 1) {
            Location dest = st.home.clone().add(rnd.nextInt(13) - 6, 0, rnd.nextInt(13) - 6);
            nav.setTarget(dest);
            return;
        }
        double ang = st.heading + (rnd.nextDouble() - 0.5) * 1.2;
        for (int i = 0; i < 6; i++) {
            double d = 24 + rnd.nextDouble() * 16;
            int x = loc.getBlockX() + (int) (Math.cos(ang) * d);
            int z = loc.getBlockZ() + (int) (Math.sin(ang) * d);
            if (w.isChunkLoaded(x >> 4, z >> 4) && t.walkable(x, z)) {
                int y = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
                nav.setTarget(new Location(w, x + 0.5, y, z + 0.5));
                st.heading = ang;
                return;
            }
            ang += 0.9;
        }
        st.heading = ang;
    }

    // ------------------------------------------------------------------ death: gear + loot

    @EventHandler(priority = EventPriority.HIGH)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof LivingEntity le) || !e.getEntity().hasMetadata("NPC")) return;
        NPC npc = CitizensAPI.getNPCRegistry().getNPC(e.getEntity());
        if (npc == null) return;
        State st = states.get(npc.getId());
        if (st == null) return;
        if (le.getHealth() - e.getFinalDamage() > 0) return;

        e.setCancelled(true);
        Player killer = null;
        if (e instanceof EntityDamageByEntityEvent byEntity) {
            Entity damager = byEntity.getDamager();
            if (damager instanceof Player p) {
                killer = p;
            } else if (damager instanceof Projectile pr && pr.getShooter() instanceof Player sp) {
                killer = sp;
            }
        }
        die(npc, st, killer);
    }

    private void die(NPC npc, State st, Player killer) {
        Location loc = npc.getEntity().getLocation();
        World w = loc.getWorld();
        if (killer != null && w != null) {
            plugin.quests().onKill(killer, st.kind);
            double armorChance = st.kind == 1 ? 0.30 : 0.12;
            double swordChance = st.kind == 1 ? 0.45 : 0.15;
            Material[] pieces = {Material.NETHERITE_HELMET, Material.NETHERITE_CHESTPLATE,
                    Material.NETHERITE_LEGGINGS, Material.NETHERITE_BOOTS};
            for (Material m : pieces) {
                if (rnd.nextDouble() < armorChance) w.dropItemNaturally(loc, Knights.armor(m));
            }
            if (rnd.nextDouble() < swordChance) w.dropItemNaturally(loc, Knights.sword());
            for (ItemStack item : LootListener.roll(st.kind == 1 ? 6 : 3)) w.dropItemNaturally(loc, item);
            w.dropItemNaturally(loc, new ItemStack(Material.NETHERITE_SCRAP, 1 + rnd.nextInt(3)));
            int xp = st.kind == 1 ? 500 : 250;
            w.spawn(loc, ExperienceOrb.class, orb -> orb.setExperience(xp));
        }
        states.remove(npc.getId());
        npc.destroy();
    }
}
