package com.elpinho.seekerdrones.network;

import java.util.List;
import java.util.Optional;

import com.elpinho.seekerdrones.SeekerDrones;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.DyeColor;

/**
 * Server to client: what a Satellite Uplink's viewer sees in its area (DESIGN.md section 7.9), sent every
 * {@code uplink.scanInterval} ticks to each player with its screen open. Every viewer gets their own snapshot: the
 * drones they are an operator of are identified, every other drone is unidentified and carries no ID, and only the
 * Charging Stations whose placer they are an operator of are listed. Positions are relative to the Uplink's center.
 * Identified drones also carry the entity they are tracking, if any; unidentified ones never do.
 */
public record UplinkContactsPayload(BlockPos pos, List<Identified> identified, List<Unidentified> unidentified, List<Station> stations)
        implements CustomPacketPayload {
    public static final Type<UplinkContactsPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SeekerDrones.MODID, "uplink_contacts"));

    public static final StreamCodec<RegistryFriendlyByteBuf, UplinkContactsPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, UplinkContactsPayload::pos,
            Identified.STREAM_CODEC.apply(ByteBufCodecs.list()), UplinkContactsPayload::identified,
            Unidentified.STREAM_CODEC.apply(ByteBufCodecs.list()), UplinkContactsPayload::unidentified,
            Station.STREAM_CODEC.apply(ByteBufCodecs.list()), UplinkContactsPayload::stations,
            UplinkContactsPayload::new);

    /** A drone's patrol ring: its center relative to the Uplink, and its radius in blocks. */
    public record Patrol(float dx, float dz, int radius) {
        public static final StreamCodec<ByteBuf, Patrol> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.FLOAT, Patrol::dx,
                ByteBufCodecs.FLOAT, Patrol::dz,
                ByteBufCodecs.VAR_INT, Patrol::radius,
                Patrol::new);
    }

    /**
     * The entity a drone is tracking (chasing, following or rushing to explode): where it is, its name, and its type,
     * plus the player's name for a player, so the map can draw their face.
     */
    public record Target(float dx, float dz, Component name, EntityType<?> type, String playerName) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Target> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.FLOAT, Target::dx,
                ByteBufCodecs.FLOAT, Target::dz,
                ComponentSerialization.STREAM_CODEC, Target::name,
                ByteBufCodecs.registry(Registries.ENTITY_TYPE), Target::type,
                ByteBufCodecs.STRING_UTF8, Target::playerName,
                Target::new);
    }

    /**
     * One of the viewer's drones.
     *
     * @param yaw    the drone's heading in degrees, Minecraft's yaw
     * @param patrol its patrol ring, only with a Patrol upgrade
     * @param target the entity it is tracking, if any
     */
    public record Identified(String droneId, float dx, float dy, float dz, float yaw, String label, DyeColor color, Optional<Patrol> patrol,
            Optional<Target> target) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Identified> STREAM_CODEC = StreamCodec.of(
                (buf, drone) -> {
                    ByteBufCodecs.STRING_UTF8.encode(buf, drone.droneId());
                    buf.writeFloat(drone.dx());
                    buf.writeFloat(drone.dy());
                    buf.writeFloat(drone.dz());
                    buf.writeFloat(drone.yaw());
                    ByteBufCodecs.STRING_UTF8.encode(buf, drone.label());
                    DyeColor.STREAM_CODEC.encode(buf, drone.color());
                    ByteBufCodecs.optional(Patrol.STREAM_CODEC).encode(buf, drone.patrol());
                    ByteBufCodecs.optional(Target.STREAM_CODEC).encode(buf, drone.target());
                },
                buf -> new Identified(ByteBufCodecs.STRING_UTF8.decode(buf), buf.readFloat(), buf.readFloat(), buf.readFloat(),
                        buf.readFloat(), ByteBufCodecs.STRING_UTF8.decode(buf), DyeColor.STREAM_CODEC.decode(buf),
                        ByteBufCodecs.optional(Patrol.STREAM_CODEC).decode(buf), ByteBufCodecs.optional(Target.STREAM_CODEC).decode(buf)));
    }

    /** Someone else's drone (or an unowned one): position and heading only, no ID. */
    public record Unidentified(float dx, float dy, float dz, float yaw) {
        public static final StreamCodec<ByteBuf, Unidentified> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.FLOAT, Unidentified::dx,
                ByteBufCodecs.FLOAT, Unidentified::dy,
                ByteBufCodecs.FLOAT, Unidentified::dz,
                ByteBufCodecs.FLOAT, Unidentified::yaw,
                Unidentified::new);
    }

    /** A Charging Station the viewer's drones could use, relative to the Uplink's block. */
    public record Station(int dx, int dz) {
        public static final StreamCodec<ByteBuf, Station> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Station::dx,
                ByteBufCodecs.VAR_INT, Station::dz,
                Station::new);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
