package dev.underworld;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;

/** Builds ruined fortresses, watch towers and mazes at the spots chosen by {@link Terrain}. */
public final class StructurePopulator extends BlockPopulator {

    @Override
    public void populate(WorldInfo info, Random unused, int chunkX, int chunkZ, LimitedRegion region) {
        Terrain t = Terrain.get(info.getSeed());
        Terrain.Feature f = t.feature(Math.floorDiv(chunkX, 4), Math.floorDiv(chunkZ, 4));
        if (f.type() == Terrain.FeatureType.NONE || f.type() == Terrain.FeatureType.SPIRE) return;
        if (Math.floorDiv(f.x(), 16) != chunkX || Math.floorDiv(f.z(), 16) != chunkZ) return;

        Random r = new Random(info.getSeed() * 31L + f.x() * 7919L + f.z() * 104729L);
        Site s = new Site(region, t, r, f);
        switch (f.type()) {
            case FORTRESS -> fortress(s);
            case TOWER -> tower(s);
            case MAZE -> maze(s);
            default -> { }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static final class Site {
        final LimitedRegion region;
        final Terrain t;
        final Random r;
        final int cx;
        final int cz;
        final int floor;

        Site(LimitedRegion region, Terrain t, Random r, Terrain.Feature f) {
            this.region = region;
            this.t = t;
            this.r = r;
            this.cx = f.x();
            this.cz = f.z();
            this.floor = f.baseY();
        }

        void set(int x, int y, int z, Material m) {
            if (region.isInRegion(x, y, z)) region.setType(x, y, z, m);
        }

        void data(int x, int y, int z, String blockData) {
            if (region.isInRegion(x, y, z)) region.setBlockData(x, y, z, Bukkit.createBlockData(blockData));
        }

        Material brick() {
            int k = r.nextInt(10);
            if (k < 6) return Material.DEEPSLATE_BRICKS;
            if (k < 9) return Material.CRACKED_DEEPSLATE_BRICKS;
            return Material.BLACKSTONE;
        }

        /** Levels the ground: foundation below the floor, clears terrain above it, lays the floor. */
        void prepare(int half) {
            for (int x = cx - half; x <= cx + half; x++) {
                for (int z = cz - half; z <= cz + half; z++) {
                    int s = t.column(x, z).surface();
                    for (int y = s + 1; y < floor; y++) set(x, y, z, Material.DEEPSLATE_BRICKS);
                    for (int y = floor + 1; y <= s; y++) set(x, y, z, Material.AIR);
                    set(x, floor, z, r.nextInt(6) == 0 ? Material.COBBLED_DEEPSLATE : Material.DEEPSLATE_TILES);
                }
            }
        }
    }

    // ------------------------------------------------------------------ fortress

    private void fortress(Site s) {
        int cx = s.cx;
        int cz = s.cz;
        int f = s.floor;
        s.prepare(15);

        // Outer wall with a gate on the south side
        for (int dx = -15; dx <= 15; dx++) {
            for (int dz = -15; dz <= 15; dz++) {
                if (Math.abs(dx) < 14 && Math.abs(dz) < 14) continue;
                boolean gate = dz >= 14 && Math.abs(dx) <= 2;
                for (int h = 1; h <= 9; h++) {
                    if (gate && h <= 5) continue;
                    if (h > 4 && s.r.nextDouble() < (h - 4) * 0.07) continue;
                    if (h == 9 && ((dx + dz) & 1) == 0) continue;
                    s.set(cx + dx, f + h, cz + dz, s.brick());
                }
            }
        }

        // Corner towers
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                int tx = cx + sx * 13;
                int tz = cz + sz * 13;
                for (int ox = -2; ox <= 2; ox++) {
                    for (int oz = -2; oz <= 2; oz++) {
                        boolean shell = Math.abs(ox) == 2 || Math.abs(oz) == 2;
                        for (int h = 1; h <= 18; h++) {
                            boolean door = ox == -sx * 2 && oz == 0 && h <= 2;
                            if (door) continue;
                            if (h > 12 && s.r.nextDouble() < (h - 12) * 0.12) continue;
                            if (shell || h == 9) s.set(tx + ox, f + h, tz + oz, s.brick());
                        }
                    }
                }
            }
        }

        // Central keep
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                boolean shell = Math.abs(dx) == 5 || Math.abs(dz) == 5;
                for (int h = 1; h <= 13; h++) {
                    boolean door = dz == 5 && Math.abs(dx) <= 1 && h <= 3;
                    if (door) continue;
                    if (shell) {
                        if (h > 9 && s.r.nextDouble() < (h - 9) * 0.1) continue;
                        s.set(cx + dx, f + h, cz + dz, s.brick());
                    } else if (h == 7 && !(Math.abs(dx) <= 1 && Math.abs(dz) <= 1)) {
                        s.set(cx + dx, f + h, cz + dz, s.brick());
                    } else if (h == 13 && s.r.nextDouble() > 0.35) {
                        s.set(cx + dx, f + h, cz + dz, Material.DEEPSLATE_TILES);
                    }
                }
            }
        }
        s.data(cx, f + 1, cz, "minecraft:chest[facing=south]");
        s.data(cx - 3, f + 1, cz - 3, "minecraft:chest[facing=south]");
        s.data(cx + 3, f + 8, cz - 3, "minecraft:chest[facing=south]");

        // Broken pillars in the courtyard
        for (int i = 0; i < 8; i++) {
            int dx = s.r.nextInt(21) - 10;
            int dz = s.r.nextInt(21) - 10;
            if (Math.abs(dx) <= 6 && Math.abs(dz) <= 6) continue;
            int height = 1 + s.r.nextInt(4);
            for (int h = 1; h <= height; h++) s.set(cx + dx, f + h, cz + dz, s.brick());
        }
    }

    // ------------------------------------------------------------------ tower

    private void tower(Site s) {
        int cx = s.cx;
        int cz = s.cz;
        int f = s.floor;
        s.prepare(4);

        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                int ring = Math.max(Math.abs(dx), Math.abs(dz));
                for (int h = 1; h <= 34; h++) {
                    if (ring == 4) {
                        boolean door = dz == 4 && dx == 0 && h <= 2;
                        boolean slit = h % 5 == 3 && ((dz == 0 && Math.abs(dx) == 4) || (dx == 0 && dz == 4));
                        if (door || slit) continue;
                        if (h == 34 && ((dx + dz) & 1) == 0) continue;
                        if (h > 20 && s.r.nextDouble() < (h - 20) * 0.04) continue;
                        s.set(cx + dx, f + h, cz + dz, s.brick());
                    } else if ((h == 10 || h == 20 || h == 30) && !(dx == 0 && dz == -3)) {
                        s.set(cx + dx, f + h, cz + dz, s.brick());
                    }
                }
            }
        }
        // Ladder up the inside of the north wall
        for (int h = 1; h <= 30; h++) {
            s.data(cx, f + h, cz - 3, "minecraft:ladder[facing=south]");
        }
        s.data(cx + 2, f + 11, cz + 2, "minecraft:chest[facing=north]");
        s.data(cx - 2, f + 21, cz + 2, "minecraft:chest[facing=north]");
        s.data(cx + 2, f + 31, cz + 2, "minecraft:chest[facing=north]");
    }

    // ------------------------------------------------------------------ maze

    private void maze(Site s) {
        int x0 = s.cx - 15;
        int z0 = s.cz - 15;
        int f = s.floor;
        s.prepare(15);

        boolean[][] open = new boolean[31][31];
        Deque<int[]> stack = new ArrayDeque<>();
        open[1][1] = true;
        stack.push(new int[] {1, 1});
        int[][] dirs = {{2, 0}, {-2, 0}, {0, 2}, {0, -2}};
        while (!stack.isEmpty()) {
            int[] cur = stack.peek();
            List<int[]> options = new ArrayList<>();
            for (int[] d : dirs) {
                int nx = cur[0] + d[0];
                int nz = cur[1] + d[1];
                if (nx > 0 && nx < 30 && nz > 0 && nz < 30 && !open[nx][nz]) options.add(d);
            }
            if (options.isEmpty()) {
                stack.pop();
                continue;
            }
            int[] d = options.get(s.r.nextInt(options.size()));
            open[cur[0] + d[0] / 2][cur[1] + d[1] / 2] = true;
            open[cur[0] + d[0]][cur[1] + d[1]] = true;
            stack.push(new int[] {cur[0] + d[0], cur[1] + d[1]});
        }

        // Entrances in the middle of every side, and a room in the middle
        open[15][0] = true;
        open[15][30] = true;
        open[0][15] = true;
        open[30][15] = true;
        for (int i = 13; i <= 17; i++) {
            for (int j = 13; j <= 17; j++) open[i][j] = true;
        }

        for (int i = 0; i < 31; i++) {
            for (int j = 0; j < 31; j++) {
                int x = x0 + i;
                int z = z0 + j;
                if (!open[i][j]) {
                    for (int h = 1; h <= 4; h++) s.set(x, f + h, z, s.brick());
                } else if (i % 2 == 1 && j % 2 == 1 && !(i >= 13 && i <= 17 && j >= 13 && j <= 17)
                        && s.r.nextInt(100) < 6) {
                    s.set(x, f, z, Material.MAGMA_BLOCK); // mild floor trap
                }
                s.set(x, f + 5, z, Material.DEEPSLATE_TILES); // roof keeps it dark
            }
        }
        s.data(s.cx - 1, f + 1, s.cz, "minecraft:chest[facing=south]");
        s.data(s.cx + 1, f + 1, s.cz, "minecraft:chest[facing=south]");
    }
}
