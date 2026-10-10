package com.elpinho.seekerdrones.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.drone.DroneState;
import com.elpinho.seekerdrones.drone.DroneStats;
import com.elpinho.seekerdrones.drone.UpgradeType;
import com.elpinho.seekerdrones.network.DroneStatusPayload;
import com.elpinho.seekerdrones.registry.ModEntityTypes;
import com.elpinho.seekerdrones.station.ChargingStationBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Self-healing GameTests (DESIGN.md sections 2.5, 5.1, 5.2, 9). The fast batch runs with 5% of max HP per second
 * (1 HP/s for the 20 HP default, so one drain interval of 20 ticks heals exactly 1 HP) and a 40 tick delay; the off
 * batch sets the percentage to 0. Both restore the config afterwards.
 */
@GameTestHolder("seekerdrones")
@PrefixGameTestTemplate(false)
public class SelfHealGameTests {

    private static final String FAST_BATCH = "config_selfheal_5pct_delay40";
    private static final String OFF_BATCH = "config_selfheal_off";
    private static final double HP_COST = 2000;

    private static double originalPercent;
    private static int originalDelay;
    private static int originalRadius;
    private static double originalPercentOff;

    @BeforeBatch(batch = FAST_BATCH)
    public static void beforeFast(ServerLevel level) {
        originalPercent = ServerConfig.get(ServerConfig.DRONE_SELF_HEAL_PERCENT_PER_SECOND);
        originalDelay = ServerConfig.get(ServerConfig.DRONE_SELF_HEAL_DELAY);
        ServerConfig.DRONE_SELF_HEAL_PERCENT_PER_SECOND.set(5.0);
        ServerConfig.DRONE_SELF_HEAL_DELAY.set(40);
        // Other tests' Charging Stations are in the same registry: only stations within a few blocks count here.
        originalRadius = ServerConfig.get(ServerConfig.DRONE_CHARGING_SEARCH_RADIUS);
        ServerConfig.DRONE_CHARGING_SEARCH_RADIUS.set(9);
    }

    @AfterBatch(batch = FAST_BATCH)
    public static void afterFast(ServerLevel level) {
        ServerConfig.DRONE_SELF_HEAL_PERCENT_PER_SECOND.set(originalPercent);
        ServerConfig.DRONE_SELF_HEAL_DELAY.set(originalDelay);
        ServerConfig.DRONE_CHARGING_SEARCH_RADIUS.set(originalRadius);
    }

    @BeforeBatch(batch = OFF_BATCH)
    public static void beforeOff(ServerLevel level) {
        originalPercentOff = ServerConfig.get(ServerConfig.DRONE_SELF_HEAL_PERCENT_PER_SECOND);
        ServerConfig.DRONE_SELF_HEAL_PERCENT_PER_SECOND.set(0.0);
    }

    @AfterBatch(batch = OFF_BATCH)
    public static void afterOff(ServerLevel level) {
        ServerConfig.DRONE_SELF_HEAL_PERCENT_PER_SECOND.set(originalPercentOff);
    }

    // --- 1. Rate, cost and cap ---

