package com.elpinho.seekerdrones.gametest;

import static com.elpinho.seekerdrones.gametest.GameTestUtil.clearTestBarriers;
import static com.elpinho.seekerdrones.gametest.GameTestUtil.droneData;
import static com.elpinho.seekerdrones.gametest.GameTestUtil.newDroneId;
import static com.elpinho.seekerdrones.gametest.GameTestUtil.placeChargingStation;
import static com.elpinho.seekerdrones.gametest.GameTestUtil.pollUntil;
import static com.elpinho.seekerdrones.gametest.GameTestUtil.reloadDrone;
import static com.elpinho.seekerdrones.gametest.GameTestUtil.removePlayer;
import static com.elpinho.seekerdrones.gametest.GameTestUtil.spawnDrone;
import static com.elpinho.seekerdrones.gametest.GameTestUtil.spawnPlayer;
import static com.elpinho.seekerdrones.gametest.GameTestUtil.spawnStationaryZombie;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.drone.DroneIndex;
import com.elpinho.seekerdrones.drone.DroneItem;
import com.elpinho.seekerdrones.drone.DroneOrder;
import com.elpinho.seekerdrones.drone.DroneState;
import com.elpinho.seekerdrones.drone.DroneStats;
import com.elpinho.seekerdrones.drone.TargetEntry;
import com.elpinho.seekerdrones.drone.UpgradeType;
import com.elpinho.seekerdrones.registry.ModItems;
import com.elpinho.seekerdrones.station.ChargingStationBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * How a drone carries out the Command Center's orders (DESIGN.md section 7.10): Move (flying, passive, arriving,
 * guarding, the timer's end), Strike, Release and Resume, newer orders replacing older ones, the return threshold, NBT,
 * and picking the drone up. They call the drone's public API ({@code moveTo}, {@code strike}, {@code resume}) directly;
 * what the Command Center's screen is allowed to send is in {@code CommandCenterOrdersGameTests}. Hold and Recall,
 * which moved into the same helper, are covered by {@code RemoteGameTests}.
 * <p>
 * Tests that change {@link ServerConfig} values run in their own batch and restore the value in an {@code @AfterBatch}.
 */
@GameTestHolder("seekerdrones")
@PrefixGameTestTemplate(false)
public class DroneOrdersGameTests {
    private static final String GUARD_BATCH = "config_orders_guard_60";
    private static final String TIMEOUT_BATCH = "config_orders_timeout_20";
    private static final int SHORT_GUARD = 60;
    private static final int SHORT_TIMEOUT = 20;
    private static int originalGuard;
    private static int originalTimeout;
    private static double originalStrikeFactor;
    /** Slow enough that a Strike can't get far in {@link #SHORT_TIMEOUT} ticks, which would leave the loaded chunks and freeze the drone. */
    private static final double SLOW_STRIKE = 0.05;

    @BeforeBatch(batch = GUARD_BATCH)
    public static void beforeGuard(ServerLevel level) {
        originalGuard = ServerConfig.get(ServerConfig.COMMAND_CENTER_GUARD_TICKS);
        ServerConfig.COMMAND_CENTER_GUARD_TICKS.set(SHORT_GUARD);
    }

    @AfterBatch(batch = GUARD_BATCH)
    public static void afterGuard(ServerLevel level) {
        ServerConfig.COMMAND_CENTER_GUARD_TICKS.set(originalGuard);
    }

    @BeforeBatch(batch = TIMEOUT_BATCH)
    public static void beforeTimeout(ServerLevel level) {
        originalTimeout = ServerConfig.get(ServerConfig.COMMAND_CENTER_MOVE_TIMEOUT_TICKS);
        ServerConfig.COMMAND_CENTER_MOVE_TIMEOUT_TICKS.set(SHORT_TIMEOUT);
        originalStrikeFactor = ServerConfig.get(ServerConfig.COMMAND_CENTER_STRIKE_SPEED_FACTOR);
        ServerConfig.COMMAND_CENTER_STRIKE_SPEED_FACTOR.set(SLOW_STRIKE);
    }

    @AfterBatch(batch = TIMEOUT_BATCH)
    public static void afterTimeout(ServerLevel level) {
        ServerConfig.COMMAND_CENTER_MOVE_TIMEOUT_TICKS.set(originalTimeout);
        ServerConfig.COMMAND_CENTER_STRIKE_SPEED_FACTOR.set(originalStrikeFactor);
    }

    // =====================================================================================================
    // Helpers
    // =====================================================================================================

    private static int height() {
        return ServerConfig.get(ServerConfig.COMMAND_CENTER_MOVE_HEIGHT);
    }

