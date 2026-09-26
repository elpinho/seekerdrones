package com.elpinho.seekerdrones.registry;

import com.elpinho.seekerdrones.SeekerDrones;
import com.elpinho.seekerdrones.drone.DroneData;

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
}
