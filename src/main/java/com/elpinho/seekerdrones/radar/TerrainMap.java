package com.elpinho.seekerdrones.radar;

import java.util.BitSet;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * A Radar Station's terrain map (DESIGN.md section 7.8): a square of {@code (2 * radius + 1)²} columns centered on the
 * station, north (negative Z) first, one row per Z. Each column holds its surface height relative to the station's Y
 * (clamped to ±127, {@link #UNKNOWN} for chunks that were never generated) and whether its surface is water. The
 * client shades it.
 */
public record TerrainMap(int radius, byte[] heights, BitSet water) implements RadarMap {
    /** A column with no data: its chunk was never generated. */
    public static final byte UNKNOWN = Byte.MIN_VALUE;

    public static final StreamCodec<ByteBuf, TerrainMap> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, TerrainMap::radius,
            ByteBufCodecs.BYTE_ARRAY, TerrainMap::heights,
            ByteBufCodecs.BYTE_ARRAY.map(BitSet::valueOf, BitSet::toByteArray), TerrainMap::water,
            TerrainMap::new);

    public static int size(int radius) {
        return radius * 2 + 1;
    }

    public byte height(int column, int row) {
        return heights[index(column, row)];
    }

    public boolean isWater(int column, int row) {
        return water.get(index(column, row));
    }

    /** A height relative to the station, clamped to what a column can hold. */
    static byte relativeHeight(int surfaceY, int stationY) {
        return (byte) Math.clamp(surfaceY - stationY, -127, 127);
    }
}
