package com.elpinho.seekerdrones.network;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntFunction;

import com.elpinho.seekerdrones.SeekerDrones;
import com.elpinho.seekerdrones.drone.DroneOrder;
import com.elpinho.seekerdrones.drone.DroneState;
import com.elpinho.seekerdrones.drone.UpgradeType;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.ByIdMap;
import net.minecraft.world.item.DyeColor;

/**
 * Server to client: a Command Center viewer's snapshot of its area (DESIGN.md section 7.10), sent every
 * {@code commandCenter.scanInterval} ticks to each player with its screen open. It has every drone in the area, with
 * the viewer's own drones, other players' and unowned ones told apart, and the Charging Stations the viewer's drones
 * could use. Positions are relative to the Command Center's center.
 * <p>
 * Every {@code commandCenter.detailInterval} ticks (and on the first snapshot) the own and other players' drones also
 * carry their details; in between the client keeps the last ones by drone ID. Unowned drones carry no ID, only their
 * place in this snapshot, so they carry their health in every snapshot.
 */
public record CommandCenterContactsPayload(BlockPos pos, List<Contact> contacts, List<MapMarkers.Station> stations)
        implements CustomPacketPayload {
    public static final Type<CommandCenterContactsPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SeekerDrones.MODID, "command_center_contacts"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CommandCenterContactsPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, CommandCenterContactsPayload::pos,
            Contact.STREAM_CODEC.apply(ByteBufCodecs.list()), CommandCenterContactsPayload::contacts,
            MapMarkers.Station.STREAM_CODEC.apply(ByteBufCodecs.list()), CommandCenterContactsPayload::stations,
            CommandCenterContactsPayload::new);

    /** Whose drone it is, for the viewer. New values go at the end: the codec goes by ordinal. */
    public enum Kind {
        /** The viewer is its operator. */
        OWN,
        /** It has an owner group (or owner) the viewer isn't an operator of. */
        OTHER,
        /** No owner, or an unknown group. */
        UNOWNED;

        private static final IntFunction<Kind> BY_ID = ByIdMap.continuous(Enum::ordinal, values(), ByIdMap.OutOfBoundsStrategy.ZERO);
        public static final StreamCodec<ByteBuf, Kind> STREAM_CODEC = ByteBufCodecs.idMapper(BY_ID, Enum::ordinal);
    }

    /** A Command Center order with the ticks it has left. */
    public record Order(DroneOrder order, int ticksLeft) {
        public static final StreamCodec<ByteBuf, Order> STREAM_CODEC = StreamCodec.composite(
                DroneOrder.STREAM_CODEC, Order::order,
                ByteBufCodecs.VAR_INT, Order::ticksLeft,
                Order::new);
    }

    /**
     * What only the viewer's own drones show.
     *
     * @param energy    the energy in FE
     * @param maxEnergy the most energy it can hold, in FE
     * @param upgrades  the installed upgrades and their counts
     */
    public record OwnDetails(int energy, int maxEnergy, Map<UpgradeType, Integer> upgrades, DroneState state, Optional<Order> order) {
        private static final StreamCodec<ByteBuf, Map<UpgradeType, Integer>> UPGRADES =
                ByteBufCodecs.map(HashMap::new, UpgradeType.STREAM_CODEC, ByteBufCodecs.VAR_INT);
        public static final StreamCodec<ByteBuf, OwnDetails> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, OwnDetails::energy,
                ByteBufCodecs.VAR_INT, OwnDetails::maxEnergy,
                UPGRADES, OwnDetails::upgrades,
                DroneState.STREAM_CODEC, OwnDetails::state,
                ByteBufCodecs.optional(Order.STREAM_CODEC), OwnDetails::order,
                OwnDetails::new);
    }

    /**
     * A drone's details: health for everyone, the patrol ring for own and other players' drones (with Patrol), and the
     * rest only for own drones.
     */
    public record Details(float health, float maxHealth, Optional<MapMarkers.Patrol> patrol, Optional<OwnDetails> own) {
        public static final StreamCodec<ByteBuf, Details> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.FLOAT, Details::health,
                ByteBufCodecs.FLOAT, Details::maxHealth,
                ByteBufCodecs.optional(MapMarkers.Patrol.STREAM_CODEC), Details::patrol,
                ByteBufCodecs.optional(OwnDetails.STREAM_CODEC), Details::own,
                Details::new);
    }

    /**
     * One drone in the area.
     *
     * @param droneId empty for an unowned drone
     * @param yaw     the drone's heading in degrees, Minecraft's yaw
     * @param label   empty for an unowned drone
     * @param target  the entity it is tracking, if any
     * @param details on detail snapshots, and always for an unowned drone
     */
    public record Contact(Kind kind, String droneId, float dx, float dy, float dz, float yaw, String label, DyeColor color,
            Optional<MapMarkers.Target> target, Optional<Details> details) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Contact> STREAM_CODEC = StreamCodec.of(
                (buf, contact) -> {
                    Kind.STREAM_CODEC.encode(buf, contact.kind());
                    ByteBufCodecs.STRING_UTF8.encode(buf, contact.droneId());
                    buf.writeFloat(contact.dx());
                    buf.writeFloat(contact.dy());
                    buf.writeFloat(contact.dz());
                    buf.writeFloat(contact.yaw());
                    ByteBufCodecs.STRING_UTF8.encode(buf, contact.label());
                    DyeColor.STREAM_CODEC.encode(buf, contact.color());
                    ByteBufCodecs.optional(MapMarkers.Target.STREAM_CODEC).encode(buf, contact.target());
                    ByteBufCodecs.optional(Details.STREAM_CODEC).encode(buf, contact.details());
                },
                buf -> new Contact(Kind.STREAM_CODEC.decode(buf), ByteBufCodecs.STRING_UTF8.decode(buf), buf.readFloat(), buf.readFloat(),
                        buf.readFloat(), buf.readFloat(), ByteBufCodecs.STRING_UTF8.decode(buf), DyeColor.STREAM_CODEC.decode(buf),
                        ByteBufCodecs.optional(MapMarkers.Target.STREAM_CODEC).decode(buf), ByteBufCodecs.optional(Details.STREAM_CODEC).decode(buf)));

        /** The label, or the ID without one. Empty for an unowned drone. */
        public String name() {
            return label.isEmpty() ? droneId : label;
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
