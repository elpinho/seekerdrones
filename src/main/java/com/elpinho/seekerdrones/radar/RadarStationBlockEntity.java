package com.elpinho.seekerdrones.radar;

import java.util.ArrayList;
import java.util.List;

import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.drone.DroneIndex;
import com.elpinho.seekerdrones.drone.DronePermissions;
import com.elpinho.seekerdrones.network.RadarContactsPayload;
import com.elpinho.seekerdrones.network.RadarTerrainPayload;
import com.elpinho.seekerdrones.registry.ModBlockEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Drone Radar Station (DESIGN.md section 7.8): the shared radar machine with the phosphor height map, sending each
 * viewer only their own drones.
 */
public class RadarStationBlockEntity extends RadarMachineBlockEntity<TerrainMap> {
    /** Client only: where the radar bar points, in degrees, so it stays put while unpowered. Not saved. */
    public float barAngle;

    public RadarStationBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.RADAR_STATION.get(), pos, state);
    }

    @Override
    protected int energyCapacity() {
        return ServerConfig.get(ServerConfig.RADAR_STATION_ENERGY_CAPACITY);
    }

    @Override
    protected int energyPerTick() {
        return ServerConfig.get(ServerConfig.RADAR_STATION_ENERGY_PER_TICK);
    }

    @Override
    public int radius() {
        return ServerConfig.get(ServerConfig.RADAR_STATION_RADIUS);
    }

    @Override
    protected int scanInterval() {
        return ServerConfig.get(ServerConfig.RADAR_STATION_SCAN_INTERVAL);
    }

    @Override
    protected int terrainRefreshInterval() {
        return ServerConfig.get(ServerConfig.RADAR_STATION_TERRAIN_REFRESH_INTERVAL);
    }

    @Override
    protected int terrainChunksPerTick() {
        return ServerConfig.get(ServerConfig.RADAR_STATION_TERRAIN_CHUNKS_PER_TICK);
    }

    @Override
    protected TerrainBuilder<TerrainMap> newTerrainBuilder(ServerLevel level, int radius) {
        return TerrainBuilder.heights(level, worldPosition, radius);
    }

    @Override
    protected List<CustomPacketPayload> terrainPayloads(TerrainMap terrain) {
        return List.of(new RadarTerrainPayload(worldPosition, terrain));
    }

    @Override
    protected CustomPacketPayload contactsPayload(ServerLevel level, ServerPlayer viewer) {
        return new RadarContactsPayload(worldPosition, contacts(level, viewer));
    }

    /** The viewer's own drones inside the circle (section 6.3, no server-operator bypass). */
    private List<RadarContactsPayload.Contact> contacts(ServerLevel level, ServerPlayer viewer) {
        Vec3 center = Vec3.atCenterOf(worldPosition);
        List<RadarContactsPayload.Contact> contacts = new ArrayList<>();
        for (DroneEntity drone : DroneIndex.drones(level)) {
            if (drone.isRemoved() || !inArea(drone.getX(), drone.getZ())) {
                continue;
            }
            if (DronePermissions.isOperator(level.getServer(), drone.snapshotData(), viewer.getUUID())) {
                contacts.add(new RadarContactsPayload.Contact(drone.getDroneId(), (float) (drone.getX() - center.x),
                        (float) (drone.getZ() - center.z), drone.getLabel(), drone.getColor()));
            }
        }
        return contacts;
    }

    // --- Menu ---

    @Override
    public Component getDisplayName() {
        return Component.translatable("screen.seekerdrones.radar_station");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        if (player instanceof ServerPlayer serverPlayer) {
            addViewer(serverPlayer);
        }
        return new RadarStationMenu(containerId, this);
    }
}
