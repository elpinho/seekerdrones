package com.elpinho.seekerdrones.registry;

import com.elpinho.seekerdrones.SeekerDrones;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Drone and machine sounds (DESIGN.md section 2.9). Uses of a shared file (the beep, the clunk) get their own events
 * so each has its own subtitle.
 */
public class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(Registries.SOUND_EVENT, SeekerDrones.MODID);

    /** Siren upgrade (DESIGN.md section 4). Variable range, so volumes above 1 are heard from 16 × volume blocks. */
    public static final DeferredHolder<SoundEvent, SoundEvent> DRONE_SIREN = register("drone_siren");

    public static final DeferredHolder<SoundEvent, SoundEvent> DRONE_FLY = register("drone_fly");
    public static final DeferredHolder<SoundEvent, SoundEvent> DRONE_DEPLOY = register("drone_deploy");
    public static final DeferredHolder<SoundEvent, SoundEvent> DRONE_HURT = register("drone_hurt");
    public static final DeferredHolder<SoundEvent, SoundEvent> DRONE_DESTROY = register("drone_destroy");
    public static final DeferredHolder<SoundEvent, SoundEvent> DRONE_LOW_POWER = register("drone_low_power");
    public static final DeferredHolder<SoundEvent, SoundEvent> DRONE_LOW_POWER_CRITICAL = register("drone_low_power_critical");
    public static final DeferredHolder<SoundEvent, SoundEvent> DRONE_LOCK_ON = register("drone_lock_on");
    public static final DeferredHolder<SoundEvent, SoundEvent> DRONE_TARGET_LOST = register("drone_target_lost");
    public static final DeferredHolder<SoundEvent, SoundEvent> DRONE_CHARGED = register("drone_charged");

    public static final DeferredHolder<SoundEvent, SoundEvent> DEPLOYING_STATION_LAUNCH = register("deploying_station_launch");
    public static final DeferredHolder<SoundEvent, SoundEvent> CHARGING_STATION_DOCK = register("charging_station_dock");
    public static final DeferredHolder<SoundEvent, SoundEvent> PROGRAMMING_STATION_INSTALL = register("programming_station_install");

    private static DeferredHolder<SoundEvent, SoundEvent> register(String name) {
        return SOUND_EVENTS.register(name, () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(SeekerDrones.MODID, name)));
    }
}
