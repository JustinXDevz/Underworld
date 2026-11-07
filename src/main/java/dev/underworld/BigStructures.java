package dev.underworld;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.ChunkGenerator.ChunkData;

/**
 * The 200x200 crystal mega castle and the dark kingdom. They are far bigger than a
 * populator can place, so they are generated column by column inside the chunk generator.
 */
public final class BigStructures {

    private static final class Data {
        static final BlockData LADDER = Bukkit.createBlockData("minecraft:ladder[facing=east]");
        static final BlockData CHEST = Bukkit.createBlockData("minecraft:chest[facing=south]");
    }

    private static final int[][] CHESTS = {
            {-4, -4, 0}, {4, -4, 0}, {-4, 4, 0}, {4, 4, 0}, {-6, 0, 0}, {6, 0, 0},
            {-5, -5, 1}, {5, -5, 1}, {-5, 5, 1}, {5, 5, 1}, {0, -6, 1}, {0, 6, 1},
            {-4, -4, 2}, {4, -4, 2}, {0, -5, 2}, {0, 5, 2}
    };

    private BigStructures() { }

    public static void column(ChunkData cd, int lx, int lz, int x, int z, Terrain.Feature b) {
        Palette p = Palette.of(b.zone());
        int dx = x - b.x();
        int dz = z - b.z();
        if (b.type() == Terrain.FeatureType.MEGA_CASTLE) {
            castle(cd, lx, lz, dx, dz, b.baseY(), b.a(), p);
        } else {
            kingdom(cd, lx, lz, dx, dz, b.baseY(), b.a(), p);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void span(ChunkData cd, int lx, int lz, int y1, int y2, Material m) {
        if (y2 >= y1) cd.setRegion(lx, y1, lz, lx + 1, y2 + 1, lz + 1, m);
    }

    private static void one(ChunkData cd, int lx, int lz, int y, Material m) {
        cd.setBlock(lx, y, lz, m);
    }

    private static long hash(int a, int b) {
        long h = a * 0x9E3779B97F4A7C15L ^ b * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return h & Long.MAX_VALUE;
    }

    // ------------------------------------------------------------------ castle

    /** dx/dz are relative to the castle centre, H is the half size. */
    private static void castle(ChunkData cd, int lx, int lz, int dx, int dz, int f, int H, Palette p) {
        int adx = Math.abs(dx);
        int adz = Math.abs(dz);
        int cheb = Math.max(adx, adz);
        if (cheb > H) return;

        int wallH = 14 + H / 8;
        int K = H * 32 / 100;
        int keepH = 30 + H / 7;
        int rc = Math.max(8, H / 9);

        // courtyard floor
        boolean light = (((dx >> 2) + (dz >> 2)) & 1) == 0;
        one(cd, lx, lz, f, light ? p.floor() : p.wall());

        // glass crystals / statues in the courtyard
        long hh = hash(dx, dz);
        if (cheb < H - 7 && (adx > K + 3 || adz > K + 3) && hh % 91 == 0) {
            span(cd, lx, lz, f + 1, f + 3 + (int) ((hh >>> 8) % 9), p.glass());
        }

        // keep
        if (adx <= K && adz <= K) keep(cd, lx, lz, dx, dz, f, K, keepH, p);

        // outer wall
        if (cheb >= H - 5) {
            span(cd, lx, lz, f + 1, f + wallH, p.pick(hash(dx * 3, dz * 7)));
            if (cheb >= H - 1 && ((dx + dz) & 1) == 0) one(cd, lx, lz, f + wallH + 1, p.wall());
            if (cheb == H && Math.floorMod(dx + dz, 9) == 0) {
                span(cd, lx, lz, f + wallH - 6, f + wallH - 5, Material.AIR);
            }
            if (dz > 0 && adz >= H - 5 && adx <= 9) span(cd, lx, lz, f + 1, f + 16, Material.AIR);
        }

        // corner towers
        int c = H - 12;
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                tower(cd, lx, lz, dx, dz, sx * c, sz * c, rc, wallH + 30, f, p, -sx, 0);
            }
        }
        // wall towers
        int m = H - 4;
        int rm = rc - 2;
        int th = wallH + 16;
        tower(cd, lx, lz, dx, dz, 0, -m, rm, th, f, p, 0, 1);
        tower(cd, lx, lz, dx, dz, m, 0, rm, th, f, p, -1, 0);
        tower(cd, lx, lz, dx, dz, -m, 0, rm, th, f, p, 1, 0);
        tower(cd, lx, lz, dx, dz, -20, m, rm, th, f, p, 0, -1);
        tower(cd, lx, lz, dx, dz, 20, m, rm, th, f, p, 0, -1);
    }

