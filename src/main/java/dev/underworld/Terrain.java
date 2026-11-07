package dev.underworld;

import org.bukkit.Material;
import org.bukkit.block.Biome;
import org.bukkit.util.noise.SimplexOctaveGenerator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * All terrain maths lives here so the chunk generator, the structure populator
 * and the runtime code (landing spots, guards) always agree with each other.
 *
 * Layout: a hub (BASE) around 0,0, then four big biomes:
 * east = Crystal Wasteland, south = The Crack, west = Snow Soul, north = Valley of Dark.
 */
public final class Terrain {

    public static final int SEA = 20;
    public static final int CELL = 64;
    /** Surface marker: no blocks at all in this column (a hole to the void). */
    public static final int VOID = Integer.MIN_VALUE;
    public static final int ABSENT = Integer.MIN_VALUE;
    public static final int HUB_RADIUS = 520;
    public static final int WORLD_HALF = 2400;
    public static final int PLATEAU = 150;
    public static final int VALLEY_FLOOR = 60;

    public enum Zone { BASE, CRYSTAL, CRACK, SNOW, DARK }

    public enum FeatureType { NONE, SPIRE, FORTRESS, TOWER, MAZE, RUIN, OUTPOST, HOUSE, MEGA_CASTLE, KINGDOM }

    /** a = radius (spire) or half-size (structures); b = height (spire only). */
    public record Feature(FeatureType type, Zone zone, int x, int z, int baseY, int a, int b) { }

    /** hollow = only a thin cap (powder snow) with nothing underneath. */
    public record Column(int surface, Material top, Material filler, int bridgeY, boolean hollow) { }

    public record Crystal(int top, Material material) { }

    public record Tree(int x, int z, int baseY, int height, int radius, int trunk) { }

    private static final Map<Long, Terrain> CACHE = new ConcurrentHashMap<>();

    public static Terrain get(long seed) {
        return CACHE.computeIfAbsent(seed, Terrain::new);
    }

    private static final Material[] CRYSTAL_MATS = {
            Material.CYAN_STAINED_GLASS, Material.LIGHT_BLUE_STAINED_GLASS, Material.PURPLE_STAINED_GLASS,
            Material.MAGENTA_STAINED_GLASS, Material.GLASS
    };
    private static final int[] NO_CRYSTAL = new int[0];
    private static final Tree NO_TREE = new Tree(0, 0, 0, 0, 0, 0);

    private final long seed;
    private final SimplexOctaveGenerator base;
    private final SimplexOctaveGenerator fine;
    private final SimplexOctaveGenerator lake;
    private final SimplexOctaveGenerator island;
    private final SimplexOctaveGenerator valleyNoise;
    private final SimplexOctaveGenerator patch;
    private final SimplexOctaveGenerator jag;
    private final SimplexOctaveGenerator warpA;
    private final SimplexOctaveGenerator warpB;
    private final SimplexOctaveGenerator crackN;
    private final SimplexOctaveGenerator pitN;

    private final Map<Long, Feature> fixed = new ConcurrentHashMap<>();
    private final Map<Long, Feature> baseFeatures = new ConcurrentHashMap<>();
    private final List<Feature> bigs = new ArrayList<>();
    private final Map<Long, int[]> crystalCells = new ConcurrentHashMap<>();
    private final Map<Long, Tree> treeCells = new ConcurrentHashMap<>();

    private Terrain(long seed) {
        this.seed = seed;
        this.base = gen(seed + 11, 4, 1.0 / 200.0);
        this.fine = gen(seed + 12, 2, 1.0 / 14.0);
        this.lake = gen(seed + 13, 3, 1.0 / 170.0);
        this.island = gen(seed + 14, 2, 1.0 / 20.0);
        this.valleyNoise = gen(seed + 15, 3, 1.0 / 260.0);
        this.patch = gen(seed + 16, 2, 1.0 / 40.0);
        this.jag = gen(seed + 17, 2, 1.0 / 9.0);
        this.warpA = gen(seed + 18, 3, 1.0 / 300.0);
        this.warpB = gen(seed + 19, 3, 1.0 / 300.0);
        this.crackN = gen(seed + 20, 3, 1.0 / 90.0);
        this.pitN = gen(seed + 21, 2, 1.0 / 26.0);
        placeSites();
    }

