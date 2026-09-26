package com.elpinho.seekerdrones.network;

import com.elpinho.seekerdrones.SeekerDrones;
import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.drone.DroneState;
import com.elpinho.seekerdrones.drone.DroneStats;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client: a snapshot of a drone for the read-only status screen (DESIGN.md section 2.4).
 *
 * @param entityId the drone entity, or {@link #NO_ENTITY} for a drone item
 * @param open     true to open the screen, false to refresh one that is already open
 */
public record DroneStatusPayload(int entityId, boolean open, DroneData data, int maxEnergy, float maxHealth, DroneState state)
        implements CustomPacketPayload {
    public static final int NO_ENTITY = -1;

    public static final Type<DroneStatusPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SeekerDrones.MODID, "drone_status"));

    public static final StreamCodec<ByteBuf, DroneStatusPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, DroneStatusPayload::entityId,
            ByteBufCodecs.BOOL, DroneStatusPayload::open,
            DroneData.STREAM_CODEC, DroneStatusPayload::data,
            ByteBufCodecs.VAR_INT, DroneStatusPayload::maxEnergy,
            ByteBufCodecs.FLOAT, DroneStatusPayload::maxHealth,
            DroneState.STREAM_CODEC, DroneStatusPayload::state,
            DroneStatusPayload::new);

    public static DroneStatusPayload of(DroneEntity drone, boolean open) {
        DroneData data = drone.snapshotData();
        return new DroneStatusPayload(drone.getId(), open, data, DroneStats.maxEnergy(data), drone.getMaxHealth(), drone.getState());
    }

    /** Opens the screen for a drone item in the player's hand. Its state is ignored. */
    public static DroneStatusPayload ofItem(DroneData data) {
        return new DroneStatusPayload(NO_ENTITY, true, data, DroneStats.maxEnergy(data), DroneStats.maxHealth(data), DroneState.IDLE);
    }

    public boolean isDeployed() {
        return entityId != NO_ENTITY;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
