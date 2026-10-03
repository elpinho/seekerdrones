package com.elpinho.seekerdrones.programming;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.annotation.Nullable;

import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.drone.DroneConfig;
import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.DroneItem;
import com.elpinho.seekerdrones.drone.DroneStats;
import com.elpinho.seekerdrones.drone.UpgradeItem;
import com.elpinho.seekerdrones.drone.UpgradeType;
import com.elpinho.seekerdrones.machine.MachineWorkingState;
import com.elpinho.seekerdrones.registry.ModBlockEntities;
import com.elpinho.seekerdrones.registry.ModDataComponents;
import com.elpinho.seekerdrones.registry.ModItems;
import com.mojang.logging.LogUtils;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * Drone Programming Station (DESIGN.md section 7.2). In Direct mode a player edits the drone in the slot by hand. In
 * Template mode every drone in the slot is brought to the stored template, one step at a time, and automation may
 * pull it out once it matches. Upgrades are installed in timed steps that spend FE evenly.
 */
public class ProgrammingStationBlockEntity extends BlockEntity implements MenuProvider {
    public static final int DRONE_SLOT = 0;
    public static final int INPUT_START = 1;
    public static final int INPUT_SLOTS = 9;
    public static final int SLOT_COUNT = INPUT_START + INPUT_SLOTS;

    private static final String TAG_ITEMS = "Items";
    private static final String TAG_ENERGY = "Energy";
    private static final String TAG_MODE = "Mode";
    private static final String TAG_TEMPLATE = "Template";
    private static final String TAG_INSTALLING = "Installing";
    private static final String TAG_PROGRESS = "Progress";
    private static final String TAG_ENERGY_SPENT = "EnergySpent";
    private static final String TAG_STEP_COST = "StepCost";
    private static final String TAG_ACCEPTED = "Accepted";

    private final ItemStackHandler items = new ItemStackHandler(SLOT_COUNT) {
        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
            if (slot == DRONE_SLOT) {
                // A different drone (or none): whatever step was running was for the old one.
                cancelStep();
                accepted = mode == ProgrammingMode.TEMPLATE && !getStackInSlot(DRONE_SLOT).isEmpty();
            }
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return slot == DRONE_SLOT ? stack.is(ModItems.DRONE.get()) : stack.getItem() instanceof UpgradeItem;
        }

