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

    /** How far away the drone can first spot a target, in blocks (section 3.3). */
    public static double sightRange(DroneData data) {
        return ServerConfig.get(ServerConfig.DRONE_BASE_SIGHT_RANGE)
                + (double) data.upgradeCount(UpgradeType.SIGHT) * ServerConfig.get(ServerConfig.UPGRADES_SIGHT_PER_UPGRADE);
    }

    /** Beyond this distance a chased target is lost, in blocks (section 3.5). */
    public static double pursuitRange(DroneData data) {
        return sightRange(data) * ServerConfig.get(ServerConfig.DRONE_PURSUIT_MULTIPLIER);
    }

    /** How many target entries the drone actually uses (section 2.7 runtime fail-safe). */
    public static int allowedTargetCount(DroneData data) {
        long count = 1L + (long) data.upgradeCount(UpgradeType.MULTI_TARGET) * ServerConfig.get(ServerConfig.UPGRADES_MULTI_TARGET_PER_UPGRADE);
        return (int) Math.min(count, Integer.MAX_VALUE);
    }

    public static boolean hasPlayerSeek(DroneData data) {
        return data.upgradeCount(UpgradeType.PLAYER_SEEK) > 0;
    }

    public static boolean hasXray(DroneData data) {
        return data.upgradeCount(UpgradeType.XRAY) > 0;
    }

    /**
     * Chase speed in blocks/tick at the given distance to the target (section 3.4). The distance is clamped to the
     * sight range, so the speed stays within [cruiseSpeed, maxSpeed].
     */
    public static double chaseSpeed(double distance, double sightRange) {
        double cruise = ServerConfig.get(ServerConfig.DRONE_CRUISE_SPEED);
        double max = ServerConfig.get(ServerConfig.DRONE_MAX_SPEED);
        double k = ServerConfig.get(ServerConfig.DRONE_CHASE_ACCELERATION_K);
        double d = Math.min(distance, sightRange);
        return Math.min(max, cruise * Math.exp(k * (1 - d / sightRange)));
    }
}
