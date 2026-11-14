package dev.underworld;

import java.util.List;

public record Quest(int id, String title, Role giver, int[] requires, String intro, String done,
                    List<Obj> objectives, int coins, int xp, String relic) {

    public enum Type { KILL_KNIGHT, KILL_GUARD, KILL_WARDEN, DELIVER, DISCOVER, BIOME, CHESTS, WAYSTONES, TALK }

    /** arg: item id (DELIVER), "TYPE" or "TYPE:ZONE" (DISCOVER), zone name (BIOME), role name (TALK). */
    public record Obj(Type type, String arg, int amount, String label) { }
}
