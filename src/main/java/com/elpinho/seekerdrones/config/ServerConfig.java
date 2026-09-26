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
    public static final ModConfigSpec.IntValue DRONE_CHARGING_SEARCH_RADIUS;
    public static final ModConfigSpec.IntValue DRONE_CHARGING_ALTERNATE_RADIUS;
    public static final ModConfigSpec.DoubleValue DRONE_BASE_MAX_HEALTH;
    public static final ModConfigSpec.IntValue DRONE_BASE_SIGHT_RANGE;
    public static final ModConfigSpec.DoubleValue DRONE_PURSUIT_MULTIPLIER;
    public static final ModConfigSpec.IntValue DRONE_LOST_SIGHT_TIMEOUT;
    public static final ModConfigSpec.IntValue DRONE_SCAN_INTERVAL;
    public static final ModConfigSpec.IntValue DRONE_MAX_RAYCASTS_PER_SCAN;
    public static final ModConfigSpec.DoubleValue DRONE_CRUISE_SPEED;
    public static final ModConfigSpec.DoubleValue DRONE_MAX_SPEED;
    public static final ModConfigSpec.DoubleValue DRONE_CHASE_ACCELERATION_K;
    public static final ModConfigSpec.IntValue DRONE_DEFAULT_FOLLOW_DISTANCE;
    public static final ModConfigSpec.DoubleValue DRONE_EXPLOSION_TRIGGER_DISTANCE;

    // === upgrades ===
    public static final ModConfigSpec.IntValue UPGRADES_TOTAL_SLOTS;

    public static final ModConfigSpec.IntValue UPGRADES_PATROL_MAX_COUNT;
    public static final ModConfigSpec.IntValue UPGRADES_PATROL_BASE_RADIUS;
    public static final ModConfigSpec.IntValue UPGRADES_PATROL_PER_UPGRADE_RADIUS;

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
    public static final ModConfigSpec.IntValue CHARGING_STATION_CHARGE_RATE;

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
        DRONE_CHARGING_SEARCH_RADIUS = BUILDER
                .comment("Radius (blocks) to search the station registry for a usable Charging Station.")
                .defineInRange("chargingSearchRadius", 500, 1, Integer.MAX_VALUE);
        DRONE_CHARGING_ALTERNATE_RADIUS = BUILDER
                .comment("Radius (blocks) to search for a free alternate station when the chosen one is busy.")
                .defineInRange("chargingAlternateRadius", 10, 1, Integer.MAX_VALUE);
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
                .comment("Chase speed (blocks/tick) at the edge of sight range.")
                .defineInRange("cruiseSpeed", 0.4, 0.01, 1.5);
        DRONE_MAX_SPEED = BUILDER
                .comment("Hard cap on chase speed (blocks/tick). Suggested ceiling is about 1.5.")
                .defineInRange("maxSpeed", 1.2, 0.01, 1.5);
        DRONE_CHASE_ACCELERATION_K = BUILDER
                .comment("Exponential growth rate k in the chase speed curve.")
                .defineInRange("chaseAccelerationK", 2.0, 0.0, 10.0);
        DRONE_DEFAULT_FOLLOW_DISTANCE = BUILDER
                .comment("Default follow distance (blocks) for a new drone's base config.")
                .defineInRange("defaultFollowDistance", 4, 1, 64);
        DRONE_EXPLOSION_TRIGGER_DISTANCE = BUILDER
                .comment("Distance (blocks) at which an Explosive drone triggers on its target.")
                .defineInRange("explosionTriggerDistance", 1.5, 0.1, 16.0);
        BUILDER.pop();

        BUILDER.push("upgrades");
        UPGRADES_TOTAL_SLOTS = BUILDER
                .comment("Total upgrade slot limit per drone, across all upgrade types.")
                .defineInRange("totalSlots", 8, 1, 64);

        BUILDER.push("patrol");
        UPGRADES_PATROL_MAX_COUNT = BUILDER.comment("Per-type cap.").defineInRange("maxCount", 4, 0, 64);
        UPGRADES_PATROL_BASE_RADIUS = BUILDER.comment("Patrol radius (blocks) with one Patrol upgrade.").defineInRange("baseRadius", 16, 1, Integer.MAX_VALUE);
        UPGRADES_PATROL_PER_UPGRADE_RADIUS = BUILDER.comment("Extra patrol radius (blocks) per additional Patrol upgrade.").defineInRange("perUpgrade", 16, 0, Integer.MAX_VALUE);
        BUILDER.pop();

        BUILDER.push("sight");
        UPGRADES_SIGHT_MAX_COUNT = BUILDER.comment("Per-type cap.").defineInRange("maxCount", 4, 0, 64);
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
        CHARGING_STATION_CHARGE_RATE = BUILDER
                .comment("FE/tick a Charging Station feeds into its docked drone.")
                .defineInRange("chargeRate", 1_000, 1, Integer.MAX_VALUE);
        BUILDER.pop();

        BUILDER.push("deployingStation");
        DEPLOYING_STATION_ENERGY_PER_DEPLOY = BUILDER
                .comment("FE consumed by a Deploying Station per drone deployed.")
                .defineInRange("energyPerDeploy", 5_000, 0, Integer.MAX_VALUE);
        BUILDER.pop();

        SPEC = BUILDER.build();
    }

    private ServerConfig() {}
}
