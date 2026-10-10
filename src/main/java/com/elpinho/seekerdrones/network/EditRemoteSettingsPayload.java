package com.elpinho.seekerdrones.network;

import java.util.Optional;

import com.elpinho.seekerdrones.SeekerDrones;
import com.elpinho.seekerdrones.remote.RemoteControl;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client to server: the settings a Drone Remote can change (DESIGN.md section 2.10). The server checks the player's
 * remote, operator permission and reach, then the values with the Programming Station's rules.
 *
 * @param patrolRadius the wanted patrol radius, empty for the largest the Patrol upgrades allow
 * @param patrolSpeed  the wanted patrol speed in blocks/tick, empty for the base speed
 */
public record EditRemoteSettingsPayload(String droneId, int followDistance, Optional<Integer> patrolRadius, Optional<Double> patrolSpeed,
        String label, DyeColor color) implements CustomPacketPayload {
    public static final Type<EditRemoteSettingsPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SeekerDrones.MODID, "edit_remote_settings"));

    public static final StreamCodec<ByteBuf, EditRemoteSettingsPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, EditRemoteSettingsPayload::droneId,
            ByteBufCodecs.VAR_INT, EditRemoteSettingsPayload::followDistance,
            ByteBufCodecs.optional(ByteBufCodecs.VAR_INT), EditRemoteSettingsPayload::patrolRadius,
            ByteBufCodecs.optional(ByteBufCodecs.DOUBLE), EditRemoteSettingsPayload::patrolSpeed,
            ByteBufCodecs.STRING_UTF8, EditRemoteSettingsPayload::label,
            DyeColor.STREAM_CODEC, EditRemoteSettingsPayload::color,
            EditRemoteSettingsPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(EditRemoteSettingsPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            RemoteControl.editSettings(player, payload);
        }
    }
}
