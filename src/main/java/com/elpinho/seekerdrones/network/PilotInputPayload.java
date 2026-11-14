package com.elpinho.seekerdrones.network;

import com.elpinho.seekerdrones.SeekerDrones;
import com.elpinho.seekerdrones.feed.FpvFeeds;
import com.elpinho.seekerdrones.feed.PilotInput;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client to server: a pilot's movement keys (DESIGN.md section 2.13), sent only while piloting and only when they
 * change. The server applies them only to the drone the player pilots.
 */
public record PilotInputPayload(PilotInput input) implements CustomPacketPayload {
    public static final Type<PilotInputPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SeekerDrones.MODID, "pilot_input"));

    public static final StreamCodec<ByteBuf, PilotInputPayload> STREAM_CODEC = PilotInput.STREAM_CODEC.map(PilotInputPayload::new, PilotInputPayload::input);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PilotInputPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            FpvFeeds.pilotInput(player, payload.input());
        }
    }
}
