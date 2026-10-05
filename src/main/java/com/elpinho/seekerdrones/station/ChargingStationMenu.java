package com.elpinho.seekerdrones.station;

import java.util.List;

import javax.annotation.Nullable;

import com.elpinho.seekerdrones.machine.UpgradeSlots;
import com.elpinho.seekerdrones.network.StationStatusPayload;
import com.elpinho.seekerdrones.registry.ModBlocks;
import com.elpinho.seekerdrones.registry.ModMenuTypes;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Charging Station menu (DESIGN.md section 7.4). The station's values come from {@link StationStatusPayload} (the
 * opening data, then a refresh about once a second). Only a player who may manage the upgrades gets slots: the Upgrades
 * tab's slots and the player inventory. Everyone else gets an empty menu and only looks.
 */
public class ChargingStationMenu extends AbstractContainerMenu {
    public static final int WIDTH = 176;
    /** The station panel, the same for everyone. */
    public static final int PANEL_HEIGHT = 108;
    /** The separate inventory frame under the panel, for managers. */
    public static final int INVENTORY_FRAME_Y = PANEL_HEIGHT + 2;
    public static final int INVENTORY_FRAME_HEIGHT = 100;
    public static final int INVENTORY_Y = INVENTORY_FRAME_Y + 18;

    private final ContainerLevelAccess access;
    private final BlockPos pos;
    @Nullable
    private final ChargingStationBlockEntity station;
    /** Client side: the status the menu opened with. */
    @Nullable
    private final StationStatusPayload openingStatus;
    private final List<Slot> upgradeSlots;
    private final int playerSlotsStart;
    /** Client side: whether the player may still manage the upgrades, from the latest refresh. */
    private boolean canManage;
    /** Client side: whether the Upgrades tab is unfolded. The server always treats the slots as active. */
    private boolean upgradesShown = true;

    /** Client side, opened from the server's status snapshot. */
    public ChargingStationMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf extraData) {
        this(containerId, inventory, StationStatusPayload.STREAM_CODEC.decode(extraData));
    }

    private ChargingStationMenu(int containerId, Inventory inventory, StationStatusPayload status) {
        this(containerId, inventory, status.pos(), null, status, UpgradeSlots.createHandler(ChargingStationBlockEntity.UPGRADES, () -> {}),
                status.canManage());
    }

    /** Server side. */
    public ChargingStationMenu(int containerId, Inventory inventory, ChargingStationBlockEntity station, boolean canManage) {
        this(containerId, inventory, station.getBlockPos(), station, null, station.getUpgrades(), canManage);
    }

    private ChargingStationMenu(int containerId, Inventory inventory, BlockPos pos, @Nullable ChargingStationBlockEntity station,
            @Nullable StationStatusPayload openingStatus, IItemHandler upgrades, boolean canManage) {
        super(ModMenuTypes.CHARGING_STATION.get(), containerId);
        this.access = station != null && station.getLevel() != null ? ContainerLevelAccess.create(station.getLevel(), pos) : ContainerLevelAccess.NULL;
        this.pos = pos;
        this.station = station;
        this.openingStatus = openingStatus;
        this.canManage = canManage;

        if (!canManage) {
            this.upgradeSlots = List.of();
            this.playerSlotsStart = 0;
            return;
        }
        this.upgradeSlots = UpgradeSlots.createSlots(upgrades, ChargingStationBlockEntity.UPGRADES, WIDTH, () -> upgradesShown);
        upgradeSlots.forEach(this::addSlot);
        this.playerSlotsStart = slots.size();
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, column + row * 9 + 9, 8 + column * 18, INVENTORY_Y + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(inventory, column, 8 + column * 18, INVENTORY_Y + 58));
        }
    }

    public BlockPos getPos() {
        return pos;
    }

    /** Client side: the status the screen starts with. */
    public StationStatusPayload getOpeningStatus() {
        if (openingStatus == null) {
            throw new IllegalStateException("Only the client menu has an opening status");
        }
        return openingStatus;
    }

    /** Whether this menu has the Upgrades tab's slots and the player inventory. Fixed when the menu opens. */
    public boolean hasInventory() {
        return !upgradeSlots.isEmpty();
    }

    public List<Slot> getUpgradeSlots() {
        return upgradeSlots;
    }

    /** Client side: whether the player may manage the upgrades now. Only a menu that has the slots can be managed. */
    public boolean canManage() {
        return canManage && hasInventory();
    }

    public void setCanManage(boolean canManage) {
        this.canManage = canManage;
    }

    public void setUpgradesShown(boolean shown) {
        this.upgradesShown = shown;
    }

    private boolean mayManage(Player player) {
        if (!hasInventory()) {
            return false;
        }
        return station != null ? ChargingStationAccess.canManage(player, station) : canManage;
    }

    /** A player who lost access since the screen opened can't change anything (section 7.4). */
    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (mayManage(player)) {
            super.clicked(slotId, button, clickType, player);
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
        if (index < playerSlotsStart) {
            if (!moveItemStackTo(stack, playerSlotsStart, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else if (!moveItemStackTo(stack, 0, playerSlotsStart, false)) {
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
        return stillValid(access, player, ModBlocks.CHARGING_STATION.get());
    }
}
