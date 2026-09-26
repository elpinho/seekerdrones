package com.elpinho.seekerdrones.network;

import com.elpinho.seekerdrones.SeekerDrones;
import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.drone.DronePermissions;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client to server: an open status screen asking for fresh drone values.
 */
public record RequestDroneStatusPayload(int entityId) implements CustomPacketPayload {
    public static final Type<RequestDroneStatusPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SeekerDrones.MODID, "request_drone_status"));

    public static final StreamCodec<ByteBuf, RequestDroneStatusPayload> STREAM_CODEC =
            ByteBufCodecs.VAR_INT.map(RequestDroneStatusPayload::new, RequestDroneStatusPayload::entityId);

    /** Extra reach allowed while the screen is open, like vanilla containers' {@code stillValid} buffer. */
    private static final double RANGE_BUFFER = 4.0;

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RequestDroneStatusPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        if (player.level().getEntity(payload.entityId()) instanceof DroneEntity drone
                && drone.isAlive()
                && player.canInteractWithEntity(drone, RANGE_BUFFER)
                && DronePermissions.canInteract(player, drone.snapshotData())) {
            PacketDistributor.sendToPlayer(player, DroneStatusPayload.of(drone, false));
        }
    }
}
