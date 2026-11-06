package dev.underworld;

import org.bukkit.Material;
import org.bukkit.block.Biome;
import org.bukkit.util.noise.SimplexOctaveGenerator;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * All terrain maths lives here so the chunk generator, the structure populator
 * and the runtime code (landing spots, guards) always agree with each other.
 */
public final class Terrain {

    /** Lava level. */
    public static final int SEA = 20;
    /** Size of one feature cell in blocks (one spire / fortress / tower / maze per cell at most). */
    public static final int CELL = 64;
    /** Marker for "nothing here". */
    public static final int ABSENT = Integer.MIN_VALUE;

    public enum FeatureType { NONE, SPIRE, FORTRESS, TOWER, MAZE }

    /** a = radius (spire) or half-size (structures); b = height (spire only). */
    public record Feature(FeatureType type, int x, int z, int baseY, int a, int b) { }

    public record Column(int surface, Material top, Material filler, int bridgeY) { }

    private static final Map<Long, Terrain> CACHE = new ConcurrentHashMap<>();

    public static Terrain get(long seed) {
        return CACHE.computeIfAbsent(seed, Terrain::new);
    }

    private final long seed;
    private final SimplexOctaveGenerator base;
    private final SimplexOctaveGenerator fine;
    private final SimplexOctaveGenerator lake;
    private final SimplexOctaveGenerator island;
    private final SimplexOctaveGenerator valleyNoise;
    private final SimplexOctaveGenerator patch;
    private final SimplexOctaveGenerator jag;
    private final Map<Long, Feature> features = new ConcurrentHashMap<>();

    private Terrain(long seed) {
        this.seed = seed;
        this.base = gen(seed + 11, 4, 1.0 / 200.0);
        this.fine = gen(seed + 12, 2, 1.0 / 14.0);
        this.lake = gen(seed + 13, 3, 1.0 / 170.0);
        this.island = gen(seed + 14, 2, 1.0 / 20.0);
        this.valleyNoise = gen(seed + 15, 3, 1.0 / 260.0);
        this.patch = gen(seed + 16, 2, 1.0 / 40.0);
        this.jag = gen(seed + 17, 2, 1.0 / 9.0);
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

    private long mix(long a, long b, long c) {
        long h = seed ^ (a * 0x9E3779B97F4A7C15L) ^ (b * 0xC2B2AE3D27D4EB4FL) ^ (c * 0x165667B19E3779F9L);
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return h;
    }

    /** 0 = no valley, 1 = centre of a canyon. */
    public double valley(int x, int z) {
        return smooth(1.0 - Math.abs(n(valleyNoise, x, z)) / 0.07);
    }

    /** 0 = no lava lake, 1 = deep in a lake. */
    public double lake(int x, int z) {
        return smooth((n(lake, x, z) - 0.30) / 0.18);
    }

    public Column column(int x, int z) {
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

        // Ancient bridges over valleys, some segments broken
        int bridge = ABSENT;
        if (v > 0.05 && (Math.floorMod(z, 160) < 5 || Math.floorMod(x, 160) < 5)) {
            long hh = mix(Math.floorDiv(x, 20), Math.floorDiv(z, 20), 7);
            if (Long.remainderUnsigned(hh, 100) >= 28) {
                int deck = (int) Math.floor(rim);
                if (deck > s) bridge = deck;
            }
        }
        return new Column(s, top, filler, bridge);
    }

    public Biome biome(int x, int z) {
        if (valley(x, z) > 0.2) return Biome.SOUL_SAND_VALLEY;
        if (lake(x, z) > 0.05) return Biome.BASALT_DELTAS;
        return Biome.NETHER_WASTES;
    }

    // ------------------------------------------------------------------ features

    public Feature feature(int cellX, int cellZ) {
        long key = ((long) cellX << 32) ^ (cellZ & 0xffffffffL);
        return features.computeIfAbsent(key, k -> compute(cellX, cellZ));
    }

    private Feature compute(int cx, int cz) {
        long h = mix(cx, cz, 1);
        int px = cx * CELL + 20 + (int) Long.remainderUnsigned(h, 25);
        int pz = cz * CELL + 20 + (int) Long.remainderUnsigned(h >>> 16, 25);
        int roll = (int) Long.remainderUnsigned(h >>> 32, 100);

        FeatureType type;
        if (roll < 34) type = FeatureType.SPIRE;
        else if (roll < 44) type = FeatureType.FORTRESS;
        else if (roll < 52) type = FeatureType.TOWER;
        else if (roll < 60) type = FeatureType.MAZE;
        else type = FeatureType.NONE;

        Feature none = new Feature(FeatureType.NONE, px, pz, 0, 0, 0);
        if (type == FeatureType.NONE) return none;

        if (type == FeatureType.SPIRE) {
            Column c = column(px, pz);
            if (c.surface() <= SEA + 3 || valley(px, pz) > 0.02) return none;
            int radius = 7 + (int) Long.remainderUnsigned(mix(cx, cz, 2), 8);
            int height = 80 + (int) Long.remainderUnsigned(mix(cx, cz, 3), 90);
            return new Feature(FeatureType.SPIRE, px, pz, c.surface(), radius, height);
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
        return new Feature(type, px, pz, hi, half, 0);
    }

    /** Top Y of a spire at this column, or ABSENT. */
    public int spireTop(int x, int z, Feature f) {
        if (f.type() != FeatureType.SPIRE) return ABSENT;
        double dx = x - f.x();
        double dz = z - f.z();
        double d = Math.sqrt(dx * dx + dz * dz) + n(jag, x, z) * 1.5;
        if (d >= f.a()) return ABSENT;
        double t = 1.0 - Math.max(d, 0) / f.a();
        return f.baseY() + (int) (f.b() * Math.pow(t, 1.6) + n(jag, z + 4096, x) * 5);
    }

    /** Highest solid block of natural terrain (including spires) at this column. */
    public int topY(int x, int z) {
        int s = column(x, z).surface();
        Feature f = feature(Math.floorDiv(x, CELL), Math.floorDiv(z, CELL));
        int sp = spireTop(x, z, f);
        return sp == ABSENT ? s : Math.max(s, sp);
    }

    public boolean isLand(int x, int z) {
        return column(x, z).surface() > SEA + 1;
    }
}
