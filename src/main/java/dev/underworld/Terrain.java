package dev.underworld;

import org.bukkit.Material;
import org.bukkit.block.Biome;
import org.bukkit.util.noise.SimplexOctaveGenerator;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * All terrain maths lives here so the chunk generator, the structure populator
 * and the runtime code (landing spots, guards) always agree with each other.
 *
 * Layout: a hub (BASE) around 0,0, then four big biomes:
 * east = Crystal Wasteland, south = The Crack, west = Snow Soul, north = Valley of Dark.
 */
public final class Terrain {

    public static final int SEA = 20;
    /** One structure roll per 384x384 area (about 40% have one): roughly one every 600 blocks. */
    public static final int STRUCT_CELL = 384;
    public static final int STRUCT_CHANCE = 40;
    /** Mega castles / kingdoms: at most one per 3600x3600 area. */
    public static final int MEGA_CELL = 3600;
    /** Surface marker: no blocks at all in this column (a hole to the void). */
    public static final int VOID = Integer.MIN_VALUE;
    public static final int ABSENT = Integer.MIN_VALUE;
    public static final int HUB_RADIUS = 520;
    public static final int PLATEAU = 150;
    public static final int VALLEY_FLOOR = 60;

    public enum Zone { BASE, CRYSTAL, CRACK, SNOW, DARK }

    public enum FeatureType { NONE, SPIRE, FORTRESS, TOWER, MAZE, RUIN, OUTPOST, HOUSE, OBELISK, MEGA_CASTLE, KINGDOM }

    /** a = radius (spire) or half-size (structures); b = height (spire only). */
    public record Feature(FeatureType type, Zone zone, int x, int z, int baseY, int a, int b) { }

    /** hollow = only a thin cap (powder snow) with nothing underneath. */
    public record Column(int surface, Material top, Material filler, int bridgeY, boolean hollow) { }

    /** A leaning crystal blade: base point, unit direction, length, base radius. */
    public record Blade(double bx, double by, double bz, double dx, double dy, double dz, double len, double rad) { }

    public record Cluster(int cx, int cz, int baseY, Blade[] blades) { }

    /** hit[i] = a crystal block at y = base + i. */
    public record CrystalColumn(int base, boolean[] hit) { }

    public record Tree(int x, int z, int baseY, int height, int radius, int trunk) { }

    private static final Map<Long, Terrain> CACHE = new ConcurrentHashMap<>();

    public static Terrain get(long seed) {
        return CACHE.computeIfAbsent(seed, Terrain::new);
    }

    private static final int[] NO_SPIRE = new int[0];
    private static final Cluster NO_CLUSTER = new Cluster(0, 0, 0, new Blade[0]);
    private static final int CLUSTER_CELL = 64;
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

    private final Map<Long, Feature> structCache = new ConcurrentHashMap<>();
    private final Map<Long, Feature> megaCache = new ConcurrentHashMap<>();
    private final Map<Long, int[]> spireCache = new ConcurrentHashMap<>();
    private final Map<Long, Cluster> clusterCells = new ConcurrentHashMap<>();
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

    /** null = use the configured hub biome. Teal fog, red fog, purple-grey fog, teal fog. */
    public Biome biome(int x, int z) {
        return switch (zone(x, z)) {
            case CRYSTAL -> Biome.SOUL_SAND_VALLEY;
            case CRACK -> Biome.CRIMSON_FOREST;
            case SNOW -> Biome.END_HIGHLANDS;
            case DARK -> Biome.SOUL_SAND_VALLEY;
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
            case CRYSTAL -> crystalZoneColumn(x, z);
            case CRACK -> crackColumn(x, z);
            case SNOW -> snowColumn(x, z);
            case DARK -> darkColumn(x, z);
            default -> baseColumn(x, z);
        };
    }

    /** Flat plateaus with short risers, like the terraced hills of the real Underworld. */
    private static double terrace(double h, double step) {
        double q = h / step;
        double f = Math.floor(q);
        return step * (f + smooth((q - f - 0.35) / 0.3));
    }

