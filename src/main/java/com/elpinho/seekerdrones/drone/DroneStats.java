package com.elpinho.seekerdrones.drone;

import com.elpinho.seekerdrones.config.ServerConfig;

/**
 * Values derived from {@link DroneData} and the server config.
 */
public final class DroneStats {
    /** Vanilla's upper bound for the max health attribute. */
    private static final float MAX_HEALTH_CEILING = 1024f;

    private DroneStats() {}

    public static int maxEnergy(DroneData data) {
        long max = (long) ServerConfig.get(ServerConfig.DRONE_BASE_MAX_ENERGY)
                + (long) data.upgradeCount(UpgradeType.ENERGY) * ServerConfig.get(ServerConfig.UPGRADES_ENERGY_PER_UPGRADE);
        return (int) Math.min(max, Integer.MAX_VALUE);
    }

    public static float maxHealth(DroneData data) {
        double max = ServerConfig.get(ServerConfig.DRONE_BASE_MAX_HEALTH)
                + data.upgradeCount(UpgradeType.HEALTH) * ServerConfig.get(ServerConfig.UPGRADES_HEALTH_PER_UPGRADE);
        return (float) Math.min(max, MAX_HEALTH_CEILING);
    }
}
