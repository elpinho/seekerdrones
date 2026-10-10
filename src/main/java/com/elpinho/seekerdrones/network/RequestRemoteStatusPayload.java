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
 * Client to server: an open Drone Remote screen asking for fresh values (DESIGN.md section 2.10).
 */
public record RequestRemoteStatusPayload(String droneId) implements CustomPacketPayload {
    public static final Type<RequestRemoteStatusPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SeekerDrones.MODID, "request_remote_status"));

    public static final StreamCodec<ByteBuf, RequestRemoteStatusPayload> STREAM_CODEC =
            ByteBufCodecs.STRING_UTF8.map(RequestRemoteStatusPayload::new, RequestRemoteStatusPayload::droneId);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RequestRemoteStatusPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            RemoteControl.heldLink(player, payload.droneId()).ifPresent(link -> RemoteControl.sendStatus(player, link, false));
        }
    }
}
