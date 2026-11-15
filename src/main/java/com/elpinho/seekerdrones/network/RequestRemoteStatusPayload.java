package com.elpinho.seekerdrones.network;

import com.elpinho.seekerdrones.SeekerDrones;
import com.elpinho.seekerdrones.remote.RemoteControl;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client to server: an open Drone Remote screen asking for fresh values (DESIGN.md section 2.10), or a pilot opening
 * the remote's screen mid-flight (section 2.13) or a Satellite Uplink viewer clicking a drone (section 7.9) with
 * {@code open}.
 */
public record RequestRemoteStatusPayload(String droneId, boolean open) implements CustomPacketPayload {
    public static final Type<RequestRemoteStatusPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SeekerDrones.MODID, "request_remote_status"));

    public static final StreamCodec<ByteBuf, RequestRemoteStatusPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, RequestRemoteStatusPayload::droneId,
            ByteBufCodecs.BOOL, RequestRemoteStatusPayload::open,
            RequestRemoteStatusPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RequestRemoteStatusPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            RemoteControl.access(player, payload.droneId()).ifPresent(access -> RemoteControl.sendStatus(player, access, payload.open()));
        }
    }
}