    /** The top of a block at {@code (x, z)} with its surface one block above the structure's floor. */
    private static Vec3 top(GameTestHelper helper, double x, double z) {
        return helper.absoluteVec(new Vec3(x, 1, z));
    }

    /** The structure-relative column (4, 4) as a Move spot: a drone spawned by {@link #spawnAtSpot} is exactly there. */
    private static Vec3 centerTop(GameTestHelper helper) {
        return top(helper, 4.5, 4.5);
    }

    private static Vec3 spotOf(Vec3 top) {
        return top.add(0, height(), 0);
    }

    /** Spawns a drone exactly at the Move spot of {@link #centerTop}, so it starts guarding on its first tick. */
    private static DroneEntity spawnAtSpot(GameTestHelper helper, DroneData data) {
        return spawnDrone(helper, 4, 1 + height(), 4, data);
    }

    private static DroneData withZombieTarget(DroneData data) {
        return data.withConfig(data.config().withTargets(List.of(new TargetEntry(TargetEntry.Kind.ENTITY_TYPE, "minecraft:zombie"))));
    }

    private static DroneData explosive(DroneData data) {
        return data.withUpgradeCount(UpgradeType.EXPLOSIVE, 1);
    }

    private static DroneData patrolling(GameTestHelper helper, DroneData data, BlockPos centerRel) {
        DroneData withUpgrade = data.withUpgradeCount(UpgradeType.PATROL, 1);
        return withUpgrade.withConfig(withUpgrade.config().withPatrolRadius(Optional.of(4))
                .withPatrolCenter(Optional.of(GlobalPos.of(helper.getLevel().dimension(), helper.absolutePos(centerRel)))));
    }

    /** A drone that is guarding, reached by spawning it at the spot and waiting a tick or two. */
    private static void withGuardingDrone(GameTestHelper helper, DroneEntity drone, Runnable onGuarding) {
        drone.moveTo(centerTop(helper));
        pollUntil(helper, () -> drone.getState() == DroneState.GUARDING, 10, onGuarding,
                () -> helper.fail("The drone at the spot never started guarding, state=" + drone.getState() + " order=" + drone.getOrder()));
    }

    private static boolean chasing(DroneEntity drone) {
        return drone.getState() == DroneState.CHASING || drone.getState() == DroneState.FOLLOWING;
    }

