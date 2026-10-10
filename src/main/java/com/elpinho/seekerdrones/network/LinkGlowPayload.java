package com.elpinho.seekerdrones.network;

import com.elpinho.seekerdrones.SeekerDrones;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client: a drone was just linked to the player's Drone Remote, so it glows for them alone (DESIGN.md
 * section 2.10). Client side only: the drone's real Glowing state isn't touched, so nobody else sees it.
 */
public record LinkGlowPayload(int entityId, int ticks) implements CustomPacketPayload {
    public static final Type<LinkGlowPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SeekerDrones.MODID, "link_glow"));

    public static final StreamCodec<ByteBuf, LinkGlowPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, LinkGlowPayload::entityId,
            ByteBufCodecs.VAR_INT, LinkGlowPayload::ticks,
            LinkGlowPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
