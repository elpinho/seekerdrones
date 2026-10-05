package com.elpinho.seekerdrones.station;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.annotation.Nullable;

import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.drone.UpgradeType;
import com.elpinho.seekerdrones.machine.MachineWorkingState;
import com.elpinho.seekerdrones.machine.UpgradeSlots;
import com.elpinho.seekerdrones.registry.ModBlockEntities;
import com.elpinho.seekerdrones.registry.ModDataComponents;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * Drone Charging Station (DESIGN.md section 7.4). Stores FE, repair fluid and its placer's UUID, and holds a claim by the one drone
 * it serves at a time (section 5.3). The drone does the charging: it renews its claim every tick and pulls FE from
 * here, so the station needs no ticker. A claim that isn't renewed expires, so a drone that is picked up, destroyed or
 * unloaded never blocks the station.
 */
public class ChargingStationBlockEntity extends BlockEntity {
    private static final String TAG_ENERGY = "Energy";
    private static final String TAG_OWNER = "Owner";
    private static final String TAG_TANK = "Tank";
    private static final String TAG_UPGRADES = "Upgrades";

    /** What the Upgrades tab takes (section 7.4). */
    public static final List<UpgradeSlots.Accepted> UPGRADES = List.of(
            new UpgradeSlots.Accepted(UpgradeType.ENERGY, () -> ServerConfig.get(ServerConfig.CHARGING_STATION_MAX_ENERGY_UPGRADES)));

    /** A claim not renewed for this many ticks lapses. */
    private static final int CLAIM_TIMEOUT_TICKS = 20;
    /** Height of the dock point (the drone's feet) above the top of the station. */
    private static final double DOCK_HEIGHT = 0.1;

    private final Energy energy = new Energy();
    private final FluidTank tank = new FluidTank(ServerConfig.get(ServerConfig.CHARGING_STATION_TANK_CAPACITY), RepairFluid::isRepairFluid) {
        @Override
        protected void onContentsChanged() {
            setChanged();
        }
    };
    private final IFluidHandler fluidHandler = new FillOnlyFluidHandler();
    /** The Upgrades tab's slots. Not part of any capability, so automation can't reach them. */
    private final ItemStackHandler upgrades = UpgradeSlots.createHandler(UPGRADES, this::onUpgradesChanged);
    /** mB already drained from the tank but not yet turned into HP, so the tank is always drained in whole mB. */
    private double repairCredit;
    @Nullable
    private UUID owner;

    // Not saved: a drone that was docked re-claims the station once it is loaded again.
    @Nullable
    private UUID claimant;
    private long claimTime;
    /** The FE given to a drone on {@link #lastTransferTime} (see {@link #getTransferRate}). */
    private int lastTransfer;
    private long lastTransferTime = Long.MIN_VALUE;
    /** Game time the docked drone last took FE or healed. Starts idle; game time is never negative. */
    private long lastWork = -MachineWorkingState.IDLE_DELAY - 1;