        @Override
        public int getSlotLimit(int slot) {
            return slot == DRONE_SLOT ? 1 : super.getSlotLimit(slot);
        }
    };
    private final Energy energy = new Energy();
    private final IItemHandler automationItems = new AutomationItemHandler();
    private final ContainerData data = new StationData();
    private final MachineWorkingState working = new MachineWorkingState();

    private ProgrammingMode mode = ProgrammingMode.DIRECT;
    private DroneProgram template = DroneProgram.createDefault();
    /** Bumped when the mode or template changes, so open menus resend the template. */
    private int programVersion;
    /**
     * Whether the drone in the slot was inserted in Template mode, so the template applies to it. Switching modes never
     * changes the drone already in the slot (section 7.2).
     */
    private boolean accepted;

    // The running install step, if any.
    @Nullable
    private UpgradeType installing;
    private int progress;
    private int energySpent;
    private int stepCost;

    public ProgrammingStationBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.PROGRAMMING_STATION.get(), pos, state);
    }

    public ItemStackHandler getItems() {
        return items;
    }

    /** Drones and upgrades in; the drone out only when complete in Template mode (section 7.2). */
    public IItemHandler getAutomationItems() {
        return automationItems;
    }

    public IEnergyStorage getEnergyStorage() {
        return energy;
    }

    public ProgrammingMode getMode() {
        return mode;
    }

    public DroneProgram getTemplate() {
        return template;
    }

    public int getProgramVersion() {
        return programVersion;
    }

    @Nullable
    public UpgradeType getInstalling() {
        return installing;
    }

    /** The drone in the slot, if any. */
    public Optional<DroneData> getDrone() {
        ItemStack stack = items.getStackInSlot(DRONE_SLOT);
        return stack.isEmpty() ? Optional.empty() : Optional.of(DroneItem.getData(stack));
    }

    /** Changes the drone in place, which doesn't count as a new drone (no step is cancelled). */
    private void writeDrone(DroneData drone) {
        items.getStackInSlot(DRONE_SLOT).set(ModDataComponents.DRONE_DATA, drone);
        setChanged();
    }

    /** Whether the template fits the current caps. A lowered server config can make a saved template invalid. */
    public boolean isTemplateValid() {
        return DroneStats.withinUpgradeLimits(template.upgrades());
    }

    /** Template mode, the drone was inserted in Template mode, and it matches the template exactly. */
    public boolean isComplete() {
        return mode == ProgrammingMode.TEMPLATE && accepted && isTemplateValid() && getDrone().map(template::matches).orElse(false);
    }

    private boolean inputHas(UpgradeType type) {
        return findInput(type) >= 0;
    }

    private int findInput(UpgradeType type) {
        for (int i = INPUT_START; i < SLOT_COUNT; i++) {
            if (items.getStackInSlot(i).getItem() instanceof UpgradeItem upgrade && upgrade.getType() == type) {
                return i;
            }
        }
        return -1;
    }

    // --- Processing ---

    public static void serverTick(Level level, BlockPos pos, BlockState state, ProgrammingStationBlockEntity station) {
        station.working.update(level, pos, station.tick());
    }

    /** Returns whether an install step made progress this tick. */
    private boolean tick() {
        Optional<DroneData> current = getDrone();
        if (current.isEmpty()) {
            cancelStep();
            return false;
        }
        DroneData drone = current.get();
        if (mode == ProgrammingMode.TEMPLATE) {
            if (!accepted || !isTemplateValid()) {
                cancelStep();
                return false;
            }
            if (!drone.config().equals(template.config())) {
                drone = drone.withConfig(template.config());
                writeDrone(drone);
            }
            if (installing != null && drone.upgradeCount(installing) >= template.upgradeCount(installing)) {
                cancelStep();
            }
            if (installing == null) {
                UpgradeType next = nextWanted(drone);
                if (next != null) {
                    startStep(next, drone);
                }
            }
        }
        return installing != null && advanceStep(drone);
    }

    /** The first type (in a fixed order) the template wants more of, that is in the input and fits the caps. */
    @Nullable
    private UpgradeType nextWanted(DroneData drone) {
        for (UpgradeType type : UpgradeType.values()) {
            if (drone.upgradeCount(type) < template.upgradeCount(type) && inputHas(type) && ProgramRules.canAdd(drone.upgrades(), type)) {
                return type;
            }
        }
        return null;
    }

    private void startStep(UpgradeType type, DroneData drone) {
        installing = type;
        progress = 0;
        energySpent = 0;
        stepCost = ProgramRules.installCost(type, drone.upgradeCount(type) + 1);
        setChanged();
    }

    /** Returns whether the step made progress. */
    private boolean advanceStep(DroneData drone) {
        int time = installTime();
        int remainingTicks = time - progress;
        int cost = remainingTicks <= 1 ? stepCost - energySpent : stepCost / time;
        if (energy.stored < cost) {
            return false;
        }
        energy.stored -= cost;
        energySpent += cost;
        progress++;
        setChanged();
        if (progress >= time) {
            finishStep(drone);
        }
        return true;
    }

    private void finishStep(DroneData drone) {
        UpgradeType type = installing;
        int slot = findInput(type);
        cancelStep();
        if (slot < 0 || !ProgramRules.canAdd(drone.upgrades(), type)) {
            return;
        }
        items.extractItem(slot, 1, false);
        writeDrone(drone.withUpgradeCount(type, drone.upgradeCount(type) + 1));
    }

    private void cancelStep() {
        if (installing != null || progress != 0 || energySpent != 0) {
            installing = null;
            progress = 0;
            energySpent = 0;
            stepCost = 0;
            setChanged();
        }
    }

    private static int installTime() {
        return ServerConfig.get(ServerConfig.PROGRAMMING_STATION_INSTALL_TIME);
    }

    // --- Edits from the GUI (checked again here, never trusted) ---

    public void setMode(ProgrammingMode mode) {
        if (this.mode != mode) {
            cancelStep();
            this.mode = mode;
            accepted = false;
            programChanged();
        }
    }

    /** Direct mode: starts installing one upgrade of the type from the input. */
    public void requestInstall(UpgradeType type) {
        Optional<DroneData> drone = getDrone();
        if (mode == ProgrammingMode.DIRECT && installing == null && drone.isPresent() && inputHas(type)
                && ProgramRules.canAdd(drone.get().upgrades(), type)) {
            startStep(type, drone.get());
        }
    }

    /**
     * Manual removal: takes one upgrade off the drone and gives it to the player, or drops it at their feet (section
     * 7.2). In Template mode only upgrades beyond the template can be removed.
     */
    public void removeUpgrade(UpgradeType type, Player player) {
        Optional<DroneData> current = getDrone();
        if (current.isEmpty()) {
            return;
        }
        DroneData drone = current.get();
        int count = drone.upgradeCount(type);
        if (count <= 0 || (mode == ProgrammingMode.TEMPLATE && count <= template.upgradeCount(type))) {
            return;
        }
        writeDrone(drone.withUpgradeCount(type, count - 1));
        ItemHandlerHelper.giveItemToPlayer(player, new ItemStack(ModItems.upgrade(type).get()));
    }

    /** Template mode: sets the programmed count of a type, within the caps. */
    public void setProgramCount(UpgradeType type, int count) {
        if (mode != ProgrammingMode.TEMPLATE || count < 0 || count == template.upgradeCount(type)) {
            return;
        }
        DroneProgram updated = template.withUpgradeCount(type, count);
        // Lowering is always allowed, so a template made invalid by a lowered config can be fixed.
        if (count > template.upgradeCount(type) && !DroneStats.withinUpgradeLimits(updated.upgrades())) {
            return;
        }
        template = updated;
        programChanged();
    }

    /** Sets the settings: on the drone in Direct mode, on the template in Template mode. */
    public void setConfig(DroneConfig requested) {
        if (level == null) {
            return;
        }
        if (mode == ProgrammingMode.DIRECT) {
            getDrone().ifPresent(drone -> ProgramRules.checkConfig(requested, drone.config(), drone.upgrades(), level.dimension())
                    .ifPresent(config -> writeDrone(drone.withConfig(config))));
        } else {
            ProgramRules.checkConfig(requested, template.config(), template.upgrades(), level.dimension()).ifPresent(config -> {
                template = template.withConfig(config);
                programChanged();
            });
        }
    }

    private void programChanged() {
        programVersion++;
        setChanged();
    }

    // --- Menu ---

    @Override
    public Component getDisplayName() {
        return Component.translatable("screen.seekerdrones.programming_station");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new ProgrammingStationMenu(containerId, inventory, this, data);
    }

    // --- Saving and item components ---

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put(TAG_ITEMS, items.serializeNBT(registries));
        tag.putInt(TAG_ENERGY, energy.stored);
        tag.putString(TAG_MODE, mode.getSerializedName());
        DroneProgram.CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), template)
                .resultOrPartial(LogUtils.getLogger()::error)
                .ifPresent(encoded -> tag.put(TAG_TEMPLATE, encoded));
        if (installing != null) {
            tag.putString(TAG_INSTALLING, installing.getSerializedName());
            tag.putInt(TAG_PROGRESS, progress);
            tag.putInt(TAG_ENERGY_SPENT, energySpent);
            tag.putInt(TAG_STEP_COST, stepCost);
        }
        tag.putBoolean(TAG_ACCEPTED, accepted);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        CompoundTag itemsTag = tag.getCompound(TAG_ITEMS);
        if (!itemsTag.isEmpty()) {
            // Keep the slot count even if a saved handler had a different size.
            ItemStackHandler loaded = new ItemStackHandler();
            loaded.deserializeNBT(registries, itemsTag);
            for (int i = 0; i < Math.min(SLOT_COUNT, loaded.getSlots()); i++) {
                items.setStackInSlot(i, loaded.getStackInSlot(i));
            }
        }
        energy.stored = Math.max(0, tag.getInt(TAG_ENERGY));
        ProgrammingMode loadedMode = ProgrammingMode.CODEC.byName(tag.getString(TAG_MODE));
        mode = loadedMode != null ? loadedMode : ProgrammingMode.DIRECT;
        if (tag.contains(TAG_TEMPLATE)) {
            template = DroneProgram.CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), tag.get(TAG_TEMPLATE))
                    .resultOrPartial(LogUtils.getLogger()::error)
                    .orElseGet(DroneProgram::createDefault);
        }
        // Set after the items, whose change callback cancels the step.
        installing = UpgradeType.CODEC.byName(tag.getString(TAG_INSTALLING));
        progress = installing != null ? Math.max(0, tag.getInt(TAG_PROGRESS)) : 0;
        energySpent = installing != null ? Math.max(0, tag.getInt(TAG_ENERGY_SPENT)) : 0;
        stepCost = installing != null ? Math.max(0, tag.getInt(TAG_STEP_COST)) : 0;
        accepted = tag.getBoolean(TAG_ACCEPTED);
        programVersion++;
    }

    /** The item keeps the mode and template when the station is broken (section 7.2). */
    @Override
    protected void applyImplicitComponents(DataComponentInput componentInput) {
        super.applyImplicitComponents(componentInput);
        ProgrammingStationSettings settings = componentInput.get(ModDataComponents.PROGRAMMING_STATION);
        if (settings != null) {
            mode = settings.mode();
            template = settings.template();
            programVersion++;
        }
    }

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        if (mode != ProgrammingMode.DIRECT || !template.equals(DroneProgram.createDefault())) {
            components.set(ModDataComponents.PROGRAMMING_STATION, new ProgrammingStationSettings(mode, template));
        }
    }

    @Override
    public void removeComponentsFromTag(CompoundTag tag) {
        super.removeComponentsFromTag(tag);
        tag.remove(TAG_MODE);
        tag.remove(TAG_TEMPLATE);
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
            return items.insertItem(slot, stack, simulate);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return slot == DRONE_SLOT && isComplete() ? items.extractItem(slot, amount, simulate) : ItemStack.EMPTY;
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
            return ServerConfig.get(ServerConfig.PROGRAMMING_STATION_ENERGY_CAPACITY);
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
     * See {@link ProgrammingStationMenu} for the indices.
     */
    private class StationData implements ContainerData {
        @Override
        public int get(int index) {
            int value = switch (index / 2) {
                case ProgrammingStationMenu.DATA_ENERGY -> energy.stored;
                case ProgrammingStationMenu.DATA_ENERGY_CAPACITY -> energy.getMaxEnergyStored();
                case ProgrammingStationMenu.DATA_PROGRESS -> progress;
                case ProgrammingStationMenu.DATA_TIME -> installTime();
                case ProgrammingStationMenu.DATA_INSTALLING -> installing != null ? installing.ordinal() + 1 : 0;
                case ProgrammingStationMenu.DATA_STEP_COST -> stepCost;
                case ProgrammingStationMenu.DATA_MODE -> mode.ordinal();
                case ProgrammingStationMenu.DATA_ACCEPTED -> accepted ? 1 : 0;
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
            return ProgrammingStationMenu.DATA_VALUES * 2;
        }
    }
}
