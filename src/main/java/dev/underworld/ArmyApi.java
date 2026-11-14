package dev.underworld;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.List;

/** What commands may do with the Legion armies (implemented only when Citizens is installed). */
public interface ArmyApi {

    /** Spawns an army in front of the player and returns a message for them. */
    String spawn(Player at, int size);

    List<String> list();

    /** Removes every army; returns how many were removed. */
    int clear();

    /** Description of the nearest army to a location, or null. */
    String nearest(Location from);
}
