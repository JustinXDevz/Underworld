package dev.underworld;

import com.destroystokyo.paper.entity.ai.MobGoals;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.ai.Navigator;
import net.citizensnpcs.api.npc.MemoryNPCDataStore;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.trait.trait.Equipment;
import net.citizensnpcs.trait.SkinTrait;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * The Legion: a rare hostile army (up to 600 soldiers) marching in tight regiments behind a commander.
 *
 * Soldiers are Citizens NPCs (army.soldier-type: CITIZENS) or, if the server cannot cope, plain armoured mobs.
 * What keeps 600 NPCs workable:
 * - While nobody is near, the army is only a position on the map that keeps marching.
 * - Regiments (10x10 soldiers, 1.15 blocks apart) only become real NPCs when a player is close to them,
 *   never more than army.max-visible at once, and only a few per step so there is no lag spike.
 * - The NPCs are not saved (anonymous registry), have no nameplate or collision, and walk in straight lines
 *   to their slot instead of running a path search each.
 */
public final class ArmyManager extends BukkitRunnable implements Listener, ArmyApi {

    private static final String KEY = "underworld-army";
    private static final int REG_SIDE = 10;
    private static final int REG_SIZE = REG_SIDE * REG_SIDE;
    private static final int REG_COLS = 3;
    private static final double S = 1.15;
    private static final double GAP = 3.0;

    private static final class Army {
        final int id;
        double x;
        double z;
        double heading;
        double tx;
        double tz;
        double lastX;
        double lastZ;
        int[] reg;
        boolean materialized;
        int commanderNpc = -1;
        long defeatedAt;
        final List<List<Soldier>> units = new ArrayList<>();

        Army(int id, int size) {
            this.id = id;
            int regiments = Math.max(1, (size + REG_SIZE - 1) / REG_SIZE);
            this.reg = new int[regiments];
            int left = size;
            for (int i = 0; i < regiments; i++) {
                reg[i] = Math.min(REG_SIZE, left);
                left -= reg[i];
                units.add(new ArrayList<>());
            }
        }

        int total() {
            int n = 0;
            for (int v : reg) n += v;
            return n;
        }
    }

    private static final class Soldier {
        final Army army;
        final int regiment;
        final int slot;
        int npcId = -1;
        UUID uuid;
        long nextAttack;
        UUID target;

        Soldier(Army army, int regiment, int slot) {
            this.army = army;
            this.regiment = regiment;
            this.slot = slot;
        }
    }

    private static final class Commander {
        final Army army;
        long nextAttack;
        UUID target;

        Commander(Army army) {
            this.army = army;
        }
    }

    private final UnderworldPlugin plugin;
    private final Map<Integer, Army> armies = new LinkedHashMap<>();
    private final Map<Integer, Soldier> npcSoldiers = new HashMap<>();
    private final Map<UUID, Soldier> mobSoldiers = new HashMap<>();
    private final Map<Integer, Commander> commanders = new HashMap<>();
    private final Random rnd = new Random();
    private NPCRegistry npcReg;
    private int nextId = 1;
    private long lastDefeat;
    private int tick;
    private boolean cleaned;

