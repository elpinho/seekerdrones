package com.elpinho.seekerdrones.drone;

import javax.annotation.Nullable;

import com.elpinho.seekerdrones.config.ServerConfig;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Chasing and following the drone's target (section 3.1), including an Explosive drone's dive at it. The target
 * itself, and finding and losing it, stay on {@link DroneEntity}. Server side only, nothing saved.
 */
final class DronePursuit {
    /**
     * Bearing turns (degrees) tried in order when the follow position is blocked (section 3.1): the current bearing
     * first, then ever further around the target.
     */
    private static final double[] FOLLOW_BEARING_TURNS = {0, 45, -45, 90, -90, 135, -135, 180};

    private final DroneEntity drone;

    /**
     * The target's position (feet x/z, eye y) smoothed over time, so the drone reacts to where the target is going
     * rather than to every hop (section 3.1). Null until the first pursuit tick.
     */
    @Nullable
    private Vec3 targetAnchor;
    /** How far the smoothed target position moved on the last tick (blocks/tick). */
    private double targetAnchorSpeed;
    /** Horizontal unit vector from the target to the follow position (section 3.1). */
    private Vec3 followBearing = new Vec3(1, 0, 0);
    /** True while {@link #followBearing} was turned to get around an obstacle, so it isn't re-derived each tick. */
    private boolean followBearingLocked;
    /** Horizontal distance (blocks) from the target to the follow position, shortened if the full distance is blocked. */
    private double followRange;
    /** Height (blocks) of the follow position above the target's eyes, lowered if blocked (e.g. under a ceiling). */
    private double followHeight;
    /** The tick of the last free-space check of the follow position. */
    private int followSlotCheckTick;
    /** Forces a free-space check of the follow position on the next tick. */
    private boolean followSlotStale = true;
    /** True while a following drone holds still, until the follow position moves more than the slack away. */
    private boolean followHolding;

    DronePursuit(DroneEntity drone) {
        this.drone = drone;
    }

    /** The smoothed target position, or null before the first pursuit tick of the current target. */
    @Nullable
    Vec3 targetAnchor() {
        return targetAnchor;
    }

    /** Forgets the smoothed target position and the follow position chosen for the previous target. */
    void reset() {
        targetAnchor = null;
        targetAnchorSpeed = 0;
        followBearingLocked = false;
        followHolding = false;
        followRange = drone.getDroneData().config().followDistance();
        followHeight = ServerConfig.get(ServerConfig.DRONE_FOLLOW_HEIGHT_OFFSET);
        // Check the follow position for free space on the first pursuit tick.
        followSlotStale = true;
    }

    /** Flies toward the follow position, switching between CHASING and FOLLOWING, or dives at the target if Explosive. */
    void pursue(DroneData data, boolean staggered) {
        if (DroneStats.isExplosive(data)) {
            pursueExplosive(data, staggered);
            return;
        }
        updateTargetAnchor();
        Vec3 goal = followPosition(data, staggered);
        double goalDistance = Math.sqrt(drone.distanceToSqr(goal));
        // The exit distance is kept above the enter distance so the state can't flip back and forth (hysteresis).
        double enter = ServerConfig.get(ServerConfig.DRONE_FOLLOW_ENTER_DISTANCE);
        double exit = Math.max(enter, ServerConfig.get(ServerConfig.DRONE_FOLLOW_EXIT_DISTANCE));
        DroneState state = drone.getState();
        if (state != DroneState.FOLLOWING && goalDistance <= enter) {
            drone.setState(DroneState.FOLLOWING);
        } else if (state != DroneState.CHASING && goalDistance > exit) {
            drone.setState(DroneState.CHASING);
        }
        // Leash: once there, the drone holds still until the follow position has moved more than the slack away.
        double slack = Math.max(enter, ServerConfig.get(ServerConfig.DRONE_FOLLOW_SLACK));
        if (goalDistance <= enter) {
            followHolding = true;
        } else if (goalDistance > slack) {
            followHolding = false;
        }
        double acceleration = ServerConfig.get(ServerConfig.DRONE_ACCELERATION);
        if (followHolding) {
            if (drone.hasPathGoal()) {
                drone.stopNavigating();
            }
            drone.droneMoveControl().hold(acceleration);
        } else {
            drone.steerTowards(goal, DroneStats.followSpeed(targetAnchorSpeed), acceleration, goal, staggered, true);
        }
    }

    private Entity target() {
        return drone.getSeekTarget();
    }

