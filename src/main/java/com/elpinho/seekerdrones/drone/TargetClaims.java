package com.elpinho.seekerdrones.drone;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import com.elpinho.seekerdrones.config.ServerConfig;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelAccessor;

/**
 * Shared target claims (DESIGN.md section 3.3): stops drones of the same team from piling onto the same entity. Only
 * one non-Explosive drone of a team tracks each entity, and at most {@code drone.maxExplosiveDronesPerTarget}
 * Explosive ones go after it. Claims are transient (not saved) and kept per level, keyed by the target's entity ID.
 * <p>
 * A claim only counts while its drone is still in the level and still targets the entity, so a claim that missed its
 * release (e.g. a drone removed in an unusual way) can't block the entity. Such entries are pruned when read.
 */
public final class TargetClaims {
    private static final Map<LevelAccessor, Int2ObjectMap<List<Claim>>> CLAIMS = new HashMap<>();

    /** A drone's team: its Operator Group, else its owner. All unowned drones share the team with a null ID. */
    public record Team(boolean group, @Nullable UUID id) {
        public static Team of(DroneData data) {
            if (data.groupId().isPresent()) {
                return new Team(true, data.groupId().get());
            }
            return new Team(false, data.ownerId().orElse(null));
        }
    }

    private record Claim(DroneEntity drone, Team team, boolean explosive) {}

    private TargetClaims() {}

    /** Whether the drone may claim this entity: its team's claims of the same kind are below the limit. */
    public static boolean canClaim(DroneEntity drone, Entity target) {
        int limit = limit(drone);
        if (limit <= 0) {
            return true;
        }
        List<Claim> claims = claims(target, false);
        return claims == null || count(claims, drone, target) < limit;
    }

    /**
     * Claims the drone's current target, if the limit allows it. The drone must not hold a claim already: release it
     * first (e.g. before its team changes).
     */
    public static boolean claim(DroneEntity drone) {
        Entity target = drone.getSeekTarget();
        if (target == null || !canClaim(drone, target)) {
            return false;
        }
        claims(target, true).add(new Claim(drone, drone.getClaimTeam(), drone.isExplosive()));
        return true;
    }

    /** Releases the drone's claim on its current target, if any. */
    public static void release(DroneEntity drone) {
        Entity target = drone.getSeekTarget();
        if (target == null) {
            return;
        }
        Int2ObjectMap<List<Claim>> levelClaims = CLAIMS.get(drone.level());
        if (levelClaims == null) {
            return;
        }
        List<Claim> claims = levelClaims.get(target.getId());
        if (claims == null) {
            return;
        }
        claims.removeIf(claim -> claim.drone() == drone);
        if (claims.isEmpty()) {
            levelClaims.remove(target.getId());
        }
    }

    /** Drops all claims of a level, e.g. when it unloads. */
    public static void clear(LevelAccessor level) {
        CLAIMS.remove(level);
    }

    private static int limit(DroneEntity drone) {
        return drone.isExplosive() ? ServerConfig.get(ServerConfig.DRONE_MAX_EXPLOSIVE_DRONES_PER_TARGET) : 1;
    }

    /** Counts the live claims of the drone's team and kind on the target, other than the drone's own. Prunes stale ones. */
    private static int count(List<Claim> claims, DroneEntity drone, Entity target) {
        Team team = drone.getClaimTeam();
        boolean explosive = drone.isExplosive();
        claims.removeIf(claim -> claim.drone().isRemoved() || claim.drone().getSeekTarget() != target);
        int count = 0;
        for (Claim claim : claims) {
            if (claim.drone() != drone && claim.explosive() == explosive && claim.team().equals(team)) {
                count++;
            }
        }
        return count;
    }

    @Nullable
    private static List<Claim> claims(Entity target, boolean create) {
        if (!create) {
            Int2ObjectMap<List<Claim>> levelClaims = CLAIMS.get(target.level());
            return levelClaims != null ? levelClaims.get(target.getId()) : null;
        }
        return CLAIMS.computeIfAbsent(target.level(), level -> new Int2ObjectOpenHashMap<>())
                .computeIfAbsent(target.getId(), id -> new ArrayList<>(2));
    }
}
