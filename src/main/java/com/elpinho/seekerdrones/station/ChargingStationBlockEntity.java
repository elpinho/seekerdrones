package com.elpinho.seekerdrones.station;

import java.util.Optional;
import java.util.UUID;

import javax.annotation.Nullable;

import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.registry.ModBlockEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * Drone Charging Station (DESIGN.md section 7.4). Stores FE and its placer's UUID, and holds a claim by the one drone
 * it serves at a time (section 5.3). The drone does the charging: it renews its claim every tick and pulls FE from
 * here, so the station needs no ticker. A claim that isn't renewed expires, so a drone that is picked up, destroyed or
 * unloaded never blocks the station.
 */
public class ChargingStationBlockEntity extends BlockEntity {
    private static final String TAG_ENERGY = "Energy";
    private static final String TAG_OWNER = "Owner";

    /** A claim not renewed for this many ticks lapses. */
    private static final int CLAIM_TIMEOUT_TICKS = 20;
    /** Height of the dock point (the drone's feet) above the top of the station. */
    private static final double DOCK_HEIGHT = 0.1;

    private final Energy energy = new Energy();
    @Nullable
    private UUID owner;

    // Not saved: a drone that was docked re-claims the station once it is loaded again.
    @Nullable
    private UUID claimant;
    private long claimTime;

    public ChargingStationBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CHARGING_STATION.get(), pos, state);
    }

    public IEnergyStorage getEnergyStorage() {
        return energy;
    }

    public int getEnergy() {
        return energy.stored;
    }

    /** Takes up to {@code amount} FE out of the station for the docked drone. Returns what was taken. */
    public int drainForDrone(int amount) {
        int taken = Math.min(amount, energy.stored);
        if (taken > 0) {
            energy.stored -= taken;
            setChanged();
        }
        return taken;
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
        if (owner != null) {
            tag.putUUID(TAG_OWNER, owner);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        energy.stored = Math.max(0, tag.getInt(TAG_ENERGY));
        owner = tag.hasUUID(TAG_OWNER) ? tag.getUUID(TAG_OWNER) : null;
    }

    /** Accepts FE on every side, never gives it out. The capacity follows the config. */
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
            return ServerConfig.get(ServerConfig.CHARGING_STATION_CAPACITY);
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
