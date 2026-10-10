package com.elpinho.seekerdrones.client.sound;

import com.elpinho.seekerdrones.drone.DroneEntity;

import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

/**
 * A looping sound that follows one drone (DESIGN.md section 2.9). {@link DroneSounds} decides which drones play one.
 */
abstract class DroneLoopSound extends AbstractTickableSoundInstance {
    protected final DroneEntity drone;

    DroneLoopSound(SoundEvent sound, DroneEntity drone) {
        super(sound, SoundSource.NEUTRAL, SoundInstance.createUnseededRandom());
        this.drone = drone;
        looping = true;
        delay = 0;
        x = drone.getX();
        y = drone.getY();
        z = drone.getZ();
    }

    /** Whether this loop is still the right one for the drone. Once it isn't, the loop stops itself. */
    boolean isCurrent() {
        return !drone.isRemoved();
    }

    @Override
    public void tick() {
        if (!isCurrent()) {
            stop();
            DroneSounds.markDirty();
            return;
        }
        x = drone.getX();
        y = drone.getY();
        z = drone.getZ();
        update();
    }

    /** Sets the volume and pitch for this tick. Subclasses also call it once they're constructed. */
    protected abstract void update();

    @Override
    public boolean canPlaySound() {
        return !drone.isSilent();
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }
}
