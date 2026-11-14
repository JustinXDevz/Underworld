package dev.underworld;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/** Chest-style menus: they work the same on Java and on Bedrock (through Geyser). */
public final class Menus {

    public static final class Holder implements InventoryHolder {
        Inventory inv;
        long confirmUntil;
        final Map<Integer, Consumer<Player>> actions = new HashMap<>();

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private record Shop(String key, Material mat, int amount, String name, int price, int rank) { }

    private static final List<Shop> SHOP = List.of(
            new Shop("void_compass", Material.COMPASS, 1, "Void Compass", 150, 0),
            new Shop("void_feather", Material.FEATHER, 1, "Void Feather", 400, 1),
            new Shop("lantern", Material.SOUL_LANTERN, 1, "Lantern of the Deep", 600, 1),
            new Shop("shardheart", Material.AMETHYST_SHARD, 1, "Shardheart Charm", 500, 1),
            new Shop("emberheart", Material.MAGMA_CREAM, 1, "Emberheart", 450, 1),
            new Shop("random_lore", Material.PAPER, 1, "Random Lore Page", 80, 0),
            new Shop(null, Material.GOLDEN_APPLE, 3, "Golden Apple x3", 60, 0),
            new Shop(null, Material.ENDER_PEARL, 4, "Ender Pearl x4", 90, 0),
            new Shop("fire_potion", Material.POTION, 1, "Fire Resistance Potion", 40, 0),
            new Shop(null, Material.TOTEM_OF_UNDYING, 1, "Totem of Undying", 350, 2),
            new Shop(null, Material.NETHERITE_INGOT, 1, "Netherite Ingot", 700, 2),
            new Shop(null, Material.ELYTRA, 1, "Elytra", 1500, 3)
    );

    private static final Map<Material, Integer> SELL = new HashMap<>();

    static {
        SELL.put(Material.GOLD_INGOT, 4);
        SELL.put(Material.GOLD_BLOCK, 36);
        SELL.put(Material.DIAMOND, 15);
        SELL.put(Material.DIAMOND_BLOCK, 135);
        SELL.put(Material.NETHERITE_SCRAP, 40);
        SELL.put(Material.ANCIENT_DEBRIS, 45);
        SELL.put(Material.NETHERITE_INGOT, 180);
        SELL.put(Material.ENCHANTED_GOLDEN_APPLE, 120);
        SELL.put(Material.GOLDEN_APPLE, 12);
        SELL.put(Material.TOTEM_OF_UNDYING, 80);
        SELL.put(Material.ENDER_PEARL, 6);
        SELL.put(Material.BLAZE_ROD, 5);
        SELL.put(Material.WITHER_SKELETON_SKULL, 90);
        SELL.put(Material.NETHER_STAR, 400);
        SELL.put(Material.BEACON, 500);
        SELL.put(Material.ELYTRA, 300);
        SELL.put(Material.NETHERITE_SWORD, 350);
        SELL.put(Material.NETHERITE_PICKAXE, 300);
        SELL.put(Material.NETHERITE_AXE, 300);
        SELL.put(Material.NETHERITE_HELMET, 300);
        SELL.put(Material.NETHERITE_CHESTPLATE, 400);
        SELL.put(Material.NETHERITE_LEGGINGS, 350);
        SELL.put(Material.NETHERITE_BOOTS, 280);
        SELL.put(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 60);
        SELL.put(Material.OBSIDIAN, 1);
    }

    private final UnderworldPlugin plugin;

    public Menus(UnderworldPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ helpers

    private Holder make(String title, int rows) {
        Holder h = new Holder();
        h.inv = Bukkit.createInventory(h, rows * 9, Txt.c(title));
        return h;
    }

    private ItemStack icon(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Txt.c(name));
        meta.lore(Txt.lore(lore));
        it.setItemMeta(meta);
        return it;
    }

    private void set(Holder h, int slot, ItemStack it, Consumer<Player> action) {
        h.inv.setItem(slot, it);
        if (action != null) h.actions.put(slot, action);
    }

    private void fillEmpty(Holder h) {
        for (int i = 0; i < h.inv.getSize(); i++) {
            if (h.inv.getItem(i) == null) h.inv.setItem(i, icon(Material.GRAY_STAINED_GLASS_PANE, " ", List.of()));
        }
    }

