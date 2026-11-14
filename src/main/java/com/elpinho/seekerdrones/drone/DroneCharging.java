package com.elpinho.seekerdrones.drone;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.annotation.Nullable;

import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.registry.ModSounds;
import com.elpinho.seekerdrones.station.ChargingStationBlockEntity;
import com.elpinho.seekerdrones.station.ChargingStationRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/**
 * Returning to a charging station, queueing for it and charging there, and flying back afterwards (sections 5.2 and
 * 5.3). Server side only.
 */
final class DroneCharging {
    private static final String TAG_CHARGING_STATION = "ChargingStation";
    private static final String TAG_DEPARTURE_POSITION = "DeparturePosition";
    private static final String TAG_HOME_GOAL = "HomeGoal";

    /** How long (ticks) the result of a charging station search is reused (section 8.4). */
    private static final int STATION_SEARCH_CACHE_TICKS = 100;
    /** Flights to a station farther than this (blocks) are split into legs, so path finding stays short. */
    private static final double STATION_LEG_LENGTH = 32;
    /** Within this distance (blocks) of the dock, the drone claims the station or queues for it (section 5.3). */
    private static final double STATION_QUEUE_DISTANCE = 4.0;
    /** How close (blocks) to the dock point the drone must get to dock. */
    private static final double DOCK_REACH_DISTANCE = 0.3;
    /** How far (blocks) from the dock point a queued drone waits. */
    private static final double QUEUE_OFFSET = 1.5;
    /** A returning drone that gets no block closer to its station for this many ticks gives up on it (section 5.2). */
    private static final int STATION_PROGRESS_TIMEOUT = 200;
    /** How close (blocks) a drone without Patrol must get to where it left to charge before hovering there. */
    private static final double HOME_REACH_DISTANCE = 0.5;

    private final DroneEntity drone;

    /** The station being returned to or charged at. Set only while RETURNING or CHARGING. */
    @Nullable
    private BlockPos chargingStation;
    /** Where the drone was when it left to charge (section 5.3). */
    @Nullable
    private GlobalPos departurePosition;
    /** Where a drone without Patrol flies back to after charging, before it hovers. */
    @Nullable
    private Vec3 homeGoal;
    /** Stations skipped until the given game time because the drone couldn't reach them (section 5.2). Not saved. */
    private final Map<BlockPos, Long> unreachableStations = new HashMap<>();
    /** The nearest usable station at the last search, or null if there was none. Reused until the expiry. */
    @Nullable
    private BlockPos cachedStation;
    private long stationSearchExpiry = Long.MIN_VALUE;
    /** Where the drone flies for the current leg of a long flight to its station, or null. */
    @Nullable
    private Vec3 stationLeg;
    private int stationLegDeadline;
    /** The closest the drone got to its station, and the tick when it last got a block closer. */
    private double stationBestDistance;
    private int stationProgressTick;
    /** Set when loaded docked: re-docking after a reload plays no dock clunk. Not saved. */
    private boolean silentRedock;

    DroneCharging(DroneEntity drone) {
        this.drone = drone;
    }

    @Nullable
    BlockPos station() {
        return chargingStation;
    }

    boolean hasHomeGoal() {
        return homeGoal != null;
    }

    void setHomeGoal(@Nullable Vec3 goal) {
        homeGoal = goal;
    }

    /** Makes the next {@link #nearestStation} search again instead of reusing the cached result. */
    void invalidateStationCache() {
        stationSearchExpiry = Long.MIN_VALUE;
    }

    /** Why a RETURNING or CHARGING drone is held up (section 2.4), or NONE. */
    DroneReason stationReason(DroneState state) {
        ServerLevel level = (ServerLevel) drone.level();
        if (chargingStation != null && level.hasChunkAt(chargingStation)
                && level.getBlockEntity(chargingStation) instanceof ChargingStationBlockEntity station) {
            if (state == DroneState.CHARGING) {
                return station.getEnergy() <= 0 ? DroneReason.STATION_NO_POWER : DroneReason.NONE;
            }
            if (station.isClaimedByOther(drone.getUUID())
                    && drone.distanceToSqr(ChargingStationBlockEntity.dockPosition(chargingStation)) <= STATION_QUEUE_DISTANCE * STATION_QUEUE_DISTANCE) {
                return DroneReason.WAITING_FOR_STATION;
            }
        }
        long now = level.getGameTime();
        return unreachableStations.values().stream().anyMatch(until -> until > now) ? DroneReason.STATION_UNREACHABLE : DroneReason.NONE;
    }

