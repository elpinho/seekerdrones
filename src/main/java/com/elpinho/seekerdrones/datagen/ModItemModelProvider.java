package com.elpinho.seekerdrones.datagen;

import com.elpinho.seekerdrones.SeekerDrones;

import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.client.model.generators.ItemModelProvider;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

public class ModItemModelProvider extends ItemModelProvider {
    public ModItemModelProvider(PackOutput output, ExistingFileHelper existingFileHelper) {
        super(output, SeekerDrones.MODID, existingFileHelper);
    }

    @Override
    protected void registerModels() {
        // No items yet. Populated starting with the Drone item in M1.
    }
}
