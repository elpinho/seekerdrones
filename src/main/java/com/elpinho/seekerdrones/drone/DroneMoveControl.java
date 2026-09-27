package com.elpinho.seekerdrones.drone;

import javax.annotation.Nullable;

import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.phys.Vec3;

/**
 * Steers the drone with inertia (DESIGN.md section 3.4). Each tick it works out a desired velocity toward the wanted
 * position and changes the actual velocity toward it by at most the acceleration, so the drone never jumps to a new
 * speed or direction. The speed modifier is the top speed in blocks/tick, not a multiplier of the movement speed
 * attribute. The drone applies the velocity in {@link DroneEntity#travel}.
 * <p>
 * The drone calls {@link #configure} every tick before steering: the wanted position may be the next node of a path,
 * while the stop position is where the drone should come to rest, so it brakes for the end of the path only.
 */
public class DroneMoveControl extends MoveControl {
    /** Closer than this (blocks) to the stop position, the drone wants to stand still. */
    private static final double ARRIVE_DISTANCE = 0.05;
    /** Below this speed (blocks/tick) the velocity snaps to zero, so a stopped drone doesn't creep. */
    private static final double STOP_SPEED = 1.0E-3;
    /** The speed factor left when the wanted direction points straight back, so the drone turns tightly. */
    private static final double MIN_TURN_SPEED_FACTOR = 0.2;

    /** True if this tick's velocity was set here, so {@link DroneEntity#travel} shouldn't apply drift drag. */
    private boolean steering;
    private boolean holding;
    private double acceleration;
    @Nullable
    private Vec3 stopAt;

    public DroneMoveControl(DroneEntity drone) {
        super(drone);
    }

    public boolean isSteering() {
        return steering;
    }

    /**
     * Sets how this tick's steering behaves.
     *
     * @param acceleration the max change in velocity this tick (blocks/tick²)
     * @param stopAt       where the drone should come to rest, braking in time to stop there, or null to fly through
     *                     the wanted position at full speed (patrol waypoints, an Explosive drone's target)
     */
    public void configure(double acceleration, @Nullable Vec3 stopAt) {
        this.acceleration = acceleration;
        this.stopAt = stopAt;
    }

    /** Brakes to a stop this tick, as fast as the acceleration allows. */
    public void hold(double acceleration) {
        this.acceleration = acceleration;
        holding = true;
        operation = Operation.WAIT;
    }

    @Override
    public void tick() {
        Vec3 desired;
        if (holding) {
            desired = Vec3.ZERO;
        } else if (operation == Operation.MOVE_TO) {
            desired = desiredVelocity();
        } else {
            steering = false;
            return;
        }
        holding = false;
        operation = Operation.WAIT;
        steering = true;
        Vec3 velocity = mob.getDeltaMovement();
        Vec3 change = desired.subtract(velocity);
        double changeLength = change.length();
        if (changeLength > acceleration) {
            change = change.scale(acceleration / changeLength);
        }
        velocity = velocity.add(change);
        mob.setDeltaMovement(velocity.lengthSqr() < STOP_SPEED * STOP_SPEED ? Vec3.ZERO : velocity);
    }

    private Vec3 desiredVelocity() {
        Vec3 position = mob.position();
        Vec3 delta = new Vec3(wantedX - position.x, wantedY - position.y, wantedZ - position.z);
        double distance = delta.length();
        double speed = speedModifier;
        if (stopAt != null) {
            double stopDistance = stopAt.distanceTo(position);
            if (stopDistance < ARRIVE_DISTANCE) {
                return Vec3.ZERO;
            }
            speed = Math.min(speed, brakingSpeed(stopDistance));
        }
        if (distance < ARRIVE_DISTANCE) {
            return Vec3.ZERO;
        }
        Vec3 direction = delta.scale(1 / distance);
        // Slow down while the wanted direction points away from the current heading, so the drone turns tightly
        // instead of swinging wide past path corners and waypoints.
        Vec3 velocity = mob.getDeltaMovement();
        double currentSpeed = velocity.length();
        if (currentSpeed > STOP_SPEED) {
            double alignment = velocity.dot(direction) / currentSpeed;
            speed *= Math.max(MIN_TURN_SPEED_FACTOR, alignment);
        }
        return direction.scale(speed);
    }

    /**
     * The highest speed from which the drone can still stop within {@code distance}, slowing by the acceleration each
     * tick: solves {@code v + (v − a) + (v − 2a) + … = distance}, and never more than the distance itself.
     */
    private double brakingSpeed(double distance) {
        double speed = acceleration / 2 * (Math.sqrt(1 + 8 * distance / acceleration) - 1);
        return Math.min(speed, distance);
    }
}