    public ChargingStationBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CHARGING_STATION.get(), pos, state);
    }

    public IEnergyStorage getEnergyStorage() {
        return energy;
    }

    public int getEnergy() {
        return energy.stored;
    }

    /** Accepts the repair fluid on every side, never gives it out. */
    public IFluidHandler getFluidHandler() {
        return fluidHandler;
    }

    public ItemStackHandler getUpgrades() {
        return upgrades;
    }

    /** Energy Upgrades installed in the Upgrades tab (section 7.4). */
    public int getEnergyUpgrades() {
        return upgrades.getStackInSlot(0).getCount();
    }

    /** {@code multiplier^count}: what the Energy Upgrades multiply the charge rate and capacity by (section 7.4). */
    private double upgradeMultiplier() {
        return Math.pow(ServerConfig.get(ServerConfig.CHARGING_STATION_UPGRADE_MULTIPLIER), getEnergyUpgrades());
    }

    /** The most FE per tick the station gives its docked drone (section 7.4). */
    public int getChargeRate() {
        return (int) Math.min(ServerConfig.get(ServerConfig.CHARGING_STATION_CHARGE_RATE) * upgradeMultiplier(), Integer.MAX_VALUE);
    }

    /** Removing an Energy Upgrade lowers the capacity, and stored FE above it is lost (section 7.4). */
    private void onUpgradesChanged() {
        energy.stored = Math.min(energy.stored, energy.getMaxEnergyStored());
        setChanged();
    }

    public FluidStack getFluid() {
        return tank.getFluid();
    }

    public int getTankCapacity() {
        return tank.getCapacity();
    }

    /** Whether the station can heal at all: it has repair fluid, or healing is free ({@code repairFluidPerHp} = 0). */
    public boolean canRepair() {
        return ServerConfig.get(ServerConfig.CHARGING_STATION_REPAIR_FLUID_PER_HP) == 0 || !tank.isEmpty() || repairCredit > 0;
    }

    /**
     * Pays for up to {@code hp} HP of healing with repair fluid (section 5.3). Returns the HP actually paid for, which
     * is less when the tank runs dry.
     */
    public float useRepairFluid(float hp) {
        int perHp = ServerConfig.get(ServerConfig.CHARGING_STATION_REPAIR_FLUID_PER_HP);
        if (perHp == 0 || hp <= 0) {
            return Math.max(hp, 0);
        }
        double needed = (double) hp * perHp;
        if (repairCredit < needed) {
            repairCredit += tank.drain((int) Math.ceil(needed - repairCredit), IFluidHandler.FluidAction.EXECUTE).getAmount();
        }
        double paid = Math.min(needed, repairCredit);
        repairCredit -= paid;
        return (float) (paid / perHp);
    }

    /** Takes up to {@code amount} FE out of the station for the docked drone. Returns what was taken. */
    public int drainForDrone(int amount) {
        int taken = Math.min(amount, energy.stored);
        if (taken > 0) {
            energy.stored -= taken;
            setChanged();
        }
        if (level != null) {
            lastTransfer = taken;
            lastTransferTime = level.getGameTime();
        }
        return taken;
    }

    /**
     * The FE per tick the station is giving a drone: what it gave on the last tick, or 0 if it gave nothing on the
     * last tick or this one. Only for the status screen, so it isn't saved.
     */
    public int getTransferRate() {
        return level != null && level.getGameTime() - lastTransferTime <= 1 ? lastTransfer : 0;
    }

    public Optional<UUID> getOwner() {
        return Optional.ofNullable(owner);
    }

    void setOwner(@Nullable UUID owner) {
        this.owner = owner;
        setChanged();
    }

    /** Where a docked drone's feet go: centered, just above the station. */
    public static Vec3 dockPosition(BlockPos station) {
        return Vec3.atBottomCenterOf(station.above()).add(0, DOCK_HEIGHT, 0);
    }

    /** Whether another drone holds a live claim on this station. */
    public boolean isClaimedByOther(UUID drone) {
        return claimant != null && !claimant.equals(drone) && level != null && level.getGameTime() - claimTime <= CLAIM_TIMEOUT_TICKS;
    }

    /** The drone holding a live claim on this station, if any. */
    public Optional<UUID> getClaimant() {
        return claimant != null && level != null && level.getGameTime() - claimTime <= CLAIM_TIMEOUT_TICKS ? Optional.of(claimant) : Optional.empty();
    }

    /** Claims or renews the claim for {@code drone}. Fails if another drone holds the station. */
    public boolean claim(UUID drone) {
        if (level == null || isClaimedByOther(drone)) {
            return false;
        }
        claimant = drone;
        claimTime = level.getGameTime();
        return true;
    }

    public void release(UUID drone) {
        if (drone.equals(claimant)) {
            claimant = null;
        }
    }

    /**
     * Called by the docked drone on each tick it takes FE or heals. Sets the working state (and {@code repairing})
     * only when it changes, and schedules {@link #checkIdle()} to turn it off once the reports stop.
     */
    public void markWorking(boolean repairing) {
        if (level == null) {
            return;
        }
        lastWork = level.getGameTime();
        BlockState state = getBlockState();
        boolean wasWorking = state.getValue(MachineWorkingState.WORKING);
        if (!wasWorking || state.getValue(ChargingStationBlock.REPAIRING) != repairing) {
            level.setBlock(worldPosition, state.setValue(MachineWorkingState.WORKING, true).setValue(ChargingStationBlock.REPAIRING, repairing),
                    Block.UPDATE_CLIENTS);
        }
        if (!wasWorking) {
            level.scheduleTick(worldPosition, state.getBlock(), MachineWorkingState.IDLE_DELAY);
        }
    }

    /** Turns the working state off if no work was reported for a while, else checks again later. */
    void checkIdle() {
        if (level == null || !getBlockState().getValue(MachineWorkingState.WORKING)) {
            return;
        }
        if (level.getGameTime() - lastWork > MachineWorkingState.IDLE_DELAY) {
            level.setBlock(worldPosition, getBlockState().setValue(MachineWorkingState.WORKING, false).setValue(ChargingStationBlock.REPAIRING, false),
                    Block.UPDATE_CLIENTS);
        } else {
            level.scheduleTick(worldPosition, getBlockState().getBlock(), MachineWorkingState.IDLE_DELAY);
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        // Keeps the registry in step with the owner saved here, e.g. for stations whose entry was lost or stale.
        if (level instanceof ServerLevel serverLevel) {
            ChargingStationRegistry.get(serverLevel).add(worldPosition, getOwner());
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt(TAG_ENERGY, energy.stored);
        tag.put(TAG_TANK, tank.writeToNBT(registries, new CompoundTag()));
        tag.put(TAG_UPGRADES, upgrades.serializeNBT(registries));
        if (owner != null) {
            tag.putUUID(TAG_OWNER, owner);
        }
    }

    /** The item keeps the upgrades when the station is broken (section 7.4). */
    @Override
    protected void applyImplicitComponents(DataComponentInput componentInput) {
        super.applyImplicitComponents(componentInput);
        Map<UpgradeType, Integer> saved = componentInput.get(ModDataComponents.MACHINE_UPGRADES);
        if (saved != null) {
            for (int slot = 0; slot < UPGRADES.size(); slot++) {
                UpgradeSlots.Accepted accepted = UPGRADES.get(slot);
                int count = saved.getOrDefault(accepted.type(), 0);
                upgrades.setStackInSlot(slot, count > 0 ? new ItemStack(accepted.item(), count) : ItemStack.EMPTY);
            }
        }
    }

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        Map<UpgradeType, Integer> counts = new HashMap<>();
        for (int slot = 0; slot < UPGRADES.size(); slot++) {
            int count = upgrades.getStackInSlot(slot).getCount();
            if (count > 0) {
                counts.put(UPGRADES.get(slot).type(), count);
            }
        }
        if (!counts.isEmpty()) {
            components.set(ModDataComponents.MACHINE_UPGRADES, counts);
        }
    }

    @Override
    public void removeComponentsFromTag(CompoundTag tag) {
        super.removeComponentsFromTag(tag);
        tag.remove(TAG_UPGRADES);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        energy.stored = Math.max(0, tag.getInt(TAG_ENERGY));
        tank.setCapacity(ServerConfig.get(ServerConfig.CHARGING_STATION_TANK_CAPACITY));
        tank.readFromNBT(registries, tag.getCompound(TAG_TANK));
        if (tag.contains(TAG_UPGRADES)) {
            upgrades.deserializeNBT(registries, tag.getCompound(TAG_UPGRADES));
        }
        owner = tag.hasUUID(TAG_OWNER) ? tag.getUUID(TAG_OWNER) : null;
    }

    private class FillOnlyFluidHandler implements IFluidHandler {
        @Override
        public int getTanks() {
            return 1;
        }

        @Override
        public FluidStack getFluidInTank(int index) {
            return tank.getFluidInTank(index);
        }

        @Override
        public int getTankCapacity(int index) {
            return tank.getTankCapacity(index);
        }

        @Override
        public boolean isFluidValid(int index, FluidStack stack) {
            return tank.isFluidValid(index, stack);
        }

        @Override
        public int fill(FluidStack resource, FluidAction action) {
            return tank.fill(resource, action);
        }

        @Override
        public FluidStack drain(FluidStack resource, FluidAction action) {
            return FluidStack.EMPTY;
        }

        @Override
        public FluidStack drain(int maxDrain, FluidAction action) {
            return FluidStack.EMPTY;
        }
    }

    /** Accepts FE on every side, never gives it out. The capacity follows the config and the Energy Upgrades. */
    private class Energy implements IEnergyStorage {
        private int stored;

        @Override
        public int receiveEnergy(int toReceive, boolean simulate) {
            int received = Math.max(0, Math.min(toReceive, getMaxEnergyStored() - stored));
            if (!simulate && received > 0) {
                stored += received;
                setChanged();
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
            return (int) Math.min(ServerConfig.get(ServerConfig.CHARGING_STATION_CAPACITY) * upgradeMultiplier(), Integer.MAX_VALUE);
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
}
