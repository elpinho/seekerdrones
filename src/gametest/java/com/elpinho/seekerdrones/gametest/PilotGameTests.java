package com.elpinho.seekerdrones.gametest;

import static com.elpinho.seekerdrones.gametest.GameTestUtil.eachTick;
import static com.elpinho.seekerdrones.gametest.GameTestUtil.placeChargingStation;
import static com.elpinho.seekerdrones.gametest.GameTestUtil.removePlayer;
import static com.elpinho.seekerdrones.gametest.GameTestUtil.spawnDrone;
import static com.elpinho.seekerdrones.gametest.GameTestUtil.spawnPlayer;
import static com.elpinho.seekerdrones.gametest.GameTestUtil.spawnStationaryZombie;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleConsumer;

import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.drone.DroneState;
import com.elpinho.seekerdrones.drone.DroneStats;
import com.elpinho.seekerdrones.drone.TargetClaims;
import com.elpinho.seekerdrones.drone.TargetEntry;
import com.elpinho.seekerdrones.drone.UpgradeType;
import com.elpinho.seekerdrones.feed.FpvFeeds;
import com.elpinho.seekerdrones.feed.PilotInput;
import com.elpinho.seekerdrones.registry.ModDataComponents;
import com.elpinho.seekerdrones.registry.ModEntityTypes;
import com.elpinho.seekerdrones.registry.ModItems;
import com.elpinho.seekerdrones.remote.RemoteCommand;
import com.elpinho.seekerdrones.remote.RemoteControl;
import com.elpinho.seekerdrones.remote.RemoteLink;
import com.elpinho.seekerdrones.station.ChargingStationBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * GameTests for remote piloting (DESIGN.md section 2.13), the PILOTED state (section 3.1) and the no-portals rule
 * (section 3.6). They call the server side ({@link FpvFeeds}, {@link RemoteControl}) directly. Tests that change
 * {@link ServerConfig} values run in their own batch and restore the value afterwards.
 */
@GameTestHolder("seekerdrones")
@PrefixGameTestTemplate(false)
public class PilotGameTests {

    /** A 48x100x48 empty template: the 9x6x9 "empty" one is walled in by barriers, which stop a flying drone. */
    private static final String ARENA = "pilot_arena";
    private static final String RANGE_BATCH = "config_pilot_remote_range_4";
    private static int originalRange;

    @BeforeBatch(batch = RANGE_BATCH)
    public static void beforeRangeBatch(ServerLevel level) {
        originalRange = ServerConfig.get(ServerConfig.REMOTE_RANGE);
        ServerConfig.REMOTE_RANGE.set(4);
    }

    @AfterBatch(batch = RANGE_BATCH)
    public static void afterRangeBatch(ServerLevel level) {
        ServerConfig.REMOTE_RANGE.set(originalRange);
    }

