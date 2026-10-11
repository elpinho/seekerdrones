package com.elpinho.seekerdrones.network;

import com.elpinho.seekerdrones.SeekerDrones;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client: what the FPV HUD needs beyond the drone's synced fields (DESIGN.md section 2.12), sent every
 * {@code camera.hudSyncInterval} ticks while the player watches.
 *
 * @param drainRate the FE per tick the drone's last energy drain cost, everything included
 * @param solarRate the FE per tick its Solar upgrades generated then (section 4), so the HUD can show the net rate
 */
public record FpvStatusPayload(int energy, int maxEnergy, float drainRate, float solarRate) implements CustomPacketPayload {
    public static final Type<FpvStatusPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SeekerDrones.MODID, "fpv_status"));

    public static final StreamCodec<ByteBuf, FpvStatusPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, FpvStatusPayload::energy,
            ByteBufCodecs.VAR_INT, FpvStatusPayload::maxEnergy,
            ByteBufCodecs.FLOAT, FpvStatusPayload::drainRate,
            ByteBufCodecs.FLOAT, FpvStatusPayload::solarRate,
            FpvStatusPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
