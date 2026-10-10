package com.elpinho.seekerdrones.factory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.annotation.Nullable;

import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.DroneIds;
import com.elpinho.seekerdrones.drone.DroneItem;
import com.elpinho.seekerdrones.machine.MachineWorkingState;
import com.elpinho.seekerdrones.registry.ModBlockEntities;
import com.elpinho.seekerdrones.registry.ModDataComponents;
import com.elpinho.seekerdrones.registry.ModRecipeTypes;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * Drone Factory (DESIGN.md section 7.1): builds drones from items, fluid and FE with {@code seekerdrones:drone_assembly}
 * recipes, with the inputs in slots by role ({@link FactorySlots}). FE is spent evenly over the processing time; items and fluid are taken when the drone is done. The output
 * drone is fully charged, gets a new drone ID and is linked to this Factory's Operator Group (section 6.1).
 */
public class DroneFactoryBlockEntity extends BlockEntity implements MenuProvider {
    public static final int INPUT_SLOTS = FactorySlots.INPUT_SLOTS;
    public static final int OUTPUT_SLOT = FactorySlots.OUTPUT;
    public static final int SLOT_COUNT = FactorySlots.COUNT;
    /** Before slot roles the Factory had six inputs, with the output in slot 6. */
    private static final int LEGACY_SLOT_COUNT = 7;

    private static final String TAG_ITEMS = "Items";
    private static final String TAG_TANK = "Tank";
    private static final String TAG_ENERGY = "Energy";
    private static final String TAG_PROGRESS = "Progress";
    private static final String TAG_ENERGY_SPENT = "EnergySpent";
    private static final String TAG_GROUP = "GroupId";

    /** Bumped when recipes reload, so every Factory looks its recipe up again. */
    private static int recipeGeneration;

