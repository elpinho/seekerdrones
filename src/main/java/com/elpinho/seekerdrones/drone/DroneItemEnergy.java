package com.elpinho.seekerdrones.drone;

import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.registry.ModDataComponents;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * The drone item's energy capability (DESIGN.md section 2.1), backed by the {@code energy} field of its data component,
 * so other mods' item chargers can charge it. Receive-only, at most {@code drone.itemChargeRate} FE per call.
 */
public class DroneItemEnergy implements IEnergyStorage {
    private final ItemStack stack;

    public DroneItemEnergy(ItemStack stack) {
        this.stack = stack;
    }

    @Override
    public int receiveEnergy(int toReceive, boolean simulate) {
        if (stack.getCount() != 1) {
            return 0;
        }
        DroneData data = DroneItem.getData(stack);
        int received = Math.max(0, Math.min(Math.min(toReceive, ServerConfig.get(ServerConfig.DRONE_ITEM_CHARGE_RATE)),
                DroneStats.maxEnergy(data) - data.energy()));
        if (!simulate && received > 0) {
            stack.set(ModDataComponents.DRONE_DATA, data.withEnergy(data.energy() + received));
        }
        return received;
    }

    @Override
    public int extractEnergy(int toExtract, boolean simulate) {
        return 0;
    }

    @Override
    public int getEnergyStored() {
        return DroneItem.getData(stack).energy();
    }

    @Override
    public int getMaxEnergyStored() {
        return DroneStats.maxEnergy(DroneItem.getData(stack));
    }

    @Override
    public boolean canExtract() {
        return false;
    }

    @Override
    public boolean canReceive() {
        return true;
    }
}
