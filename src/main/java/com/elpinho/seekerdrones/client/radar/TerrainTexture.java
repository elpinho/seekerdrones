package com.elpinho.seekerdrones.client.radar;

import com.elpinho.seekerdrones.radar.TerrainMap;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import net.minecraft.util.Mth;

/**
 * The radar's terrain map as a texture, in monochrome green phosphor (DESIGN.md section 7.8). Each column is shaded
 * like a vanilla map, brighter or darker by its height compared with the column to its north, with a slight tint by
 * its height relative to the station. Water is a darker, flatter tone, and columns outside the circle are transparent.
 */
public final class TerrainTexture implements AutoCloseable {
    private static final int LAND_R = 64;
    private static final int LAND_G = 186;
    private static final int LAND_B = 96;
    private static final int WATER = 0xFF1A4446;
    private static final int UNKNOWN = 0xFF141C1B;
    /** Vanilla map shading: the column is higher, level with or lower than the one to its north. */
    private static final float SHADE_HIGHER = 0.80F;
    private static final float SHADE_LEVEL = 0.66F;
    private static final float SHADE_LOWER = 0.50F;
    /** Height (blocks above or below the station) at which the height tint is strongest. */
    private static final float TINT_RANGE = 32;
    private static final float TINT_STRENGTH = 0.18F;

    private final MapTexture texture = new MapTexture("radar_terrain");

    /** Replaces the texture with the map. */
    public void upload(TerrainMap map) {
        float center = map.radius();
        float radius = map.radius() + 0.5F;
        texture.upload(map.size(), (column, row) -> {
            float dx = column - center;
            float dz = row - center;
            return dx * dx + dz * dz > radius * radius ? 0 : color(map, column, row);
        });
    }

    public boolean isReady() {
        return texture.isReady();
    }

    public ResourceLocation location() {
        return texture.location();
    }

    private static int color(TerrainMap map, int column, int row) {
        byte height = map.height(column, row);
        if (height == TerrainMap.UNKNOWN) {
            return UNKNOWN;
        }
        if (map.isWater(column, row)) {
            return WATER;
        }
        byte north = row > 0 ? map.height(column, row - 1) : height;
        if (north == TerrainMap.UNKNOWN) {
            north = height;
        }
        float shade = height > north ? SHADE_HIGHER : height < north ? SHADE_LOWER : SHADE_LEVEL;
        shade *= 1 + TINT_STRENGTH * Mth.clamp(height / TINT_RANGE, -1, 1);
        return FastColor.ARGB32.color(255, channel(LAND_R, shade), channel(LAND_G, shade), channel(LAND_B, shade));
    }

    private static int channel(int value, float shade) {
        return Mth.clamp(Math.round(value * shade), 0, 255);
    }

    @Override
    public void close() {
        texture.close();
    }
}
