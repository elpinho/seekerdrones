package com.elpinho.seekerdrones.commandcenter;

import javax.annotation.Nullable;

import com.elpinho.seekerdrones.radar.RemoteStationMenu;
import com.elpinho.seekerdrones.registry.ModBlocks;
import com.elpinho.seekerdrones.registry.ModMenuTypes;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.block.Block;

/**
 * Command Center menu (DESIGN.md section 7.10): the shared remote station menu. Orders are only taken from a player
 * who has it open, and it stays open while they use a drone's remote, FPV feed or piloting through the Command Center.
 */
public class CommandCenterMenu extends RemoteStationMenu {
    /** Client side, opened from the server's extra data. */
    public CommandCenterMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf extraData) {
        super(ModMenuTypes.COMMAND_CENTER.get(), containerId, extraData.readBlockPos(), null);
    }

    /** Server side. */
    CommandCenterMenu(int containerId, CommandCenterBlockEntity commandCenter) {
        super(ModMenuTypes.COMMAND_CENTER.get(), containerId, commandCenter.getBlockPos(), commandCenter);
    }

    /** The Command Center, on the server only. */
    @Nullable
    public CommandCenterBlockEntity getCommandCenter() {
        return (CommandCenterBlockEntity) getStation();
    }

    @Override
    protected Block block() {
        return ModBlocks.COMMAND_CENTER.get();
    }
}
