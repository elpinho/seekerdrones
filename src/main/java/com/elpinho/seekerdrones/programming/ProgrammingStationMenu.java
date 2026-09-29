package com.elpinho.seekerdrones.programming;

import javax.annotation.Nullable;

import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.DroneItem;
import com.elpinho.seekerdrones.drone.UpgradeItem;
import com.elpinho.seekerdrones.drone.UpgradeType;
import com.elpinho.seekerdrones.network.ProgramTemplatePayload;
import com.elpinho.seekerdrones.registry.ModBlocks;
import com.elpinho.seekerdrones.registry.ModItems;
import com.elpinho.seekerdrones.registry.ModMenuTypes;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.SlotItemHandler;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Programming Station menu (DESIGN.md section 7.2): the drone slot, the upgrade input and the player inventory, plus FE,
 * install step and mode values. The template is synced separately with {@link ProgramTemplatePayload}.
 */
public class ProgrammingStationMenu extends AbstractContainerMenu {
    // Container data indices, each an int split into two shorts (see ProgrammingStationBlockEntity.StationData).
    static final int DATA_ENERGY = 0;
    static final int DATA_ENERGY_CAPACITY = 1;
    static final int DATA_PROGRESS = 2;
    static final int DATA_TIME = 3;
    static final int DATA_INSTALLING = 4;
    static final int DATA_STEP_COST = 5;
    static final int DATA_MODE = 6;
    static final int DATA_ACCEPTED = 7;
    static final int DATA_VALUES = 8;

    public static final int DRONE_X = 32;
    public static final int DRONE_Y = 20;
    public static final int INPUT_X = 26;
    public static final int INPUT_Y = 52;
    public static final int INVENTORY_X = 60;
    public static final int INVENTORY_Y = 178;

    private static final int PLAYER_SLOTS_START = ProgrammingStationBlockEntity.SLOT_COUNT;
    private static final int PLAYER_SLOTS_END = PLAYER_SLOTS_START + 36;
    /** All slots, the station's and the player's. */
    public static final int SLOT_TOTAL = PLAYER_SLOTS_END;

    private final ContainerLevelAccess access;
    private final BlockPos pos;
    private final Player player;
    @Nullable
    private final ProgrammingStationBlockEntity station;
    private final ContainerData data;
    /** Server only: the template version last sent to the client. */
    private int sentVersion = -1;
    /** Client only: the latest template from the server, or null before the first one arrives. */
    @Nullable
    private DroneProgram template;

