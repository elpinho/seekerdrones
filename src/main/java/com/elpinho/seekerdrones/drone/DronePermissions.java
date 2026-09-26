package com.elpinho.seekerdrones.drone;

import java.util.UUID;

import com.elpinho.seekerdrones.operator.OperatorGroups;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;

/**
 * Permission checks for direct player interaction with drones (DESIGN.md section 6.2). Machines never call this.
 */
public final class DronePermissions {
    /** Permission level at which server operators bypass the group check for direct interaction. */
    private static final int BYPASS_PERMISSION_LEVEL = 2;

    private DronePermissions() {}

    /**
     * Whether the player may pick up, hand-deploy or open the GUI of this drone. Drones without a group are usable by
     * anyone, server operators always pass, and a drone whose group doesn't exist is usable by nobody else.
     * Only meaningful on the server: the client can't know group membership and always gets true.
     */
    public static boolean canInteract(Player player, DroneData data) {
        if (data.groupId().isEmpty()) {
            return true;
        }
        MinecraftServer server = player.getServer();
        if (server == null || player.hasPermissions(BYPASS_PERMISSION_LEVEL)) {
            return true;
        }
        return OperatorGroups.get(server).isOperator(data.groupId().get(), player.getUUID());
    }

    /**
     * Pure group membership with no bypass, for Player Seek exemption and Transmitter recipients (section 6.2).
     * False for drones without a group or with an unknown group.
     */
    public static boolean isGroupOperator(MinecraftServer server, DroneData data, UUID player) {
        return data.groupId().isPresent() && OperatorGroups.get(server).isOperator(data.groupId().get(), player);
    }

    /** Tells a blocked player why, on the action bar. */
    public static void sendDenied(Player player) {
        player.displayClientMessage(Component.translatable("message.seekerdrones.not_operator"), true);
    }
}
