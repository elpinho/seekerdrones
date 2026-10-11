package com.elpinho.seekerdrones.network;

import com.elpinho.seekerdrones.SeekerDrones;
import com.elpinho.seekerdrones.feed.FpvFeeds;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client to server: the FPV FEED button on the Drone Remote screen (DESIGN.md section 2.12). The server checks the
 * player's remote, operator permission, reach and the drone's FPV Camera again.
 */
public record StartFpvFeedPayload(String droneId) implements CustomPacketPayload {
    public static final Type<StartFpvFeedPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SeekerDrones.MODID, "start_fpv_feed"));

    public static final StreamCodec<ByteBuf, StartFpvFeedPayload> STREAM_CODEC =
            ByteBufCodecs.STRING_UTF8.map(StartFpvFeedPayload::new, StartFpvFeedPayload::droneId);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(StartFpvFeedPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            FpvFeeds.startFromRemote(player, payload.droneId());
        }
    }
}
