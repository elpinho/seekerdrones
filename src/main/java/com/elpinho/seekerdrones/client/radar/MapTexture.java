package com.elpinho.seekerdrones.client.radar;

import javax.annotation.Nullable;

import com.elpinho.seekerdrones.SeekerDrones;
import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;

/** A radar machine's terrain map as a dynamic texture, one pixel per column (DESIGN.md sections 7.8 and 7.9). */
public final class MapTexture implements AutoCloseable {
    /** The color of one pixel, in ARGB. */
    @FunctionalInterface
    public interface Pixels {
        int argb(int column, int row);
    }

    private final ResourceLocation location;
    @Nullable
    private DynamicTexture texture;
    private int size;

    public MapTexture(String name) {
        this.location = ResourceLocation.fromNamespaceAndPath(SeekerDrones.MODID, "dynamic/" + name);
    }

    /** Replaces the texture with a square of {@code size} pixels. */
    public void upload(int size, Pixels pixels) {
        if (texture == null || this.size != size) {
            close();
            texture = new DynamicTexture(size, size, false);
            this.size = size;
            Minecraft.getInstance().getTextureManager().register(location, texture);
        }
        NativeImage image = texture.getPixels();
        for (int row = 0; row < size; row++) {
            for (int column = 0; column < size; column++) {
                // NativeImage takes ABGR.
                int argb = pixels.argb(column, row);
                image.setPixelRGBA(column, row, FastColor.ABGR32.color(FastColor.ARGB32.alpha(argb), FastColor.ARGB32.blue(argb),
                        FastColor.ARGB32.green(argb), FastColor.ARGB32.red(argb)));
            }
        }
        texture.upload();
    }

    public boolean isReady() {
        return texture != null;
    }

    public int size() {
        return size;
    }

    public ResourceLocation location() {
        return location;
    }

    @Override
    public void close() {
        if (texture != null) {
            Minecraft.getInstance().getTextureManager().release(location);
            texture = null;
        }
    }
}