    private void later(Runnable r) {
        Bukkit.getScheduler().runTask(plugin, r);
    }

    private void open(Player p, Holder h) {
        fillEmpty(h);
        p.openInventory(h.inv);
    }

    static String zoneName(String zone) {
        return switch (zone) {
            case "BASE" -> "The Hub";
            case "CRYSTAL" -> "Crystal Wasteland";
            case "CRACK" -> "The Crack";
            case "SNOW" -> "Snow Soul";
            case "DARK" -> "Valley of Dark";
            default -> zone;
        };
    }

    static String typeName(String type) {
        return switch (type) {
            case "FORTRESS" -> "Fortresses";
            case "TOWER" -> "Towers";
            case "MAZE" -> "Mazes";
            case "RUIN" -> "Ruins";
            case "OUTPOST" -> "Outposts";
            case "HOUSE" -> "Houses";
            case "OBELISK" -> "Obelisks";
            case "MEGA_CASTLE" -> "Crystal Citadels";
            case "KINGDOM" -> "Kingdoms";
            default -> type;
        };
    }

    // ------------------------------------------------------------------ NPC menu

    public void openNpc(Player p, Role role) {
        QuestEngine engine = plugin.quests();
        engine.onTalk(p, role);
        PlayerStore.Data d = engine.data(p);

        Holder h = make(role.coloredName(), 3);
        set(h, 4, icon(Material.BOOK, "\u00a7e" + role.display, Txt.wrap(role.greeting, 34, "\u00a77")), null);

        List<Quest> qs = Quests.byGiver(role);
        int[] slots = {10, 13, 16};
        for (int i = 0; i < qs.size() && i < slots.length; i++) {
            questButton(h, slots[i], p, d, qs.get(i), role);
        }
        if (role.shop) {
            set(h, 22, icon(Material.EMERALD, "\u00a7aTrade", List.of("\u00a77Buy relics and supplies,", "\u00a77sell your loot for coins.")),
                    pl -> later(() -> openShop(pl, role)));
        }
        open(p, h);
    }

    private void questButton(Holder h, int slot, Player p, PlayerStore.Data d, Quest q, Role role) {
        QuestEngine engine = plugin.quests();
        List<String> lore = new ArrayList<>();
        if (d.completed.contains(q.id())) {
            lore.add("\u00a77Completed");
            set(h, slot, icon(Material.LIME_DYE, "\u00a7a[x] " + q.title(), lore), null);
            return;
        }
        if (d.active.containsKey(q.id())) {
            int[] prog = d.active.get(q.id());
            for (int i = 0; i < q.objectives().size(); i++) {
                Quest.Obj o = q.objectives().get(i);
                int v = Math.min(engine.value(p, d, prog, o, i), o.amount());
                lore.add((v >= o.amount() ? "\u00a7a" : "\u00a77") + "- " + o.label() + " (" + v + "/" + o.amount() + ")");
            }
            boolean ready = engine.isComplete(p, d, q);
            lore.add("");
            lore.add(ready ? "\u00a7aClick to turn in!" : "\u00a78In progress");
            set(h, slot, icon(ready ? Material.WRITABLE_BOOK : Material.BOOK, (ready ? "\u00a7a" : "\u00a7e") + q.title(), lore),
                    pl -> {
                        engine.turnIn(pl, q);
                        later(() -> openNpc(pl, role));
                    });
            return;
        }
        if (!engine.requirementsMet(d, q)) {
            lore.add("\u00a77Requires:");
            for (int r : q.requires()) {
                if (!d.completed.contains(r)) {
                    Quest rq = Quests.byId(r);
                    lore.add("\u00a78- " + (rq == null ? "?" : rq.title()));
                }
            }
            set(h, slot, icon(Material.BARRIER, "\u00a7c" + q.title(), lore), null);
            return;
        }
        lore.addAll(Txt.wrap(q.intro(), 34, "\u00a77"));
        lore.add("");
        for (Quest.Obj o : q.objectives()) lore.add("\u00a78- \u00a7f" + o.label());
        lore.add("");
        lore.add("\u00a76Reward: \u00a7f" + q.coins() + " coins, " + q.xp() + " xp" + (q.relic() != null ? " + a relic" : ""));
        lore.add("\u00a7aClick to accept");
        set(h, slot, icon(Material.PAPER, "\u00a7e" + q.title(), lore), pl -> {
            engine.accept(pl, q);
            later(() -> openNpc(pl, role));
        });
    }

