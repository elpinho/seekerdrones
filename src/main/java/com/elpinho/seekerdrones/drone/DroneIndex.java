package com.elpinho.seekerdrones.drone;

import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;

/**
 * Finds a loaded drone entity by its drone ID without searching (DESIGN.md section 2.10). Kept per level and updated
 * when a drone joins or leaves the level, or when its ID changes. Only drones in loaded chunks are in it. Drone IDs are
 * random, so two drones could in theory share one: the latest to join is the one found.
 */
public final class DroneIndex {
    private static final Map<LevelAccessor, Map<String, DroneEntity>> DRONES = new HashMap<>();

    private DroneIndex() {}

    /** Adds the drone under its ID. Drones without an ID aren't indexed. */
    static void add(DroneEntity drone, String droneId) {
        if (!droneId.isEmpty()) {
            DRONES.computeIfAbsent(drone.level(), level -> new HashMap<>()).put(droneId, drone);
        }
    }

    /** Removes the drone from under its ID, unless another drone has taken that ID since. */
    static void remove(DroneEntity drone, String droneId) {
        Map<String, DroneEntity> levelDrones = DRONES.get(drone.level());
        if (levelDrones != null && levelDrones.get(droneId) == drone) {
            levelDrones.remove(droneId);
            if (levelDrones.isEmpty()) {
                DRONES.remove(drone.level());
            }
        }
    }

    /** The loaded drone with this ID in the level, or null. */
    @Nullable
    public static DroneEntity find(LevelAccessor level, String droneId) {
        Map<String, DroneEntity> levelDrones = DRONES.get(level);
        DroneEntity drone = levelDrones != null ? levelDrones.get(droneId) : null;
        return drone != null && !drone.isRemoved() ? drone : null;
    }

    /** The loaded drone with this ID in any of the server's levels, or null. */
    @Nullable
    public static DroneEntity find(MinecraftServer server, String droneId) {
        for (ServerLevel level : server.getAllLevels()) {
            DroneEntity drone = find(level, droneId);
            if (drone != null) {
                return drone;
            }
        }
        return null;
    }

    /** Drops a level's drones, e.g. when it unloads. */
    public static void clear(LevelAccessor level) {
        DRONES.remove(level);
    }
}
