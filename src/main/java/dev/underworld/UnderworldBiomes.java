package dev.underworld;

import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.WorldInfo;

import java.util.List;

public final class UnderworldBiomes extends BiomeProvider {

    @Override
    public Biome getBiome(WorldInfo info, int x, int y, int z) {
        return Terrain.get(info.getSeed()).biome(x, z);
    }

    @Override
    public List<Biome> getBiomes(WorldInfo info) {
        return List.of(Biome.NETHER_WASTES, Biome.SOUL_SAND_VALLEY, Biome.BASALT_DELTAS);
    }
}
