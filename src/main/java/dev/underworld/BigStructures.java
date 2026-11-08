package dev.underworld;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.ChunkGenerator.ChunkData;

/**
 * The two giant structures, generated column by column inside the chunk generator:
 * the round crystal citadel (200x200) and the gothic kingdom (340x340) with its cathedral castle.
 */
public final class BigStructures {

    private static final class Data {
        static final BlockData LADDER = Bukkit.createBlockData("minecraft:ladder[facing=east]");
        static final BlockData CHEST = Bukkit.createBlockData("minecraft:chest[facing=south]");
    }

    private static final int[][] NAVE_CHESTS = {
            {-6, -30}, {6, -30}, {-6, -40}, {6, -40}, {0, -43}, {-28, -3}, {28, -3}, {-8, 20}, {8, 20}
    };

    private BigStructures() { }

    public static void column(ChunkData cd, int lx, int lz, int x, int z, Terrain.Feature b) {
        Palette p = Palette.of(b.zone());
        int dx = x - b.x();
        int dz = z - b.z();
        if (b.type() == Terrain.FeatureType.MEGA_CASTLE) {
            citadel(cd, lx, lz, dx, dz, b.baseY(), b.a(), p);
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

    /** Solid cone: height shrinks linearly from the centre to the radius. */
    private static void cone(ChunkData cd, int lx, int lz, double dist, double rad, int y0, double height, Material m) {
        if (dist > rad) return;
        int ch = (int) Math.round(height * (1.0 - dist / rad));
        if (ch > 0) span(cd, lx, lz, y0, y0 + ch - 1, m);
    }

    /** Round tower with a hollow inside, floors every 12 blocks and a cone roof. */
    private static void tower(ChunkData cd, int lx, int lz, int dx, int dz, int tcx, int tcz, int r, int h,
                              int f, Palette p, int doorX, int doorZ, Material coneMat, int coneH) {
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
                span(cd, lx, lz, f + 8, f + 9, p.light());
            }
            if (coneH <= 0 && ((dx + dz) & 1) == 0) one(cd, lx, lz, f + h + 1, p.wall());
        } else {
            span(cd, lx, lz, f + 1, f + h, Material.AIR);
            for (int y = f + 12; y <= f + h; y += 12) one(cd, lx, lz, y, p.floor());
        }
        if (coneH > 0) cone(cd, lx, lz, Math.sqrt(d2), r, f + h + 1, coneH, coneMat);
    }

    // ------------------------------------------------------------------ crystal citadel