    private static SimplexOctaveGenerator gen(long seed, int octaves, double scale) {
        SimplexOctaveGenerator g = new SimplexOctaveGenerator(seed, octaves);
        g.setScale(scale);
        return g;
    }

    private static double n(SimplexOctaveGenerator g, int x, int z) {
        return g.noise(x, z, 2.0, 0.5, true);
    }

    private static double smooth(double t) {
        if (t <= 0) return 0;
        if (t >= 1) return 1;
        return t * t * (3 - 2 * t);
    }

    private static long key(int a, int b) {
        return ((long) a << 32) ^ (b & 0xffffffffL);
    }

    private long mix(long a, long b, long c) {
        long h = seed ^ (a * 0x9E3779B97F4A7C15L) ^ (b * 0xC2B2AE3D27D4EB4FL) ^ (c * 0x165667B19E3779F9L);
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return h;
    }

    // ------------------------------------------------------------------ zones

    public Zone zone(int x, int z) {
        double wx = x + n(warpA, x, z) * 180;
        double wz = z + n(warpB, x, z) * 180;
        if (Math.hypot(wx, wz) < HUB_RADIUS) return Zone.BASE;
        if (Math.abs(wx) >= Math.abs(wz)) return wx > 0 ? Zone.CRYSTAL : Zone.SNOW;
        return wz > 0 ? Zone.CRACK : Zone.DARK;
    }

    /** null = use the configured hub biome. */
    public Biome biome(int x, int z) {
        return switch (zone(x, z)) {
            case CRYSTAL -> Biome.SOUL_SAND_VALLEY;
            case CRACK -> Biome.BASALT_DELTAS;
            case SNOW -> Biome.SNOWY_TAIGA;
            case DARK -> Biome.DEEP_DARK;
            default -> null;
        };
    }

    /** 0 = no valley, 1 = centre of a canyon (hub only). */
    public double valley(int x, int z) {
        return smooth(1.0 - Math.abs(n(valleyNoise, x, z)) / 0.07);
    }

    public double lake(int x, int z) {
        return smooth((n(lake, x, z) - 0.30) / 0.18);
    }

    // ------------------------------------------------------------------ columns

    public Column column(int x, int z) {
        Feature site = siteCovering(x, z, 2);
        if (site != null) {
            return new Column(site.baseY(), floorTop(site.zone()), Material.BLACKSTONE, ABSENT, false);
        }
        return rawColumn(x, z);
    }

    private static Material floorTop(Zone z) {
        return switch (z) {
            case CRYSTAL -> Material.SOUL_SAND;
            case SNOW -> Material.SNOW_BLOCK;
            case DARK -> Material.DEEPSLATE;
            default -> Material.BLACKSTONE;
        };
    }

    public Column rawColumn(int x, int z) {
        return switch (zone(x, z)) {
            case CRYSTAL -> crystalColumn(x, z);
            case CRACK -> crackColumn(x, z);
            case SNOW -> snowColumn(x, z);
            case DARK -> darkColumn(x, z);
            default -> baseColumn(x, z);
        };
    }

