package dev.underworld;

import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.WorldCreator;
import org.bukkit.command.PluginCommand;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;

public final class UnderworldPlugin extends JavaPlugin {

    private TransitionListener transition;
    private Knights knights;
    private Runnable guardShutdown;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        UnderworldBiomes.configure(getConfig().getString("fog-biome", "soul_sand_valley"));

        knights = new Knights(this);
        transition = new TransitionListener(this);
        getServer().getPluginManager().registerEvents(transition, this);
        getServer().getPluginManager().registerEvents(new ProtectionListener(this), this);
        getServer().getPluginManager().registerEvents(new LootListener(this), this);
        getServer().getPluginManager().registerEvents(new MobListener(this), this);
        boolean citizens = getConfig().getBoolean("use-citizens", true)
                && getServer().getPluginManager().isPluginEnabled("Citizens");
        if (citizens) {
            try {
                guardShutdown = CitizensHook.start(this);
                getLogger().info("Guards are Citizens NPCs.");
            } catch (Throwable e) {
                getLogger().warning("Could not hook into Citizens (" + e + "). Using wither skeleton guards instead.");
                citizens = false;
            }
        }
        if (!citizens) {
            new GuardTask(this).runTaskTimer(this, 100L, 40L);
            getLogger().info("Guards are wither skeletons.");
        }
        getServer().getScheduler().runTaskTimer(this, this::snowfall, 40L, 10L);

        PluginCommand cmd = getCommand("underworld");
        if (cmd != null) {
            UnderworldCommand executor = new UnderworldCommand(this);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }

        // Give Multiverse (if installed) time to load its worlds first
        if (getConfig().getBoolean("auto-create-world", true)) {
            getServer().getScheduler().runTaskLater(this, this::ensureWorld, 40L);
        }
        // Keep the Underworld permanently dark
        getServer().getScheduler().runTaskTimer(this, this::lockWorld, 100L, 100L);
    }

    /** Used by Multiverse: /mv create underworld normal -g Underworld */
    @Override
    public ChunkGenerator getDefaultWorldGenerator(String worldName, String id) {
        return new UnderworldGenerator();
    }

    private void ensureWorld() {
        String name = underworldName();
        if (Bukkit.getWorld(name) != null) return;
        getLogger().info("Creating world '" + name + "'...");
        Bukkit.createWorld(new WorldCreator(name)
                .environment(World.Environment.NORMAL)
                .generator(new UnderworldGenerator()));
    }

    private void lockWorld() {
        World w = Bukkit.getWorld(underworldName());
        if (w == null) return;
        w.setTime(getConfig().getLong("world-time", 13000L));
        WorldBorder border = w.getWorldBorder();
        double size = getConfig().getDouble("border-size", 0);
        if (size <= 0) size = 59999968;
        if (border.getSize() != size) {
            border.setCenter(0, 0);
            border.setSize(size);
        }
        w.setStorm(false);
        w.setThundering(false);
        if (w.getDifficulty() != Difficulty.HARD) w.setDifficulty(Difficulty.HARD);
    }

    @Override
    public void onDisable() {
        if (guardShutdown != null) guardShutdown.run();
    }

    /** Falling snow around players in the Snow Soul. */
    private void snowfall() {
        World w = Bukkit.getWorld(underworldName());
        if (w == null) return;
        Terrain t = Terrain.get(w.getSeed());
        for (org.bukkit.entity.Player p : w.getPlayers()) {
            org.bukkit.Location l = p.getLocation();
            if (t.zone(l.getBlockX(), l.getBlockZ()) == Terrain.Zone.SNOW) {
                p.spawnParticle(org.bukkit.Particle.SNOWFLAKE, l.getX(), l.getY() + 7, l.getZ(), 50, 14, 6, 14, 0.02);
            }
        }
    }

    public void reload() {
        reloadConfig();
        transition.reload();
    }

    public Knights knights() {
        return knights;
    }

    public TransitionListener transition() {
        return transition;
    }

    public String underworldName() {
        return getConfig().getString("underworld-world", "underworld");
    }

    public String netherName() {
        return getConfig().getString("nether-world", "world_nether");
    }
}
