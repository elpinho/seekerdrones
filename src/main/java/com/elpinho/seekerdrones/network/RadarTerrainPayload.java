package com.elpinho.seekerdrones.network;

import com.elpinho.seekerdrones.SeekerDrones;
import com.elpinho.seekerdrones.radar.TerrainMap;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client: a Radar Station's terrain map (DESIGN.md section 7.8), sent to a player with its screen open when
 * they open it and whenever the map is rebuilt.
 */
public record RadarTerrainPayload(BlockPos pos, TerrainMap map) implements CustomPacketPayload {
    public static final Type<RadarTerrainPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SeekerDrones.MODID, "radar_terrain"));

    public static final StreamCodec<ByteBuf, RadarTerrainPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, RadarTerrainPayload::pos,
            TerrainMap.STREAM_CODEC, RadarTerrainPayload::map,
            RadarTerrainPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
