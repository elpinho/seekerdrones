package com.elpinho.seekerdrones.deploying;

import java.util.Optional;

import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.drone.DroneItem;
import com.elpinho.seekerdrones.registry.ModBlockEntities;
import com.elpinho.seekerdrones.registry.ModDataComponents;
import com.elpinho.seekerdrones.registry.ModEntityTypes;
import com.elpinho.seekerdrones.registry.ModItems;
import com.elpinho.seekerdrones.registry.ModSounds;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * Drone Deploying Station (DESIGN.md section 7.3): deploys the drone in its slot above itself, for FE per deploy. With
 * auto-deploy on it deploys as soon as it can; with auto-deploy off, on the GUI button or a redstone pulse. A drone
 * that can't be deployed yet waits in the slot and is retried every {@code checkInterval} ticks.
 */
public class DeployingStationBlockEntity extends BlockEntity implements MenuProvider {
    public static final int DRONE_SLOT = 0;

    private static final String TAG_ITEMS = "Items";
    private static final String TAG_ENERGY = "Energy";
    private static final String TAG_AUTO_DEPLOY = "AutoDeploy";

    private final ItemStackHandler items = new ItemStackHandler(1) {
        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
            checkNow = true;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return stack.is(ModItems.DRONE.get());
        }

        @Override
        public int getSlotLimit(int slot) {
            return 1;
        }
    };
    private final Energy energy = new Energy();
    private final IItemHandler automationItems = new AutomationItemHandler();
    private final ContainerData data = new StationData();

    private boolean autoDeploy = true;
    /** Set when the slot changes, so the next tick checks at once instead of waiting for the interval. */
    private boolean checkNow = true;
    private DeployingStatus status = DeployingStatus.IDLE;

    public DeployingStationBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.DEPLOYING_STATION.get(), pos, state);
    }

    public ItemStackHandler getItems() {
        return items;
    }

    /** Drones in, never out (section 7.3). */
    public IItemHandler getAutomationItems() {
        return automationItems;
    }

    public IEnergyStorage getEnergyStorage() {
        return energy;
    }

    public boolean isAutoDeploy() {
        return autoDeploy;
    }

    public void setAutoDeploy(boolean autoDeploy) {
        if (this.autoDeploy != autoDeploy) {
            this.autoDeploy = autoDeploy;
            checkNow = true;
            setChanged();
        }
    }

    public DeployingStatus getStatus() {
        return status;
    }

    // --- Processing ---

    public static void serverTick(Level level, BlockPos pos, BlockState state, DeployingStationBlockEntity station) {
        station.tick(level);
    }

    private void tick(Level level) {
        int interval = ServerConfig.get(ServerConfig.DEPLOYING_STATION_CHECK_INTERVAL);
        // Staggered like the drone scans, so many stations don't all check on the same tick.
        if (!checkNow && Math.floorMod(level.getGameTime() + worldPosition.hashCode(), interval) != 0) {
            return;
        }
        checkNow = false;
        if (autoDeploy) {
            tryDeploy(level);
        } else {
            status = check(level);
        }
        if (getBlockState().getValue(DeployingStationBlock.SHAFT) != DeployingShaft.LAUNCHING) {
            setShaft(level, slotShaft());
        }
    }

    /** Called by the block's scheduled tick, {@link DeployingStationBlock#LAUNCH_TICKS} after a deploy. */
    void endLaunch(Level level) {
        setShaft(level, slotShaft());
    }

    private DeployingShaft slotShaft() {
        return items.getStackInSlot(DRONE_SLOT).isEmpty() ? DeployingShaft.EMPTY : DeployingShaft.LOADED;
    }

    /** Only writes the block state when it changes (section 7.6). */
    private void setShaft(Level level, DeployingShaft shaft) {
        BlockState state = getBlockState();
        if (state.getValue(DeployingStationBlock.SHAFT) != shaft) {
            level.setBlock(worldPosition, state.setValue(DeployingStationBlock.SHAFT, shaft), Block.UPDATE_CLIENTS);
        }
    }

    /** With auto-deploy off: the GUI button or a redstone pulse deploys the drone, if it can be deployed now. */
    public void requestDeploy() {
        if (!autoDeploy && level != null) {
            tryDeploy(level);
        }
    }

    private void tryDeploy(Level level) {
        status = check(level);
        if (status == DeployingStatus.READY) {
            deploy(level);
            status = check(level);
        }
    }

    /** Whether the drone in the slot could be deployed now, or why not. */
    private DeployingStatus check(Level level) {
        if (items.getStackInSlot(DRONE_SLOT).isEmpty()) {
            return DeployingStatus.IDLE;
        }
        if (energy.stored < energyPerDeploy()) {
            return DeployingStatus.NO_ENERGY;
        }
        return isSpaceClear(level) ? DeployingStatus.READY : DeployingStatus.BLOCKED;
    }

    /** Where the drone spawns: centered on the station, with the bottom of its box on the station's top face. */
    private Vec3 spawnPosition() {
        return Vec3.atBottomCenterOf(worldPosition.above());
    }

    /** The drone's box at the spawn spot, stretched up by the launch height, must be free of blocks and drones. */
    private boolean isSpaceClear(Level level) {
        AABB box = ModEntityTypes.DRONE.get().getDimensions().makeBoundingBox(spawnPosition())
                .expandTowards(0, launchHeight(), 0);
        return level.noCollision(box) && level.getEntitiesOfClass(DroneEntity.class, box).isEmpty();
    }

    private void deploy(Level level) {
        DroneEntity drone = ModEntityTypes.DRONE.get().create(level);
        if (drone == null) {
            return;
        }
        ItemStack stack = items.getStackInSlot(DRONE_SLOT);
        // The station never sets the owner, but a drone without an ID gets one (sections 2.8 and 6.3).
        DroneData droneData = DroneItem.getData(stack).withIdAssigned(level.getRandom());
        Vec3 spawn = spawnPosition();
        drone.moveTo(spawn.x, spawn.y, spawn.z, 0, 0);
        drone.setDroneData(droneData);
        // Under drift drag a velocity v carries the drone v / (1 - drag) blocks in total, so it rises launchHeight at
        // most (less, since it stops once it is slower than the rest speed).
        double drag = ServerConfig.get(ServerConfig.DRONE_DEPLOY_DRAG);
        drone.setDeltaMovement(0, launchHeight() * (1 - drag), 0);
        drone.startDrifting(GlobalPos.of(level.dimension(), worldPosition.above()));
        if (!level.addFreshEntity(drone)) {
            return;
        }
        drone.playDeploySound();
        level.playSound(null, worldPosition, ModSounds.DEPLOYING_STATION_LAUNCH.get(), SoundSource.BLOCKS,
                ServerConfig.get(ServerConfig.SOUNDS_LAUNCH_VOLUME).floatValue(), ServerConfig.get(ServerConfig.SOUNDS_LAUNCH_PITCH).floatValue());
        energy.stored -= energyPerDeploy();
        items.setStackInSlot(DRONE_SLOT, ItemStack.EMPTY);
        setChanged();
        level.blockEvent(worldPosition, getBlockState().getBlock(), DeployingStationBlock.EVENT_DEPLOYED, 0);
        setShaft(level, DeployingShaft.LAUNCHING);
        level.scheduleTick(worldPosition, getBlockState().getBlock(), DeployingStationBlock.LAUNCH_TICKS);
    }

    private static int energyPerDeploy() {
        return ServerConfig.get(ServerConfig.DEPLOYING_STATION_ENERGY_PER_DEPLOY);
    }

    private static double launchHeight() {
        return ServerConfig.get(ServerConfig.DEPLOYING_STATION_LAUNCH_HEIGHT);
    }

    // --- Menu ---

    @Override
    public Component getDisplayName() {
        return Component.translatable("screen.seekerdrones.deploying_station");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new DeployingStationMenu(containerId, inventory, this, data);
    }

    // --- Saving and item components ---

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put(TAG_ITEMS, items.serializeNBT(registries));
        tag.putInt(TAG_ENERGY, energy.stored);
        tag.putBoolean(TAG_AUTO_DEPLOY, autoDeploy);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        CompoundTag itemsTag = tag.getCompound(TAG_ITEMS);
        if (!itemsTag.isEmpty()) {
            // Keep the slot count even if a saved handler had a different size.
            ItemStackHandler loaded = new ItemStackHandler();
            loaded.deserializeNBT(registries, itemsTag);
            if (loaded.getSlots() > 0) {
                items.setStackInSlot(DRONE_SLOT, loaded.getStackInSlot(0));
            }
        }
        energy.stored = Math.max(0, tag.getInt(TAG_ENERGY));
        autoDeploy = !tag.contains(TAG_AUTO_DEPLOY) || tag.getBoolean(TAG_AUTO_DEPLOY);
    }

    /** The item keeps the auto-deploy setting when the station is broken (section 7.3). */
    @Override
    protected void applyImplicitComponents(DataComponentInput componentInput) {
        super.applyImplicitComponents(componentInput);
        Boolean saved = componentInput.get(ModDataComponents.DEPLOYING_STATION);
        if (saved != null) {
            autoDeploy = saved;
        }
    }

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        if (!autoDeploy) {
            components.set(ModDataComponents.DEPLOYING_STATION, false);
        }
    }

    @Override
    public void removeComponentsFromTag(CompoundTag tag) {
        super.removeComponentsFromTag(tag);
        tag.remove(TAG_AUTO_DEPLOY);
    }

    /** The drone in the slot, for dropping when the block is broken. */
    public Optional<ItemStack> getDrop() {
        ItemStack stack = items.getStackInSlot(DRONE_SLOT);
        return stack.isEmpty() ? Optional.empty() : Optional.of(stack);
    }

    private class AutomationItemHandler implements IItemHandler {
        @Override
        public int getSlots() {
            return items.getSlots();
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return items.getStackInSlot(slot);
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            return items.insertItem(slot, stack, simulate);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return ItemStack.EMPTY;
        }

        @Override
        public int getSlotLimit(int slot) {
            return items.getSlotLimit(slot);
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return items.isItemValid(slot, stack);
        }
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
            return ServerConfig.get(ServerConfig.DEPLOYING_STATION_ENERGY_CAPACITY);
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

    /**
     * Synced to the open menu. Container data travels as shorts, so every int is split into two 16-bit halves.
     * See {@link DeployingStationMenu} for the indices.
     */
    private class StationData implements ContainerData {
        @Override
        public int get(int index) {
            int value = switch (index / 2) {
                case DeployingStationMenu.DATA_ENERGY -> energy.stored;
                case DeployingStationMenu.DATA_ENERGY_CAPACITY -> energy.getMaxEnergyStored();
                case DeployingStationMenu.DATA_ENERGY_PER_DEPLOY -> energyPerDeploy();
                case DeployingStationMenu.DATA_AUTO_DEPLOY -> autoDeploy ? 1 : 0;
                case DeployingStationMenu.DATA_STATUS -> status.ordinal();
                default -> 0;
            };
            return index % 2 == 0 ? value & 0xFFFF : value >>> 16;
        }

        @Override
        public void set(int index, int value) {
            // Server-side data is read-only; the client copy lives in the menu.
        }

        @Override
        public int getCount() {
            return DeployingStationMenu.DATA_VALUES * 2;
        }
    }
}
