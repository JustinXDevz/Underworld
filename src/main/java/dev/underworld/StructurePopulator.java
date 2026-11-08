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

/**
 * Builds the (rare) structures chosen by {@link Terrain}. Every zone has its own designs:
 * hub chapel ruins, crystal shrines, crack pavilions, snow cabins and stone circles, gothic houses and keeps.
 * Every structure also gets a random rotation and random proportions.
 */
public final class StructurePopulator extends BlockPopulator {

    private static final String CHEST = "minecraft:chest[facing=south]";
    private static final String LADDER_S = "minecraft:ladder[facing=south]";
    private static final String LADDER_E = "minecraft:ladder[facing=east]";

    @Override
    public void populate(WorldInfo info, Random unused, int chunkX, int chunkZ, LimitedRegion region) {
        Terrain t = Terrain.get(info.getSeed());
        int per = Terrain.STRUCT_CELL / 16;
        Terrain.Feature f = t.structure(Math.floorDiv(chunkX, per), Math.floorDiv(chunkZ, per));
        if (f.type() == Terrain.FeatureType.NONE) return;
        if (Math.floorDiv(f.x(), 16) != chunkX || Math.floorDiv(f.z(), 16) != chunkZ) return;

        Random r = new Random(info.getSeed() * 31L + f.x() * 7919L + f.z() * 104729L);
        Site s = new Site(region, t, r, f);
        Terrain.Zone z = f.zone();
        switch (f.type()) {
            case FORTRESS -> fortress(s);
            case MAZE -> maze(s);
            case TOWER -> {
                if (z == Terrain.Zone.BASE) squareTower(s);
                else if (z == Terrain.Zone.DARK) gothicTower(s);
                else roundTower(s);
            }
            case RUIN -> {
                switch (z) {
                    case CRYSTAL -> shrine(s);
                    case CRACK -> pavilion(s);
                    case SNOW -> stoneCircle(s);
                    case DARK -> gothicHouse(s, true);
                    default -> chapel(s);
                }
            }
            case OUTPOST -> {
                switch (z) {
                    case CRYSTAL -> outpostRing(s);
                    case CRACK -> outpostCross(s);
                    case SNOW -> palisade(s);
                    case DARK -> gothicKeep(s);
                    default -> outpostSquare(s);
                }
            }
            case HOUSE -> {
                if (z == Terrain.Zone.SNOW) cabin(s);
                else gothicHouse(s, false);
            }
            case OBELISK -> {
                if (z == Terrain.Zone.CRYSTAL) bladeObelisk(s);
                else if (z == Terrain.Zone.CRACK) spike(s);
                else pillarObelisk(s);
            }
            default -> { }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static final class Site {
        final LimitedRegion region;
        final Terrain t;
        final Random r;
        final Palette pal;
        final Terrain.Zone zone;
        final int cx;
        final int cz;
        final int floor;
        final int rot;

        Site(LimitedRegion region, Terrain t, Random r, Terrain.Feature f) {
            this.region = region;
            this.t = t;
            this.r = r;
            this.zone = f.zone();
            this.pal = Palette.of(f.zone());
            this.cx = f.x();
            this.cz = f.z();
            this.floor = f.baseY();
            this.rot = r.nextInt(4);
        }

        private int rx(int x, int z) {
            int dx = x - cx;
            int dz = z - cz;
            return cx + switch (rot) {
                case 1 -> -dz;
                case 2 -> -dx;
                case 3 -> dz;
                default -> dx;
            };
        }

        private int rz(int x, int z) {
            int dx = x - cx;
            int dz = z - cz;
            return cz + switch (rot) {
                case 1 -> dx;
                case 2 -> -dz;
                case 3 -> -dx;
                default -> dz;
            };
        }

        /** Absolute coordinates, rotated around the structure centre. */
        void set(int x, int y, int z, Material m) {
            raw(rx(x, z), y, rz(x, z), m);
        }

        void data(int x, int y, int z, String blockData) {
            int ax = rx(x, z);
            int az = rz(x, z);
            if (region.isInRegion(ax, y, az)) {
                region.setBlockData(ax, y, az, Bukkit.createBlockData(rotateFacing(blockData)));
            }
        }

        void raw(int x, int y, int z, Material m) {
            if (region.isInRegion(x, y, z)) region.setType(x, y, z, m);
        }

        private String rotateFacing(String d) {
            int i = d.indexOf("facing=");
            if (i < 0 || rot == 0) return d;
            int start = i + 7;
            int end = start;
            while (end < d.length() && Character.isLetter(d.charAt(end))) end++;
            String face = d.substring(start, end);
            String[] order = {"north", "east", "south", "west"};
            for (int k = 0; k < 4; k++) {
                if (order[k].equals(face)) return d.substring(0, start) + order[(k + rot) % 4] + d.substring(end);
            }
            return d;
        }

        // ---- relative helpers (dx/dz from the centre, dy above the floor)

        void b(int dx, int dy, int dz, Material m) {
            set(cx + dx, floor + dy, cz + dz, m);
        }

        void bd(int dx, int dy, int dz, String blockData) {
            data(cx + dx, floor + dy, cz + dz, blockData);
        }

        void box(int x1, int y1, int z1, int x2, int y2, int z2, Material m) {
            for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
                for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                    for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) b(x, y, z, m);
                }
            }
        }