    /** Round citadel: ring wall, eight bastions, six inner towers and giant lime crystals in the middle. */
    private static void citadel(ChunkData cd, int lx, int lz, int dx, int dz, int f, int H, Palette p) {
        double d = Math.sqrt((double) dx * dx + (double) dz * dz);
        if (d > H) return;
        int wallH = 22;

        boolean ring = ((int) (d / 5)) % 2 == 0;
        one(cd, lx, lz, f, ring ? p.floor() : p.wall());

        // amethyst ring around the crystal heart
        if (d >= 24 && d <= 26) span(cd, lx, lz, f + 1, f + 2, p.rare());

        // giant central crystals
        int[][] spires = {{0, 0, 100, 12}, {15, 7, 72, 9}, {-14, 9, 66, 9}, {9, -15, 80, 9}, {-10, -13, 60, 8}, {20, -8, 54, 7}};
        for (int[] sp : spires) {
            cone(cd, lx, lz, Math.hypot(dx - sp[0], dz - sp[1]), sp[3], f + 1, sp[2], p.glass());
        }

        // chests around the heart
        for (int i = 0; i < 6; i++) {
            double a = i * Math.PI / 3;
            if (dx == Math.round(Math.cos(a) * 31) && dz == Math.round(Math.sin(a) * 31)) {
                cd.setBlock(lx, f + 1, lz, Data.CHEST);
            }
        }

        // ring wall with a south gate
        if (d >= H - 5) {
            span(cd, lx, lz, f + 1, f + wallH, p.pick(hash(dx * 3, dz * 7)));
            if (d >= H - 1.5 && ((dx + dz) & 1) == 0) one(cd, lx, lz, f + wallH + 1, p.wall());
            if (dz > 0 && Math.abs(dx) <= 9) span(cd, lx, lz, f + 1, f + 14, Material.AIR);
        }

        // eight bastions on the wall
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4 + Math.PI / 8;
            int bx = (int) Math.round(Math.cos(a) * (H - 4));
            int bz = (int) Math.round(Math.sin(a) * (H - 4));
            int doorX = Math.abs(bx) >= Math.abs(bz) ? -(int) Math.signum((double) bx) : 0;
            int doorZ = Math.abs(bx) >= Math.abs(bz) ? 0 : -(int) Math.signum((double) bz);
            tower(cd, lx, lz, dx, dz, bx, bz, 9, wallH + 24, f, p, doorX, doorZ, p.glass(), 17);
        }
        // six slender inner towers
        for (int i = 0; i < 6; i++) {
            double a = i * Math.PI / 3 + Math.PI / 6;
            int bx = (int) Math.round(Math.cos(a) * 58);
            int bz = (int) Math.round(Math.sin(a) * 58);
            int doorX = Math.abs(bx) >= Math.abs(bz) ? -(int) Math.signum((double) bx) : 0;
            int doorZ = Math.abs(bx) >= Math.abs(bz) ? 0 : -(int) Math.signum((double) bz);
            tower(cd, lx, lz, dx, dz, bx, bz, 6, 50 + (i * 7) % 16, f, p, doorX, doorZ, p.glass(), 16);
        }
    }

    // ------------------------------------------------------------------ gothic kingdom

    private static void kingdom(ChunkData cd, int lx, int lz, int dx, int dz, int f, int H, Palette p) {
        int adx = Math.abs(dx);
        int adz = Math.abs(dz);
        int cheb = Math.max(adx, adz);
        if (cheb > H) return;

        // the cathedral castle at the far (north) end
        int cz2 = dz + 100;
        if (adx <= 62 && Math.abs(cz2) <= 62) {
            cathedral(cd, lx, lz, dx, cz2, f, p);
            return;
        }

        if (cheb >= H - 4) {
            span(cd, lx, lz, f + 1, f + 18, p.pick(hash(dx * 3, dz * 7)));
            if (cheb >= H - 1 && ((dx + dz) & 1) == 0) one(cd, lx, lz, f + 19, p.wall());
            boolean gate = (adz >= H - 4 && adx <= 7) || (adx >= H - 4 && adz <= 7);
            if (gate) span(cd, lx, lz, f + 1, f + 11, Material.AIR);
        } else {
            city(cd, lx, lz, dx, dz, f, p);
        }
        int c = H - 8;
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                tower(cd, lx, lz, dx, dz, sx * c, sz * c, 8, 38, f, p, -sx, 0, p.roof(), 26);
            }
        }
    }

    private static boolean inCath(int dx, int dz) {
        boolean nave = Math.abs(dx) <= 13 && dz >= -46 && dz <= 34;
        boolean transept = Math.abs(dx) <= 34 && dz >= -14 && dz <= 8;
        return nave || transept;
    }

    private static void cathedral(ChunkData cd, int lx, int lz, int dx, int dz, int f, Palette p) {
        int adx = Math.abs(dx);
        one(cd, lx, lz, f, p.floor());

        boolean nave = adx <= 13 && dz >= -46 && dz <= 34;
        boolean in = inCath(dx, dz);
        if (in) {
            int roofRel = nave ? 30 + (13 - adx) : 24 + (11 - Math.abs(dz + 3));
            int wallH = nave ? 30 : 24;
            boolean edge = !inCath(dx + 2, dz) || !inCath(dx - 2, dz) || !inCath(dx, dz + 2) || !inCath(dx, dz - 2);
            if (edge) {
                int top = Math.max(wallH, roofRel - 1);
                span(cd, lx, lz, f + 1, f + top, p.pick(hash(dx * 5, dz * 11)));
                int k = Math.floorMod(dx + dz, 8);
                for (int h = 6; h <= 22; h++) {
                    double half = h <= 15 ? 1.6 : (h <= 19 ? 1.0 : 0.4);
                    if (Math.abs(k - 3.5) <= half) one(cd, lx, lz, f + h, p.light());
                }
                if (dz >= 33 && adx <= 4) span(cd, lx, lz, f + 1, f + 13, Material.AIR);
            }
            one(cd, lx, lz, f + roofRel, p.roof());
        }

        // crossing tower with a tall slate spire
        int ccz = dz + 3;
        if (adx <= 8 && Math.abs(ccz) <= 8) {
            if (Math.max(adx, Math.abs(ccz)) >= 7) {
                span(cd, lx, lz, f + 1, f + 62, p.pick(hash(dx * 7, dz * 3)));
                if (Math.floorMod(dx + dz, 5) == 0) span(cd, lx, lz, f + 40, f + 48, p.light());
            } else {
                span(cd, lx, lz, f + 31, f + 62, Material.AIR);
            }
            one(cd, lx, lz, f + 63, p.roof());
            cone(cd, lx, lz, Math.hypot(dx, ccz), 9, f + 64, 46, p.roof());
        }

        // turrets with slate spires
        int[][] turrets = {{13, -46}, {-13, -46}, {13, 34}, {-13, 34}, {34, -14}, {-34, -14},
                {34, 8}, {-34, 8}, {13, -14}, {-13, -14}, {13, 8}, {-13, 8}};
        for (int i = 0; i < turrets.length; i++) {
            int tx = turrets[i][0];
            int tz = turrets[i][1];
            tower(cd, lx, lz, dx, dz, tx, tz, 3, 50 + (i * 7) % 20, f, p, 0, 0, p.roof(), 22);
        }
        // twin front towers
        tower(cd, lx, lz, dx, dz, 9, 40, 6, 58, f, p, 0, -1, p.roof(), 30);
        tower(cd, lx, lz, dx, dz, -9, 40, 6, 58, f, p, 0, -1, p.roof(), 30);

        for (int[] c : NAVE_CHESTS) {
            if (dx == c[0] && dz == c[1]) cd.setBlock(lx, f + 1, lz, Data.CHEST);
        }
    }

    /** Streets and tall gothic houses: steep slate roofs, teal trim, warm orange windows. */
    private static void city(ChunkData cd, int lx, int lz, int dx, int dz, int f, Palette p) {
        int px = dx + 200;
        int pz = dz + 200;
        int cx = px / 18;
        int cz = pz / 18;
        int hx = px % 18;
        int hz = pz % 18;

        boolean road = hx < 3 || hz < 3 || Math.abs(dx) <= 3;
        if (road) {
            one(cd, lx, lz, f, p.floor());
            return;
        }
        long h = hash(cx, cz);
        if (h % 100 >= 60) return;

        boolean inside = hx >= 4 && hx <= 13 && hz >= 4 && hz <= 14;
        double u = Math.abs(hx - 8.5);
        int roofRel = 9 + (int) (4.5 - u);

        if (inside) {
            one(cd, lx, lz, f, p.floor());
            boolean shellX = hx == 4 || hx == 13;
            boolean shellZ = hz == 4 || hz == 14;
            if (shellX || shellZ) {
                boolean corner = shellX && shellZ;
                if (corner) {
                    span(cd, lx, lz, f + 1, f + 8, p.trim());
                } else {
                    span(cd, lx, lz, f + 1, f + 8, p.pick(hash(px * 3, pz * 5)));
                    if (Math.floorMod(hx + hz, 3) == 1) {
                        span(cd, lx, lz, f + 3, f + 4, p.light());
                        span(cd, lx, lz, f + 6, f + 7, p.light());
                    }
                    if (hz == 14 && (hx == 8 || hx == 9)) span(cd, lx, lz, f + 1, f + 3, Material.AIR);
                }
                if (shellZ) span(cd, lx, lz, f + 9, f + roofRel - 1, p.wall());
            } else {
                if (hx == 11 && hz == 6 && (h >>> 5) % 3 == 0) span(cd, lx, lz, f + 1, f + roofRel + 4, Material.COBBLESTONE);
                if (hx == 6 && hz == 7 && (h >>> 9) % 4 == 0) cd.setBlock(lx, f + 1, lz, Data.CHEST);
            }
        }
        if (hx >= 3 && hx <= 14 && hz >= 3 && hz <= 15) {
            one(cd, lx, lz, f + roofRel, p.roof());
        }
    }
}
