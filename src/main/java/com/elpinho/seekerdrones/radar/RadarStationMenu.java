package com.elpinho.seekerdrones.radar;

import javax.annotation.Nullable;

import com.elpinho.seekerdrones.machine.IntMenuData;
import com.elpinho.seekerdrones.registry.ModBlocks;
import com.elpinho.seekerdrones.registry.ModMenuTypes;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;

/**
 * Radar Station menu (DESIGN.md section 7.8): no slots, only the FE and power values. The map and the drones come in
 * their own payloads while the menu is open, so the server knows who is watching from the open menus.
 */
public class RadarStationMenu extends AbstractContainerMenu {
    static final int DATA_ENERGY = 0;
    static final int DATA_ENERGY_CAPACITY = 1;
    static final int DATA_ENERGY_PER_TICK = 2;
    static final int DATA_POWERED = 3;
    static final int DATA_VALUES = 4;

    private final ContainerLevelAccess access;
    private final BlockPos pos;
    @Nullable
    private final RadarStationBlockEntity station;
    private final ContainerData data;

    /** Client side, opened from the server's extra data. */
    public RadarStationMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf extraData) {
        this(containerId, extraData.readBlockPos(), null, IntMenuData.client(DATA_VALUES));
    }

    /** Server side. */
    RadarStationMenu(int containerId, RadarStationBlockEntity station, ContainerData data) {
        this(containerId, station.getBlockPos(), station, data);
    }

    private RadarStationMenu(int containerId, BlockPos pos, @Nullable RadarStationBlockEntity station, ContainerData data) {
        super(ModMenuTypes.RADAR_STATION.get(), containerId);
        this.access = station != null && station.getLevel() != null ? ContainerLevelAccess.create(station.getLevel(), pos) : ContainerLevelAccess.NULL;
        this.pos = pos;
        this.station = station;
        this.data = data;
        addDataSlots(data);
    }

    public BlockPos getPos() {
        return pos;
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
        if (station != null && player instanceof ServerPlayer serverPlayer) {
            station.removeViewer(serverPlayer);
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        // Opened from either half, so the player may stand within reach of the upper one.
        return access.evaluate((level, lower) -> level.getBlockState(lower).is(ModBlocks.RADAR_STATION.get())
                && (player.canInteractWithBlock(lower, 4.0) || player.canInteractWithBlock(lower.above(), 4.0)), true);
    }
}
