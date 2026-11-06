package dev.underworld;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

import java.util.List;
import java.util.Random;

public final class UnderworldGenerator extends ChunkGenerator {

    @Override
    public void generateNoise(WorldInfo info, Random random, int chunkX, int chunkZ, ChunkData cd) {
        Terrain t = Terrain.get(info.getSeed());
        int minY = cd.getMinHeight();
        int maxY = cd.getMaxHeight();

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = chunkX * 16 + lx;
                int z = chunkZ * 16 + lz;
                Terrain.Column c = t.column(x, z);
                int s = c.surface();

                // Bedrock floor, deepslate, then blackstone up to the surface
                cd.setBlock(lx, minY, lz, Material.BEDROCK);
                for (int y = minY + 1; y <= minY + 3; y++) {
                    cd.setBlock(lx, y, lz, random.nextInt(y - minY + 1) == 0 ? Material.BEDROCK : Material.DEEPSLATE);
                }
                cd.setRegion(lx, minY + 4, lz, lx + 1, 0, lz + 1, Material.DEEPSLATE);
                cd.setRegion(lx, 0, lz, lx + 1, s - 3, lz + 1, Material.BLACKSTONE);
                cd.setRegion(lx, s - 3, lz, lx + 1, s, lz + 1, c.filler());
                cd.setBlock(lx, s, lz, c.top());

                if (s < Terrain.SEA) {
                    cd.setRegion(lx, s + 1, lz, lx + 1, Terrain.SEA + 1, lz + 1, Material.LAVA);
                }

                // Spires
                Terrain.Feature f = t.feature(Math.floorDiv(x, Terrain.CELL), Math.floorDiv(z, Terrain.CELL));
                int top = t.spireTop(x, z, f);
                if (top != Terrain.ABSENT && top > s) {
                    top = Math.min(top, maxY - 1);
                    for (int y = s + 1; y <= top; y++) {
                        boolean dark = ((x * 31 + y * 17 + z * 13) & 7) == 0;
                        cd.setBlock(lx, y, lz, dark ? Material.BLACKSTONE : Material.BASALT);
                    }
                }

                // Bridges
                if (c.bridgeY() != Terrain.ABSENT) {
                    int by = c.bridgeY();
                    boolean cracked = ((x * 7 + z * 5) & 3) == 0;
                    Material deck = cracked ? Material.CRACKED_DEEPSLATE_BRICKS : Material.DEEPSLATE_BRICKS;
                    cd.setBlock(lx, by, lz, deck);
                    cd.setBlock(lx, by - 1, lz, Material.DEEPSLATE_BRICKS);
                }
            }
        }
    }

    @Override
    public BiomeProvider getDefaultBiomeProvider(WorldInfo info) {
        return new UnderworldBiomes();
    }

    @Override
    public List<BlockPopulator> getDefaultPopulators(World world) {
        return List.of(new StructurePopulator());
    }

    @Override
    public Location getFixedSpawnLocation(World world, Random random) {
        Terrain t = Terrain.get(world.getSeed());
        for (int r = 0; r < 600; r += 16) {
            for (int a = 0; a < 8; a++) {
                double ang = a * Math.PI / 4.0;
                int x = (int) Math.round(Math.cos(ang) * r);
                int z = (int) Math.round(Math.sin(ang) * r);
                if (t.isLand(x, z) && t.valley(x, z) < 0.02) {
                    return new Location(world, x + 0.5, t.topY(x, z) + 1, z + 0.5);
                }
            }
        }
        return new Location(world, 0.5, 90, 0.5);
    }
}