    /** Moves the smoothed target position a fraction of the way toward the target (section 3.1). */
    private void updateTargetAnchor() {
        Entity target = target();
        Vec3 actual = new Vec3(target.getX(), target.getEyeY(), target.getZ());
        if (targetAnchor == null) {
            targetAnchor = actual;
            targetAnchorSpeed = 0;
            return;
        }
        Vec3 next = targetAnchor.lerp(actual, ServerConfig.get(ServerConfig.DRONE_FOLLOW_SMOOTHING));
        targetAnchorSpeed = next.distanceTo(targetAnchor);
        targetAnchor = next;
    }

    /**
     * Where the drone wants to be (section 3.1): {@link #followRange} from the smoothed target position along the
     * bearing, and {@link #followHeight} above its eyes. Chasing drones fly toward this position too. The spot is
     * checked for free space on the staggered tick, and soon after bumping into a block.
     */
    private Vec3 followPosition(DroneData data, boolean staggered) {
        if (!followBearingLocked) {
            followBearing = bearingFromAnchor();
        }
        boolean bumped = (drone.horizontalCollision || drone.verticalCollision)
                && drone.tickCount - followSlotCheckTick >= DroneEntity.COLLISION_RECHECK_TICKS;
        if (staggered || followSlotStale || bumped) {
            chooseFollowSlot(data);
        }
        return followSlot(followBearing, followRange, followHeight);
    }

    /** The horizontal direction from the smoothed target position to the drone, or the last one if right above it. */
    private Vec3 bearingFromAnchor() {
        double dx = drone.getX() - targetAnchor.x;
        double dz = drone.getZ() - targetAnchor.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        return horizontal > 1.0E-3 ? new Vec3(dx / horizontal, 0, dz / horizontal) : followBearing;
    }

    private Vec3 followSlot(Vec3 bearing, double range, double height) {
        return new Vec3(targetAnchor.x + bearing.x * range, targetAnchor.y + height, targetAnchor.z + bearing.z * range);
    }

    /**
     * Picks a follow position with room for the drone (section 3.1). The ideal one comes first. If it's blocked, the
     * drone tries lower heights (under a ceiling), then half the distance (against a wall), then other bearings
     * around the target. If nothing is free, it aims for the ideal spot and path finding gets as close as it can.
     */
    private void chooseFollowSlot(DroneData data) {
        followSlotStale = false;
        followSlotCheckTick = drone.tickCount;
        double distance = data.config().followDistance();
        double[] heights = {ServerConfig.get(ServerConfig.DRONE_FOLLOW_HEIGHT_OFFSET), 0, -target().getEyeHeight() / 2};
        double[] ranges = {distance, distance / 2};
        Vec3 base = bearingFromAnchor();
        for (double turn : FOLLOW_BEARING_TURNS) {
            Vec3 bearing = base.yRot((float) Math.toRadians(turn));
            for (double range : ranges) {
                for (double height : heights) {
                    if (drone.hasRoomAt(followSlot(bearing, range, height))) {
                        followBearing = bearing;
                        followBearingLocked = turn != 0;
                        followRange = range;
                        followHeight = height;
                        return;
                    }
                }
            }
        }
        followBearing = base;
        followBearingLocked = false;
        followRange = distance;
        followHeight = heights[0];
    }

    /**
     * Explosive drones never follow: they fly straight at the target's center and explode once close enough. They
     * track the target's actual position with a higher acceleration and never brake (section 3.4).
     */
    private void pursueExplosive(DroneData data, boolean staggered) {
        Entity target = target();
        Vec3 targetCenter = target.getBoundingBox().getCenter();
        double halfHeight = drone.getBbHeight() / 2;
        double trigger = ServerConfig.get(ServerConfig.DRONE_EXPLOSION_TRIGGER_DISTANCE);
        if (drone.position().add(0, halfHeight, 0).distanceToSqr(targetCenter) <= trigger * trigger) {
            drone.explode(data);
            return;
        }
        if (drone.getState() != DroneState.CHASING) {
            drone.setState(DroneState.CHASING);
        }
        double speed = DroneStats.chaseSpeed(drone.distanceTo(target), DroneStats.sightRange(data));
        drone.steerTowards(targetCenter.subtract(0, halfHeight, 0), speed, ServerConfig.get(ServerConfig.DRONE_EXPLOSIVE_ACCELERATION),
                null, staggered, true);
    }
}
