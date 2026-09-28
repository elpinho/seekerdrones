package com.elpinho.seekerdrones.network;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntFunction;

import com.elpinho.seekerdrones.SeekerDrones;
import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.drone.DroneState;
import com.elpinho.seekerdrones.drone.DroneStats;
import com.elpinho.seekerdrones.station.ChargingStationBlockEntity;
import com.elpinho.seekerdrones.station.RepairFluid;
import com.mojang.authlib.GameProfile;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ByIdMap;
import net.minecraft.world.level.material.Fluid;

/**
 * Server to client: a snapshot of a Charging Station for its read-only screen (DESIGN.md section 7.4).
 *
 * @param open      true to open the screen, false to refresh one that is already open
 * @param fluid     the fluid in the tank, or the repair fluid it takes if the tank is empty
 * @param ownerName the placer's name (or UUID if it can't be resolved), empty if a non-player placed the station
 * @param drone     the drone holding the station, if any
 */
public record StationStatusPayload(BlockPos pos, boolean open, Status status, int energy, int capacity, int chargeRate, Fluid fluid,
        int fluidAmount, int tankCapacity, String ownerName, Optional<DockedDrone> drone) implements CustomPacketPayload {
    public static final Type<StationStatusPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SeekerDrones.MODID, "station_status"));

    private static final StreamCodec<ByteBuf, Optional<DockedDrone>> DRONE_CODEC = ByteBufCodecs.optional(DockedDrone.STREAM_CODEC);

    /** Written by hand: {@link StreamCodec#composite} only goes up to six fields. */
    private static final StreamCodec<RegistryFriendlyByteBuf, Fluid> FLUID_CODEC = ByteBufCodecs.registry(Registries.FLUID);

    public static final StreamCodec<RegistryFriendlyByteBuf, StationStatusPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                BlockPos.STREAM_CODEC.encode(buf, payload.pos());
                ByteBufCodecs.BOOL.encode(buf, payload.open());
                Status.STREAM_CODEC.encode(buf, payload.status());
                ByteBufCodecs.VAR_INT.encode(buf, payload.energy());
                ByteBufCodecs.VAR_INT.encode(buf, payload.capacity());
                ByteBufCodecs.VAR_INT.encode(buf, payload.chargeRate());
                FLUID_CODEC.encode(buf, payload.fluid());
                ByteBufCodecs.VAR_INT.encode(buf, payload.fluidAmount());
                ByteBufCodecs.VAR_INT.encode(buf, payload.tankCapacity());
                ByteBufCodecs.STRING_UTF8.encode(buf, payload.ownerName());
                DRONE_CODEC.encode(buf, payload.drone());
            },
            buf -> new StationStatusPayload(
                    BlockPos.STREAM_CODEC.decode(buf),
                    ByteBufCodecs.BOOL.decode(buf),
                    Status.STREAM_CODEC.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    FLUID_CODEC.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    DRONE_CODEC.decode(buf)));

    public static StationStatusPayload of(ServerLevel level, ChargingStationBlockEntity station, boolean open) {
        Optional<DockedDrone> drone = station.getClaimant()
                .map(level::getEntity)
                .filter(entity -> entity instanceof DroneEntity)
                .map(entity -> DockedDrone.of((DroneEntity) entity));
        Status status = drone.map(docked -> docked.status(station.getEnergy())).orElse(Status.IDLE);
        String ownerName = station.getOwner().map(uuid -> playerName(level.getServer(), uuid)).orElse("");
        Fluid fluid = station.getFluid().isEmpty() ? RepairFluid.displayFluid() : station.getFluid().getFluid();
        return new StationStatusPayload(station.getBlockPos(), open, status, station.getEnergy(), station.getEnergyStorage().getMaxEnergyStored(),
                ServerConfig.get(ServerConfig.CHARGING_STATION_CHARGE_RATE), fluid, station.getFluid().getAmount(), station.getTankCapacity(),
                ownerName, drone);
    }

    private static String playerName(MinecraftServer server, UUID uuid) {
        return Optional.ofNullable(server.getProfileCache())
                .flatMap(cache -> cache.get(uuid))
                .map(GameProfile::getName)
                .orElse(uuid.toString());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * The drone holding the station. It claims the station a few blocks out, so it may not have docked yet.
     *
     * @param data the drone's data, with its current health
     */
    public record DockedDrone(DroneData data, int maxEnergy, float maxHealth, boolean docked) {
        public static final StreamCodec<ByteBuf, DockedDrone> STREAM_CODEC = StreamCodec.composite(
                DroneData.STREAM_CODEC, DockedDrone::data,
                ByteBufCodecs.VAR_INT, DockedDrone::maxEnergy,
                ByteBufCodecs.FLOAT, DockedDrone::maxHealth,
                ByteBufCodecs.BOOL, DockedDrone::docked,
                DockedDrone::new);

        static DockedDrone of(DroneEntity drone) {
            DroneData data = drone.snapshotData();
            return new DockedDrone(data, DroneStats.maxEnergy(data), drone.getMaxHealth(), drone.getState() == DroneState.CHARGING);
        }

        Status status(int stationEnergy) {
            if (!docked) {
                return Status.DOCKING;
            }
            if (stationEnergy <= 0) {
                return Status.NO_POWER;
            }
            return data.energy() < maxEnergy ? Status.CHARGING : Status.HEALING;
        }
    }

    public enum Status {
        IDLE,
        /** A drone has claimed the station and is flying onto it. */
        DOCKING,
        CHARGING,
        /** Full energy, still restoring health. */
        HEALING,
        /** A drone is docked but the station has no FE. */
        NO_POWER;

        private static final IntFunction<Status> BY_ID = ByIdMap.continuous(Enum::ordinal, values(), ByIdMap.OutOfBoundsStrategy.ZERO);
        public static final StreamCodec<ByteBuf, Status> STREAM_CODEC = ByteBufCodecs.idMapper(BY_ID, Enum::ordinal);

        public String getTranslationKey() {
            return "screen.seekerdrones.charging_station.status." + name().toLowerCase(Locale.ROOT);
        }
    }
}
