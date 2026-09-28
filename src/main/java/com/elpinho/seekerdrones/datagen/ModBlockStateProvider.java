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
        // Placeholder look from vanilla textures until the M9 polish pass.
        simpleBlockWithItem(ModBlocks.CHARGING_STATION.get(), models().cubeBottomTop("charging_station",
                ResourceLocation.withDefaultNamespace("block/iron_block"),
                ResourceLocation.withDefaultNamespace("block/smooth_stone"),
                ResourceLocation.withDefaultNamespace("block/redstone_block")));
        simpleBlockWithItem(ModBlocks.DRONE_FACTORY.get(), models().cubeBottomTop("drone_factory",
                ResourceLocation.withDefaultNamespace("block/crafter_east"),
                ResourceLocation.withDefaultNamespace("block/crafter_bottom"),
                ResourceLocation.withDefaultNamespace("block/crafter_top")));
        horizontalBlock(ModBlocks.PROGRAMMING_STATION.get(), models().orientableWithBottom("programming_station",
                ResourceLocation.withDefaultNamespace("block/iron_block"),
                ResourceLocation.withDefaultNamespace("block/observer_front"),
                ResourceLocation.withDefaultNamespace("block/smooth_stone"),
                ResourceLocation.withDefaultNamespace("block/lodestone_top")));
        simpleBlockItem(ModBlocks.PROGRAMMING_STATION.get(), models().getExistingFile(modLoc("block/programming_station")));
    }
}
