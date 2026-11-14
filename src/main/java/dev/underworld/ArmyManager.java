package dev.underworld;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.ai.Navigator;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.trait.trait.Equipment;
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
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
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
 * The Legion: a rare, hostile army of 50-60 Citizens soldiers marching in formation behind a commander.
 *
 * An army is "virtual" while nobody is near (it keeps marching as a position on the map) and only turns into
 * real NPCs when a player gets close, so it costs almost nothing when unobserved.
 */
public final class ArmyManager extends BukkitRunnable implements Listener, ArmyApi {

    private static final String KEY = "underworld-army";
    private static final int COLS = 7;
    private static final double SPACING = 2.4;

    private static final class Army {
        final int id;
        double x;
        double z;
        double heading;
        double tx;
        double tz;
        double lastX;
        double lastZ;
        int alive;
        boolean materialized;
        int commanderNpc = -1;
        int toSpawn;
        int nextSlot;
        long defeatedAt;
        final List<Integer> npcIds = new ArrayList<>();

        Army(int id) {
            this.id = id;
        }
    }

    private static final class Member {
        final Army army;
        final int slot;
        final boolean commander;
        long nextAttack;
        UUID target;

        Member(Army army, int slot, boolean commander) {
            this.army = army;
            this.slot = slot;
            this.commander = commander;
        }
    }

    private final UnderworldPlugin plugin;
    private final Map<Integer, Army> armies = new LinkedHashMap<>();
    private final Map<Integer, Member> members = new HashMap<>();
    private final Random rnd = new Random();
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
                Army a = new Army(Integer.parseInt(k));
                a.x = c.getDouble("x");
                a.z = c.getDouble("z");
                a.heading = c.getDouble("heading");
                a.alive = c.getInt("alive", 56);
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
            y.set(p + "alive", a.alive);
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
        NPCRegistry reg = CitizensAPI.getNPCRegistry();
        List<Player> players = new ArrayList<>();
        for (Player p : w.getPlayers()) {
            if (p.getGameMode() != GameMode.SPECTATOR && !reg.isNPC(p)) players.add(p);
        }
        Terrain t = Terrain.get(w.getSeed());

        tick++;
        if (tick % 1200 == 0 && !players.isEmpty()) naturalSpawn(w, t, players);