    /** Client side, opened from the server's extra data. */
    public ProgrammingStationMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf extraData) {
        this(containerId, inventory, extraData.readBlockPos(), null, new ItemStackHandler(ProgrammingStationBlockEntity.SLOT_COUNT),
                new SimpleContainerData(DATA_VALUES * 2));
    }

    /** Server side. */
    public ProgrammingStationMenu(int containerId, Inventory inventory, ProgrammingStationBlockEntity station, ContainerData data) {
        this(containerId, inventory, station.getBlockPos(), station, station.getItems(), data);
    }

    private ProgrammingStationMenu(int containerId, Inventory inventory, BlockPos pos, @Nullable ProgrammingStationBlockEntity station,
            IItemHandler items, ContainerData data) {
        super(ModMenuTypes.PROGRAMMING_STATION.get(), containerId);
        this.access = station != null && station.getLevel() != null ? ContainerLevelAccess.create(station.getLevel(), pos) : ContainerLevelAccess.NULL;
        this.pos = pos;
        this.player = inventory.player;
        this.station = station;
        this.data = data;

        addSlot(new SlotItemHandler(items, ProgrammingStationBlockEntity.DRONE_SLOT, DRONE_X, DRONE_Y));
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                addSlot(new SlotItemHandler(items, ProgrammingStationBlockEntity.INPUT_START + row * 3 + column,
                        INPUT_X + column * 18, INPUT_Y + row * 18));
            }
        }
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, column + row * 9 + 9, INVENTORY_X + column * 18, INVENTORY_Y + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(inventory, column, INVENTORY_X + column * 18, INVENTORY_Y + 58));
        }
        addDataSlots(data);
    }

    public BlockPos getPos() {
        return pos;
    }

    /** The server-side block entity, null on the client. */
    @Nullable
    public ProgrammingStationBlockEntity getStation() {
        return station;
    }

    /** Client: the drone in the slot, synced like any slot. */
    @Nullable
    public DroneData getDrone() {
        ItemStack stack = slots.get(ProgrammingStationBlockEntity.DRONE_SLOT).getItem();
        return stack.isEmpty() ? null : DroneItem.getData(stack);
    }

    /** Client: how many upgrades of the type are in the input. */
    public int inputCount(UpgradeType type) {
        int count = 0;
        for (int i = ProgrammingStationBlockEntity.INPUT_START; i < ProgrammingStationBlockEntity.SLOT_COUNT; i++) {
            ItemStack stack = slots.get(i).getItem();
            if (stack.getItem() instanceof UpgradeItem upgrade && upgrade.getType() == type) {
                count += stack.getCount();
            }
        }
        return count;
    }

    @Nullable
    public DroneProgram getTemplate() {
        return template;
    }

    public void setTemplate(DroneProgram template) {
        this.template = template;
    }

    private int value(int index) {
        // Each half arrives as a sign-extended short.
        return (data.get(index * 2 + 1) & 0xFFFF) << 16 | data.get(index * 2) & 0xFFFF;
    }

    public int getEnergy() {
        return value(DATA_ENERGY);
    }

    public int getEnergyCapacity() {
        return value(DATA_ENERGY_CAPACITY);
    }

    public int getProgress() {
        return value(DATA_PROGRESS);
    }

    public int getTime() {
        return value(DATA_TIME);
    }

    /** The upgrade type being installed, or null. */
    @Nullable
    public UpgradeType getInstalling() {
        int id = value(DATA_INSTALLING);
        return id > 0 && id <= UpgradeType.values().length ? UpgradeType.values()[id - 1] : null;
    }

    public int getStepCost() {
        return value(DATA_STEP_COST);
    }

    public ProgrammingMode getMode() {
        return ProgrammingMode.BY_ID.apply(value(DATA_MODE));
    }

    /** Whether the drone in the slot was inserted in Template mode, so the template applies to it. */
    public boolean isAccepted() {
        return value(DATA_ACCEPTED) != 0;
    }

    /** Also sends the template whenever it changed, including right after the menu opens. */
    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        if (station != null && player instanceof ServerPlayer serverPlayer && station.getProgramVersion() != sentVersion) {
            sentVersion = station.getProgramVersion();
            PacketDistributor.sendToPlayer(serverPlayer, new ProgramTemplatePayload(containerId, station.getTemplate()));
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        if (index < PLAYER_SLOTS_START) {
            if (!moveItemStackTo(stack, PLAYER_SLOTS_START, PLAYER_SLOTS_END, true)) {
                return ItemStack.EMPTY;
            }
        } else if (stack.is(ModItems.DRONE.get())) {
            if (!moveItemStackTo(stack, ProgrammingStationBlockEntity.DRONE_SLOT, ProgrammingStationBlockEntity.DRONE_SLOT + 1, false)) {
                return ItemStack.EMPTY;
            }
        } else if (!moveItemStackTo(stack, ProgrammingStationBlockEntity.INPUT_START, ProgrammingStationBlockEntity.SLOT_COUNT, false)) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        if (stack.getCount() == original.getCount()) {
            return ItemStack.EMPTY;
        }
        slot.onTake(player, stack);
        return original;
    }

    @Override
    public boolean stillValid(Player player) {
        return stillValid(access, player, ModBlocks.PROGRAMMING_STATION.get());
    }
}
