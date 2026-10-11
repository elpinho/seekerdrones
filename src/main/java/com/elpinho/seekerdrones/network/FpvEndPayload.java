package com.elpinho.seekerdrones.network;

import com.elpinho.seekerdrones.SeekerDrones;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client: the player's FPV feed ended (DESIGN.md section 2.12).
 *
 * @param signalLost the drone was lost (out of range, destroyed, unloaded): show "Signal lost" before switching back
 */
public record FpvEndPayload(boolean signalLost) implements CustomPacketPayload {
    public static final Type<FpvEndPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SeekerDrones.MODID, "fpv_end"));

    public static final StreamCodec<ByteBuf, FpvEndPayload> STREAM_CODEC = ByteBufCodecs.BOOL.map(FpvEndPayload::new, FpvEndPayload::signalLost);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
