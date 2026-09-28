package com.elpinho.seekerdrones.factory;

import javax.annotation.Nullable;

import com.elpinho.seekerdrones.network.FactoryOperatorsPayload;
import com.elpinho.seekerdrones.registry.ModBlocks;
import com.elpinho.seekerdrones.registry.ModMenuTypes;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.SlotItemHandler;

/**
 * Drone Factory menu (DESIGN.md section 7.1): six input slots, the output slot and the player inventory, plus progress,
 * FE and tank values. The Operator list (owner only) is synced separately with {@link FactoryOperatorsPayload}.
 */
public class DroneFactoryMenu extends AbstractContainerMenu {
    // Container data indices, each an int split into two shorts (see DroneFactoryBlockEntity.FactoryData).
    static final int DATA_PROGRESS = 0;
    static final int DATA_TIME = 1;
    static final int DATA_ENERGY = 2;
    static final int DATA_ENERGY_CAPACITY = 3;
    static final int DATA_FLUID_AMOUNT = 4;
    static final int DATA_TANK_CAPACITY = 5;
    static final int DATA_FLUID_ID = 6;
    static final int DATA_VALUES = 7;

    public static final int INPUT_X = 48;
    public static final int INPUT_Y = 22;
    public static final int OUTPUT_X = 134;
    public static final int OUTPUT_Y = 31;
    public static final int INVENTORY_Y = 84;

    private static final int PLAYER_SLOTS_START = DroneFactoryBlockEntity.SLOT_COUNT;
    private static final int PLAYER_SLOTS_END = PLAYER_SLOTS_START + 36;

    private final ContainerLevelAccess access;
    private final BlockPos pos;
    @Nullable
    private final DroneFactoryBlockEntity factory;
    private final ContainerData data;
    /** Client only: the latest Operator list from the server, or null before the first one arrives. */
    @Nullable
    private FactoryOperatorsPayload operators;

    /** Client side, opened from the server's extra data. */
    public DroneFactoryMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf extraData) {
        this(containerId, inventory, extraData.readBlockPos(), null, new ItemStackHandler(DroneFactoryBlockEntity.SLOT_COUNT),
                new SimpleContainerData(DATA_VALUES * 2));
    }

    /** Server side. */
    public DroneFactoryMenu(int containerId, Inventory inventory, DroneFactoryBlockEntity factory, ContainerData data) {
        this(containerId, inventory, factory.getBlockPos(), factory, factory.getItems(), data);
    }

    private DroneFactoryMenu(int containerId, Inventory inventory, BlockPos pos, @Nullable DroneFactoryBlockEntity factory, IItemHandler items,
            ContainerData data) {
        super(ModMenuTypes.DRONE_FACTORY.get(), containerId);
        this.access = factory != null && factory.getLevel() != null ? ContainerLevelAccess.create(factory.getLevel(), pos) : ContainerLevelAccess.NULL;
        this.pos = pos;
        this.factory = factory;
        this.data = data;

        for (int row = 0; row < 2; row++) {
            for (int column = 0; column < 3; column++) {
                addSlot(new SlotItemHandler(items, row * 3 + column, INPUT_X + column * 18, INPUT_Y + row * 18));
            }
        }
        addSlot(new SlotItemHandler(items, DroneFactoryBlockEntity.OUTPUT_SLOT, OUTPUT_X, OUTPUT_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return false;
            }
        });
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

    /** The server-side block entity, null on the client. */
    @Nullable
    public DroneFactoryBlockEntity getFactory() {
        return factory;
    }

    @Nullable
    public FactoryOperatorsPayload getOperators() {
        return operators;
    }

    public void setOperators(FactoryOperatorsPayload operators) {
        this.operators = operators;
    }

    private int value(int index) {
        // Each half arrives as a sign-extended short.
        return (data.get(index * 2 + 1) & 0xFFFF) << 16 | data.get(index * 2) & 0xFFFF;
    }

    public int getProgress() {
        return value(DATA_PROGRESS);
    }

    public int getTime() {
        return value(DATA_TIME);
    }

    public int getEnergy() {
        return value(DATA_ENERGY);
    }

    public int getEnergyCapacity() {
        return value(DATA_ENERGY_CAPACITY);
    }

    public int getFluidAmount() {
        return value(DATA_FLUID_AMOUNT);
    }

    public int getTankCapacity() {
        return value(DATA_TANK_CAPACITY);
    }

    public Fluid getFluid() {
        int id = value(DATA_FLUID_ID);
        return id < 0 ? Fluids.EMPTY : BuiltInRegistries.FLUID.byId(id);
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
        } else if (!moveItemStackTo(stack, 0, DroneFactoryBlockEntity.INPUT_SLOTS, false)) {
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
        return stillValid(access, player, ModBlocks.DRONE_FACTORY.get());
    }
}
