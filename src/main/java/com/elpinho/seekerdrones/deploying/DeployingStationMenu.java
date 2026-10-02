package com.elpinho.seekerdrones.deploying;

import javax.annotation.Nullable;

import com.elpinho.seekerdrones.registry.ModBlocks;
import com.elpinho.seekerdrones.registry.ModItems;
import com.elpinho.seekerdrones.registry.ModMenuTypes;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
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

/**
 * Deploying Station menu (DESIGN.md section 7.3): the drone slot and the player inventory, plus FE, auto-deploy and
 * status values. The auto-deploy toggle and the Deploy button are menu button clicks.
 */
public class DeployingStationMenu extends AbstractContainerMenu {
    // Container data indices, each an int split into two shorts (see DeployingStationBlockEntity.StationData).
    static final int DATA_ENERGY = 0;
    static final int DATA_ENERGY_CAPACITY = 1;
    static final int DATA_ENERGY_PER_DEPLOY = 2;
    static final int DATA_AUTO_DEPLOY = 3;
    static final int DATA_STATUS = 4;
    static final int DATA_VALUES = 5;

    public static final int BUTTON_TOGGLE_AUTO_DEPLOY = 0;
    public static final int BUTTON_DEPLOY = 1;

    public static final int DRONE_X = 62;
    public static final int DRONE_Y = 55;
    public static final int INVENTORY_Y = 112;

    private static final int PLAYER_SLOTS_START = 1;
    private static final int PLAYER_SLOTS_END = PLAYER_SLOTS_START + 36;

    private final ContainerLevelAccess access;
    private final BlockPos pos;
    @Nullable
    private final DeployingStationBlockEntity station;
    private final ContainerData data;

    /** Client side, opened from the server's extra data. */
    public DeployingStationMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf extraData) {
        this(containerId, inventory, extraData.readBlockPos(), null, new ItemStackHandler(1), new SimpleContainerData(DATA_VALUES * 2));
    }

    /** Server side. */
    public DeployingStationMenu(int containerId, Inventory inventory, DeployingStationBlockEntity station, ContainerData data) {
        this(containerId, inventory, station.getBlockPos(), station, station.getItems(), data);
    }

    private DeployingStationMenu(int containerId, Inventory inventory, BlockPos pos, @Nullable DeployingStationBlockEntity station,
            IItemHandler items, ContainerData data) {
        super(ModMenuTypes.DEPLOYING_STATION.get(), containerId);
        this.access = station != null && station.getLevel() != null ? ContainerLevelAccess.create(station.getLevel(), pos) : ContainerLevelAccess.NULL;
        this.pos = pos;
        this.station = station;
        this.data = data;

        addSlot(new SlotItemHandler(items, DeployingStationBlockEntity.DRONE_SLOT, DRONE_X, DRONE_Y));
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, column + row * 9 + 9, 8 + column * 18, INVENTORY_Y + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(inventory, column, 8 + column * 18, INVENTORY_Y + 58));
        }
        addDataSlots(data);
    }

    public BlockPos getPos() {
        return pos;
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

    public int getEnergyPerDeploy() {
        return value(DATA_ENERGY_PER_DEPLOY);
    }

    public boolean isAutoDeploy() {
        return value(DATA_AUTO_DEPLOY) != 0;
    }

    public DeployingStatus getStatus() {
        return DeployingStatus.BY_ID.apply(value(DATA_STATUS));
    }

    public boolean hasDrone() {
        return slots.get(DeployingStationBlockEntity.DRONE_SLOT).hasItem();
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (station == null) {
            return false;
        }
        switch (id) {
            case BUTTON_TOGGLE_AUTO_DEPLOY -> station.setAutoDeploy(!station.isAutoDeploy());
            case BUTTON_DEPLOY -> station.requestDeploy();
            default -> {
                return false;
            }
        }
        return true;
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
        } else if (!stack.is(ModItems.DRONE.get()) || !moveItemStackTo(stack, 0, PLAYER_SLOTS_START, false)) {
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
        return stillValid(access, player, ModBlocks.DEPLOYING_STATION.get());
    }
}