    /** Hub: terraced soul sand / soul soil hills, lava lakes, canyons with old bridges. */
    private Column baseColumn(int x, int z) {
        double rim = 62 + n(base, x, z) * 20;
        double h = terrace(rim + n(fine, x, z) * 3, 3);

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
        Material filler = Material.SOUL_SOIL;
        if (s <= SEA + 1) {
            top = (((x * 73856093) ^ (z * 19349663)) & 3) == 0 ? Material.MAGMA_BLOCK : Material.BASALT;
            filler = Material.BASALT;
        } else if (v > 0.25) {
            top = Material.SOUL_SOIL;
        } else {
            double p = n(patch, x, z);
            top = p > 0.15 ? Material.SOUL_SAND : (p < -0.55 ? Material.BLACKSTONE : Material.SOUL_SOIL);
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

    /** Crystal Wasteland: dark soul soil terraces with soul sand streaks (crystals are added on top). */
    private Column crystalZoneColumn(int x, int z) {
        int s = (int) Math.round(terrace(62 + n(base, x, z) * 8 + n(fine, x, z) * 2, 4));
        Material top = n(patch, x, z) > 0.45 ? Material.SOUL_SAND : Material.SOUL_SOIL;
        return new Column(s, top, Material.SOUL_SOIL, ABSENT, false);
    }

    /** The Crack: big flat plates of land; the further out, the wider the gaps, until only shards float. */
    private Column crackColumn(int x, int z) {
        double d = Math.hypot(x, z);
        double t = 1.0 - Math.exp(-Math.max(0, d - HUB_RADIUS) / 2500.0);
        double width = 0.02 + 0.40 * Math.pow(t, 1.1);
        if (Math.abs(n(crackN, x, z)) < width) {
            return new Column(VOID, Material.AIR, Material.AIR, ABSENT, false);
        }
        int s = (int) Math.round(terrace(62 + n(base, x, z) * 10, 6));
        double p = n(patch, x, z);
        Material top = p > 0.3 ? Material.NETHERRACK : (p < -0.6 ? Material.CRYING_OBSIDIAN : Material.SOUL_SOIL);
        return new Column(s, top, Material.SOUL_SOIL, ABSENT, false);
    }

    /** Snow Soul: smooth snowy terraces with powder snow pits that drop into the void. */
    private Column snowColumn(int x, int z) {
        int s = (int) Math.round(terrace(64 + n(base, x, z) * 14 + n(fine, x, z) * 1.5, 3));
        boolean pit = n(pitN, x, z) > 0.62;
        return new Column(s, Material.SNOW_BLOCK, Material.PACKED_ICE, ABSENT, pit);
    }

    /** Valley of Dark: enormous brick canyon walls (150 high) around wide corridors (60 floor). */
    private Column darkColumn(int x, int z) {
        int lx = Math.floorMod(x, 17);
        int lz = Math.floorMod(z, 17);
        int gx = Math.floorDiv(x, 17);
        int gz = Math.floorDiv(z, 17);
        boolean open;
        if (lx < 11 && lz < 11) open = true;
        else if (lx >= 11 && lz < 11) open = edge(gx, gz, 0);
        else if (lx < 11) open = edge(gx, gz, 1);
        else open = false;
        if (open) {
            Material top = n(patch, x, z) > 0.3 ? Material.SOUL_SAND : Material.SOUL_SOIL;
            return new Column(VALLEY_FLOOR, top, Material.DEEPSLATE, ABSENT, false);
        }
        return new Column(PLATEAU, Material.SCULK, Material.DEEPSLATE_BRICKS, ABSENT, false);
    }

    private boolean edge(int gx, int gz, int dir) {
        return Long.remainderUnsigned(mix(gx, gz, 100 + dir), 100) < 62;
    }

    // ------------------------------------------------------------------ structures (rare, spread out)

    private static final FeatureType[] TYPES = {
            FeatureType.FORTRESS, FeatureType.TOWER, FeatureType.MAZE, FeatureType.RUIN,
            FeatureType.OUTPOST, FeatureType.HOUSE, FeatureType.OBELISK
    };

    private static int[] weights(Zone zone) {
        // order: FORTRESS, TOWER, MAZE, RUIN, OUTPOST, HOUSE, OBELISK
        return switch (zone) {
            case CRYSTAL -> new int[] {0, 20, 0, 35, 15, 0, 30};
            case CRACK -> new int[] {0, 15, 0, 35, 25, 0, 15};
            case SNOW -> new int[] {0, 8, 0, 22, 15, 35, 10};
            case DARK -> new int[] {0, 18, 0, 20, 15, 25, 12};
            default -> new int[] {20, 20, 10, 30, 12, 0, 18};
        };
    }

    private static FeatureType pickType(Zone zone, long h) {
        int[] w = weights(zone);
        int total = 0;
        for (int v : w) total += v;
        int r = (int) Long.remainderUnsigned(h, total);
        for (int i = 0; i < w.length; i++) {
            r -= w[i];
            if (r < 0) return TYPES[i];
        }
        return FeatureType.RUIN;
    }

    private static int halfOf(FeatureType t) {
        return switch (t) {
            case FORTRESS, MAZE, RUIN -> 15;
            case TOWER, OBELISK -> 6;
            case OUTPOST -> 9;
            case HOUSE -> 8;
            default -> 8;
        };
    }

    /** The structure (if any) of one 384x384 cell. Centre is always at least 50 blocks from the cell edge. */
    public Feature structure(int cx, int cz) {
        return structCache.computeIfAbsent(key(cx, cz), k -> computeStructure(cx, cz));
    }

    private Feature computeStructure(int cx, int cz) {
        Feature none = new Feature(FeatureType.NONE, Zone.BASE, cx * STRUCT_CELL, cz * STRUCT_CELL, 0, 0, 0);
        if (Long.remainderUnsigned(mix(cx, cz, 1), 100) >= STRUCT_CHANCE) return none;

        for (int attempt = 0; attempt < 4; attempt++) {
            long ha = mix(cx, cz, 10 + attempt);
            int px = cx * STRUCT_CELL + 50 + (int) Long.remainderUnsigned(ha, STRUCT_CELL - 100);
            int pz = cz * STRUCT_CELL + 50 + (int) Long.remainderUnsigned(ha >>> 24, STRUCT_CELL - 100);
            if (Math.hypot(px, pz) < 80) continue;

            Zone zone = zone(px, pz);
            FeatureType type = pickType(zone, mix(cx, cz, 20 + attempt));
            int half = halfOf(type);

            Feature mega = megaFeature(Math.floorDiv(px, MEGA_CELL), Math.floorDiv(pz, MEGA_CELL));
            if (mega.type() != FeatureType.NONE && Math.abs(px - mega.x()) <= mega.a() + 80
                    && Math.abs(pz - mega.z()) <= mega.a() + 80) continue;

            int floor = VALLEY_FLOOR;
            if (zone != Zone.DARK) {
                Column c = rawColumn(px, pz);
                if (c.surface() == VOID || c.hollow()) continue;
                floor = c.surface();
                if (zone == Zone.BASE) {
                    // hub: needs dry, fairly flat ground
                    int lo = floor;
                    int hi = floor;
                    boolean ok = floor > SEA + 3 && valley(px, pz) < 0.02;
                    for (int sx = -1; sx <= 1 && ok; sx += 2) {
                        for (int sz = -1; sz <= 1 && ok; sz += 2) {
                            int sh = rawColumn(px + sx * half, pz + sz * half).surface();
                            if (sh <= SEA + 3) ok = false;
                            lo = Math.min(lo, sh);
                            hi = Math.max(hi, sh);
                        }
                    }
                    if (!ok || hi - lo > 16) continue;
                }
            }
            return new Feature(type, zone, px, pz, floor, half, 0);
        }
        return none;
    }

    /** The mega castle (crystal) or kingdom (dark) of one 3600x3600 cell, if any. */
    private Feature megaFeature(int mx, int mz) {
        return megaCache.computeIfAbsent(key(mx, mz), k -> computeMega(mx, mz));
    }

    private Feature computeMega(int mx, int mz) {
        Feature none = new Feature(FeatureType.NONE, Zone.BASE, 0, 0, 0, 0, 0);
        if (Long.remainderUnsigned(mix(mx, mz, 200), 100) >= 70) return none;
        for (int attempt = 0; attempt < 10; attempt++) {
            long h = mix(mx, mz, 210 + attempt);
            int x = mx * MEGA_CELL + 400 + (int) Long.remainderUnsigned(h, MEGA_CELL - 800);
            int z = mz * MEGA_CELL + 400 + (int) Long.remainderUnsigned(h >>> 24, MEGA_CELL - 800);
            Zone zone = zone(x, z);
            if (zone != Zone.CRYSTAL && zone != Zone.DARK) continue;
            int half = zone == Zone.CRYSTAL ? 100 : 170;
            if (zone(x - half, z - half) != zone || zone(x + half, z - half) != zone
                    || zone(x - half, z + half) != zone || zone(x + half, z + half) != zone) continue;
            if (Math.hypot(x, z) < HUB_RADIUS + half + 150) continue;
            return new Feature(zone == Zone.CRYSTAL ? FeatureType.MEGA_CASTLE : FeatureType.KINGDOM,
                    zone, x, z, zone == Zone.CRYSTAL ? 66 : VALLEY_FLOOR, half, 0);
        }
        return none;
    }

    /** The mega castle / kingdom of the 3600x3600 cell containing this point (type NONE if none). */
    public Feature mega(int x, int z) {
        return megaFeature(Math.floorDiv(x, MEGA_CELL), Math.floorDiv(z, MEGA_CELL));
    }

    private Feature siteCovering(int x, int z, int margin) {
        Feature m = megaFeature(Math.floorDiv(x, MEGA_CELL), Math.floorDiv(z, MEGA_CELL));
        if (m.type() != FeatureType.NONE && Math.abs(x - m.x()) <= m.a() + margin
                && Math.abs(z - m.z()) <= m.a() + margin) return m;
        Feature f = structure(Math.floorDiv(x, STRUCT_CELL), Math.floorDiv(z, STRUCT_CELL));
        if (f.type() != FeatureType.NONE && Math.abs(x - f.x()) <= f.a() + margin
                && Math.abs(z - f.z()) <= f.a() + margin) return f;
        return null;
    }

    public boolean nearSite(int x, int z, int margin) {
        return siteCovering(x, z, margin) != null;
    }

    /** The mega castle / kingdom covering this column, or null. */
    public Feature big(int x, int z) {
        Feature m = megaFeature(Math.floorDiv(x, MEGA_CELL), Math.floorDiv(z, MEGA_CELL));
        if (m.type() != FeatureType.NONE && Math.abs(x - m.x()) <= m.a() && Math.abs(z - m.z()) <= m.a()) return m;
        return null;
    }

    // ------------------------------------------------------------------ hub spires (scenery)

    public int spireTop(int x, int z) {
        int gx = Math.floorDiv(x, 72);
        int gz = Math.floorDiv(z, 72);
        int[] sp = spireCache.computeIfAbsent(key(gx, gz), k -> computeSpire(gx, gz));
        if (sp.length == 0) return ABSENT;
        double dx = x - sp[0];
        double dz = z - sp[1];
        double d = Math.sqrt(dx * dx + dz * dz) + n(jag, x, z) * 1.5;
        if (d >= sp[2]) return ABSENT;
        double t = 1.0 - Math.max(d, 0) / sp[2];
        return sp[4] + (int) (sp[3] * Math.pow(t, 1.6) + n(jag, z + 4096, x) * 5);
    }

    private int[] computeSpire(int gx, int gz) {
        long h = mix(gx, gz, 60);
        if (Long.remainderUnsigned(h, 100) >= 28) return NO_SPIRE;
        int cx = gx * 72 + 20 + (int) Long.remainderUnsigned(h >>> 8, 33);
        int cz = gz * 72 + 20 + (int) Long.remainderUnsigned(h >>> 20, 33);
        if (zone(cx, cz) != Zone.BASE || nearSite(cx, cz, 20)) return NO_SPIRE;
        Column c = rawColumn(cx, cz);
        if (c.surface() <= SEA + 3 || valley(cx, cz) > 0.05) return NO_SPIRE;
        int radius = 6 + (int) Long.remainderUnsigned(h >>> 32, 8);
        int height = 70 + (int) Long.remainderUnsigned(h >>> 44, 100);
        return new int[] {cx, cz, radius, height, c.surface()};
    }

    // ------------------------------------------------------------------ decoration

    // ------------------------------------------------------------------ crystals

    public static boolean insideBlade(double px, double py, double pz, Blade b) {
        double x = px - b.bx();
        double y = py - b.by();
        double z = pz - b.bz();
        double t = x * b.dx() + y * b.dy() + z * b.dz();
        if (t < 0 || t > b.len()) return false;
        double r = b.rad() * (1.0 - t / b.len());
        double perp = x * x + y * y + z * z - t * t;
        return perp <= r * r;
    }

    /** Leaning lime-glass crystal blades grouped in clusters (dense in the Crystal Wasteland, rare in the hub). */
    public CrystalColumn crystalColumn(int x, int z) {
        int gx = Math.floorDiv(x, CLUSTER_CELL);
        int gz = Math.floorDiv(z, CLUSTER_CELL);
        Cluster c = clusterCells.computeIfAbsent(key(gx, gz), k -> computeCluster(gx, gz));
        if (c.blades().length == 0) return null;
        if (Math.abs(x - c.cx()) > 26 || Math.abs(z - c.cz()) > 26) return null;

        boolean[] hit = null;
        int base = c.baseY() - 3;
        for (Blade b : c.blades()) {
            double ex = b.bx() + b.dx() * b.len();
            double ez = b.bz() + b.dz() * b.len();
            if (distToSegmentSq(x + 0.5, z + 0.5, b.bx(), b.bz(), ex, ez) > (b.rad() + 1) * (b.rad() + 1)) continue;
            int y1 = (int) Math.floor(b.by() - b.rad());
            int y2 = (int) Math.ceil(b.by() + b.dy() * b.len() + b.rad());
            for (int y = y1; y <= y2; y++) {
                if (insideBlade(x + 0.5, y + 0.5, z + 0.5, b)) {
                    if (hit == null) hit = new boolean[100];
                    int i = y - base;
                    if (i >= 0 && i < 100) hit[i] = true;
                }
            }
        }
        return hit == null ? null : new CrystalColumn(base, hit);
    }

    private static double distToSegmentSq(double px, double pz, double ax, double az, double bx, double bz) {
        double vx = bx - ax;
        double vz = bz - az;
        double len2 = vx * vx + vz * vz;
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * vx + (pz - az) * vz) / len2));
        double qx = ax + t * vx - px;
        double qz = az + t * vz - pz;
        return qx * qx + qz * qz;
    }

    private Cluster computeCluster(int gx, int gz) {
        long h = mix(gx, gz, 31);
        int cx = gx * CLUSTER_CELL + 24 + (int) Long.remainderUnsigned(h >>> 8, 17);
        int cz = gz * CLUSTER_CELL + 24 + (int) Long.remainderUnsigned(h >>> 20, 17);
        Zone zone = zone(cx, cz);
        int chance = zone == Zone.CRYSTAL ? 80 : (zone == Zone.BASE ? 14 : 0);
        if (Long.remainderUnsigned(h, 100) >= chance) return NO_CLUSTER;
        if (nearSite(cx, cz, 26)) return NO_CLUSTER;
        Column col = rawColumn(cx, cz);
        if (col.surface() == VOID || col.hollow() || col.surface() <= SEA + 3) return NO_CLUSTER;

        Random r = new Random(h);
        int count = 4 + r.nextInt(4);
        Blade[] blades = new Blade[count];
        for (int i = 0; i < count; i++) {
            double a0 = r.nextDouble() * Math.PI * 2;
            double rr = r.nextDouble() * 3.5;
            double bx = cx + Math.cos(a0) * rr;
            double bz = cz + Math.sin(a0) * rr;
            double tilt = Math.toRadians(6 + r.nextDouble() * 26);
            double az = Math.atan2(bz - cz, bx - cx) + (r.nextDouble() - 0.5) * 1.0;
            double len = Math.min(14 + r.nextDouble() * 34, 19.0 / Math.sin(tilt));
            blades[i] = new Blade(bx, col.surface() - 2, bz,
                    Math.sin(tilt) * Math.cos(az), Math.cos(tilt), Math.sin(tilt) * Math.sin(az),
                    len, 1.7 + r.nextDouble() * 1.7);
        }
        return new Cluster(cx, cz, col.surface(), blades);
    }

    // ------------------------------------------------------------------ giant snow spruces

    /** Huge spruces with 5x5 or 7x7 trunks. */
    public Tree treeAt(int x, int z) {
        int gx = Math.floorDiv(x, 40);
        int gz = Math.floorDiv(z, 40);
        Tree t = treeCells.computeIfAbsent(key(gx, gz), k -> computeTree(gx, gz));
        return t.height() == 0 ? null : t;
    }

    private Tree computeTree(int gx, int gz) {
        long h = mix(gx, gz, 41);
        if (Long.remainderUnsigned(h, 100) >= 68) return NO_TREE;
        int cx = gx * 40 + 15 + (int) Long.remainderUnsigned(h >>> 8, 10);
        int cz = gz * 40 + 15 + (int) Long.remainderUnsigned(h >>> 20, 10);
        if (zone(cx, cz) != Zone.SNOW || nearSite(cx, cz, 14)) return NO_TREE;
        Column c = rawColumn(cx, cz);
        if (c.surface() == VOID || c.hollow()) return NO_TREE;
        int height = 44 + (int) Long.remainderUnsigned(h >>> 32, 30);
        int radius = 9 + (int) Long.remainderUnsigned(h >>> 44, 5);
        return new Tree(cx, cz, c.surface(), height, radius, height >= 62 ? 3 : 2);
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
        int sp = spireTop(x, z);
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

    // ------------------------------------------------------------------ locate

    /** Nearest structure (or mega structure) matching the filter, scanning outward cell by cell. */
    public Feature findStructure(int px, int pz, boolean mega, int maxRings, Predicate<Feature> match) {
        int cell = mega ? MEGA_CELL : STRUCT_CELL;
        int cx0 = Math.floorDiv(px, cell);
        int cz0 = Math.floorDiv(pz, cell);
        Feature best = null;
        double bestD = Double.MAX_VALUE;
        for (int r = 0; r <= maxRings; r++) {
            if (best != null && (r - 1.0) * cell > bestD) break;
            for (int i = -r; i <= r; i++) {
                for (int j = -r; j <= r; j++) {
                    if (Math.max(Math.abs(i), Math.abs(j)) != r) continue;
                    Feature f = mega ? megaFeature(cx0 + i, cz0 + j) : structure(cx0 + i, cz0 + j);
                    if (f.type() == FeatureType.NONE || !match.test(f)) continue;
                    double d = Math.hypot(f.x() - px, f.z() - pz);
                    if (d < bestD) {
                        bestD = d;
                        best = f;
                    }
                }
            }
        }
        return best;
    }

    /** Nearest point (sampled every 96 blocks) that lies in the given zone, or null. */
    public int[] findZone(int px, int pz, Zone target, int maxRadius) {
        int step = 96;
        for (int r = 0; r <= maxRadius / step; r++) {
            for (int i = -r; i <= r; i++) {
                for (int j = -r; j <= r; j++) {
                    if (Math.max(Math.abs(i), Math.abs(j)) != r) continue;
                    int x = px + i * step;
                    int z = pz + j * step;
                    if (zone(x, z) == target) return new int[] {x, z};
                }
            }
        }
        return null;
    }
}
