package com.elpinho.seekerdrones.drone;

import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.feed.FpvFeeds;
import com.elpinho.seekerdrones.station.ChargingStationBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.ItemEntity;

/**
 * A drone's energy drain accounting and self-healing (sections 2.5 and 5.1). Server side only.
 */
final class DroneEnergy {
    private static final String TAG_DRAIN_DISTANCE = "DrainDistance";
    private static final String TAG_DRAIN_REMAINDER = "DrainRemainder";
    private static final String TAG_DRAIN_HOVER_TICKS = "DrainHoverTicks";
    private static final String TAG_DRAIN_QUEUE_COST = "DrainQueueCost";

    /** A single-tick move longer than this (blocks) is a teleport, not flight, and costs no energy. */
    private static final double MAX_DRAIN_STEP = 4.0;

    private final DroneEntity drone;

    /** Distance flown (blocks) since the last energy drain (section 5.1). */
    private double drainDistance;
    /** Ticks spent airborne (not docked) since the last energy drain (section 5.1). */
    private int drainHoverTicks;
    /**
     * FE owed since the last drain for ticks spent waiting at a busy station, which pay only the base hover cost
     * without the upgrade multiplier (section 5.1). Kept apart from the distance and hover above, which are multiplied.
     */
    private double drainQueueCost;
    /** FE owed since the last drain for ticks watched through the FPV feed (section 2.12), without the upgrade multiplier. Not saved. */
    private double drainFeedCost;
    /** Ticks billed by the next drain, docked ticks excluded, to turn its cost into a rate. Not saved. */
    private int drainTicks;
    /** FE per tick the last drain cost, for the FPV HUD (section 2.12). Not saved. */
    private double drainRate;
    /** FE per tick the Solar upgrades generated at the last drain (section 4), for the energy rate displays. Not saved. */
    private double solarRate;
    /** Set while the drone waits at a busy station this tick (section 5.3), read and cleared by the energy drain. */
    private boolean queuedThisTick;
    /** The fraction of a FE owed at the last drain, carried over so small costs add up. */
    private double drainRemainder;
    /** Game time of the last damage taken, which delays self-healing (section 2.5). Not saved. */
    private long lastDamageTime = Long.MIN_VALUE;
    /** FE per tick that self-healing cost at the last drain, for the status screen's energy rate (section 2.4). Not saved. */
    private double selfHealRate;

    DroneEnergy(DroneEntity drone) {
        this.drone = drone;
    }

    /** The drone waits at a busy station this tick: it pays only the base hover cost (section 5.3). */
    void markQueued() {
        queuedThisTick = true;
    }

    /** The drone took damage: self-healing waits for {@code drone.selfHealDelay} again. */
    void onHurt() {
        lastDamageTime = drone.level().getGameTime();
        selfHealRate = 0;
    }

    double solarRate() {
        return solarRate;
    }

    double selfHealRate() {
        return selfHealRate;
    }

    double drainRate() {
        return drainRate;
    }

    /**
     * Adds up the distance flown and the ticks spent airborne, and applies the drain in a batch every
     * {@code drone.energyDrainInterval} ticks (section 5.1): the distance cost plus the hover cost for those ticks,
     * times the drone's upgrade multiplier. Ticks spent waiting at a busy station only pay the base hover cost. Docked
     * ticks don't count. What a drone owes when it docks is applied right away, before it starts charging, so
     * nothing from before charging is billed after it: a drone leaves its station at full energy. The return threshold
     * is checked right after each drain.
     */
    void tick(boolean docked) {
        boolean queued = queuedThisTick;
        queuedThisTick = false;
        if (!docked) {
            drainTicks++;
            if (FpvFeeds.isWatched(drone)) {
                drainFeedCost += ServerConfig.get(ServerConfig.CAMERA_FEED_ENERGY_PER_TICK);
            }
            if (drone.getState() == DroneState.PILOTED) {
                drainFeedCost += ServerConfig.get(ServerConfig.PILOT_ENERGY_PER_TICK);
            }
            if (queued) {
                drainQueueCost += ServerConfig.get(ServerConfig.DRONE_HOVER_ENERGY_PER_TICK);
            } else {
                double step = Math.sqrt(Mth.lengthSquared(drone.getX() - drone.xo, drone.getY() - drone.yo, drone.getZ() - drone.zo));
                if (step <= MAX_DRAIN_STEP) {
                    drainDistance += drone.isBoosting() ? step * ServerConfig.get(ServerConfig.PILOT_BOOST_DISTANCE_FACTOR) : step;
                }
                drainHoverTicks++;
            }
        }
        boolean settleOnDocking = drone.getState() == DroneState.CHARGING;
        boolean nothingOwed = drainHoverTicks == 0 && drainDistance == 0 && drainQueueCost == 0 && drainFeedCost == 0;
        if (settleOnDocking ? nothingOwed : !drone.isStaggeredTick(ServerConfig.get(ServerConfig.DRONE_ENERGY_DRAIN_INTERVAL))) {
            return;
        }
        DroneData data = drone.getDroneData();
        double cost = drainRemainder + drainQueueCost + drainFeedCost + DroneStats.energyUsageMultiplier(data)
                * (drainDistance * ServerConfig.get(ServerConfig.DRONE_ENERGY_PER_BLOCK)
                        + (double) drainHoverTicks * ServerConfig.get(ServerConfig.DRONE_HOVER_ENERGY_PER_TICK));
        cost += selfHeal(data, drainHoverTicks, cost);
        drainDistance = 0;
        drainHoverTicks = 0;
        drainQueueCost = 0;
        drainFeedCost = 0;
        drainRate = drainTicks > 0 ? (cost - drainRemainder) / drainTicks : 0;
        solarRate = drainTicks > 0 ? DroneStats.solarPerTick(data, drone.level(), BlockPos.containing(drone.getX(), drone.getEyeY(), drone.getZ())) : 0;
        double solar = solarRate * drainTicks;
        drainTicks = 0;
        cost -= solar;
        long whole = (long) cost;
        drainRemainder = cost - whole;
        long energy = Math.min(data.energy() - whole, DroneStats.maxEnergy(data));
        if (energy <= 0) {
            runOutOfEnergy(data);
            return;
        }
        DroneData drained = data.withEnergy((int) energy);
        drone.replaceData(drained);
        drone.updateLowPower();
        drone.charging().checkReturnThreshold(drained);
    }

