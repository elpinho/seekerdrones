package com.elpinho.seekerdrones.uplink;

import com.elpinho.seekerdrones.radar.RemoteStationMenu;
import com.elpinho.seekerdrones.registry.ModBlocks;
import com.elpinho.seekerdrones.registry.ModMenuTypes;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.block.Block;

/**
 * Satellite Uplink menu (DESIGN.md section 7.9): the shared remote station menu. It stays open while the player uses a
 * drone's remote, FPV feed or piloting through the Uplink, and closing it ends all of them.
 */
public class UplinkMenu extends RemoteStationMenu {
    /** Client side, opened from the server's extra data. */
    public UplinkMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf extraData) {
        super(ModMenuTypes.UPLINK.get(), containerId, extraData.readBlockPos(), null);
    }

    /** Server side. */
    UplinkMenu(int containerId, UplinkBlockEntity uplink) {
        super(ModMenuTypes.UPLINK.get(), containerId, uplink.getBlockPos(), uplink);
    }

    @Override
    protected Block block() {
        return ModBlocks.UPLINK.get();
    }
}
