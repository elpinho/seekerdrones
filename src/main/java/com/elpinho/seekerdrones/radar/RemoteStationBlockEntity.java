package com.elpinho.seekerdrones.radar;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.annotation.Nullable;

import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.drone.DroneIndex;
import com.elpinho.seekerdrones.drone.DronePermissions;
import com.elpinho.seekerdrones.drone.DroneStats;
import com.elpinho.seekerdrones.feed.FpvFeeds;
import com.elpinho.seekerdrones.network.MapMarkers;
import com.elpinho.seekerdrones.operator.OperatorGroups;
import com.elpinho.seekerdrones.remote.RemoteReach;
import com.elpinho.seekerdrones.station.ChargingStationRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The radar machines a drone's remote can be opened through (DESIGN.md sections 7.9 and 7.10): the Satellite Uplink
 * and the Command Center. They share the station reach rule (see {@code RemoteControl.findThroughStation}), the
 * intruder alarm, which goes by the placer, and the map markers of their snapshots. Remote sessions opened through one
 * end when it loses power.
 */
public abstract class RemoteStationBlockEntity<M extends RadarMap> extends RadarMachineBlockEntity<M> {
    private static final String TAG_PLACER = "Placer";
    /** The most a comparator can show. */
    public static final int MAX_ALARM = 15;

    @Nullable
    private UUID placer;
    /** The intruder count for comparators, capped at {@link #MAX_ALARM}. Recounted while powered, not saved. */
    private int alarm;

    protected RemoteStationBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    /** The remote screen's reach reason when this station has no power. */
    public abstract RemoteReach noPowerReach();

    /** The remote screen's reach reason when the drone is outside this station's area. */
    public abstract RemoteReach outOfAreaReach();

    // --- Placer and alarm ---

    public Optional<UUID> getPlacer() {
        return Optional.ofNullable(placer);
    }

    public void setPlacer(UUID placer) {
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
        FpvFeeds.stopThroughStation(this);
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

    // --- Map markers ---

    /** The station's center, which snapshot positions are relative to. */
    protected Vec3 center() {
        return Vec3.atCenterOf(worldPosition);
    }

    /** The entity the drone is chasing or following, if any. */
    protected static Optional<MapMarkers.Target> target(DroneEntity drone, Vec3 center) {
        Entity target = drone.getSeekTarget();
        if (target == null || target.isRemoved() || target.level() != drone.level()) {
            return Optional.empty();
        }
        String playerName = target instanceof Player player ? player.getGameProfile().getName() : "";
        return Optional.of(new MapMarkers.Target((float) (target.getX() - center.x), (float) (target.getZ() - center.z),
                target.getDisplayName(), target.getType(), playerName));
    }

    /** The drone's patrol ring, only with a Patrol upgrade and a center. */
    protected static Optional<MapMarkers.Patrol> patrol(DroneEntity drone, DroneData data, Vec3 center) {
        GlobalPos patrolCenter = DroneStats.isPatrolling(data) ? drone.getPatrolCenter() : null;
        if (patrolCenter == null) {
            return Optional.empty();
        }
        Vec3 at = Vec3.atCenterOf(patrolCenter.pos());
        return Optional.of(new MapMarkers.Patrol((float) (at.x - center.x), (float) (at.z - center.z), DroneStats.patrolRadius(data)));
    }

    /**
     * The Charging Stations in the area whose placer the viewer is an operator of: the placer, or an operator of a
     * group the placer owns (section 7.4's station operators, without the server-operator bypass). Stations with no
     * placer aren't shown, so the map doesn't reveal other players' bases.
     */
    protected List<MapMarkers.Station> stations(ServerLevel level, ServerPlayer viewer) {
        UUID id = viewer.getUUID();
        OperatorGroups groups = OperatorGroups.get(level.getServer());
        List<MapMarkers.Station> stations = new ArrayList<>();
        for (Map.Entry<BlockPos, Optional<UUID>> entry : ChargingStationRegistry.get(level).getStations().entrySet()) {
            BlockPos pos = entry.getKey();
            Optional<UUID> owner = entry.getValue();
            if (owner.isEmpty() || !inArea(pos.getX() + 0.5, pos.getZ() + 0.5)) {
                continue;
            }
            if (owner.get().equals(id) || groups.isOperatorOfAnyGroupOwnedBy(owner.get(), id)) {
                stations.add(new MapMarkers.Station(pos.getX() - worldPosition.getX(), pos.getZ() - worldPosition.getZ()));
            }
        }
        return stations;
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
