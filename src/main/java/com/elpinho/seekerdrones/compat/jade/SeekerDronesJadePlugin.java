package com.elpinho.seekerdrones.compat.jade;

import com.elpinho.seekerdrones.SeekerDrones;
import com.elpinho.seekerdrones.deploying.DeployingStationBlock;
import com.elpinho.seekerdrones.deploying.DeployingStationBlockEntity;
import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.factory.DroneFactoryBlock;
import com.elpinho.seekerdrones.factory.DroneFactoryBlockEntity;
import com.elpinho.seekerdrones.programming.ProgrammingStationBlock;
import com.elpinho.seekerdrones.programming.ProgrammingStationBlockEntity;
import com.elpinho.seekerdrones.station.ChargingStationBlock;
import com.elpinho.seekerdrones.station.ChargingStationBlockEntity;

import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

/**
 * Jade integration (DESIGN.md section 8.6). Jade already shows the machines' energy, tanks and inventories and the
 * drone's name and health; this adds the drone's details, its energy, and each machine's progress and status. Jade is
 * optional: this class is only loaded when Jade finds it.
 */
@WailaPlugin
public class SeekerDronesJadePlugin implements IWailaPlugin {
    static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(SeekerDrones.MODID, path);
    }

    @Override
    public void register(IWailaCommonRegistration registration) {
        registration.registerEntityDataProvider(DroneProvider.INSTANCE, DroneEntity.class);
        registration.registerEnergyStorage(DroneEnergyProvider.INSTANCE, DroneEntity.class);
        for (Class<?> machine : new Class<?>[] {DroneFactoryBlockEntity.class, ProgrammingStationBlockEntity.class,
                DeployingStationBlockEntity.class, ChargingStationBlockEntity.class}) {
            registration.registerBlockDataProvider(MachineProvider.INSTANCE, machine);
        }
        registration.registerProgress(MachineProgressProvider.INSTANCE, DroneFactoryBlockEntity.class);
        registration.registerProgress(MachineProgressProvider.INSTANCE, ProgrammingStationBlockEntity.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerEntityComponent(DroneProvider.INSTANCE, DroneEntity.class);
        registration.registerEnergyStorageClient(DroneEnergyProvider.INSTANCE);
        registration.registerBlockComponent(MachineProvider.INSTANCE, DroneFactoryBlock.class);
        registration.registerBlockComponent(MachineProvider.INSTANCE, ProgrammingStationBlock.class);
        registration.registerBlockComponent(MachineProvider.INSTANCE, DeployingStationBlock.class);
        registration.registerBlockComponent(MachineProvider.INSTANCE, ChargingStationBlock.class);
        registration.registerProgressClient(MachineProgressProvider.INSTANCE);
    }
}
