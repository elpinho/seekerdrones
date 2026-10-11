package com.elpinho.seekerdrones.gametest;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.drone.DroneReason;
import com.elpinho.seekerdrones.drone.DroneState;
import com.elpinho.seekerdrones.drone.DroneStats;
import com.elpinho.seekerdrones.drone.TargetEntry;
import com.elpinho.seekerdrones.drone.TargetFilter;
import com.elpinho.seekerdrones.drone.UpgradeType;
import com.elpinho.seekerdrones.network.DroneStatusPayload;
import com.elpinho.seekerdrones.operator.OperatorGroups;
import com.elpinho.seekerdrones.registry.ModEntityTypes;
import com.elpinho.seekerdrones.station.ChargingStationBlockEntity;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * GameTests for status reasons (DESIGN.md 2.4, with 3.3, 3.5, 5.2, 5.3, 6.2): {@code DroneEntity.resolveReason}, the
 * status payload and its codec. The test that changes {@link ServerConfig} runs in its own batch and restores the value.
 */
@GameTestHolder("seekerdrones")
@PrefixGameTestTemplate(false)
public class StatusReasonGameTests {

    // --- 1. Unknown Operator Group ---

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void droneWithMissingGroupReportsUnknownGroup(GameTestHelper helper) {
        DroneEntity drone = spawn(helper, 4, 3, 4, zombieData().withGroupId(Optional.of(UUID.randomUUID())));
        DroneReason.Info info = drone.resolveReason();
        helper.assertTrue(info.reason() == DroneReason.UNKNOWN_GROUP, "Expected UNKNOWN_GROUP, was " + info);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void unknownGroupBeatsNoTargetEntries(GameTestHelper helper) {
        DroneEntity drone = spawn(helper, 4, 3, 4, noTargetsData().withGroupId(Optional.of(UUID.randomUUID())));
        helper.assertTrue(drone.resolveReason().reason() == DroneReason.UNKNOWN_GROUP, "Unknown group is first in line, was " + drone.resolveReason());
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void droneWithExistingGroupDoesNotReportUnknownGroup(GameTestHelper helper) {
        UUID group = OperatorGroups.get(helper.getLevel().getServer()).createGroup(UUID.randomUUID());
        DroneEntity drone = spawn(helper, 4, 3, 4, noTargetsData().withGroupId(Optional.of(group)));
        helper.assertTrue(drone.resolveReason().reason() == DroneReason.NO_TARGET_ENTRIES, "Expected NO_TARGET_ENTRIES, was " + drone.resolveReason());
        helper.succeed();
    }

    // --- 2. Docked, station without FE ---

    @GameTest(template = "empty", timeoutTicks = 150)
    public static void dockedDroneAtEmptyStationReportsNoPowerAndNoneOnceItHasFe(GameTestHelper helper) {
        BlockPos stationRel = new BlockPos(4, 3, 7);
        ChargingStationBlockEntity station = EnergyGameTests.placeChargingStation(helper, stationRel);
        DroneEntity drone = spawn(helper, 4, 3, 4, zombieData());
        drone.setDroneData(drone.snapshotData().withEnergy(DroneStats.maxEnergy(drone.snapshotData()) - 50_000));
        EnergyGameTests.forceReturning(drone, helper.absolutePos(stationRel));
        EnergyGameTests.pollUntil(helper, () -> drone.getState() == DroneState.CHARGING, 100, () -> {
            helper.assertTrue(station.getEnergy() == 0, "Sanity: station starts empty");
            helper.assertTrue(drone.resolveReason().reason() == DroneReason.STATION_NO_POWER, "Expected STATION_NO_POWER, was " + drone.resolveReason());
            station.getEnergyStorage().receiveEnergy(1000, false);
            helper.assertTrue(station.getEnergy() > 0, "Sanity: station should now hold FE");
            helper.assertTrue(drone.getState() == DroneState.CHARGING, "Sanity: still docked");
            helper.assertTrue(drone.resolveReason() == DroneReason.Info.NONE, "Expected NONE with FE, was " + drone.resolveReason());
            helper.succeed();
        }, () -> helper.fail("Drone never docked, state=" + drone.getState()));
    }

    // --- 3. Returning: waiting and unreachable ---

    @GameTest(template = "empty", timeoutTicks = 220)
    public static void returningDroneNextToClaimedStationReportsWaiting(GameTestHelper helper) {
        BlockPos stationRel = new BlockPos(4, 3, 6);
        ChargingStationBlockEntity station = EnergyGameTests.placeChargingStation(helper, stationRel);
        station.getEnergyStorage().receiveEnergy(Integer.MAX_VALUE, false);
        BlockPos stationAbs = helper.absolutePos(stationRel);
        DroneEntity first = spawn(helper, 4, 3, 5, zombieData());
        first.setDroneData(first.snapshotData().withEnergy(DroneStats.maxEnergy(first.snapshotData()) - 300_000));
        EnergyGameTests.forceReturning(first, stationAbs);
        EnergyGameTests.pollUntil(helper, () -> first.getState() == DroneState.CHARGING, 40, () -> {
            DroneEntity second = spawn(helper, 4, 3, 2, zombieData());
            EnergyGameTests.forceReturning(second, stationAbs);
            EnergyGameTests.pollUntil(helper, () -> second.resolveReason().reason() == DroneReason.WAITING_FOR_STATION, 120, () -> {
                helper.assertTrue(second.getState() == DroneState.RETURNING, "Waiting drone should be RETURNING, was " + second.getState());
                helper.assertTrue(first.getState() == DroneState.CHARGING, "Holder should still be charging");
                helper.succeed();
            }, () -> helper.fail("Never WAITING_FOR_STATION, reason=" + second.resolveReason() + " state=" + second.getState()
                    + " dist=" + Math.sqrt(second.distanceToSqr(ChargingStationBlockEntity.dockPosition(stationAbs)))));
        }, () -> helper.fail("First drone never docked, state=" + first.getState()));
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void droneRedirectedAfterUnreachableStationReportsStationUnreachable(GameTestHelper helper) {
        BlockPos aRel = new BlockPos(4, 3, 7);
        BlockPos bRel = new BlockPos(7, 3, 7);
        EnergyGameTests.placeChargingStation(helper, aRel);
        EnergyGameTests.placeChargingStation(helper, bRel);
        BlockPos aAbs = helper.absolutePos(aRel);
        BlockPos bAbs = helper.absolutePos(bRel);
        DroneEntity drone = spawn(helper, 4, 3, 3, zombieData());
        EnergyGameTests.forceReturning(drone, aAbs);
        helper.assertTrue(drone.resolveReason() == DroneReason.Info.NONE, "Before skipping a station the reason should be NONE, was " + drone.resolveReason());
        try {
            Method markUnreachable = DroneEntity.class.getDeclaredMethod("markUnreachable", DroneData.class, BlockPos.class);
            markUnreachable.setAccessible(true);
            markUnreachable.invoke(drone, drone.snapshotData(), aAbs);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new RuntimeException(e);
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e.getCause());
        }
        helper.assertTrue(drone.getState() == DroneState.RETURNING, "Drone should be returning to the next station, state=" + drone.getState());
        helper.assertTrue(bAbs.equals(drone.getChargingStation()), "Drone should now head for B, station=" + drone.getChargingStation());
        helper.assertTrue(drone.resolveReason().reason() == DroneReason.STATION_UNREACHABLE, "Expected STATION_UNREACHABLE, was " + drone.resolveReason());
        helper.succeed();
    }

    // --- 4. Target out of sight ---

    @GameTest(template = "empty", timeoutTicks = 160)
    public static void chasingDroneWithHiddenTargetReportsOutOfSightWithSecondsLeft(GameTestHelper helper) {
        DroneEntity drone = spawn(helper, 4, 3, 3, zombieData());
        Zombie zombie = stationaryZombie(helper, 4, 1, 8);
        AtomicReference<Boolean> hidden = new AtomicReference<>(false);
        helper.onEachTick(() -> {
            if (!hidden.get()) {
                if (drone.getSeekTarget() == zombie && (drone.getState() == DroneState.CHASING || drone.getState() == DroneState.FOLLOWING)) {
                    helper.assertTrue(drone.resolveReason().reason() != DroneReason.TARGET_OUT_OF_SIGHT, "Visible target must not report out of sight");
                    helper.assertTrue(ServerConfig.get(ServerConfig.DRONE_INVISIBILITY_HIDES), "Precondition: invisibility hides targets by default");
                    zombie.setInvisible(true);
                    hidden.set(true);
                }
                return;
            }
            if (drone.getSeekTarget() != zombie) {
                helper.fail("Drone lost the target before reporting out of sight, state=" + drone.getState());
                return;
            }
            DroneReason.Info info = drone.resolveReason();
            if (info.reason() != DroneReason.TARGET_OUT_OF_SIGHT) {
                return;
            }
            int since = (int) readField(drone, "lostSightSince");
            helper.assertTrue(since >= 0, "lostSightSince should be set, was " + since);
            int timeout = ServerConfig.get(ServerConfig.DRONE_LOST_SIGHT_TIMEOUT);
            int ticksLeft = timeout - (drone.tickCount - since);
            int expected = Math.max(0, (int) Math.ceil(ticksLeft / 20.0));
            helper.assertTrue(info.seconds() == expected, "Seconds should be ceil(" + ticksLeft + "/20)=" + expected + ", was " + info.seconds());
            helper.assertTrue(info.seconds() >= 1 && info.seconds() <= (int) Math.ceil(timeout / 20.0), "Seconds out of range: " + info.seconds());
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 160)
    public static void outOfSightReasonClearsWhenTargetIsVisibleAgain(GameTestHelper helper) {
        DroneEntity drone = spawn(helper, 4, 3, 3, zombieData());
        Zombie zombie = stationaryZombie(helper, 4, 1, 8);
        AtomicReference<Integer> phase = new AtomicReference<>(0);
        helper.onEachTick(() -> {
            DroneReason.Info info = drone.resolveReason();
            switch (phase.get()) {
                case 0 -> {
                    if (drone.getSeekTarget() == zombie) {
                        zombie.setInvisible(true);
                        phase.set(1);
                    }
                }
                case 1 -> {
                    if (info.reason() == DroneReason.TARGET_OUT_OF_SIGHT) {
                        zombie.setInvisible(false);
                        phase.set(2);
                    }
                }
                default -> {
                    if (drone.getSeekTarget() == zombie && info.reason() != DroneReason.TARGET_OUT_OF_SIGHT) {
                        helper.succeed();
                    }
                }
            }
        });
    }

    // --- 5. Idle / patrolling ---

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void idleDroneWithoutTargetEntriesReportsNoTargetEntries(GameTestHelper helper) {
        DroneEntity drone = spawn(helper, 4, 3, 4, noTargetsData());
        helper.assertTrue(drone.getState() == DroneState.IDLE, "Sanity: IDLE, was " + drone.getState());
        helper.assertTrue(drone.resolveReason().reason() == DroneReason.NO_TARGET_ENTRIES, "Expected NO_TARGET_ENTRIES, was " + drone.resolveReason());
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void playerEntryWithoutPlayerSeekReportsNoUsableTargets(GameTestHelper helper) {
        DroneData base = DroneData.createNew();
        DroneData data = base.withConfig(base.config().withTargets(List.of(new TargetEntry(TargetEntry.Kind.PLAYER_NAME, "Steve"))));
        DroneEntity drone = spawn(helper, 4, 3, 4, data);
        helper.assertTrue(drone.resolveReason().reason() == DroneReason.NO_USABLE_TARGETS, "Expected NO_USABLE_TARGETS, was " + drone.resolveReason());
        // With Player Seek the entry is usable: no entries-related reason any more.
        drone.setDroneData(data.withUpgradeCount(UpgradeType.PLAYER_SEEK, 1));
        DroneReason reason = drone.resolveReason().reason();
        helper.assertTrue(reason != DroneReason.NO_USABLE_TARGETS && reason != DroneReason.NO_TARGET_ENTRIES, "With Player Seek the entry is usable, was " + reason);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 10, batch = "config_status_reason_denylist")
    public static void denylistedEntityTypeReportsNoUsableTargets(GameTestHelper helper) {
        DroneEntity drone = spawn(helper, 4, 3, 4, zombieData());
        try {
            ServerConfig.DRONE_TARGET_DENYLIST.set(List.of("minecraft:zombie"));
            TargetFilter.invalidate();
            helper.assertTrue(drone.resolveReason().reason() == DroneReason.NO_USABLE_TARGETS, "Expected NO_USABLE_TARGETS, was " + drone.resolveReason());
        } finally {
            ServerConfig.DRONE_TARGET_DENYLIST.set(List.of());
            TargetFilter.invalidate();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 140)
    public static void droneThatSkippedAClaimedEntityReportsTargetClaimedThenClears(GameTestHelper helper) {
        DroneEntity a = spawn(helper, 3, 3, 3, zombieData());
        DroneEntity b = spawn(helper, 5, 3, 3, zombieData());
        Zombie zombie = stationaryZombie(helper, 4, 1, 7);
        AtomicReference<DroneEntity> loser = new AtomicReference<>();
        helper.onEachTick(() -> {
            if (loser.get() == null) {
                DroneEntity candidate = a.getSeekTarget() == zombie && b.getSeekTarget() == null ? b
                        : b.getSeekTarget() == zombie && a.getSeekTarget() == null ? a : null;
                if (candidate != null && candidate.getState() == DroneState.IDLE && candidate.resolveReason().reason() == DroneReason.TARGET_CLAIMED) {
                    DroneEntity winner = candidate == a ? b : a;
                    helper.assertTrue(winner.resolveReason().reason() != DroneReason.TARGET_CLAIMED, "The claiming drone has nothing to report, was " + winner.resolveReason());
                    loser.set(candidate);
                    zombie.discard();
                }
                return;
            }
            if (loser.get().resolveReason().reason() != DroneReason.TARGET_CLAIMED) {
                helper.succeed();
            }
        });
    }

    // --- 6. Fallback ---

    // Own batch: the default search radius (500) would reach stations of concurrently running tests.

    @GameTest(template = "empty", timeoutTicks = 10, batch = "config_status_reason_station_radius")
    public static void idleDroneWithUsableTargetsAndNoStationReportsNoStation(GameTestHelper helper) {
        int original = ServerConfig.get(ServerConfig.DRONE_CHARGING_SEARCH_RADIUS);
        ServerConfig.DRONE_CHARGING_SEARCH_RADIUS.set(6);
        try {
            DroneEntity drone = spawn(helper, 4, 3, 4, zombieData());
            helper.assertTrue(drone.resolveReason().reason() == DroneReason.NO_STATION, "Expected NO_STATION, was " + drone.resolveReason());
        } finally {
            ServerConfig.DRONE_CHARGING_SEARCH_RADIUS.set(original);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 10, batch = "config_status_reason_station_radius")
    public static void idleDroneWithStationInRangeReportsNothing(GameTestHelper helper) {
        int original = ServerConfig.get(ServerConfig.DRONE_CHARGING_SEARCH_RADIUS);
        ServerConfig.DRONE_CHARGING_SEARCH_RADIUS.set(6);
        try {
            EnergyGameTests.placeChargingStation(helper, new BlockPos(4, 3, 7));
            DroneEntity drone = spawn(helper, 4, 3, 3, zombieData());
            helper.assertTrue(drone.resolveReason() == DroneReason.Info.NONE, "Expected NONE, was " + drone.resolveReason());
        } finally {
            ServerConfig.DRONE_CHARGING_SEARCH_RADIUS.set(original);
        }
        helper.succeed();
    }

    // --- 7. Payload ---

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void statusPayloadCarriesTheResolvedReasonAndItemHasNone(GameTestHelper helper) {
        DroneEntity drone = spawn(helper, 4, 3, 4, zombieData().withGroupId(Optional.of(UUID.randomUUID())));
        DroneStatusPayload payload = DroneStatusPayload.of(drone, false);
        helper.assertTrue(payload.reason().equals(drone.resolveReason()), "Payload reason should match resolveReason, was " + payload.reason());
        helper.assertTrue(payload.reason().reason() == DroneReason.UNKNOWN_GROUP, "Expected UNKNOWN_GROUP, was " + payload.reason());
        helper.assertTrue(DroneStatusPayload.ofItem(drone.snapshotData()).reason() == DroneReason.Info.NONE, "Item payload should have NONE");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void statusPayloadCodecRoundTripsTheReason(GameTestHelper helper) {
        DroneEntity drone = spawn(helper, 4, 3, 4, zombieData());
        DroneStatusPayload base = DroneStatusPayload.of(drone, true);
        for (DroneReason reason : DroneReason.values()) {
            DroneReason.Info info = new DroneReason.Info(reason, reason == DroneReason.TARGET_OUT_OF_SIGHT ? 37 : 0);
            DroneStatusPayload payload = new DroneStatusPayload(base.entityId(), base.open(), base.data(), base.maxEnergy(), base.maxHealth(), base.state(),
                    base.patrolCenter(), base.patrolRadius(), base.maxPatrolRadius(), base.sightRange(), base.selfHealRate(), info);
            ByteBuf buf = Unpooled.buffer();
            DroneStatusPayload.STREAM_CODEC.encode(buf, payload);
            DroneStatusPayload decoded = DroneStatusPayload.STREAM_CODEC.decode(buf);
            helper.assertTrue(decoded.reason().equals(info), "Round trip of " + info + " gave " + decoded.reason());
            helper.assertTrue(buf.readableBytes() == 0, "Codec should consume the whole buffer for " + reason);
        }
        helper.succeed();
    }

    // --- helpers ---

    private static Object readField(DroneEntity drone, String name) {
        try {
            Field field = DroneEntity.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(drone);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static DroneEntity spawn(GameTestHelper helper, int x, int y, int z, DroneData data) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(x, y, z));
        drone.setDroneData(data);
        return drone;
    }

    private static DroneData zombieData() {
        DroneData base = DroneData.createNew();
        return base.withConfig(base.config().withTargets(List.of(new TargetEntry(TargetEntry.Kind.ENTITY_TYPE, "minecraft:zombie"))));
    }

    private static DroneData noTargetsData() {
        DroneData base = DroneData.createNew();
        return base.withConfig(base.config().withTargets(List.of()));
    }

    private static Zombie stationaryZombie(GameTestHelper helper, int x, int y, int z) {
        Zombie zombie = helper.spawn(EntityType.ZOMBIE, new BlockPos(x, y, z));
        zombie.setNoAi(true);
        zombie.setNoGravity(true);
        zombie.setInvulnerable(true);
        return zombie;
    }
}