    /**
     * Starts returning once the energy left is what it takes to reach the nearest usable station and wait there
     * (section 5.2). An Explosive drone chasing a target keeps chasing, since it will be consumed anyway.
     */
    void checkReturnThreshold(DroneData data) {
        DroneState state = drone.getState();
        if (state == DroneState.RETURNING || state == DroneState.CHARGING || state == DroneState.PILOTED) {
            // A piloted drone only warns: the pilot decides when to head home (section 2.13).
            return;
        }
        if (DroneStats.isExplosive(data) && drone.hasOrAwaitsTarget()) {
            return;
        }
        BlockPos station = nearestStation(data);
        if (station == null) {
            return;
        }
        double distance = Math.sqrt(drone.distanceToSqr(ChargingStationBlockEntity.dockPosition(station)));
        if (data.energy() <= DroneStats.returnThreshold(data, distance)) {
            drone.startReturning(station);
        }
    }

    /** The nearest usable station in range, searched at most every {@link #STATION_SEARCH_CACHE_TICKS} (section 8.4). */
    @Nullable
    BlockPos nearestStation(DroneData data) {
        long now = drone.level().getGameTime();
        if (now >= stationSearchExpiry) {
            cachedStation = findStation(data, drone.position(), ServerConfig.get(ServerConfig.DRONE_CHARGING_SEARCH_RADIUS), null, false);
            stationSearchExpiry = now + STATION_SEARCH_CACHE_TICKS;
        }
        return cachedStation;
    }

    /**
     * The usable station nearest to the drone among those within {@code radius} of {@code around}, from the
     * dimension's station registry (section 8.3). Stations the drone couldn't reach recently are skipped.
     *
     * @param exclude     a station to leave out, or null
     * @param requireFree only stations in loaded chunks that no other drone holds
     */
    @Nullable
    private BlockPos findStation(DroneData data, Vec3 around, double radius, @Nullable BlockPos exclude, boolean requireFree) {
        ServerLevel level = (ServerLevel) drone.level();
        long now = level.getGameTime();
        unreachableStations.values().removeIf(until -> until <= now);
        double radiusSqr = radius * radius;
        double bestSqr = Double.MAX_VALUE;
        BlockPos best = null;
        for (Map.Entry<BlockPos, Optional<UUID>> entry : ChargingStationRegistry.get(level).getStations().entrySet()) {
            BlockPos pos = entry.getKey();
            Vec3 center = Vec3.atCenterOf(pos);
            if (pos.equals(exclude) || center.distanceToSqr(around) > radiusSqr || unreachableStations.containsKey(pos)) {
                continue;
            }
            double distanceSqr = drone.distanceToSqr(center);
            if (distanceSqr >= bestSqr || !DronePermissions.canUseStation(level.getServer(), data, entry.getValue())) {
                continue;
            }
            if (requireFree && !(level.hasChunkAt(pos) && level.getBlockEntity(pos) instanceof ChargingStationBlockEntity station
                    && !station.isClaimedByOther(drone.getUUID()))) {
                continue;
            }
            bestSqr = distanceSqr;
            best = pos;
        }
        return best;
    }

    /**
     * Heads for the station, after the drone has dropped everything else (see {@link DroneEntity#startReturning}).
     * Where the drone is now is where it comes back to (section 5.3).
     */
    void startReturning(BlockPos station) {
        homeGoal = null;
        departurePosition = drone.currentPosition();
        setStation(station);
    }

    /** Heads for another station, releasing any claim on the previous one. */
    private void setStation(BlockPos station) {
        if (!station.equals(chargingStation)) {
            releaseStation();
        }
        chargingStation = station;
        stationLeg = null;
        stationBestDistance = Double.MAX_VALUE;
        stationProgressTick = drone.tickCount;
        drone.requestPathRecheck();
        drone.stopNavigating();
        drone.setState(DroneState.RETURNING);
    }

