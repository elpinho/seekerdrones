package com.elpinho.seekerdrones.radar;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.level.material.MapColor;

/**
 * A Drone Satellite Uplink's terrain map in vanilla map colors (DESIGN.md section 7.9). Each column is one byte, like
 * a vanilla map pixel: the top block's {@link MapColor} id times 4 plus a brightness. The server works out the
 * brightness, so the client only looks the colors up. {@link #UNKNOWN} (color 0) is a chunk that was never generated.
 */
public record ColorMap(int radius, byte[] colors) implements RadarMap {
    public static final byte UNKNOWN = 0;

    public static final StreamCodec<ByteBuf, ColorMap> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ColorMap::radius,
            ByteBufCodecs.BYTE_ARRAY, ColorMap::colors,
            ColorMap::new);

    public byte color(int column, int row) {
        return colors[index(column, row)];
    }
}
