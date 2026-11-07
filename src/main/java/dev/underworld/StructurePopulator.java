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

/** Builds fortresses, towers, mazes, ruins, outposts and houses at the sites chosen by {@link Terrain}. */
public final class StructurePopulator extends BlockPopulator {

    private static final String CHEST_S = "minecraft:chest[facing=south]";
    private static final String CHEST_N = "minecraft:chest[facing=north]";

    @Override
    public void populate(WorldInfo info, Random unused, int chunkX, int chunkZ, LimitedRegion region) {
        Terrain t = Terrain.get(info.getSeed());
        Terrain.Feature f = t.feature(Math.floorDiv(chunkX, 4), Math.floorDiv(chunkZ, 4));
        if (!isBuilt(f.type())) return;
        if (Math.floorDiv(f.x(), 16) != chunkX || Math.floorDiv(f.z(), 16) != chunkZ) return;

        Random r = new Random(info.getSeed() * 31L + f.x() * 7919L + f.z() * 104729L);
        Site s = new Site(region, t, r, f);
        switch (f.type()) {
            case FORTRESS -> fortress(s);
            case TOWER -> tower(s);
            case MAZE -> maze(s);
            case RUIN -> ruin(s);
            case OUTPOST -> outpost(s);
            case HOUSE -> house(s);
            default -> { }
        }
    }

    private static boolean isBuilt(Terrain.FeatureType type) {
        return type == Terrain.FeatureType.FORTRESS || type == Terrain.FeatureType.TOWER
                || type == Terrain.FeatureType.MAZE || type == Terrain.FeatureType.RUIN
                || type == Terrain.FeatureType.OUTPOST || type == Terrain.FeatureType.HOUSE;
    }

    // ------------------------------------------------------------------ helpers

    private static final class Site {
        final LimitedRegion region;
        final Terrain t;
        final Random r;
        final Palette pal;
        final int cx;
        final int cz;
        final int floor;

        Site(LimitedRegion region, Terrain t, Random r, Terrain.Feature f) {
            this.region = region;
            this.t = t;
            this.r = r;
            this.pal = Palette.of(f.zone());
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
            return pal.pick(r);
        }

