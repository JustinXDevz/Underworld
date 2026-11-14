package dev.underworld;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Menu clicks, relic abilities, lore pages and waystones. */
public final class GameplayListener implements Listener {

    private final UnderworldPlugin plugin;
    private final Map<UUID, Long> featherReady = new HashMap<>();
    private final Map<UUID, Long> compassReady = new HashMap<>();

    public GameplayListener(UnderworldPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ data + menus

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        plugin.quests().store().unload(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menus.Holder holder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() != e.getInventory()) return;
        Consumer<Player> action = holder.actions.get(e.getRawSlot());
        if (action != null) action.accept(p);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Menus.Holder) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ items and waystones

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        Action action = e.getAction();
        boolean right = action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
        if (!right) return;

        // waystones: lodestones at the foot of obelisks
        Block b = e.getClickedBlock();
        if (action == Action.RIGHT_CLICK_BLOCK && b != null && b.getType() == Material.LODESTONE
                && b.getWorld().getName().equals(plugin.underworldName()) && !p.isSneaking()) {
            if (handleWaystone(p, b)) {
                e.setCancelled(true);
                return;
            }
        }

        ItemStack hand = p.getInventory().getItemInMainHand();
        String id = plugin.relics().idOf(hand);
        if (id == null) return;
        switch (id) {
            case "void_compass" -> {
                e.setCancelled(true);
                useCompass(p);
            }
            case "void_feather" -> {
                e.setCancelled(true);
                useFeather(p);
            }
            case "lore_page" -> {
                e.setCancelled(true);
                readLore(p, hand);
            }
            default -> { }
        }
    }

    private boolean handleWaystone(Player p, Block b) {
        World w = b.getWorld();
        Terrain t = Terrain.get(w.getSeed());
        Terrain.Feature f = t.structure(Math.floorDiv(b.getX(), Terrain.STRUCT_CELL), Math.floorDiv(b.getZ(), Terrain.STRUCT_CELL));
        if (f.type() != Terrain.FeatureType.OBELISK
                || Math.max(Math.abs(b.getX() - f.x()), Math.abs(b.getZ() - f.z())) > 8) {
            return false;
        }
        PlayerStore.Data d = plugin.quests().data(p);
        String key = b.getX() + "," + b.getY() + "," + b.getZ();
        if (d.waystones.add(key)) {
            p.sendMessage(Txt.c("\u00a7d* Waystone activated! \u00a77You can now travel here from any waystone."));
            p.playSound(p.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1f, 1f);
            plugin.quests().addCoins(p, 30);
            plugin.quests().addXp(p, 15);
            plugin.quests().recheck(p);
        }
        Bukkit.getScheduler().runTask(plugin, () -> plugin.menus().openWaystones(p));
        return true;
    }

    private void useCompass(Player p) {
        long now = System.currentTimeMillis();
        Long ready = compassReady.get(p.getUniqueId());
        if (ready != null && now < ready) return;
        compassReady.put(p.getUniqueId(), now + 2000);

        World w = Bukkit.getWorld(plugin.underworldName());
        if (w == null || !p.getWorld().equals(w)) {
            p.sendMessage(Txt.c("\u00a7cThe compass only works in the Underworld."));
            return;
        }
        PlayerStore.Data d = plugin.quests().data(p);
        Terrain t = Terrain.get(w.getSeed());
        int px = p.getLocation().getBlockX();
        int pz = p.getLocation().getBlockZ();

        Terrain.Feature best = t.findStructure(px, pz, false, 12, f -> !d.discovered.contains(keyOf(f)));
        Terrain.Feature mega = t.findStructure(px, pz, true, 3, f -> !d.discovered.contains(keyOf(f)));
        if (mega != null && (best == null
                || Math.hypot(mega.x() - px, mega.z() - pz) < Math.hypot(best.x() - px, best.z() - pz))) {
            best = mega;
        }
        if (best == null) {
            p.sendMessage(Txt.c("\u00a77The compass is quiet. Nothing new lies nearby."));
            return;
        }
        p.setCompassTarget(new Location(w, best.x(), best.baseY(), best.z()));
        long dist = Math.round(Math.hypot(best.x() - px, best.z() - pz));
        p.sendMessage(Txt.c("\u00a7bThe compass hums: \u00a7f" + Menus.typeName(best.type().name())
                + " \u00a77(" + Menus.zoneName(best.zone().name()) + ") \u00a7f" + dist + " blocks " + direction(best.x() - px, best.z() - pz)));
    }

    private static String keyOf(Terrain.Feature f) {
        return f.type().name() + "|" + f.zone().name() + "|" + f.x() + "|" + f.z();
    }

    private static String direction(int dx, int dz) {
        double ang = Math.toDegrees(Math.atan2(dx, -dz));
        if (ang < 0) ang += 360;
        String[] names = {"north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"};
        return names[(int) Math.round(ang / 45.0) % 8];
    }

    private void useFeather(Player p) {
        long now = System.currentTimeMillis();
        Long ready = featherReady.get(p.getUniqueId());
        if (ready != null && now < ready) {
            p.sendMessage(Txt.c("\u00a77The feather needs " + ((ready - now) / 1000 + 1) + "s to recover."));
            return;
        }
        featherReady.put(p.getUniqueId(), now + 45_000);
        Vector dir = p.getLocation().getDirection();
        p.setVelocity(dir.multiply(1.5).setY(Math.max(dir.getY(), 0) * 0.5 + 0.9));
        p.setFallDistance(0f);
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 300, 0, false, false, true));
        p.playSound(p.getLocation(), Sound.ENTITY_BAT_TAKEOFF, 1f, 1f);
    }

    private void readLore(Player p, ItemStack item) {
        int idx = plugin.relics().loreIndex(item);
        if (idx < 0 || idx >= Relics.LORE.length) return;
        PlayerStore.Data d = plugin.quests().data(p);
        boolean fresh = d.lore.add(idx);
        item.setAmount(item.getAmount() - 1);
        p.sendMessage(Txt.c("\u00a7d" + Relics.LORE[idx][0]));
        for (String line : Txt.wrap(Relics.LORE[idx][1], 48, "\u00a77")) p.sendMessage(Txt.c(line));
        if (fresh) {
            plugin.quests().addCoins(p, 15);
            plugin.quests().addXp(p, 10);
        } else {
            plugin.quests().addCoins(p, 5);
        }
    }
}
