package com.elpinho.seekerdrones.network;

import com.elpinho.seekerdrones.SeekerDrones;
import com.elpinho.seekerdrones.remote.RemoteCommand;
import com.elpinho.seekerdrones.remote.RemoteControl;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client to server: a command button on the Drone Remote screen (DESIGN.md section 2.10). The server checks the
 * player's remote, operator permission and reach again.
 */
public record RemoteCommandPayload(String droneId, RemoteCommand command) implements CustomPacketPayload {
    public static final Type<RemoteCommandPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SeekerDrones.MODID, "remote_command"));

    public static final StreamCodec<ByteBuf, RemoteCommandPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, RemoteCommandPayload::droneId,
            RemoteCommand.STREAM_CODEC, RemoteCommandPayload::command,
            RemoteCommandPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RemoteCommandPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            RemoteControl.command(player, payload.droneId(), payload.command());
        }
    }
}
