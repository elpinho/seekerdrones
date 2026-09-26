package com.elpinho.seekerdrones.network;

import java.util.Optional;

import com.elpinho.seekerdrones.SeekerDrones;
import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.drone.DroneState;
import com.elpinho.seekerdrones.drone.DroneStats;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client: a snapshot of a drone for the read-only status screen (DESIGN.md section 2.4).
 *
 * @param entityId     the drone entity, or {@link #NO_ENTITY} for a drone item
 * @param open         true to open the screen, false to refresh one that is already open
 * @param patrolCenter the patrol center the drone uses (section 3.2), or the configured one for a drone item
 * @param patrolRadius the radius the drone patrols at, capped at {@code maxPatrolRadius} (section 3.2)
 */
public record DroneStatusPayload(int entityId, boolean open, DroneData data, int maxEnergy, float maxHealth, DroneState state,
        Optional<GlobalPos> patrolCenter, int patrolRadius, int maxPatrolRadius) implements CustomPacketPayload {
    public static final int NO_ENTITY = -1;

    public static final Type<DroneStatusPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SeekerDrones.MODID, "drone_status"));

    private static final StreamCodec<ByteBuf, Optional<GlobalPos>> PATROL_CENTER_CODEC = ByteBufCodecs.optional(GlobalPos.STREAM_CODEC);

    /** Written by hand: {@link StreamCodec#composite} only goes up to six fields. */
    public static final StreamCodec<ByteBuf, DroneStatusPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                ByteBufCodecs.VAR_INT.encode(buf, payload.entityId());
                ByteBufCodecs.BOOL.encode(buf, payload.open());
                DroneData.STREAM_CODEC.encode(buf, payload.data());
                ByteBufCodecs.VAR_INT.encode(buf, payload.maxEnergy());
                ByteBufCodecs.FLOAT.encode(buf, payload.maxHealth());
                DroneState.STREAM_CODEC.encode(buf, payload.state());
                PATROL_CENTER_CODEC.encode(buf, payload.patrolCenter());
                ByteBufCodecs.VAR_INT.encode(buf, payload.patrolRadius());
                ByteBufCodecs.VAR_INT.encode(buf, payload.maxPatrolRadius());
            },
            buf -> new DroneStatusPayload(
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.BOOL.decode(buf),
                    DroneData.STREAM_CODEC.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.FLOAT.decode(buf),
                    DroneState.STREAM_CODEC.decode(buf),
                    PATROL_CENTER_CODEC.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf)));

    public static DroneStatusPayload of(DroneEntity drone, boolean open) {
        DroneData data = drone.snapshotData();
        return new DroneStatusPayload(drone.getId(), open, data, DroneStats.maxEnergy(data), drone.getMaxHealth(), drone.getState(),
                Optional.ofNullable(drone.getPatrolCenter()), DroneStats.patrolRadius(data), DroneStats.maxPatrolRadius(data));
    }

    /** Opens the screen for a drone item in the player's hand. Its state is ignored. */
    public static DroneStatusPayload ofItem(DroneData data) {
        return new DroneStatusPayload(NO_ENTITY, true, data, DroneStats.maxEnergy(data), DroneStats.maxHealth(data), DroneState.IDLE,
                data.config().patrolCenter(), DroneStats.patrolRadius(data), DroneStats.maxPatrolRadius(data));
    }

    public boolean isDeployed() {
        return entityId != NO_ENTITY;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