    /** Releases the claim on the current station, if its chunk is loaded (otherwise the claim lapses on its own). */
    void releaseStation() {
        if (chargingStation != null && drone.level().hasChunkAt(chargingStation)
                && drone.level().getBlockEntity(chargingStation) instanceof ChargingStationBlockEntity station) {
            station.release(drone.getUUID());
        }
        chargingStation = null;
        stationLeg = null;
    }

    /** A remote command took over a RETURNING or CHARGING drone: gives up the station and the trip back. */
    void abandon() {
        releaseStation();
        departurePosition = null;
        silentRedock = false;
    }

    /**
     * RETURNING and CHARGING (sections 5.2 and 5.3). The drone flies to its station, claims it once close, and docks.
     * If another drone holds it, the drone looks for a free station nearby, or else waits next to it. A station that
     * is gone, no longer usable or unreachable is replaced by the next nearest one.
     */
    void tick(DroneData data, boolean staggered) {
        if (chargingStation == null) {
            endReturn(data);
            return;
        }
        BlockPos pos = chargingStation;
        ServerLevel level = (ServerLevel) drone.level();
        ChargingStationBlockEntity station = null;
        if (level.hasChunkAt(pos)) {
            if (!(level.getBlockEntity(pos) instanceof ChargingStationBlockEntity found)) {
                // Removed without unregistering (e.g. replaced by a command): forget it.
                ChargingStationRegistry.get(level).remove(pos);
                redirect(data);
                return;
            }
            station = found;
            if (staggered && !DronePermissions.canUseStation(level.getServer(), data, station.getOwner())) {
                redirect(data);
                return;
            }
        }
        Vec3 dock = ChargingStationBlockEntity.dockPosition(pos);
        double distance = Math.sqrt(drone.distanceToSqr(dock));
        if (drone.getState() == DroneState.CHARGING) {
            charge(data, station, dock, distance, staggered);
            return;
        }
        double speed = ServerConfig.get(ServerConfig.DRONE_CRUISE_SPEED);
        double acceleration = ServerConfig.get(ServerConfig.DRONE_ACCELERATION);
        if (station != null && distance <= STATION_QUEUE_DISTANCE) {
            if (!station.claim(drone.getUUID())) {
                queue(data, pos, dock, staggered);
                return;
            }
            if (distance <= DOCK_REACH_DISTANCE) {
                drone.stopNavigating();
                drone.setState(DroneState.CHARGING);
                if (!silentRedock) {
                    level.playSound(null, pos, ModSounds.CHARGING_STATION_DOCK.get(), SoundSource.BLOCKS,
                            ServerConfig.get(ServerConfig.SOUNDS_DOCK_VOLUME).floatValue(), ServerConfig.get(ServerConfig.SOUNDS_DOCK_PITCH).floatValue());
                }
                silentRedock = false;
                drone.droneMoveControl().hold(acceleration);
                return;
            }
        }
        if (distance < stationBestDistance - 1) {
            stationBestDistance = distance;
            stationProgressTick = drone.tickCount;
        } else if (drone.tickCount - stationProgressTick > STATION_PROGRESS_TIMEOUT) {
            markUnreachable(data, pos);
            return;
        }
        if (distance <= STATION_LEG_LENGTH) {
            stationLeg = null;
            drone.steerTowards(dock, speed, acceleration, dock, staggered, false);
            return;
        }
        // Too far for one path search: fly legs toward the station, raised over obstacles like patrol waypoints.
        double reach = Math.max(DronePatrol.WAYPOINT_REACH_DISTANCE, speed * speed / acceleration);
        if (stationLeg == null || drone.distanceToSqr(stationLeg) <= reach * reach || drone.tickCount > stationLegDeadline) {
            Vec3 spot = drone.position().add(dock.subtract(drone.position()).normalize().scale(STATION_LEG_LENGTH));
            Vec3 raised = drone.patrol().patrolHeightAt(spot);
            stationLeg = raised != null ? raised : spot;
            stationLegDeadline = drone.tickCount + (int) (2 * STATION_LEG_LENGTH / speed) + DronePatrol.WAYPOINT_GRACE_TICKS;
            drone.requestPathRecheck();
        }
        drone.steerTowards(stationLeg, speed, acceleration, null, staggered, false);
    }