    private static void keep(ChunkData cd, int lx, int lz, int dx, int dz, int f, int K, int keepH, Palette p) {
        int adx = Math.abs(dx);
        int adz = Math.abs(dz);
        boolean wallCol = adx >= K - 2 || adz >= K - 2;
        if (wallCol) {
            span(cd, lx, lz, f + 1, f + keepH, p.pick(hash(dx * 5, dz * 11)));
            boolean gate = dz > 0 && adz >= K - 2 && adx <= 4;
            if (gate) {
                span(cd, lx, lz, f + 1, f + 9, Material.AIR);
            } else if (Math.floorMod(dx + dz, 8) == 0 && (adx == K || adz == K)) {
                span(cd, lx, lz, f + 7, f + 9, p.glass());
            }
            if (((dx + dz) & 1) == 0 && (adx == K || adz == K)) one(cd, lx, lz, f + keepH + 1, p.wall());
            return;
        }
        boolean hole = adx <= 2 && adz <= 2;
        if (!hole) {
            one(cd, lx, lz, f + 14, p.floor());
            one(cd, lx, lz, f + 28, p.floor());
            if (hash(dx, dz) % 6 != 0) one(cd, lx, lz, f + keepH, p.roof());
        }
        if (dx == 0 && dz == 0) span(cd, lx, lz, f + 1, f + keepH, p.wall());
        if (dx == 1 && dz == 0) {
            for (int y = f + 1; y < f + keepH; y++) cd.setBlock(lx, y, lz, Data.LADDER);
        }
        for (int[] ch : CHESTS) {
            if (dx == K * ch[0] / 8 && dz == K * ch[1] / 8) {
                cd.setBlock(lx, f + 1 + 14 * ch[2], lz, Data.CHEST);
            }
        }
    }

    private static void tower(ChunkData cd, int lx, int lz, int dx, int dz, int tcx, int tcz, int r, int h,
                              int f, Palette p, int doorX, int doorZ) {
        int ox = dx - tcx;
        int oz = dz - tcz;
        int d2 = ox * ox + oz * oz;
        if (d2 > r * r) return;
        int rin = r - 2;
        if (d2 > rin * rin) {
            span(cd, lx, lz, f + 1, f + h, p.pick(hash(dx * 13, dz * 17)));
            boolean door = (doorX != 0 && ox * doorX >= r - 3 && Math.abs(oz) <= 1)
                    || (doorZ != 0 && oz * doorZ >= r - 3 && Math.abs(ox) <= 1);
            if (door) {
                span(cd, lx, lz, f + 1, f + 3, Material.AIR);
            } else if (Math.floorMod(ox + oz, 7) == 0) {
                span(cd, lx, lz, f + 8, f + 9, p.glass());
            }
            if (((dx + dz) & 1) == 0) one(cd, lx, lz, f + h + 1, p.wall());
        } else {
            span(cd, lx, lz, f + 1, f + h, Material.AIR);
            for (int y = f + 12; y <= f + h; y += 12) one(cd, lx, lz, y, p.floor());
        }
        // pointed glass roof
        double d = Math.sqrt(d2);
        int cone = (int) Math.round((r + 8) * (1.0 - d / r));
        if (cone > 0) span(cd, lx, lz, f + h + 2, f + h + 1 + cone, p.glass());
    }

    // ------------------------------------------------------------------ kingdom

    private static void kingdom(ChunkData cd, int lx, int lz, int dx, int dz, int f, int H, Palette p) {
        int adx = Math.abs(dx);
        int adz = Math.abs(dz);
        int cheb = Math.max(adx, adz);
        if (cheb > H) return;

        // the huge castle at the far (north) end
        int cz2 = dz + 100;
        if (Math.abs(cz2) <= 62 && adx <= 62) {
            castle(cd, lx, lz, dx, cz2, f, 62, p);
            return;
        }

        if (cheb >= H - 4) {
            // city wall with four gates
            span(cd, lx, lz, f + 1, f + 16, p.pick(hash(dx * 3, dz * 7)));
            if (cheb >= H - 1 && ((dx + dz) & 1) == 0) one(cd, lx, lz, f + 17, p.wall());
            boolean gate = (adz >= H - 4 && adx <= 7) || (adx >= H - 4 && adz <= 7);
            if (gate) span(cd, lx, lz, f + 1, f + 10, Material.AIR);
        } else {
            city(cd, lx, lz, dx, dz, f, p);
        }

        // corner towers of the city wall
        int c = H - 8;
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                tower(cd, lx, lz, dx, dz, sx * c, sz * c, 8, 34, f, p, -sx, 0);
            }
        }
    }

    private static void city(ChunkData cd, int lx, int lz, int dx, int dz, int f, Palette p) {
        int px = dx + 200;
        int pz = dz + 200;
        int cx = px / 18;
        int cz = pz / 18;
        int hx = px % 18;
        int hz = pz % 18;

        boolean road = hx < 4 || hz < 4 || Math.abs(dx) <= 3;
        if (road) {
            one(cd, lx, lz, f, p.floor());
            return;
        }
        long h = hash(cx, cz);
        if (h % 100 >= 62) return;

        boolean shellX = hx == 5 || hx == 14;
        boolean shellZ = hz == 6 || hz == 14;
        if (hx >= 5 && hx <= 14 && hz >= 6 && hz <= 14) {
            one(cd, lx, lz, f, p.floor());
            if (shellX || shellZ) {
                Material m = (shellX && shellZ) ? p.log() : p.plank();
                span(cd, lx, lz, f + 1, f + 5, m);
                if (hz == 14 && (hx == 9 || hx == 10)) {
                    span(cd, lx, lz, f + 1, f + 2, Material.AIR);
                } else if (((hx == 8 || hx == 11) && hz == 6) || (hz == 10 && shellX)) {
                    one(cd, lx, lz, f + 3, p.glass());
                }
            } else if (hx == 9 && hz == 8 && (h >>> 7) % 3 == 0) {
                cd.setBlock(lx, f + 1, lz, Data.CHEST);
            }
        }
        for (int k = 0; k < 5; k++) {
            if (hx >= 4 + k && hx <= 15 - k && hz >= 5 + k && hz <= 15 - k) {
                one(cd, lx, lz, f + 6 + k, p.roof());
            }
        }
    }
}
