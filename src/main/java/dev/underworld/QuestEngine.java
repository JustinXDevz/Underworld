package dev.underworld;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** Coins, ranks and quest progress. */
public final class QuestEngine {

    public static final int[] RANK_XP = {0, 150, 450, 1000, 2000};
    public static final String[] RANK_NAME = {"Wanderer", "Delver", "Relic Hunter", "Warden-Slayer", "Voidwalker"};

    private final UnderworldPlugin plugin;
    private final PlayerStore store;

    public QuestEngine(UnderworldPlugin plugin, PlayerStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    public PlayerStore.Data data(Player p) {
        return store.get(p.getUniqueId());
    }

    public PlayerStore store() {
        return store;
    }

    // ------------------------------------------------------------------ coins and rank

    public int rank(PlayerStore.Data d) {
        int r = 0;
        for (int i = 0; i < RANK_XP.length; i++) if (d.xp >= RANK_XP[i]) r = i;
        return r;
    }

    public void addCoins(Player p, long base) {
        PlayerStore.Data d = data(p);
        long amount = Math.round(base * (1.0 + 0.05 * rank(d)));
        d.coins += amount;
        p.sendActionBar(Txt.c("\u00a76+" + amount + " Underworld coins"));
    }

    public boolean takeCoins(Player p, long amount) {
        PlayerStore.Data d = data(p);
        if (d.coins < amount) return false;
        d.coins -= amount;
        return true;
    }

    public void addXp(Player p, int xp) {
        PlayerStore.Data d = data(p);
        int before = rank(d);
        d.xp += xp;
        int after = rank(d);
        if (after > before) {
            p.sendMessage(Txt.c("\u00a7d* Rank up! You are now a \u00a7l" + RANK_NAME[after] + "\u00a7r\u00a7d."));
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        }
    }

    // ------------------------------------------------------------------ objective values

    public int countDiscovered(PlayerStore.Data d, String arg) {
        String type = arg;
        String zone = null;
        int colon = arg.indexOf(':');
        if (colon >= 0) {
            type = arg.substring(0, colon);
            zone = arg.substring(colon + 1);
        }
        int n = 0;
        for (String key : d.discovered) {
            String[] parts = key.split("\\|");
            if (parts.length < 2 || !parts[0].equals(type)) continue;
            if (zone != null && !parts[1].equals(zone)) continue;
            n++;
        }
        return n;
    }

    public int value(Player p, PlayerStore.Data d, int[] prog, Quest.Obj o, int idx) {
        return switch (o.type()) {
            case DELIVER -> plugin.relics().count(p, o.arg());
            case DISCOVER -> countDiscovered(d, o.arg());
            case BIOME -> d.zones.contains(o.arg()) ? 1 : 0;
            case WAYSTONES -> d.waystones.size();
            default -> prog[idx];
        };
    }

    public boolean isComplete(Player p, PlayerStore.Data d, Quest q) {
        int[] prog = d.active.get(q.id());
        if (prog == null) return false;
        List<Quest.Obj> objs = q.objectives();
        for (int i = 0; i < objs.size(); i++) {
            if (value(p, d, prog, objs.get(i), i) < objs.get(i).amount()) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ accept / turn in

    public boolean requirementsMet(PlayerStore.Data d, Quest q) {
        for (int r : q.requires()) if (!d.completed.contains(r)) return false;
        return true;
    }

    public void accept(Player p, Quest q) {
        PlayerStore.Data d = data(p);
        if (d.completed.contains(q.id()) || d.active.containsKey(q.id()) || !requirementsMet(d, q)) return;
        d.active.put(q.id(), new int[q.objectives().size()]);
        p.sendMessage(Txt.c("\u00a76* Quest accepted: \u00a7e" + q.title()));
        for (String line : Txt.wrap(q.intro(), 48, "\u00a77")) p.sendMessage(Txt.c(line));
        for (Quest.Obj o : q.objectives()) p.sendMessage(Txt.c("\u00a78 - \u00a7f" + o.label()));
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
        recheck(p);
    }

    public void turnIn(Player p, Quest q) {
        PlayerStore.Data d = data(p);
        if (!d.active.containsKey(q.id())) return;
        if (!isComplete(p, d, q)) {
            p.sendMessage(Txt.c("\u00a7cYou are not finished yet:"));
            int[] prog = d.active.get(q.id());
            List<Quest.Obj> objs = q.objectives();
            for (int i = 0; i < objs.size(); i++) {
                int v = Math.min(value(p, d, prog, objs.get(i), i), objs.get(i).amount());
                p.sendMessage(Txt.c("\u00a78 - \u00a7f" + objs.get(i).label() + " \u00a77(" + v + "/" + objs.get(i).amount() + ")"));
            }
            return;
        }
        for (Quest.Obj o : q.objectives()) {
            if (o.type() == Quest.Type.DELIVER) plugin.relics().take(p, o.arg(), o.amount());
        }
        d.active.remove(q.id());
        d.notified.remove(q.id());
        d.completed.add(q.id());
        addCoins(p, q.coins());
        addXp(p, q.xp());
        if (q.relic() != null) {
            ItemStack relic = plugin.relics().create(q.relic(), 1);
            if (relic != null) {
                plugin.relics().give(p, relic);
                p.sendMessage(Txt.c("\u00a7dYou received: " + relic.getItemMeta().getDisplayName()));
            }
        }
        p.sendMessage(Txt.c("\u00a7a* Quest complete: \u00a7f" + q.title()));
        for (String line : Txt.wrap(q.done(), 48, "\u00a77")) p.sendMessage(Txt.c(line));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
        plugin.getServer().getScheduler().runTask(plugin, () -> store.save(p.getUniqueId()));
    }

    /** Tells the player when a quest's objectives become complete. */
    public void recheck(Player p) {
        PlayerStore.Data d = data(p);
        for (Integer id : new java.util.ArrayList<>(d.active.keySet())) {
            Quest q = Quests.byId(id);
            if (q == null || d.notified.contains(id)) continue;
            if (isComplete(p, d, q)) {
                d.notified.add(id);
                p.sendMessage(Txt.c("\u00a7a* Quest ready: \u00a7f" + q.title() + " \u00a77\u2014 return to \u00a76" + q.giver().display));
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1.4f);
            }
        }
    }

    // ------------------------------------------------------------------ events

    private void bump(Player p, Quest.Type type) {
        PlayerStore.Data d = data(p);
        for (java.util.Map.Entry<Integer, int[]> e : d.active.entrySet()) {
            Quest q = Quests.byId(e.getKey());
            if (q == null) continue;
            List<Quest.Obj> objs = q.objectives();
            for (int i = 0; i < objs.size(); i++) {
                if (objs.get(i).type() == type && e.getValue()[i] < objs.get(i).amount()) e.getValue()[i]++;
            }
        }
        recheck(p);
    }

    /** kind: 1 outpost knight, 2 guard, 3 warden. */
    public void onKill(Player killer, int kind) {
        if (kind == 1) {
            addCoins(killer, 60);
            addXp(killer, 10);
            bump(killer, Quest.Type.KILL_KNIGHT);
        } else if (kind == 2) {
            addCoins(killer, 25);
            addXp(killer, 3);
            bump(killer, Quest.Type.KILL_GUARD);
        } else if (kind == 3) {
            addCoins(killer, 200);
            addXp(killer, 50);
            bump(killer, Quest.Type.KILL_WARDEN);
        }
    }

    public void onChest(Player p) {
        PlayerStore.Data d = data(p);
        d.chests++;
        addCoins(p, 5 + ThreadLocalRandom.current().nextInt(16));
        bump(p, Quest.Type.CHESTS);
    }

    public void onTalk(Player p, Role role) {
        PlayerStore.Data d = data(p);
        for (java.util.Map.Entry<Integer, int[]> e : d.active.entrySet()) {
            Quest q = Quests.byId(e.getKey());
            if (q == null) continue;
            List<Quest.Obj> objs = q.objectives();
            for (int i = 0; i < objs.size(); i++) {
                if (objs.get(i).type() == Quest.Type.TALK && objs.get(i).arg().equals(role.name())) e.getValue()[i] = 1;
            }
        }
        recheck(p);
    }
}
