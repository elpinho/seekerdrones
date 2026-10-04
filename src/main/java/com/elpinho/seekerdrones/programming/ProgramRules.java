package com.elpinho.seekerdrones.programming;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.annotation.Nullable;

import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.drone.DroneConfig;
import com.elpinho.seekerdrones.drone.DroneStats;
import com.elpinho.seekerdrones.drone.TargetBlacklist;
import com.elpinho.seekerdrones.drone.TargetEntry;
import com.elpinho.seekerdrones.drone.UpgradeType;

import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.StringUtil;
import net.minecraft.world.level.Level;

/**
 * The Programming Station's rules for programs (DESIGN.md section 7.2). The client uses them to grey out buttons and
 * mark bad input, and the server checks every edit against them again.
 */
public final class ProgramRules {
    public static final int MAX_LABEL_LENGTH = 32;
    /** Sanity bound for a typed patrol center's height. */
    private static final int MAX_PATROL_CENTER_Y = 4096;

    private static final String KEY = "screen.seekerdrones.programming_station.target.";

    private ProgramRules() {}

    /** Whether one more upgrade of the type fits the per-type cap and the total slot limit (section 4). */
    public static boolean canAdd(Map<UpgradeType, Integer> upgrades, UpgradeType type) {
        return upgrades.getOrDefault(type, 0) < type.maxCount()
                && DroneStats.totalUpgrades(upgrades) < ServerConfig.get(ServerConfig.UPGRADES_TOTAL_SLOTS);
    }

    /**
     * FE to install the {@code index}-th upgrade of a type (1-based): {@code baseCost × index}, plus the capacity an
     * Energy upgrade adds, which goes into the drone.
     */
    public static int installCost(UpgradeType type, int index) {
        long cost = (long) type.baseCost() * index;
        if (type == UpgradeType.ENERGY) {
            cost += ServerConfig.get(ServerConfig.UPGRADES_ENERGY_PER_UPGRADE);
        }
        return (int) Math.min(cost, Integer.MAX_VALUE);
    }

    public static int maxFollowDistance() {
        return ServerConfig.get(ServerConfig.DRONE_MAX_FOLLOW_DISTANCE);
    }

    /**
     * Why the entry at {@code index} of {@code targets} isn't allowed, as a translation key, or null if it is. Entries
     * already in {@code previous} are always kept: they may be ignored at runtime (section 2.7) but are only removed on
     * purpose.
     */
    @Nullable
    public static String targetProblem(List<TargetEntry> targets, int index, List<TargetEntry> previous, Map<UpgradeType, Integer> upgrades) {
        TargetEntry entry = targets.get(index);
        for (int i = 0; i < targets.size(); i++) {
            if (i != index && targets.get(i).sameAs(entry)) {
                return KEY + "duplicate";
            }
        }
        if (previous.contains(entry)) {
            return null;
        }
        if (!entry.isValid()) {
            return KEY + "unknown." + entry.kind().getSerializedName();
        }
        if (TargetBlacklist.blocks(entry)) {
            return KEY + "blacklisted";
        }
        if (index >= DroneStats.targetSlots()) {
            return KEY + "no_slot";
        }
        if (entry.kind() == TargetEntry.Kind.PLAYER_NAME && upgrades.getOrDefault(UpgradeType.PLAYER_SEEK, 0) <= 0) {
            return KEY + "needs_player_seek";
        }
        return null;
    }

    /**
     * Checks settings sent by a player against the ones they replace. Returns the accepted settings, with the patrol
     * center moved to the station's dimension, or empty if anything is invalid.
     */
    public static Optional<DroneConfig> checkConfig(DroneConfig requested, DroneConfig previous, Map<UpgradeType, Integer> upgrades,
            ResourceKey<Level> dimension) {
        List<TargetEntry> targets = requested.targets();
        for (int i = 0; i < targets.size(); i++) {
            if (targetProblem(targets, i, previous.targets(), upgrades) != null) {
                return Optional.empty();
            }
        }
        int follow = requested.followDistance();
        if (follow != previous.followDistance() && (follow < 1 || follow > maxFollowDistance())) {
            return Optional.empty();
        }
        String label = requested.label();
        if (label.length() > MAX_LABEL_LENGTH || !StringUtil.filterText(label).equals(label)) {
            return Optional.empty();
        }
        if (requested.patrolRadius().isPresent() && requested.patrolRadius().get() < 1) {
            return Optional.empty();
        }
        Optional<GlobalPos> center = requested.patrolCenter();
        // An unchanged center keeps its dimension; a new one is always in the station's.
        if (center.isPresent() && !center.equals(previous.patrolCenter())) {
            var pos = center.get().pos();
            if (Math.abs(pos.getX()) > Level.MAX_LEVEL_SIZE || Math.abs(pos.getZ()) > Level.MAX_LEVEL_SIZE
                    || Math.abs(pos.getY()) > MAX_PATROL_CENTER_Y) {
                return Optional.empty();
            }
            center = Optional.of(GlobalPos.of(dimension, pos));
        }
        return Optional.of(requested.withPatrolCenter(center));
    }
}
