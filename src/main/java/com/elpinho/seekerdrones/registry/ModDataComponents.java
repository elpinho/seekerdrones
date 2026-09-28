package com.elpinho.seekerdrones.registry;

import com.elpinho.seekerdrones.SeekerDrones;
import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.programming.ProgrammingStationSettings;

import java.util.UUID;

import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModDataComponents {
    public static final DeferredRegister<DataComponentType<?>> DATA_COMPONENT_TYPES =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, SeekerDrones.MODID);

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<DroneData>> DRONE_DATA = DATA_COMPONENT_TYPES.register(
            "drone_data",
            () -> DataComponentType.<DroneData>builder()
                    .persistent(DroneData.CODEC)
                    .networkSynchronized(DroneData.STREAM_CODEC)
                    .build());

    /** The Operator Group ID a broken Drone Factory keeps on its item (DESIGN.md section 6.1). */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<UUID>> OPERATOR_GROUP = DATA_COMPONENT_TYPES.register(
            "operator_group",
            () -> DataComponentType.<UUID>builder()
                    .persistent(UUIDUtil.CODEC)
                    .networkSynchronized(UUIDUtil.STREAM_CODEC)
                    .build());

    /** The mode and template a broken Programming Station keeps on its item (DESIGN.md section 7.2). */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ProgrammingStationSettings>> PROGRAMMING_STATION =
            DATA_COMPONENT_TYPES.register("programming_station",
                    () -> DataComponentType.<ProgrammingStationSettings>builder()
                            .persistent(ProgrammingStationSettings.CODEC)
                            .networkSynchronized(ProgrammingStationSettings.STREAM_CODEC)
                            .build());
}
