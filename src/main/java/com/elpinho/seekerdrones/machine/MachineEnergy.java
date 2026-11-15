package com.elpinho.seekerdrones.machine;

import net.neoforged.neoforge.energy.IEnergyStorage;

import java.util.function.IntSupplier;

/**
 * A machine's FE buffer: accepts FE on every side and never gives it out. The capacity is read from the server config
 * every time, so a config change applies at once. The owning block entity saves {@link #getEnergyStored()} itself.
 */
public class MachineEnergy implements IEnergyStorage {
    private final IntSupplier capacity;
    private final Runnable onChanged;
    private int stored;

    public MachineEnergy(IntSupplier capacity, Runnable onChanged) {
        this.capacity = capacity;
        this.onChanged = onChanged;
    }

    /** Takes {@code amount} FE if it's all there. Machines use this; other mods can't extract. */
    public boolean consume(int amount) {
        if (stored < amount) {
            return false;
        }
        if (amount > 0) {
            stored -= amount;
            onChanged.run();
        }
        return true;
    }

    /** Sets the stored FE when loading, clamped to zero. A buffer over the capacity keeps its FE until it's used. */
    public void load(int energy) {
        stored = Math.max(0, energy);
    }

    @Override
    public int receiveEnergy(int toReceive, boolean simulate) {
        int received = Math.max(0, Math.min(toReceive, getMaxEnergyStored() - stored));
        if (!simulate && received > 0) {
            stored += received;
            onChanged.run();
        }
        return received;
    }

    @Override
    public int extractEnergy(int toExtract, boolean simulate) {
        return 0;
    }

    @Override
    public int getEnergyStored() {
        return stored;
    }

    @Override
    public int getMaxEnergyStored() {
        return capacity.getAsInt();
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
