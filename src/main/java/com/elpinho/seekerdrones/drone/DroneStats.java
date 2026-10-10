package com.elpinho.seekerdrones.drone;

import java.util.Map;

import com.elpinho.seekerdrones.config.ServerConfig;

/**
 * Values derived from {@link DroneData} and the server config.
 */
public final class DroneStats {
    /** Vanilla's upper bound for the max health attribute. */
    private static final float MAX_HEALTH_CEILING = 1024f;
    /** Speeds are stored in blocks/tick. Player-facing commands and screens show blocks/second. */
    public static final double TICKS_PER_SECOND = 20;

    private DroneStats() {}

    /** Section 5.1: {@code baseMaxEnergy × multiplier^count}, where {@code count} is the number of Energy upgrades. */
    public static int maxEnergy(DroneData data) {
        double max = ServerConfig.get(ServerConfig.DRONE_BASE_MAX_ENERGY)
                * Math.pow(ServerConfig.get(ServerConfig.UPGRADES_ENERGY_MULTIPLIER), data.upgradeCount(UpgradeType.ENERGY));
        return (int) Math.min(max, Integer.MAX_VALUE);
    }

    /**
     * The upgrade multiplier {@code M} on the drone's distance and hover costs (section 5.1): the product of each
     * installed upgrade's per-type factor. The first Patrol upgrade doesn't count, since {@code energyPerBlock} is
     * priced for it.
     */
    public static double energyUsageMultiplier(DroneData data) {
        double multiplier = 1;
        for (Map.Entry<UpgradeType, Integer> entry : data.upgrades().entrySet()) {
            int count = entry.getKey() == UpgradeType.PATROL ? entry.getValue() - 1 : entry.getValue();
            if (count > 0) {
                multiplier *= Math.pow(entry.getKey().energyFactor(), count);
            }
        }
        return multiplier;
    }

    /**
     * The energy at which a drone {@code distance} blocks from its nearest usable station starts returning (section
     * 5.2): {@code M × distance × (energyPerBlock + hoverEnergyPerTick / cruiseSpeed) × safetyMargin} for the flight
     * home, plus {@code returnWaitBuffer × hoverEnergyPerTick} for waiting at a busy station, which has no multiplier.
     */
    public static double returnThreshold(DroneData data, double distance) {
        int hover = ServerConfig.get(ServerConfig.DRONE_HOVER_ENERGY_PER_TICK);
        double perBlock = ServerConfig.get(ServerConfig.DRONE_ENERGY_PER_BLOCK) + hover / ServerConfig.get(ServerConfig.DRONE_CRUISE_SPEED);
        return energyUsageMultiplier(data) * distance * perBlock * ServerConfig.get(ServerConfig.DRONE_RETURN_SAFETY_MARGIN)
                + (double) ServerConfig.get(ServerConfig.DRONE_RETURN_WAIT_BUFFER) * hover;
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

    /**
     * Beyond this distance a chased target is lost, in blocks (section 3.5): {@code sightRange × pursuitMultiplier},
     * capped at {@code maxPursuitRange} but never below the sight range.
     */
    public static double pursuitRange(DroneData data) {
        double sight = sightRange(data);
        double pursuit = Math.min(sight * ServerConfig.get(ServerConfig.DRONE_PURSUIT_MULTIPLIER), ServerConfig.get(ServerConfig.DRONE_MAX_PURSUIT_RANGE));
        return Math.max(sight, pursuit);
    }

    /** How many target entries a drone has and uses (section 2.7). */
    public static int targetSlots() {
        return ServerConfig.get(ServerConfig.DRONE_TARGET_SLOTS);
    }

    public static boolean hasPlayerSeek(DroneData data) {
        return data.upgradeCount(UpgradeType.PLAYER_SEEK) > 0;
    }

    /** Upgrade slots used, across all types. */
    public static int totalUpgrades(Map<UpgradeType, Integer> upgrades) {
        return upgrades.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** How big the drone is drawn (section 3.7): {@code 1 + sizePerUpgrade × total upgrades}, capped. Never affects the hitbox. */
    public static float visualScale(DroneData data) {
        double scale = 1.0 + ServerConfig.get(ServerConfig.DRONE_SIZE_PER_UPGRADE) * totalUpgrades(data.upgrades());
        return (float) Math.min(scale, ServerConfig.get(ServerConfig.DRONE_MAX_VISUAL_SCALE));
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
        return maxPatrolRadius(data.upgradeCount(UpgradeType.PATROL));
    }

    /** The largest patrol radius for a Patrol upgrade count. */
    public static int maxPatrolRadius(int count) {
        long max = ServerConfig.get(ServerConfig.UPGRADES_PATROL_BASE_RADIUS)
                + (long) ServerConfig.get(ServerConfig.UPGRADES_PATROL_PER_UPGRADE_RADIUS) * Math.max(0, count - 1);
        return (int) Math.min(max, Integer.MAX_VALUE);
    }

    /** The radius the drone patrols at: its configured radius capped at the max, or the max if none is set. */
    public static int patrolRadius(DroneData data) {
        int max = maxPatrolRadius(data);
        return data.config().patrolRadius().map(radius -> Math.min(radius, max)).orElse(max);
    }

    /**
     * The fastest a drone can patrol in blocks/tick (section 3.2): {@code basePatrolSpeed + perUpgrade × (count − 1)},
     * with both config values in blocks/second, capped at the drone's max speed (section 3.4).
     */
    public static double maxPatrolSpeed(int count) {
        double perSecond = ServerConfig.get(ServerConfig.UPGRADES_PATROL_SPEED)
                + ServerConfig.get(ServerConfig.UPGRADES_PATROL_PER_UPGRADE_SPEED) * Math.max(0, count - 1);
        return Math.min(perSecond / TICKS_PER_SECOND, ServerConfig.get(ServerConfig.DRONE_MAX_SPEED));
    }

    /** The speed the drone patrols at: its configured speed capped at the max, or the max if none is set. */
    public static double patrolSpeed(DroneData data) {
        double max = maxPatrolSpeed(data.upgradeCount(UpgradeType.PATROL));
        return data.config().patrolSpeed().map(speed -> Math.min(speed, max)).orElse(max);
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
