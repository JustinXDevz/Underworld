package dev.underworld;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.enchantments.Enchantment;
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

    private record Ench(Enchantment enchantment, int level) { }

    private record Entry(int weight, Material material, int min, int max, Ench... enchants) { }

    private static final Ench PROT = new Ench(Enchantment.PROTECTION, 4);
    private static final Ench UNB = new Ench(Enchantment.UNBREAKING, 3);
    private static final Ench MEND = new Ench(Enchantment.MENDING, 1);
    private static final Ench SHARP = new Ench(Enchantment.SHARPNESS, 5);
    private static final Ench EFF = new Ench(Enchantment.EFFICIENCY, 5);

    private static final List<Entry> TABLE = List.of(
            // materials
            new Entry(10, Material.NETHERITE_INGOT, 1, 3),
            new Entry(10, Material.NETHERITE_SCRAP, 2, 5),
            new Entry(6, Material.ANCIENT_DEBRIS, 2, 5),
            new Entry(12, Material.DIAMOND, 3, 8),
            new Entry(4, Material.DIAMOND_BLOCK, 1, 2),
            new Entry(6, Material.GOLD_BLOCK, 1, 3),
            new Entry(3, Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 1, 2),
            // survival goodies
            new Entry(6, Material.ENCHANTED_GOLDEN_APPLE, 1, 2),
            new Entry(8, Material.GOLDEN_APPLE, 3, 6),
            new Entry(4, Material.TOTEM_OF_UNDYING, 1, 1),
            new Entry(6, Material.ENDER_PEARL, 4, 8),
            new Entry(8, Material.EXPERIENCE_BOTTLE, 8, 20),
            new Entry(6, Material.COOKED_BEEF, 16, 32),
            new Entry(2, Material.WITHER_SKELETON_SKULL, 1, 2),
            new Entry(1, Material.NETHER_STAR, 1, 1),
            new Entry(1, Material.BEACON, 1, 1),
            new Entry(1, Material.ELYTRA, 1, 1, UNB, MEND),
            // enchanted netherite gear
            new Entry(2, Material.NETHERITE_SWORD, 1, 1, SHARP, UNB, MEND),
            new Entry(2, Material.NETHERITE_PICKAXE, 1, 1, EFF, UNB, MEND),
            new Entry(2, Material.NETHERITE_AXE, 1, 1, EFF, UNB, MEND),
            new Entry(1, Material.NETHERITE_HELMET, 1, 1, PROT, UNB, MEND),
            new Entry(1, Material.NETHERITE_CHESTPLATE, 1, 1, PROT, UNB, MEND),
            new Entry(1, Material.NETHERITE_LEGGINGS, 1, 1, PROT, UNB, MEND),
            new Entry(1, Material.NETHERITE_BOOTS, 1, 1, PROT, UNB, MEND)
    );
    private static final int TOTAL_WEIGHT = TABLE.stream().mapToInt(Entry::weight).sum();

    private final UnderworldPlugin plugin;
    // New key on purpose: chests opened by the old (buggy) version get filled again.
    private final NamespacedKey looted;

    public LootListener(UnderworldPlugin plugin) {
        this.plugin = plugin;
        this.looted = new NamespacedKey(plugin, "looted_v2");
    }

    @EventHandler(ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        Location loc = e.getInventory().getLocation();
        if (loc == null || loc.getWorld() == null) return;
        if (!loc.getWorld().getName().equals(plugin.underworldName())) return;

        BlockState state = loc.getBlock().getState();
        if (!(state instanceof Chest chest)) return;
        if (chest.getPersistentDataContainer().has(looted, PersistentDataType.BYTE)) return;

        // Save the "looted" flag FIRST, then fill the live inventory.
        // (Saving after filling wrote the empty snapshot back and wiped the loot.)
        chest.getPersistentDataContainer().set(looted, PersistentDataType.BYTE, (byte) 1);
        chest.update();
        fill(e.getInventory());
    }

    /** Rolls loot from the table (also used for knight drops). */
    public static List<ItemStack> roll(int rolls) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < rolls; i++) {
            Entry entry = pick(r.nextInt(TOTAL_WEIGHT));
            int amount = entry.min() + r.nextInt(entry.max() - entry.min() + 1);
            ItemStack item = new ItemStack(entry.material(), amount);
            for (Ench en : entry.enchants()) item.addUnsafeEnchantment(en.enchantment(), en.level());
            out.add(item);
        }
        return out;
    }

    private void fill(Inventory inv) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < inv.getSize(); i++) slots.add(i);

        // Guaranteed goodies
        put(inv, slots, new ItemStack(Material.NETHERITE_INGOT, 1 + r.nextInt(2)));
        put(inv, slots, fireResistance());

        for (ItemStack item : roll(7 + r.nextInt(5))) put(inv, slots, item);
    }

    private void put(Inventory inv, List<Integer> slots, ItemStack item) {
        if (slots.isEmpty()) return;
        int slot = slots.remove(ThreadLocalRandom.current().nextInt(slots.size()));
        inv.setItem(slot, item);
    }

    private ItemStack fireResistance() {
        ItemStack potion = new ItemStack(Material.POTION);
        PotionMeta meta = (PotionMeta) potion.getItemMeta();
        meta.setBasePotionType(PotionType.LONG_FIRE_RESISTANCE);
        potion.setItemMeta(meta);
        return potion;
    }

    private static Entry pick(int roll) {
        for (Entry e : TABLE) {
            roll -= e.weight();
            if (roll < 0) return e;
        }
        return TABLE.get(0);
    }
}
