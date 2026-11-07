package dev.underworld;

import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.PluginCommand;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;

public final class UnderworldPlugin extends JavaPlugin {

    private TransitionListener transition;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        UnderworldBiomes.configure(getConfig().getString("fog-biome", "soul_sand_valley"));

        transition = new TransitionListener(this);
        getServer().getPluginManager().registerEvents(transition, this);
        getServer().getPluginManager().registerEvents(new ProtectionListener(this), this);
        getServer().getPluginManager().registerEvents(new LootListener(this), this);
        getServer().getPluginManager().registerEvents(new MobListener(this), this);
        new GuardTask(this).runTaskTimer(this, 100L, 100L);

        PluginCommand cmd = getCommand("underworld");
        if (cmd != null) cmd.setExecutor(new UnderworldCommand(this));

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
        w.setStorm(false);
        w.setThundering(false);
        if (w.getDifficulty() != Difficulty.HARD) w.setDifficulty(Difficulty.HARD);
    }

    public void reload() {
        reloadConfig();
        transition.reload();
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
