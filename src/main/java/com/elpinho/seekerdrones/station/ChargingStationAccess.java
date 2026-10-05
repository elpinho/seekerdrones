package com.elpinho.seekerdrones.station;

import java.util.Optional;
import java.util.UUID;

import com.elpinho.seekerdrones.drone.DronePermissions;
import com.elpinho.seekerdrones.operator.OperatorGroups;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;

/**
 * Who may manage a Charging Station's upgrades (DESIGN.md section 7.4): its placer, every operator of every Operator
 * Group the placer owns, and server operators. Anyone may manage a station with no recorded placer. Everything else on
 * the station is open to anyone, and machines never check this.
 */
public final class ChargingStationAccess {
    private ChargingStationAccess() {}

    /** Only meaningful on the server: the client can't know group membership and always gets true. */
    public static boolean canManage(Player player, ChargingStationBlockEntity station) {
        Optional<UUID> owner = station.getOwner();
        MinecraftServer server = player.getServer();
        if (owner.isEmpty() || server == null || player.hasPermissions(DronePermissions.BYPASS_PERMISSION_LEVEL)) {
            return true;
        }
        UUID id = player.getUUID();
        return owner.get().equals(id) || OperatorGroups.get(server).isOperatorOfAnyGroupOwnedBy(owner.get(), id);
    }
}
