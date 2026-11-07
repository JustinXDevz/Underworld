package dev.underworld;

import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.WorldInfo;

import java.util.List;

/**
 * One biome everywhere so the fog colour is the same across the whole Underworld.
 * Soul Sand Valley gives the teal-green fog.
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
        return fog;
    }

    @Override
    public List<Biome> getBiomes(WorldInfo info) {
        return List.of(fog);
    }
}
