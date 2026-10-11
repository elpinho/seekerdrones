package com.elpinho.seekerdrones.network;

import com.elpinho.seekerdrones.SeekerDrones;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client: the player now watches this drone's FPV feed (DESIGN.md section 2.12). The drone may not have
 * reached the client yet: its chunks are still being sent.
 *
 * @param remote opened from a Drone Remote, so the signal drops with distance; false for the operator command
 */
public record FpvStartPayload(int entityId, String droneId, boolean remote) implements CustomPacketPayload {
    public static final Type<FpvStartPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SeekerDrones.MODID, "fpv_start"));

    public static final StreamCodec<ByteBuf, FpvStartPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, FpvStartPayload::entityId,
            ByteBufCodecs.STRING_UTF8, FpvStartPayload::droneId,
            ByteBufCodecs.BOOL, FpvStartPayload::remote,
            FpvStartPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
