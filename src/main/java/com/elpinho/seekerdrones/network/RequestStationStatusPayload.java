package com.elpinho.seekerdrones.network;

import com.elpinho.seekerdrones.SeekerDrones;
import com.elpinho.seekerdrones.station.ChargingStationBlockEntity;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client to server: an open Charging Station screen asking for fresh values.
 */
public record RequestStationStatusPayload(BlockPos pos) implements CustomPacketPayload {
    public static final Type<RequestStationStatusPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SeekerDrones.MODID, "request_station_status"));

    public static final StreamCodec<ByteBuf, RequestStationStatusPayload> STREAM_CODEC =
            BlockPos.STREAM_CODEC.map(RequestStationStatusPayload::new, RequestStationStatusPayload::pos);

    /** Extra reach allowed while the screen is open, like vanilla containers' {@code stillValid} buffer. */
    private static final double RANGE_BUFFER = 4.0;

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RequestStationStatusPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        BlockPos pos = payload.pos();
        // Stations have no access control in v1 (section 6.2), so anyone in reach may look.
        if (player.canInteractWithBlock(pos, RANGE_BUFFER) && player.serverLevel().hasChunkAt(pos)
                && player.serverLevel().getBlockEntity(pos) instanceof ChargingStationBlockEntity station) {
            PacketDistributor.sendToPlayer(player, StationStatusPayload.of(player.serverLevel(), station, false));
        }
    }
}