    // ------------------------------------------------------------------ shop

    private ItemStack shopStack(Shop s, Player p) {
        if (s.key() == null) return new ItemStack(s.mat(), s.amount());
        if (s.key().equals("random_lore")) {
            PlayerStore.Data d = plugin.quests().data(p);
            List<Integer> missing = new ArrayList<>();
            for (int i = 0; i < Relics.LORE.length; i++) if (!d.lore.contains(i)) missing.add(i);
            int idx = missing.isEmpty() ? ThreadLocalRandom.current().nextInt(Relics.LORE.length)
                    : missing.get(ThreadLocalRandom.current().nextInt(missing.size()));
            return plugin.relics().lorePage(idx);
        }
        if (s.key().equals("fire_potion")) {
            ItemStack potion = new ItemStack(Material.POTION);
            PotionMeta meta = (PotionMeta) potion.getItemMeta();
            meta.setBasePotionType(PotionType.LONG_FIRE_RESISTANCE);
            potion.setItemMeta(meta);
            return potion;
        }
        return plugin.relics().create(s.key(), 1);
    }

    public void openShop(Player p, Role role) {
        QuestEngine engine = plugin.quests();
        PlayerStore.Data d = engine.data(p);
        int rank = engine.rank(d);
        Holder h = make("\u00a7b" + role.display + " \u00a78- Trade", 6);

        for (int i = 0; i < SHOP.size(); i++) {
            Shop s = SHOP.get(i);
            List<String> lore = new ArrayList<>();
            lore.add("\u00a76Price: \u00a7f" + s.price() + " coins");
            if (s.rank() > rank) {
                lore.add("\u00a7cRequires rank: " + QuestEngine.RANK_NAME[s.rank()]);
                set(h, i, icon(Material.BARRIER, "\u00a7c" + s.name(), lore), null);
            } else {
                lore.add("\u00a7aClick to buy");
                set(h, i, icon(s.mat(), "\u00a7f" + s.name(), lore), pl -> {
                    if (!engine.takeCoins(pl, s.price())) {
                        pl.sendMessage(Txt.c("\u00a7cNot enough coins."));
                        return;
                    }
                    ItemStack stack = shopStack(s, pl);
                    if (stack != null) plugin.relics().give(pl, stack);
                    pl.sendMessage(Txt.c("\u00a7aBought " + s.name() + " for " + s.price() + " coins."));
                    later(() -> openShop(pl, role));
                });
            }
        }

        set(h, 45, icon(Material.GOLD_INGOT, "\u00a76Your coins: \u00a7f" + d.coins, List.of()), null);
        set(h, 47, icon(Material.HOPPER, "\u00a7eSell item in hand", List.of("\u00a77Sells the stack you are holding.")),
                pl -> {
                    ItemStack hand = pl.getInventory().getItemInMainHand();
                    int value = sellValue(hand);
                    if (value <= 0) {
                        pl.sendMessage(Txt.c("\u00a7cThe merchant does not want that."));
                        return;
                    }
                    pl.getInventory().setItemInMainHand(null);
                    engine.addCoins(pl, value);
                    later(() -> openShop(pl, role));
                });
        set(h, 49, icon(Material.CHEST, "\u00a7cSell ALL valuables",
                List.of("\u00a77Sells every item the merchant buys,", "\u00a77in your whole inventory.", "\u00a7eClick twice to confirm.")),
                pl -> {
                    long now = System.currentTimeMillis();
                    if (now > h.confirmUntil) {
                        h.confirmUntil = now + 8000;
                        pl.sendMessage(Txt.c("\u00a7eClick again within 8 seconds to sell ALL valuables (" + totalSellValue(pl) + " coins)."));
                        return;
                    }
                    long total = 0;
                    ItemStack[] contents = pl.getInventory().getStorageContents();
                    for (int i = 0; i < contents.length; i++) {
                        int v = sellValue(contents[i]);
                        if (v > 0) {
                            total += v;
                            pl.getInventory().setItem(i, null);
                        }
                    }
                    if (total > 0) engine.addCoins(pl, total);
                    else pl.sendMessage(Txt.c("\u00a7cNothing to sell."));
                    later(() -> openShop(pl, role));
                });
        set(h, 53, icon(Material.ARROW, "\u00a77Back", List.of()), pl -> later(() -> openNpc(pl, role)));
        open(p, h);
    }

