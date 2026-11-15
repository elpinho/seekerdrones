package com.elpinho.seekerdrones.radar;

import java.util.Arrays;
import java.util.BitSet;

/** The Radar Station's phosphor map (section 7.8): each column's height relative to the station, and a water flag. */
final class HeightCanvas implements TerrainCanvas<TerrainMap> {
    private final int radius;
    private final int stationY;
    private final byte[] heights;
    private final BitSet water = new BitSet();

    HeightCanvas(int radius, int stationY) {
        this.radius = radius;
        this.stationY = stationY;
        this.heights = new byte[RadarMap.size(radius) * RadarMap.size(radius)];
        Arrays.fill(heights, TerrainMap.UNKNOWN);
    }

    @Override
    public boolean colors() {
        return false;
    }

    @Override
    public void set(int index, ChunkSurface surface, int localX, int localZ) {
        heights[index] = TerrainMap.relativeHeight(surface.top(localX, localZ), stationY);
        water.set(index, surface.isWater(localX, localZ));
    }

    @Override
    public TerrainMap finish() {
        return new TerrainMap(radius, heights, water);
    }
}
