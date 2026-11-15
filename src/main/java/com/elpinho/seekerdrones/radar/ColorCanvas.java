package com.elpinho.seekerdrones.radar;

import java.util.Arrays;

import net.minecraft.world.level.material.MapColor;

/**
 * The Uplink's map in vanilla map colors (section 7.9). The brightness is worked out like a vanilla map at scale 0,
 * once every column is in: water from its depth with the checker pattern, everything else from its height compared
 * with the column to its north.
 */
final class ColorCanvas implements TerrainCanvas<ColorMap> {
    private final int radius;
    private final int originX;
    private final int originZ;
    private final int[] tops;
    private final byte[] colorIds;
    private final byte[] depths;

    ColorCanvas(int radius, int originX, int originZ) {
        int cells = RadarMap.size(radius) * RadarMap.size(radius);
        this.radius = radius;
        this.originX = originX;
        this.originZ = originZ;
        this.tops = new int[cells];
        this.colorIds = new byte[cells];
        this.depths = new byte[cells];
        Arrays.fill(tops, ChunkSurface.NONE);
    }

    @Override
    public boolean colors() {
        return true;
    }

    @Override
    public void set(int index, ChunkSurface surface, int localX, int localZ) {
        tops[index] = surface.top(localX, localZ);
        colorIds[index] = (byte) surface.mapColor(localX, localZ);
        depths[index] = (byte) Math.min(surface.fluidDepth(localX, localZ), Byte.MAX_VALUE);
    }

    @Override
    public ColorMap finish() {
        int size = RadarMap.size(radius);
        byte[] colors = new byte[size * size];
        for (int row = 0; row < size; row++) {
            for (int column = 0; column < size; column++) {
                int index = row * size + column;
                int id = colorIds[index] & 0xFF;
                if (tops[index] == ChunkSurface.NONE || id == MapColor.NONE.id) {
                    colors[index] = ColorMap.UNKNOWN;
                    continue;
                }
                int checker = (originX + column + originZ + row) & 1;
                MapColor.Brightness brightness;
                if (id == MapColor.WATER.id) {
                    double shade = depths[index] * 0.1 + checker * 0.2;
                    brightness = shade < 0.5 ? MapColor.Brightness.HIGH : shade > 0.9 ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
                } else {
                    int north = row > 0 && tops[index - size] != ChunkSurface.NONE ? tops[index - size] : tops[index];
                    double shade = (tops[index] - north) * 4.0 / 5.0 + (checker - 0.5) * 0.4;
                    brightness = shade > 0.6 ? MapColor.Brightness.HIGH : shade < -0.6 ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
                }
                colors[index] = MapColor.byId(id).getPackedId(brightness);
            }
        }
        return new ColorMap(radius, colors);
    }
}
