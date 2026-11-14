package dev.underworld;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Relics (special items with abilities), quest items and the collectible lore pages. */
public final class Relics {

    public static final Set<String> RELICS = Set.of("void_compass", "void_feather", "shardheart",
            "emberheart", "lantern", "sigil");

    public static final String[][] LORE = {
            {"The Heart of Vael", "A crystal the size of a cathedral hung above the throne, and by its light the whole kingdom stood."},
            {"The Queen's Last Edict", "Queen Isolde ordered the gates closed. Those inside were saved. Those outside were not."},
            {"The First Crack", "It began as a hairline in the plaza stones. By dusk the south road was gone."},
            {"Letters from the Watch", "Do not come south. The ground is not ground any more. Tell my wife I kept my post."},
            {"The Cartographer's Note", "I redrew the border six times this winter. The seventh will be a lake of nothing."},
            {"The Frost Seer", "The stones were raised before the kingdom. The kingdom only borrowed their silence."},
            {"A Gravekeeper's Ledger", "Four hundred and twelve buried. Two hundred more waiting. The sculk grows where they lie."},
            {"Hymn of the Crystal Choir", "Sing, shard of morning. Sing, glass of night. The Heart is mended when all voices unite."},
            {"The Knights' Oath", "We guard the road though no one walks it. We guard the dead though no one mourns them."},
            {"On Wardens", "They hear a heartbeat from a hundred paces. Walk softly. Better yet, do not walk."},
            {"The Cathedral Bell", "Nine strokes for the Fall. One more would end the watch. No one dares to ring it."},
            {"The Exile's Letter", "I leave my crown at the altar. Let Vael forget me, if that is what saves it."}
    };

    private final NamespacedKey idKey;
    private final NamespacedKey loreKey;

    public Relics(UnderworldPlugin plugin) {
        this.idKey = new NamespacedKey(plugin, "uw_item");
        this.loreKey = new NamespacedKey(plugin, "uw_lore");
    }

    private ItemStack build(Material m, int amount, String id, String name, String... lore) {
        ItemStack item = new ItemStack(m, amount);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Txt.c(name));
        List<String> lines = new ArrayList<>();
        for (String l : lore) lines.add(l);
        meta.lore(Txt.lore(lines));
        meta.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, id);
        item.setItemMeta(meta);
        return item;
    }

    public ItemStack create(String id, int amount) {
        return switch (id) {
            case "void_compass" -> build(Material.COMPASS, amount, id, "\u00a7bVoid Compass",
                    "\u00a77Right-click: points to the nearest", "\u00a77structure you have not found yet.");
            case "void_feather" -> build(Material.FEATHER, amount, id, "\u00a7bVoid Feather",
                    "\u00a77Right-click: leap forward and glide.", "\u00a78Cooldown: 45 seconds");
            case "shardheart" -> build(Material.AMETHYST_SHARD, amount, id, "\u00a7aShardheart Charm",
                    "\u00a77Hold in your hotbar: Speed I.");
            case "emberheart" -> build(Material.MAGMA_CREAM, amount, id, "\u00a76Emberheart",
                    "\u00a77Hold in your hotbar: Fire Resistance.");
            case "lantern" -> build(Material.SOUL_LANTERN, amount, id, "\u00a73Lantern of the Deep",
                    "\u00a77Hold in your hotbar: Night Vision.");
            case "sigil" -> build(Material.NETHER_STAR, amount, id, "\u00a7dVoidwalker's Sigil",
                    "\u00a77Hold in your hotbar: Strength I,", "\u00a77Speed I and Regeneration.");
            case "crystal_shard" -> build(Material.PRISMARINE_SHARD, amount, id, "\u00a7aCrystal Shard",
                    "\u00a77A warm, humming shard.", "\u00a78Quest item");
            case "map_fragment" -> build(Material.PAPER, amount, id, "\u00a7eTorn Map Fragment",
                    "\u00a77A scrap of an old road map.", "\u00a78Quest item");
            case "frost_locket" -> build(Material.GOLD_NUGGET, amount, id, "\u00a7bFrost Locket",
                    "\u00a77Cold to the touch.", "\u00a78Quest item");
            case "grave_token" -> build(Material.ECHO_SHARD, amount, id, "\u00a78Grave Token",
                    "\u00a77Left on a grave long ago.", "\u00a78Quest item");
            case "royal_seal" -> build(Material.GOLD_INGOT, amount, id, "\u00a76Royal Seal",
                    "\u00a77Stamped with the queen's crest.", "\u00a78Quest item");
            default -> null;
        };
    }

    public ItemStack lorePage(int index) {
        String[] page = LORE[index];
        ItemStack item = build(Material.PAPER, 1, "lore_page", "\u00a7dLore Page: " + page[0],
                "\u00a77Right-click to read it and add it", "\u00a77to your journal.");
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(loreKey, PersistentDataType.INTEGER, index);
        item.setItemMeta(meta);
        return item;
    }

    public String idOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
    }

    public int loreIndex(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return -1;
        Integer v = item.getItemMeta().getPersistentDataContainer().get(loreKey, PersistentDataType.INTEGER);
        return v == null ? -1 : v;
    }

    public int count(Player p, String id) {
        int n = 0;
        for (ItemStack it : p.getInventory().getContents()) {
            if (it != null && id.equals(idOf(it))) n += it.getAmount();
        }
        return n;
    }

    public void take(Player p, String id, int amount) {
        ItemStack[] contents = p.getInventory().getContents();
        for (int i = 0; i < contents.length && amount > 0; i++) {
            ItemStack it = contents[i];
            if (it == null || !id.equals(idOf(it))) continue;
            int remove = Math.min(amount, it.getAmount());
            amount -= remove;
            if (remove >= it.getAmount()) p.getInventory().setItem(i, null);
            else it.setAmount(it.getAmount() - remove);
        }
    }

    /** Gives an item, dropping what does not fit at the player's feet. */
    public void give(Player p, ItemStack item) {
        for (ItemStack left : p.getInventory().addItem(item).values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), left);
        }
    }
}
