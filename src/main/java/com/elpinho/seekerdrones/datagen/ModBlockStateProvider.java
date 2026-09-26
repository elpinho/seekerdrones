package com.elpinho.seekerdrones.datagen;

import com.elpinho.seekerdrones.SeekerDrones;

import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.client.model.generators.BlockStateProvider;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

public class ModBlockStateProvider extends BlockStateProvider {
    public ModBlockStateProvider(PackOutput output, ExistingFileHelper existingFileHelper) {
        super(output, SeekerDrones.MODID, existingFileHelper);
    }

    @Override
    protected void registerStatesAndModels() {
        // No blocks yet. Populated starting with the Drone Factory in M6.
    }
}
