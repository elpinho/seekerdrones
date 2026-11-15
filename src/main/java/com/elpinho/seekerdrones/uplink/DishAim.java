package com.elpinho.seekerdrones.uplink;

import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/**
 * The Uplink dish's slow random re-aim (DESIGN.md section 7.9), client-side and cosmetic. Every 8 to 14 s the dish
 * eases over 2 s to a new random bearing and an elevation of 30° to 65°, from a random sequence seeded by the block
 * position. Unpowered, it stops where it is. Plain state with no client classes, kept on the block entity.
 */
public final class DishAim {
    /** Ticks from the start of one move to the start of the next. */
    private static final int MIN_WAIT_TICKS = 160;
    private static final int MAX_WAIT_TICKS = 280;
    private static final int MOVE_TICKS = 40;
    private static final float MIN_ELEVATION = 30;
    private static final float MAX_ELEVATION = 65;

    private RandomSource random;
    private float fromYaw;
    private float fromElevation = 45;
    private float toYaw;
    private float toElevation = 45;
    /** Powered ticks since the current move started. */
    private double age;
    private int wait;
    private double lastTime = -1;
    private float yaw;
    private float elevation = 45;

    /** Advances to {@code time} (world ticks with the partial tick) if powered, and returns this aim. */
    public DishAim update(long seed, double time, boolean powered) {
        if (random == null) {
            random = RandomSource.create(seed);
            toYaw = fromYaw = random.nextFloat() * 360;
            toElevation = fromElevation = elevation = nextElevation();
            yaw = toYaw;
            wait = nextWait();
        }
        double delta = lastTime < 0 ? 0 : Math.max(0, time - lastTime);
        lastTime = time;
        if (!powered) {
            return this;
        }
        age += delta;
        if (age >= wait) {
            age = 0;
            fromYaw = yaw;
            fromElevation = elevation;
            toYaw = random.nextFloat() * 360;
            toElevation = nextElevation();
            wait = nextWait();
        }
        float progress = (float) Math.min(1, age / MOVE_TICKS);
        float eased = progress * progress * (3 - 2 * progress);
        yaw = fromYaw + Mth.wrapDegrees(toYaw - fromYaw) * eased;
        elevation = Mth.lerp(eased, fromElevation, toElevation);
        return this;
    }

    /** Degrees clockwise from north, seen from above. */
    public float yaw() {
        return yaw;
    }

    /** Degrees above the horizon. */
    public float elevation() {
        return elevation;
    }

    private float nextElevation() {
        return MIN_ELEVATION + random.nextFloat() * (MAX_ELEVATION - MIN_ELEVATION);
    }

    private int nextWait() {
        return MIN_WAIT_TICKS + random.nextInt(MAX_WAIT_TICKS - MIN_WAIT_TICKS + 1);
    }
}
