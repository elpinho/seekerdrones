package com.elpinho.seekerdrones.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * All values are placeholder defaults from DESIGN.md section 9, to be tuned in the M8 balance pass.
 */
public class ServerConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // === drone ===
    public static final ModConfigSpec.IntValue DRONE_BASE_MAX_ENERGY;
    public static final ModConfigSpec.IntValue DRONE_ENERGY_PER_BLOCK;
    public static final ModConfigSpec.IntValue DRONE_HOVER_ENERGY_PER_TICK;
    public static final ModConfigSpec.IntValue DRONE_ENERGY_DRAIN_INTERVAL;
    public static final ModConfigSpec.DoubleValue DRONE_RETURN_SAFETY_MARGIN;
    public static final ModConfigSpec.IntValue DRONE_RETURN_WAIT_BUFFER;
    public static final ModConfigSpec.IntValue DRONE_CHARGING_SEARCH_RADIUS;
    public static final ModConfigSpec.IntValue DRONE_CHARGING_ALTERNATE_RADIUS;
    public static final ModConfigSpec.IntValue DRONE_UNREACHABLE_STATION_COOLDOWN;
    public static final ModConfigSpec.DoubleValue DRONE_BASE_MAX_HEALTH;
    public static final ModConfigSpec.IntValue DRONE_BASE_SIGHT_RANGE;
    public static final ModConfigSpec.DoubleValue DRONE_PURSUIT_MULTIPLIER;
    public static final ModConfigSpec.IntValue DRONE_LOST_SIGHT_TIMEOUT;
    public static final ModConfigSpec.IntValue DRONE_SCAN_INTERVAL;
    public static final ModConfigSpec.IntValue DRONE_MAX_RAYCASTS_PER_SCAN;
    public static final ModConfigSpec.DoubleValue DRONE_CRUISE_SPEED;
    public static final ModConfigSpec.DoubleValue DRONE_MAX_SPEED;
    public static final ModConfigSpec.DoubleValue DRONE_CHASE_ACCELERATION_K;
    public static final ModConfigSpec.DoubleValue DRONE_ACCELERATION;
    public static final ModConfigSpec.DoubleValue DRONE_EXPLOSIVE_ACCELERATION;
    public static final ModConfigSpec.DoubleValue DRONE_TURN_SPEED;
    public static final ModConfigSpec.DoubleValue DRONE_FACING_TOLERANCE;
    public static final ModConfigSpec.IntValue DRONE_DEFAULT_FOLLOW_DISTANCE;
    public static final ModConfigSpec.DoubleValue DRONE_FOLLOW_HEIGHT_OFFSET;
    public static final ModConfigSpec.DoubleValue DRONE_FOLLOW_ENTER_DISTANCE;
    public static final ModConfigSpec.DoubleValue DRONE_FOLLOW_EXIT_DISTANCE;
    public static final ModConfigSpec.DoubleValue DRONE_FOLLOW_SLACK;
    public static final ModConfigSpec.DoubleValue DRONE_FOLLOW_SMOOTHING;
    public static final ModConfigSpec.DoubleValue DRONE_REPATH_DISTANCE;
    public static final ModConfigSpec.DoubleValue DRONE_EXPLOSION_TRIGGER_DISTANCE;
    public static final ModConfigSpec.DoubleValue DRONE_DEPLOY_SPAWN_DISTANCE;
    public static final ModConfigSpec.DoubleValue DRONE_DEPLOY_THROW_SPEED;
    public static final ModConfigSpec.DoubleValue DRONE_DEPLOY_DRAG;
    public static final ModConfigSpec.DoubleValue DRONE_DEPLOY_REST_SPEED;
    public static final ModConfigSpec.DoubleValue DRONE_WATER_DAMAGE;
    public static final ModConfigSpec.IntValue DRONE_WATER_DAMAGE_INTERVAL;

    // === upgrades ===
    public static final ModConfigSpec.IntValue UPGRADES_TOTAL_SLOTS;

    public static final ModConfigSpec.IntValue UPGRADES_PATROL_MAX_COUNT;
    public static final ModConfigSpec.IntValue UPGRADES_PATROL_BASE_RADIUS;
    public static final ModConfigSpec.IntValue UPGRADES_PATROL_PER_UPGRADE_RADIUS;
    public static final ModConfigSpec.DoubleValue UPGRADES_PATROL_SPEED;
    public static final ModConfigSpec.DoubleValue UPGRADES_PATROL_WAYPOINT_SPACING;
    public static final ModConfigSpec.DoubleValue UPGRADES_PATROL_MAX_CLIMB;
    public static final ModConfigSpec.DoubleValue UPGRADES_PATROL_CLIMB_CLEARANCE;

    public static final ModConfigSpec.IntValue UPGRADES_SIGHT_MAX_COUNT;
    public static final ModConfigSpec.IntValue UPGRADES_SIGHT_PER_UPGRADE;

    public static final ModConfigSpec.IntValue UPGRADES_EXPLOSIVE_MAX_COUNT;
    public static final ModConfigSpec.DoubleValue UPGRADES_EXPLOSIVE_BASE_POWER;
    public static final ModConfigSpec.DoubleValue UPGRADES_EXPLOSIVE_PER_UPGRADE;

    public static final ModConfigSpec.IntValue UPGRADES_SIREN_MAX_COUNT;
    public static final ModConfigSpec.DoubleValue UPGRADES_SIREN_BASE_VOLUME;
    public static final ModConfigSpec.DoubleValue UPGRADES_SIREN_PER_UPGRADE;
    public static final ModConfigSpec.IntValue UPGRADES_SIREN_REPEAT_INTERVAL;

    public static final ModConfigSpec.IntValue UPGRADES_TRANSMITTER_MAX_COUNT;
    public static final ModConfigSpec.IntValue UPGRADES_TRANSMITTER_COOLDOWN;

    public static final ModConfigSpec.IntValue UPGRADES_ENERGY_MAX_COUNT;
    public static final ModConfigSpec.IntValue UPGRADES_ENERGY_PER_UPGRADE;

    public static final ModConfigSpec.IntValue UPGRADES_HEALTH_MAX_COUNT;
    public static final ModConfigSpec.DoubleValue UPGRADES_HEALTH_PER_UPGRADE;

    public static final ModConfigSpec.IntValue UPGRADES_PLAYER_SEEK_MAX_COUNT;

    public static final ModConfigSpec.IntValue UPGRADES_MULTI_TARGET_MAX_COUNT;
    public static final ModConfigSpec.IntValue UPGRADES_MULTI_TARGET_PER_UPGRADE;

    public static final ModConfigSpec.IntValue UPGRADES_XRAY_MAX_COUNT;

    // === programmingStation ===
    public static final ModConfigSpec.IntValue PROGRAMMING_STATION_BASE_COST_PATROL;
    public static final ModConfigSpec.IntValue PROGRAMMING_STATION_BASE_COST_SIGHT;
    public static final ModConfigSpec.IntValue PROGRAMMING_STATION_BASE_COST_EXPLOSIVE;
    public static final ModConfigSpec.IntValue PROGRAMMING_STATION_BASE_COST_SIREN;
    public static final ModConfigSpec.IntValue PROGRAMMING_STATION_BASE_COST_TRANSMITTER;
    public static final ModConfigSpec.IntValue PROGRAMMING_STATION_BASE_COST_ENERGY;
    public static final ModConfigSpec.IntValue PROGRAMMING_STATION_BASE_COST_HEALTH;
    public static final ModConfigSpec.IntValue PROGRAMMING_STATION_BASE_COST_PLAYER_SEEK;
    public static final ModConfigSpec.IntValue PROGRAMMING_STATION_BASE_COST_MULTI_TARGET;
    public static final ModConfigSpec.IntValue PROGRAMMING_STATION_BASE_COST_XRAY;

    // === chargingStation ===
    public static final ModConfigSpec.IntValue CHARGING_STATION_CAPACITY;
    public static final ModConfigSpec.IntValue CHARGING_STATION_CHARGE_RATE;
    public static final ModConfigSpec.DoubleValue CHARGING_STATION_HEAL_PER_TICK;
    public static final ModConfigSpec.IntValue CHARGING_STATION_TANK_CAPACITY;
    public static final ModConfigSpec.IntValue CHARGING_STATION_REPAIR_FLUID_PER_HP;

    public static final ModConfigSpec.IntValue FACTORY_ENERGY_CAPACITY;
    public static final ModConfigSpec.IntValue FACTORY_TANK_CAPACITY;

    // === deployingStation ===
    public static final ModConfigSpec.IntValue DEPLOYING_STATION_ENERGY_PER_DEPLOY;

    public static final ModConfigSpec SPEC;

    static {
        BUILDER.push("drone");
        DRONE_BASE_MAX_ENERGY = BUILDER
                .comment("Base max energy (FE) of a drone with no Energy upgrades.")
                .defineInRange("baseMaxEnergy", 100_000, 1, Integer.MAX_VALUE);
        DRONE_ENERGY_PER_BLOCK = BUILDER
                .comment("FE consumed per block flown (distance cost).")
                .defineInRange("energyPerBlock", 20, 0, Integer.MAX_VALUE);
        DRONE_HOVER_ENERGY_PER_TICK = BUILDER
                .comment("FE consumed per tick while airborne (hover cost).")
                .defineInRange("hoverEnergyPerTick", 1, 0, Integer.MAX_VALUE);
        DRONE_ENERGY_DRAIN_INTERVAL = BUILDER
                .comment("Ticks between batched energy drain applications.")
                .defineInRange("energyDrainInterval", 20, 1, Integer.MAX_VALUE);
        DRONE_RETURN_SAFETY_MARGIN = BUILDER
                .comment("Safety margin multiplier applied to the dynamic return-to-charge threshold.")
                .defineInRange("returnSafetyMargin", 1.25, 1.0, 100.0);
        DRONE_RETURN_WAIT_BUFFER = BUILDER
                .comment("Ticks of hover energy added to the return-to-charge threshold, so a drone can wait for a busy station.")
                .defineInRange("returnWaitBuffer", 600, 0, Integer.MAX_VALUE);
        DRONE_CHARGING_SEARCH_RADIUS = BUILDER
                .comment("Radius (blocks) to search the station registry for a usable Charging Station.")
                .defineInRange("chargingSearchRadius", 500, 1, Integer.MAX_VALUE);
        DRONE_CHARGING_ALTERNATE_RADIUS = BUILDER
                .comment("Radius (blocks) to search for a free alternate station when the chosen one is busy.")
                .defineInRange("chargingAlternateRadius", 10, 1, Integer.MAX_VALUE);
        DRONE_UNREACHABLE_STATION_COOLDOWN = BUILDER
                .comment("Ticks a drone skips a Charging Station it couldn't reach before trying it again.")
                .defineInRange("unreachableStationCooldown", 1200, 0, Integer.MAX_VALUE);
        DRONE_BASE_MAX_HEALTH = BUILDER
                .comment("Base max HP of a drone with no Health upgrades.")
                .defineInRange("baseMaxHealth", 20.0, 1.0, 1024.0);
        DRONE_BASE_SIGHT_RANGE = BUILDER
                .comment("Base detection (sight) range in blocks, with no Sight upgrades.")
                .defineInRange("baseSightRange", 8, 1, 256);
        DRONE_PURSUIT_MULTIPLIER = BUILDER
                .comment("Multiplier applied to sight range to get the pursuit range at which a chased target is lost.")
                .defineInRange("pursuitMultiplier", 2.0, 1.0, 100.0);
        DRONE_LOST_SIGHT_TIMEOUT = BUILDER
                .comment("Ticks of continuous lost line of sight before a non-X-ray drone gives up its target.")
                .defineInRange("lostSightTimeout", 100, 0, Integer.MAX_VALUE);
        DRONE_SCAN_INTERVAL = BUILDER
                .comment("Ticks between staggered target scans per drone.")
                .defineInRange("scanInterval", 10, 1, Integer.MAX_VALUE);
        DRONE_MAX_RAYCASTS_PER_SCAN = BUILDER
                .comment("Maximum line-of-sight raycasts performed per drone per scan.")
                .defineInRange("maxRaycastsPerScan", 4, 1, 64);
        DRONE_CRUISE_SPEED = BUILDER
                .comment("Base chase speed (blocks/tick). Non-Explosive drones chase at this speed plus their target's speed; Explosive drones fly at it at the edge of sight range.")
                .defineInRange("cruiseSpeed", 0.4, 0.01, 1.5);
        DRONE_MAX_SPEED = BUILDER
                .comment("Hard cap on chase speed (blocks/tick). Suggested ceiling is about 1.5.")
                .defineInRange("maxSpeed", 1.2, 0.01, 1.5);
        DRONE_CHASE_ACCELERATION_K = BUILDER
                .comment("Exponential growth rate k in the Explosive chase speed curve.")
                .defineInRange("chaseAccelerationK", 2.0, 0.0, 10.0);
        DRONE_ACCELERATION = BUILDER
                .comment("Max change in a drone's velocity per tick (blocks/tick²). Lower is smoother but slower to turn and stop.")
                .defineInRange("acceleration", 0.04, 0.001, 1.5);
        DRONE_EXPLOSIVE_ACCELERATION = BUILDER
                .comment("Max change in velocity per tick (blocks/tick²) for an Explosive drone chasing its target, so it can turn fast enough to hit it.")
                .defineInRange("explosiveAcceleration", 0.15, 0.001, 1.5);
        DRONE_TURN_SPEED = BUILDER
                .comment("Max turn rate (degrees/tick) of a drone's facing.")
                .defineInRange("turnSpeed", 12.0, 1.0, 180.0);
        DRONE_FACING_TOLERANCE = BUILDER
                .comment("How far (degrees) a drone may face away from where it wants to face before it turns.")
                .defineInRange("facingTolerance", 20.0, 0.0, 180.0);
        DRONE_DEFAULT_FOLLOW_DISTANCE = BUILDER
                .comment("Default follow distance (blocks) for a new drone's base config.")
                .defineInRange("defaultFollowDistance", 4, 1, 64);
        DRONE_FOLLOW_HEIGHT_OFFSET = BUILDER
                .comment("Minimum height (blocks) above the target's eyes while following.")
                .defineInRange("followHeightOffset", 1.5, 0.0, 16.0);
        DRONE_FOLLOW_ENTER_DISTANCE = BUILDER
                .comment("Distance (blocks) to the follow position at which a chasing drone switches to following.")
                .defineInRange("followEnterDistance", 1.0, 0.1, 16.0);
        DRONE_FOLLOW_EXIT_DISTANCE = BUILDER
                .comment("Distance (blocks) to the follow position at which a following drone goes back to chasing. Must be larger than followEnterDistance; it is raised to match if not.")
                .defineInRange("followExitDistance", 3.0, 0.1, 32.0);
        DRONE_FOLLOW_SLACK = BUILDER
                .comment("How far (blocks) the follow position may move away from a settled following drone before it moves again. Kept at least followEnterDistance.")
                .defineInRange("followSlack", 2.0, 0.1, 32.0);
        DRONE_FOLLOW_SMOOTHING = BUILDER
                .comment("Fraction of the way the tracked target position moves toward the target each tick while chasing or following. Lower is smoother but lags more.")
                .defineInRange("followSmoothing", 0.15, 0.01, 1.0);
        DRONE_REPATH_DISTANCE = BUILDER
                .comment("How far (blocks) a navigating drone's goal may move before its path is recomputed between staggered scan ticks.")
                .defineInRange("repathDistance", 1.0, 0.1, 16.0);
        DRONE_EXPLOSION_TRIGGER_DISTANCE = BUILDER
                .comment("Distance (blocks) at which an Explosive drone triggers on its target.")
                .defineInRange("explosionTriggerDistance", 1.5, 0.1, 16.0);
        DRONE_DEPLOY_SPAWN_DISTANCE = BUILDER
                .comment("Distance (blocks) in front of the player's eyes where a hand-deployed drone spawns.")
                .defineInRange("deploySpawnDistance", 1.0, 0.0, 4.0);
        DRONE_DEPLOY_THROW_SPEED = BUILDER
                .comment("Speed (blocks/tick) added along the look direction on hand-deploy, on top of the player's velocity.")
                .defineInRange("deployThrowSpeed", 0.15, 0.0, 1.5);
        DRONE_DEPLOY_DRAG = BUILDER
                .comment("Velocity multiplier applied each tick while a drone drifts.")
                .defineInRange("deployDrag", 0.9, 0.0, 0.99);
        DRONE_DEPLOY_REST_SPEED = BUILDER
                .comment("Speed (blocks/tick) below which a drifting drone comes to rest.")
                .defineInRange("deployRestSpeed", 0.01, 0.0, 1.0);
        DRONE_WATER_DAMAGE = BUILDER
                .comment("HP lost each water damage interval while a drone is in water.")
                .defineInRange("waterDamage", 1.0, 0.0, 1024.0);
        DRONE_WATER_DAMAGE_INTERVAL = BUILDER
                .comment("Ticks between water damage applications.")
                .defineInRange("waterDamageInterval", 20, 1, Integer.MAX_VALUE);
        BUILDER.pop();

        BUILDER.push("upgrades");
        UPGRADES_TOTAL_SLOTS = BUILDER
                .comment("Total upgrade slot limit per drone, across all upgrade types.")
                .defineInRange("totalSlots", 24, 1, 64);

        BUILDER.push("patrol");
        UPGRADES_PATROL_MAX_COUNT = BUILDER.comment("Per-type cap.").defineInRange("maxCount", 4, 0, 64);
        UPGRADES_PATROL_BASE_RADIUS = BUILDER.comment("Patrol radius (blocks) with one Patrol upgrade.").defineInRange("baseRadius", 16, 1, Integer.MAX_VALUE);
        UPGRADES_PATROL_PER_UPGRADE_RADIUS = BUILDER.comment("Extra patrol radius (blocks) per additional Patrol upgrade.").defineInRange("perUpgrade", 16, 0, Integer.MAX_VALUE);
        UPGRADES_PATROL_SPEED = BUILDER.comment("Patrol flight speed (blocks/tick).").defineInRange("speed", 0.25, 0.01, 1.5);
        UPGRADES_PATROL_WAYPOINT_SPACING = BUILDER.comment("Distance (blocks) between patrol waypoints along the circle. There are always at least 8 waypoints.").defineInRange("waypointSpacing", 8.0, 1.0, 256.0);
        UPGRADES_PATROL_MAX_CLIMB = BUILDER.comment("How far (blocks) above the patrol height a patrol waypoint may be raised to clear an obstacle. Waypoints that need more are skipped.").defineInRange("maxClimb", 16.0, 0.0, 384.0);
        UPGRADES_PATROL_CLIMB_CLEARANCE = BUILDER.comment("Gap (blocks) kept between the drone and the obstacle below a raised patrol waypoint.").defineInRange("climbClearance", 1.0, 0.0, 16.0);
        BUILDER.pop();

        BUILDER.push("sight");
        UPGRADES_SIGHT_MAX_COUNT = BUILDER.comment("Per-type cap.").defineInRange("maxCount", 8, 0, 64);
        UPGRADES_SIGHT_PER_UPGRADE = BUILDER.comment("Extra sight range (blocks) per Sight upgrade.").defineInRange("perUpgrade", 8, 0, Integer.MAX_VALUE);
        BUILDER.pop();

        BUILDER.push("explosive");
        UPGRADES_EXPLOSIVE_MAX_COUNT = BUILDER.comment("Per-type cap.").defineInRange("maxCount", 4, 0, 64);
        UPGRADES_EXPLOSIVE_BASE_POWER = BUILDER.comment("Explosion power with one Explosive upgrade. TNT is 4.0.").defineInRange("basePower", 2.0, 0.0, 128.0);
        UPGRADES_EXPLOSIVE_PER_UPGRADE = BUILDER.comment("Extra explosion power per additional Explosive upgrade.").defineInRange("perUpgrade", 1.0, 0.0, 128.0);
        BUILDER.pop();

        BUILDER.push("siren");
        UPGRADES_SIREN_MAX_COUNT = BUILDER.comment("Per-type cap.").defineInRange("maxCount", 3, 0, 64);
        UPGRADES_SIREN_BASE_VOLUME = BUILDER.comment("Sound volume with one Siren upgrade.").defineInRange("baseVolume", 2.0, 0.0, 128.0);
        UPGRADES_SIREN_PER_UPGRADE = BUILDER.comment("Extra sound volume per additional Siren upgrade.").defineInRange("perUpgrade", 2.0, 0.0, 128.0);
        UPGRADES_SIREN_REPEAT_INTERVAL = BUILDER.comment("Ticks between siren repeats while chasing or following.").defineInRange("repeatInterval", 100, 1, Integer.MAX_VALUE);
        BUILDER.pop();

        BUILDER.push("transmitter");
        UPGRADES_TRANSMITTER_MAX_COUNT = BUILDER.comment("Per-type cap.").defineInRange("maxCount", 1, 0, 64);
        UPGRADES_TRANSMITTER_COOLDOWN = BUILDER.comment("Ticks between Transmitter chat notifications per drone.").defineInRange("cooldown", 200, 0, Integer.MAX_VALUE);
        BUILDER.pop();

        BUILDER.push("energy");
        UPGRADES_ENERGY_MAX_COUNT = BUILDER.comment("Per-type cap.").defineInRange("maxCount", 4, 0, 64);
        UPGRADES_ENERGY_PER_UPGRADE = BUILDER.comment("Extra max energy (FE) per Energy upgrade.").defineInRange("perUpgrade", 100_000, 0, Integer.MAX_VALUE);
        BUILDER.pop();

        BUILDER.push("health");
        UPGRADES_HEALTH_MAX_COUNT = BUILDER.comment("Per-type cap.").defineInRange("maxCount", 4, 0, 64);
        UPGRADES_HEALTH_PER_UPGRADE = BUILDER.comment("Extra max HP per Health upgrade.").defineInRange("perUpgrade", 10.0, 0.0, 1024.0);
        BUILDER.pop();

        BUILDER.push("playerSeek");
        UPGRADES_PLAYER_SEEK_MAX_COUNT = BUILDER.comment("Per-type cap.").defineInRange("maxCount", 1, 0, 64);
        BUILDER.pop();

        BUILDER.push("multiTarget");
        UPGRADES_MULTI_TARGET_MAX_COUNT = BUILDER.comment("Per-type cap.").defineInRange("maxCount", 3, 0, 64);
        UPGRADES_MULTI_TARGET_PER_UPGRADE = BUILDER.comment("Extra target slots per Multi-target upgrade.").defineInRange("perUpgrade", 1, 0, 64);
        BUILDER.pop();

        BUILDER.push("xray");
        UPGRADES_XRAY_MAX_COUNT = BUILDER.comment("Per-type cap.").defineInRange("maxCount", 1, 0, 64);
        BUILDER.pop();

        BUILDER.pop(); // upgrades

        BUILDER.push("programmingStation");
        BUILDER.push("baseCost");
        PROGRAMMING_STATION_BASE_COST_PATROL = BUILDER.comment("Base FE cost, multiplied by the upgrade's install index.").defineInRange("patrol", 10_000, 0, Integer.MAX_VALUE);
        PROGRAMMING_STATION_BASE_COST_SIGHT = BUILDER.comment("Base FE cost, multiplied by the upgrade's install index.").defineInRange("sight", 10_000, 0, Integer.MAX_VALUE);
        PROGRAMMING_STATION_BASE_COST_EXPLOSIVE = BUILDER.comment("Base FE cost, multiplied by the upgrade's install index.").defineInRange("explosive", 10_000, 0, Integer.MAX_VALUE);
        PROGRAMMING_STATION_BASE_COST_SIREN = BUILDER.comment("Base FE cost, multiplied by the upgrade's install index.").defineInRange("siren", 10_000, 0, Integer.MAX_VALUE);
        PROGRAMMING_STATION_BASE_COST_TRANSMITTER = BUILDER.comment("Base FE cost, multiplied by the upgrade's install index.").defineInRange("transmitter", 10_000, 0, Integer.MAX_VALUE);
        PROGRAMMING_STATION_BASE_COST_ENERGY = BUILDER.comment("Base FE cost, multiplied by the upgrade's install index.").defineInRange("energy", 10_000, 0, Integer.MAX_VALUE);
        PROGRAMMING_STATION_BASE_COST_HEALTH = BUILDER.comment("Base FE cost, multiplied by the upgrade's install index.").defineInRange("health", 10_000, 0, Integer.MAX_VALUE);
        PROGRAMMING_STATION_BASE_COST_PLAYER_SEEK = BUILDER.comment("Base FE cost, multiplied by the upgrade's install index.").defineInRange("playerSeek", 10_000, 0, Integer.MAX_VALUE);
        PROGRAMMING_STATION_BASE_COST_MULTI_TARGET = BUILDER.comment("Base FE cost, multiplied by the upgrade's install index.").defineInRange("multiTarget", 10_000, 0, Integer.MAX_VALUE);
        PROGRAMMING_STATION_BASE_COST_XRAY = BUILDER.comment("Base FE cost, multiplied by the upgrade's install index.").defineInRange("xray", 10_000, 0, Integer.MAX_VALUE);
        BUILDER.pop();
        BUILDER.pop(); // programmingStation

        BUILDER.push("chargingStation");
        CHARGING_STATION_CAPACITY = BUILDER
                .comment("FE a Charging Station can store.")
                .defineInRange("capacity", 100_000, 1, Integer.MAX_VALUE);
        CHARGING_STATION_CHARGE_RATE = BUILDER
                .comment("FE/tick a Charging Station feeds into its docked drone.")
                .defineInRange("chargeRate", 1_000, 1, Integer.MAX_VALUE);
        CHARGING_STATION_HEAL_PER_TICK = BUILDER
                .comment("HP/tick a Charging Station restores to its docked drone.")
                .defineInRange("healPerTick", 0.1, 0.0, 1024.0);
        CHARGING_STATION_TANK_CAPACITY = BUILDER
                .comment("mB of repair fluid a Charging Station can store.")
                .defineInRange("tankCapacity", 4_000, 1, Integer.MAX_VALUE);
        CHARGING_STATION_REPAIR_FLUID_PER_HP = BUILDER
                .comment("mB of repair fluid used per HP restored. 0 makes healing free and not need any fluid.")
                .defineInRange("repairFluidPerHp", 10, 0, Integer.MAX_VALUE);
        BUILDER.pop();

        BUILDER.push("factory");
        FACTORY_ENERGY_CAPACITY = BUILDER
                .comment("FE a Drone Factory can store.")
                .defineInRange("energyCapacity", 200_000, 1, Integer.MAX_VALUE);
        FACTORY_TANK_CAPACITY = BUILDER
                .comment("mB of fluid a Drone Factory can store.")
                .defineInRange("tankCapacity", 4_000, 1, Integer.MAX_VALUE);
        BUILDER.pop();

        BUILDER.push("deployingStation");
        DEPLOYING_STATION_ENERGY_PER_DEPLOY = BUILDER
                .comment("FE consumed by a Deploying Station per drone deployed.")
                .defineInRange("energyPerDeploy", 5_000, 0, Integer.MAX_VALUE);
        BUILDER.pop();

        SPEC = BUILDER.build();
    }

    /**
     * Reads a config value, falling back to its default while the server config isn't loaded
     * (e.g. item tooltips in the main menu).
     */
    public static <T> T get(ModConfigSpec.ConfigValue<T> value) {
        return SPEC.isLoaded() ? value.get() : value.getDefault();
    }

    private ServerConfig() {}
}
