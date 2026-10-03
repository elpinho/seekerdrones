package com.elpinho.seekerdrones.datagen;

import com.elpinho.seekerdrones.SeekerDrones;
import com.elpinho.seekerdrones.machine.MachineWorkingState;
import com.elpinho.seekerdrones.registry.ModBlocks;
import com.elpinho.seekerdrones.station.ChargingStationBlock;

import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.client.model.generators.BlockStateProvider;
import net.neoforged.neoforge.client.model.generators.ConfiguredModel;
import net.neoforged.neoforge.client.model.generators.ModelFile;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

public class ModBlockStateProvider extends BlockStateProvider {
    public ModBlockStateProvider(PackOutput output, ExistingFileHelper existingFileHelper) {
        super(output, SeekerDrones.MODID, existingFileHelper);
    }

    @Override
    protected void registerStatesAndModels() {
        // Placeholder textures from scripts/textures/generate_textures.py until the M9 art pass. All machines share
        // the bottom face.
        chargingStation();
        machine(ModBlocks.DRONE_FACTORY.get(), "drone_factory");
        machine(ModBlocks.DEPLOYING_STATION.get(), "deploying_station");
        horizontalBlock(ModBlocks.PROGRAMMING_STATION.get(), models().orientableWithBottom("programming_station",
                modLoc("block/programming_station_side"),
                modLoc("block/programming_station_front"),
                modLoc("block/machine_bottom"),
                modLoc("block/programming_station_top")));
        simpleBlockItem(ModBlocks.PROGRAMMING_STATION.get(), models().getExistingFile(modLoc("block/programming_station")));
    }

    /**
     * The core chamber on the front, capacitors on the back and vents on the sides, lit while
     * {@link MachineWorkingState#WORKING} (the chamber is animated). Repairing has no texture of its own.
     */
    private void chargingStation() {
        ModelFile idle = chargingStationModel("charging_station", "", "_front");
        ModelFile working = chargingStationModel("charging_station_working", "_working", "_front_working");
        getVariantBuilder(ModBlocks.CHARGING_STATION.get()).forAllStates(state -> ConfiguredModel.builder()
                .modelFile(state.getValue(MachineWorkingState.WORKING) ? working : idle)
                .rotationY(((int) state.getValue(ChargingStationBlock.FACING).toYRot() + 180) % 360)
                .build());
        simpleBlockItem(ModBlocks.CHARGING_STATION.get(), idle);
    }

    /** The front faces north; the blockstate rotates it. */
    private ModelFile chargingStationModel(String name, String suffix, String front) {
        String base = "block/charging_station";
        ResourceLocation side = modLoc(base + "_side" + suffix);
        return models().cube(name, modLoc("block/machine_bottom"), modLoc(base + "_top" + suffix),
                        modLoc(base + front), modLoc(base + "_back" + suffix), side, side)
                .texture("particle", side);
    }

    private void machine(Block block, String name) {
        simpleBlockWithItem(block, models().cubeBottomTop(name,
                modLoc("block/" + name + "_side"),
                modLoc("block/machine_bottom"),
                modLoc("block/" + name + "_top")));
    }
}
