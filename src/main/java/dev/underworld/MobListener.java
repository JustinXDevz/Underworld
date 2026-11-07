package dev.underworld;

import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.event.world.EntitiesLoadEvent;

/** No mobs in the Underworld: blocks natural spawns and removes mobs that already exist. */
public final class MobListener implements Listener {

    private final UnderworldPlugin plugin;

    public MobListener(UnderworldPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean inUnderworld(World w) {
        return w != null && w.getName().equals(plugin.underworldName());
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (!inUnderworld(e.getLocation().getWorld())) return;
        if (plugin.getConfig().getBoolean("allow-mobs", false)) return;
        SpawnReason reason = e.getSpawnReason();
        // Plugins (guards), /summon and spawn eggs still work for admins
        if (reason == SpawnReason.CUSTOM || reason == SpawnReason.COMMAND || reason == SpawnReason.SPAWNER_EGG) return;
        e.setCancelled(true);
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        if (!inUnderworld(e.getWorld())) return;
        if (plugin.getConfig().getBoolean("allow-mobs", false)) return;
        if (plugin.getConfig().getBoolean("guard-mobs", false)) return;
        for (Entity en : e.getEntities()) {
            if (en instanceof Mob) en.remove();
        }
    }
}
