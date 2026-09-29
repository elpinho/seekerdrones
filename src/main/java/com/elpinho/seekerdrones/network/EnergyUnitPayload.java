package com.elpinho.seekerdrones.network;

import com.elpinho.seekerdrones.SeekerDrones;
import com.elpinho.seekerdrones.energy.EnergyUnit;
import com.elpinho.seekerdrones.registry.ModAttachments;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * A player's energy display unit (DESIGN.md section 5.4), both ways. Server to client: the saved choice, sent on login.
 * Client to server: the player picked a new unit with a unit button, to be saved.
 */
public record EnergyUnitPayload(EnergyUnit unit) implements CustomPacketPayload {
    public static final Type<EnergyUnitPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SeekerDrones.MODID, "energy_unit"));

    public static final StreamCodec<ByteBuf, EnergyUnitPayload> STREAM_CODEC =
            EnergyUnit.STREAM_CODEC.map(EnergyUnitPayload::new, EnergyUnitPayload::unit);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Skipped for connections that didn't negotiate the channel (e.g. GameTest fake players). */
    public static void sendTo(ServerPlayer player) {
        if (player.connection.hasChannel(TYPE)) {
            PacketDistributor.sendToPlayer(player, new EnergyUnitPayload(player.getData(ModAttachments.ENERGY_UNIT)));
        }
    }

    public static void handleOnServer(EnergyUnitPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            player.setData(ModAttachments.ENERGY_UNIT, payload.unit());
        }
    }
}