    /**
     * Self-healing (section 2.5): heals for the airborne ticks being billed, once the drone hasn't taken damage for
     * {@code drone.selfHealDelay} ticks, and returns the FE it costs. It never heals into the return threshold (section
     * 5.2), or into 0 FE when no station is in range, and not while returning or docked.
     */
    private double selfHeal(DroneData data, int ticks, double drainCost) {
        double cost = healForEnergy(data, ticks, drainCost);
        selfHealRate = ticks > 0 ? cost / ticks : 0;
        return cost;
    }

    private double healForEnergy(DroneData data, int ticks, double drainCost) {
        float missing = drone.getMaxHealth() - drone.getHealth();
        DroneState state = drone.getState();
        if (missing <= 0 || ticks == 0 || state == DroneState.RETURNING || state == DroneState.CHARGING
                || lastDamageTime + ServerConfig.get(ServerConfig.DRONE_SELF_HEAL_DELAY) > drone.level().getGameTime()) {
            return 0;
        }
        double heal = Math.min(missing, DroneStats.selfHealPerSecond(data) * ticks / DroneStats.TICKS_PER_SECOND);
        int perHp = ServerConfig.get(ServerConfig.DRONE_SELF_HEAL_ENERGY_PER_HP);
        if (perHp > 0) {
            BlockPos station = drone.charging().nearestStation(data);
            double reserve = station == null ? 1
                    : DroneStats.returnThreshold(data, Math.sqrt(drone.distanceToSqr(ChargingStationBlockEntity.dockPosition(station))));
            heal = Math.min(heal, (data.energy() - drainCost - reserve) / perHp);
        }
        if (heal <= 0) {
            return 0;
        }
        drone.setHealth(Math.min(drone.getMaxHealth(), drone.getHealth() + (float) heal));
        return heal * perHp;
    }

    /** At 0 energy the drone drops as an item where it is, with its data intact (section 5.2). */
    private void runOutOfEnergy(DroneData data) {
        drone.charging().releaseStation();
        ItemEntity item = new ItemEntity(drone.level(), drone.getX(), drone.getY(), drone.getZ(),
                DroneItem.createStack(data.withEnergy(0).withHealth(drone.getHealth())));
        item.setDefaultPickUpDelay();
        drone.level().addFreshEntity(item);
        drone.discard();
    }

    void save(CompoundTag tag) {
        tag.putDouble(TAG_DRAIN_DISTANCE, drainDistance);
        tag.putDouble(TAG_DRAIN_REMAINDER, drainRemainder);
        tag.putInt(TAG_DRAIN_HOVER_TICKS, drainHoverTicks);
        tag.putDouble(TAG_DRAIN_QUEUE_COST, drainQueueCost);
    }

    void load(CompoundTag tag) {
        drainDistance = tag.getDouble(TAG_DRAIN_DISTANCE);
        drainRemainder = tag.getDouble(TAG_DRAIN_REMAINDER);
        drainHoverTicks = tag.getInt(TAG_DRAIN_HOVER_TICKS);
        drainQueueCost = tag.getDouble(TAG_DRAIN_QUEUE_COST);
    }
}
