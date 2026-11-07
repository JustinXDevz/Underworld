package dev.underworld;

import org.bukkit.Material;

import java.util.Random;

/** Building materials per biome so every zone's structures look different. */
public record Palette(Material wall, Material alt, Material rare, Material floor,
                      Material glass, Material log, Material roof, Material plank) {

    public static Palette of(Terrain.Zone zone) {
        return switch (zone) {
            case CRYSTAL -> new Palette(Material.QUARTZ_BRICKS, Material.SMOOTH_QUARTZ, Material.AMETHYST_BLOCK,
                    Material.QUARTZ_BLOCK, Material.LIGHT_BLUE_STAINED_GLASS, Material.QUARTZ_PILLAR,
                    Material.PURPUR_BLOCK, Material.QUARTZ_BRICKS);
            case CRACK -> new Palette(Material.POLISHED_BLACKSTONE_BRICKS, Material.CRACKED_POLISHED_BLACKSTONE_BRICKS,
                    Material.CRYING_OBSIDIAN, Material.BLACKSTONE, Material.TINTED_GLASS,
                    Material.POLISHED_BLACKSTONE, Material.BLACKSTONE, Material.POLISHED_BLACKSTONE_BRICKS);
            case SNOW -> new Palette(Material.STONE_BRICKS, Material.CRACKED_STONE_BRICKS, Material.COBBLESTONE,
                    Material.SPRUCE_PLANKS, Material.GLASS, Material.SPRUCE_LOG,
                    Material.SPRUCE_PLANKS, Material.SPRUCE_PLANKS);
            case DARK -> new Palette(Material.DEEPSLATE_TILES, Material.CRACKED_DEEPSLATE_TILES, Material.SCULK,
                    Material.POLISHED_DEEPSLATE, Material.TINTED_GLASS, Material.POLISHED_DEEPSLATE,
                    Material.DEEPSLATE_TILES, Material.DEEPSLATE_BRICKS);
            default -> new Palette(Material.DEEPSLATE_BRICKS, Material.CRACKED_DEEPSLATE_BRICKS, Material.BLACKSTONE,
                    Material.DEEPSLATE_TILES, Material.TINTED_GLASS, Material.POLISHED_DEEPSLATE,
                    Material.DEEPSLATE_TILES, Material.DEEPSLATE_BRICKS);
        };
    }

    public Material pick(Random r) {
        int k = r.nextInt(10);
        if (k < 6) return wall;
        if (k < 9) return alt;
        return rare;
    }

    /** Deterministic pick for column-based generation. */
    public Material pick(long hash) {
        int k = (int) Math.floorMod(hash, 10L);
        if (k < 6) return wall;
        if (k < 9) return alt;
        return rare;
    }
}