        void disc(int rad, int dy, Material m) {
            for (int dx = -rad; dx <= rad; dx++) {
                for (int dz = -rad; dz <= rad; dz++) {
                    if (dx * dx + dz * dz <= rad * rad) b(dx, dy, dz, m);
                }
            }
        }

        /** A leaning crystal blade (relative base point, azimuth in radians, tilt in degrees). */
        void blade(double rxp, double ry, double rzp, double az, double tiltDeg, double len, double rad, Material m) {
            double tilt = Math.toRadians(tiltDeg);
            Terrain.Blade bl = new Terrain.Blade(cx + rxp, floor + ry, cz + rzp,
                    Math.sin(tilt) * Math.cos(az), Math.cos(tilt), Math.sin(tilt) * Math.sin(az), len, rad);
            double ex = bl.bx() + bl.dx() * len;
            double ez = bl.bz() + bl.dz() * len;
            double ey = bl.by() + bl.dy() * len;
            int x1 = (int) Math.floor(Math.min(bl.bx(), ex) - rad - 1);
            int x2 = (int) Math.ceil(Math.max(bl.bx(), ex) + rad + 1);
            int z1 = (int) Math.floor(Math.min(bl.bz(), ez) - rad - 1);
            int z2 = (int) Math.ceil(Math.max(bl.bz(), ez) + rad + 1);
            int y1 = (int) Math.floor(bl.by() - rad);
            int y2 = (int) Math.ceil(ey + rad);
            for (int x = x1; x <= x2; x++) {
                for (int z = z1; z <= z2; z++) {
                    for (int y = y1; y <= y2; y++) {
                        if (Terrain.insideBlade(x + 0.5, y + 0.5, z + 0.5, bl)) raw(x, y, z, m);
                    }
                }
            }
        }

        Material brick() {
            return pal.pick(r);
        }