    /**
     * The station is held by another drone (section 5.3): switch to a free usable station near it if there is one,
     * otherwise wait right next to it. Waiting doesn't count against the progress timeout.
     */
    private void queue(DroneData data, BlockPos pos, Vec3 dock, boolean staggered) {
        stationProgressTick = drone.tickCount;
        drone.energy().markQueued();
        if (staggered) {
            BlockPos alternate = findStation(data, Vec3.atCenterOf(pos), ServerConfig.get(ServerConfig.DRONE_CHARGING_ALTERNATE_RADIUS), pos, true);
            if (alternate != null) {
                setStation(alternate);
                return;
            }
        }
        Vec3 away = new Vec3(drone.getX() - dock.x, 0, drone.getZ() - dock.z);
        Vec3 direction = away.lengthSqr() > 1.0E-4 ? away.normalize() : new Vec3(1, 0, 0);
        Vec3 spot = dock.add(direction.scale(QUEUE_OFFSET));
        if (!drone.hasRoomAt(spot)) {
            spot = dock.add(0, QUEUE_OFFSET, 0);
        }
        double acceleration = ServerConfig.get(ServerConfig.DRONE_ACCELERATION);
        if (drone.hasRoomAt(spot)) {
            drone.steerTowards(spot, ServerConfig.get(ServerConfig.DRONE_CRUISE_SPEED), acceleration, spot, staggered, false);
        } else {
            drone.stopNavigating();
            drone.droneMoveControl().hold(acceleration);
        }
    }

    /**
     * Docked (section 5.3): renews the claim, takes up to the station's charge rate in FE per tick, and heals
     * {@code healPercentPerSecond} of its max HP per second while the station has FE and repair fluid. With no FE it waits. Done at full energy and either full health or no
     * repair fluid left: a drone is never held just to wait for fluid.
     */
    private void charge(DroneData data, @Nullable ChargingStationBlockEntity station, Vec3 dock, double distance, boolean staggered) {
        if (station == null || !station.claim(drone.getUUID())) {
            drone.setState(DroneState.RETURNING);
            return;
        }
        double acceleration = ServerConfig.get(ServerConfig.DRONE_ACCELERATION);
        if (distance > DOCK_REACH_DISTANCE) {
            drone.steerTowards(dock, ServerConfig.get(ServerConfig.DRONE_CRUISE_SPEED), acceleration, dock, staggered, false);
        } else {
            if (drone.hasPathGoal()) {
                drone.stopNavigating();
            }
            drone.droneMoveControl().hold(acceleration);
        }
        int maxEnergy = DroneStats.maxEnergy(data);
        if (station.getEnergy() > 0) {
            int missing = maxEnergy - data.energy();
            int taken = 0;
            if (missing > 0) {
                taken = station.drainForDrone(Math.min(missing, station.getChargeRate()));
                data = data.withEnergy(data.energy() + taken);
                drone.replaceData(data);
            }
            float healed = 0;
            if (drone.getHealth() < drone.getMaxHealth() && station.canRepair()) {
                float perTick = (float) (drone.getMaxHealth() * ServerConfig.get(ServerConfig.CHARGING_STATION_HEAL_PERCENT_PER_SECOND) / 100 / 20);
                float heal = Math.min(drone.getMaxHealth() - drone.getHealth(), perTick);
                healed = station.useRepairFluid(heal);
                drone.setHealth(Math.min(drone.getMaxHealth(), drone.getHealth() + healed));
            }
            if (taken > 0 || healed > 0) {
                station.markWorking(healed > 0);
            }
        }
        if (data.energy() >= maxEnergy && (drone.getHealth() >= drone.getMaxHealth() || !station.canRepair())) {
            drone.playDroneSound(ModSounds.DRONE_CHARGED.get(), ServerConfig.get(ServerConfig.SOUNDS_CHARGED_VOLUME), ServerConfig.get(ServerConfig.SOUNDS_CHARGED_PITCH));
            endReturn(data);
        }
    }

