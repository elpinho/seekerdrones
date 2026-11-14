package com.elpinho.seekerdrones.drone;

import javax.annotation.Nullable;

import com.elpinho.seekerdrones.config.ServerConfig;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Flying the Patrol upgrade's circle of waypoints (section 3.2). Server side only, nothing saved.
 */
final class DronePatrol {
    /** At least this many patrol waypoints, however small the circle (section 3.2). */
    private static final int MIN_PATROL_WAYPOINTS = 8;
    /** How close (blocks) the drone must get to a patrol waypoint before heading for the next one. */
    static final double WAYPOINT_REACH_DISTANCE = 1.0;
    /** Distance (blocks) between the points checked for obstacles along a patrol leg; less than the drone's width. */
    private static final double LEG_SAMPLE_SPACING = 0.5;
    /** Extra ticks on top of twice the straight flight time before a patrol waypoint is given up. */
    static final int WAYPOINT_GRACE_TICKS = 60;
    /** {@link #patrolWaypoint} value: no waypoint is usable, so the drone hovers at the patrol center. */
    private static final int NO_FREE_WAYPOINT = -2;

    private final DroneEntity drone;

    /** The patrol waypoint being flown to, -1 to pick the nearest one, or {@link #NO_FREE_WAYPOINT}. */
    private int patrolWaypoint = -1;
    /** Where the drone flies for the current patrol waypoint, raised over any obstacle. Null if none is selected. */
    @Nullable
    private Vec3 patrolGoal;
    /** The patrol circle's center (at the patrol height) and radius when the waypoint was picked. */
    @Nullable
    private Vec3 patrolCircleCenter;
    private double patrolCircleRadius;
    /** The tick after which the current patrol waypoint is given up. */
    private int waypointDeadline;
    /** Patrol waypoints skipped in a row because no path reached them. */
    private int unreachableWaypoints;

    DronePatrol(DroneEntity drone) {
        this.drone = drone;
    }

    /** Starts the next patrol from the waypoint nearest to wherever the drone is by then. */
    void reset() {
        patrolWaypoint = -1;
    }

    /** With no target: a Patrol drone flies its circle, any other drone hovers where it is. */
    void tick(DroneData data, boolean staggered) {
        if (!DroneStats.isPatrolling(data)) {
            if (drone.getState() == DroneState.PATROLLING) {
                // The Patrol upgrades were removed mid-patrol.
                drone.stopNavigating();
                patrolWaypoint = -1;
                drone.setState(DroneState.IDLE);
            }
            return;
        }
        GlobalPos center = drone.getPatrolCenter();
        if (center == null) {
            // Fallback 4: e.g. a summoned drone. Wherever it is now becomes its rest position.
            center = drone.restHere();
        }
        if (drone.getState() != DroneState.PATROLLING) {
            drone.setState(DroneState.PATROLLING);
            patrolWaypoint = -1;
        }
        // The drone patrols at the center's own height (section 3.2).
        Vec3 centerPos = Vec3.atBottomCenterOf(center.pos());
        double radius = DroneStats.patrolRadius(data);
        if (!centerPos.equals(patrolCircleCenter) || radius != patrolCircleRadius) {
            // A new center or radius: start over from the nearest waypoint of the new circle.
            patrolCircleCenter = centerPos;
            patrolCircleRadius = radius;
            patrolWaypoint = -1;
        }
        int count = patrolWaypointCount(radius);
        double speed = DroneStats.patrolSpeed(data);
        double acceleration = ServerConfig.get(ServerConfig.DRONE_ACCELERATION);
        if (patrolWaypoint < 0 || patrolWaypoint >= count) {
            if (!staggered && patrolWaypoint == NO_FREE_WAYPOINT) {
                // Every waypoint was skipped: hover at the center and look again on the next staggered tick.
                drone.steerTowards(centerPos, speed, acceleration, centerPos, false, false);
                return;
            }
            selectWaypoint(nearestWaypoint(centerPos, count), centerPos, radius, count, speed);
        }
        if (patrolWaypoint == NO_FREE_WAYPOINT) {
            drone.steerTowards(centerPos, speed, acceleration, centerPos, staggered, false);
            return;
        }
        Vec3 goal = patrolGoal;
        // Waypoints are flown through, not stopped at. The reach distance covers the drone's turning circle at patrol
        // speed, so it can't end up circling a waypoint it keeps missing. On a small circle it is capped at half the
        // gap between adjacent waypoints, or every waypoint would count as reached and the drone would never fly it.
        double halfGap = radius * Math.sin(Math.PI / count);
        double reach = Math.min(Math.max(WAYPOINT_REACH_DISTANCE, speed * speed / acceleration), halfGap);
        boolean reached = drone.distanceToSqr(goal) <= reach * reach;
        boolean timedOut = drone.tickCount > waypointDeadline;
        boolean reachable = drone.steerTowards(goal, speed, acceleration, null, staggered, false);
        if (reached) {
            unreachableWaypoints = 0;
        } else if (!reachable || timedOut) {
            // Each unreachable waypoint costs a path search, so stop trying once a full lap has failed.
            if (++unreachableWaypoints >= count) {
                unreachableWaypoints = 0;
                patrolWaypoint = NO_FREE_WAYPOINT;
                patrolGoal = null;
                return;
            }
        } else {
            return;
        }
        selectWaypoint((patrolWaypoint + 1) % count, centerPos, radius, count, speed);
    }

