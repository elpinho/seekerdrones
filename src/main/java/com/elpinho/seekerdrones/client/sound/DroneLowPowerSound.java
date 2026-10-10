package com.elpinho.seekerdrones.client.sound;

import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.drone.LowPowerLevel;
import com.elpinho.seekerdrones.registry.ModSounds;

/**
 * The low-power beep loop for one low-power level (DESIGN.md section 2.9). It stops once the drone's level changes,
 * and {@link DroneSounds} starts the loop for the new level.
 */
class DroneLowPowerSound extends DroneLoopSound {
    private final LowPowerLevel level;

    DroneLowPowerSound(DroneEntity drone, LowPowerLevel level) {
        super(level == LowPowerLevel.CRITICAL ? ModSounds.DRONE_LOW_POWER_CRITICAL.get() : ModSounds.DRONE_LOW_POWER.get(), drone);
        this.level = level;
        update();
    }

    @Override
    boolean isCurrent() {
        return super.isCurrent() && drone.getLowPower() == level;
    }

    @Override
    protected void update() {
        volume = volume();
    }

    static float volume() {
        return ServerConfig.get(ServerConfig.SOUNDS_LOW_POWER_VOLUME).floatValue();
    }
}