    // =====================================================================================================
    // 1. Starting
    // =====================================================================================================

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void startingPilotingPutsTheDroneInPilotedAndDropsTargetAndClaim(GameTestHelper helper) {
        String id = newId();
        DroneEntity drone = spawnDrone(helper, 4, 3, 7, zombieCameraData(id));
        DroneEntity rival = spawnDrone(helper, 7, 3, 7, data(newId()));
        rival.setNoGravity(true);
        Zombie zombie = spawnStationaryZombie(helper, 2, 1, 7);
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 0.5, 2);
        linkedRemote(player, drone);
        AtomicInteger ticks = new AtomicInteger();
        eachTick(helper, () -> {
            if (!drone.isPiloted()) {
                if (drone.getSeekTarget() == zombie) {
                    helper.assertFalse(TargetClaims.canClaim(rival, zombie), "Sanity: the drone should hold a claim on the zombie");
                    FpvFeeds.startFromRemote(player, id, true);
                    helper.assertTrue(drone.isPiloted(), "The drone should be piloted");
                    helper.assertValueEqual(drone.getState(), DroneState.PILOTED, "state");
                    helper.assertTrue(drone.getSeekTarget() == null, "Piloting drops the target");
                    helper.assertTrue(TargetClaims.canClaim(rival, zombie), "Piloting releases the claim");
                    helper.assertTrue(FpvFeeds.pilotOf(drone) == player, "pilotOf");
                    helper.assertTrue(FpvFeeds.pilotedDrone(player) == drone, "pilotedDrone");
                    helper.assertTrue(FpvFeeds.isViewing(player), "The pilot also has the feed");
                } else if (ticks.incrementAndGet() > 60) {
                    helper.fail("Sanity: the drone never acquired the zombie, state=" + drone.getState());
                }
                return;
            }
            if (ticks.incrementAndGet() > 25) {
                removePlayer(helper, player);
                helper.succeed();
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void pilotStartIsRefusedWithoutTheCamera(GameTestHelper helper) {
        String id = newId();
        DroneEntity drone = spawnDrone(helper, 4, 3, 5, data(id));
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 1.5, 0);
        linkedRemote(player, drone);
        FpvFeeds.startFromRemote(player, id, true);
        helper.assertFalse(drone.isPiloted(), "No FPV Camera: no piloting");
        helper.assertFalse(FpvFeeds.isViewing(player), "No FPV Camera: no feed");
        removePlayer(helper, player);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void pilotStartIsRefusedWithoutTheRemote(GameTestHelper helper) {
        String id = newId();
        DroneEntity drone = spawnDrone(helper, 4, 3, 5, cameraData(id));
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 1.5, 0);
        FpvFeeds.startFromRemote(player, id, true);
        helper.assertFalse(drone.isPiloted(), "Empty hands: no piloting");
        ItemStack other = new ItemStack(ModItems.DRONE_REMOTE.get());
        other.set(ModDataComponents.REMOTE_LINK, new RemoteLink(newId(), "", net.minecraft.world.item.DyeColor.BLUE));
        player.setItemInHand(InteractionHand.MAIN_HAND, other);
        FpvFeeds.startFromRemote(player, id, true);
        helper.assertFalse(drone.isPiloted(), "A remote linked to another drone: no piloting");
        removePlayer(helper, player);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void pilotStartIsRefusedForANonOperator(GameTestHelper helper) {
        String id = newId();
        DroneEntity drone = spawnDrone(helper, 4, 3, 5, cameraData(id).withOwnerId(Optional.of(UUID.randomUUID())));
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 1.5, 0);
        linkedRemote(player, drone);
        FpvFeeds.startFromRemote(player, id, true);
        helper.assertFalse(drone.isPiloted(), "A non-operator must not pilot");
        helper.assertFalse(FpvFeeds.isViewing(player), "No feed either");
        removePlayer(helper, player);
        helper.succeed();
    }

    // Own batch: remote.range is 4.
    @GameTest(template = "empty", timeoutTicks = 20, batch = RANGE_BATCH)
    public static void pilotStartIsRefusedOutOfRange(GameTestHelper helper) {
        String id = newId();
        DroneEntity far = spawnDrone(helper, 4, 3, 10, cameraData(id));
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 0.5, 0);
        linkedRemote(player, far);
        FpvFeeds.startFromRemote(player, id, true);
        helper.assertFalse(far.isPiloted(), "9.5 blocks with range 4: no piloting");
        removePlayer(helper, player);
        helper.succeed();
    }

    // =====================================================================================================
    // 2. One pilot at a time
    // =====================================================================================================

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void aSecondPilotIsRefusedButCanStillWatch(GameTestHelper helper) {
        String id = newId();
        DroneEntity drone = spawnDrone(helper, 4, 3, 5, cameraData(id));
        ServerPlayer first = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 1.5, 2);
        ServerPlayer second = spawnPlayer(helper, UUID.randomUUID(), 6.5, 1, 1.5, 2);
        linkedRemote(first, drone);
        linkedRemote(second, drone);
        FpvFeeds.startFromRemote(first, id, true);
        helper.assertTrue(FpvFeeds.pilotOf(drone) == first, "Sanity: the first player pilots");

        FpvFeeds.startFromRemote(second, id, true);
        helper.assertTrue(FpvFeeds.pilotOf(drone) == first, "The first pilot keeps piloting");
        helper.assertTrue(FpvFeeds.pilotedDrone(second) == null, "The second player doesn't pilot");
        helper.assertTrue(drone.isPiloted(), "The drone stays piloted");

        FpvFeeds.startFromRemote(second, id, false);
        helper.assertTrue(FpvFeeds.viewedDrone(second) == drone, "The second player can still watch");
        helper.assertTrue(FpvFeeds.pilotOf(drone) == first, "Watching doesn't take the controls");
        helper.assertTrue(FpvFeeds.pilotedDrone(first) == drone, "The first player still pilots");
        removePlayer(helper, first);
        removePlayer(helper, second);
        helper.succeed();
    }

    // =====================================================================================================
    // 3. Input moves the drone
    // =====================================================================================================

