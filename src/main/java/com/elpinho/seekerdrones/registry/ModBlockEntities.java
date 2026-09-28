package com.elpinho.seekerdrones.registry;

import com.elpinho.seekerdrones.SeekerDrones;
import com.elpinho.seekerdrones.factory.DroneFactoryBlockEntity;
import com.elpinho.seekerdrones.programming.ProgrammingStationBlockEntity;
import com.elpinho.seekerdrones.station.ChargingStationBlockEntity;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, SeekerDrones.MODID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ChargingStationBlockEntity>> CHARGING_STATION =
            BLOCK_ENTITY_TYPES.register("charging_station",
                    () -> BlockEntityType.Builder.of(ChargingStationBlockEntity::new, ModBlocks.CHARGING_STATION.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<DroneFactoryBlockEntity>> DRONE_FACTORY =
            BLOCK_ENTITY_TYPES.register("drone_factory",
                    () -> BlockEntityType.Builder.of(DroneFactoryBlockEntity::new, ModBlocks.DRONE_FACTORY.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ProgrammingStationBlockEntity>> PROGRAMMING_STATION =
            BLOCK_ENTITY_TYPES.register("programming_station",
                    () -> BlockEntityType.Builder.of(ProgrammingStationBlockEntity::new, ModBlocks.PROGRAMMING_STATION.get()).build(null));
}