    private Column baseColumn(int x, int z) {
        double rim = 62 + n(base, x, z) * 20;
        double h = rim + n(fine, x, z) * 3;

        double v = smooth(1.0 - Math.abs(n(valleyNoise, x, z)) / 0.07);
        if (v > 0) {
            h -= v * Math.min(60, Math.max(0, h - 26));
        }

        double l = smooth((n(lake, x, z) - 0.30) / 0.18);
        if (l > 0) {
            h += (8 - h) * l;
        }
        if (l > 0.1) {
            double isl = n(island, x, z);
            if (isl > 0.5) {
                h = Math.max(h, SEA + 2 + (isl - 0.5) * 12);
            }
        }

        int s = (int) Math.round(h);

        Material top;
        Material filler = Material.BLACKSTONE;
        if (s <= SEA + 1) {
            top = (((x * 73856093) ^ (z * 19349663)) & 3) == 0 ? Material.MAGMA_BLOCK : Material.BASALT;
            filler = Material.BASALT;
        } else if (v > 0.25) {
            top = Material.SOUL_SOIL;
        } else {
            double p = n(patch, x, z);
            top = p > 0.4 ? Material.NETHERRACK : (p < -0.4 ? Material.BASALT : Material.BLACKSTONE);
        }

        int bridge = ABSENT;
        if (v > 0.05 && (Math.floorMod(z, 160) < 5 || Math.floorMod(x, 160) < 5)) {
            long hh = mix(Math.floorDiv(x, 20), Math.floorDiv(z, 20), 7);
            if (Long.remainderUnsigned(hh, 100) >= 28) {
                int deck = (int) Math.floor(rim);
                if (deck > s) bridge = deck;
            }
        }
        return new Column(s, top, filler, bridge, false);
    }

    /** Crystal Wasteland: soul sand dunes. */
    private Column crystalColumn(int x, int z) {
        int s = (int) Math.round(62 + n(base, x, z) * 7 + n(fine, x, z) * 2);
        Material top = n(patch, x, z) > 0.35 ? Material.SOUL_SOIL : Material.SOUL_SAND;
        return new Column(s, top, Material.SOUL_SOIL, ABSENT, false);
    }

    /** The Crack: the further from the hub, the wider the gaps. */
    private Column crackColumn(int x, int z) {
        double d = Math.hypot(x, z);
        double t = Math.max(0, Math.min(1, (d - HUB_RADIUS) / (WORLD_HALF - HUB_RADIUS)));
        double width = 0.02 + 0.24 * Math.pow(t, 1.1);
        if (Math.abs(n(crackN, x, z)) < width) {
            return new Column(VOID, Material.AIR, Material.AIR, ABSENT, false);
        }
        int s = (int) Math.round(62 + n(base, x, z) * 8 + n(fine, x, z) * 2);
        double p = n(patch, x, z);
        Material top = p > 0.3 ? Material.NETHERRACK : (p < -0.45 ? Material.CRYING_OBSIDIAN : Material.BLACKSTONE);
        return new Column(s, top, Material.BLACKSTONE, ABSENT, false);
    }

    /** Snow Soul: snowy hills with powder snow pits that drop into the void. */
    private Column snowColumn(int x, int z) {
        int s = (int) Math.round(64 + n(base, x, z) * 14 + n(fine, x, z) * 2);
        boolean pit = n(pitN, x, z) > 0.62;
        return new Column(s, Material.SNOW_BLOCK, Material.PACKED_ICE, ABSENT, pit);
    }

    /** Valley of Dark: a maze of deep corridors under a sculk plateau. */
    private Column darkColumn(int x, int z) {
        int lx = Math.floorMod(x, 12);
        int lz = Math.floorMod(z, 12);
        int gx = Math.floorDiv(x, 12);
        int gz = Math.floorDiv(z, 12);
        boolean open;
        if (lx < 7 && lz < 7) open = true;
        else if (lx >= 7 && lz < 7) open = edge(gx, gz, 0);
        else if (lx < 7) open = edge(gx, gz, 1);
        else open = false;
        if (open) {
            Material top = n(patch, x, z) > 0.45 ? Material.SCULK : Material.DEEPSLATE;
            return new Column(VALLEY_FLOOR, top, Material.DEEPSLATE, ABSENT, false);
        }
        return new Column(PLATEAU, Material.SCULK, Material.BLACKSTONE, ABSENT, false);
    }

    private boolean edge(int gx, int gz, int dir) {
        return Long.remainderUnsigned(mix(gx, gz, 100 + dir), 100) < 62;
    }

