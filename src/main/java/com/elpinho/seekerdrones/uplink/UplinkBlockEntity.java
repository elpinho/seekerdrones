package com.elpinho.seekerdrones.uplink;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.annotation.Nullable;

import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.drone.DroneIndex;
import com.elpinho.seekerdrones.drone.DronePermissions;
import com.elpinho.seekerdrones.drone.DroneStats;
import com.elpinho.seekerdrones.feed.FpvFeeds;
import com.elpinho.seekerdrones.network.UplinkContactsPayload;
import com.elpinho.seekerdrones.network.UplinkTerrainPayload;
import com.elpinho.seekerdrones.operator.OperatorGroups;
import com.elpinho.seekerdrones.radar.ColorMap;
import com.elpinho.seekerdrones.radar.RadarMachineBlockEntity;
import com.elpinho.seekerdrones.radar.TerrainBuilder;
import com.elpinho.seekerdrones.registry.ModBlockEntities;
import com.elpinho.seekerdrones.station.ChargingStationRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
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
public class UplinkBlockEntity extends RadarMachineBlockEntity<ColorMap> {
    private static final String TAG_PLACER = "Placer";
    /** The most a comparator can show. */
    public static final int MAX_ALARM = 15;

    @Nullable
    private UUID placer;
    /** The intruder count for comparators, capped at {@link #MAX_ALARM}. Recounted while powered, not saved. */
    private int alarm;
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

    // --- Placer and alarm ---

    public Optional<UUID> getPlacer() {
        return Optional.ofNullable(placer);
    }

    void setPlacer(UUID placer) {
        this.placer = placer;
        setChanged();
    }

    public int getAlarm() {
        return alarm;
    }

    @Override
    protected void tickPowered(ServerLevel level) {
        if (isScanTick(level)) {
            setAlarm(level, countIntruders(level));
        }
    }

    @Override
    protected void onPowerLost(ServerLevel level) {
        setAlarm(level, 0);
        FpvFeeds.stopThroughUplink(this);
    }

    /**
     * The drones in the area that the placer isn't an operator of (no bypass: machines never check the viewer). With
     * no recorded placer, every owned drone counts and unowned drones don't.
     */
    private int countIntruders(ServerLevel level) {
        MinecraftServer server = level.getServer();
        int count = 0;
        for (DroneEntity drone : DroneIndex.drones(level)) {
            if (drone.isRemoved() || !inArea(drone.getX(), drone.getZ())) {
                continue;
            }
            DroneData data = drone.snapshotData();
            boolean intruder = placer != null
                    ? !DronePermissions.isOperator(server, data, placer)
                    : data.groupId().isPresent() || data.ownerId().isPresent();
            if (intruder && ++count >= MAX_ALARM) {
                return MAX_ALARM;
            }
        }
        return count;
    }

    /** Updates the comparators next to either half only when the count changes. */
    private void setAlarm(ServerLevel level, int alarm) {
        if (alarm == this.alarm) {
            return;
        }
        this.alarm = alarm;
        level.updateNeighbourForOutputSignal(worldPosition, getBlockState().getBlock());
        level.updateNeighbourForOutputSignal(worldPosition.above(), getBlockState().getBlock());
    }

    // --- Contacts (section 7.9) ---

    @Override
    protected CustomPacketPayload terrainPayload(ColorMap terrain) {
        return new UplinkTerrainPayload(worldPosition, terrain);
    }

    @Override
    protected CustomPacketPayload contactsPayload(ServerLevel level, ServerPlayer viewer) {
        MinecraftServer server = level.getServer();
        Vec3 center = Vec3.atCenterOf(worldPosition);
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

    /** The entity the drone is chasing or following, only for the viewer's own drones (section 7.9). */
    private static Optional<UplinkContactsPayload.Target> target(DroneEntity drone, Vec3 center) {
        Entity target = drone.getSeekTarget();
        if (target == null || target.isRemoved() || target.level() != drone.level()) {
            return Optional.empty();
        }
        String playerName = target instanceof Player player ? player.getGameProfile().getName() : "";
        return Optional.of(new UplinkContactsPayload.Target((float) (target.getX() - center.x), (float) (target.getZ() - center.z),
                target.getDisplayName(), target.getType(), playerName));
    }

    private static Optional<UplinkContactsPayload.Patrol> patrol(DroneEntity drone, DroneData data, Vec3 center) {
        GlobalPos patrolCenter = DroneStats.isPatrolling(data) ? drone.getPatrolCenter() : null;
        if (patrolCenter == null) {
            return Optional.empty();
        }
        Vec3 at = Vec3.atCenterOf(patrolCenter.pos());
        return Optional.of(new UplinkContactsPayload.Patrol((float) (at.x - center.x), (float) (at.z - center.z), DroneStats.patrolRadius(data)));
    }

    /**
     * The Charging Stations in the area whose placer the viewer is an operator of: the placer, or an operator of a
     * group the placer owns (section 7.4's station operators, without the server-operator bypass). Stations with no
     * placer aren't shown, so the map doesn't reveal other players' bases.
     */
    private List<UplinkContactsPayload.Station> stations(ServerLevel level, ServerPlayer viewer) {
        UUID id = viewer.getUUID();
        OperatorGroups groups = OperatorGroups.get(level.getServer());
        List<UplinkContactsPayload.Station> stations = new ArrayList<>();
        for (Map.Entry<BlockPos, Optional<UUID>> entry : ChargingStationRegistry.get(level).getStations().entrySet()) {
            BlockPos pos = entry.getKey();
            Optional<UUID> owner = entry.getValue();
            if (owner.isEmpty() || !inArea(pos.getX() + 0.5, pos.getZ() + 0.5)) {
                continue;
            }
            if (owner.get().equals(id) || groups.isOperatorOfAnyGroupOwnedBy(owner.get(), id)) {
                stations.add(new UplinkContactsPayload.Station(pos.getX() - worldPosition.getX(), pos.getZ() - worldPosition.getZ()));
            }
        }
        return stations;
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

    // --- Saving ---

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (placer != null) {
            tag.putUUID(TAG_PLACER, placer);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        placer = tag.hasUUID(TAG_PLACER) ? tag.getUUID(TAG_PLACER) : null;
    }
}
