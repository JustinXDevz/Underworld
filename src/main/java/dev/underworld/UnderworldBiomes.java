package dev.underworld;

import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.WorldInfo;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Hub uses the configured fog biome (teal by default). The zones: Crystal Wasteland teal,
 * The Crack red, Snow Soul purple-grey, Valley of Dark teal.
 */
public final class UnderworldBiomes extends BiomeProvider {

    private static volatile Biome fog = Biome.SOUL_SAND_VALLEY;

    public static void configure(String name) {
        String n = name == null ? "" : name.toLowerCase();
        fog = switch (n) {
            case "warped_forest" -> Biome.WARPED_FOREST;
            case "nether_wastes" -> Biome.NETHER_WASTES;
            case "crimson_forest" -> Biome.CRIMSON_FOREST;
            case "basalt_deltas" -> Biome.BASALT_DELTAS;
            default -> Biome.SOUL_SAND_VALLEY;
        };
    }

    @Override
    public Biome getBiome(WorldInfo info, int x, int y, int z) {
        Biome b = Terrain.get(info.getSeed()).biome(x, z);
        return b != null ? b : fog;
    }

    @Override
    public List<Biome> getBiomes(WorldInfo info) {
        Set<Biome> set = new LinkedHashSet<>();
        set.add(fog);
        set.add(Biome.SOUL_SAND_VALLEY);
        set.add(Biome.CRIMSON_FOREST);
        set.add(Biome.END_HIGHLANDS);
        return new ArrayList<>(set);
    }
}
