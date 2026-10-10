package com.elpinho.seekerdrones.datagen;

import com.elpinho.seekerdrones.SeekerDrones;
import com.elpinho.seekerdrones.registry.ModSounds;

import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.common.data.ExistingFileHelper;
import net.neoforged.neoforge.common.data.SoundDefinitionsProvider;
import net.neoforged.neoforge.registries.DeferredHolder;

public class ModSoundDefinitionsProvider extends SoundDefinitionsProvider {
    public ModSoundDefinitionsProvider(PackOutput output, ExistingFileHelper helper) {
        super(output, SeekerDrones.MODID, helper);
    }

    @Override
    public void registerSounds() {
        add(ModSounds.DRONE_SIREN, "drone/siren");
        add(ModSounds.DRONE_FLY, "drone/fly");
        add(ModSounds.DRONE_DEPLOY, "drone/deploy");
        add(ModSounds.DRONE_HURT, "drone/hurt");
        add(ModSounds.DRONE_DESTROY, "drone/destroy");
        add(ModSounds.DRONE_LOW_POWER, "drone/low_power");
        add(ModSounds.DRONE_LOW_POWER_CRITICAL, "drone/low_power_critical");
        add(ModSounds.DRONE_LOCK_ON, "drone/beep");
        add(ModSounds.DRONE_TARGET_LOST, "drone/beep");
        add(ModSounds.DRONE_CHARGED, "drone/beep");
        add(ModSounds.DEPLOYING_STATION_LAUNCH, "machine/clunk");
        add(ModSounds.CHARGING_STATION_DOCK, "machine/clunk");
        add(ModSounds.PROGRAMMING_STATION_INSTALL, "machine/clunk");
    }

    /** One sound file, with the subtitle {@code subtitles.seekerdrones.<event name>}. */
    private void add(DeferredHolder<SoundEvent, SoundEvent> event, String file) {
        String name = event.getId().getPath();
        add(event, definition()
                .subtitle("subtitles." + SeekerDrones.MODID + "." + name)
                .with(sound(ResourceLocation.fromNamespaceAndPath(SeekerDrones.MODID, file))));
    }
}