    public ArmyManager(UnderworldPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    private FileConfiguration cfg() {
        return plugin.getConfig();
    }

    /** Army NPCs live in their own registry: they are never saved and never clutter the normal NPC list. */
    private NPCRegistry reg() {
        if (npcReg == null) npcReg = CitizensAPI.createAnonymousNPCRegistry(new MemoryNPCDataStore());
        return npcReg;
    }

    private boolean citizensSoldiers() {
        return "CITIZENS".equalsIgnoreCase(cfg().getString("army.soldier-type", "CITIZENS"));
    }

    private int visible() {
        return npcSoldiers.size() + mobSoldiers.size();
    }

    // ------------------------------------------------------------------ persistence

    private File file() {
        return new File(plugin.getDataFolder(), "armies.yml");
    }

    private void load() {
        File f = file();
        if (!f.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        nextId = y.getInt("next-id", 1);
        lastDefeat = y.getLong("last-defeat", 0);
        ConfigurationSection s = y.getConfigurationSection("armies");
        if (s == null) return;
        for (String k : s.getKeys(false)) {
            ConfigurationSection c = s.getConfigurationSection(k);
            if (c == null) continue;
            try {
                List<Integer> regs = c.getIntegerList("reg");
                int total = 0;
                for (int v : regs) total += v;
                Army a = new Army(Integer.parseInt(k), Math.max(10, total));
                for (int i = 0; i < regs.size() && i < a.reg.length; i++) a.reg[i] = regs.get(i);
                a.x = c.getDouble("x");
                a.z = c.getDouble("z");
                a.heading = c.getDouble("heading");
                a.tx = a.x;
                a.tz = a.z;
                armies.put(a.id, a);
            } catch (NumberFormatException ignored) {
                // skip broken entry
            }
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("next-id", nextId);
        y.set("last-defeat", lastDefeat);
        for (Army a : armies.values()) {
            String p = "armies." + a.id + ".";
            y.set(p + "x", a.x);
            y.set(p + "z", a.z);
            y.set(p + "heading", a.heading);
            List<Integer> regs = new ArrayList<>();
            for (int v : a.reg) regs.add(v);
            y.set(p + "reg", regs);
        }
        try {
            y.save(file());
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save armies.yml: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ main loop (every 10 ticks)

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
            if (p.getGameMode() != GameMode.SPECTATOR && !p.hasMetadata("NPC")) players.add(p);
        }
        Terrain t = Terrain.get(w.getSeed());

        tick++;
        if (tick % 1200 == 0 && !players.isEmpty()) naturalSpawn(t, players);
        for (Army a : new ArrayList<>(armies.values())) step(w, t, a, players);
    }

    /** Older versions saved army NPCs in the normal registry. */
    private void cleanup() {
        List<NPC> old = new ArrayList<>();
        for (NPC n : CitizensAPI.getNPCRegistry()) {
            if (n.data().has(KEY)) old.add(n);
        }
        for (NPC n : old) n.destroy();
    }

    public void shutdown() {
        for (Army a : armies.values()) {
            removeAllSoldiers(a);
            destroyCommander(a);
            a.materialized = false;
        }
        commanders.clear();
        npcSoldiers.clear();
        mobSoldiers.clear();
        save();
    }

    private void destroyCommander(Army a) {
        if (a.commanderNpc < 0) return;
        NPC cmd = reg().getById(a.commanderNpc);
        if (cmd != null) cmd.destroy();
        commanders.remove(a.commanderNpc);
        a.commanderNpc = -1;
    }

    private void removeSoldier(Soldier s) {
        if (s.npcId >= 0) {
            NPC n = reg().getById(s.npcId);
            if (n != null) n.destroy();
            npcSoldiers.remove(s.npcId);
        } else if (s.uuid != null) {
            Entity ent = Bukkit.getEntity(s.uuid);
            if (ent != null) ent.remove();
            mobSoldiers.remove(s.uuid);
        }
    }

    private void removeAllSoldiers(Army a) {
        for (List<Soldier> list : a.units) {
            for (Soldier s : new ArrayList<>(list)) removeSoldier(s);
            list.clear();
        }
    }

    // ------------------------------------------------------------------ creating armies

    private void naturalSpawn(Terrain t, List<Player> players) {
        FileConfiguration c = cfg();
        if (!c.getBoolean("army.natural", true)) return;
        if (armies.size() >= c.getInt("army.max-active", 1)) return;
        long cooldown = c.getLong("army.respawn-minutes", 120) * 60_000L;
        if (System.currentTimeMillis() - lastDefeat < cooldown) return;
        if (rnd.nextInt(100) >= c.getInt("army.chance-per-check", 8)) return;

        Player p = players.get(rnd.nextInt(players.size()));
        for (int i = 0; i < 30; i++) {
            double ang = rnd.nextDouble() * Math.PI * 2;
            double d = 500 + rnd.nextDouble() * 400;
            int x = (int) (p.getLocation().getX() + Math.cos(ang) * d);
            int z = (int) (p.getLocation().getZ() + Math.sin(ang) * d);
            if (!t.walkable(x, z)) continue;
            createArmy(x, z, rnd.nextDouble() * Math.PI * 2, c.getInt("army.size", 560));
            return;
        }
    }

    private Army createArmy(double x, double z, double heading, int size) {
        Army a = new Army(nextId++, Math.max(10, Math.min(600, size)));
        a.x = x;
        a.z = z;
        a.heading = heading;
        a.tx = x;
        a.tz = z;
        armies.put(a.id, a);
        save();
        return a;
    }

    @Override
    public String spawn(Player at, int size) {
        if (armies.size() >= cfg().getInt("army.max-total", 3)) {
            return "There are already " + armies.size() + " armies. Use /uw army clear first.";
        }
        int n = size > 0 ? Math.min(600, size) : cfg().getInt("army.size", 560);
        double heading = Math.toRadians(at.getLocation().getYaw() + 90);
        double x = at.getLocation().getX() + Math.cos(heading) * 60;
        double z = at.getLocation().getZ() + Math.sin(heading) * 60;
        // it marches towards the player so they meet face to face
        createArmy(x, z, heading + Math.PI, n);
        return "A Legion army of " + n + " soldiers marches 60 blocks away.";
    }

    @Override
    public List<String> list() {
        List<String> out = new ArrayList<>();
        for (Army a : armies.values()) {
            out.add("#" + a.id + " at " + Math.round(a.x) + ", " + Math.round(a.z) + " - " + a.total()
                    + " soldiers" + (a.materialized ? " (visible)" : " (marching)"));
        }
        return out;
    }

    @Override
    public int clear() {
        int n = armies.size();
        shutdown();
        armies.clear();
        save();
        return n;
    }

    @Override
    public String nearest(Location from) {
        Army best = null;
        double bd = Double.MAX_VALUE;
        for (Army a : armies.values()) {
            double d = Math.hypot(a.x - from.getX(), a.z - from.getZ());
            if (d < bd) {
                bd = d;
                best = a;
            }
        }
        if (best == null) return null;
        return "Legion army (" + best.total() + " soldiers): " + Math.round(best.x) + ", " + Math.round(best.z)
                + "  (about " + Math.round(bd) + " blocks away)";
    }

    // ------------------------------------------------------------------ per-army step

    private void step(World w, Terrain t, Army a, List<Player> players) {
        long now = System.currentTimeMillis();
        if (a.defeatedAt > 0) {
            if (now - a.defeatedAt > 15_000) disband(a);
            return;
        }
        double near = Double.MAX_VALUE;
        for (Player p : players) {
            near = Math.min(near, Math.hypot(p.getLocation().getX() - a.x, p.getLocation().getZ() - a.z));
        }
        double matDist = cfg().getDouble("army.materialize-distance", 130);
        if (!a.materialized) {
            if (near <= matDist) materialize(w, t, a);
            else virtualMarch(t, a);
            return;
        }
        NPC cmd = reg().getById(a.commanderNpc);
        if (cmd == null || near > matDist + 70) {
            dematerialize(a);
            return;
        }
        drive(w, t, a, cmd, players, now);
    }

    private void virtualMarch(Terrain t, Army a) {
        double dx = a.tx - a.x;
        double dz = a.tz - a.z;
        double dist = Math.hypot(dx, dz);
        if (dist < 8) {
            pickTarget(t, a, 60, 100);
            return;
        }
        double step = cfg().getDouble("army.march-speed", 3.0) * 0.5;
        a.x += dx / dist * step;
        a.z += dz / dist * step;
        a.heading = Math.atan2(dz, dx);
    }

    private void pickTarget(Terrain t, Army a, double min, double max) {
        for (int i = 0; i < 8; i++) {
            double ang = a.heading + (rnd.nextDouble() - 0.5) * 1.6;
            double d = min + rnd.nextDouble() * (max - min);
            int x = (int) (a.x + Math.cos(ang) * d);
            int z = (int) (a.z + Math.sin(ang) * d);
            if (t.walkable(x, z)) {
                a.tx = x;
                a.tz = z;
                a.heading = ang;
                return;
            }
            a.heading += 0.7;
        }
        a.tx = a.x + Math.cos(a.heading) * min;
        a.tz = a.z + Math.sin(a.heading) * min;
    }

    private int[] findWalkable(World w, Terrain t, int cx, int cz, int radius) {
        for (int r = 0; r <= radius; r += 4) {
            int steps = r == 0 ? 1 : 8;
            for (int i = 0; i < steps; i++) {
                double ang = i * Math.PI / 4;
                int x = cx + (int) Math.round(Math.cos(ang) * r);
                int z = cz + (int) Math.round(Math.sin(ang) * r);
                if (!w.isChunkLoaded(x >> 4, z >> 4) || !t.walkable(x, z)) continue;
                int y = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                if (y < 5 || w.getBlockAt(x, y, z).getType() == Material.POWDER_SNOW) continue;
                return new int[] {x, y + 1, z};
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ materialize / dematerialize

    private void materialize(World w, Terrain t, Army a) {
        int[] spot = findWalkable(w, t, (int) a.x, (int) a.z, 48);
        if (spot == null) {
            a.x += Math.cos(a.heading) * 24;
            a.z += Math.sin(a.heading) * 24;
            return;
        }
        Location loc = new Location(w, spot[0] + 0.5, spot[1], spot[2] + 0.5);
        NPC cmd = spawnCommander(loc);
        if (cmd == null) return;
        a.commanderNpc = cmd.getId();
        commanders.put(cmd.getId(), new Commander(a));
        a.x = loc.getX();
        a.z = loc.getZ();
        a.lastX = a.x;
        a.lastZ = a.z;
        a.materialized = true;
        pickTarget(t, a, 35, 55);

        for (Player p : w.getPlayers()) {
            if (Math.hypot(p.getLocation().getX() - a.x, p.getLocation().getZ() - a.z) < 250) {
                p.playSound(p.getLocation(), Sound.EVENT_RAID_HORN, 4f, 1f);
                p.sendMessage(Txt.c("\u00a74* A war horn sounds. The Legion marches!"));
            }
        }
    }

    private void dematerialize(Army a) {
        NPC cmd = reg().getById(a.commanderNpc);
        if (cmd != null && cmd.isSpawned()) {
            a.x = cmd.getEntity().getLocation().getX();
            a.z = cmd.getEntity().getLocation().getZ();
        }
        removeAllSoldiers(a);
        destroyCommander(a);
        a.materialized = false;
        save();
    }

    private void disband(Army a) {
        removeAllSoldiers(a);
        destroyCommander(a);
        armies.remove(a.id);
        save();
    }

    // ------------------------------------------------------------------ creating units

    private ItemStack soldierArmor(Material m) {
        ItemStack item = new ItemStack(m);
        item.addUnsafeEnchantment(Enchantment.PROTECTION, 2);
        if (item.getItemMeta() instanceof ArmorMeta meta) {
            meta.setTrim(new ArmorTrim(TrimMaterial.REDSTONE, TrimPattern.SILENCE));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack soldierSword() {
        ItemStack sword = new ItemStack(Material.NETHERITE_SWORD);
        sword.addUnsafeEnchantment(Enchantment.SHARPNESS, 2);
        return sword;
    }

    private NPC spawnCommander(Location loc) {
        NPC npc = reg().createNPC(EntityType.PLAYER, "\u00a74\u00a7lLegion Commander");
        npc.setProtected(false);
        Equipment eq = npc.getOrAddTrait(Equipment.class);
        eq.set(Equipment.EquipmentSlot.HELMET, Knights.armor(Material.NETHERITE_HELMET));
        eq.set(Equipment.EquipmentSlot.CHESTPLATE, Knights.armor(Material.NETHERITE_CHESTPLATE));
        eq.set(Equipment.EquipmentSlot.LEGGINGS, Knights.armor(Material.NETHERITE_LEGGINGS));
        eq.set(Equipment.EquipmentSlot.BOOTS, Knights.armor(Material.NETHERITE_BOOTS));
        eq.set(Equipment.EquipmentSlot.HAND, Knights.sword());
        if (!npc.spawn(loc)) {
            npc.destroy();
            return null;
        }
        if (npc.getEntity() instanceof LivingEntity le) {
            AttributeInstance health = le.getAttribute(Attribute.MAX_HEALTH);
            if (health != null) {
                health.setBaseValue(300);
                le.setHealth(health.getBaseValue());
            }
            if (cfg().getBoolean("army.commander-glow", true)) le.setGlowing(true);
        }
        npc.getNavigator().getDefaultParameters().range(128f).speedModifier(0.85f);
        return npc;
    }

    /** A cheap Citizens soldier: no nameplate, no collision, no saving, straight-line walking. */
    private NPC spawnSoldierNpc(Location loc) {
        NPC npc = reg().createNPC(EntityType.PLAYER, "\u00a77Legion Soldier");
        npc.setProtected(false);
        npc.data().setPersistent("nameplate-visible", false);
        npc.data().setPersistent("collidable", false);
        Equipment eq = npc.getOrAddTrait(Equipment.class);
        eq.set(Equipment.EquipmentSlot.HELMET, soldierArmor(Material.NETHERITE_HELMET));
        eq.set(Equipment.EquipmentSlot.CHESTPLATE, soldierArmor(Material.NETHERITE_CHESTPLATE));
        eq.set(Equipment.EquipmentSlot.LEGGINGS, soldierArmor(Material.NETHERITE_LEGGINGS));
        eq.set(Equipment.EquipmentSlot.BOOTS, soldierArmor(Material.NETHERITE_BOOTS));
        eq.set(Equipment.EquipmentSlot.HAND, soldierSword());
        String skin = cfg().getString("army.soldier-skin", "");
        if (skin != null && !skin.isEmpty()) npc.getOrAddTrait(SkinTrait.class).setSkinName(skin);
        if (!npc.spawn(loc)) {
            npc.destroy();
            return null;
        }
        if (npc.getEntity() instanceof LivingEntity le) {
            AttributeInstance health = le.getAttribute(Attribute.MAX_HEALTH);
            if (health != null) {
                health.setBaseValue(36);
                le.setHealth(health.getBaseValue());
            }
        }
        npc.getNavigator().getDefaultParameters().range(48f).speedModifier(1.2f).straightLineTargetingDistance(48f);
        return npc;
    }

    private void equipMob(Mob mob) {
        mob.setPersistent(false);
        mob.setRemoveWhenFarAway(false);
        mob.setCanPickupItems(false);
        mob.setCollidable(false);
        plugin.knights().tag(mob, 4);
        MobGoals goals = Bukkit.getMobGoals();
        goals.removeAllGoals(mob);

        EntityEquipment eq = mob.getEquipment();
        eq.setHelmet(soldierArmor(Material.NETHERITE_HELMET));
        eq.setChestplate(soldierArmor(Material.NETHERITE_CHESTPLATE));
        eq.setLeggings(soldierArmor(Material.NETHERITE_LEGGINGS));
        eq.setBoots(soldierArmor(Material.NETHERITE_BOOTS));
        eq.setItemInMainHand(soldierSword());
        eq.setHelmetDropChance(0f);
        eq.setChestplateDropChance(0f);
        eq.setLeggingsDropChance(0f);
        eq.setBootsDropChance(0f);
        eq.setItemInMainHandDropChance(0f);

        AttributeInstance health = mob.getAttribute(Attribute.MAX_HEALTH);
        if (health != null) {
            health.setBaseValue(36);
            mob.setHealth(health.getBaseValue());
        }
    }

    private Soldier spawnSoldier(World w, Army a, int r, int slot, Location loc) {
        Soldier s = new Soldier(a, r, slot);
        if (citizensSoldiers()) {
            NPC npc = spawnSoldierNpc(loc);
            if (npc == null) return null;
            s.npcId = npc.getId();
            npcSoldiers.put(s.npcId, s);
        } else {
            boolean zombie = "ZOMBIE".equalsIgnoreCase(cfg().getString("army.soldier-type", "CITIZENS"));
            Class<? extends Mob> type = zombie ? Zombie.class : WitherSkeleton.class;
            Mob mob = w.spawn(loc, type, this::equipMob);
            s.uuid = mob.getUniqueId();
            mobSoldiers.put(s.uuid, s);
        }
        return s;
    }

    // ------------------------------------------------------------------ formation

    private Location slotLocation(World w, Terrain t, Army a, int r, int slot, Location c) {
        double fx = Math.cos(a.heading);
        double fz = Math.sin(a.heading);
        int ri = r / REG_COLS;
        int rj = r % REG_COLS;
        double regLen = REG_SIDE * S;
        double u = -(4.0 + ri * (regLen + GAP)) - (slot / REG_SIDE) * S;
        double v = (rj - (REG_COLS - 1) / 2.0) * (regLen + GAP) + ((slot % REG_SIDE) - (REG_SIDE - 1) / 2.0) * S;
        double x = c.getX() + fx * u - fz * v;
        double z = c.getZ() + fz * u + fx * v;
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        if (!w.isChunkLoaded(bx >> 4, bz >> 4) || !t.walkable(bx, bz)) {
            // narrow ground (canyons, cracks): squeeze into the commander's line
            x = c.getX() + fx * u;
            z = c.getZ() + fz * u;
            bx = (int) Math.floor(x);
            bz = (int) Math.floor(z);
        }
        double y = c.getY();
        if (w.isChunkLoaded(bx >> 4, bz >> 4)) {
            y = w.getHighestBlockYAt(bx, bz, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
        }
        return new Location(w, x, y, z);
    }

    // ------------------------------------------------------------------ marching and fighting

    private void drive(World w, Terrain t, Army a, NPC cmd, List<Player> players, long now) {
        if (!cmd.isSpawned()) return;
        Location cLoc = cmd.getEntity().getLocation();

        double mx = cLoc.getX() - a.lastX;
        double mz = cLoc.getZ() - a.lastZ;
        if (Math.hypot(mx, mz) > 1.0) {
            a.heading = Math.atan2(mz, mx);
            a.lastX = cLoc.getX();
            a.lastZ = cLoc.getZ();
        }
        a.x = cLoc.getX();
        a.z = cLoc.getZ();

        driveCommander(w, t, a, cmd, cLoc, players, now);
        manageRegiments(w, t, a, cLoc, players);
        driveSoldiers(w, t, a, cLoc, players, now);
    }

    private void driveCommander(World w, Terrain t, Army a, NPC cmd, Location loc, List<Player> players, long now) {
        Commander c = commanders.get(cmd.getId());
        if (c == null) return;
        Navigator nav = cmd.getNavigator();
        Entity ent = cmd.getEntity();

        Player target = null;
        double best = 30.0 * 30.0;
        for (Player p : players) {
            double d = p.getLocation().distanceSquared(loc);
            if (d < best) {
                best = d;
                target = p;
            }
        }
        if (target != null) {
            if (!target.getUniqueId().equals(c.target) || !nav.isNavigating()) {
                nav.setTarget(target, false);
                c.target = target.getUniqueId();
            }
            if (Math.sqrt(best) <= 3.3 && now >= c.nextAttack) {
                c.nextAttack = now + 900;
                target.damage(cfg().getDouble("army.commander-damage", 16.0), ent);
                target.setFireTicks(40);
                if (ent instanceof Player pl) pl.swingMainHand();
            }
            return;
        }
        if (c.target != null) {
            nav.cancelNavigation();
            c.target = null;
        }
        if (Math.hypot(a.tx - loc.getX(), a.tz - loc.getZ()) < 8) pickTarget(t, a, 35, 55);
        if (!nav.isNavigating()) {
            int tx = (int) a.tx;
            int tz = (int) a.tz;
            if (w.isChunkLoaded(tx >> 4, tz >> 4)) {
                int y = w.getHighestBlockYAt(tx, tz, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
                nav.setTarget(new Location(w, tx + 0.5, y, tz + 0.5));
            } else {
                pickTarget(t, a, 25, 40);
            }
        }
    }

    /** Spawns the regiments players are close to (a few soldiers per step), removes the ones left behind. */
    private void manageRegiments(World w, Terrain t, Army a, Location cLoc, List<Player> players) {
        int cap = cfg().getInt("army.max-visible", 200);
        double regDist = cfg().getDouble("army.regiment-distance", 70);
        int budget = cfg().getInt("army.spawn-per-step", citizensSoldiers() ? 8 : 25);

        for (int r = 0; r < a.reg.length && budget > 0; r++) {
            List<Soldier> list = a.units.get(r);
            Location center = slotLocation(w, t, a, r, 45, cLoc);
            double near = Double.MAX_VALUE;
            for (Player p : players) {
                near = Math.min(near, Math.hypot(p.getLocation().getX() - center.getX(), p.getLocation().getZ() - center.getZ()));
            }
            if (near > regDist + 50) {
                for (Soldier s : new ArrayList<>(list)) removeSoldier(s);
                list.clear();
                continue;
            }
            if (near > regDist && list.isEmpty()) continue;

            boolean[] used = new boolean[REG_SIZE];
            for (Soldier s : list) used[s.slot] = true;
            int slot = 0;
            while (list.size() < a.reg[r] && budget > 0 && visible() < cap) {
                while (slot < REG_SIZE && used[slot]) slot++;
                if (slot >= REG_SIZE) break;
                Location at = slotLocation(w, t, a, r, slot, cLoc);
                Soldier s = spawnSoldier(w, a, r, slot, at);
                used[slot] = true;
                budget--;
                if (s != null) list.add(s);
            }
        }
    }

    private void driveSoldiers(World w, Terrain t, Army a, Location cLoc, List<Player> players, long now) {
        double dmg = cfg().getDouble("army.soldier-damage", 6.0);
        int i = 0;
        for (int r = 0; r < a.reg.length; r++) {
            for (Soldier s : new ArrayList<>(a.units.get(r))) {
                i++;
                Entity ent = null;
                NPC npc = null;
                boolean lost = false;
                if (s.npcId >= 0) {
                    npc = reg().getById(s.npcId);
                    if (npc == null) lost = true;
                    else if (npc.isSpawned()) ent = npc.getEntity();
                } else {
                    ent = s.uuid == null ? null : Bukkit.getEntity(s.uuid);
                    if (ent == null || !ent.isValid()) lost = true;
                }
                if (lost) {
                    a.units.get(r).remove(s);
                    npcSoldiers.remove(s.npcId);
                    if (s.uuid != null) mobSoldiers.remove(s.uuid);
                    continue;
                }
                if (ent == null) continue;
                Location loc = ent.getLocation();

                Player target = null;
                double best = 24.0 * 24.0;
                for (Player p : players) {
                    double d = p.getLocation().distanceSquared(loc);
                    if (d < best) {
                        best = d;
                        target = p;
                    }
                }
                if (target != null) {
                    if (best <= 2.7 * 2.7) {
                        if (now >= s.nextAttack) {
                            s.nextAttack = now + 1000;
                            target.damage(dmg, ent);
                            if (ent instanceof LivingEntity le) le.swingMainHand();
                        }
                    } else if (npc != null) {
                        Navigator nav = npc.getNavigator();
                        if (!target.getUniqueId().equals(s.target) || !nav.isNavigating()) {
                            nav.setTarget(target, false);
                            s.target = target.getUniqueId();
                        }
                    } else if ((tick + i) % 2 == 0 && ent instanceof Mob mob) {
                        mob.getPathfinder().moveTo(target.getLocation(), 1.3);
                    }
                    continue;
                }
                if (npc != null && s.target != null) {
                    npc.getNavigator().cancelNavigation();
                    s.target = null;
                }
                if ((tick + i) % 2 != 0) continue;
                Location slot = slotLocation(w, t, a, r, s.slot, cLoc);
                double d = Math.hypot(slot.getX() - loc.getX(), slot.getZ() - loc.getZ());
                if (d > 24) {
                    if (npc != null) npc.teleport(slot, PlayerTeleportEvent.TeleportCause.PLUGIN);
                    else ent.teleport(slot);
                } else if (d > 1.0) {
                    if (npc != null) npc.getNavigator().setTarget(slot);
                    else if (ent instanceof Mob mob) mob.getPathfinder().moveTo(slot, 1.4);
                }
            }
        }
    }

    // ------------------------------------------------------------------ death

    private List<ItemStack> soldierDrops(Player killer) {
        List<ItemStack> out = new ArrayList<>();
        if (killer == null) return out;
        plugin.quests().onKill(killer, 4);
        if (rnd.nextDouble() < 0.06) {
            Material[] pieces = {Material.NETHERITE_HELMET, Material.NETHERITE_CHESTPLATE,
                    Material.NETHERITE_LEGGINGS, Material.NETHERITE_BOOTS};
            out.add(soldierArmor(pieces[rnd.nextInt(pieces.length)]));
        }
        if (rnd.nextDouble() < 0.12) out.addAll(LootListener.roll(1));
        return out;
    }

    private void forget(Soldier s) {
        s.army.units.get(s.regiment).remove(s);
        s.army.reg[s.regiment] = Math.max(0, s.army.reg[s.regiment] - 1);
    }

    /** Armoured-mob soldiers die the normal way. */
    @EventHandler
    public void onMobSoldierDeath(EntityDeathEvent e) {
        Soldier s = mobSoldiers.remove(e.getEntity().getUniqueId());
        if (s == null) return;
        forget(s);
        e.getDrops().clear();
        e.setDroppedExp(8);
        e.getDrops().addAll(soldierDrops(e.getEntity().getKiller()));
    }

    /** NPC soldiers and the commander: intercept the lethal hit and handle the death ourselves. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof LivingEntity le) || !e.getEntity().hasMetadata("NPC")) return;
        NPC npc = reg().getNPC(e.getEntity());
        if (npc == null || reg().getById(npc.getId()) != npc) return;
        Soldier s = npcSoldiers.get(npc.getId());
        Commander c = commanders.get(npc.getId());
        if (s == null && c == null) return;
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
        if (c != null) commanderDown(npc, c, killer);
        else soldierDown(npc, s, killer);
    }

    private void soldierDown(NPC npc, Soldier s, Player killer) {
        Location loc = npc.getEntity().getLocation();
        World w = loc.getWorld();
        if (w != null) {
            for (ItemStack item : soldierDrops(killer)) w.dropItemNaturally(loc, item);
            if (killer != null) w.spawn(loc, ExperienceOrb.class, orb -> orb.setExperience(8));
        }
        npcSoldiers.remove(npc.getId());
        forget(s);
        npc.destroy();
    }

    private void commanderDown(NPC npc, Commander c, Player killer) {
        Location loc = npc.getEntity().getLocation();
        World w = loc.getWorld();
        Army a = c.army;
        if (killer != null && w != null) {
            plugin.quests().onKill(killer, 5);
            dropCommanderLoot(w, loc);
            for (Player p : w.getPlayers()) {
                if (p.getLocation().distanceSquared(loc) < 150 * 150) {
                    p.sendMessage(Txt.c("\u00a74* The Legion Commander has fallen! \u00a77The army scatters."));
                    p.playSound(p.getLocation(), Sound.ENTITY_WITHER_DEATH, 1f, 0.8f);
                }
            }
        }
        commanders.remove(npc.getId());
        npc.destroy();
        a.commanderNpc = -1;
        a.defeatedAt = System.currentTimeMillis();
        lastDefeat = a.defeatedAt;
        save();
    }

    private void dropCommanderLoot(World w, Location loc) {
        ItemStack wings = new ItemStack(Material.ELYTRA);
        ItemMeta meta = wings.getItemMeta();
        meta.displayName(Txt.c("\u00a75Wings of the Legion Commander"));
        wings.setItemMeta(meta);
        wings.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
        wings.addUnsafeEnchantment(Enchantment.MENDING, 1);
        w.dropItemNaturally(loc, wings);

        w.dropItemNaturally(loc, new ItemStack(Material.NETHERITE_INGOT, 3 + rnd.nextInt(4)));
        w.dropItemNaturally(loc, new ItemStack(Material.ANCIENT_DEBRIS, 4 + rnd.nextInt(5)));
        w.dropItemNaturally(loc, new ItemStack(Material.ENCHANTED_GOLDEN_APPLE, 2 + rnd.nextInt(3)));
        w.dropItemNaturally(loc, new ItemStack(Material.TOTEM_OF_UNDYING, 1 + rnd.nextInt(2)));
        w.dropItemNaturally(loc, plugin.relics().create("royal_seal", 2));
        if (rnd.nextDouble() < 0.35) w.dropItemNaturally(loc, new ItemStack(Material.NETHER_STAR));
        if (rnd.nextDouble() < 0.25) w.dropItemNaturally(loc, new ItemStack(Material.BEACON));
        if (rnd.nextDouble() < 0.70) w.dropItemNaturally(loc, Knights.sword());
        if (rnd.nextDouble() < 0.15) w.dropItemNaturally(loc, plugin.relics().create("sigil", 1));
        Material[] pieces = {Material.NETHERITE_HELMET, Material.NETHERITE_CHESTPLATE,
                Material.NETHERITE_LEGGINGS, Material.NETHERITE_BOOTS};
        for (Material mat : pieces) {
            if (rnd.nextDouble() < 0.40) w.dropItemNaturally(loc, Knights.armor(mat));
        }
        for (ItemStack item : LootListener.roll(8)) w.dropItemNaturally(loc, item);
        w.spawn(loc, ExperienceOrb.class, orb -> orb.setExperience(2000));
    }
}
