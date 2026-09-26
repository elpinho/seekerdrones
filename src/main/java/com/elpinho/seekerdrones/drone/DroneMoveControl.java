package com.elpinho.seekerdrones.drone;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.phys.Vec3;

/**
 * Flies straight toward the wanted position at an exact speed. The speed modifier is the speed in blocks/tick, not a
 * multiplier of the movement speed attribute. The drone applies the velocity in {@link DroneEntity#travel}.
 */
public class DroneMoveControl extends MoveControl {
    /** Max body turn per tick, in degrees. */
    private static final float MAX_TURN = 30.0F;
    private static final double MIN_TURN_DISTANCE_SQR = 1.0E-4;

    /** True if this tick's velocity was set here, so {@link DroneEntity#travel} shouldn't apply drift drag. */
    private boolean steering;

    public DroneMoveControl(DroneEntity drone) {
        super(drone);
    }

    public boolean isSteering() {
        return steering;
    }

    @Override
    public void tick() {
        if (operation != Operation.MOVE_TO) {
            steering = false;
            return;
        }
        operation = Operation.WAIT;
        steering = true;
        Vec3 delta = new Vec3(wantedX - mob.getX(), wantedY - mob.getY(), wantedZ - mob.getZ());
        double distance = delta.length();
        if (distance < MIN_SPEED) {
            mob.setDeltaMovement(Vec3.ZERO);
            return;
        }
        // Never overshoot the wanted position.
        mob.setDeltaMovement(delta.scale(Math.min(speedModifier, distance) / distance));
        if (delta.horizontalDistanceSqr() > MIN_TURN_DISTANCE_SQR) {
            float yaw = (float) (Mth.atan2(delta.z, delta.x) * Mth.RAD_TO_DEG) - 90.0F;
            mob.setYRot(rotlerp(mob.getYRot(), yaw, MAX_TURN));
            mob.setYBodyRot(mob.getYRot());
        }
    }
}
