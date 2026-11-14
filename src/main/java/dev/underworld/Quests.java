package dev.underworld;

import java.util.ArrayList;
import java.util.List;

/** The 20 quests of Vael, grouped into seven story lines. */
public final class Quests {

    private static final List<Quest> ALL = new ArrayList<>();

    private Quests() { }

    private static Quest.Obj o(Quest.Type t, String arg, int amount, String label) {
        return new Quest.Obj(t, arg, amount, label);
    }

    private static void add(int id, String title, Role giver, int[] req, String intro, String done,
                            int coins, int xp, String relic, Quest.Obj... objs) {
        ALL.add(new Quest(id, title, giver, req, intro, done, List.of(objs), coins, xp, relic));
    }

    static {
        // ---- Brother Ash (the hub chapels): the fall of Vael
        add(1, "Whispers of the Fall", Role.HERMIT, new int[] {},
                "Travelers fall into Vael and are forgotten. Prove you will not be. Open three chests and see what the ruins still guard.",
                "You still draw breath. Good. The ruins remember the careful.",
                50, 20, null, o(Quest.Type.CHESTS, "", 3, "Open 3 chests"));
        add(2, "Blood on the Ash", Role.HERMIT, new int[] {1},
                "The old knights never stopped their watch, and they will not stop for you. Cut down three Underworld guards.",
                "Take this compass. It hunts what you have not yet seen.",
                120, 40, "void_compass", o(Quest.Type.KILL_GUARD, "", 3, "Defeat 3 Underworld Guards"));
        add(3, "Pages of the Fallen", Role.HERMIT, new int[] {2},
                "Vael's story lies scattered in its chests. Bring me three lore pages and I will tell you how the Heart broke.",
                "The Heart of Vael was a crystal that kept the kingdom whole. When it cracked, the land cracked with it.",
                150, 60, null, o(Quest.Type.DELIVER, "lore_page", 3, "Deliver 3 Lore Pages"));

        // ---- Lyra (crystal shrines): the shards
        add(4, "A Light in the Lime", Role.SEEKER, new int[] {},
                "East of the hub the crystals still grow wild. Find two shrines where they gather.",
                "Then you have felt it too. The song.",
                80, 30, null, o(Quest.Type.DISCOVER, "RUIN:CRYSTAL", 2, "Discover 2 crystal shrines"));
        add(5, "Shards for the Citadel", Role.SEEKER, new int[] {4},
                "Shrine chests hold crystal shards. Bring me five. The citadel's heart is starving.",
                "Warm, aren't they? Keep this charm. It will quicken your step.",
                200, 60, "shardheart", o(Quest.Type.DELIVER, "crystal_shard", 5, "Deliver 5 Crystal Shards"));
        add(6, "The Hollow Heart", Role.SEEKER, new int[] {5},
                "Find the great crystal citadel in the east and speak with its Archivist. He knows what the shards are for.",
                "So the citadel still stands. Perhaps there is hope for Vael yet.",
                300, 90, null, o(Quest.Type.DISCOVER, "MEGA_CASTLE", 1, "Find a crystal citadel"),
                o(Quest.Type.TALK, "ARCHIVIST", 1, "Speak to the Citadel Archivist"));

        // ---- Orin (crack pavilions): the maps
        add(7, "Edge of the World", Role.CARTOGRAPHER, new int[] {},
                "South of the hub the world is breaking apart. Walk to the Crack and tell me how wide the gaps are.",
                "Wider every day. Thank you. My maps are always out of date.",
                80, 30, null, o(Quest.Type.BIOME, "CRACK", 1, "Reach The Crack"));
        add(8, "Four Torn Maps", Role.CARTOGRAPHER, new int[] {7},
                "My maps were torn and scattered through the Crack's chests. Bring me four fragments.",
                "Ah, the old roads! Take this feather. It will carry you over a gap or two.",
                220, 70, "void_feather", o(Quest.Type.DELIVER, "map_fragment", 4, "Deliver 4 Map Fragments"));
        add(9, "The Lighthouse Spikes", Role.CARTOGRAPHER, new int[] {8},
                "Old waystones stand at the foot of the tall obelisks. Wake two of them so travelers can find their way.",
                "Now the roads are lit again, if only a little.",
                250, 80, null, o(Quest.Type.WAYSTONES, "", 2, "Activate 2 waystones"));

        // ---- Skarn (snow cabins): the hunt
        add(10, "Frostbitten", Role.HUNTER, new int[] {},
                "West of the hub the snow never melts. Reach the Snow Soul and come back with your toes.",
                "You have toes? Impressive. Now we can talk.",
                80, 30, null, o(Quest.Type.BIOME, "SNOW", 1, "Reach the Snow Soul"));
        add(11, "The Elite Quarry", Role.HUNTER, new int[] {10},
                "The outpost knights are the best of Vael's guard. Bring two of them down. I will pay for the proof.",
                "Two knights. I have not seen that since the queen rode out. Wear this heart; it burns bright.",
                260, 80, "emberheart", o(Quest.Type.KILL_KNIGHT, "", 2, "Defeat 2 Outpost Knights"));
        add(12, "A Mother's Locket", Role.HUNTER, new int[] {11},
                "A woman froze out here, clutching a locket. Three were lost in the cabins' chests. Find them for me.",
                "Now she can rest. You have my thanks, stranger.",
                240, 70, null, o(Quest.Type.DELIVER, "frost_locket", 3, "Deliver 3 Frost Lockets"));

        // ---- Veyla (stone circles): the stones
        add(13, "Stones That Remember", Role.SEER, new int[] {},
                "The standing stones are not tombs. They are listeners. Find two circles in the snow.",
                "Yes. They heard you, too.",
                120, 40, null, o(Quest.Type.DISCOVER, "RUIN:SNOW", 2, "Discover 2 stone circles"));
        add(14, "Voice of the Frost", Role.SEER, new int[] {13},
                "Wake four waystones across the Underworld and bring me two lore pages. The stones will speak.",
                "The stones say the queen is not dead. Not yet.",
                280, 90, null, o(Quest.Type.WAYSTONES, "", 4, "Activate 4 waystones"),
                o(Quest.Type.DELIVER, "lore_page", 2, "Deliver 2 Lore Pages"));

        // ---- Mald (gothic ruins): the dead
        add(15, "Into the Canyon", Role.GRAVEKEEPER, new int[] {},
                "North of the hub the canyon walls rise to the sky. Walk its floor and do not look up.",
                "You walked it. Few do. Few return.",
                100, 40, null, o(Quest.Type.BIOME, "DARK", 1, "Reach the Valley of Dark"));
        add(16, "Tokens of the Dead", Role.GRAVEKEEPER, new int[] {15},
                "Every grave in the valley held a token. Four have not been collected. Bring them to me.",
                "Now they can be buried properly. Take this lantern. The dark will not trouble you.",
                300, 90, "lantern", o(Quest.Type.DELIVER, "grave_token", 4, "Deliver 4 Grave Tokens"));
        add(17, "What Wakes Above", Role.GRAVEKEEPER, new int[] {16},
                "Something sleeps in the sculk on the plateau and wakes at the smallest sound. Put it down.",
                "A warden, slain. No one has done that since the Fall.",
                600, 200, null, o(Quest.Type.KILL_WARDEN, "", 1, "Defeat a Warden"));

        // ---- Herald Maren (the kingdom): the queen
        add(18, "The Queen's Heralds", Role.HERALD, new int[] {2},
                "The queen's guard still patrols the roads. Thin their ranks. Eight of them.",
                "The roads are quieter. Thank you.",
                400, 100, null, o(Quest.Type.KILL_GUARD, "", 8, "Defeat 8 Underworld Guards"));
        add(19, "Seal of the Exile", Role.HERALD, new int[] {18},
                "Royal seals lie in the chests of the great keeps. Bring me two. I will prove who you are.",
                "The seals are genuine. Then the queen's exile is not forgotten.",
                500, 150, null, o(Quest.Type.DELIVER, "royal_seal", 2, "Deliver 2 Royal Seals"));
        add(20, "The Last Bell", Role.HERALD, new int[] {19, 17},
                "The cathedral bell has not rung since the Fall. Break its guardians and bring one more seal. Then Vael may rest.",
                "The bell rings. For the first time in an age, Vael is quiet. Take the Sigil. You have earned it.",
                1000, 400, "sigil",
                o(Quest.Type.KILL_WARDEN, "", 2, "Defeat 2 Wardens"),
                o(Quest.Type.KILL_KNIGHT, "", 4, "Defeat 4 Outpost Knights"),
                o(Quest.Type.DELIVER, "royal_seal", 1, "Deliver 1 Royal Seal"));
    }

    public static List<Quest> all() {
        return ALL;
    }

    public static Quest byId(int id) {
        for (Quest q : ALL) if (q.id() == id) return q;
        return null;
    }

    public static List<Quest> byGiver(Role role) {
        List<Quest> out = new ArrayList<>();
        for (Quest q : ALL) if (q.giver() == role) out.add(q);
        return out;
    }
}