        /** Levels the ground (not rotated: the square footprint is symmetric). */
        void prepare(int half) {
            for (int x = cx - half; x <= cx + half; x++) {
                for (int z = cz - half; z <= cz + half; z++) {
                    int s = t.column(x, z).surface();
                    if (s == Terrain.VOID) s = floor;
                    for (int y = s + 1; y < floor; y++) raw(x, y, z, pal.wall());
                    for (int y = floor + 1; y <= s; y++) raw(x, y, z, Material.AIR);
                    raw(x, floor, z, r.nextInt(6) == 0 ? pal.alt() : pal.floor());
                }
            }
        }
    }

    // ================================================================== HUB

    /** A ruined gothic chapel: broken pointed windows, pillars, a stump of a tower. */
    private void chapel(Site s) {
        s.prepare(15);
        int len = 11 + s.r.nextInt(4);
        int wallH = 9 + s.r.nextInt(4);
        double decay = 0.035 + s.r.nextDouble() * 0.04;

        for (int dz = -len; dz <= len; dz++) {
            int k = Math.floorMod(dz + len, 6);
            for (int dx = -6; dx <= 6; dx++) {
                boolean side = Math.abs(dx) == 6;
                boolean back = dz == -len;
                boolean front = dz == len;
                if (side || back || front) {
                    int top = back ? wallH + 5 : wallH;
                    for (int h = 1; h <= top; h++) {
                        if (side && arch(k, h)) continue;
                        if (back && Math.abs(dx) <= 1 && h >= 4 && h <= 12) continue;
                        if (front && Math.abs(dx) <= 1 && h <= 7) continue;
                        if (s.r.nextDouble() < Math.max(0, h - 3) * decay) continue;
                        s.b(dx, h, dz, s.brick());
                    }
                    if (side && k == 0) {
                        for (int h = 1; h <= 6; h++) s.b(dx > 0 ? 7 : -7, h, dz, s.brick());
                    }
                } else if (Math.abs(dx) == 3 && k == 3) {
                    int ph = 6 + s.r.nextInt(5);
                    for (int h = 1; h <= ph; h++) s.b(dx, h, dz, s.pal.log());
                }
            }
        }
        for (int i = 0; i < 16; i++) {
            s.b(s.r.nextInt(9) - 4, 1, s.r.nextInt(2 * len - 3) - len + 2, s.brick());
        }
        s.b(0, 1, -len + 2, s.pal.wall());
        s.bd(0, 2, -len + 2, CHEST);

        int tx = -10;
        int tz = -len + 3;
        for (int ox = -3; ox <= 3; ox++) {
            for (int oz = -3; oz <= 3; oz++) {
                int d2 = ox * ox + oz * oz;
                if (d2 > 10 || d2 < 3) continue;
                int th = 12 + s.r.nextInt(8);
                for (int h = 1; h <= th; h++) {
                    if (h > 6 && s.r.nextDouble() < (h - 6) * 0.1) continue;
                    s.b(tx + ox, h, tz + oz, s.brick());
                }
            }
        }
    }

    private static boolean arch(int k, int h) {
        if (h < 3 || h > 8) return false;
        double half = h <= 5 ? 1.6 : (h <= 7 ? 1.0 : 0.4);
        return Math.abs(k - 2.5) <= half;
    }

    private void fortress(Site s) {
        int cx = s.cx;
        int cz = s.cz;
        int f = s.floor;
        int wall = 7 + s.r.nextInt(5);
        int towerH = 14 + s.r.nextInt(9);
        int keepH = 11 + s.r.nextInt(6);
        s.prepare(15);

        for (int dx = -15; dx <= 15; dx++) {
            for (int dz = -15; dz <= 15; dz++) {
                if (Math.abs(dx) < 14 && Math.abs(dz) < 14) continue;
                boolean gate = dz >= 14 && Math.abs(dx) <= 2;
                for (int h = 1; h <= wall; h++) {
                    if (gate && h <= 5) continue;
                    if (h > 4 && s.r.nextDouble() < (h - 4) * 0.07) continue;
                    if (h == wall && ((dx + dz) & 1) == 0) continue;
                    s.set(cx + dx, f + h, cz + dz, s.brick());
                }
            }
        }
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                int tx = cx + sx * 13;
                int tz = cz + sz * 13;
                for (int ox = -2; ox <= 2; ox++) {
                    for (int oz = -2; oz <= 2; oz++) {
                        boolean shell = Math.abs(ox) == 2 || Math.abs(oz) == 2;
                        for (int h = 1; h <= towerH; h++) {
                            boolean door = ox == -sx * 2 && oz == 0 && h <= 2;
                            if (door) continue;
                            if (h > towerH - 6 && s.r.nextDouble() < (h - towerH + 6) * 0.12) continue;
                            if (shell || h == 9) s.set(tx + ox, f + h, tz + oz, s.brick());
                        }
                    }
                }
            }
        }
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                boolean shell = Math.abs(dx) == 5 || Math.abs(dz) == 5;
                for (int h = 1; h <= keepH; h++) {
                    boolean door = dz == 5 && Math.abs(dx) <= 1 && h <= 3;
                    if (door) continue;
                    if (shell) {
                        if (h > keepH - 4 && s.r.nextDouble() < (h - keepH + 4) * 0.1) continue;
                        s.set(cx + dx, f + h, cz + dz, s.brick());
                    } else if (h == 7 && !(Math.abs(dx) <= 1 && Math.abs(dz) <= 1)) {
                        s.set(cx + dx, f + h, cz + dz, s.brick());
                    } else if (h == keepH && s.r.nextDouble() > 0.35) {
                        s.set(cx + dx, f + h, cz + dz, s.pal.roof());
                    }
                }
            }
        }
        s.data(cx, f + 1, cz, CHEST);
        s.data(cx - 3, f + 1, cz - 3, CHEST);
        s.data(cx + 3, f + 8, cz - 3, CHEST);

        int pillars = 4 + s.r.nextInt(9);
        for (int i = 0; i < pillars; i++) {
            int dx = s.r.nextInt(21) - 10;
            int dz = s.r.nextInt(21) - 10;
            if (Math.abs(dx) <= 6 && Math.abs(dz) <= 6) continue;
            int height = 1 + s.r.nextInt(4);
            for (int h = 1; h <= height; h++) s.set(cx + dx, f + h, cz + dz, s.brick());
        }
    }

    private void squareTower(Site s) {
        int cx = s.cx;
        int cz = s.cz;
        int f = s.floor;
        int height = 20 + s.r.nextInt(21);
        s.prepare(4);

        int topFloor = 0;
        for (int y = 10; y <= height - 3; y += 10) topFloor = y;

        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                int ring = Math.max(Math.abs(dx), Math.abs(dz));
                for (int h = 1; h <= height; h++) {
                    if (ring == 4) {
                        boolean door = dz == 4 && dx == 0 && h <= 2;
                        boolean slit = h % 5 == 3 && ((dz == 0 && Math.abs(dx) == 4) || (dx == 0 && dz == 4));
                        if (door || slit) continue;
                        if (h == height && ((dx + dz) & 1) == 0) continue;
                        if (h > height - 14 && s.r.nextDouble() < (h - height + 14) * 0.04) continue;
                        s.set(cx + dx, f + h, cz + dz, s.brick());
                    } else if (h % 10 == 0 && h <= topFloor && !(dx == 0 && dz == -3)) {
                        s.set(cx + dx, f + h, cz + dz, s.brick());
                    }
                }
            }
        }
        for (int h = 1; h <= topFloor; h++) s.data(cx, f + h, cz - 3, LADDER_S);
        for (int y = 10; y <= topFloor; y += 10) {
            s.data(cx + (y % 20 == 0 ? -2 : 2), f + y + 1, cz + 2, CHEST);
        }
    }

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
        s.data(s.cx - 1, f + 1, s.cz, CHEST);
        s.data(s.cx + 1, f + 1, s.cz, CHEST);
    }

    /** Hub outpost: square wall, tall corner posts, a watch tower in the middle. */
    private void outpostSquare(Site s) {
        int cx = s.cx;
        int cz = s.cz;
        int f = s.floor;
        s.prepare(9);
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
        for (int h = 1; h <= 9; h++) s.data(cx, f + h, cz - 1, LADDER_S);
        s.data(cx - 5, f + 1, cz - 5, CHEST);
        s.data(cx + 5, f + 1, cz - 5, CHEST);
        s.data(cx, f + 6, cz + 1, CHEST);
    }

    private void pillarObelisk(Site s) {
        int height = 24 + s.r.nextInt(26);
        s.prepare(4);
        plinth(s);
        for (int h = 4; h <= height; h++) {
            boolean thin = h > height * 0.7;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (thin && (dx != 0 || dz != 0)) continue;
                    s.b(dx, h, dz, h % 9 == 0 ? s.pal.rare() : s.pal.log());
                }
            }
        }
        s.b(0, height + 1, 0, s.pal.light());
        s.b(0, height + 2, 0, Material.LIGHT_BLUE_STAINED_GLASS);
        s.bd(4, 2, 0, CHEST);
    }

    private void plinth(Site s) {
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                int ring = Math.max(Math.abs(dx), Math.abs(dz));
                if (ring <= 4) s.b(dx, 1, dz, s.brick());
                if (ring <= 3) s.b(dx, 2, dz, s.brick());
                if (ring <= 2) s.b(dx, 3, dz, s.brick());
            }
        }
    }

    // ================================================================== CRYSTAL WASTELAND

    /** Round tower; the roof depends on the zone (glass cone, slate spire, or broken battlements). */
    private void roundTower(Site s) {
        s.prepare(7);
        int r = 4 + s.r.nextInt(2);
        int height = 22 + s.r.nextInt(18);
        boolean broken = s.zone == Terrain.Zone.CRACK;
        int lastFloor = 0;
        for (int y = 8; y <= height - 3; y += 8) lastFloor = y;

        for (int h = 1; h <= height; h++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    int d2 = dx * dx + dz * dz;
                    if (d2 > r * r + 1) continue;
                    boolean shell = d2 > (r - 1) * (r - 1);
                    if (shell) {
                        boolean door = dz >= r - 1 && Math.abs(dx) <= 1 && h <= 3;
                        boolean slit = h % 6 == 3 && dz == 0 && Math.abs(dx) >= r - 1;
                        if (door || slit) continue;
                        if (broken && h > height - 10 && s.r.nextDouble() < (h - height + 10) * 0.09) continue;
                        s.b(dx, h, dz, s.brick());
                    } else if (h % 8 == 0 && h <= lastFloor && !(dx == 0 && dz == -(r - 1))) {
                        s.b(dx, h, dz, s.brick());
                    }
                }
            }
        }
        for (int h = 1; h <= lastFloor; h++) s.bd(0, h, -(r - 1), LADDER_S);

        if (s.zone == Terrain.Zone.CRYSTAL || s.zone == Terrain.Zone.SNOW) {
            Material cone = s.zone == Terrain.Zone.CRYSTAL ? s.pal.glass() : s.pal.roof();
            int ch = r + 9;
            for (int k = 1; k <= ch; k++) {
                double rr = r * (1.0 - (double) k / ch) + 0.4;
                s.disc((int) Math.round(rr), height + k, cone);
            }
        } else {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    int d2 = dx * dx + dz * dz;
                    if (d2 > r * r + 1 || d2 <= (r - 1) * (r - 1)) continue;
                    if (((dx + dz) & 1) == 0 && !broken) s.b(dx, height + 1, dz, s.brick());
                }
            }
        }
        if (lastFloor >= 8) s.bd(2, 9, 1, CHEST);
        if (lastFloor >= 16) s.bd(-2, lastFloor + 1, 1, CHEST);
    }

    /** Circular dais with a ring of lit pillars, leaning crystal blades and an altar. */
    private void shrine(Site s) {
        s.prepare(12);
        s.disc(11, 1, s.pal.floor());
        s.disc(9, 2, s.pal.alt());
        s.disc(7, 3, s.pal.floor());

        int pillars = 6 + s.r.nextInt(3);
        for (int i = 0; i < pillars; i++) {
            double a = i * Math.PI * 2 / pillars;
            int px = (int) Math.round(Math.cos(a) * 8.5);
            int pz = (int) Math.round(Math.sin(a) * 8.5);
            int ph = 5 + s.r.nextInt(5);
            for (int h = 2; h <= ph + 1; h++) s.b(px, h, pz, s.pal.log());
            s.b(px, ph + 2, pz, s.pal.light());
            s.blade(px, 2, pz, a + (s.r.nextDouble() - 0.5) * 0.6, 12 + s.r.nextInt(18),
                    10 + s.r.nextInt(16), 1.4 + s.r.nextDouble(), s.pal.glass());
        }
        s.blade(2, 3, 2, s.r.nextDouble() * 6.28, 8, 20 + s.r.nextInt(14), 2.4, s.pal.glass());
        s.box(-1, 4, -1, 1, 4, 1, s.pal.wall());
        s.b(0, 5, 0, s.pal.rare());
        s.bd(0, 6, 0, CHEST);
    }

    /** Crystal outpost: a ring wall with four gaps, crystal buttresses and a glass-capped keep. */
    private void outpostRing(Site s) {
        s.prepare(9);
        for (int dx = -9; dx <= 9; dx++) {
            for (int dz = -9; dz <= 9; dz++) {
                int d2 = dx * dx + dz * dz;
                if (d2 > 81 || d2 < 64) continue;
                if (Math.abs(dx) <= 1 || Math.abs(dz) <= 1) continue;
                int hh = 4 + ((dx * 3 + dz * 5) & 1);
                for (int h = 1; h <= hh; h++) s.b(dx, h, dz, s.brick());
            }
        }
        for (int i = 0; i < 4; i++) {
            double a = Math.PI / 4 + i * Math.PI / 2;
            s.blade(Math.cos(a) * 8.5, 1, Math.sin(a) * 8.5, a, 18, 12 + s.r.nextInt(6), 1.5, s.pal.glass());
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int d2 = dx * dx + dz * dz;
                if (d2 > 5 || d2 < 2) continue;
                for (int h = 1; h <= 8; h++) s.b(dx, h, dz, s.brick());
            }
        }
        s.disc(1, 9, s.pal.glass());
        s.b(0, 10, 0, s.pal.light());
        s.bd(-6, 1, -3, CHEST);
        s.bd(6, 1, -3, CHEST);
    }

    /** One huge leaning crystal on a plinth. */
    private void bladeObelisk(Site s) {
        s.prepare(5);
        plinth(s);
        s.blade(0, 4, 0, s.r.nextDouble() * 6.28, 7 + s.r.nextInt(8), 34 + s.r.nextInt(18), 3.2, s.pal.glass());
        s.blade(1, 4, -1, s.r.nextDouble() * 6.28, 20 + s.r.nextInt(10), 14 + s.r.nextInt(8), 1.6, s.pal.glass());
        s.bd(4, 2, 0, CHEST);
    }

    // ================================================================== THE CRACK

    /** Open columned pavilion of red nether brick on a round dais. */
    private void pavilion(Site s) {
        s.prepare(10);
        s.disc(9, 1, s.pal.floor());
        s.disc(7, 2, s.pal.alt());
        int n = 8;
        for (int i = 0; i < n; i++) {
            double a = i * Math.PI * 2 / n + 0.2;
            int px = (int) Math.round(Math.cos(a) * 7);
            int pz = (int) Math.round(Math.sin(a) * 7);
            int ph = 5 + s.r.nextInt(6);
            s.b(px, 3, pz, s.pal.rare());
            for (int h = 4; h <= ph + 3; h++) s.b(px, h, pz, s.brick());
            if (s.r.nextDouble() < 0.6) s.b(px, ph + 4, pz, s.pal.rare());
        }
        s.b(0, 3, 0, s.pal.rare());
        s.b(0, 4, 0, s.pal.light());
        s.b(0, 5, 0, s.pal.light());
        s.bd(3, 3, 0, CHEST);
    }

    private static boolean cross(int dx, int dz) {
        return (Math.abs(dx) <= 4 && Math.abs(dz) <= 9) || (Math.abs(dz) <= 4 && Math.abs(dx) <= 9);
    }

    /** Cross-shaped bastion of nether brick. */
    private void outpostCross(Site s) {
        s.prepare(10);
        for (int dx = -9; dx <= 9; dx++) {
            for (int dz = -9; dz <= 9; dz++) {
                if (!cross(dx, dz)) continue;
                boolean edge = !cross(dx + 1, dz) || !cross(dx - 1, dz) || !cross(dx, dz + 1) || !cross(dx, dz - 1);
                if (!edge) continue;
                boolean gate = (Math.abs(dx) == 9 && Math.abs(dz) <= 1) || (Math.abs(dz) == 9 && Math.abs(dx) <= 1);
                if (gate) continue;
                int hh = 5 + ((dx + dz) & 1);
                for (int h = 1; h <= hh; h++) s.b(dx, h, dz, h == hh && (dx + dz) % 4 == 0 ? s.pal.rare() : s.brick());
            }
        }
        s.b(0, 1, 0, s.pal.rare());
        s.b(0, 2, 0, s.pal.light());
        s.bd(0, 1, -7, CHEST);
        s.bd(0, 1, 7, CHEST);
    }

    /** Tapering spike of crying obsidian. */
    private void spike(Site s) {
        s.prepare(5);
        plinth(s);
        int height = 28 + s.r.nextInt(18);
        for (int h = 1; h <= height; h++) {
            double rr = 2.8 * (1.0 - (double) h / height);
            int ri = (int) Math.ceil(rr);
            for (int dx = -ri; dx <= ri; dx++) {
                for (int dz = -ri; dz <= ri; dz++) {
                    if (dx * dx + dz * dz <= rr * rr + 0.4) {
                        s.b(dx, h + 3, dz, (h / 5) % 2 == 0 ? s.pal.rare() : Material.OBSIDIAN);
                    }
                }
            }
        }
        s.b(0, height + 4, 0, s.pal.light());
        s.bd(4, 2, 0, CHEST);
    }

    // ================================================================== SNOW SOUL

    /** Log cabin with a steep snowy roof and a chimney. */
    private void cabin(Site s) {
        s.prepare(8);
        int hw = 5 + s.r.nextInt(2);
        for (int dx = -(hw + 1); dx <= hw + 1; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                boolean inside = Math.abs(dx) <= hw && Math.abs(dz) <= 3;
                boolean shell = inside && (Math.abs(dx) == hw || Math.abs(dz) == 3);
                boolean corner = Math.abs(dx) == hw && Math.abs(dz) == 3;
                int roofY = Math.abs(dz) == 4 ? 4 : 5 + (3 - Math.abs(dz));
                if (shell) {
                    for (int h = 1; h <= 4; h++) {
                        boolean door = dz == 3 && dx == 0 && h <= 2;
                        if (door) continue;
                        boolean window = h == 3 && ((Math.abs(dx) == hw && dz == 0)
                                || (dz == -3 && Math.abs(dx) == 2) || (dz == 3 && Math.abs(dx) == 3));
                        s.b(dx, h, dz, window ? s.pal.glass() : (corner ? s.pal.log() : s.pal.plank()));
                    }
                    if (Math.abs(dx) == hw) {
                        for (int h = 5; h < roofY; h++) s.b(dx, h, dz, s.pal.plank());
                    }
                }
                s.b(dx, roofY, dz, s.pal.roof());
                if (s.r.nextDouble() < 0.75) s.b(dx, roofY + 1, dz, Material.SNOW_BLOCK);
            }
        }
        for (int h = 1; h <= 11; h++) s.b(hw - 1, h, -2, Material.COBBLESTONE);
        s.bd(-hw + 2, 1, -2, CHEST);
    }

    /** Ring of weathered standing stones around a snowy altar. */
    private void stoneCircle(Site s) {
        s.prepare(11);
        int n = 9 + s.r.nextInt(5);
        for (int i = 0; i < n; i++) {
            double a = i * Math.PI * 2 / n;
            int px = (int) Math.round(Math.cos(a) * 8);
            int pz = (int) Math.round(Math.sin(a) * 8);
            int h = 3 + s.r.nextInt(6);
            for (int y = 1; y <= h; y++) {
                for (int ox = 0; ox <= 1; ox++) {
                    for (int oz = 0; oz <= 1; oz++) {
                        s.b(px + ox, y, pz + oz, y == h && ox + oz == 0 ? s.pal.alt() : s.pal.rare());
                    }
                }
            }
            s.b(px, h + 1, pz, Material.SNOW_BLOCK);
        }
        for (int k = 0; k < 6; k++) s.b(-3 + k, 1, 5, s.pal.wall());
        s.box(-1, 1, -1, 1, 1, 1, s.pal.floor());
        s.b(0, 2, 0, Material.SNOW_BLOCK);
        s.bd(0, 3, 0, CHEST);
    }

    /** Snow outpost: spruce log palisade, watch posts and a cabin in the middle. */
    private void palisade(Site s) {
        s.prepare(9);
        for (int dx = -8; dx <= 8; dx++) {
            for (int dz = -8; dz <= 8; dz++) {
                if (Math.abs(dx) != 8 && Math.abs(dz) != 8) continue;
                boolean gate = dz == 8 && Math.abs(dx) <= 1;
                boolean post = Math.abs(dx) >= 7 && Math.abs(dz) >= 7;
                int hh = post ? 10 : 6 + ((dx * 7 + dz * 3) & 1);
                for (int h = 1; h <= hh; h++) {
                    if (gate && h <= 4) continue;
                    s.b(dx, h, dz, s.pal.log());
                }
            }
        }
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                s.box(sx * 8 - 1, 8, sz * 8 - 1, sx * 8 + 1, 8, sz * 8 + 1, s.pal.plank());
            }
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                boolean shell = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                if (shell) {
                    for (int h = 1; h <= 4; h++) {
                        boolean door = dz == 2 && dx == 0 && h <= 2;
                        if (door) continue;
                        s.b(dx, h, dz, s.pal.plank());
                    }
                }
                for (int k = 0; k < 3; k++) {
                    if (Math.abs(dx) <= 3 - k && Math.abs(dz) <= 3 - k) s.b(dx, 5 + k, dz, s.pal.roof());
                }
                if (Math.abs(dx) <= 2 && Math.abs(dz) <= 2) s.b(dx, 8, dz, Material.SNOW_BLOCK);
            }
        }
        s.bd(-5, 1, -5, CHEST);
        s.bd(5, 1, -5, CHEST);
    }

    // ================================================================== VALLEY OF DARK (gothic)

    /** Tall narrow gothic house with a steep slate roof, teal trim and warm orange windows. */
    private void gothicHouse(Site s, boolean ruin) {
        s.prepare(8);
        int wallH = 8;
        double decay = ruin ? 0.07 : 0.0;
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                boolean inside = Math.abs(dx) <= 4 && Math.abs(dz) <= 5;
                boolean shellX = Math.abs(dx) == 4;
                boolean shellZ = Math.abs(dz) == 5;
                if (inside && (shellX || shellZ)) {
                    boolean corner = shellX && shellZ;
                    for (int h = 1; h <= wallH; h++) {
                        if (ruin && s.r.nextDouble() < Math.max(0, h - 2) * decay) continue;
                        boolean door = dz == 5 && dx == 0 && h <= 3;
                        if (door) continue;
                        Material m = corner ? s.pal.trim() : s.brick();
                        boolean row = h == 3 || h == 4 || h == 6 || h == 7;
                        if (!corner && row && Math.floorMod(shellX ? dz : dx, 3) == 1) m = s.pal.light();
                        s.b(dx, h, dz, m);
                    }
                    if (shellZ) {
                        for (int h = wallH + 1; h < roofHeight(wallH, dx); h++) {
                            if (!(ruin && s.r.nextDouble() < 0.4)) s.b(dx, h, dz, s.brick());
                        }
                    }
                } else if (inside && Math.abs(dx) <= 3 && Math.abs(dz) <= 4) {
                    s.b(dx, 4, dz, s.pal.floor());
                }
                int ry = roofHeight(wallH, dx);
                if (Math.abs(dx) <= 5 && !(ruin && s.r.nextDouble() < 0.45)) s.b(dx, ry, dz, s.pal.roof());
            }
        }
        s.b(-3, 4, 2, Material.AIR);
        for (int h = 1; h <= 4; h++) s.bd(-3, h, 2, LADDER_E);
        if (!ruin) {
            for (int h = 1; h <= wallH + 7; h++) s.b(3, h, -4, Material.COBBLESTONE);
        }
        s.bd(2, 5, -3, CHEST);
    }

    private static int roofHeight(int wallH, int dx) {
        return wallH + 1 + (4 - Math.min(4, Math.abs(dx))) + (Math.abs(dx) > 4 ? -1 : 0);
    }

    /** Slender gothic tower with a tall slate spire. */
    private void gothicTower(Site s) {
        s.prepare(6);
        int height = 24 + s.r.nextInt(14);
        int lastFloor = 0;
        for (int y = 9; y < height - 2; y += 9) lastFloor = y;
        for (int h = 1; h <= height; h++) {
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    boolean shell = Math.abs(dx) == 3 || Math.abs(dz) == 3;
                    boolean corner = Math.abs(dx) == 3 && Math.abs(dz) == 3;
                    if (shell) {
                        boolean door = dz == 3 && dx == 0 && h <= 3;
                        if (door) continue;
                        Material m = corner ? s.pal.trim() : s.brick();
                        if (!corner && h % 6 == 3 && h > 3 && (dx == 0 || dz == 0)) m = s.pal.light();
                        s.b(dx, h, dz, m);
                    } else if (h % 9 == 0 && h <= lastFloor && !(dx == 0 && dz == -2)) {
                        s.b(dx, h, dz, s.pal.floor());
                    }
                }
            }
        }
        for (int h = 1; h <= lastFloor; h++) s.bd(0, h, -2, LADDER_S);
        for (int k = 0; k < 15; k++) {
            int rr = (int) Math.round(3.4 * (1.0 - k / 15.0));
            s.box(-rr, height + 1 + k, -rr, rr, height + 1 + k, rr, s.pal.roof());
        }
        for (int k = 0; k < 5; k++) s.b(0, height + 16 + k, 0, s.pal.rare());
        s.bd(2, 1, 2, CHEST);
        if (lastFloor >= 9) s.bd(-2, lastFloor + 1, 2, CHEST);
    }

    /** Dark outpost: low curtain wall around a three-storey keep with turrets. */
    private void gothicKeep(Site s) {
        s.prepare(9);
        for (int dx = -8; dx <= 8; dx++) {
            for (int dz = -8; dz <= 8; dz++) {
                if (Math.abs(dx) != 8 && Math.abs(dz) != 8) continue;
                boolean gate = dz == 8 && Math.abs(dx) <= 1;
                for (int h = 1; h <= 3; h++) {
                    if (gate && h <= 3) continue;
                    s.b(dx, h, dz, s.brick());
                }
                if (!gate && ((dx + dz) & 1) == 0) s.b(dx, 4, dz, s.brick());
            }
        }
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                boolean shell = Math.abs(dx) == 4 || Math.abs(dz) == 4;
                boolean corner = Math.abs(dx) == 4 && Math.abs(dz) == 4;
                for (int h = 1; h <= 12; h++) {
                    if (shell) {
                        boolean door = dz == 4 && dx == 0 && h <= 3;
                        if (door) continue;
                        Material m = corner ? s.pal.trim() : s.brick();
                        if (!corner && (h == 3 || h == 7 || h == 11) && (dx == 0 || dz == 0) && !(dz == 4 && dx == 0)) {
                            m = s.pal.light();
                        }
                        s.b(dx, h, dz, m);
                    } else if ((h == 5 || h == 9 || h == 12) && !(dx == 0 && dz == -3)) {
                        s.b(dx, h, dz, s.pal.floor());
                    }
                }
                if (corner) {
                    for (int h = 13; h <= 16; h++) s.b(dx, h, dz, s.pal.trim());
                    s.b(dx, 17, dz, s.pal.roof());
                } else if (shell && ((dx + dz) & 1) == 0) {
                    s.b(dx, 13, dz, s.brick());
                }
            }
        }
        for (int h = 1; h <= 11; h++) s.bd(0, h, -3, LADDER_S);
        s.bd(2, 6, 2, CHEST);
        s.bd(-2, 10, 2, CHEST);
    }
}
