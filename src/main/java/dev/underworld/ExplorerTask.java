package dev.underworld;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashSet;
import java.util.Set;

/** Records discoveries (biomes, structures), pays for them, and applies relic effects. */
public final class ExplorerTask extends BukkitRunnable {

    private final UnderworldPlugin plugin;

    public ExplorerTask(UnderworldPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        World w = Bukkit.getWorld(plugin.underworldName());
        if (w == null) return;
        Terrain t = Terrain.get(w.getSeed());
        QuestEngine engine = plugin.quests();

        for (Player p : w.getPlayers()) {
            PlayerStore.Data d = engine.data(p);
            int x = p.getLocation().getBlockX();
            int z = p.getLocation().getBlockZ();

            // biome
            String zone = t.zone(x, z).name();
            if (d.zones.add(zone)) {
                p.sendMessage(Txt.c("\u00a7d* Discovered: \u00a7f" + Menus.zoneName(zone)));
                engine.addCoins(p, 25);
                engine.addXp(p, 10);
                engine.recheck(p);
            }

            // structures
            int cx = Math.floorDiv(x, Terrain.STRUCT_CELL);
            int cz = Math.floorDiv(z, Terrain.STRUCT_CELL);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Terrain.Feature f = t.structure(cx + dx, cz + dz);
                    if (f.type() == Terrain.FeatureType.NONE) continue;
                    if (Math.max(Math.abs(x - f.x()), Math.abs(z - f.z())) > f.a() + 10) continue;
                    discover(p, d, f, structureReward(f.type()));
                }
            }
            Terrain.Feature m = t.mega(x, z);
            if (m.type() != Terrain.FeatureType.NONE
                    && Math.max(Math.abs(x - m.x()), Math.abs(z - m.z())) <= m.a() + 15) {
                discover(p, d, m, 100);
            }

            relicEffects(p);
        }
    }

    private int structureReward(Terrain.FeatureType type) {
        return switch (type) {
            case OUTPOST -> 15;
            case TOWER, RUIN -> 10;
            case HOUSE -> 8;
            case OBELISK, FORTRESS -> 20;
            case MAZE -> 25;
            default -> 10;
        };
    }

    private void discover(Player p, PlayerStore.Data d, Terrain.Feature f, int coins) {
        String key = f.type().name() + "|" + f.zone().name() + "|" + f.x() + "|" + f.z();
        if (!d.discovered.add(key)) return;
        QuestEngine engine = plugin.quests();
        p.sendMessage(Txt.c("\u00a7d* Discovered: \u00a7f" + Menus.typeName(f.type().name())
                + " \u00a77(" + Menus.zoneName(f.zone().name()) + ")"));
        engine.addCoins(p, coins);
        engine.addXp(p, f.type() == Terrain.FeatureType.MEGA_CASTLE || f.type() == Terrain.FeatureType.KINGDOM ? 40 : 5);
        engine.recheck(p);
    }

    private void relicEffects(Player p) {
        Relics relics = plugin.relics();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 9; i++) {
            String id = relics.idOf(p.getInventory().getItem(i));
            if (id != null) ids.add(id);
        }
        ItemStack off = p.getInventory().getItemInOffHand();
        String offId = relics.idOf(off);
        if (offId != null) ids.add(offId);

        if (ids.contains("shardheart") || ids.contains("sigil")) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 120, 0, true, false, true));
        }
        if (ids.contains("emberheart")) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 120, 0, true, false, true));
        }
        if (ids.contains("lantern")) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 300, 0, true, false, true));
        }
        if (ids.contains("sigil")) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 120, 0, true, false, true));
            p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 120, 0, true, false, true));
        }
    }
}