    private static int patrolWaypointCount(double radius) {
        double spacing = ServerConfig.get(ServerConfig.UPGRADES_PATROL_WAYPOINT_SPACING);
        return Math.max(MIN_PATROL_WAYPOINTS, (int) Math.ceil(2 * Math.PI * radius / spacing));
    }

    /** Waypoint positions go counter-clockwise seen from above (east, then north). */
    private static Vec3 waypoint(Vec3 center, double radius, int count, int index) {
        double angle = 2 * Math.PI * index / count;
        return new Vec3(center.x + radius * Math.cos(angle), center.y, center.z - radius * Math.sin(angle));
    }

    private int nearestWaypoint(Vec3 center, int count) {
        double angle = Math.atan2(-(drone.getZ() - center.z), drone.getX() - center.x);
        return Math.floorMod((int) Math.round(angle / (2 * Math.PI) * count), count);
    }

    /**
     * Picks the first usable waypoint from {@code start} on, skipping spots in unloaded chunks and obstacles too tall
     * to climb over. Sets {@link #NO_FREE_WAYPOINT} if none is usable.
     */
    private void selectWaypoint(int start, Vec3 center, double radius, int count, double speed) {
        for (int i = 0; i < count; i++) {
            int index = (start + i) % count;
            Vec3 spot = waypoint(center, radius, count, index);
            Vec3 goal = patrolHeightAt(spot);
            if (goal != null) {
                // Climb before an obstacle on the legs to either neighbor and come down after it, so the drone
                // crosses it level instead of flying into its face.
                double legTop = Math.max(legClimbHeight(waypoint(center, radius, count, index - 1), spot),
                        legClimbHeight(spot, waypoint(center, radius, count, index + 1)));
                if (legTop > goal.y && drone.hasRoomAt(new Vec3(goal.x, legTop, goal.z))) {
                    goal = new Vec3(goal.x, legTop, goal.z);
                }
                patrolWaypoint = index;
                patrolGoal = goal;
                drone.requestPathRecheck();
                // Give up on a waypoint that takes far longer than a straight flight would, e.g. when stuck.
                waypointDeadline = drone.tickCount + (int) (2 * Math.sqrt(drone.distanceToSqr(goal)) / speed) + WAYPOINT_GRACE_TICKS;
                return;
            }
        }
        patrolWaypoint = NO_FREE_WAYPOINT;
        patrolGoal = null;
    }

    /**
     * The height the drone must fly at to clear every obstacle on the straight leg between two waypoints at the
     * patrol height (section 3.2), sampled every {@link #LEG_SAMPLE_SPACING} blocks. Obstacles too tall to climb are
     * left to path finding.
     */
    private double legClimbHeight(Vec3 from, Vec3 to) {
        double top = from.y;
        int steps = Math.max(1, Mth.ceil(from.distanceTo(to) / LEG_SAMPLE_SPACING));
        for (int step = 1; step < steps; step++) {
            Vec3 raised = patrolHeightAt(from.lerp(to, (double) step / steps));
            if (raised != null) {
                top = Math.max(top, raised.y);
            }
        }
        return top;
    }

    /**
     * Where the drone flies for a waypoint at {@code spot} (section 3.2): the spot itself if the drone fits there,
     * otherwise raised over the obstacle to {@code upgrades.patrol.climbClearance} above the highest block under the
     * drone. It never drops below the spot, so it climbs over hills and buildings rather than diving into caves. Null
     * if the chunk isn't loaded or the climb would be more than {@code upgrades.patrol.maxClimb}. Also used for the
     * legs of a long flight to a charging station.
     */
    @Nullable
    Vec3 patrolHeightAt(Vec3 spot) {
        Level level = drone.level();
        if (!level.hasChunkAt(BlockPos.containing(spot))) {
            return null;
        }
        EntityDimensions dimensions = drone.getDimensions(drone.getPose());
        AABB box = dimensions.makeBoundingBox(spot);
        if (level.noBlockCollision(drone, box)) {
            return spot;
        }
        int top = level.getMinBuildHeight();
        for (int x = Mth.floor(box.minX); x <= Mth.floor(box.maxX); x++) {
            for (int z = Mth.floor(box.minZ); z <= Mth.floor(box.maxZ); z++) {
                top = Math.max(top, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z));
            }
        }
        Vec3 raised = new Vec3(spot.x, top + ServerConfig.get(ServerConfig.UPGRADES_PATROL_CLIMB_CLEARANCE), spot.z);
        if (raised.y <= spot.y || raised.y - spot.y > ServerConfig.get(ServerConfig.UPGRADES_PATROL_MAX_CLIMB)) {
            return null;
        }
        return level.noBlockCollision(drone, dimensions.makeBoundingBox(raised)) ? raised : null;
    }
}