    /** Skips a station for {@code drone.unreachableStationCooldown} ticks and heads for the next one (section 5.2). */
    private void markUnreachable(DroneData data, BlockPos pos) {
        unreachableStations.put(pos, drone.level().getGameTime() + ServerConfig.get(ServerConfig.DRONE_UNREACHABLE_STATION_COOLDOWN));
        redirect(data);
    }

    /** Picks the next nearest usable station, or carries on without one until the energy runs out. */
    private void redirect(DroneData data) {
        releaseStation();
        stationSearchExpiry = Long.MIN_VALUE;
        BlockPos next = nearestStation(data);
        if (next != null) {
            setStation(next);
        } else {
            endReturn(data);
        }
    }

    /**
     * Done charging, or no station left to go to. A Patrol drone resumes patrolling from the nearest waypoint. Any
     * other drone flies back to where it left and hovers there (section 5.3).
     */
    private void endReturn(DroneData data) {
        silentRedock = false;
        releaseStation();
        drone.stopNavigating();
        if (!DroneStats.isPatrolling(data) && departurePosition != null && departurePosition.dimension() == drone.level().dimension()) {
            homeGoal = Vec3.atBottomCenterOf(departurePosition.pos());
        }
        departurePosition = null;
        drone.patrol().reset();
        drone.requestPathRecheck();
        drone.setState(DroneState.IDLE);
    }

    /** A drone without Patrol flies back to where it left to charge, then hovers there. */
    void flyHome(DroneData data, boolean staggered) {
        if (DroneStats.isPatrolling(data)) {
            homeGoal = null;
            return;
        }
        if (drone.distanceToSqr(homeGoal) <= HOME_REACH_DISTANCE * HOME_REACH_DISTANCE) {
            homeGoal = null;
            drone.stopNavigating();
            return;
        }
        if (!drone.steerTowards(homeGoal, ServerConfig.get(ServerConfig.DRONE_CRUISE_SPEED), ServerConfig.get(ServerConfig.DRONE_ACCELERATION),
                homeGoal, staggered, false)) {
            // Can't get back: hover where it is.
            homeGoal = null;
            drone.stopNavigating();
        }
    }

    // --- Persistence ---

    void save(CompoundTag tag) {
        if (chargingStation != null) {
            BlockPos.CODEC.encodeStart(NbtOps.INSTANCE, chargingStation).result().ifPresent(pos -> tag.put(TAG_CHARGING_STATION, pos));
        }
        if (departurePosition != null) {
            GlobalPos.CODEC.encodeStart(NbtOps.INSTANCE, departurePosition).result().ifPresent(pos -> tag.put(TAG_DEPARTURE_POSITION, pos));
        }
        if (homeGoal != null) {
            Vec3.CODEC.encodeStart(NbtOps.INSTANCE, homeGoal).result().ifPresent(pos -> tag.put(TAG_HOME_GOAL, pos));
        }
    }

    /** Reads the station, departure position and home goal. The caller then decides with {@link #resumeAfterLoad} or {@link #forgetStation}. */
    void load(CompoundTag tag) {
        chargingStation = tag.contains(TAG_CHARGING_STATION)
                ? BlockPos.CODEC.parse(NbtOps.INSTANCE, tag.get(TAG_CHARGING_STATION)).result().orElse(null)
                : null;
        departurePosition = tag.contains(TAG_DEPARTURE_POSITION)
                ? GlobalPos.CODEC.parse(NbtOps.INSTANCE, tag.get(TAG_DEPARTURE_POSITION)).result().orElse(null)
                : null;
        homeGoal = tag.contains(TAG_HOME_GOAL) ? Vec3.CODEC.parse(NbtOps.INSTANCE, tag.get(TAG_HOME_GOAL)).result().orElse(null) : null;
    }

    /** Loaded RETURNING or CHARGING with a station: heads for it again. Claims aren't saved, so a docked drone re-docks, without a second dock clunk. */
    void resumeAfterLoad(boolean docked) {
        silentRedock = docked;
        setStation(chargingStation);
    }

    /** Loaded in a state that has no station: drops the loaded one without touching its claim. */
    void forgetStation() {
        chargingStation = null;
    }
}
