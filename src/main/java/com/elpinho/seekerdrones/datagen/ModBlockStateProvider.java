package com.elpinho.seekerdrones.datagen;

import com.elpinho.seekerdrones.SeekerDrones;
import com.elpinho.seekerdrones.registry.ModBlocks;

import net.minecraft.data.PackOutput;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.client.model.generators.BlockStateProvider;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

public class ModBlockStateProvider extends BlockStateProvider {
    public ModBlockStateProvider(PackOutput output, ExistingFileHelper existingFileHelper) {
        super(output, SeekerDrones.MODID, existingFileHelper);
    }

    @Override
    protected void registerStatesAndModels() {
        // Placeholder textures from scripts/textures/generate_textures.py until the M9 art pass. All machines share
        // the bottom face.
        machine(ModBlocks.CHARGING_STATION.get(), "charging_station");
        machine(ModBlocks.DRONE_FACTORY.get(), "drone_factory");
        machine(ModBlocks.DEPLOYING_STATION.get(), "deploying_station");
        horizontalBlock(ModBlocks.PROGRAMMING_STATION.get(), models().orientableWithBottom("programming_station",
                modLoc("block/programming_station_side"),
                modLoc("block/programming_station_front"),
                modLoc("block/machine_bottom"),
                modLoc("block/programming_station_top")));
        simpleBlockItem(ModBlocks.PROGRAMMING_STATION.get(), models().getExistingFile(modLoc("block/programming_station")));
    }

    private void machine(Block block, String name) {
        simpleBlockWithItem(block, models().cubeBottomTop(name,
                modLoc("block/" + name + "_side"),
                modLoc("block/machine_bottom"),
                modLoc("block/" + name + "_top")));
    }
}
