package com.elpinho.seekerdrones.network;

import java.util.List;
import java.util.Optional;
import java.util.function.IntFunction;

import com.elpinho.seekerdrones.SeekerDrones;
import com.elpinho.seekerdrones.commandcenter.CommandCenterOrders;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ByIdMap;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client to server: a Command Center viewer gives the selected drones an order (DESIGN.md section 7.10). The server
 * checks each drone against the station reach rule of the Command Center whose menu the player has open.
 *
 * @param spot the spot for Move, Patrol Here and Strike, in blocks (x, z); empty for the others
 */
public record CommandCenterOrderPayload(List<String> droneIds, Mode mode, Optional<Spot> spot) implements CustomPacketPayload {
    public static final Type<CommandCenterOrderPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SeekerDrones.MODID, "command_center_order"));
    /** The most drones one order is sent to, so a payload can't grow without bound. */
    public static final int MAX_DRONES = 256;

    public static final StreamCodec<ByteBuf, CommandCenterOrderPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(MAX_DRONES)), CommandCenterOrderPayload::droneIds,
            Mode.STREAM_CODEC, CommandCenterOrderPayload::mode,
            ByteBufCodecs.optional(Spot.STREAM_CODEC), CommandCenterOrderPayload::spot,
            CommandCenterOrderPayload::new);

    /** The mode picker's modes, in its order. New values go at the end: the codec goes by ordinal. */
    public enum Mode {
        MOVE(true),
        PATROL_HERE(true),
        STRIKE(true),
        HOLD(false),
        RETURN_TO_CHARGE(false),
        RELEASE(false);

        private static final IntFunction<Mode> BY_ID = ByIdMap.continuous(Enum::ordinal, values(), ByIdMap.OutOfBoundsStrategy.ZERO);
        public static final StreamCodec<ByteBuf, Mode> STREAM_CODEC = ByteBufCodecs.idMapper(BY_ID, Enum::ordinal);

        private final boolean aims;

        Mode(boolean aims) {
            this.aims = aims;
        }

        /** Whether the mode needs a spot picked on the map; the others are sent right away. */
        public boolean aims() {
            return aims;
        }
    }

    /** A spot on the map, in block coordinates. */
    public record Spot(double x, double z) {
        public static final StreamCodec<ByteBuf, Spot> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.DOUBLE, Spot::x,
                ByteBufCodecs.DOUBLE, Spot::z,
                Spot::new);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(CommandCenterOrderPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            CommandCenterOrders.give(player, payload);
        }
    }
}
