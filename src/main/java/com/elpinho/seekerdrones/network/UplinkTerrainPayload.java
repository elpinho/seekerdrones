package com.elpinho.seekerdrones.network;

import com.elpinho.seekerdrones.SeekerDrones;
import com.elpinho.seekerdrones.radar.ColorMap;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client: a Satellite Uplink's terrain map in map colors (DESIGN.md section 7.9), sent to a player with its
 * screen open when they open it and whenever the map is rebuilt.
 */
public record UplinkTerrainPayload(BlockPos pos, ColorMap map) implements CustomPacketPayload {
    public static final Type<UplinkTerrainPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SeekerDrones.MODID, "uplink_terrain"));

    public static final StreamCodec<ByteBuf, UplinkTerrainPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, UplinkTerrainPayload::pos,
            ColorMap.STREAM_CODEC, UplinkTerrainPayload::map,
            UplinkTerrainPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
