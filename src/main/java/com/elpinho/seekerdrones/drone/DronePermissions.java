package com.elpinho.seekerdrones.drone;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.elpinho.seekerdrones.operator.OperatorGroups;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * Who counts as a drone's operator (DESIGN.md section 6). A drone with a group uses the group's current membership and
 * ignores its owner. A drone with no group but an owner has the owner as its only operator. A drone with neither is
 * unowned. Machines never check any of this.
 */
public final class DronePermissions {
    /** Permission level at which server operators bypass the operator check for direct interaction. */
    private static final int BYPASS_PERMISSION_LEVEL = 2;

    private DronePermissions() {}

    /**
     * Whether the player may pick up, hand-deploy or open the GUI of this drone. Unowned drones are usable by anyone,
     * server operators always pass, and a drone whose group doesn't exist is usable by nobody else.
     * Only meaningful on the server: the client can't know group membership and always gets true.
     */
    public static boolean canInteract(Player player, DroneData data) {
        if (data.groupId().isEmpty() && data.ownerId().isEmpty()) {
            return true;
        }
        MinecraftServer server = player.getServer();
        if (server == null || player.hasPermissions(BYPASS_PERMISSION_LEVEL)) {
            return true;
        }
        return isOperator(server, data, player.getUUID());
    }

    /**
     * Operator status with no bypass, for Player Seek exemption and Transmitter recipients (section 6.2). False for
     * unowned drones and for drones with an unknown group.
     */
    public static boolean isOperator(MinecraftServer server, DroneData data, UUID player) {
        if (data.groupId().isPresent()) {
            return OperatorGroups.get(server).isOperator(data.groupId().get(), player);
        }
        return data.ownerId().map(player::equals).orElse(false);
    }

    /**
     * Whether the drone may charge at a station placed by {@code stationOwner} (section 5.2): the placer must be an
     * operator of the drone. An unowned drone may use any station, and a station with no placer serves only unowned
     * drones.
     */
    public static boolean canUseStation(MinecraftServer server, DroneData data, Optional<UUID> stationOwner) {
        if (data.groupId().isEmpty() && data.ownerId().isEmpty()) {
            return true;
        }
        return stationOwner.isPresent() && isOperator(server, data, stationOwner.get());
    }

    /** All online operators of the drone, e.g. for Transmitter notifications (section 4). */
    public static List<ServerPlayer> onlineOperators(MinecraftServer server, DroneData data) {
        if (data.groupId().isPresent()) {
            return OperatorGroups.get(server).onlineOperators(server, data.groupId().get());
        }
        ServerPlayer owner = data.ownerId().map(server.getPlayerList()::getPlayer).orElse(null);
        return owner != null ? List.of(owner) : List.of();
    }

    /** Tells a blocked player why, on the action bar. */
    public static void sendDenied(Player player) {
        player.displayClientMessage(Component.translatable("message.seekerdrones.not_operator"), true);
    }
}