        /** Levels the ground: foundation below the floor, clears terrain above it, lays the floor. */
        void prepare(int half) {
            for (int x = cx - half; x <= cx + half; x++) {
                for (int z = cz - half; z <= cz + half; z++) {
                    int s = t.column(x, z).surface();
                    if (s == Terrain.VOID) s = floor;
                    for (int y = s + 1; y < floor; y++) set(x, y, z, pal.wall());
                    for (int y = floor + 1; y <= s; y++) set(x, y, z, Material.AIR);
                    set(x, floor, z, r.nextInt(6) == 0 ? pal.alt() : pal.floor());
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
                        s.set(cx + dx, f + h, cz + dz, s.pal.roof());
                    }
                }
            }
        }
        s.data(cx, f + 1, cz, CHEST_S);
        s.data(cx - 3, f + 1, cz - 3, CHEST_S);
        s.data(cx + 3, f + 8, cz - 3, CHEST_S);

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
        for (int h = 1; h <= 30; h++) {
            s.data(cx, f + h, cz - 3, "minecraft:ladder[facing=south]");
        }
        s.data(cx + 2, f + 11, cz + 2, CHEST_N);
        s.data(cx - 2, f + 21, cz + 2, CHEST_N);
        s.data(cx + 2, f + 31, cz + 2, CHEST_N);
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
                    s.set(x, f, z, Material.MAGMA_BLOCK);
                }
                s.set(x, f + 5, z, s.pal.roof());
            }
        }
        s.data(s.cx - 1, f + 1, s.cz, CHEST_S);
        s.data(s.cx + 1, f + 1, s.cz, CHEST_S);
    }

    // ------------------------------------------------------------------ ruin

    private void ruin(Site s) {
        int cx = s.cx;
        int cz = s.cz;
        int f = s.floor;
        s.prepare(10);

        for (int i = 0; i < 6; i++) {
            boolean alongX = s.r.nextBoolean();
            int ox = s.r.nextInt(15) - 7;
            int oz = s.r.nextInt(15) - 7;
            int len = 4 + s.r.nextInt(6);
            int height = 2 + s.r.nextInt(5);
            for (int k = 0; k < len; k++) {
                int x = cx + ox + (alongX ? k : 0);
                int z = cz + oz + (alongX ? 0 : k);
                int hgt = Math.max(1, height - s.r.nextInt(3));
                for (int h = 1; h <= hgt; h++) s.set(x, f + h, z, s.brick());
            }
        }
        for (int i = 0; i < 6; i++) {
            int x = cx + s.r.nextInt(17) - 8;
            int z = cz + s.r.nextInt(17) - 8;
            int height = 2 + s.r.nextInt(5);
            for (int h = 1; h <= height; h++) s.set(x, f + h, z, s.pal.log());
        }
        if (s.r.nextInt(100) < 75) {
            s.data(cx + s.r.nextInt(5) - 2, f + 1, cz + s.r.nextInt(5) - 2, CHEST_S);
        }
    }

    // ------------------------------------------------------------------ outpost

    private void outpost(Site s) {
        int cx = s.cx;
        int cz = s.cz;
        int f = s.floor;
        s.prepare(8);

        // Walls, gate in the south, taller corner posts
        for (int dx = -8; dx <= 8; dx++) {
            for (int dz = -8; dz <= 8; dz++) {
                if (Math.abs(dx) != 8 && Math.abs(dz) != 8) continue;
                boolean gate = dz == 8 && Math.abs(dx) <= 1;
                boolean post = Math.abs(dx) >= 7 && Math.abs(dz) >= 7;
                int top = post ? 7 : 4;
                for (int h = 1; h <= top; h++) {
                    if (gate && h <= 3) continue;
                    if (!post && h == 4 && ((dx + dz) & 1) == 0) continue;
                    s.set(cx + dx, f + h, cz + dz, s.brick());
                }
            }
        }

        // Watch tower in the middle
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                boolean shell = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                for (int h = 1; h <= 10; h++) {
                    if (shell) {
                        boolean door = dz == 2 && dx == 0 && h <= 2;
                        if (door) continue;
                        s.set(cx + dx, f + h, cz + dz, s.brick());
                    } else if (h == 5 && !(dx == 0 && dz == -1)) {
                        s.set(cx + dx, f + h, cz + dz, s.brick());
                    }
                }
            }
        }
        for (int h = 1; h <= 9; h++) {
            s.data(cx, f + h, cz - 1, "minecraft:ladder[facing=south]");
        }
        s.data(cx - 5, f + 1, cz - 5, CHEST_S);
        s.data(cx + 5, f + 1, cz - 5, CHEST_S);
        s.data(cx, f + 6, cz + 1, CHEST_S);
    }

    // ------------------------------------------------------------------ house

    private void house(Site s) {
        int cx = s.cx;
        int cz = s.cz;
        int f = s.floor;
        s.prepare(6);

        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                boolean shell = Math.abs(dx) == 5 || Math.abs(dz) == 4;
                boolean corner = Math.abs(dx) == 5 && Math.abs(dz) == 4;
                if (shell) {
                    for (int h = 1; h <= 4; h++) {
                        boolean door = dz == 4 && dx == 0 && h <= 2;
                        if (door) continue;
                        boolean window = h == 3 && ((Math.abs(dx) == 5 && dz == 0) || (dz == -4 && Math.abs(dx) == 2));
                        s.set(cx + dx, f + h, cz + dz, window ? s.pal.glass() : (corner ? s.pal.log() : s.pal.plank()));
                    }
                }
                for (int k = 0; k < 5; k++) {
                    if (dx >= -6 + k && dx <= 6 - k && dz >= -5 + k && dz <= 5 - k) {
                        s.set(cx + dx, f + 5 + k, cz + dz, s.pal.roof());
                    }
                }
            }
        }
        s.data(cx + 3, f + 1, cz - 2, CHEST_S);
    }
}
