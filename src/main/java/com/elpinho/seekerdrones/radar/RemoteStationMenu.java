package com.elpinho.seekerdrones.radar;

import javax.annotation.Nullable;

import com.elpinho.seekerdrones.feed.FpvFeeds;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;

/**
 * The menu of a radar machine a remote can be opened through (DESIGN.md sections 7.9 and 7.10). It stays open while
 * the player uses a drone's remote, FPV feed or piloting through the station, and closing it ends all of them.
 */
public abstract class RemoteStationMenu extends RadarMachineMenu {
    protected RemoteStationMenu(@Nullable MenuType<?> type, int containerId, BlockPos pos, @Nullable RemoteStationBlockEntity<?> station) {
        super(type, containerId, pos, station);
    }

    /** The station, on the server only. */
    @Nullable
    public RemoteStationBlockEntity<?> getStation() {
        return (RemoteStationBlockEntity<?>) getMachine();
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (getStation() != null && player instanceof ServerPlayer serverPlayer) {
            FpvFeeds.stopThroughStation(serverPlayer, getStation());
        }
    }
}
