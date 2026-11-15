package com.elpinho.seekerdrones.uplink;

import java.util.ArrayList;
import java.util.List;

import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.drone.DroneIndex;
import com.elpinho.seekerdrones.drone.DronePermissions;
import com.elpinho.seekerdrones.network.UplinkContactsPayload;
import com.elpinho.seekerdrones.network.UplinkTerrainPayload;
import com.elpinho.seekerdrones.radar.ColorMap;
import com.elpinho.seekerdrones.radar.RemoteStationBlockEntity;
import com.elpinho.seekerdrones.radar.TerrainBuilder;
import com.elpinho.seekerdrones.registry.ModBlockEntities;
import com.elpinho.seekerdrones.remote.RemoteReach;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Drone Satellite Uplink (DESIGN.md section 7.9): the shared radar machine with a map in vanilla map colors. Each
 * viewer gets their drones identified and everyone else's unidentified, plus the Charging Stations their drones could
 * use. While powered it also counts the drones in its area that its placer isn't an operator of, for the comparator
 * output, whether or not anyone watches. Remote sessions opened through it (section 7.9) end when it loses power.
 */
public class UplinkBlockEntity extends RemoteStationBlockEntity<ColorMap> {
    /** Client only: the dish's aim, eased by the renderer. Not saved. */
    public final DishAim dishAim = new DishAim();

    public UplinkBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.UPLINK.get(), pos, state);
    }

    // --- Config ---

    @Override
    protected int energyCapacity() {
        return ServerConfig.get(ServerConfig.UPLINK_ENERGY_CAPACITY);
    }

    @Override
    protected int energyPerTick() {
        return ServerConfig.get(ServerConfig.UPLINK_ENERGY_PER_TICK);
    }

    @Override
    public int radius() {
        return ServerConfig.get(ServerConfig.UPLINK_RADIUS);
    }

    @Override
    protected int scanInterval() {
        return ServerConfig.get(ServerConfig.UPLINK_SCAN_INTERVAL);
    }

    @Override
    protected int terrainRefreshInterval() {
        return ServerConfig.get(ServerConfig.UPLINK_TERRAIN_REFRESH_INTERVAL);
    }

    @Override
    protected int terrainChunksPerTick() {
        return ServerConfig.get(ServerConfig.UPLINK_TERRAIN_CHUNKS_PER_TICK);
    }

    @Override
    protected TerrainBuilder<ColorMap> newTerrainBuilder(ServerLevel level, int radius) {
        return TerrainBuilder.colors(level, worldPosition, radius);
    }

    @Override
    public RemoteReach noPowerReach() {
        return RemoteReach.NO_POWER;
    }

    @Override
    public RemoteReach outOfAreaReach() {
        return RemoteReach.OUT_OF_AREA;
    }

    // --- Contacts (section 7.9) ---

    @Override
    protected List<CustomPacketPayload> terrainPayloads(ColorMap terrain) {
        return List.of(new UplinkTerrainPayload(worldPosition, terrain));
    }

    @Override
    protected CustomPacketPayload contactsPayload(ServerLevel level, ServerPlayer viewer) {
        MinecraftServer server = level.getServer();
        Vec3 center = center();
        List<UplinkContactsPayload.Identified> identified = new ArrayList<>();
        List<UplinkContactsPayload.Unidentified> unidentified = new ArrayList<>();
        for (DroneEntity drone : DroneIndex.drones(level)) {
            if (drone.isRemoved() || !inArea(drone.getX(), drone.getZ())) {
                continue;
            }
            float dx = (float) (drone.getX() - center.x);
            float dy = (float) (drone.getY() - worldPosition.getY());
            float dz = (float) (drone.getZ() - center.z);
            DroneData data = drone.snapshotData();
            // The operator check has no server-operator bypass: the map is gameplay, not moderation.
            if (DronePermissions.isOperator(server, data, viewer.getUUID())) {
                identified.add(new UplinkContactsPayload.Identified(drone.getDroneId(), dx, dy, dz, drone.getYRot(), drone.getLabel(),
                        drone.getColor(), patrol(drone, data, center), target(drone, center)));
            } else {
                unidentified.add(new UplinkContactsPayload.Unidentified(dx, dy, dz, drone.getYRot()));
            }
        }
        return new UplinkContactsPayload(worldPosition, identified, unidentified, stations(level, viewer));
    }

    // --- Menu ---

    @Override
    public Component getDisplayName() {
        return Component.translatable("screen.seekerdrones.uplink");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        if (player instanceof ServerPlayer serverPlayer) {
            addViewer(serverPlayer);
        }
        return new UplinkMenu(containerId, this);
    }
}