    // =====================================================================================================
    // 1. Move (section 7.10)
    // =====================================================================================================

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void moveFliesToTheSpotAboveTheTopBlockAtCruisePaceThenGuards(GameTestHelper helper) {
        clearTestBarriers(helper);
        DroneEntity drone = spawnDrone(helper, 0, 1, 0, droneData(newDroneId()));
        Vec3 top = top(helper, 7.5, 7.5);
        drone.moveTo(top);
        helper.assertValueEqual(drone.getState(), DroneState.MOVING, "state right after moveTo");
        DroneOrder.Info info = drone.getOrder();
        helper.assertTrue(info != null && info.order() == DroneOrder.MOVE, "A Move order, got " + info);
        helper.assertTrue(info.spot().equals(spotOf(top)), "The spot is moveHeight (" + height() + ") above the top block " + top + ", got " + info.spot());
        helper.assertTrue(info.ticksLeft() == ServerConfig.get(ServerConfig.COMMAND_CENTER_MOVE_TIMEOUT_TICKS), "It has moveTimeoutTicks to get there, got " + info.ticksLeft());
        double cruise = ServerConfig.get(ServerConfig.DRONE_CRUISE_SPEED);
        double[] fastest = {0};
        pollUntil(helper, () -> {
            if (drone.getState() == DroneState.MOVING) {
                double speed = drone.getDeltaMovement().length();
                fastest[0] = Math.max(fastest[0], speed);
                helper.assertTrue(speed <= cruise + 0.05, "A Move flies at cruise pace (" + cruise + " blocks/tick), but moved at " + speed);
            }
            return drone.getState() == DroneState.GUARDING;
        }, 190, () -> {
            helper.assertTrue(fastest[0] >= cruise * 0.75, "It should have reached about cruise pace on the way, fastest was " + fastest[0]);
            helper.assertTrue(drone.position().distanceTo(spotOf(top)) <= 1.1, "On arrival it is at the spot, but is " + drone.position().distanceTo(spotOf(top)) + " away");
            DroneOrder.Info guard = drone.getOrder();
            int guardTicks = ServerConfig.get(ServerConfig.COMMAND_CENTER_GUARD_TICKS);
            helper.assertTrue(guard != null && guard.order() == DroneOrder.GUARD && guard.ticksLeft() <= guardTicks && guard.ticksLeft() > guardTicks - 5,
                    "It guards for guardTicks (" + guardTicks + "), got " + guard);
            helper.assertTrue(drone.hasResumableOrder(), "Guarding is an order Resume ends");
            helper.succeed();
        }, () -> helper.fail("The drone never reached the spot, state=" + drone.getState() + " at " + drone.position() + " order=" + drone.getOrder()));
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void aMovingDroneIsPassiveAndNeverAcquiresATarget(GameTestHelper helper) {
        clearTestBarriers(helper);
        DroneEntity drone = spawnDrone(helper, 0, 1, 0, withZombieTarget(droneData(newDroneId())));
        Zombie zombie = spawnStationaryZombie(helper, 3, 1, 5);
        helper.assertTrue(zombie.isAlive(), "Fixture: the zombie is there");
        drone.moveTo(top(helper, 7.5, 7.5));
        AtomicInteger moving = new AtomicInteger();
        pollUntil(helper, () -> {
            if (drone.getState() == DroneState.MOVING) {
                moving.incrementAndGet();
                helper.assertTrue(drone.getSeekTarget() == null, "A moving drone must not acquire a target, but has " + drone.getSeekTarget());
                helper.assertTrue(drone.isPassive(), "A moving drone is passive");
            }
            return drone.getState() == DroneState.GUARDING;
        }, 190, () -> {
            helper.assertTrue(moving.get() >= 20, "Fixture: it should have been moving for a while (saw " + moving.get() + " ticks) with the zombie in sight");
            helper.succeed();
        }, () -> helper.fail("The drone never arrived, state=" + drone.getState()));
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void aGuardingDroneChasesATargetAndFliesBackToTheSpot(GameTestHelper helper) {
        clearTestBarriers(helper);
        DroneEntity drone = spawnAtSpot(helper, withZombieTarget(droneData(newDroneId())));
        Vec3 spot = spotOf(centerTop(helper));
        withGuardingDrone(helper, drone, () -> {
            helper.assertTrue(!drone.isPassive(), "A guarding drone isn't passive: it scans and fights");
            Zombie zombie = spawnStationaryZombie(helper, 4, 1, 8);
            double[] farthest = {0};
            pollUntil(helper, () -> {
                farthest[0] = Math.max(farthest[0], drone.position().distanceTo(spot));
                return chasing(drone) && drone.getSeekTarget() == zombie && farthest[0] > 2.5;
            }, 150, () -> {
                helper.assertTrue(drone.getOrder() != null && drone.getOrder().order() == DroneOrder.GUARD, "The guard order lives on while chasing, got " + drone.getOrder());
                zombie.discard();
                pollUntil(helper, () -> drone.getState() == DroneState.GUARDING && drone.position().distanceTo(spot) <= 1.1, 250, () -> {
                    helper.assertTrue(drone.getOrder() != null && drone.getOrder().order() == DroneOrder.GUARD, "Still guarding the spot, got " + drone.getOrder());
                    helper.succeed();
                }, () -> helper.fail("The drone never came back to the spot, state=" + drone.getState() + " at " + drone.position()));
            }, () -> helper.fail("The guarding drone never chased the zombie, state=" + drone.getState() + " target=" + drone.getSeekTarget() + " farthest=" + farthest[0]));
        });
    }

    // =====================================================================================================
    // 2. Strike
    // =====================================================================================================

    @GameTest(template = "empty", timeoutTicks = 150)
    public static void strikeRushesAtStrikeSpeedFactorTimesMaxSpeedIgnoresTargetsAndDetonatesNearTheTopBlock(GameTestHelper helper) {
        clearTestBarriers(helper);
        DroneEntity drone = spawnDrone(helper, 0, 1, 0, explosive(withZombieTarget(droneData(newDroneId()))));
        spawnStationaryZombie(helper, 4, 1, 4);
        Vec3 top = top(helper, 8.5, 8.5);
        helper.assertTrue(drone.strike(top), "An Explosive drone takes a Strike");
        helper.assertValueEqual(drone.getState(), DroneState.STRIKING, "state right after strike");
        helper.assertTrue(drone.getOrder() != null && drone.getOrder().order() == DroneOrder.STRIKE && drone.getOrder().spot().equals(top),
                "A Strike order at the top block, got " + drone.getOrder());
        double striking = ServerConfig.get(ServerConfig.COMMAND_CENTER_STRIKE_SPEED_FACTOR) * ServerConfig.get(ServerConfig.DRONE_MAX_SPEED);
        double trigger = ServerConfig.get(ServerConfig.DRONE_EXPLOSION_TRIGGER_DISTANCE);
        double[] fastest = {0};
        Vec3[] last = {drone.position()};
        pollUntil(helper, () -> {
            if (drone.isRemoved()) {
                return true;
            }
            helper.assertValueEqual(drone.getState(), DroneState.STRIKING, "state while striking");
            helper.assertTrue(drone.getSeekTarget() == null, "A striking drone ignores targets, but has " + drone.getSeekTarget());
            double speed = drone.getDeltaMovement().length();
            fastest[0] = Math.max(fastest[0], speed);
            helper.assertTrue(speed <= striking + 0.02, "A Strike flies at strikeSpeedFactor x maxSpeed = " + striking + ", but moved at " + speed);
            last[0] = drone.position();
            return false;
        }, 140, () -> {
            helper.assertTrue(fastest[0] >= striking - 0.1, "It should fly at about " + striking + " the whole way, fastest was " + fastest[0]);
            double halfHeight = drone.getBbHeight() / 2;
            double away = last[0].add(0, halfHeight, 0).distanceTo(top);
            helper.assertTrue(away <= trigger + striking + 0.5, "It detonates near the top block, last seen " + away + " away");
            helper.succeed();
        }, () -> helper.fail("The striking drone never detonated, state=" + drone.getState() + " at " + drone.position() + " order=" + drone.getOrder()));
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void strikeIsRefusedWithoutAnExplosiveUpgrade(GameTestHelper helper) {
        clearTestBarriers(helper);
        DroneEntity drone = spawnDrone(helper, 2, 1, 2, droneData(newDroneId()));
        helper.assertFalse(drone.strike(top(helper, 7.5, 7.5)), "No Explosive: refused");
        helper.assertTrue(drone.getState() == DroneState.IDLE && drone.getOrder() == null, "Nothing changes, state=" + drone.getState());
        helper.succeed();
    }

    // =====================================================================================================
    // 3. Timeouts (batch: moveTimeoutTicks = 20)
    // =====================================================================================================

    @GameTest(template = "empty", timeoutTicks = 100, batch = TIMEOUT_BATCH)
    public static void aMoveThatHasNotArrivedWithinTheTimeoutIsDropped(GameTestHelper helper) {
        clearTestBarriers(helper);
        helper.assertTrue(ServerConfig.get(ServerConfig.COMMAND_CENTER_MOVE_TIMEOUT_TICKS) == SHORT_TIMEOUT, "Fixture: timeout in this batch");
        DroneEntity drone = spawnDrone(helper, 0, 1, 0, droneData(newDroneId()));
        // 40 blocks away at cruise pace (0.4 blocks/tick) takes over 100 ticks.
        drone.moveTo(top(helper, 40.5, 4.5));
        helper.assertValueEqual(drone.getState(), DroneState.MOVING, "state right after moveTo");
        AtomicInteger moving = new AtomicInteger();
        pollUntil(helper, () -> {
            if (drone.getState() == DroneState.MOVING) {
                moving.incrementAndGet();
            }
            helper.assertTrue(drone.getState() != DroneState.GUARDING, "It can't have arrived");
            return drone.getState() != DroneState.MOVING;
        }, 60, () -> {
            helper.assertTrue(moving.get() >= SHORT_TIMEOUT - 3 && moving.get() <= SHORT_TIMEOUT + 3, "It moves for about moveTimeoutTicks (" + SHORT_TIMEOUT + "), but moved for " + moving.get());
            helper.assertValueEqual(drone.getState(), DroneState.IDLE, "state after the timeout");
            helper.assertTrue(drone.getOrder() == null && !drone.hasResumableOrder(), "The order is dropped, got " + drone.getOrder());
            helper.succeed();
        }, () -> helper.fail("The Move was never dropped, state=" + drone.getState() + " order=" + drone.getOrder()));
    }

    @GameTest(template = "empty", timeoutTicks = 100, batch = TIMEOUT_BATCH)
    public static void aStrikeThatHasNotArrivedWithinTheTimeoutIsDroppedWithoutDetonating(GameTestHelper helper) {
        clearTestBarriers(helper);
        DroneEntity drone = spawnDrone(helper, 0, 1, 0, explosive(droneData(newDroneId())));
        // 12 blocks away at strikeSpeedFactor 0.05 (0.06 blocks/tick in this batch) takes far longer than the timeout.
        helper.assertTrue(drone.strike(top(helper, 8.5, 8.5)), "Fixture: Strike taken");
        pollUntil(helper, () -> drone.getState() != DroneState.STRIKING, 60, () -> {
            helper.assertTrue(!drone.isRemoved(), "A dropped Strike must not detonate");
            helper.assertValueEqual(drone.getState(), DroneState.IDLE, "state after the timeout");
            helper.assertTrue(drone.getOrder() == null, "The order is dropped, got " + drone.getOrder());
            helper.succeed();
        }, () -> helper.fail("The Strike was never dropped, state=" + drone.getState() + " order=" + drone.getOrder()));
    }

    // =====================================================================================================
    // 4. The guard timer (batch: guardTicks = 60)
    // =====================================================================================================

    @GameTest(template = "empty", timeoutTicks = 200, batch = GUARD_BATCH)
    public static void whenTheGuardTimerEndsADroneWithoutPatrolStaysAtTheSpotAsItsRestPosition(GameTestHelper helper) {
        clearTestBarriers(helper);
        helper.assertTrue(ServerConfig.get(ServerConfig.COMMAND_CENTER_GUARD_TICKS) == SHORT_GUARD, "Fixture: guard time in this batch");
        DroneEntity drone = spawnAtSpot(helper, droneData(newDroneId()));
        Vec3 spot = spotOf(centerTop(helper));
        withGuardingDrone(helper, drone, () -> pollUntil(helper, () -> drone.getOrder() == null, 90, () -> {
            helper.assertValueEqual(drone.getState(), DroneState.IDLE, "state when the timer ends");
            helper.assertValueEqual(drone.getRestPosition(), BlockPos.containing(spot), "the rest position becomes the spot");
            helper.runAfterDelay(25, () -> {
                helper.assertTrue(drone.position().distanceTo(spot) <= 2.0, "A drone without Patrol stays at the spot, but is " + drone.position().distanceTo(spot) + " away");
                helper.assertValueEqual(drone.getState(), DroneState.IDLE, "state later");
                helper.succeed();
            });
        }, () -> helper.fail("The guard timer never ended, order=" + drone.getOrder())));
    }

    @GameTest(template = "empty", timeoutTicks = 250, batch = GUARD_BATCH)
    public static void whenTheGuardTimerEndsADroneWithPatrolPatrolsAgain(GameTestHelper helper) {
        clearTestBarriers(helper);
        DroneEntity drone = spawnAtSpot(helper, patrolling(helper, droneData(newDroneId()), new BlockPos(4, 1 + height(), 4)));
        withGuardingDrone(helper, drone, () -> pollUntil(helper, () -> drone.getOrder() == null, 90, () -> {
            helper.assertTrue(drone.getState() != DroneState.GUARDING && !drone.hasResumableOrder(), "The guard is over, state=" + drone.getState());
            pollUntil(helper, () -> drone.getState() == DroneState.PATROLLING, 60, helper::succeed,
                    () -> helper.fail("A drone with Patrol should fly its circle after guarding, state=" + drone.getState()));
        }, () -> helper.fail("The guard timer never ended, order=" + drone.getOrder())));
    }

    @GameTest(template = "empty", timeoutTicks = 400, batch = GUARD_BATCH)
    public static void aGuardTimerThatEndsMidChaseLetsTheChaseFinishAndDoesNotReturnToGuarding(GameTestHelper helper) {
        clearTestBarriers(helper);
        DroneEntity drone = spawnAtSpot(helper, withZombieTarget(droneData(newDroneId())));
        withGuardingDrone(helper, drone, () -> {
            Zombie zombie = spawnStationaryZombie(helper, 4, 1, 8);
            // The timer runs on during the chase and ends while it's still going.
            pollUntil(helper, () -> drone.getOrder() == null, 100, () -> {
                helper.assertTrue(chasing(drone) && drone.getSeekTarget() == zombie, "The chase goes on when the timer ends, state=" + drone.getState() + " target=" + drone.getSeekTarget());
                helper.assertTrue(!drone.hasResumableOrder(), "The guard order is gone");
                helper.runAfterDelay(15, () -> {
                    helper.assertTrue(chasing(drone), "Still chasing 15 ticks later, state=" + drone.getState());
                    zombie.discard();
                    pollUntil(helper, () -> !chasing(drone), 60, () -> {
                        helper.assertTrue(drone.getState() != DroneState.GUARDING && drone.getOrder() == null, "After the chase it is back to normal, not guarding, state=" + drone.getState());
                        helper.runAfterDelay(30, () -> {
                            helper.assertTrue(drone.getState() != DroneState.GUARDING && drone.getOrder() == null, "...and stays so, state=" + drone.getState());
                            helper.succeed();
                        });
                    }, () -> helper.fail("The chase never ended, state=" + drone.getState()));
                });
            }, () -> helper.fail("The guard timer never ended during the chase, state=" + drone.getState() + " order=" + drone.getOrder()));
        });
    }

    // =====================================================================================================
    // 5. Replacing, resuming and releasing
    // =====================================================================================================

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void aNewerOrderReplacesTheCurrentOne(GameTestHelper helper) {
        clearTestBarriers(helper);
        DroneEntity drone = spawnDrone(helper, 2, 1, 2, explosive(droneData(newDroneId())));
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 1.5, 0);
        try {
            Vec3 top = top(helper, 7.5, 7.5);
            drone.moveTo(top);
            drone.hold();
            helper.assertTrue(drone.getState() == DroneState.HOLDING && drone.getOrder() == null, "Hold replaces a Move, state=" + drone.getState() + " order=" + drone.getOrder());
            drone.moveTo(top);
            helper.assertTrue(drone.getState() == DroneState.MOVING && drone.getOrder() != null && drone.getOrder().order() == DroneOrder.MOVE, "A Move replaces a Hold, state=" + drone.getState());
            drone.strike(top);
            helper.assertTrue(drone.getState() == DroneState.STRIKING && drone.getOrder().order() == DroneOrder.STRIKE, "A Strike replaces a Move, state=" + drone.getState());
            drone.moveTo(top);
            helper.assertTrue(drone.getState() == DroneState.MOVING && drone.getOrder().order() == DroneOrder.MOVE, "A Move replaces a Strike, state=" + drone.getState());
            drone.recall(player);
            helper.assertTrue(drone.getState() == DroneState.RECALLED && drone.getOrder() == null, "A Recall replaces a Move, state=" + drone.getState() + " order=" + drone.getOrder());
            drone.moveTo(top);
            helper.assertTrue(drone.getState() == DroneState.MOVING && drone.getOrder() != null, "A Move replaces a Recall, state=" + drone.getState());
        } finally {
            removePlayer(helper, player);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void aMoveAfterAMoveReplacesTheSpot(GameTestHelper helper) {
        clearTestBarriers(helper);
        DroneEntity drone = spawnDrone(helper, 2, 1, 2, droneData(newDroneId()));
        drone.moveTo(top(helper, 7.5, 7.5));
        Vec3 second = top(helper, 1.5, 7.5);
        drone.moveTo(second);
        helper.assertTrue(drone.getOrder() != null && drone.getOrder().spot().equals(spotOf(second)), "The last order sent wins, got " + drone.getOrder());
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void resumeEndsAMoveAndAStrikeAtOnce(GameTestHelper helper) {
        clearTestBarriers(helper);
        DroneEntity drone = spawnDrone(helper, 2, 1, 2, explosive(droneData(newDroneId())));
        Vec3 top = top(helper, 7.5, 7.5);
        drone.moveTo(top);
        helper.assertTrue(drone.hasResumableOrder(), "A Move is resumable");
        helper.assertTrue(drone.resume(), "Resume ends the Move");
        helper.assertTrue(drone.getState() == DroneState.IDLE && drone.getOrder() == null && !drone.hasResumableOrder(), "Back to normal, state=" + drone.getState());
        helper.assertTrue(drone.strike(top), "Fixture: Strike");
        helper.assertTrue(drone.hasResumableOrder(), "A Strike is resumable");
        helper.assertTrue(drone.resume(), "Resume ends the Strike");
        helper.assertTrue(drone.getState() == DroneState.IDLE && drone.getOrder() == null && !drone.hasResumableOrder(), "Back to normal, state=" + drone.getState());
        helper.assertFalse(drone.resume(), "With nothing to end, Resume returns false");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void resumeEndsGuardingAndTheDroneGoesBackToNormal(GameTestHelper helper) {
        clearTestBarriers(helper);
        DroneEntity drone = spawnAtSpot(helper, droneData(newDroneId()));
        withGuardingDrone(helper, drone, () -> {
            helper.assertTrue(drone.hasResumableOrder(), "Guarding is resumable");
            helper.assertTrue(drone.resume(), "Resume ends the guard");
            helper.assertTrue(drone.getState() == DroneState.IDLE && drone.getOrder() == null && !drone.hasResumableOrder(), "Back to normal at once, state=" + drone.getState());
            helper.runAfterDelay(5, () -> {
                helper.assertTrue(drone.getState() != DroneState.GUARDING && drone.getOrder() == null, "Stays released, state=" + drone.getState());
                helper.succeed();
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void resumeStillEndsHoldAndRecall(GameTestHelper helper) {
        clearTestBarriers(helper);
        DroneEntity drone = spawnDrone(helper, 2, 3, 2, droneData(newDroneId()));
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 1.5, 0);
        try {
            drone.hold();
            helper.assertTrue(drone.hasResumableOrder() && drone.resume() && drone.getState() == DroneState.IDLE, "Resume ends a Hold, state=" + drone.getState());
            drone.recall(player);
            helper.assertTrue(drone.hasResumableOrder() && drone.resume() && drone.getState() != DroneState.RECALLED, "Resume ends a Recall, state=" + drone.getState());
        } finally {
            removePlayer(helper, player);
        }
        helper.succeed();
    }

    // =====================================================================================================
    // 6. The return threshold overrides an order (sections 5.2 and 7.10)
    // =====================================================================================================

    private static void thresholdOverridesOrder(GameTestHelper helper, boolean guarding) {
        clearTestBarriers(helper);
        BlockPos stationRel = new BlockPos(7, 3, 7);
        ChargingStationBlockEntity station = placeChargingStation(helper, stationRel);
        station.getEnergyStorage().receiveEnergy(Integer.MAX_VALUE, false);
        DroneEntity drone = guarding ? spawnAtSpot(helper, droneData(newDroneId())) : spawnDrone(helper, 1, 3, 1, droneData(newDroneId()));
        Runnable begin = () -> {
            DroneState expected = guarding ? DroneState.GUARDING : DroneState.MOVING;
            helper.assertValueEqual(drone.getState(), expected, "state with the order");
            int maxEnergy = DroneStats.maxEnergy(drone.snapshotData());
            double distance = drone.position().distanceTo(ChargingStationBlockEntity.dockPosition(helper.absolutePos(stationRel)));
            int below = Math.max(1, (int) DroneStats.returnThreshold(drone.snapshotData(), distance) - 2);
            drone.setDroneData(drone.snapshotData().withEnergy(below));
            pollUntil(helper, () -> drone.getState() == DroneState.RETURNING || drone.getState() == DroneState.CHARGING, 60, () -> {
                helper.assertTrue(drone.getOrder() == null, "Returning overrides the order, got " + drone.getOrder());
                drone.setDroneData(drone.snapshotData().withEnergy(maxEnergy - 500));
                pollUntil(helper, () -> drone.getEnergy() >= maxEnergy && drone.getState() == DroneState.IDLE && drone.getChargingStation() == null, 450, () -> {
                    helper.assertTrue(drone.getOrder() == null && !drone.hasResumableOrder(), "After charging the order is not restored, got " + drone.getOrder());
                    helper.runAfterDelay(20, () -> {
                        helper.assertTrue(drone.getState() != DroneState.MOVING && drone.getState() != DroneState.GUARDING && drone.getOrder() == null,
                                "...and stays unrestored, state=" + drone.getState());
                        helper.succeed();
                    });
                }, () -> helper.fail("The drone never finished charging, state=" + drone.getState() + " energy=" + drone.getEnergy()));
            }, () -> helper.fail("The drone never returned below its threshold, state=" + drone.getState() + " energy=" + drone.getEnergy()));
        };
        if (guarding) {
            withGuardingDrone(helper, drone, begin);
        } else {
            drone.moveTo(centerTop(helper));
            begin.run();
        }
    }

    @GameTest(template = "empty", timeoutTicks = 700)
    public static void aMovingDroneReturnsAtTheThresholdAndTheMoveIsNotRestoredAfterCharging(GameTestHelper helper) {
        clearTestBarriers(helper);
        thresholdOverridesOrder(helper, false);
    }

    @GameTest(template = "empty", timeoutTicks = 700)
    public static void aGuardingDroneReturnsAtTheThresholdAndTheGuardIsNotRestoredAfterCharging(GameTestHelper helper) {
        clearTestBarriers(helper);
        thresholdOverridesOrder(helper, true);
    }

    // =====================================================================================================
    // 7. NBT and picking the drone up
    // =====================================================================================================

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void aMoveSurvivesAnNbtRoundTripAndCarriesOn(GameTestHelper helper) {
        clearTestBarriers(helper);
        DroneEntity original = spawnDrone(helper, 0, 1, 0, droneData(newDroneId()));
        original.moveTo(top(helper, 7.5, 7.5));
        DroneOrder.Info before = original.getOrder();
        DroneEntity loaded = reloadDrone(helper, original);
        helper.assertValueEqual(loaded.getState(), DroneState.MOVING, "state after loading");
        DroneOrder.Info after = loaded.getOrder();
        helper.assertTrue(after != null && after.order() == DroneOrder.MOVE && after.spot().equals(before.spot()) && after.ticksLeft() == before.ticksLeft(),
                "The order, spot and ticks left survive, before " + before + " after " + after);
        helper.assertTrue(loaded.isPassive(), "Still passive");
        pollUntil(helper, () -> loaded.getState() == DroneState.GUARDING, 190, helper::succeed,
                () -> helper.fail("The reloaded drone never arrived, state=" + loaded.getState()));
    }

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void aStrikeSurvivesAnNbtRoundTrip(GameTestHelper helper) {
        clearTestBarriers(helper);
        DroneEntity original = spawnDrone(helper, 0, 1, 0, explosive(droneData(newDroneId())));
        Vec3 top = top(helper, 70.5, 4.5);
        original.strike(top);
        DroneEntity loaded = reloadDrone(helper, original);
        helper.assertValueEqual(loaded.getState(), DroneState.STRIKING, "state after loading");
        helper.assertTrue(loaded.getOrder() != null && loaded.getOrder().order() == DroneOrder.STRIKE && loaded.getOrder().spot().equals(top), "The Strike order survives, got " + loaded.getOrder());
        helper.runAfterDelay(8, () -> {
            helper.assertTrue(!loaded.isRemoved() && loaded.getState() == DroneState.STRIKING, "Still striking a few ticks later, state=" + loaded.getState());
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void guardingSurvivesAnNbtRoundTripWithItsTimer(GameTestHelper helper) {
        clearTestBarriers(helper);
        DroneEntity original = spawnAtSpot(helper, droneData(newDroneId()));
        withGuardingDrone(helper, original, () -> {
            DroneOrder.Info before = original.getOrder();
            DroneEntity loaded = reloadDrone(helper, original);
            helper.assertValueEqual(loaded.getState(), DroneState.GUARDING, "state after loading");
            DroneOrder.Info after = loaded.getOrder();
            helper.assertTrue(after != null && after.order() == DroneOrder.GUARD && after.spot().equals(before.spot()) && after.ticksLeft() == before.ticksLeft(),
                    "The guard order, spot and ticks left survive, before " + before + " after " + after);
            helper.runAfterDelay(5, () -> {
                helper.assertValueEqual(loaded.getState(), DroneState.GUARDING, "still guarding");
                helper.assertTrue(loaded.getOrder() != null && loaded.getOrder().ticksLeft() < after.ticksLeft(), "The timer keeps running");
                helper.succeed();
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void aGuardOrderSurvivesAnNbtRoundTripOnADroneLoadedMidChase(GameTestHelper helper) {
        clearTestBarriers(helper);
        DroneEntity original = spawnAtSpot(helper, withZombieTarget(droneData(newDroneId())));
        Vec3 spot = spotOf(centerTop(helper));
        withGuardingDrone(helper, original, () -> {
            Zombie zombie = spawnStationaryZombie(helper, 4, 1, 8);
            pollUntil(helper, () -> chasing(original) && original.getSeekTarget() == zombie, 150, () -> {
                DroneEntity loaded = reloadDrone(helper, original);
                helper.assertTrue(chasing(loaded), "Loaded mid-chase it is chasing again, state=" + loaded.getState());
                helper.assertTrue(loaded.getOrder() != null && loaded.getOrder().order() == DroneOrder.GUARD && loaded.getOrder().spot().equals(spot),
                        "The guard order is kept for a drone loaded mid-chase, got " + loaded.getOrder());
                pollUntil(helper, () -> loaded.getSeekTarget() == zombie, 30, () -> {
                    zombie.discard();
                    pollUntil(helper, () -> loaded.getState() == DroneState.GUARDING && loaded.position().distanceTo(spot) <= 1.1, 250, helper::succeed,
                            () -> helper.fail("The reloaded drone never went back to guarding the spot, state=" + loaded.getState() + " at " + loaded.position()));
                }, () -> helper.fail("The reloaded drone never found its target again, state=" + loaded.getState()));
            }, () -> helper.fail("The guarding drone never chased, state=" + original.getState()));
        });
    }

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void pickingUpAMovingDroneClearsItsOrder(GameTestHelper helper) {
        clearTestBarriers(helper);
        String id = newDroneId();
        DroneEntity drone = spawnDrone(helper, 4, 3, 7, droneData(id));
        drone.moveTo(top(helper, 7.5, 7.5));
        helper.assertValueEqual(drone.getState(), DroneState.MOVING, "Fixture: moving");
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 1.5, 0);
        try {
            player.setShiftKeyDown(true);
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            drone.interact(player, InteractionHand.MAIN_HAND);
            helper.assertTrue(drone.isRemoved(), "Sanity: picked up");
            ItemStack stack = ItemStack.EMPTY;
            for (ItemStack candidate : player.getInventory().items) {
                if (candidate.getItem() == ModItems.DRONE.get()) {
                    stack = candidate;
                }
            }
            helper.assertFalse(stack.isEmpty(), "The drone item should be in the inventory");
            helper.assertValueEqual(DroneItem.getData(stack).droneId(), id, "item drone id");
            player.setItemInHand(InteractionHand.MAIN_HAND, stack.copy());
            stack.setCount(0);
            ModItems.DRONE.get().use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
            DroneEntity redeployed = DroneIndex.find(helper.getLevel(), id);
            helper.assertTrue(redeployed != null, "The redeployed drone should be in the index");
            helper.assertTrue(redeployed.getState() != DroneState.MOVING && redeployed.getOrder() == null && !redeployed.hasResumableOrder(),
                    "A redeployed drone has no order, state=" + redeployed.getState() + " order=" + redeployed.getOrder());
        } finally {
            removePlayer(helper, player);
        }
        helper.succeed();
    }
}
