package com.elpinho.seekerdrones.client.sound;

import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.registry.ModSounds;

import net.minecraft.util.Mth;

/**
 * The rotor loop (DESIGN.md section 2.9): a low hum while hovering, higher and louder the faster the drone flies.
 */
class DroneFlyingSound extends DroneLoopSound {
    /** Fraction of the way the smoothed speed moves toward the measured one each tick, so the pitch glides. */
    private static final double SPEED_SMOOTHING = 0.3;

    private double smoothedSpeed = -1;

    DroneFlyingSound(DroneEntity drone) {
        super(ModSounds.DRONE_FLY.get(), drone);
        update();
    }

    @Override
    protected void update() {
        // The position change follows the synced (interpolated) position, so it works for any drone the client sees.
        double speed = Math.sqrt(Mth.lengthSquared(drone.getX() - drone.xo, drone.getY() - drone.yo, drone.getZ() - drone.zo));
        smoothedSpeed = smoothedSpeed < 0 ? speed : smoothedSpeed + (speed - smoothedSpeed) * SPEED_SMOOTHING;
        double t = Mth.clamp(smoothedSpeed / ServerConfig.get(ServerConfig.SOUNDS_FLYING_FULL_SPEED), 0, 1);
        volume = (float) Mth.lerp(t, ServerConfig.get(ServerConfig.SOUNDS_FLYING_MIN_VOLUME), ServerConfig.get(ServerConfig.SOUNDS_FLYING_MAX_VOLUME))
                * drone.getSoundVolumeMultiplier();
        pitch = (float) Mth.lerp(t, ServerConfig.get(ServerConfig.SOUNDS_FLYING_MIN_PITCH), ServerConfig.get(ServerConfig.SOUNDS_FLYING_MAX_PITCH))
                * drone.getSoundPitchMultiplier();
    }

    /** The loudest this drone's loop gets, which sets how far away it can be heard. */
    static float maxVolume(DroneEntity drone) {
        return ServerConfig.get(ServerConfig.SOUNDS_FLYING_MAX_VOLUME).floatValue() * drone.getSoundVolumeMultiplier();
    }
}
