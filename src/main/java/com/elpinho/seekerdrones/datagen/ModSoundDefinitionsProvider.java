package com.elpinho.seekerdrones.datagen;

import com.elpinho.seekerdrones.SeekerDrones;
import com.elpinho.seekerdrones.registry.ModSounds;

import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.data.ExistingFileHelper;
import net.neoforged.neoforge.common.data.SoundDefinitionsProvider;

public class ModSoundDefinitionsProvider extends SoundDefinitionsProvider {
    public ModSoundDefinitionsProvider(PackOutput output, ExistingFileHelper helper) {
        super(output, SeekerDrones.MODID, helper);
    }

    @Override
    public void registerSounds() {
        // Placeholder: the vanilla raid horn until the drone gets its own siren sound.
        add(ModSounds.DRONE_SIREN, definition()
                .subtitle("subtitles.seekerdrones.drone_siren")
                .with(sound(ResourceLocation.withDefaultNamespace("event/raid/raidhorn_01")),
                        sound(ResourceLocation.withDefaultNamespace("event/raid/raidhorn_02")),
                        sound(ResourceLocation.withDefaultNamespace("event/raid/raidhorn_03")),
                        sound(ResourceLocation.withDefaultNamespace("event/raid/raidhorn_04"))));
    }
}
