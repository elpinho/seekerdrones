package com.elpinho.seekerdrones.registry;

import com.elpinho.seekerdrones.SeekerDrones;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(Registries.SOUND_EVENT, SeekerDrones.MODID);

    /** Siren upgrade (DESIGN.md section 4). Variable range, so volumes above 1 are heard from 16 × volume blocks. */
    public static final DeferredHolder<SoundEvent, SoundEvent> DRONE_SIREN = SOUND_EVENTS.register("drone_siren",
            () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(SeekerDrones.MODID, "drone_siren")));
}
