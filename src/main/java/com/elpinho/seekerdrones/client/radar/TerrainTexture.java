package com.elpinho.seekerdrones.client.radar;

import javax.annotation.Nullable;

import com.elpinho.seekerdrones.SeekerDrones;
import com.elpinho.seekerdrones.radar.TerrainMap;
import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import net.minecraft.util.Mth;

/**
 * The radar's terrain map as a texture, in monochrome green phosphor (DESIGN.md section 7.8). Each column is shaded
 * like a vanilla map, brighter or darker by its height compared with the column to its north, with a slight tint by
 * its height relative to the station. Water is a darker, flatter tone, and columns outside the circle are transparent.
 */
public final class TerrainTexture implements AutoCloseable {
    private static final ResourceLocation LOCATION = ResourceLocation.fromNamespaceAndPath(SeekerDrones.MODID, "dynamic/radar_terrain");

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

    @Nullable
    private DynamicTexture texture;
    private int size;

    /** Replaces the texture with the map. */
    public void upload(TerrainMap map) {
        int n = map.size();
        if (texture == null || size != n) {
            close();
            texture = new DynamicTexture(n, n, false);
            size = n;
            Minecraft.getInstance().getTextureManager().register(LOCATION, texture);
        }
        NativeImage image = texture.getPixels();
        float center = map.radius();
        float radius = map.radius() + 0.5F;
        for (int row = 0; row < n; row++) {
            for (int column = 0; column < n; column++) {
                float dx = column - center;
                float dz = row - center;
                // NativeImage takes ABGR.
                image.setPixelRGBA(column, row, dx * dx + dz * dz > radius * radius ? 0 : abgr(color(map, column, row)));
            }
        }
        texture.upload();
    }

    public boolean isReady() {
        return texture != null;
    }

    public ResourceLocation location() {
        return LOCATION;
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

    private static int abgr(int argb) {
        return FastColor.ABGR32.color(FastColor.ARGB32.alpha(argb), FastColor.ARGB32.blue(argb), FastColor.ARGB32.green(argb), FastColor.ARGB32.red(argb));
    }

    @Override
    public void close() {
        if (texture != null) {
            Minecraft.getInstance().getTextureManager().release(LOCATION);
            texture = null;
        }
    }
}
