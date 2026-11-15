package com.elpinho.seekerdrones.network;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.entity.EntityType;

import io.netty.buffer.ByteBuf;

/**
 * The map markers the Satellite Uplink and the Command Center share in their snapshots (DESIGN.md sections 7.9 and
 * 7.10): patrol rings, tracked entities and Charging Stations. Positions are relative to the station.
 */
public final class MapMarkers {
    private MapMarkers() {}

    /** A drone's patrol ring: its center relative to the station, and its radius in blocks. */
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

    /** A Charging Station the viewer's drones could use, relative to the station's block. */
    public record Station(int dx, int dz) {
        public static final StreamCodec<ByteBuf, Station> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Station::dx,
                ByteBufCodecs.VAR_INT, Station::dz,
                Station::new);
    }
}