    private int sellValue(ItemStack it) {
        if (it == null || it.getType() == Material.AIR) return 0;
        if (plugin.relics().idOf(it) != null) return 0;
        Integer v = SELL.get(it.getType());
        return v == null ? 0 : v * it.getAmount();
    }

    private long totalSellValue(Player p) {
        long total = 0;
        for (ItemStack it : p.getInventory().getStorageContents()) total += sellValue(it);
        return total;
    }

    // ------------------------------------------------------------------ journal

    public void openJournal(Player p) {
        QuestEngine engine = plugin.quests();
        PlayerStore.Data d = engine.data(p);
        int rank = engine.rank(d);
        Holder h = make("\u00a75Underworld Journal", 6);

        List<String> rankLore = new ArrayList<>();
        rankLore.add("\u00a77Rank: \u00a7f" + QuestEngine.RANK_NAME[rank]);
        if (rank + 1 < QuestEngine.RANK_XP.length) {
            rankLore.add("\u00a77XP: \u00a7f" + d.xp + " / " + QuestEngine.RANK_XP[rank + 1]);
        } else {
            rankLore.add("\u00a77XP: \u00a7f" + d.xp + " (max rank)");
        }
        rankLore.add("\u00a77Coin bonus: \u00a7a+" + (rank * 5) + "%");
        rankLore.add("\u00a77Waystone fee discount: \u00a7a-" + Math.min(40, rank * 10) + "%");
        set(h, 11, icon(Material.NAME_TAG, "\u00a7dRank", rankLore), null);
        set(h, 13, icon(Material.GOLD_INGOT, "\u00a76Underworld coins", List.of("\u00a7f" + d.coins)), null);

        List<String> active = new ArrayList<>();
        for (Map.Entry<Integer, int[]> e : d.active.entrySet()) {
            Quest q = Quests.byId(e.getKey());
            if (q == null) continue;
            active.add("\u00a7e" + q.title() + " \u00a78(" + q.giver().display + ")");
            for (int i = 0; i < q.objectives().size(); i++) {
                Quest.Obj o = q.objectives().get(i);
                int v = Math.min(engine.value(p, d, e.getValue(), o, i), o.amount());
                active.add((v >= o.amount() ? "\u00a7a" : "\u00a77") + "  " + o.label() + " " + v + "/" + o.amount());
            }
        }
        if (active.isEmpty()) active.add("\u00a77No active quests. Talk to the survivors of Vael.");
        set(h, 15, icon(Material.BOOK, "\u00a7eActive quests", active), null);

        List<String> done = new ArrayList<>();
        done.add("\u00a77" + d.completed.size() + " / " + Quests.all().size() + " completed");
        int shown = 0;
        for (int id : d.completed) {
            Quest q = Quests.byId(id);
            if (q != null && shown++ < 12) done.add("\u00a7a- " + q.title());
        }
        set(h, 22, icon(Material.EMERALD_BLOCK, "\u00a7aCompleted quests", done), null);

        List<String> zones = new ArrayList<>();
        for (String z : new String[] {"BASE", "CRYSTAL", "CRACK", "SNOW", "DARK"}) {
            zones.add((d.zones.contains(z) ? "\u00a7a[x] " : "\u00a78[ ] ") + zoneName(z));
        }
        set(h, 29, icon(Material.MAP, "\u00a7bBiomes", zones), null);

        Map<String, Integer> counts = new TreeMap<>();
        for (String key : d.discovered) {
            String[] parts = key.split("\\|");
            counts.merge(parts[0], 1, Integer::sum);
        }
        List<String> structures = new ArrayList<>();
        if (counts.isEmpty()) structures.add("\u00a77Nothing discovered yet.");
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            structures.add("\u00a77" + typeName(e.getKey()) + ": \u00a7f" + e.getValue());
        }
        set(h, 31, icon(Material.COMPASS, "\u00a7bStructures discovered", structures), null);

