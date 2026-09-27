package com.elpinho.seekerdrones.datagen;

import com.elpinho.seekerdrones.SeekerDrones;
import com.elpinho.seekerdrones.registry.ModBlocks;

import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.model.generators.BlockStateProvider;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

public class ModBlockStateProvider extends BlockStateProvider {
    public ModBlockStateProvider(PackOutput output, ExistingFileHelper existingFileHelper) {
        super(output, SeekerDrones.MODID, existingFileHelper);
    }

    @Override
    protected void registerStatesAndModels() {
        // Placeholder look from vanilla textures until the M8 polish pass.
        simpleBlockWithItem(ModBlocks.CHARGING_STATION.get(), models().cubeBottomTop("charging_station",
                ResourceLocation.withDefaultNamespace("block/iron_block"),
                ResourceLocation.withDefaultNamespace("block/smooth_stone"),
                ResourceLocation.withDefaultNamespace("block/redstone_block")));
    }
}
