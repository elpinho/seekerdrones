package com.elpinho.seekerdrones.radar;

import com.elpinho.seekerdrones.registry.ModBlocks;
import com.elpinho.seekerdrones.registry.ModMenuTypes;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.block.Block;

/** Radar Station menu (DESIGN.md section 7.8): the shared radar machine menu. */
public class RadarStationMenu extends RadarMachineMenu {
    /** Client side, opened from the server's extra data. */
    public RadarStationMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf extraData) {
        super(ModMenuTypes.RADAR_STATION.get(), containerId, extraData.readBlockPos(), null);
    }

    /** Server side. */
    RadarStationMenu(int containerId, RadarStationBlockEntity station) {
        super(ModMenuTypes.RADAR_STATION.get(), containerId, station.getBlockPos(), station);
    }

    @Override
    protected Block block() {
        return ModBlocks.RADAR_STATION.get();
    }
}