    private final ItemStackHandler items = new FactorySlots.Handler(this::getLevel) {
        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
            if (slot != OUTPUT_SLOT) {
                recipeDirty = true;
            }
        }
    };
    private final FluidTank tank = new FluidTank(ServerConfig.get(ServerConfig.FACTORY_TANK_CAPACITY)) {
        @Override
        protected void onContentsChanged() {
            setChanged();
            recipeDirty = true;
        }
    };
    private final Energy energy = new Energy();
    private final IItemHandler automationItems = new AutomationItemHandler();

    @Nullable
    private UUID groupId;
    private int progress;
    /** FE already spent on the current build, so the last tick can take exactly the rest. */
    private int energySpent;

    // Recipe cache (section 8.4 spirit: no recipe lookup every tick).
    @Nullable
    private RecipeHolder<DroneAssemblyRecipe> recipe;
    /** Whether the inputs match a recipe whose fluid the tank lacks, found with the recipe. */
    private boolean fluidMissing;
    private boolean recipeDirty = true;
    private int cachedGeneration = -1;

    private final ContainerData data = new FactoryData();
    private final MachineWorkingState working = new MachineWorkingState();
    private FactoryStatus status = FactoryStatus.IDLE;

    public DroneFactoryBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.DRONE_FACTORY.get(), pos, state);
    }

    public static void onRecipesReloaded() {
        recipeGeneration++;
    }

    public ItemStackHandler getItems() {
        return items;
    }

    /** Inputs are insert-only and the output is extract-only for automation, on every side. */
    public IItemHandler getAutomationItems() {
        return automationItems;
    }

    public IFluidHandler getFluidHandler() {
        return tank;
    }

    public FluidStack getFluid() {
        return tank.getFluid();
    }

    public IEnergyStorage getEnergyStorage() {
        return energy;
    }

    public Optional<UUID> getGroupId() {
        return Optional.ofNullable(groupId);
    }

    void setGroupId(@Nullable UUID groupId) {
        this.groupId = groupId;
        setChanged();
    }

    public int getProgress() {
        return progress;
    }

    public FactoryStatus getStatus() {
        return status;
    }

    // --- Processing ---

    public static void serverTick(Level level, BlockPos pos, BlockState state, DroneFactoryBlockEntity factory) {
        factory.working.update(level, pos, factory.tick(level));
    }

    /** Returns whether the build made progress this tick. */
    private boolean tick(Level level) {
        if (recipeDirty || cachedGeneration != recipeGeneration) {
            RecipeHolder<DroneAssemblyRecipe> previous = recipe;
            recipe = findRecipe(level);
            recipeDirty = false;
            cachedGeneration = recipeGeneration;
            // After a load there is no previous recipe yet, so the saved progress is kept if a recipe still matches.
            if (recipe == null || (previous != null && !recipe.id().equals(previous.id()))) {
                resetProgress();
            }
        }
        if (recipe == null) {
            status = fluidMissing ? FactoryStatus.MISSING_FLUID : FactoryStatus.IDLE;
            return false;
        }
        if (!items.getStackInSlot(OUTPUT_SLOT).isEmpty()) {
            status = FactoryStatus.OUTPUT_FULL;
            return false;
        }
        DroneAssemblyRecipe assembly = recipe.value();
        int remainingTicks = assembly.time() - progress;
        int cost = remainingTicks <= 1 ? assembly.energy() - energySpent : assembly.energy() / assembly.time();
        if (energy.stored < cost) {
            status = FactoryStatus.NO_ENERGY;
            return false;
        }
        status = FactoryStatus.BUILDING;
        energy.stored -= cost;
        energySpent += cost;
        progress++;
        setChanged();
        if (progress >= assembly.time()) {
            finish(level, assembly);
        }
        return true;
    }

    @Nullable
    private RecipeHolder<DroneAssemblyRecipe> findRecipe(Level level) {
        DroneAssemblyInput input = input();
        fluidMissing = false;
        for (RecipeHolder<DroneAssemblyRecipe> holder : level.getRecipeManager().getAllRecipesFor(ModRecipeTypes.DRONE_ASSEMBLY.get())) {
            if (holder.value().itemsMatch(input.items())) {
                if (holder.value().fluid().test(input.fluid())) {
                    return holder;
                }
                fluidMissing = true;
            }
        }
        return null;
    }

    private DroneAssemblyInput input() {
        List<ItemStack> stacks = new ArrayList<>(INPUT_SLOTS);
        for (int i = 0; i < INPUT_SLOTS; i++) {
            stacks.add(items.getStackInSlot(i));
        }
        return new DroneAssemblyInput(stacks, tank.getFluid());
    }

    private void finish(Level level, DroneAssemblyRecipe assembly) {
        if (!assembly.itemsMatch(input().items()) || !assembly.fluid().test(tank.getFluid())) {
            // Can't happen while the cache is fresh, but never build a drone for free.
            resetProgress();
            recipeDirty = true;
            return;
        }
        for (int i = 0; i < INPUT_SLOTS; i++) {
            int count = assembly.countFor(i);
            if (count > 0) {
                items.extractItem(i, count, false);
            }
        }
        tank.drain(assembly.fluid().amount(), IFluidHandler.FluidAction.EXECUTE);
        DroneData drone = DroneData.createNew()
                .withDroneId(DroneIds.generate(level.getRandom()))
                .withGroupId(getGroupId());
        items.setStackInSlot(OUTPUT_SLOT, DroneItem.createStack(drone));
        resetProgress();
    }

    private void resetProgress() {
        if (progress != 0 || energySpent != 0) {
            progress = 0;
            energySpent = 0;
            setChanged();
        }
    }

    // --- Menu ---

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.seekerdrones.drone_factory");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new DroneFactoryMenu(containerId, inventory, this, data);
    }

    /** The total time of the current recipe, or 0 without one. */
    public int recipeTime() {
        return recipe != null ? recipe.value().time() : 0;
    }

    // --- Saving and item components ---

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put(TAG_ITEMS, items.serializeNBT(registries));
        tag.put(TAG_TANK, tank.writeToNBT(registries, new CompoundTag()));
        tag.putInt(TAG_ENERGY, energy.stored);
        tag.putInt(TAG_PROGRESS, progress);
        tag.putInt(TAG_ENERGY_SPENT, energySpent);
        if (groupId != null) {
            tag.putUUID(TAG_GROUP, groupId);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        CompoundTag itemsTag = tag.getCompound(TAG_ITEMS);
        if (!itemsTag.isEmpty()) {
            // Keep the slot count even if a saved handler had a different size.
            ItemStackHandler loaded = new ItemStackHandler();
            loaded.deserializeNBT(registries, itemsTag);
            if (loaded.getSlots() == LEGACY_SLOT_COUNT) {
                // A Factory saved before slot roles: its output moves to the new output slot. Inputs keep their slot
                // numbers and can be taken out by hand if they're in the wrong role.
                for (int i = 0; i < LEGACY_SLOT_COUNT - 1; i++) {
                    items.setStackInSlot(i, loaded.getStackInSlot(i));
                }
                items.setStackInSlot(OUTPUT_SLOT, loaded.getStackInSlot(LEGACY_SLOT_COUNT - 1));
            } else {
                for (int i = 0; i < Math.min(SLOT_COUNT, loaded.getSlots()); i++) {
                    items.setStackInSlot(i, loaded.getStackInSlot(i));
                }
            }
        }
        tank.setCapacity(ServerConfig.get(ServerConfig.FACTORY_TANK_CAPACITY));
        tank.readFromNBT(registries, tag.getCompound(TAG_TANK));
        energy.stored = Math.max(0, tag.getInt(TAG_ENERGY));
        progress = Math.max(0, tag.getInt(TAG_PROGRESS));
        energySpent = Math.max(0, tag.getInt(TAG_ENERGY_SPENT));
        groupId = tag.hasUUID(TAG_GROUP) ? tag.getUUID(TAG_GROUP) : null;
        recipeDirty = true;
    }

    /** The Factory item keeps its group ID when broken, so placing it again reconnects to the group (section 6.1). */
    @Override
    protected void applyImplicitComponents(DataComponentInput componentInput) {
        super.applyImplicitComponents(componentInput);
        UUID fromItem = componentInput.get(ModDataComponents.OPERATOR_GROUP);
        if (fromItem != null) {
            groupId = fromItem;
        }
    }

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        if (groupId != null) {
            components.set(ModDataComponents.OPERATOR_GROUP, groupId);
        }
    }

    @Override
    public void removeComponentsFromTag(CompoundTag tag) {
        super.removeComponentsFromTag(tag);
        tag.remove(TAG_GROUP);
    }

    /** All item stacks, for dropping when the block is broken. */
    public List<ItemStack> getDrops() {
        List<ItemStack> drops = new ArrayList<>();
        for (int i = 0; i < items.getSlots(); i++) {
            if (!items.getStackInSlot(i).isEmpty()) {
                drops.add(items.getStackInSlot(i));
            }
        }
        return drops;
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
            return slot == OUTPUT_SLOT ? stack : items.insertItem(slot, stack, simulate);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return slot == OUTPUT_SLOT ? items.extractItem(slot, amount, simulate) : ItemStack.EMPTY;
        }

        @Override
        public int getSlotLimit(int slot) {
            return items.getSlotLimit(slot);
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return slot != OUTPUT_SLOT && items.isItemValid(slot, stack);
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
            return ServerConfig.get(ServerConfig.FACTORY_ENERGY_CAPACITY);
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
     * See {@link DroneFactoryMenu} for the indices.
     */
    private class FactoryData implements ContainerData {
        @Override
        public int get(int index) {
            int value = switch (index / 2) {
                case DroneFactoryMenu.DATA_PROGRESS -> progress;
                case DroneFactoryMenu.DATA_TIME -> recipeTime();
                case DroneFactoryMenu.DATA_ENERGY -> energy.stored;
                case DroneFactoryMenu.DATA_ENERGY_CAPACITY -> energy.getMaxEnergyStored();
                case DroneFactoryMenu.DATA_FLUID_AMOUNT -> tank.getFluidAmount();
                case DroneFactoryMenu.DATA_TANK_CAPACITY -> tank.getCapacity();
                case DroneFactoryMenu.DATA_FLUID_ID -> tank.isEmpty() ? -1
                        : BuiltInRegistries.FLUID.getId(tank.getFluid().getFluid());
                case DroneFactoryMenu.DATA_STATUS -> status.ordinal();
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
            return DroneFactoryMenu.DATA_VALUES * 2;
        }
    }
}
