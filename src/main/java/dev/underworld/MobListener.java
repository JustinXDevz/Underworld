package dev.underworld;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.ItemStack;

import java.util.concurrent.ThreadLocalRandom;

/**
 * No natural mobs in the Underworld. Allowed: the netherite knights/guards (spawned by this plugin),
 * wardens (summoned by sculk shriekers) and anything an admin spawns by hand.
 */
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
        if (e.getEntityType() == EntityType.WARDEN) return;
        SpawnReason reason = e.getSpawnReason();
        if (reason == SpawnReason.CUSTOM || reason == SpawnReason.COMMAND || reason == SpawnReason.SPAWNER_EGG) return;
        e.setCancelled(true);
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        if (!inUnderworld(e.getWorld())) return;
        if (plugin.getConfig().getBoolean("allow-mobs", false)) return;
        for (Entity en : e.getEntities()) {
            if (!(en instanceof Mob)) continue;
            if (en.getType() == EntityType.WARDEN || plugin.knights().kind(en) > 0) continue;
            en.remove();
        }
    }

    /** Knights and guards drop extra loot on top of their gear; kills pay coins and count for quests. */
    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        Player killer = e.getEntity().getKiller();
        if (e.getEntityType() == EntityType.WARDEN) {
            if (killer != null && inUnderworld(e.getEntity().getWorld())) plugin.quests().onKill(killer, 3);
            return;
        }
        int kind = plugin.knights().kind(e.getEntity());
        if (kind == 0) return;
        if (killer != null) plugin.quests().onKill(killer, kind);
        e.getDrops().addAll(LootListener.roll(kind == 1 ? 6 : 3));
        e.getDrops().add(new ItemStack(Material.NETHERITE_SCRAP, 1 + ThreadLocalRandom.current().nextInt(3)));
        e.setDroppedExp(kind == 1 ? 500 : 250);
    }
}
