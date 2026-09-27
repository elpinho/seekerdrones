package com.elpinho.seekerdrones.gametest;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.drone.DroneItem;
import com.elpinho.seekerdrones.drone.DroneState;
import com.elpinho.seekerdrones.drone.DroneStats;
import com.elpinho.seekerdrones.drone.TargetEntry;
import com.elpinho.seekerdrones.drone.UpgradeType;
import com.elpinho.seekerdrones.registry.ModBlocks;
import com.elpinho.seekerdrones.registry.ModEntityTypes;
import com.elpinho.seekerdrones.registry.ModItems;
import com.elpinho.seekerdrones.station.ChargingStationBlockEntity;
import com.elpinho.seekerdrones.station.ChargingStationRegistry;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * M5 (energy and charging) GameTests: hover and distance drain (section 5.1), running out of energy (5.2), the
 * dynamic return threshold and usability rules (5.2, 6.2), the Explosive exception, the charging queue and healing
 * (5.3), the Charging Station registry (7.4, 8.3) and the {@code /seekerdrones energy} debug command
 * (DESIGN.md sections 5, 6.2, 7.4, 9).
 *
 * All tests share the {@code seekerdrones:empty} structure template.
 *
 * <p><b>No test in this file mutates {@link ServerConfig}.</b> GameTests run in large concurrent batches sharing one
 * server process, so a temporary {@code ServerConfig.SOMETHING.set(...)} override (even restored at the end of the
 * test) is visible to every other drone ticking anywhere in the world for as long as it's in effect, including in
 * unrelated, simultaneously-running tests. An earlier version of this file overrode
 * {@code drone.energyDrainInterval} in one test and it measurably corrupted a completely different test's hover-drain
 * arithmetic (expected ~20 FE/interval, observed ~2 FE/interval, exactly matching the other test's temporary
 * override). Tests that need a drone to reliably start returning to charge instead compute the real, dynamic
 * threshold from the drone's actual distance to the station, including the wait buffer ({@link #returnThresholdEnergy};
 * see {@link #distanceBasedThreshold} for the buffer's own effect), and tests that only
 * care about the docked/charging phase (not the threshold trigger itself) skip the trigger by calling the private
 * {@code DroneEntity.startReturning} directly via reflection ({@link #forceReturning}), matching the
 * {@code invokePrivatePickUp} convention in {@code DroneGameTests}.
 */
@GameTestHolder("seekerdrones")
@PrefixGameTestTemplate(false)
public class EnergyGameTests {

    private static final String SELECTOR = "@e[type=seekerdrones:drone,distance=..3]";

    // --- 1. Hover drain (DESIGN.md section 5.1) ---

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void hoverDrainAppliesHoverCostPerInterval(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        int interval = ServerConfig.get(ServerConfig.DRONE_ENERGY_DRAIN_INTERVAL);
        int hoverPerTick = ServerConfig.get(ServerConfig.DRONE_HOVER_ENERGY_PER_TICK);

        int[] prevEnergy = {drone.getEnergy()};
        double[] distanceAccum = {0};
        Vec3[] prevPos = {null};
        List<double[]> events = new ArrayList<>();
        int ticks = interval * 3 + 10;
        trackEnergyDrain(helper, drone, ticks, prevEnergy, distanceAccum, prevPos, events, () -> {
            helper.assertTrue(events.size() >= 3, "Expected at least 3 drain events within " + ticks + " ticks, got " + events.size());
            double[] event = events.get(1); // skip the first, possibly-partial interval
            double distance = event[0];
            double energyDelta = event[1];
            helper.assertTrue(distance < 0.2, "Sanity: a stationary, non-patrolling drone shouldn't fly any distance, flew " + distance);
            double expected = interval * hoverPerTick;
            helper.assertTrue(Math.abs(energyDelta - expected) <= 2,
                    "Hover-only drain over one interval should be about " + expected + " FE, was " + energyDelta);
            helper.succeed();
        });
    }

    // --- 2. Distance drain (DESIGN.md section 5.1) ---

    @GameTest(template = "empty", timeoutTicks = 150)
    public static void distanceDrainAddsEnergyPerBlockCost(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        DroneData base = dataWithUpgrades(DroneData.createNew(), Map.of(UpgradeType.PATROL, 1));
        drone.setDroneData(base.withConfig(base.config().withPatrolRadius(Optional.of(4))));

        int interval = ServerConfig.get(ServerConfig.DRONE_ENERGY_DRAIN_INTERVAL);
        int energyPerBlock = ServerConfig.get(ServerConfig.DRONE_ENERGY_PER_BLOCK);
        int hoverPerTick = ServerConfig.get(ServerConfig.DRONE_HOVER_ENERGY_PER_TICK);

        helper.runAfterDelay(40, () -> { // let the drone settle into steady patrol flight first
            helper.assertTrue(drone.getState() == DroneState.PATROLLING, "Sanity: drone should be patrolling, state=" + drone.getState());
            int[] prevEnergy = {drone.getEnergy()};
            double[] distanceAccum = {0};
            Vec3[] prevPos = {null};
            List<double[]> events = new ArrayList<>();
            int ticks = interval * 3 + 10;
            trackEnergyDrain(helper, drone, ticks, prevEnergy, distanceAccum, prevPos, events, () -> {
                helper.assertTrue(events.size() >= 3, "Expected at least 3 drain events, got " + events.size());
                double[] event = events.get(1);
                double distance = event[0];
                double energyDelta = event[1];
                helper.assertTrue(distance > 1.0, "Sanity: a patrolling drone should have flown a real distance in one interval, flew " + distance);
                double expected = distance * energyPerBlock + (double) interval * hoverPerTick;
                helper.assertTrue(Math.abs(energyDelta - expected) <= Math.max(2, energyPerBlock * 0.2),
                        "Drain over one interval should be about distance*energyPerBlock + hover cost (" + expected
                                + " FE for " + distance + " blocks), was " + energyDelta);
                helper.succeed();
            });
        });
    }

    // --- 3. Running out of energy (DESIGN.md section 5.2) ---

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void droneDropsAsItemAtZeroEnergy(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        drone.setDroneData(drone.snapshotData().withEnergy(1).withDroneId("ZERO0001"));
        Vec3 pos = drone.position();

        helper.succeedWhen(() -> {
            if (!drone.isRemoved()) {
                throw new GameTestAssertException("Drone hasn't run out of energy yet, energy=" + drone.getEnergy());
            }
            ItemEntity droneItem = findDroppedDrone(helper, pos);
            helper.assertTrue(droneItem != null, "Expected a dropped drone item entity where the drone ran out of energy");
            DroneData dropped = DroneItem.getData(droneItem.getItem());
            helper.assertTrue(dropped.energy() == 0, "Dropped drone item should have 0 energy, was " + dropped.energy());
            helper.assertTrue(dropped.droneId().equals("ZERO0001"), "Dropped drone item should keep the same drone ID, was " + dropped.droneId());
        });
    }

    // --- 4. Return threshold, docking and full recharge (DESIGN.md sections 5.2, 5.3) ---

    @GameTest(template = "empty", timeoutTicks = 500)
    public static void droneBelowThresholdReturnsChargesAndReachesFullEnergy(GameTestHelper helper) {
        // Opposite corners of the (9x9-footprint) "empty" structure: about as much straight-line distance as the
        // template allows, for the most slack under the default 1.25x safety margin (see returnThresholdEnergy).
        // Placements must stay within the structure's own bounding box: GameTestInfo always walls off its horizontal
        // extent with invisible barriers (StructureUtils.encaseStructure), regardless of skyAccess.
        BlockPos stationRel = new BlockPos(7, 3, 7);
        ChargingStationBlockEntity station = placeChargingStation(helper, stationRel);
        station.getEnergyStorage().receiveEnergy(Integer.MAX_VALUE, false);

        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(1, 3, 1));
        int startEnergy = returnThresholdEnergy(helper, drone, stationRel);
        DroneData data = drone.snapshotData().withEnergy(startEnergy);
        drone.setDroneData(data);
        int maxEnergy = DroneStats.maxEnergy(data);
        int initialStationEnergy = station.getEnergy();

        // First, confirm the natural threshold trigger actually engages on its own (the behavior this test is
        // really about). Once that's proven, top up the energy: even the template's largest reachable distance
        // (~8.6 blocks) leaves only a thin margin under the default safety margin once the extra ticks spent
        // accelerating away and decelerating into the dock are accounted for, and running out mid-flight is a
        // different, already-covered behavior (droneWithNoUsableStationRunsOutInsteadOfReturning).
        pollUntil(helper, () -> drone.getState() == DroneState.RETURNING || drone.getState() == DroneState.CHARGING, 40, () -> {
            drone.setDroneData(drone.snapshotData().withEnergy(maxEnergy - 500));

            pollUntil(helper, () -> drone.getEnergy() >= maxEnergy && drone.getHealth() >= drone.getMaxHealth()
                    && drone.getState() == DroneState.IDLE && drone.getChargingStation() == null, 400, () -> {
                helper.assertTrue(station.getEnergy() < initialStationEnergy, "The station should have given up FE to the drone, energy=" + station.getEnergy());
                helper.succeed();
            }, () -> helper.fail("Drone never finished docking/charging to full, energy=" + drone.getEnergy() + "/" + maxEnergy
                    + " health=" + drone.getHealth() + "/" + drone.getMaxHealth() + " state=" + drone.getState()));
        }, () -> helper.fail("Drone never naturally started returning once below the computed threshold, state=" + drone.getState()
                + " energy=" + drone.getEnergy()));
    }

    // --- 4b. The wait buffer's own effect (DESIGN.md section 5.2) ---

    /**
     * Energy above the plain distance-based part of the threshold ({@code distance × energyPerBlock × safetyMargin},
     * what the whole threshold used to be before the wait buffer existed) would never have triggered a return under
     * the old formula. With {@code returnWaitBuffer} added on top, that same energy level is still at or below the
     * real threshold, so the drone should return anyway: that's the buffer's entire point (a drone can afford to
     * wait at a busy station without cutting it fine on distance alone).
     */
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void energyAboveDistanceThresholdButBelowBufferedThresholdStillReturns(GameTestHelper helper) {
        BlockPos stationRel = new BlockPos(4, 3, 7);
        ChargingStationBlockEntity station = placeChargingStation(helper, stationRel);
        station.getEnergyStorage().receiveEnergy(Integer.MAX_VALUE, false);

        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        double oldThreshold = distanceBasedThreshold(helper, drone, stationRel);
        double newThreshold = returnThreshold(helper, drone, stationRel);
        int waitBufferEnergy = ServerConfig.get(ServerConfig.DRONE_RETURN_WAIT_BUFFER) * ServerConfig.get(ServerConfig.DRONE_HOVER_ENERGY_PER_TICK);
        helper.assertTrue(waitBufferEnergy > 4, "Sanity: this test needs a non-trivial wait buffer to fit an energy level strictly between the two thresholds");
        // Comfortably above the old (unbuffered) threshold, comfortably below the new (buffered) one.
        int testEnergy = (int) oldThreshold + waitBufferEnergy / 2;
        helper.assertTrue(testEnergy > oldThreshold && testEnergy < newThreshold,
                "Sanity: test energy " + testEnergy + " should sit strictly between the old threshold (" + oldThreshold
                        + ") and the buffered one (" + newThreshold + ")");

        drone.setDroneData(drone.snapshotData().withEnergy(testEnergy));

        pollUntil(helper, () -> drone.getState() == DroneState.RETURNING || drone.getState() == DroneState.CHARGING, 30, helper::succeed,
                () -> helper.fail("A drone above the old (unbuffered) threshold but below the new, buffered one should still start returning, energy="
                        + drone.getEnergy() + " oldThreshold=" + oldThreshold + " bufferedThreshold=" + newThreshold + " state=" + drone.getState()));
    }

    // --- 5. No usable station: mismatched owner (DESIGN.md section 5.2, 6.2) ---

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void droneWithNoUsableStationRunsOutInsteadOfReturning(GameTestHelper helper) {
        UUID stationPlacer = UUID.randomUUID();
        UUID droneOwner = UUID.randomUUID();
        ChargingStationBlockEntity station = placeChargingStationOwnedBy(helper, new BlockPos(4, 3, 7), stationPlacer);
        station.getEnergyStorage().receiveEnergy(Integer.MAX_VALUE, false);

        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        drone.setDroneData(drone.snapshotData().withEnergy(50).withOwnerId(Optional.of(droneOwner)));

        helper.succeedWhen(() -> {
            if (!drone.isRemoved()) {
                DroneState state = drone.getState();
                helper.assertTrue(state != DroneState.RETURNING && state != DroneState.CHARGING,
                        "A drone with no usable station (owned by someone else) should never start returning, state=" + state);
                throw new GameTestAssertException("Drone hasn't run out of energy yet, energy=" + drone.getEnergy());
            }
        });
    }

    // --- 6. Placer-less station usability (DESIGN.md sections 5.2, 6.2) ---

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void ownedDroneCannotUsePlacerlessStation(GameTestHelper helper) {
        ChargingStationBlockEntity station = placeChargingStation(helper, new BlockPos(4, 3, 7)); // no placer recorded
        station.getEnergyStorage().receiveEnergy(Integer.MAX_VALUE, false);

        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        drone.setDroneData(drone.snapshotData().withEnergy(50).withOwnerId(Optional.of(UUID.randomUUID())));

        helper.succeedWhen(() -> {
            if (!drone.isRemoved()) {
                DroneState state = drone.getState();
                helper.assertTrue(state != DroneState.RETURNING && state != DroneState.CHARGING,
                        "An owned drone should never treat a placer-less station as usable, state=" + state);
                throw new GameTestAssertException("Drone hasn't run out of energy yet, energy=" + drone.getEnergy());
            }
        });
    }

    // --- 7. The Explosive exception (DESIGN.md section 5.2) ---

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void explosiveDroneChasingTargetKeepsChasingBelowReturnThreshold(GameTestHelper helper) {
        // Placements must stay within the structure's own bounding box: GameTestInfo always walls off its horizontal
        // extent with invisible barriers (StructureUtils.encaseStructure), regardless of skyAccess (see
        // droneBelowThresholdReturnsChargesAndReachesFullEnergy). ~7.3 blocks, just inside the default 8-block sight
        // range, for the slowest (still comfortably detectable) approach speed the Explosive chase curve allows,
        // giving the energy-drain interval (20 ticks by default) a good chance to land at least once while the drone
        // is still chasing.
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(1, 3, 1));
        Zombie zombie = helper.spawn(EntityType.ZOMBIE, new BlockPos(1, 1, 8));
        zombie.setNoAi(true);
        zombie.setNoGravity(true);
        // Shade the column so the undead target doesn't burn and die before the explosion.
        helper.setBlock(new BlockPos(1, 5, 8), Blocks.STONE);
        float startHealth = zombie.getHealth();

        ChargingStationBlockEntity station = placeChargingStation(helper, new BlockPos(7, 3, 1));
        station.getEnergyStorage().receiveEnergy(Integer.MAX_VALUE, false);

        // 3 Explosive upgrades for a strong, unambiguous blast (power 4.0, same as TNT) regardless of the exact
        // distance at which the trigger fires.
        DroneData base = dataWithUpgrades(droneTargetingZombieEntity(), Map.of(UpgradeType.EXPLOSIVE, 3));
        drone.setDroneData(base);

        helper.succeedWhen(() -> {
            if (!drone.isRemoved()) {
                if (drone.getSeekTarget() == zombie && drone.getEnergy() > 1) {
                    // Force it below the (usable, nearby) station's return threshold once it's chasing.
                    drone.setDroneData(drone.snapshotData().withEnergy(1));
                }
                DroneState state = drone.getState();
                helper.assertTrue(state != DroneState.RETURNING && state != DroneState.CHARGING,
                        "An Explosive drone chasing its target should keep chasing despite being below the return threshold, state=" + state);
                throw new GameTestAssertException("Drone hasn't reached its target yet");
            }
            helper.assertItemEntityNotPresent(ModItems.DRONE.get());
            helper.assertTrue(zombie.getHealth() < startHealth,
                    "Target should take damage from the explosion, health=" + zombie.getHealth() + " (started at " + startHealth + ")");
        });
    }

    // --- 8. Healing, and staying put with an empty station (DESIGN.md section 5.3) ---

    @GameTest(template = "empty", timeoutTicks = 250)
    public static void chargingHealsOnlyWhileStationHasEnergyThenResumes(GameTestHelper helper) {
        BlockPos stationRel = new BlockPos(4, 3, 7); // starts empty
        ChargingStationBlockEntity station = placeChargingStation(helper, stationRel);
        BlockPos stationAbs = helper.absolutePos(stationRel);

        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        int maxEnergy = DroneStats.maxEnergy(drone.snapshotData());
        drone.setDroneData(drone.snapshotData().withEnergy(maxEnergy - 500)); // only a small top-up needed once FE arrives
        drone.setHealth(drone.getMaxHealth() / 2.0F);
        forceReturning(drone, stationAbs);

        pollUntil(helper, () -> drone.getState() == DroneState.CHARGING, 100, () -> {
            int energyAtDock = drone.getEnergy();
            float healthAtDock = drone.getHealth();
            helper.runAfterDelay(15, () -> {
                helper.assertTrue(drone.getState() == DroneState.CHARGING, "Drone should remain CHARGING while the station is empty, state=" + drone.getState());
                helper.assertTrue(drone.getEnergy() == energyAtDock,
                        "Energy shouldn't change while the station has no FE, was " + drone.getEnergy() + " expected " + energyAtDock);
                helper.assertTrue(drone.getHealth() == healthAtDock,
                        "Health shouldn't heal while the station has no FE, was " + drone.getHealth() + " expected " + healthAtDock);

                station.getEnergyStorage().receiveEnergy(Integer.MAX_VALUE, false);

                pollUntil(helper, () -> drone.getEnergy() >= maxEnergy && drone.getHealth() >= drone.getMaxHealth(), 150, () -> {
                    helper.assertTrue(drone.getHealth() > healthAtDock, "Drone should have healed once FE arrived, was " + drone.getHealth());
                    helper.succeed();
                }, () -> helper.fail("Drone never finished charging after FE arrived, energy=" + drone.getEnergy() + " health=" + drone.getHealth()));
            });
        }, () -> helper.fail("Drone never reached CHARGING at the station, state=" + drone.getState()));
    }

    // --- 9. Charging queue (DESIGN.md section 5.3) ---

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void chargingStationQueuesASecondDroneUntilTheFirstFinishes(GameTestHelper helper) {
        BlockPos stationRel = new BlockPos(4, 3, 6);
        ChargingStationBlockEntity station = placeChargingStation(helper, stationRel);
        station.getEnergyStorage().receiveEnergy(Integer.MAX_VALUE, false);
        BlockPos stationAbs = helper.absolutePos(stationRel);

        DroneEntity drone1 = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 5));
        DroneEntity drone2 = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 2));
        // A ~20-tick charge window (deliberately not tiny): long enough to reliably observe "exactly one CHARGING,
        // the other waiting" over several polls, without needing anywhere near a full recharge from empty.
        int maxEnergy1 = DroneStats.maxEnergy(drone1.snapshotData());
        int maxEnergy2 = DroneStats.maxEnergy(drone2.snapshotData());
        drone1.setDroneData(drone1.snapshotData().withEnergy(maxEnergy1 - 20_000));
        drone2.setDroneData(drone2.snapshotData().withEnergy(maxEnergy2 - 20_000));
        forceReturning(drone1, stationAbs);
        forceReturning(drone2, stationAbs);

        pollUntil(helper, () -> drone1.getState() == DroneState.CHARGING || drone2.getState() == DroneState.CHARGING, 60, () -> {
            boolean drone1Charging = drone1.getState() == DroneState.CHARGING;
            boolean drone2Charging = drone2.getState() == DroneState.CHARGING;
            helper.assertTrue(drone1Charging != drone2Charging,
                    "Exactly one drone should be CHARGING at a single station, drone1=" + drone1.getState() + " drone2=" + drone2.getState());
            DroneEntity first = drone1Charging ? drone1 : drone2;
            DroneEntity second = drone1Charging ? drone2 : drone1;
            helper.assertTrue(second.getState() == DroneState.RETURNING,
                    "The other drone should be waiting nearby (RETURNING), not CHARGING, state=" + second.getState());

            pollUntil(helper, () -> first.getState() != DroneState.CHARGING, 60, () ->
                    pollUntil(helper, () -> second.getState() == DroneState.CHARGING, 60, helper::succeed,
                            () -> helper.fail("The second drone never got to charge after the first one finished")),
                    () -> helper.fail("The first drone never finished charging"));
        }, () -> helper.fail("Neither drone ever reached CHARGING"));
    }

    /**
     * The wait buffer's actual point (DESIGN.md section 5.3): a drone whose energy is only just above the real
     * (buffered) return threshold should be able to queue behind another drone that's mid-charge for a good while
     * without running dry, since {@code returnWaitBuffer} sets aside exactly that much hover-only energy for it. The
     * first drone is deliberately given very little energy (the most a full recharge can realistically buy within
     * this template's default max energy / charge rate, without touching config) so the second drone's wait is a
     * real one, not a token few ticks.
     */
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void queuedDroneWithEnergyJustAboveBufferSurvivesALongWait(GameTestHelper helper) {
        BlockPos stationRel = new BlockPos(4, 3, 6);
        ChargingStationBlockEntity station = placeChargingStation(helper, stationRel);
        station.getEnergyStorage().receiveEnergy(Integer.MAX_VALUE, false);
        BlockPos stationAbs = helper.absolutePos(stationRel);

        DroneEntity drone1 = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 5)); // 1 block: docks almost at once
        drone1.setDroneData(drone1.snapshotData().withEnergy(100)); // near-empty: the longest charge the default max energy/rate allows
        forceReturning(drone1, stationAbs);

        pollUntil(helper, () -> drone1.getState() == DroneState.CHARGING, 40, () -> {
            DroneEntity drone2 = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 2)); // a real, but modest, distance to the (busy) station
            int startEnergy2 = returnThresholdEnergy(helper, drone2, stationRel);
            int maxEnergy2 = DroneStats.maxEnergy(drone2.snapshotData());
            drone2.setDroneData(drone2.snapshotData().withEnergy(startEnergy2));

            pollUntil(helper, () -> drone2.getState() == DroneState.RETURNING, 30, () -> {
                helper.assertTrue(drone1.getState() == DroneState.CHARGING,
                        "Sanity: the first drone should still be charging when the second starts returning, so the wait is real, state=" + drone1.getState());

                // The station's own FE capacity defaults to a whole drone's max energy, so the same near-empty fill
                // that gives drone1 a long charge can't also cover drone2's full recharge afterward; keep it topped
                // up throughout (as continuous automation would) so this test is only about the drones, not the
                // station running dry a second time.
                BooleanSupplier finishedCharging = () -> {
                    helper.assertTrue(!drone2.isRemoved(),
                            "The queued drone should survive the wait on its buffered energy, ran out while waiting behind the first drone");
                    if (station.getEnergyStorage().getEnergyStored() < station.getEnergyStorage().getMaxEnergyStored() / 2) {
                        station.getEnergyStorage().receiveEnergy(Integer.MAX_VALUE, false);
                    }
                    return drone2.getState() == DroneState.IDLE && drone2.getEnergy() >= maxEnergy2 && drone2.getHealth() >= drone2.getMaxHealth();
                };
                pollUntil(helper, finishedCharging, 300, helper::succeed,
                        () -> helper.fail("Queued drone never finished charging after its wait, energy=" + drone2.getEnergy() + "/" + maxEnergy2
                                + " health=" + drone2.getHealth() + "/" + drone2.getMaxHealth() + " state=" + drone2.getState()
                                + " (drone1 state=" + drone1.getState() + ")"));
            }, () -> helper.fail("Second drone never naturally started returning on its buffered energy, state=" + drone2.getState()
                    + " energy=" + drone2.getEnergy() + " (start was " + startEnergy2 + ")"));
        }, () -> helper.fail("The first drone never started charging, state=" + drone1.getState()));
    }

    @GameTest(template = "empty", timeoutTicks = 250)
    public static void queuedDroneSwitchesToFreeStationWithinAlternateRadius(GameTestHelper helper) {
        BlockPos station1Rel = new BlockPos(4, 3, 6);
        BlockPos station2Rel = new BlockPos(7, 3, 6); // within the default alternate radius (10) of station1
        ChargingStationBlockEntity station1 = placeChargingStation(helper, station1Rel);
        ChargingStationBlockEntity station2 = placeChargingStation(helper, station2Rel);
        station1.getEnergyStorage().receiveEnergy(Integer.MAX_VALUE, false);
        station2.getEnergyStorage().receiveEnergy(Integer.MAX_VALUE, false);
        BlockPos station1Abs = helper.absolutePos(station1Rel);
        BlockPos station2Abs = helper.absolutePos(station2Rel);

        DroneEntity drone1 = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 5)); // close to station1, docks first
        int maxEnergy1 = DroneStats.maxEnergy(drone1.snapshotData());
        // A charge window comfortably longer than it takes drone2 to fly over and notice station1 is busy.
        drone1.setDroneData(drone1.snapshotData().withEnergy(maxEnergy1 - 30_000));
        forceReturning(drone1, station1Abs);

        pollUntil(helper, () -> drone1.getState() == DroneState.CHARGING, 60, () -> {
            // station1 is still nearer to drone2 than station2, so it should try station1 first, find it busy, then switch.
            DroneEntity drone2 = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 1));
            int maxEnergy2 = DroneStats.maxEnergy(drone2.snapshotData());
            drone2.setDroneData(drone2.snapshotData().withEnergy(maxEnergy2 - 500));
            forceReturning(drone2, station1Abs);

            pollUntil(helper, () -> station2Abs.equals(drone2.getChargingStation()), 120, helper::succeed,
                    () -> helper.fail("The second drone should have switched to the free alternate station, ended up at " + drone2.getChargingStation()));
        }, () -> helper.fail("The first drone never docked at station1, state=" + drone1.getState()));
    }

    // --- 10. After charging: patrol resumes, no-patrol hovers near departure (DESIGN.md section 5.3) ---

    @GameTest(template = "empty", timeoutTicks = 150)
    public static void patrolDroneResumesPatrollingAfterCharging(GameTestHelper helper) {
        BlockPos stationRel = new BlockPos(4, 3, 7);
        ChargingStationBlockEntity station = placeChargingStation(helper, stationRel);
        station.getEnergyStorage().receiveEnergy(Integer.MAX_VALUE, false);
        BlockPos stationAbs = helper.absolutePos(stationRel);

        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        DroneData base = dataWithUpgrades(DroneData.createNew(), Map.of(UpgradeType.PATROL, 1));
        int maxEnergy = DroneStats.maxEnergy(base);
        drone.setDroneData(base.withConfig(base.config().withPatrolRadius(Optional.of(4))).withEnergy(maxEnergy - 500));
        forceReturning(drone, stationAbs);

        pollUntil(helper, () -> drone.getState() == DroneState.RETURNING || drone.getState() == DroneState.CHARGING, 40, () ->
                pollUntil(helper, () -> drone.getState() == DroneState.PATROLLING, 80, helper::succeed,
                        () -> helper.fail("Patrol drone never resumed patrolling after charging, state=" + drone.getState())),
                () -> helper.fail("Drone never started returning to charge, state=" + drone.getState()));
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void nonPatrolDroneReturnsNearDeparturePositionAfterCharging(GameTestHelper helper) {
        BlockPos stationRel = new BlockPos(4, 3, 7);
        ChargingStationBlockEntity station = placeChargingStation(helper, stationRel);
        station.getEnergyStorage().receiveEnergy(Integer.MAX_VALUE, false);
        BlockPos stationAbs = helper.absolutePos(stationRel);

        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        Vec3 departure = drone.position();
        int maxEnergy = DroneStats.maxEnergy(drone.snapshotData());
        drone.setDroneData(drone.snapshotData().withEnergy(maxEnergy - 500));
        forceReturning(drone, stationAbs);

        pollUntil(helper, () -> drone.getState() == DroneState.RETURNING || drone.getState() == DroneState.CHARGING, 40, () ->
                pollUntil(helper, () -> drone.getState() == DroneState.IDLE && drone.getChargingStation() == null, 80, () ->
                        helper.runAfterDelay(100, () -> { // give it time to actually fly back from the station before checking position
                            // "Near" with some tolerance: the drone settles within drone.HOME_REACH_DISTANCE (0.5) of
                            // its recorded departure position rounded to a block, not the exact original sub-block spot.
                            helper.assertTrue(drone.position().distanceTo(departure) <= 2.0,
                                    "A non-Patrol drone should return near its departure position after charging, ended at "
                                            + drone.position() + " (departure " + departure + ")");
                            helper.succeed();
                        }),
                        () -> helper.fail("Drone never went back to IDLE after charging, state=" + drone.getState())),
                () -> helper.fail("Drone never started returning to charge, state=" + drone.getState()));
    }

    // --- 11. Charging Station registry (DESIGN.md sections 7.4, 8.3) ---

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void placingRegistersAndBreakingUnregistersTheStation(GameTestHelper helper) {
        BlockPos relative = new BlockPos(4, 3, 4);
        BlockPos abs = helper.absolutePos(relative);
        ServerLevel level = helper.getLevel();

        helper.setBlock(relative, ModBlocks.CHARGING_STATION.get());
        helper.assertTrue(ChargingStationRegistry.get(level).getStations().containsKey(abs), "Placing a Charging Station should register its position");
        helper.assertTrue(ChargingStationRegistry.get(level).getStations().get(abs).isEmpty(),
                "A station placed with no player placer should have no recorded owner");

        UUID placerUuid = UUID.randomUUID();
        Player placer = helper.makeMockPlayer(GameType.SURVIVAL);
        placer.setUUID(placerUuid);
        BlockState state = level.getBlockState(abs);
        ModBlocks.CHARGING_STATION.get().setPlacedBy(level, abs, state, placer, new ItemStack(ModItems.CHARGING_STATION.get()));

        helper.assertValueEqual(ChargingStationRegistry.get(level).getStations().get(abs), Optional.of(placerUuid),
                "setPlacedBy should register the placer's UUID in the registry");
        helper.assertValueEqual(((ChargingStationBlockEntity) level.getBlockEntity(abs)).getOwner(), Optional.of(placerUuid),
                "The block entity should also record the placer as its owner");

        helper.destroyBlock(relative);
        helper.assertFalse(ChargingStationRegistry.get(level).getStations().containsKey(abs), "Breaking the station should unregister its position");
        helper.succeed();
    }

    // --- 12. /seekerdrones energy command (ROADMAP M5) ---

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void energyCommandSetsFillsAndGetsClampedToMax(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        int maxEnergy = DroneStats.maxEnergy(drone.snapshotData());
        drone.setDroneData(drone.snapshotData().withEnergy(0));

        // Checked immediately after each command, with no tick advance in between: command dispatch is synchronous,
        // but the drone still drains energy every tick it's alive, so waiting even a few ticks before asserting an
        // exact energy value risks catching a hover-drain event and failing on an unrelated coincidence.
        runCommand(helper, "seekerdrones energy set " + (maxEnergy * 2) + " " + SELECTOR);
        helper.assertTrue(drone.getEnergy() == maxEnergy,
                "energy set above max should clamp to the drone's max energy, was " + drone.getEnergy() + "/" + maxEnergy);

        drone.setDroneData(drone.snapshotData().withEnergy(0));
        runCommand(helper, "seekerdrones energy fill " + SELECTOR);
        helper.assertTrue(drone.getEnergy() == maxEnergy, "energy fill should set energy to the drone's max, was " + drone.getEnergy());

        runCommand(helper, "seekerdrones energy get " + SELECTOR);
        helper.assertTrue(drone.getEnergy() == maxEnergy, "energy get should not change the drone's energy, was " + drone.getEnergy());
        helper.succeed();
    }

    // --- 13. Persistence: RETURNING state and station survive an NBT round-trip (DESIGN.md sections 5.2, 8.2) ---

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void returningStateAndStationSurviveNbtRoundTrip(GameTestHelper helper) {
        BlockPos stationRel = new BlockPos(4, 3, 7);
        placeChargingStation(helper, stationRel);
        BlockPos stationAbs = helper.absolutePos(stationRel);

        DroneEntity original = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        forceReturning(original, stationAbs);
        helper.assertTrue(original.getState() == DroneState.RETURNING, "Sanity: forcing RETURNING should actually set that state, was " + original.getState());
        helper.assertValueEqual(original.getChargingStation(), stationAbs, "Sanity: drone should be heading to the station");

        // saveWithoutId still writes the entity's UUID, so discard the original first (see UpgradeGameTests).
        CompoundTag tag = original.saveWithoutId(new CompoundTag());
        original.discard();
        DroneEntity loaded = ModEntityTypes.DRONE.get().create(helper.getLevel());
        loaded.load(tag);
        helper.getLevel().addFreshEntity(loaded);

        helper.assertTrue(loaded.getState() == DroneState.RETURNING, "State should survive an NBT round-trip, was " + loaded.getState());
        helper.assertValueEqual(loaded.getChargingStation(), stationAbs, "Charging station position should survive an NBT round-trip");
        helper.succeed();
    }

    // --- Helpers ---

    private static MinecraftServer server(GameTestHelper helper) {
        return helper.getLevel().getServer();
    }

    private static void runCommand(GameTestHelper helper, String command) {
        MinecraftServer server = server(helper);
        Vec3 pos = helper.absoluteVec(new Vec3(4, 3, 4));
        CommandSourceStack source = server.createCommandSourceStack()
                .withLevel(helper.getLevel())
                .withPosition(pos)
                .withPermission(4);
        server.getCommands().performPrefixedCommand(source, command);
    }

    /** A drone data set with a single {@code minecraft:zombie} entity-type target entry, otherwise default. */
    private static DroneData droneTargetingZombieEntity() {
        DroneData base = DroneData.createNew();
        return base.withConfig(base.config().withTargets(List.of(new TargetEntry(TargetEntry.Kind.ENTITY_TYPE, "minecraft:zombie"))));
    }

    private static DroneData dataWithUpgrades(DroneData data, Map<UpgradeType, Integer> upgrades) {
        return new DroneData(data.droneId(), data.groupId(), data.ownerId(), data.ownerName(), data.energy(), data.health(), upgrades, data.config());
    }

    private static ItemEntity findDroppedDrone(GameTestHelper helper, Vec3 pos) {
        List<ItemEntity> drops = helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(pos, pos).inflate(2.0));
        for (ItemEntity item : drops) {
            if (item.getItem().getItem() == ModItems.DRONE.get()) {
                return item;
            }
        }
        return null;
    }

    /** Places a Charging Station with no recorded placer (as if placed by a non-player, e.g. automation). */
    private static ChargingStationBlockEntity placeChargingStation(GameTestHelper helper, BlockPos relativePos) {
        helper.setBlock(relativePos, ModBlocks.CHARGING_STATION.get());
        BlockPos abs = helper.absolutePos(relativePos);
        return (ChargingStationBlockEntity) helper.getLevel().getBlockEntity(abs);
    }

    /** Places a Charging Station and runs it through {@code setPlacedBy} with a mock player at the given UUID. */
    private static ChargingStationBlockEntity placeChargingStationOwnedBy(GameTestHelper helper, BlockPos relativePos, UUID placerUuid) {
        ChargingStationBlockEntity station = placeChargingStation(helper, relativePos);
        Player placer = helper.makeMockPlayer(GameType.SURVIVAL);
        placer.setUUID(placerUuid);
        BlockPos abs = helper.absolutePos(relativePos);
        BlockState state = helper.getLevel().getBlockState(abs);
        ModBlocks.CHARGING_STATION.get().setPlacedBy(helper.getLevel(), abs, state, placer, new ItemStack(ModItems.CHARGING_STATION.get()));
        return station;
    }

    /**
     * The distance-based part of the return threshold (section 5.2), without the wait buffer: {@code distance ×
     * energyPerBlock × safetyMargin}. This is what the threshold used to be entirely, before the wait buffer was
     * added; kept separate so tests can demonstrate the buffer's own effect (energy above this value alone, but
     * below the real, buffered threshold, still triggers a return).
     */
    private static double distanceBasedThreshold(GameTestHelper helper, DroneEntity drone, BlockPos stationRelative) {
        BlockPos abs = helper.absolutePos(stationRelative);
        double distance = drone.position().distanceTo(ChargingStationBlockEntity.dockPosition(abs));
        return distance * ServerConfig.get(ServerConfig.DRONE_ENERGY_PER_BLOCK) * ServerConfig.get(ServerConfig.DRONE_RETURN_SAFETY_MARGIN);
    }

    /**
     * The real, dynamic return threshold (section 5.2): {@link #distanceBasedThreshold} plus
     * {@code returnWaitBuffer × hoverEnergyPerTick}, the extra hover-only energy set aside so a drone can wait at a
     * busy station (section 5.3) without running dry.
     */
    private static double returnThreshold(GameTestHelper helper, DroneEntity drone, BlockPos stationRelative) {
        return distanceBasedThreshold(helper, drone, stationRelative)
                + (double) ServerConfig.get(ServerConfig.DRONE_RETURN_WAIT_BUFFER) * ServerConfig.get(ServerConfig.DRONE_HOVER_ENERGY_PER_TICK);
    }

    /**
     * The energy (just under the real, dynamic return threshold, section 5.2) at which a drone at its current
     * position should reliably start returning to the given (already placed) station, computed from the drone's
     * actual distance and the live config, so the test doesn't depend on any hardcoded assumption about default
     * values.
     */
    private static int returnThresholdEnergy(GameTestHelper helper, DroneEntity drone, BlockPos stationRelative) {
        return Math.max(1, (int) returnThreshold(helper, drone, stationRelative) - 2);
    }

    /**
     * Invokes the private {@code DroneEntity.startReturning(BlockPos)} directly, forcing the drone into RETURNING
     * toward the given station without relying on the real energy-threshold trigger (already covered by
     * {@link #droneBelowThresholdReturnsChargesAndReachesFullEnergy}). Used by tests that are only interested in the
     * docked/charging/queueing phase, so they can pick whatever energy level gives a convenient, predictable charge
     * duration. See {@code DroneGameTests#invokePrivatePickUp} for the same convention.
     */
    private static void forceReturning(DroneEntity drone, BlockPos stationAbs) {
        try {
            Method startReturning = DroneEntity.class.getDeclaredMethod("startReturning", BlockPos.class);
            startReturning.setAccessible(true);
            startReturning.invoke(drone, stationAbs);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new RuntimeException(e);
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e.getCause());
        }
    }

    /**
     * Samples the drone's energy and flown distance every tick, recording an event (distance flown, energy lost)
     * each time the energy actually changes (a drain, section 5.1). The first recorded event may cover a partial
     * interval; callers should generally look at later events. Each step reschedules with a fresh lambda: a reused
     * one would be silently dropped by {@code GameTestInfo}'s tick-time map (see {@code UpgradeGameTests}).
     */
    private static void trackEnergyDrain(GameTestHelper helper, DroneEntity drone, int ticksRemaining, int[] prevEnergy, double[] distanceAccum,
            Vec3[] prevPos, List<double[]> events, Runnable onDone) {
        Vec3 pos = drone.position();
        if (prevPos[0] != null) {
            distanceAccum[0] += pos.distanceTo(prevPos[0]);
        }
        prevPos[0] = pos;
        int energy = drone.getEnergy();
        if (energy != prevEnergy[0]) {
            events.add(new double[] {distanceAccum[0], prevEnergy[0] - energy});
            distanceAccum[0] = 0;
            prevEnergy[0] = energy;
        }
        if (ticksRemaining <= 1) {
            onDone.run();
            return;
        }
        helper.runAtTickTime(helper.getTick() + 1,
                () -> trackEnergyDrain(helper, drone, ticksRemaining - 1, prevEnergy, distanceAccum, prevPos, events, onDone));
    }

    /**
     * Polls {@code condition} every tick for up to {@code ticksRemaining} ticks, running {@code onReady} as soon as
     * it's true, or {@code onTimeout} if it never becomes true in time.
     */
    private static void pollUntil(GameTestHelper helper, BooleanSupplier condition, int ticksRemaining, Runnable onReady, Runnable onTimeout) {
        if (condition.getAsBoolean()) {
            onReady.run();
            return;
        }
        if (ticksRemaining <= 1) {
            onTimeout.run();
            return;
        }
        helper.runAtTickTime(helper.getTick() + 1, () -> pollUntil(helper, condition, ticksRemaining - 1, onReady, onTimeout));
    }
}