        set(h, 33, icon(Material.LODESTONE, "\u00a7dWaystones: \u00a7f" + d.waystones.size(),
                List.of("\u00a77Click to fast travel.")), pl -> later(() -> openWaystones(pl)));
        set(h, 40, icon(Material.ENCHANTED_BOOK, "\u00a7dLore pages: \u00a7f" + d.lore.size() + "/" + Relics.LORE.length,
                List.of("\u00a77Click to read.")), pl -> later(() -> openLore(pl)));
        open(p, h);
    }

    // ------------------------------------------------------------------ waystones

    public int waystoneFee(Player p, double distance) {
        int rank = plugin.quests().rank(plugin.quests().data(p));
        double discount = 1.0 - Math.min(40, rank * 10) / 100.0;
        return Math.max(5, (int) Math.round((10 + distance / 200.0) * discount));
    }

    public void openWaystones(Player p) {
        PlayerStore.Data d = plugin.quests().data(p);
        World w = Bukkit.getWorld(plugin.underworldName());
        Holder h = make("\u00a75Waystones", 6);
        if (w == null || !p.getWorld().equals(w)) {
            set(h, 22, icon(Material.BARRIER, "\u00a7cYou must be in the Underworld", List.of()), null);
            open(p, h);
            return;
        }
        List<int[]> list = new ArrayList<>();
        for (String s : d.waystones) {
            String[] a = s.split(",");
            try {
                list.add(new int[] {Integer.parseInt(a[0]), Integer.parseInt(a[1]), Integer.parseInt(a[2])});
            } catch (RuntimeException ignored) {
                // skip broken entry
            }
        }
        Location here = p.getLocation();
        list.sort(Comparator.comparingDouble(a -> Math.hypot(a[0] - here.getX(), a[2] - here.getZ())));
        for (int i = 0; i < list.size() && i < 45; i++) {
            int[] a = list.get(i);
            double dist = Math.hypot(a[0] - here.getX(), a[2] - here.getZ());
            int fee = waystoneFee(p, dist);
            set(h, i, icon(Material.LODESTONE, "\u00a7dWaystone " + a[0] + ", " + a[2],
                    List.of("\u00a77Distance: \u00a7f" + Math.round(dist) + " blocks", "\u00a76Fee: \u00a7f" + fee + " coins", "\u00a7aClick to travel")),
                    pl -> {
                        if (!engineTake(pl, fee)) return;
                        pl.closeInventory();
                        pl.teleportAsync(new Location(w, a[0] + 0.5, a[1] + 1, a[2] + 0.5));
                    });
        }
        set(h, 53, icon(Material.ARROW, "\u00a77Back", List.of()), pl -> later(() -> openJournal(pl)));
        open(p, h);
    }

    private boolean engineTake(Player p, int fee) {
        if (plugin.quests().takeCoins(p, fee)) return true;
        p.sendMessage(Txt.c("\u00a7cNot enough coins (" + fee + " needed)."));
        return false;
    }

    // ------------------------------------------------------------------ lore

    public void openLore(Player p) {
        PlayerStore.Data d = plugin.quests().data(p);
        Holder h = make("\u00a75Lore of Vael", 3);
        for (int i = 0; i < Relics.LORE.length && i < 27; i++) {
            final int idx = i;
            if (d.lore.contains(i)) {
                set(h, i, icon(Material.PAPER, "\u00a7d" + Relics.LORE[i][0], Txt.wrap(Relics.LORE[i][1], 34, "\u00a77")),
                        pl -> {
                            pl.sendMessage(Txt.c("\u00a7d" + Relics.LORE[idx][0]));
                            for (String line : Txt.wrap(Relics.LORE[idx][1], 48, "\u00a77")) pl.sendMessage(Txt.c(line));
                        });
            } else {
                set(h, i, icon(Material.GRAY_DYE, "\u00a78???", List.of("\u00a78A page not yet found.")), null);
            }
        }
        open(p, h);
    }
}