    @GameTest(template = "empty", timeoutTicks = 100, batch = FAST_BATCH)
    public static void damagedAirborneDroneHealsAtConfiguredRateAndPaysEnergyPerHp(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        setHp(drone, 4);
        List<double[]> ticks = new ArrayList<>();
        observe(helper, drone, 90, ticks, () -> {
            checkHealEvents(helper, ticks, 1.0, "no upgrades");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 200, batch = FAST_BATCH)
    public static void healingIsFasterWithEnergyUpgradesButCostPerHpIsUnchanged(GameTestHelper helper) {
        DroneEntity plain = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(2, 3, 4));
        DroneEntity upgraded = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(6, 3, 4));
        upgraded.setDroneData(EnergyGameTests.dataWithUpgrades(upgraded.snapshotData(), Map.of(UpgradeType.ENERGY, 2)));
        setHp(plain, 4);
        setHp(upgraded, 4);
        List<double[]> plainTicks = new ArrayList<>();
        List<double[]> upgradedTicks = new ArrayList<>();
        // One scheduled step records both drones (the 2 HP upgraded drone must not reach max HP before the end).
        stepBoth(helper, plain, upgraded, 70, plain.getHealth(), plain.getEnergy(), upgraded.getHealth(), upgraded.getEnergy(), plainTicks, upgradedTicks, () -> {
            checkHealEvents(helper, plainTicks, 1.0, "0 Energy upgrades");
            // 1 + 0.5 * 2 = 2x the rate: 2 HP per interval, still 2000 FE per HP.
            checkHealEvents(helper, upgradedTicks, 2.0, "2 Energy upgrades");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 80, batch = FAST_BATCH)
    public static void healingStopsAtMaxHpAndOnlyChargesForWhatWasHealed(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        float max = drone.getMaxHealth();
        setHp(drone, max - 0.3F);
        int startEnergy = drone.getEnergy();
        List<double[]> ticks = new ArrayList<>();
        observe(helper, drone, 70, ticks, () -> {
            helper.assertTrue(drone.getHealth() == max, "Should be back at max HP " + max + ", was " + drone.getHealth());
            double max_seen = ticks.stream().mapToDouble(t -> t[2]).max().orElse(0);
            helper.assertTrue(max_seen <= max, "Health must never exceed max, saw " + max_seen);
            double hover = 70.0 * ServerConfig.get(ServerConfig.DRONE_HOVER_ENERGY_PER_TICK);
            double spent = startEnergy - drone.getEnergy();
            double expected = hover + 0.3 * HP_COST;
            double slack = ServerConfig.get(ServerConfig.DRONE_ENERGY_DRAIN_INTERVAL) * ServerConfig.get(ServerConfig.DRONE_HOVER_ENERGY_PER_TICK) + 5;
            helper.assertTrue(Math.abs(spent - expected) <= slack,
                    "Energy spent should be about hover " + hover + " + 0.3 HP * 2000 = " + expected + " (+-" + slack + "), was " + spent);
            helper.succeed();
        });
    }

    // --- 4. Full health ---

    @GameTest(template = "empty", timeoutTicks = 80, batch = FAST_BATCH)
    public static void fullHealthDronePaysNoHealCost(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        int hover = ServerConfig.get(ServerConfig.DRONE_HOVER_ENERGY_PER_TICK);
        int interval = ServerConfig.get(ServerConfig.DRONE_ENERGY_DRAIN_INTERVAL);
        List<double[]> ticks = new ArrayList<>();
        observe(helper, drone, 70, ticks, () -> {
            int events = 0;
            boolean first = true;
            for (int i = 1; i < ticks.size(); i++) {
                double[] t = ticks.get(i);
                if (t[1] != 0) {
                    if (first) {
                        first = false;
                        continue;
                    }
                    events++;
                    helper.assertTrue(Math.abs(t[1] - (double) hover * interval) <= 3, "A full-health drain event should be just hover " + (hover * interval) + ", was " + t[1]);
                }
            }
            helper.assertTrue(events >= 2, "Expected at least 2 drain events after the first, got " + events);
            helper.succeed();
        });
    }

    // --- 3. Delay ---

    @GameTest(template = "empty", timeoutTicks = 110, batch = FAST_BATCH)
    public static void noHealingUntilTheDelayPassesAfterDamage(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        setHp(drone, 10);
        helper.runAfterDelay(5, () -> {
            helper.assertTrue(drone.hurt(helper.getLevel().damageSources().generic(), 1), "Sanity: the hit should land");
            float afterHit = drone.getHealth();
            helper.assertTrue(afterHit < 10, "Sanity: the hit should reduce health, was " + afterHit);
            int delay = ServerConfig.get(ServerConfig.DRONE_SELF_HEAL_DELAY);
            List<double[]> ticks = new ArrayList<>();
            observe(helper, drone, delay - 2, ticks, () -> {
                helper.assertTrue(drone.getHealth() == afterHit, "No healing within the delay, health " + afterHit + " -> " + drone.getHealth());
                EnergyGameTests.pollUntil(helper, () -> drone.getHealth() > afterHit, 50, helper::succeed,
                        () -> helper.fail("Healing never started after the delay, health=" + drone.getHealth()));
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 140, batch = FAST_BATCH)
    public static void takingDamageAgainResetsTheHealDelay(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        setHp(drone, 10);
        helper.runAfterDelay(5, () -> {
            drone.hurt(helper.getLevel().damageSources().generic(), 1);
            helper.runAfterDelay(30, () -> {
                helper.assertTrue(drone.hurt(helper.getLevel().damageSources().generic(), 1), "Sanity: the second hit should land");
                float afterSecond = drone.getHealth();
                int delay = ServerConfig.get(ServerConfig.DRONE_SELF_HEAL_DELAY);
                // Would have healed by now if the first hit still counted (30 + 20 > 40 + one drain interval).
                List<double[]> ticks = new ArrayList<>();
                observe(helper, drone, delay - 2, ticks, () -> {
                    helper.assertTrue(drone.getHealth() == afterSecond,
                            "The second hit should restart the delay, health " + afterSecond + " -> " + drone.getHealth());
                    EnergyGameTests.pollUntil(helper, () -> drone.getHealth() > afterSecond, 50, helper::succeed,
                            () -> helper.fail("Healing never started after the second delay, health=" + drone.getHealth()));
                });
            });
        });
    }

    // --- 5. Energy limits and states ---

    @GameTest(template = "empty", timeoutTicks = 120, batch = FAST_BATCH)
    public static void healingNeverTakesEnergyBelowTheReturnThresholdWhenAStationIsInRange(GameTestHelper helper) {
        BlockPos stationRel = new BlockPos(4, 3, 8);
        EnergyGameTests.placeChargingStation(helper, stationRel).getEnergyStorage().receiveEnergy(Integer.MAX_VALUE, false);
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 1));
        setHp(drone, 4);
        double distance = drone.position().distanceTo(ChargingStationBlockEntity.dockPosition(helper.absolutePos(stationRel)));
        double threshold = DroneStats.returnThreshold(drone.snapshotData(), distance);
        int hover = ServerConfig.get(ServerConfig.DRONE_HOVER_ENERGY_PER_TICK);
        int interval = ServerConfig.get(ServerConfig.DRONE_ENERGY_DRAIN_INTERVAL);
        // Room for the hover cost of one interval and 1.5 HP of healing; uncapped it would heal about 5 HP in 100 ticks.
        int startEnergy = (int) (threshold + hover * interval + 1.5 * HP_COST);
        drone.setDroneData(drone.snapshotData().withEnergy(startEnergy));
        float startHealth = drone.getHealth();
        EnergyGameTests.pollUntil(helper, () -> drone.getState() == DroneState.RETURNING || drone.getState() == DroneState.CHARGING, 110, () -> {
            double healed = drone.getHealth() - startHealth;
            double budget = (startEnergy - threshold) / HP_COST;
            helper.assertTrue(healed > 0.3, "It should heal with what it can afford above the threshold, healed " + healed);
            helper.assertTrue(healed <= budget + 0.05,
                    "Healing must stay above the return threshold: healed " + healed + " HP, but only " + budget + " HP were affordable");
            helper.succeed();
        }, () -> helper.fail("The drone never started returning; health=" + drone.getHealth() + " energy=" + drone.getEnergy() + " threshold=" + threshold));
    }

    @GameTest(template = "empty", timeoutTicks = 110, batch = FAST_BATCH)
    public static void healingNeverTakesEnergyToZeroWithoutAStation(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        setHp(drone, 4);
        drone.setDroneData(drone.snapshotData().withEnergy(5000));
        float[] prev = {drone.getHealth()};
        int[] heals = {0};
        StringBuilder trace = new StringBuilder();
        pollEveryTick(helper, 100, () -> {
            helper.assertTrue(!drone.isRemoved(), "The drone must not run out of energy because of healing (health " + drone.getHealth() + ")");
            if (drone.getHealth() > prev[0]) {
                heals[0]++;
                helper.assertTrue(drone.getEnergy() > 0, "Energy must stay above 0 right after a heal, was " + drone.getEnergy());
            }
            if (drone.tickCount % 10 == 0) trace.append(drone.getState()).append('/').append(drone.getEnergy()).append('/').append(drone.blockPosition().getY()).append(' ');
            prev[0] = drone.getHealth();
            return heals[0] >= 3;
        }, () -> helper.succeed(), () -> helper.fail("Expected at least 3 heal events, got " + heals[0] + " (health " + drone.getHealth() + ", energy " + drone.getEnergy() + ") trace " + trace));
    }

    @GameTest(template = "empty", timeoutTicks = 200, batch = FAST_BATCH)
    public static void noSelfHealingWhileReturningInTheQueue(GameTestHelper helper) {
        BlockPos stationRel = new BlockPos(4, 3, 6);
        ChargingStationBlockEntity station = EnergyGameTests.placeChargingStation(helper, stationRel);
        station.getEnergyStorage().receiveEnergy(Integer.MAX_VALUE, false);
        BlockPos stationAbs = helper.absolutePos(stationRel);
        DroneEntity holder = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 5));
        holder.setDroneData(holder.snapshotData().withEnergy(DroneStats.maxEnergy(holder.snapshotData()) - 380_000));
        EnergyGameTests.forceReturning(holder, stationAbs);
        EnergyGameTests.pollUntil(helper, () -> holder.getState() == DroneState.CHARGING, 60, () -> {
            DroneEntity queued = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(1, 3, 6));
            setHp(queued, 5);
            EnergyGameTests.forceReturning(queued, stationAbs);
            helper.runAfterDelay(20, () -> {
                helper.assertTrue(queued.getState() == DroneState.RETURNING, "Sanity: the drone should be waiting in the queue, state=" + queued.getState());
                float start = queued.getHealth();
                List<double[]> ticks = new ArrayList<>();
                observe(helper, queued, 70, ticks, () -> {
                    helper.assertTrue(queued.getState() == DroneState.RETURNING && holder.getState() == DroneState.CHARGING, "Sanity: still queued");
                    helper.assertTrue(queued.getHealth() == start, "A returning drone must not self-heal, health " + start + " -> " + queued.getHealth());
                    helper.succeed();
                });
            });
        }, () -> helper.fail("The holder never docked, state=" + holder.getState()));
    }

    @GameTest(template = "empty", timeoutTicks = 150, batch = FAST_BATCH)
    public static void noSelfHealingWhileChargingAtAStationWithoutRepairFluid(GameTestHelper helper) {
        BlockPos stationRel = new BlockPos(4, 3, 6);
        ChargingStationBlockEntity station = EnergyGameTests.placeChargingStation(helper, stationRel);
        station.getEnergyStorage().receiveEnergy(Integer.MAX_VALUE, false);
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 5));
        drone.setDroneData(drone.snapshotData().withEnergy(DroneStats.maxEnergy(drone.snapshotData()) - 380_000));
        setHp(drone, 5);
        EnergyGameTests.forceReturning(drone, helper.absolutePos(stationRel));
        EnergyGameTests.pollUntil(helper, () -> drone.getState() == DroneState.CHARGING, 60, () -> {
            helper.assertTrue(station.getFluid().isEmpty(), "Sanity: the station has no repair fluid");
            float start = drone.getHealth();
            List<double[]> ticks = new ArrayList<>();
            observe(helper, drone, 70, ticks, () -> {
                helper.assertTrue(drone.getState() == DroneState.CHARGING, "Sanity: still charging, state=" + drone.getState());
                helper.assertTrue(drone.getHealth() == start, "A docked drone must leave healing to the station, health " + start + " -> " + drone.getHealth());
                helper.succeed();
            });
        }, () -> helper.fail("The drone never docked, state=" + drone.getState()));
    }

    // --- 6. Disabled ---

    @GameTest(template = "empty", timeoutTicks = 120, batch = OFF_BATCH)
    public static void zeroPercentDisablesSelfHealing(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        setHp(drone, 10);
        int hover = ServerConfig.get(ServerConfig.DRONE_HOVER_ENERGY_PER_TICK);
        int interval = ServerConfig.get(ServerConfig.DRONE_ENERGY_DRAIN_INTERVAL);
        List<double[]> ticks = new ArrayList<>();
        observe(helper, drone, 110, ticks, () -> {
            helper.assertTrue(drone.getHealth() == 10, "With 0% the drone must not heal, health=" + drone.getHealth());
            for (int i = 1; i < ticks.size(); i++) {
                double delta = ticks.get(i)[1];
                helper.assertTrue(delta == 0 || (i < 25 && delta < 160) || Math.abs(delta - (double) hover * interval) <= 3, "Energy should only drain by hover, event was " + delta);
            }
            helper.succeed();
        });
    }

    // --- Water (default config) ---

    @GameTest(template = "empty", timeoutTicks = 130)
    public static void droneInWaterNeverHealsAtDefaultConfig(GameTestHelper helper) {
        for (int x = 3; x <= 5; x++) {
            for (int y = 2; y <= 5; y++) {
                for (int z = 3; z <= 5; z++) {
                    helper.setBlock(new BlockPos(x, y, z), net.minecraft.world.level.block.Blocks.WATER);
                }
            }
        }
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        setHp(drone, 12);
        List<double[]> ticks = new ArrayList<>();
        observe(helper, drone, 110, ticks, () -> {
            helper.assertTrue(drone.isInWater(), "Sanity: the drone should be in water");
            for (double[] t : ticks) {
                helper.assertTrue(t[0] <= 0, "A drone in water must never heal at the default config, health rose by " + t[0]);
            }
            helper.assertTrue(drone.getHealth() < 12, "Water should have damaged it, health=" + drone.getHealth());
            helper.succeed();
        });
    }

    // --- Status payload ---

    @GameTest(template = "empty", timeoutTicks = 120, batch = FAST_BATCH)
    public static void statusPayloadReportsSelfHealRateAndResetsOnDamage(GameTestHelper helper) {
        DroneEntity full = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(2, 3, 4));
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(6, 3, 4));
        setHp(drone, 4);
        helper.assertTrue(DroneStatusPayload.ofItem(drone.snapshotData()).selfHealRate() == 0, "ofItem gives 0");
        int interval = ServerConfig.get(ServerConfig.DRONE_ENERGY_DRAIN_INTERVAL);
        float[] prev = {drone.getHealth()};
        int[] events = {0};
        pollEveryTick(helper, 110, () -> {
            helper.assertTrue(DroneStatusPayload.of(full, false).selfHealRate() == 0, "A full-health drone reports 0");
            float health = drone.getHealth();
            if (health > prev[0] && ++events[0] >= 2) {
                double expected = (health - prev[0]) * HP_COST / interval;
                double rate = DroneStatusPayload.of(drone, false).selfHealRate();
                helper.assertTrue(rate > 0 && Math.abs(rate - expected) < 0.5, "Rate should be heal FE / drain ticks = " + expected + ", was " + rate);
                helper.assertTrue(drone.hurt(helper.getLevel().damageSources().generic(), 1), "Sanity: the hit should land");
                helper.assertTrue(DroneStatusPayload.of(drone, false).selfHealRate() == 0, "Rate resets to 0 right after damage");
                return true;
            }
            prev[0] = health;
            return false;
        }, helper::succeed, () -> helper.fail("Not enough heal events: " + events[0]));
    }

    // --- Helpers ---

    /** Sets health through the drone data: the entity re-applies the data's health when it first ticks. */
    private static void setHp(DroneEntity drone, float hp) {
        drone.setDroneData(drone.snapshotData().withHealth(hp));
    }

    /**
     * Checks the heal events (ticks where health rose), skipping the first, which may cover a partial interval: each
     * should heal {@code expectedHp} and cost exactly that many HP times 2000 FE plus the hover for the interval.
     */
    private static void checkHealEvents(GameTestHelper helper, List<double[]> ticks, double expectedHp, String label) {
        int hover = ServerConfig.get(ServerConfig.DRONE_HOVER_ENERGY_PER_TICK);
        int interval = ServerConfig.get(ServerConfig.DRONE_ENERGY_DRAIN_INTERVAL);
        int events = 0;
        for (double[] t : ticks) {
            if (t[0] <= 0.0001) {
                continue;
            }
            if (++events == 1) {
                continue;
            }
            helper.assertTrue(Math.abs(t[0] - expectedHp) <= 0.01, label + ": each heal event should heal " + expectedHp + " HP, healed " + t[0] + " all=" + ticks.stream().filter(x -> x[0] != 0 || x[1] != 0).map(x -> x[0] + "/" + x[1]).toList());
            double expectedCost = t[0] * HP_COST + (double) hover * interval;
            helper.assertTrue(Math.abs(t[1] - expectedCost) <= 3,
                    label + ": a heal event should cost " + expectedCost + " FE (heal " + t[0] + " HP x 2000 + hover), cost " + t[1]);
        }
        helper.assertTrue(events >= 3, label + ": expected at least 3 heal events, got " + events);
    }

    /** Records {dHealth, dEnergy (positive = lost), health} for each of the next {@code ticks} ticks. */
    private static void observe(GameTestHelper helper, DroneEntity drone, int ticks, List<double[]> out, Runnable onDone) {
        step(helper, drone, ticks, drone.getHealth(), drone.getEnergy(), out, onDone);
    }

    private static void stepBoth(GameTestHelper helper, DroneEntity a, DroneEntity b, int remaining, float ah, int ae, float bh, int be,
            List<double[]> outA, List<double[]> outB, Runnable onDone) {
        helper.runAtTickTime(helper.getTick() + 1, () -> {
            float nah = a.getHealth();
            int nae = a.getEnergy();
            float nbh = b.getHealth();
            int nbe = b.getEnergy();
            outA.add(new double[] {nah - ah, ae - nae, nah});
            outB.add(new double[] {nbh - bh, be - nbe, nbh});
            if (remaining <= 1) {
                onDone.run();
            } else {
                stepBoth(helper, a, b, remaining - 1, nah, nae, nbh, nbe, outA, outB, onDone);
            }
        });
    }

    private static void step(GameTestHelper helper, DroneEntity drone, int remaining, float prevHealth, int prevEnergy, List<double[]> out, Runnable onDone) {
        helper.runAtTickTime(helper.getTick() + 1, () -> {
            float health = drone.getHealth();
            int energy = drone.getEnergy();
            out.add(new double[] {health - prevHealth, prevEnergy - energy, health});
            if (remaining <= 1) {
                onDone.run();
            } else {
                step(helper, drone, remaining - 1, health, energy, out, onDone);
            }
        });
    }

    /** Runs {@code check} each tick until it returns true (then {@code onDone}) or the ticks run out ({@code onTimeout}). */
    private static void pollEveryTick(GameTestHelper helper, int remaining, java.util.function.BooleanSupplier check, Runnable onDone, Runnable onTimeout) {
        helper.runAtTickTime(helper.getTick() + 1, () -> {
            if (check.getAsBoolean()) {
                onDone.run();
            } else if (remaining <= 1) {
                onTimeout.run();
            } else {
                pollEveryTick(helper, remaining - 1, check, onDone, onTimeout);
            }
        });
    }
}
