package dev.underworld;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

import java.util.List;
import java.util.Random;

public final class UnderworldGenerator extends ChunkGenerator {

    /** Block data is created lazily, once the server is running. */
    private static final class Data {
        static final BlockData LEAVES = Bukkit.createBlockData("minecraft:spruce_leaves[persistent=true]");
        static final BlockData SNOW = Bukkit.createBlockData("minecraft:snow[layers=1]");
        static final BlockData SHRIEKER = Bukkit.createBlockData("minecraft:sculk_shrieker[can_summon=true]");
        static final BlockData SENSOR = Bukkit.createBlockData("minecraft:sculk_sensor");
    }

    private static Material strata(int y) {
        int k = Math.floorMod(y, 3);
        return k == 0 ? Material.SOUL_SAND : (k == 1 ? Material.SOUL_SOIL : Material.BLACKSTONE);
    }

    private static Material wallBrick(int x, int y, int z) {
        int k = Math.floorMod(x * 7 + y * 13 + z * 5, 11);
        if (k < 6) return Material.DEEPSLATE_BRICKS;
        if (k < 9) return Material.CRACKED_DEEPSLATE_BRICKS;
        if (k == 9) return Material.DEEPSLATE_TILES;
        return Material.BLACKSTONE;
    }

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

                // The Crack: nothing here at all
                if (s == Terrain.VOID) continue;

                // Powder snow pit: a thin cap with nothing underneath
                if (c.hollow()) {
                    cd.setRegion(lx, s - 2, lz, lx + 1, s + 1, lz + 1, Material.POWDER_SNOW);
                    continue;
                }

                // Bedrock floor and deepslate
                cd.setBlock(lx, minY, lz, Material.BEDROCK);
                for (int y = minY + 1; y <= minY + 3; y++) {
                    cd.setBlock(lx, y, lz, random.nextInt(y - minY + 1) == 0 ? Material.BEDROCK : Material.DEEPSLATE);
                }
                cd.setRegion(lx, minY + 4, lz, lx + 1, 0, lz + 1, Material.DEEPSLATE);

                if (c.filler() == Material.DEEPSLATE_BRICKS) {
                    // Valley of Dark: towering brick canyon wall
                    cd.setRegion(lx, 0, lz, lx + 1, Terrain.VALLEY_FLOOR - 6, lz + 1, Material.BLACKSTONE);
                    for (int y = Terrain.VALLEY_FLOOR - 6; y < s; y++) {
                        cd.setBlock(lx, y, lz, wallBrick(x, y, z));
                    }
                } else if (c.filler() == Material.SOUL_SOIL) {
                    // Terraced soul soil / soul sand with striped cliff sides
                    cd.setRegion(lx, 0, lz, lx + 1, s - 8, lz + 1, Material.BLACKSTONE);
                    for (int y = s - 8; y < s; y++) cd.setBlock(lx, y, lz, strata(y));
                } else {
                    cd.setRegion(lx, 0, lz, lx + 1, s - 3, lz + 1, Material.BLACKSTONE);
                    cd.setRegion(lx, s - 3, lz, lx + 1, s, lz + 1, c.filler());
                }
                cd.setBlock(lx, s, lz, c.top());

                if (s < Terrain.SEA) {
                    cd.setRegion(lx, s + 1, lz, lx + 1, Terrain.SEA + 1, lz + 1, Material.LAVA);
                }

                // Hub spires
                int top = t.spireTop(x, z);
                if (top != Terrain.ABSENT && top > s) {
                    top = Math.min(top, maxY - 1);
                    for (int y = s + 1; y <= top; y++) {
                        boolean dark = ((x * 31 + y * 17 + z * 13) & 7) == 0;
                        cd.setBlock(lx, y, lz, dark ? Material.BLACKSTONE : Material.BASALT);
                    }
                }

                // Hub bridges
                if (c.bridgeY() != Terrain.ABSENT) {
                    int by = c.bridgeY();
                    boolean cracked = ((x * 7 + z * 5) & 3) == 0;
                    cd.setBlock(lx, by, lz, cracked ? Material.CRACKED_DEEPSLATE_BRICKS : Material.DEEPSLATE_BRICKS);
                    cd.setBlock(lx, by - 1, lz, Material.DEEPSLATE_BRICKS);
                }

                // Leaning lime crystal blades
                Terrain.CrystalColumn cc = t.crystalColumn(x, z);
                if (cc != null) {
                    for (int i = 0; i < cc.hit().length; i++) {
                        int y = cc.base() + i;
                        if (cc.hit()[i] && y > s && y < maxY) cd.setBlock(lx, y, lz, Material.LIME_STAINED_GLASS);
                    }
                }

                // Giant snowy spruces
                Terrain.Tree tr = t.treeAt(x, z);
                if (tr != null) {
                    int dx = x - tr.x();
                    int dz = z - tr.z();
                    int cheb = Math.max(Math.abs(dx), Math.abs(dz));
                    int treeTop = tr.baseY() + tr.height();
                    if (cheb <= tr.trunk()) {
                        cd.setRegion(lx, s + 1, lz, lx + 1, Math.min(treeTop, maxY - 1) + 1, lz + 1, Material.SPRUCE_LOG);
                    }
                    double d = Math.sqrt((double) dx * dx + (double) dz * dz);
                    int leafStart = tr.baseY() + (int) (tr.height() * 0.3);
                    int highest = -1;
                    for (int y = leafStart; y <= treeTop + 4 && y < maxY - 2; y++) {
                        int k = treeTop + 4 - y;
                        double rr = Math.min(tr.radius(), 1.5 + k * tr.radius() / (tr.height() * 0.62));
                        if (k % 6 >= 4) rr -= 2.0;
                        if (d <= rr && (cheb > tr.trunk() || y > treeTop)) {
                            cd.setBlock(lx, y, lz, Data.LEAVES);
                            highest = y;
                        }
                    }
                    if (highest >= 0 && ((x * 31 + z * 17) & 3) != 0) {
                        cd.setBlock(lx, highest + 1, lz, Data.SNOW);
                    }
                }

                // Sculk plateau: shriekers summon wardens, sensors listen
                if (c.top() == Material.SCULK && s == Terrain.PLATEAU) {
                    int deco = t.deco(x, z);
                    if (deco == 1) cd.setBlock(lx, s + 1, lz, Data.SHRIEKER);
                    else if (deco == 2) cd.setBlock(lx, s + 1, lz, Data.SENSOR);
                }

                // Mega castle / kingdom
                Terrain.Feature big = t.big(x, z);
                if (big != null) BigStructures.column(cd, lx, lz, x, z, big);
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
        for (int r = 0; r < 400; r += 16) {
            for (int a = 0; a < 8; a++) {
                double ang = a * Math.PI / 4.0;
                int x = (int) Math.round(Math.cos(ang) * r);
                int z = (int) Math.round(Math.sin(ang) * r);
                if (t.isLand(x, z) && t.zone(x, z) == Terrain.Zone.BASE && t.valley(x, z) < 0.02) {
                    return new Location(world, x + 0.5, t.topY(x, z) + 1, z + 0.5);
                }
            }
        }
        return new Location(world, 0.5, 90, 0.5);
    }
}
