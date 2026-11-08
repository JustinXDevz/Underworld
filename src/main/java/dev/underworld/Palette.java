package dev.underworld;

import org.bukkit.Material;

import java.util.Random;

/** Building materials per zone. */
public record Palette(Material wall, Material alt, Material rare, Material floor, Material glass,
                      Material log, Material roof, Material plank, Material trim, Material light) {

    public static Palette of(Terrain.Zone zone) {
        return switch (zone) {
            case CRYSTAL -> new Palette(Material.POLISHED_BLACKSTONE_BRICKS, Material.CRACKED_POLISHED_BLACKSTONE_BRICKS,
                    Material.AMETHYST_BLOCK, Material.POLISHED_BLACKSTONE, Material.LIME_STAINED_GLASS,
                    Material.POLISHED_BLACKSTONE, Material.POLISHED_BLACKSTONE_BRICKS, Material.POLISHED_BLACKSTONE_BRICKS,
                    Material.AMETHYST_BLOCK, Material.SEA_LANTERN);
            case CRACK -> new Palette(Material.NETHER_BRICKS, Material.CRACKED_NETHER_BRICKS,
                    Material.CRYING_OBSIDIAN, Material.RED_NETHER_BRICKS, Material.TINTED_GLASS,
                    Material.POLISHED_BLACKSTONE, Material.NETHER_BRICKS, Material.NETHER_BRICKS,
                    Material.CRYING_OBSIDIAN, Material.SHROOMLIGHT);
            case SNOW -> new Palette(Material.STONE_BRICKS, Material.CRACKED_STONE_BRICKS,
                    Material.COBBLESTONE, Material.SPRUCE_PLANKS, Material.GLASS,
                    Material.SPRUCE_LOG, Material.SPRUCE_PLANKS, Material.SPRUCE_PLANKS,
                    Material.STRIPPED_SPRUCE_LOG, Material.SHROOMLIGHT);
            case DARK -> new Palette(Material.STONE_BRICKS, Material.CRACKED_STONE_BRICKS,
                    Material.COBBLESTONE, Material.DEEPSLATE_TILES, Material.TINTED_GLASS,
                    Material.STRIPPED_SPRUCE_LOG, Material.DEEPSLATE_TILES, Material.SPRUCE_PLANKS,
                    Material.WARPED_PLANKS, Material.SHROOMLIGHT);
            default -> new Palette(Material.DEEPSLATE_BRICKS, Material.CRACKED_DEEPSLATE_BRICKS,
                    Material.BLACKSTONE, Material.DEEPSLATE_TILES, Material.TINTED_GLASS,
                    Material.POLISHED_DEEPSLATE, Material.DEEPSLATE_TILES, Material.DEEPSLATE_BRICKS,
                    Material.POLISHED_DEEPSLATE, Material.SHROOMLIGHT);
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
