package com.elpinho.seekerdrones.datagen;

import com.elpinho.seekerdrones.SeekerDrones;

import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.common.data.LanguageProvider;

public class ModLanguageProvider extends LanguageProvider {
    public ModLanguageProvider(PackOutput output) {
        super(output, SeekerDrones.MODID, "en_us");
    }

    @Override
    protected void addTranslations() {
        add("itemGroup.seekerdrones", "Seeker Drones");
    }
}
