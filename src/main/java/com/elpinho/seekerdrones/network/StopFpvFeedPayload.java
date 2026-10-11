package com.elpinho.seekerdrones.network;

import com.elpinho.seekerdrones.SeekerDrones;
import com.elpinho.seekerdrones.feed.FpvFeeds;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client to server: the player pressed Sneak to leave the FPV feed (DESIGN.md section 2.12). */
public record StopFpvFeedPayload() implements CustomPacketPayload {
    public static final StopFpvFeedPayload INSTANCE = new StopFpvFeedPayload();
    public static final Type<StopFpvFeedPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SeekerDrones.MODID, "stop_fpv_feed"));

    public static final StreamCodec<ByteBuf, StopFpvFeedPayload> STREAM_CODEC = StreamCodec.unit(INSTANCE);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(StopFpvFeedPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            FpvFeeds.stop(player, false);
        }
    }
}
