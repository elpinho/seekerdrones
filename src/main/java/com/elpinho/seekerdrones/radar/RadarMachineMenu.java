package com.elpinho.seekerdrones.radar;

import javax.annotation.Nullable;

import com.elpinho.seekerdrones.machine.IntMenuData;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

/**
 * The radar machines' shared menu (DESIGN.md sections 7.8 and 7.9): no slots, only the FE and power values. The map
 * and the contacts come in their own payloads while the menu is open, so the server knows who is watching from the
 * open menus.
 */
public abstract class RadarMachineMenu extends AbstractContainerMenu {
    static final int DATA_ENERGY = 0;
    static final int DATA_ENERGY_CAPACITY = 1;
    static final int DATA_ENERGY_PER_TICK = 2;
    static final int DATA_POWERED = 3;
    static final int DATA_VALUES = 4;

    private final ContainerLevelAccess access;
    private final BlockPos pos;
    @Nullable
    private final RadarMachineBlockEntity<?> machine;
    private final ContainerData data;

    /**
     * @param machine the block entity on the server, null on the client
     */
    protected RadarMachineMenu(@Nullable MenuType<?> type, int containerId, BlockPos pos, @Nullable RadarMachineBlockEntity<?> machine) {
        super(type, containerId);
        this.access = machine != null && machine.getLevel() != null ? ContainerLevelAccess.create(machine.getLevel(), pos) : ContainerLevelAccess.NULL;
        this.pos = pos;
        this.machine = machine;
        this.data = machine != null ? machine.data : IntMenuData.client(DATA_VALUES);
        addDataSlots(data);
    }

    /** The machine's block, for the reach check. */
    protected abstract Block block();

    public BlockPos getPos() {
        return pos;
    }

    /** The block entity, on the server only. */
    @Nullable
    public RadarMachineBlockEntity<?> getMachine() {
        return machine;
    }

    public int getEnergy() {
        return IntMenuData.read(data, DATA_ENERGY);
    }

    public int getEnergyCapacity() {
        return IntMenuData.read(data, DATA_ENERGY_CAPACITY);
    }

    public int getEnergyPerTick() {
        return IntMenuData.read(data, DATA_ENERGY_PER_TICK);
    }

    public boolean isPowered() {
        return IntMenuData.read(data, DATA_POWERED) != 0;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (machine != null && player instanceof ServerPlayer serverPlayer) {
            machine.removeViewer(serverPlayer);
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        // Opened from either half, so the player may stand within reach of the upper one.
        return access.evaluate((level, lower) -> level.getBlockState(lower).is(block())
                && (player.canInteractWithBlock(lower, 4.0) || player.canInteractWithBlock(lower.above(), 4.0)), true);
    }
}