        for (Army a : new ArrayList<>(armies.values())) step(w, t, a, players);
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
        for (Army a : armies.values()) {
            if (a.materialized) syncAlive(a);
            for (Integer id : new ArrayList<>(a.npcIds)) {
                NPC npc = reg.getById(id);
                if (npc != null) npc.destroy();
            }
            a.npcIds.clear();
            a.materialized = false;
        }
        members.clear();
        save();
    }

    // ------------------------------------------------------------------ spawning armies

    private void naturalSpawn(World w, Terrain t, List<Player> players) {
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
            createArmy(x, z, rnd.nextDouble() * Math.PI * 2, c.getInt("army.size", 56));
            return;
        }
    }

    private Army createArmy(double x, double z, double heading, int size) {
        Army a = new Army(nextId++);
        a.x = x;
        a.z = z;
        a.heading = heading;
        a.tx = x;
        a.tz = z;
        a.alive = Math.max(10, Math.min(80, size));
        armies.put(a.id, a);
        save();
        return a;
    }

    @Override
    public String spawn(Player at, int size) {
        if (armies.size() >= cfg().getInt("army.max-total", 3)) {
            return "There are already " + armies.size() + " armies. Use /uw army clear first.";
        }
        double yaw = Math.toRadians(at.getLocation().getYaw() + 90);
        double heading = yaw;
        double x = at.getLocation().getX() + Math.cos(heading) * 40;
        double z = at.getLocation().getZ() + Math.sin(heading) * 40;
        // march towards the player so they meet face to face
        createArmy(x, z, heading + Math.PI, size > 0 ? size : cfg().getInt("army.size", 56));
        return "A Legion army (" + (size > 0 ? size : cfg().getInt("army.size", 56)) + " soldiers) marches 40 blocks away.";
    }

    @Override
    public List<String> list() {
        List<String> out = new ArrayList<>();
        for (Army a : armies.values()) {
            out.add("#" + a.id + " at " + Math.round(a.x) + ", " + Math.round(a.z) + " - " + a.alive
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
        return "Legion army: " + Math.round(best.x) + ", " + Math.round(best.z) + "  (about " + Math.round(bd) + " blocks away)";
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
        double matDist = cfg().getDouble("army.materialize-distance", 90);
        if (!a.materialized) {
            if (near <= matDist) materialize(w, t, a);
            else virtualMarch(t, a);
            return;
        }
        NPC cmd = CitizensAPI.getNPCRegistry().getById(a.commanderNpc);
        if (cmd == null) {
            dematerialize(a);
            return;
        }
        if (near > matDist + 60) {
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
        NPC cmd = spawnNpc(loc, true);
        if (cmd == null) return;
        a.commanderNpc = cmd.getId();
        a.npcIds.add(cmd.getId());
        members.put(cmd.getId(), new Member(a, -1, true));
        a.x = loc.getX();
        a.z = loc.getZ();
        a.lastX = a.x;
        a.lastZ = a.z;
        a.materialized = true;
        a.toSpawn = a.alive;
        a.nextSlot = 0;
        pickTarget(t, a, 35, 55);

        for (Player p : w.getPlayers()) {
            if (Math.hypot(p.getLocation().getX() - a.x, p.getLocation().getZ() - a.z) < 200) {
                p.playSound(p.getLocation(), Sound.EVENT_RAID_HORN, 4f, 1f);
                p.sendMessage(Txt.c("\u00a74* A war horn sounds. The Legion marches!"));
            }
        }
    }

    /** Counts the living soldiers so the army keeps its strength while unseen. */
    private void syncAlive(Army a) {
        int soldiers = 0;
        NPCRegistry reg = CitizensAPI.getNPCRegistry();
        for (Integer id : a.npcIds) {
            Member m = members.get(id);
            if (m != null && !m.commander && reg.getById(id) != null) soldiers++;
        }
        a.alive = soldiers + a.toSpawn;
    }

    private void dematerialize(Army a) {
        syncAlive(a);
        NPCRegistry reg = CitizensAPI.getNPCRegistry();
        NPC cmd = reg.getById(a.commanderNpc);
        if (cmd != null && cmd.isSpawned()) {
            a.x = cmd.getEntity().getLocation().getX();
            a.z = cmd.getEntity().getLocation().getZ();
        }
        for (Integer id : new ArrayList<>(a.npcIds)) {
            NPC npc = reg.getById(id);
            if (npc != null) npc.destroy();
            members.remove(id);
        }
        a.npcIds.clear();
        a.materialized = false;
        a.commanderNpc = -1;
        a.toSpawn = 0;
        save();
    }

    private void disband(Army a) {
        NPCRegistry reg = CitizensAPI.getNPCRegistry();
        for (Integer id : new ArrayList<>(a.npcIds)) {
            NPC npc = reg.getById(id);
            if (npc != null) npc.destroy();
            members.remove(id);
        }
        a.npcIds.clear();
        armies.remove(a.id);
        save();
    }

    // ------------------------------------------------------------------ NPC creation

    private ItemStack soldierArmor(Material m) {
        ItemStack item = new ItemStack(m);
        item.addUnsafeEnchantment(Enchantment.PROTECTION, 2);
        if (item.getItemMeta() instanceof ArmorMeta meta) {
            meta.setTrim(new ArmorTrim(TrimMaterial.REDSTONE, TrimPattern.SILENCE));
            item.setItemMeta(meta);
        }
        return item;
    }

    private NPC spawnNpc(Location loc, boolean commander) {
        NPC npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER,
                commander ? "\u00a74\u00a7lLegion Commander" : "\u00a77Legion Soldier");
        npc.setProtected(false);
        npc.data().setPersistent(KEY, commander ? 2 : 1);

        Equipment eq = npc.getOrAddTrait(Equipment.class);
        if (commander) {
            eq.set(Equipment.EquipmentSlot.HELMET, Knights.armor(Material.NETHERITE_HELMET));
            eq.set(Equipment.EquipmentSlot.CHESTPLATE, Knights.armor(Material.NETHERITE_CHESTPLATE));
            eq.set(Equipment.EquipmentSlot.LEGGINGS, Knights.armor(Material.NETHERITE_LEGGINGS));
            eq.set(Equipment.EquipmentSlot.BOOTS, Knights.armor(Material.NETHERITE_BOOTS));
            eq.set(Equipment.EquipmentSlot.HAND, Knights.sword());
        } else {
            eq.set(Equipment.EquipmentSlot.HELMET, soldierArmor(Material.NETHERITE_HELMET));
            eq.set(Equipment.EquipmentSlot.CHESTPLATE, soldierArmor(Material.NETHERITE_CHESTPLATE));
            eq.set(Equipment.EquipmentSlot.LEGGINGS, soldierArmor(Material.NETHERITE_LEGGINGS));
            eq.set(Equipment.EquipmentSlot.BOOTS, soldierArmor(Material.NETHERITE_BOOTS));
            ItemStack sword = new ItemStack(Material.NETHERITE_SWORD);
            sword.addUnsafeEnchantment(Enchantment.SHARPNESS, 2);
            eq.set(Equipment.EquipmentSlot.HAND, sword);
        }
        if (!npc.spawn(loc)) {
            npc.destroy();
            return null;
        }
        if (npc.getEntity() instanceof LivingEntity le) {
            AttributeInstance health = le.getAttribute(Attribute.MAX_HEALTH);
            if (health != null) {
                health.setBaseValue(commander ? 250 : 40);
                le.setHealth(health.getBaseValue());
            }
            if (commander && cfg().getBoolean("army.commander-glow", true)) le.setGlowing(true);
        }
        npc.getNavigator().getDefaultParameters().range(128f).speedModifier(commander ? 0.95f : 1.15f);
        return npc;
    }

    // ------------------------------------------------------------------ marching and fighting

    private Location slotLocation(World w, Terrain t, Army a, int slot, Location c) {
        double fx = Math.cos(a.heading);
        double fz = Math.sin(a.heading);
        int row = slot / COLS;
        int col = slot % COLS;
        double back = SPACING * (row + 2.5);
        double side = SPACING * (col - (COLS - 1) / 2.0);
        double x = c.getX() - fx * back - fz * side;
        double z = c.getZ() - fz * back + fx * side;
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        if (!w.isChunkLoaded(bx >> 4, bz >> 4) || !t.walkable(bx, bz)) {
            // narrow terrain (canyons, cracks): fall back to a single file behind the commander
            x = c.getX() - fx * back;
            z = c.getZ() - fz * back;
            bx = (int) Math.floor(x);
            bz = (int) Math.floor(z);
        }
        double y = c.getY();
        if (w.isChunkLoaded(bx >> 4, bz >> 4)) {
            y = w.getHighestBlockYAt(bx, bz, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
        }
        return new Location(w, x, y, z);
    }

    private void drive(World w, Terrain t, Army a, NPC cmd, List<Player> players, long now) {
        NPCRegistry reg = CitizensAPI.getNPCRegistry();
        if (!cmd.isSpawned()) return;
        Location cLoc = cmd.getEntity().getLocation();

        // keep the formation oriented the way the commander is walking
        double mx = cLoc.getX() - a.lastX;
        double mz = cLoc.getZ() - a.lastZ;
        if (Math.hypot(mx, mz) > 1.0) {
            a.heading = Math.atan2(mz, mx);
            a.lastX = cLoc.getX();
            a.lastZ = cLoc.getZ();
        }
        a.x = cLoc.getX();
        a.z = cLoc.getZ();

        // bring in the soldiers a few at a time
        int spawnedNow = 0;
        while (a.toSpawn > 0 && spawnedNow < 6) {
            Location slot = slotLocation(w, t, a, a.nextSlot, cLoc);
            NPC s = spawnNpc(slot, false);
            a.nextSlot++;
            a.toSpawn--;
            if (s == null) continue;
            a.npcIds.add(s.getId());
            members.put(s.getId(), new Member(a, a.nextSlot - 1, false));
            spawnedNow++;
        }

        double dmgSoldier = cfg().getDouble("army.soldier-damage", 8.0);
        double dmgCommander = cfg().getDouble("army.commander-damage", 16.0);

        for (Integer id : new ArrayList<>(a.npcIds)) {
            Member m = members.get(id);
            NPC npc = reg.getById(id);
            if (m == null || npc == null) {
                a.npcIds.remove(id);
                continue;
            }
            if (!npc.isSpawned()) continue;
            Entity ent = npc.getEntity();
            Location loc = ent.getLocation();
            Navigator nav = npc.getNavigator();

            Player target = null;
            double best = (m.commander ? 30.0 : 26.0) * (m.commander ? 30.0 : 26.0);
            for (Player p : players) {
                double d = p.getLocation().distanceSquared(loc);
                if (d < best) {
                    best = d;
                    target = p;
                }
            }
            if (target != null) {
                if (!target.getUniqueId().equals(m.target) || !nav.isNavigating()) {
                    nav.setTarget(target, false);
                    m.target = target.getUniqueId();
                }
                if (Math.sqrt(best) <= 3.3 && now >= m.nextAttack) {
                    m.nextAttack = now + 900;
                    target.damage(m.commander ? dmgCommander : dmgSoldier, ent);
                    target.setFireTicks(40);
                    if (ent instanceof Player pl) pl.swingMainHand();
                }
                continue;
            }
            if (m.target != null) {
                nav.cancelNavigation();
                m.target = null;
            }

            if (m.commander) {
                double dx = a.tx - loc.getX();
                double dz = a.tz - loc.getZ();
                if (Math.hypot(dx, dz) < 8) pickTarget(t, a, 35, 55);
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
            } else if ((tick + m.slot) % 3 == 0) {
                Location slot = slotLocation(w, t, a, m.slot, cLoc);
                double d = Math.hypot(slot.getX() - loc.getX(), slot.getZ() - loc.getZ());
                if (d > 28) {
                    npc.teleport(slot, PlayerTeleportEvent.TeleportCause.PLUGIN);
                } else if (d > 1.6) {
                    double fx = Math.cos(a.heading) * 3;
                    double fz = Math.sin(a.heading) * 3;
                    nav.setTarget(slot.clone().add(fx, 0, fz));
                }
            }
        }
    }

    // ------------------------------------------------------------------ death

    @EventHandler(priority = EventPriority.HIGH)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof LivingEntity le) || !e.getEntity().hasMetadata("NPC")) return;
        NPC npc = CitizensAPI.getNPCRegistry().getNPC(e.getEntity());
        if (npc == null) return;
        Member m = members.get(npc.getId());
        if (m == null) return;
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
        if (m.commander) commanderDown(npc, m, killer);
        else soldierDown(npc, m, killer);
    }

    private void soldierDown(NPC npc, Member m, Player killer) {
        Location loc = npc.getEntity().getLocation();
        World w = loc.getWorld();
        if (killer != null && w != null) {
            plugin.quests().onKill(killer, 4);
            if (rnd.nextDouble() < 0.10) {
                Material[] pieces = {Material.NETHERITE_HELMET, Material.NETHERITE_CHESTPLATE,
                        Material.NETHERITE_LEGGINGS, Material.NETHERITE_BOOTS};
                w.dropItemNaturally(loc, soldierArmor(pieces[rnd.nextInt(pieces.length)]));
            }
            if (rnd.nextDouble() < 0.25) {
                for (ItemStack item : LootListener.roll(1)) w.dropItemNaturally(loc, item);
            }
            w.spawn(loc, ExperienceOrb.class, orb -> orb.setExperience(40));
        }
        m.army.npcIds.remove((Integer) npc.getId());
        members.remove(npc.getId());
        m.army.alive = Math.max(0, m.army.alive - 1);
        npc.destroy();
    }

    private void commanderDown(NPC npc, Member m, Player killer) {
        Location loc = npc.getEntity().getLocation();
        World w = loc.getWorld();
        Army a = m.army;
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
        a.npcIds.remove((Integer) npc.getId());
        members.remove(npc.getId());
        npc.destroy();
        a.defeatedAt = System.currentTimeMillis();
        lastDefeat = a.defeatedAt;
        save();
    }

    private void dropCommanderLoot(World w, Location loc) {
        // the prize: elytra
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
