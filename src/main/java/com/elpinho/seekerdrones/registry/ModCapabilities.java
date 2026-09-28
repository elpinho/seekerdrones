package com.elpinho.seekerdrones.registry;

import com.elpinho.seekerdrones.factory.DroneFactoryBlockEntity;
import com.elpinho.seekerdrones.station.ChargingStationBlockEntity;

import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/** Block capabilities of the machines (DESIGN.md section 7): exposed on every side. */
public final class ModCapabilities {
    private ModCapabilities() {}

    public static void register(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, ModBlockEntities.CHARGING_STATION.get(),
                (station, side) -> station.getEnergyStorage());
        event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, ModBlockEntities.CHARGING_STATION.get(),
                (station, side) -> station.getFluidHandler());

        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, ModBlockEntities.DRONE_FACTORY.get(),
                (factory, side) -> factory.getEnergyStorage());
        event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, ModBlockEntities.DRONE_FACTORY.get(),
                (factory, side) -> factory.getFluidHandler());
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, ModBlockEntities.DRONE_FACTORY.get(),
                (factory, side) -> factory.getAutomationItems());
    }
}