    @GameTest(template = ARENA, timeoutTicks = 40)
    public static void forwardWithYawZeroFliesAlongPositiveZAtPilotSpeed(GameTestHelper helper) {
        Pilot p = startPilot(helper, 24, 10, 10, 0);
        Vec3 start = p.drone.position();
        FpvFeeds.pilotInput(p.player, new PilotInput((byte) 1, (byte) 0, (byte) 0, false, 0));
        double expected = Math.min(ServerConfig.get(ServerConfig.PILOT_SPEED) / 20.0, ServerConfig.get(ServerConfig.DRONE_MAX_SPEED));
        measureSpeed(helper, p.drone, 15, 28, speed -> {
            Vec3 moved = p.drone.position().subtract(start);
            helper.assertTrue(moved.z > 5 && Math.abs(moved.x) < 0.5 && Math.abs(moved.y) < 0.5, "Should fly along +Z, moved " + moved);
            helper.assertTrue(Math.abs(speed - expected) < 0.04, "Settled speed " + speed + " should be about " + expected);
            removePlayer(helper, p.player);
            helper.succeed();
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 40)
    public static void forwardWithYaw90FliesAlongNegativeX(GameTestHelper helper) {
        Pilot p = startPilot(helper, 30, 10, 24, 0);
        Vec3 start = p.drone.position();
        FpvFeeds.pilotInput(p.player, new PilotInput((byte) 1, (byte) 0, (byte) 0, false, 90));
        helper.runAfterDelay(20, () -> {
            Vec3 moved = p.drone.position().subtract(start);
            helper.assertTrue(moved.x < -2 && Math.abs(moved.z) < 0.5 && Math.abs(moved.y) < 0.5, "Yaw 90 forward should fly along -X, moved " + moved);
            removePlayer(helper, p.player);
            helper.succeed();
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 40)
    public static void leftWithYawZeroFliesAlongPositiveX(GameTestHelper helper) {
        Pilot p = startPilot(helper, 10, 10, 24, 0);
        Vec3 start = p.drone.position();
        FpvFeeds.pilotInput(p.player, new PilotInput((byte) 0, (byte) 1, (byte) 0, false, 0));
        helper.runAfterDelay(20, () -> {
            Vec3 moved = p.drone.position().subtract(start);
            helper.assertTrue(moved.x > 2 && Math.abs(moved.z) < 0.5 && Math.abs(moved.y) < 0.5, "Left at yaw 0 should fly along +X, moved " + moved);
            removePlayer(helper, p.player);
            helper.succeed();
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 40)
    public static void backwardFliesOppositeToForward(GameTestHelper helper) {
        Pilot p = startPilot(helper, 24, 10, 40, 0);
        Vec3 start = p.drone.position();
        FpvFeeds.pilotInput(p.player, new PilotInput((byte) -1, (byte) 0, (byte) 0, false, 0));
        helper.runAfterDelay(20, () -> {
            Vec3 moved = p.drone.position().subtract(start);
            helper.assertTrue(moved.z < -2 && Math.abs(moved.x) < 0.5, "Back at yaw 0 should fly along -Z, moved " + moved);
            removePlayer(helper, p.player);
            helper.succeed();
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 40)
    public static void jumpClimbsAndSneakDescends(GameTestHelper helper) {
        Pilot p = startPilot(helper, 24, 30, 24, 0);
        double y0 = p.drone.getY();
        FpvFeeds.pilotInput(p.player, new PilotInput((byte) 0, (byte) 0, (byte) 1, false, 0));
        helper.runAfterDelay(15, () -> {
            double up = p.drone.getY() - y0;
            helper.assertTrue(up > 1.5, "Jump should climb, moved " + up);
            FpvFeeds.pilotInput(p.player, new PilotInput((byte) 0, (byte) 0, (byte) -1, false, 0));
            double y1 = p.drone.getY();
            helper.runAfterDelay(25, () -> {
                double down = y1 - p.drone.getY();
                helper.assertTrue(down > 1.5, "Sneak should descend, moved " + down);
                removePlayer(helper, p.player);
                helper.succeed();
            });
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 90)
    public static void boostFliesAtMinOfBoostSpeedAndMaxSpeed(GameTestHelper helper) {
        Pilot p = startPilot(helper, 24, 4, 24, 0);
        FpvFeeds.pilotInput(p.player, new PilotInput((byte) 0, (byte) 0, (byte) 1, true, 0));
        double expected = Math.min(ServerConfig.get(ServerConfig.PILOT_BOOST_SPEED) / 20.0, ServerConfig.get(ServerConfig.DRONE_MAX_SPEED));
        measureSpeed(helper, p.drone, 50, 62, speed -> {
            helper.assertTrue(p.drone.isBoosting(), "isBoosting while boosting and moving");
            helper.assertTrue(Math.abs(speed - expected) < 0.04, "Boost speed " + speed + " should be about " + expected);
            removePlayer(helper, p.player);
            helper.succeed();
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 40)
    public static void zeroInputBrakesToAStop(GameTestHelper helper) {
        Pilot p = startPilot(helper, 24, 10, 10, 0);
        FpvFeeds.pilotInput(p.player, new PilotInput((byte) 1, (byte) 0, (byte) 0, false, 0));
        helper.runAfterDelay(15, () -> {
            FpvFeeds.pilotInput(p.player, PilotInput.NONE);
            AtomicInteger waited = new AtomicInteger();
            Vec3[] last = {p.drone.position()};
            eachTick(helper, () -> {
                Vec3 now = p.drone.position();
                double step = now.distanceTo(last[0]);
                last[0] = now;
                if (waited.incrementAndGet() >= 20) {
                    helper.assertTrue(step < 0.01, "The drone should have stopped after 20 ticks of zero input, still moves " + step + "/tick");
                    removePlayer(helper, p.player);
                    helper.succeed();
                }
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void isBoostingOnlyWhileBoostAndMoving(GameTestHelper helper) {
        Pilot p = startPilot(helper, 4, 6, 4, 0);
        helper.assertFalse(p.drone.isBoosting(), "Not boosting at start");
        FpvFeeds.pilotInput(p.player, new PilotInput((byte) 1, (byte) 0, (byte) 0, true, 0));
        helper.runAfterDelay(3, () -> {
            helper.assertTrue(p.drone.isBoosting(), "Boost held and moving");
            FpvFeeds.pilotInput(p.player, new PilotInput((byte) 0, (byte) 0, (byte) 0, true, 0));
            helper.runAfterDelay(3, () -> {
                helper.assertFalse(p.drone.isBoosting(), "Boost held but not moving");
                FpvFeeds.pilotInput(p.player, new PilotInput((byte) 1, (byte) 0, (byte) 0, false, 0));
                helper.runAfterDelay(3, () -> {
                    helper.assertFalse(p.drone.isBoosting(), "Moving without boost");
                    removePlayer(helper, p.player);
                    helper.succeed();
                });
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 50)
    public static void inputFromANonPilotIsIgnored(GameTestHelper helper) {
        String id = newId();
        DroneEntity drone = spawnDrone(helper, 4, 6, 4, cameraData(id));
        drone.setNoGravity(true);
        ServerPlayer pilot = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 1.5, 2);
        ServerPlayer watcher = spawnPlayer(helper, UUID.randomUUID(), 6.5, 1, 1.5, 2);
        ServerPlayer bystander = spawnPlayer(helper, UUID.randomUUID(), 2.5, 1, 1.5, 2);
        linkedRemote(pilot, drone);
        linkedRemote(watcher, drone);
        FpvFeeds.startFromRemote(pilot, id, true);
        FpvFeeds.startFromRemote(watcher, id, false);
        Vec3 start = drone.position();
        PilotInput forward = new PilotInput((byte) 1, (byte) 0, (byte) 1, true, 0);
        FpvFeeds.pilotInput(watcher, forward);
        FpvFeeds.pilotInput(bystander, forward);
        helper.runAfterDelay(25, () -> {
            helper.assertTrue(drone.position().distanceTo(start) < 0.2, "Input from non-pilots must be ignored, moved " + drone.position().subtract(start));
            helper.assertFalse(drone.isBoosting(), "Not boosting");
            removePlayer(helper, pilot);
            removePlayer(helper, watcher);
            removePlayer(helper, bystander);
            helper.succeed();
        });
    }

    // =====================================================================================================
    // 4. The AI pauses
    // =====================================================================================================

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void pilotedDroneDoesNotAcquireATargetInSight(GameTestHelper helper) {
        String id = newId();
        DroneEntity drone = spawnDrone(helper, 4, 3, 7, zombieCameraData(id));
        spawnStationaryZombie(helper, 2, 1, 7);
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 0.5, 2);
        linkedRemote(player, drone);
        FpvFeeds.startFromRemote(player, id, true);
        AtomicInteger ticks = new AtomicInteger();
        eachTick(helper, () -> {
            helper.assertTrue(drone.getSeekTarget() == null, "A piloted drone must not acquire a target, got " + drone.getSeekTarget());
            helper.assertValueEqual(drone.getState(), DroneState.PILOTED, "state");
            if (ticks.incrementAndGet() >= 50) {
                removePlayer(helper, player);
                helper.succeed();
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void pilotedExplosiveDroneNextToATargetDoesNotExplode(GameTestHelper helper) {
        String id = newId();
        DroneEntity drone = spawnDrone(helper, 4, 3, 12, zombieCameraData(id).withUpgradeCount(UpgradeType.EXPLOSIVE, 1));
        drone.setNoGravity(true);
        spawnStationaryZombie(helper, 4, 3, 13);
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 0.5, 2);
        linkedRemote(player, drone);
        FpvFeeds.startFromRemote(player, id, true);
        AtomicInteger ticks = new AtomicInteger();
        eachTick(helper, () -> {
            helper.assertFalse(drone.isRemoved(), "A piloted Explosive drone must not explode on its own");
            helper.assertTrue(drone.getSeekTarget() == null, "No target while piloted");
            if (ticks.incrementAndGet() >= 50) {
                removePlayer(helper, player);
                helper.succeed();
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void pilotedDroneBelowTheReturnThresholdKeepsFlyingThenReturnsOnStop(GameTestHelper helper) {
        BlockPos stationRel = new BlockPos(7, 3, 7);
        placeChargingStation(helper, stationRel).getEnergyStorage().receiveEnergy(Integer.MAX_VALUE, false);
        String id = newId();
        DroneEntity drone = spawnDrone(helper, 1, 3, 1, cameraData(id));
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 1.5, 1, 0.5, 2);
        linkedRemote(player, drone);
        double distance = drone.position().distanceTo(ChargingStationBlockEntity.dockPosition(helper.absolutePos(stationRel)));
        int below = Math.max(1, (int) DroneStats.returnThreshold(drone.snapshotData(), distance) - 2);
        drone.setDroneData(drone.snapshotData().withEnergy(below));
        FpvFeeds.startFromRemote(player, id, true);
        helper.assertTrue(drone.isPiloted(), "Sanity: piloted");
        helper.assertTrue(drone.isBelowReturnThreshold(), "Sanity: the drone is below its return threshold");
        AtomicInteger ticks = new AtomicInteger();
        eachTick(helper, () -> {
            helper.assertValueEqual(drone.getState(), DroneState.PILOTED, "A piloted drone must not start returning");
            if (ticks.incrementAndGet() >= 30) {
                helper.assertTrue(drone.isBelowReturnThreshold(), "Still below the threshold");
                RemoteControl.command(player, id, RemoteCommand.STOP_PILOT);
                helper.assertTrue(drone.getState() == DroneState.RETURNING || drone.getState() == DroneState.CHARGING,
                        "After piloting, a drone below its threshold returns to charge, state=" + drone.getState());
                removePlayer(helper, player);
                helper.succeed();
            }
        });
    }

    // =====================================================================================================
    // 5. Energy
    // =====================================================================================================

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void pilotedDronePaysPilotEnergyPerTickOnTopOfTheFeedCost(GameTestHelper helper) {
        int interval = ServerConfig.get(ServerConfig.DRONE_ENERGY_DRAIN_INTERVAL);
        int pilotCost = ServerConfig.get(ServerConfig.PILOT_ENERGY_PER_TICK);
        DroneEntity piloted = spawnDrone(helper, 2, 6, 6, cameraData(newId()));
        DroneEntity watched = spawnDrone(helper, 8, 6, 6, cameraData(newId()));
        piloted.setNoGravity(true);
        watched.setNoGravity(true);
        ServerPlayer pilot = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 1.5, 2);
        ServerPlayer viewer = spawnPlayer(helper, UUID.randomUUID(), 5.5, 1, 1.5, 2);
        linkedRemote(pilot, piloted);
        FpvFeeds.startFromRemote(pilot, piloted.getDroneId(), true);
        FpvFeeds.start(viewer, watched, false);
        helper.runAfterDelay(2 * interval + 5, () -> {
            double diff = piloted.getDrainRate() - watched.getDrainRate();
            helper.assertTrue(watched.getDrainRate() > 0, "Sanity: the watched drone drains, rate " + watched.getDrainRate());
            helper.assertTrue(Math.abs(diff - pilotCost) < 0.3,
                    "Piloted drain " + piloted.getDrainRate() + " minus watched " + watched.getDrainRate() + " should be about " + pilotCost + " (pilot.energyPerTick)");
            removePlayer(helper, pilot);
            removePlayer(helper, viewer);
            helper.succeed();
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 110)
    public static void boostingMultipliesTheDistanceCostByTheBoostFactor(GameTestHelper helper) {
        int feed = ServerConfig.get(ServerConfig.CAMERA_FEED_ENERGY_PER_TICK);
        int pilotCost = ServerConfig.get(ServerConfig.PILOT_ENERGY_PER_TICK);
        double hover = ServerConfig.get(ServerConfig.DRONE_HOVER_ENERGY_PER_TICK);
        double perBlock = ServerConfig.get(ServerConfig.DRONE_ENERGY_PER_BLOCK);
        double factor = ServerConfig.get(ServerConfig.PILOT_BOOST_DISTANCE_FACTOR);
        double maxSpeed = ServerConfig.get(ServerConfig.DRONE_MAX_SPEED);
        double normalSpeed = Math.min(ServerConfig.get(ServerConfig.PILOT_SPEED) / 20.0, maxSpeed);
        double boostSpeed = Math.min(ServerConfig.get(ServerConfig.PILOT_BOOST_SPEED) / 20.0, maxSpeed);
        Pilot boosted = startPilot(helper, 12, 4, 24, 0);
        Pilot normal = startPilot(helper, 36, 4, 24, 0);
        double mult = DroneStats.energyUsageMultiplier(boosted.drone.snapshotData());
        FpvFeeds.pilotInput(boosted.player, new PilotInput((byte) 0, (byte) 0, (byte) 1, true, 0));
        FpvFeeds.pilotInput(normal.player, new PilotInput((byte) 0, (byte) 0, (byte) 1, false, 0));
        helper.runAfterDelay(85, () -> {
            double fixed = feed + pilotCost + mult * hover;
            double expectedNormal = mult * perBlock;
            double expectedBoost = mult * perBlock * factor;
            double normalPerBlock = (normal.drone.getDrainRate() - fixed) / normalSpeed;
            double boostPerBlock = (boosted.drone.getDrainRate() - fixed) / boostSpeed;
            helper.assertTrue(Math.abs(normalPerBlock - expectedNormal) < expectedNormal * 0.05,
                    "Unboosted distance cost per block " + normalPerBlock + " should be about " + expectedNormal);
            helper.assertTrue(Math.abs(boostPerBlock - expectedBoost) < expectedBoost * 0.05,
                    "Boosted distance cost per block " + boostPerBlock + " should be about " + expectedBoost + " (x" + factor + ")");
            removePlayer(helper, boosted.player);
            removePlayer(helper, normal.player);
            helper.succeed();
        });
    }

    // =====================================================================================================
    // 6. DETONATE
    // =====================================================================================================

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void detonateByThePilotExplodesAnExplosiveDroneAndEndsTheSession(GameTestHelper helper) {
        String id = newId();
        DroneEntity drone = spawnDrone(helper, 4, 3, 12, cameraData(id).withUpgradeCount(UpgradeType.EXPLOSIVE, 1));
        drone.setNoGravity(true);
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 0.5, 2);
        linkedRemote(player, drone);
        FpvFeeds.startFromRemote(player, id, true);
        helper.assertTrue(drone.isPiloted(), "Sanity: piloted");
        RemoteControl.command(player, id, RemoteCommand.DETONATE);
        helper.assertTrue(drone.isRemoved(), "The drone should be consumed by the explosion");
        helper.succeedWhen(() -> {
            helper.assertTrue(FpvFeeds.pilotedDrone(player) == null, "The piloting session should end");
            helper.assertFalse(FpvFeeds.isViewing(player), "The feed should end");
            removePlayer(helper, player);
        });
    }

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void detonateDoesNothingOnANonExplosiveDrone(GameTestHelper helper) {
        String id = newId();
        DroneEntity drone = spawnDrone(helper, 4, 3, 5, cameraData(id));
        drone.setNoGravity(true);
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 1.5, 2);
        linkedRemote(player, drone);
        FpvFeeds.startFromRemote(player, id, true);
        RemoteControl.command(player, id, RemoteCommand.DETONATE);
        helper.assertFalse(drone.isRemoved(), "A non-Explosive drone must not explode");
        helper.assertTrue(drone.isPiloted(), "Still piloted");
        helper.assertTrue(FpvFeeds.pilotedDrone(player) == drone, "The session continues");
        helper.runAfterDelay(5, () -> {
            helper.assertFalse(drone.isRemoved(), "Still there a few ticks later");
            removePlayer(helper, player);
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void detonateFromAnOperatorWhoIsNotThePilotDoesNothing(GameTestHelper helper) {
        String id = newId();
        DroneEntity drone = spawnDrone(helper, 4, 3, 12, cameraData(id).withUpgradeCount(UpgradeType.EXPLOSIVE, 1));
        drone.setNoGravity(true);
        ServerPlayer pilot = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 0.5, 2);
        ServerPlayer other = spawnPlayer(helper, UUID.randomUUID(), 6.5, 1, 0.5, 2);
        linkedRemote(pilot, drone);
        linkedRemote(other, drone);
        FpvFeeds.startFromRemote(pilot, id, true);
        RemoteControl.command(other, id, RemoteCommand.DETONATE);
        helper.assertFalse(drone.isRemoved(), "Only the pilot may detonate");
        helper.assertTrue(drone.isPiloted(), "Still piloted");
        // Not even a watcher.
        FpvFeeds.startFromRemote(other, id, false);
        RemoteControl.command(other, id, RemoteCommand.DETONATE);
        helper.assertFalse(drone.isRemoved(), "A watcher may not detonate either");
        removePlayer(helper, pilot);
        removePlayer(helper, other);
        helper.succeed();
    }

    // =====================================================================================================
    // 7. STOP_PILOT and what the AI does afterwards
    // =====================================================================================================

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void stopPilotOnADroneWithoutPatrolHoversWhereItWasReleasedAndRestsThere(GameTestHelper helper) {
        Pilot p = startPilot(helper, 4, 6, 4, 0);
        String id = p.drone.getDroneId();
        FpvFeeds.pilotInput(p.player, new PilotInput((byte) 1, (byte) 0, (byte) 0, false, 0));
        helper.runAfterDelay(20, () -> {
            Vec3 releasedAt = p.drone.position();
            RemoteControl.command(p.player, id, RemoteCommand.STOP_PILOT);
            helper.assertFalse(p.drone.isPiloted(), "Piloting should have ended");
            helper.assertValueEqual(p.drone.getState(), DroneState.IDLE, "A drone without Patrol goes IDLE");
            helper.assertTrue(FpvFeeds.pilotOf(p.drone) == null, "No pilot any more");
            helper.assertFalse(FpvFeeds.isViewing(p.player), "The pilot's feed ends too");
            BlockPos rest = p.drone.getRestPosition();
            helper.assertTrue(rest != null, "A rest position should be set");
            helper.assertTrue(Vec3.atCenterOf(rest).distanceTo(releasedAt) < 1.5, "The rest position " + rest + " should be where it was released " + releasedAt);
            helper.runAfterDelay(40, () -> {
                helper.assertValueEqual(p.drone.getState(), DroneState.IDLE, "Still idle");
                helper.assertTrue(p.drone.position().distanceTo(releasedAt) < 3.0,
                        "The drone should hover near where it was released " + releasedAt + ", is at " + p.drone.position());
                removePlayer(helper, p.player);
                helper.succeed();
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void stopPilotOnAPatrolDroneGoesBackToPatrolling(GameTestHelper helper) {
        String id = newId();
        DroneData base = cameraData(id).withUpgradeCount(UpgradeType.PATROL, 1);
        DroneEntity drone = spawnDrone(helper, 4, 3, 4, base.withConfig(base.config().withPatrolRadius(Optional.of(4))));
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 1.5, 2);
        linkedRemote(player, drone);
        FpvFeeds.startFromRemote(player, id, true);
        helper.assertValueEqual(drone.getState(), DroneState.PILOTED, "Sanity: piloted");
        RemoteControl.command(player, id, RemoteCommand.STOP_PILOT);
        helper.assertFalse(drone.isPiloted(), "Piloting ended");
        helper.succeedWhen(() -> {
            helper.assertValueEqual(drone.getState(), DroneState.PATROLLING, "The patrol resumes");
            removePlayer(helper, player);
        });
    }

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void stopPilotFromANonPilotDoesNothing(GameTestHelper helper) {
        String id = newId();
        DroneEntity drone = spawnDrone(helper, 4, 3, 5, cameraData(id));
        drone.setNoGravity(true);
        ServerPlayer pilot = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 1.5, 2);
        ServerPlayer other = spawnPlayer(helper, UUID.randomUUID(), 6.5, 1, 1.5, 2);
        linkedRemote(pilot, drone);
        linkedRemote(other, drone);
        FpvFeeds.startFromRemote(pilot, id, true);
        RemoteControl.command(other, id, RemoteCommand.STOP_PILOT);
        helper.assertTrue(drone.isPiloted(), "Only the pilot can stop the session");
        removePlayer(helper, pilot);
        removePlayer(helper, other);
        helper.succeed();
    }

    // =====================================================================================================
    // 8. Recall / Hold / Charge from any operator end piloting; Center doesn't
    // =====================================================================================================

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void recallFromAnotherOperatorEndsPilotingThenRecalls(GameTestHelper helper) {
        commandEndsPiloting(helper, RemoteCommand.RECALL, DroneState.RECALLED, false);
    }

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void holdFromAnotherOperatorEndsPilotingThenHolds(GameTestHelper helper) {
        commandEndsPiloting(helper, RemoteCommand.HOLD, DroneState.HOLDING, false);
    }

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void chargeFromAnotherOperatorEndsPilotingThenReturns(GameTestHelper helper) {
        commandEndsPiloting(helper, RemoteCommand.CHARGE, DroneState.RETURNING, true);
    }

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void recallFromThePilotEndsPilotingThenRecalls(GameTestHelper helper) {
        pilotCommandEndsPiloting(helper, RemoteCommand.RECALL, DroneState.RECALLED);
    }

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void holdFromThePilotEndsPilotingThenHolds(GameTestHelper helper) {
        pilotCommandEndsPiloting(helper, RemoteCommand.HOLD, DroneState.HOLDING);
    }

    private static void pilotCommandEndsPiloting(GameTestHelper helper, RemoteCommand command, DroneState expected) {
        String id = newId();
        DroneEntity drone = spawnDrone(helper, 4, 3, 5, cameraData(id));
        drone.setNoGravity(true);
        ServerPlayer pilot = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 1.5, 2);
        linkedRemote(pilot, drone);
        FpvFeeds.startFromRemote(pilot, id, true);
        RemoteControl.command(pilot, id, command);
        helper.assertValueEqual(drone.getState(), expected, "state after " + command);
        helper.assertTrue(FpvFeeds.pilotOf(drone) == null, "No pilot after " + command);
        helper.assertFalse(FpvFeeds.isViewing(pilot), "The pilot's feed ended");
        removePlayer(helper, pilot);
        helper.succeed();
    }

    private static void commandEndsPiloting(GameTestHelper helper, RemoteCommand command, DroneState expected, boolean withStation) {
        if (withStation) {
            placeChargingStation(helper, new BlockPos(7, 3, 7)).getEnergyStorage().receiveEnergy(Integer.MAX_VALUE, false);
        }
        String id = newId();
        DroneEntity drone = spawnDrone(helper, 4, 3, 5, cameraData(id));
        drone.setNoGravity(true);
        ServerPlayer pilot = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 1.5, 2);
        ServerPlayer other = spawnPlayer(helper, UUID.randomUUID(), 6.5, 1, 1.5, 2);
        linkedRemote(pilot, drone);
        linkedRemote(other, drone);
        FpvFeeds.startFromRemote(pilot, id, true);
        helper.assertTrue(drone.isPiloted(), "Sanity: piloted");
        RemoteControl.command(other, id, command);
        helper.assertValueEqual(drone.getState(), expected, "state after " + command);
        helper.assertTrue(FpvFeeds.pilotOf(drone) == null, "The session should be gone");
        helper.assertTrue(FpvFeeds.pilotedDrone(pilot) == null, "The pilot no longer pilots");
        helper.assertFalse(FpvFeeds.isViewing(pilot), "The pilot's feed ended");
        removePlayer(helper, pilot);
        removePlayer(helper, other);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void centerDoesNotEndPiloting(GameTestHelper helper) {
        String id = newId();
        DroneData base = cameraData(id).withUpgradeCount(UpgradeType.PATROL, 1);
        DroneEntity drone = spawnDrone(helper, 4, 3, 5, base);
        drone.setNoGravity(true);
        ServerPlayer pilot = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 1.5, 2);
        linkedRemote(pilot, drone);
        FpvFeeds.startFromRemote(pilot, id, true);
        RemoteControl.command(pilot, id, RemoteCommand.CENTER);
        helper.assertTrue(drone.isPiloted(), "Center must not end piloting");
        helper.assertTrue(FpvFeeds.pilotedDrone(pilot) == drone, "The session continues");
        removePlayer(helper, pilot);
        helper.succeed();
    }

    // =====================================================================================================
    // 9. Other ways piloting ends
    // =====================================================================================================

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void pilotingEndsWhenThePilotTakesDamage(GameTestHelper helper) {
        Pilot p = startPilot(helper, 4, 6, 4, 0);
        boolean hurt = p.player.hurt(helper.getLevel().damageSources().fellOutOfWorld(), 2.0F);
        helper.assertTrue(hurt, "Sanity: the pilot should have been hurt");
        helper.assertFalse(p.drone.isPiloted(), "Damage ends piloting");
        helper.assertValueEqual(p.drone.getState(), DroneState.IDLE, "The AI resumes");
        helper.assertTrue(FpvFeeds.pilotOf(p.drone) == null, "No pilot");
        removePlayer(helper, p.player);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void pilotingEndsWhenThePilotLogsOut(GameTestHelper helper) {
        Pilot p = startPilot(helper, 4, 6, 4, 0);
        FpvFeeds.pilotInput(p.player, new PilotInput((byte) 1, (byte) 0, (byte) 0, false, 0));
        removePlayer(helper, p.player);
        helper.succeedWhen(() -> {
            helper.assertFalse(p.drone.isPiloted(), "Logging out ends piloting");
            helper.assertTrue(FpvFeeds.pilotOf(p.drone) == null, "No pilot");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void pilotingEndsWhenTheDroneIsRemoved(GameTestHelper helper) {
        Pilot p = startPilot(helper, 4, 6, 4, 0);
        p.drone.discard();
        helper.succeedWhen(() -> {
            helper.assertTrue(FpvFeeds.pilotedDrone(p.player) == null, "The session should end with the drone");
            helper.assertFalse(FpvFeeds.isViewing(p.player), "The feed ends");
            removePlayer(helper, p.player);
        });
    }

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void pilotingAnotherDroneReplacesTheSessionAndReleasesTheFirst(GameTestHelper helper) {
        String idA = newId();
        String idB = newId();
        DroneEntity a = spawnDrone(helper, 2, 6, 5, cameraData(idA));
        DroneEntity b = spawnDrone(helper, 8, 6, 5, cameraData(idB));
        a.setNoGravity(true);
        b.setNoGravity(true);
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 1.5, 2);
        linkedRemote(player, a);
        FpvFeeds.startFromRemote(player, idA, true);
        helper.assertTrue(a.isPiloted(), "Sanity: A piloted");
        linkedRemote(player, b);
        FpvFeeds.startFromRemote(player, idB, true);
        helper.assertFalse(a.isPiloted(), "A is released when the session is replaced");
        helper.assertTrue(b.isPiloted(), "B is piloted");
        helper.assertTrue(FpvFeeds.pilotOf(a) == null, "A has no pilot");
        helper.assertTrue(FpvFeeds.pilotedDrone(player) == b, "The player pilots B");
        // Replacing the session with a plain feed of the same drone also ends the piloting.
        FpvFeeds.startFromRemote(player, idB, false);
        helper.assertFalse(b.isPiloted(), "Watching B instead ends the piloting");
        helper.assertTrue(FpvFeeds.viewedDrone(player) == b, "Still watching B");
        removePlayer(helper, player);
        helper.succeed();
    }

    // =====================================================================================================
    // 10. Portals (section 3.6)
    // =====================================================================================================

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void droneCanNeverUsePortals(GameTestHelper helper) {
        DroneEntity drone = spawnDrone(helper, 4, 3, 4, data(newId()));
        helper.assertFalse(drone.canUsePortal(false), "canUsePortal(false)");
        helper.assertFalse(drone.canUsePortal(true), "canUsePortal(true)");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void droneInsideANetherPortalStaysInTheOverworld(GameTestHelper helper) {
        DroneEntity drone = spawnDrone(helper, 4, 4, 4, data(newId()));
        drone.setNoGravity(true);
        BlockPos portal = drone.blockPosition();
        helper.getLevel().setBlock(portal, Blocks.NETHER_PORTAL.defaultBlockState(), 2);
        helper.assertTrue(helper.getLevel().getBlockState(portal).is(Blocks.NETHER_PORTAL), "Sanity: the portal block is there");
        helper.runAfterDelay(100, () -> {
            helper.assertFalse(drone.isRemoved(), "The drone must not have left the overworld");
            helper.assertTrue(drone.level() == helper.getLevel(), "The drone must still be in the overworld");
            helper.getLevel().setBlock(portal, Blocks.AIR.defaultBlockState(), 2);
            helper.succeed();
        });
    }

    // =====================================================================================================
    // 11. Persistence
    // =====================================================================================================

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void pilotedDroneReloadsAsANormalNonPilotedDrone(GameTestHelper helper) {
        Pilot p = startPilot(helper, 4, 6, 4, 0);
        FpvFeeds.pilotInput(p.player, new PilotInput((byte) 1, (byte) 0, (byte) 0, true, 0));
        helper.assertTrue(p.drone.isPiloted(), "Sanity: piloted");
        // saveWithoutId still writes the UUID: discard the original before adding the copy.
        CompoundTag tag = p.drone.saveWithoutId(new CompoundTag());
        p.drone.discard();
        DroneEntity loaded = ModEntityTypes.DRONE.get().create(helper.getLevel());
        loaded.load(tag);
        helper.getLevel().addFreshEntity(loaded);
        helper.assertFalse(loaded.isPiloted(), "A reloaded drone isn't piloted");
        helper.assertTrue(loaded.getState() != DroneState.PILOTED, "state=" + loaded.getState());
        helper.runAfterDelay(10, () -> {
            helper.assertFalse(loaded.isPiloted(), "Still not piloted a few ticks later");
            helper.assertFalse(loaded.isBoosting(), "Not boosting");
            helper.assertTrue(FpvFeeds.pilotOf(loaded) == null, "The loaded copy has no pilot");
            loaded.discard();
            removePlayer(helper, p.player);
            helper.succeed();
        });
    }

    // =====================================================================================================
    // Helpers
    // =====================================================================================================

    private record Pilot(DroneEntity drone, ServerPlayer player) {}

    /** Spawns a hovering camera drone and a server-op player who starts piloting it. */
    private static Pilot startPilot(GameTestHelper helper, int x, int y, int z, int yaw) {
        String id = newId();
        DroneEntity drone = spawnDrone(helper, x, y, z, cameraData(id));
        drone.setNoGravity(true);
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), x + 0.5, 1, 0.5, 2);
        linkedRemote(player, drone);
        FpvFeeds.startFromRemote(player, id, true);
        helper.assertTrue(drone.isPiloted(), "Sanity: the drone should be piloted");
        return new Pilot(drone, player);
    }

    /** Averages the drone's per-tick displacement between tick {@code from} (exclusive) and {@code to}, then runs the check. */
    private static void measureSpeed(GameTestHelper helper, DroneEntity drone, int from, int to, DoubleConsumer check) {
        AtomicInteger tick = new AtomicInteger();
        Vec3[] last = {drone.position()};
        double[] sum = {0};
        int[] samples = {0};
        eachTick(helper, () -> {
            int t = tick.incrementAndGet();
            Vec3 now = drone.position();
            if (t > from) {
                sum[0] += now.distanceTo(last[0]);
                samples[0]++;
            }
            last[0] = now;
            if (t == to) {
                check.accept(sum[0] / samples[0]);
            }
        });
    }

    private static String newId() {
        return "T" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private static DroneData data(String id) {
        return DroneData.createNew().withDroneId(id);
    }

    private static DroneData cameraData(String id) {
        return data(id).withUpgradeCount(UpgradeType.FPV_CAMERA, 1);
    }

    private static DroneData zombieCameraData(String id) {
        DroneData base = cameraData(id);
        return base.withConfig(base.config().withTargets(List.of(new TargetEntry(TargetEntry.Kind.ENTITY_TYPE, "minecraft:zombie"))));
    }

    private static ItemStack linkedRemote(ServerPlayer player, DroneEntity drone) {
        ItemStack remote = new ItemStack(ModItems.DRONE_REMOTE.get());
        remote.set(ModDataComponents.REMOTE_LINK, RemoteLink.of(drone.snapshotData()));
        player.setItemInHand(InteractionHand.MAIN_HAND, remote);
        return remote;
    }
}
