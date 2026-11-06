package dev.underworld;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** Chests in ruins are generated empty and filled with loot the first time someone opens them. */
public final class LootListener implements Listener {

    private record Entry(int weight, Material material, int min, int max) { }

    private static final List<Entry> TABLE = List.of(
            new Entry(14, Material.GOLD_INGOT, 4, 10),
            new Entry(10, Material.GOLDEN_APPLE, 1, 2),
            new Entry(10, Material.ENDER_PEARL, 2, 4),
            new Entry(10, Material.COOKED_BEEF, 8, 16),
            new Entry(8, Material.DIAMOND, 1, 3),
            new Entry(8, Material.OBSIDIAN, 4, 12),
            new Entry(8, Material.BLAZE_ROD, 2, 5),
            new Entry(4, Material.NETHERITE_SCRAP, 1, 2),
            new Entry(3, Material.ANCIENT_DEBRIS, 1, 2),
            new Entry(2, Material.WITHER_SKELETON_SKULL, 1, 1),
            new Entry(2, Material.DIAMOND_SWORD, 1, 1),
            new Entry(1, Material.NETHERITE_INGOT, 1, 1),
            new Entry(1, Material.ENCHANTED_GOLDEN_APPLE, 1, 1),
            new Entry(1, Material.TOTEM_OF_UNDYING, 1, 1)
    );
    private static final int TOTAL_WEIGHT = TABLE.stream().mapToInt(Entry::weight).sum();

    private final UnderworldPlugin plugin;
    private final NamespacedKey looted;

    public LootListener(UnderworldPlugin plugin) {
        this.plugin = plugin;
        this.looted = new NamespacedKey(plugin, "looted");
    }

    @EventHandler(ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        Location loc = e.getInventory().getLocation();
        if (loc == null || loc.getWorld() == null) return;
        if (!loc.getWorld().getName().equals(plugin.underworldName())) return;

        BlockState state = loc.getBlock().getState();
        if (!(state instanceof Chest chest)) return;
        if (chest.getPersistentDataContainer().has(looted, PersistentDataType.BYTE)) return;

        fill(chest.getBlockInventory());
        chest.getPersistentDataContainer().set(looted, PersistentDataType.BYTE, (byte) 1);
        chest.update();
    }

    private void fill(Inventory inv) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int rolls = 4 + r.nextInt(5);
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < inv.getSize(); i++) slots.add(i);

        for (int i = 0; i < rolls && !slots.isEmpty(); i++) {
            Entry entry = pick(r.nextInt(TOTAL_WEIGHT));
            int amount = entry.min() + r.nextInt(entry.max() - entry.min() + 1);
            int slot = slots.remove(r.nextInt(slots.size()));
            inv.setItem(slot, new ItemStack(entry.material(), amount));
        }
        // Always one fire resistance potion
        if (!slots.isEmpty()) {
            ItemStack potion = new ItemStack(Material.POTION);
            PotionMeta meta = (PotionMeta) potion.getItemMeta();
            meta.setBasePotionType(PotionType.FIRE_RESISTANCE);
            potion.setItemMeta(meta);
            inv.setItem(slots.remove(r.nextInt(slots.size())), potion);
        }
    }

    private Entry pick(int roll) {
        for (Entry e : TABLE) {
            roll -= e.weight();
            if (roll < 0) return e;
        }
        return TABLE.get(0);
    }
}
