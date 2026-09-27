package com.elpinho.seekerdrones.drone;

import java.util.Map;

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

    /** Upgrade slots used, across all types. */
    public static int totalUpgrades(Map<UpgradeType, Integer> upgrades) {
        return upgrades.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** Whether the upgrade counts fit the per-type caps and the total slot limit (section 4). */
    public static boolean withinUpgradeLimits(Map<UpgradeType, Integer> upgrades) {
        for (Map.Entry<UpgradeType, Integer> entry : upgrades.entrySet()) {
            if (entry.getValue() > entry.getKey().maxCount()) {
                return false;
            }
        }
        return totalUpgrades(upgrades) <= ServerConfig.get(ServerConfig.UPGRADES_TOTAL_SLOTS);
    }

    public static boolean isPatrolling(DroneData data) {
        return data.upgradeCount(UpgradeType.PATROL) > 0;
    }

    /** The largest patrol radius the Patrol upgrades allow (section 3.2): {@code base + perUpgrade × (count − 1)}. */
    public static int maxPatrolRadius(DroneData data) {
        int count = data.upgradeCount(UpgradeType.PATROL);
        long max = ServerConfig.get(ServerConfig.UPGRADES_PATROL_BASE_RADIUS)
                + (long) ServerConfig.get(ServerConfig.UPGRADES_PATROL_PER_UPGRADE_RADIUS) * Math.max(0, count - 1);
        return (int) Math.min(max, Integer.MAX_VALUE);
    }

    /** The radius the drone patrols at: its configured radius capped at the max, or the max if none is set. */
    public static int patrolRadius(DroneData data) {
        int max = maxPatrolRadius(data);
        return data.config().patrolRadius().map(radius -> Math.min(radius, max)).orElse(max);
    }

    public static boolean isExplosive(DroneData data) {
        return data.upgradeCount(UpgradeType.EXPLOSIVE) > 0;
    }

    /** Section 3.1: {@code basePower + perUpgrade × (count − 1)}. */
    public static float explosionPower(DroneData data) {
        int count = data.upgradeCount(UpgradeType.EXPLOSIVE);
        return (float) (ServerConfig.get(ServerConfig.UPGRADES_EXPLOSIVE_BASE_POWER)
                + ServerConfig.get(ServerConfig.UPGRADES_EXPLOSIVE_PER_UPGRADE) * Math.max(0, count - 1));
    }

    /** Section 4: {@code baseVolume + perUpgrade × (count − 1)}, or 0 without a Siren upgrade. */
    public static float sirenVolume(DroneData data) {
        int count = data.upgradeCount(UpgradeType.SIREN);
        if (count <= 0) {
            return 0;
        }
        return (float) (ServerConfig.get(ServerConfig.UPGRADES_SIREN_BASE_VOLUME)
                + ServerConfig.get(ServerConfig.UPGRADES_SIREN_PER_UPGRADE) * (count - 1));
    }

    public static boolean hasTransmitter(DroneData data) {
        return data.upgradeCount(UpgradeType.TRANSMITTER) > 0;
    }

    /**
     * Top speed in blocks/tick of a non-Explosive drone chasing or following (section 3.4): the cruise speed plus the
     * target's own speed, so it keeps up with fast targets, capped at maxSpeed.
     */
    public static double followSpeed(double targetSpeed) {
        return Math.min(ServerConfig.get(ServerConfig.DRONE_MAX_SPEED), ServerConfig.get(ServerConfig.DRONE_CRUISE_SPEED) + targetSpeed);
    }

    /**
     * Explosive chase speed in blocks/tick at the given distance to the target (section 3.4). The distance is clamped
     * to the sight range, so the speed stays within [cruiseSpeed, maxSpeed].
     */
    public static double chaseSpeed(double distance, double sightRange) {
        double cruise = ServerConfig.get(ServerConfig.DRONE_CRUISE_SPEED);
        double max = ServerConfig.get(ServerConfig.DRONE_MAX_SPEED);
        double k = ServerConfig.get(ServerConfig.DRONE_CHASE_ACCELERATION_K);
        double d = Math.min(distance, sightRange);
        return Math.min(max, cruise * Math.exp(k * (1 - d / sightRange)));
    }
}