    // ------------------------------------------------------------------ fixed sites

    private static List<FeatureType> plan(Object... pairs) {
        List<FeatureType> list = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            FeatureType type = (FeatureType) pairs[i];
            int count = (Integer) pairs[i + 1];
            for (int k = 0; k < count; k++) list.add(type);
        }
        return list;
    }

    private void placeSites() {
        Random r = new Random(seed * 6364136223846793005L + 1442695040888963407L);

        bigs.add(new Feature(FeatureType.MEGA_CASTLE, Zone.CRYSTAL, 1900, 0, 66, 100, 0));
        bigs.add(new Feature(FeatureType.KINGDOM, Zone.DARK, 0, -2000, VALLEY_FLOOR, 170, 0));

        // Crystal Wasteland: 15 structures + 2 outposts (+ the mega castle)
        place(r, Zone.CRYSTAL, plan(FeatureType.RUIN, 6, FeatureType.TOWER, 4, FeatureType.FORTRESS, 3,
                FeatureType.MAZE, 2, FeatureType.OUTPOST, 2));
        // The Crack: 10 structures
        place(r, Zone.CRACK, plan(FeatureType.RUIN, 4, FeatureType.OUTPOST, 3, FeatureType.TOWER, 2,
                FeatureType.FORTRESS, 1));
        // Snow Soul: 20 structures
        place(r, Zone.SNOW, plan(FeatureType.HOUSE, 10, FeatureType.RUIN, 5, FeatureType.TOWER, 2,
                FeatureType.OUTPOST, 3));
        // Valley of Dark: 30 structures (+ the kingdom)
        place(r, Zone.DARK, plan(FeatureType.RUIN, 12, FeatureType.TOWER, 6, FeatureType.OUTPOST, 5,
                FeatureType.FORTRESS, 4, FeatureType.HOUSE, 3));
    }

    private static int halfOf(FeatureType t) {
        return switch (t) {
            case FORTRESS, MAZE -> 15;
            case TOWER -> 4;
            case RUIN -> 10;
            case OUTPOST -> 8;
            case HOUSE -> 6;
            default -> 8;
        };
    }

    private void place(Random r, Zone zone, List<FeatureType> types) {
        for (FeatureType type : types) {
            int half = halfOf(type);
            for (int attempt = 0; attempt < 800; attempt++) {
                int x = r.nextInt(2 * WORLD_HALF - 400) - (WORLD_HALF - 200);
                int z = r.nextInt(2 * WORLD_HALF - 400) - (WORLD_HALF - 200);
                if (zone(x, z) != zone) continue;
                if (Math.hypot(x, z) < HUB_RADIUS + 100) continue;
                if (tooClose(x, z, half)) continue;
                int floor = VALLEY_FLOOR;
                if (zone != Zone.DARK) {
                    int s = rawColumn(x, z).surface();
                    floor = s == VOID ? 62 : s;
                }
                fixed.put(key(Math.floorDiv(x, CELL), Math.floorDiv(z, CELL)),
                        new Feature(type, zone, x, z, floor, half, 0));
                break;
            }
        }
    }

    private boolean tooClose(int x, int z, int half) {
        for (Feature f : fixed.values()) {
            if (Math.max(Math.abs(x - f.x()), Math.abs(z - f.z())) < 150 + half) return true;
        }
        for (Feature b : bigs) {
            if (Math.max(Math.abs(x - b.x()), Math.abs(z - b.z())) < b.a() + 90 + half) return true;
        }
        return false;
    }

    private Feature siteCovering(int x, int z, int margin) {
        for (Feature b : bigs) {
            if (Math.abs(x - b.x()) <= b.a() + margin && Math.abs(z - b.z()) <= b.a() + margin) return b;
        }
        int cx = Math.floorDiv(x, CELL);
        int cz = Math.floorDiv(z, CELL);
        for (int i = -1; i <= 1; i++) {
            for (int j = -1; j <= 1; j++) {
                Feature f = fixed.get(key(cx + i, cz + j));
                if (f != null && Math.abs(x - f.x()) <= f.a() + margin && Math.abs(z - f.z()) <= f.a() + margin) {
                    return f;
                }
            }
        }
        return null;
    }

    public boolean nearSite(int x, int z, int margin) {
        return siteCovering(x, z, margin) != null;
    }

    /** The mega castle / kingdom covering this column, or null. */
    public Feature big(int x, int z) {
        for (Feature b : bigs) {
            if (Math.abs(x - b.x()) <= b.a() && Math.abs(z - b.z()) <= b.a()) return b;
        }
        return null;
    }

    // ------------------------------------------------------------------ hub features (random, one per cell)

    public Feature feature(int cellX, int cellZ) {
        long k = key(cellX, cellZ);
        Feature f = fixed.get(k);
        if (f != null) return f;
        return baseFeatures.computeIfAbsent(k, kk -> compute(cellX, cellZ));
    }

    private Feature compute(int cx, int cz) {
        long h = mix(cx, cz, 1);
        int px = cx * CELL + 20 + (int) Long.remainderUnsigned(h, 25);
        int pz = cz * CELL + 20 + (int) Long.remainderUnsigned(h >>> 16, 25);
        int roll = (int) Long.remainderUnsigned(h >>> 32, 100);

        Feature none = new Feature(FeatureType.NONE, Zone.BASE, px, pz, 0, 0, 0);
        if (zone(px, pz) != Zone.BASE) return none;

        FeatureType type;
        if (roll < 34) type = FeatureType.SPIRE;
        else if (roll < 44) type = FeatureType.FORTRESS;
        else if (roll < 52) type = FeatureType.TOWER;
        else if (roll < 60) type = FeatureType.MAZE;
        else return none;

        if (type == FeatureType.SPIRE) {
            Column c = column(px, pz);
            if (c.surface() <= SEA + 3 || valley(px, pz) > 0.02) return none;
            int radius = 7 + (int) Long.remainderUnsigned(mix(cx, cz, 2), 8);
            int height = 80 + (int) Long.remainderUnsigned(mix(cx, cz, 3), 90);
            return new Feature(FeatureType.SPIRE, Zone.BASE, px, pz, c.surface(), radius, height);
        }

        int half = type == FeatureType.TOWER ? 4 : 15;
        int lo = Integer.MAX_VALUE;
        int hi = Integer.MIN_VALUE;
        for (int dx = -half; dx <= half; dx += half) {
            for (int dz = -half; dz <= half; dz += half) {
                int x = px + dx;
                int z = pz + dz;
                int s = column(x, z).surface();
                if (s <= SEA + 3 || valley(x, z) > 0.02) return none;
                lo = Math.min(lo, s);
                hi = Math.max(hi, s);
            }
        }
        if (hi - lo > (type == FeatureType.TOWER ? 8 : 14)) return none;
        return new Feature(type, Zone.BASE, px, pz, hi, half, 0);
    }

    public int spireTop(int x, int z, Feature f) {
        if (f.type() != FeatureType.SPIRE) return ABSENT;
        double dx = x - f.x();
        double dz = z - f.z();
        double d = Math.sqrt(dx * dx + dz * dz) + n(jag, x, z) * 1.5;
        if (d >= f.a()) return ABSENT;
        double t = 1.0 - Math.max(d, 0) / f.a();
        return f.baseY() + (int) (f.b() * Math.pow(t, 1.6) + n(jag, z + 4096, x) * 5);
    }

    // ------------------------------------------------------------------ decoration

    /** Glass crystal spikes in the Crystal Wasteland. */
    public Crystal crystalAt(int x, int z) {
        int gx = Math.floorDiv(x, 24);
        int gz = Math.floorDiv(z, 24);
        int[] c = crystalCells.computeIfAbsent(key(gx, gz), k -> computeCrystal(gx, gz));
        if (c.length == 0) return null;
        double dx = x - c[0];
        double dz = z - c[1];
        double d = Math.max(Math.abs(dx), Math.abs(dz)) * 0.6 + Math.sqrt(dx * dx + dz * dz) * 0.4;
        if (d >= c[2]) return null;
        int top = c[4] + (int) (c[3] * Math.pow(1.0 - d / c[2], 1.1));
        return new Crystal(top, CRYSTAL_MATS[c[5]]);
    }

    private int[] computeCrystal(int gx, int gz) {
        long h = mix(gx, gz, 31);
        if (Long.remainderUnsigned(h, 100) >= 70) return NO_CRYSTAL;
        int cx = gx * 24 + 6 + (int) Long.remainderUnsigned(h >>> 8, 12);
        int cz = gz * 24 + 6 + (int) Long.remainderUnsigned(h >>> 20, 12);
        if (zone(cx, cz) != Zone.CRYSTAL || nearSite(cx, cz, 8)) return NO_CRYSTAL;
        Column col = rawColumn(cx, cz);
        if (col.surface() == VOID) return NO_CRYSTAL;
        int radius = 2 + (int) Long.remainderUnsigned(h >>> 32, 3);
        int height = 8 + (int) Long.remainderUnsigned(h >>> 40, 28);
        int mat = (int) Long.remainderUnsigned(h >>> 50, CRYSTAL_MATS.length);
        return new int[] {cx, cz, radius, height, col.surface(), mat};
    }

    /** Big spruce trees in the Snow Soul. */
    public Tree treeAt(int x, int z) {
        int gx = Math.floorDiv(x, 28);
        int gz = Math.floorDiv(z, 28);
        Tree t = treeCells.computeIfAbsent(key(gx, gz), k -> computeTree(gx, gz));
        return t.height() == 0 ? null : t;
    }

    private Tree computeTree(int gx, int gz) {
        long h = mix(gx, gz, 41);
        if (Long.remainderUnsigned(h, 100) >= 55) return NO_TREE;
        int cx = gx * 28 + 9 + (int) Long.remainderUnsigned(h >>> 8, 11);
        int cz = gz * 28 + 9 + (int) Long.remainderUnsigned(h >>> 20, 11);
        if (zone(cx, cz) != Zone.SNOW || nearSite(cx, cz, 12)) return NO_TREE;
        Column c = rawColumn(cx, cz);
        if (c.surface() == VOID || c.hollow()) return NO_TREE;
        int height = 24 + (int) Long.remainderUnsigned(h >>> 32, 19);
        int radius = 5 + (int) Long.remainderUnsigned(h >>> 44, 4);
        return new Tree(cx, cz, c.surface(), height, radius, height >= 32 ? 1 : 0);
    }

    /** Sculk plateau decoration: 1 = shrieker (summons wardens), 2 = sensor. */
    public int deco(int x, int z) {
        int r = (int) Long.remainderUnsigned(mix(x, z, 55), 100);
        if (r < 2) return 1;
        if (r < 5) return 2;
        return 0;
    }

    // ------------------------------------------------------------------ queries

    /** Highest solid block of natural terrain (including spires), or VOID. */
    public int topY(int x, int z) {
        int s = column(x, z).surface();
        if (s == VOID) return VOID;
        Feature f = feature(Math.floorDiv(x, CELL), Math.floorDiv(z, CELL));
        int sp = spireTop(x, z, f);
        return sp == ABSENT ? s : Math.max(s, sp);
    }

    public boolean isLand(int x, int z) {
        Column c = column(x, z);
        return c.surface() != VOID && !c.hollow() && c.surface() > SEA + 1;
    }

    /** Somewhere a guard can stand: land, and in the Valley of Dark only the corridors. */
    public boolean walkable(int x, int z) {
        Column c = column(x, z);
        if (c.surface() == VOID || c.hollow() || c.surface() <= SEA + 1) return false;
        return c.surface() < PLATEAU - 20 || zone(x, z) != Zone.DARK;
    }
}
