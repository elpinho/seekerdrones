package com.elpinho.seekerdrones.drone;

import net.minecraft.world.entity.player.Player;

/**
 * Permission checks for direct player interaction with drones (DESIGN.md section 6.2). Machines never call this.
 */
public final class DronePermissions {
    private DronePermissions() {}

    /** Whether the player may pick up, hand-deploy or open the GUI of this drone. Operator Groups arrive in M2. */
    public static boolean canInteract(Player player, DroneData data) {
        return true;
    }
}
