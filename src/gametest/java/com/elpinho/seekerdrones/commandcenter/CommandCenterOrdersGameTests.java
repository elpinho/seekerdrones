package com.elpinho.seekerdrones.commandcenter;

import static com.elpinho.seekerdrones.commandcenter.CommandCenterGameTests.RADIUS_BATCH;
import static com.elpinho.seekerdrones.commandcenter.CommandCenterGameTests.findUnloadedSpot;
import static com.elpinho.seekerdrones.commandcenter.CommandCenterGameTests.openMenu;
import static com.elpinho.seekerdrones.commandcenter.CommandCenterGameTests.placeStation;
import static com.elpinho.seekerdrones.commandcenter.CommandCenterGameTests.poweredStation;
import static com.elpinho.seekerdrones.gametest.GameTestUtil.cameraDroneData;
import static com.elpinho.seekerdrones.gametest.GameTestUtil.droneData;
import static com.elpinho.seekerdrones.gametest.GameTestUtil.foreignDroneData;
import static com.elpinho.seekerdrones.gametest.GameTestUtil.newDroneId;
import static com.elpinho.seekerdrones.gametest.GameTestUtil.placeChargingStation;
import static com.elpinho.seekerdrones.gametest.GameTestUtil.removePlayer;
import static com.elpinho.seekerdrones.gametest.GameTestUtil.spawnDrone;
import static com.elpinho.seekerdrones.gametest.GameTestUtil.spawnPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.drone.DroneOrder;
import com.elpinho.seekerdrones.drone.DroneState;
import com.elpinho.seekerdrones.drone.UpgradeType;
import com.elpinho.seekerdrones.feed.FpvFeeds;
import com.elpinho.seekerdrones.network.CommandCenterOrderPayload;
import com.elpinho.seekerdrones.network.CommandCenterOrderPayload.Mode;
import com.elpinho.seekerdrones.station.ChargingStationBlockEntity;
import com.elpinho.seekerdrones.station.ChargingStationRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The server side of the Command Center's group orders (DESIGN.md section 7.10), through
 * {@link CommandCenterOrders#give}: what it needs (the menu open and valid, power), where it may send drones (inside
 * the area, in a loaded chunk), which drones it skips (out of reach, not the player's, unable to take the mode) and what
 * each mode does at the moment it is sent. How a drone then carries an order out is in {@code DroneOrdersGameTests}.
 */
@GameTestHolder("seekerdrones")
@PrefixGameTestTemplate(false)
public class CommandCenterOrdersGameTests {
    private static final BlockPos STATION = new BlockPos(4, 1, 4);

    /** The player's spot of the structure to aim orders at, one block above the floor. */
    private static CommandCenterOrderPayload.Spot spotAt(GameTestHelper helper, double x, double z) {
        Vec3 abs = helper.absoluteVec(new Vec3(x, 1, z));
        return new CommandCenterOrderPayload.Spot(abs.x, abs.z);
    }

    private static void give(ServerPlayer player, Mode mode, CommandCenterOrderPayload.Spot spot, String... ids) {
        CommandCenterOrders.give(player, new CommandCenterOrderPayload(List.of(ids), mode, Optional.ofNullable(spot)));
    }

    private static DroneEntity owned(GameTestHelper helper, int x, int y, int z, UUID owner, String id) {
        return spawnDrone(helper, x, y, z, droneData(id).withOwnerId(Optional.of(owner)));
    }

    // =====================================================================================================
    // What an order needs
    // =====================================================================================================

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void anOrderNeedsTheCommandCenterMenuOpen(GameTestHelper helper) {
        poweredStation(helper, STATION, 50);
        UUID uuid = UUID.randomUUID();
        String id = newDroneId();
        DroneEntity drone = owned(helper, 4, 1, 6, uuid, id);
        ServerPlayer player = spawnPlayer(helper, uuid, 4.5, 1, 1.5, 0);
        try {
            give(player, Mode.HOLD, null, id);
            helper.assertTrue(drone.getState() != DroneState.HOLDING, "No menu open: the order must be ignored, state=" + drone.getState());
        } finally {
            removePlayer(helper, player);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void anOrderNeedsThePlayerToStillBeInReachOfTheMenu(GameTestHelper helper) {
        CommandCenterBlockEntity station = poweredStation(helper, STATION, 50);
        UUID uuid = UUID.randomUUID();
        String id = newDroneId();
        DroneEntity drone = owned(helper, 4, 1, 6, uuid, id);
        ServerPlayer player = spawnPlayer(helper, uuid, 4.5, 1, 1.5, 0);
        try {
            openMenu(player, station);
            player.moveTo(helper.absoluteVec(new Vec3(4.5, 1, 60)));
            helper.assertTrue(!player.containerMenu.stillValid(player), "Fixture: the menu is out of reach");
            give(player, Mode.HOLD, null, id);
            helper.assertTrue(drone.getState() != DroneState.HOLDING, "A menu that is no longer valid: the order must be ignored, state=" + drone.getState());
        } finally {
            removePlayer(helper, player);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void anOrderNeedsThePowerOn(GameTestHelper helper) {
        CommandCenterBlockEntity station = placeStation(helper, STATION);
        UUID uuid = UUID.randomUUID();
        String id = newDroneId();
        DroneEntity drone = owned(helper, 4, 1, 6, uuid, id);
        ServerPlayer player = spawnPlayer(helper, uuid, 4.5, 1, 1.5, 0);
        try {
            openMenu(player, station);
            helper.assertTrue(!station.isPowered(), "Fixture: unpowered");
            give(player, Mode.HOLD, null, id);
            helper.assertTrue(drone.getState() != DroneState.HOLDING, "No power: the order must be ignored, state=" + drone.getState());
            give(player, Mode.MOVE, spotAt(helper, 6.5, 6.5), id);
            helper.assertTrue(drone.getState() != DroneState.MOVING && drone.getOrder() == null, "No power: a Move must be ignored too, state=" + drone.getState());
        } finally {
            removePlayer(helper, player);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void ordersAlreadyRunningCarryOnWhenThePowerIsLost(GameTestHelper helper) {
        // Enough FE for a few ticks, then it runs dry.
        CommandCenterBlockEntity station = poweredStation(helper, STATION, 6);
        UUID uuid = UUID.randomUUID();
        String id = newDroneId();
        DroneEntity drone = owned(helper, 4, 1, 6, uuid, id);
        ServerPlayer player = spawnPlayer(helper, uuid, 4.5, 1, 1.5, 0);
        openMenu(player, station);
        give(player, Mode.MOVE, spotAt(helper, 6.5, 6.5), id);
        helper.assertValueEqual(drone.getState(), DroneState.MOVING, "state after Move");
        helper.runAfterDelay(12, () -> {
            removePlayer(helper, player);
            helper.assertTrue(!station.isPowered(), "Fixture: the Command Center lost its power");
            helper.assertTrue(drone.getOrder() != null && drone.getState() == DroneState.MOVING, "The order lives on the drone and carries on, state=" + drone.getState());
            helper.succeed();
        });
    }

    // =====================================================================================================
    // Where drones may be sent
    // =====================================================================================================

    @GameTest(template = "empty", timeoutTicks = 20, batch = RADIUS_BATCH)
    public static void aSpotOutsideTheAreaIsRefusedAndOneInsideIsTaken(GameTestHelper helper) {
        CommandCenterBlockEntity station = poweredStation(helper, STATION, 50);
        helper.assertTrue(station.radius() == 8, "Fixture: radius 8 in this batch");
        UUID uuid = UUID.randomUUID();
        String id = newDroneId();
        String strikeId = newDroneId();
        DroneEntity drone = owned(helper, 4, 1, 6, uuid, id);
        DroneEntity striker = spawnDrone(helper, 5, 1, 6, droneData(strikeId).withOwnerId(Optional.of(uuid)).withUpgradeCount(UpgradeType.EXPLOSIVE, 1));
        ServerPlayer player = spawnPlayer(helper, uuid, 4.5, 1, 1.5, 0);
        try {
            openMenu(player, station);
            give(player, Mode.MOVE, spotAt(helper, 4.5 + 20, 4.5), id);
            helper.assertTrue(drone.getState() != DroneState.MOVING && drone.getOrder() == null, "A Move 20 blocks out is refused, state=" + drone.getState());
            give(player, Mode.STRIKE, spotAt(helper, 4.5, 4.5 - 20), strikeId);
            helper.assertTrue(striker.getState() != DroneState.STRIKING, "A Strike outside the area is refused, state=" + striker.getState());
            give(player, Mode.PATROL_HERE, spotAt(helper, 4.5 + 20, 4.5), id);
            helper.assertTrue(drone.getPatrolCenter() == null || drone.getPatrolCenter().pos().getX() < helper.absolutePos(new BlockPos(4 + 15, 1, 4)).getX(),
                    "Patrol Here outside the area is refused too");
            give(player, Mode.MOVE, spotAt(helper, 4.5 + 7, 4.5), id);
            helper.assertValueEqual(drone.getState(), DroneState.MOVING, "state after a Move 7 blocks out (inside the radius of 8)");
        } finally {
            removePlayer(helper, player);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void aSpotInAnUnloadedChunkIsRefusedForEveryAimedMode(GameTestHelper helper) {
        CommandCenterBlockEntity station = poweredStation(helper, STATION, 50);
        net.minecraft.server.level.ServerLevel level = helper.getLevel();
        Vec3 spot = findUnloadedSpot(level, station);
        helper.assertTrue(spot != null, "Fixture: expected to find an unloaded chunk within the area");
        UUID uuid = UUID.randomUUID();
        String id = newDroneId();
        DroneEntity drone = spawnDrone(helper, 4, 1, 6, droneData(id).withOwnerId(Optional.of(uuid)).withUpgradeCount(UpgradeType.EXPLOSIVE, 1).withUpgradeCount(UpgradeType.PATROL, 1));
        GlobalPos before = drone.getPatrolCenter();
        ServerPlayer player = spawnPlayer(helper, uuid, 4.5, 1, 1.5, 0);
        try {
            openMenu(player, station);
            CommandCenterOrderPayload.Spot unloaded = new CommandCenterOrderPayload.Spot(spot.x, spot.z);
            for (Mode mode : List.of(Mode.MOVE, Mode.STRIKE, Mode.PATROL_HERE)) {
                give(player, mode, unloaded, id);
                helper.assertTrue(drone.getOrder() == null && drone.getState() == DroneState.IDLE, mode + " into an unloaded chunk must be refused, state=" + drone.getState());
            }
            helper.assertTrue(java.util.Objects.equals(drone.getPatrolCenter(), before), "Patrol Here into an unloaded chunk must not move the center");
            helper.assertTrue(!level.hasChunkAt(BlockPos.containing(spot)), "Giving the order must not load the chunk");
        } finally {
            removePlayer(helper, player);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void aMissingSpotIsRefusedForAnAimedMode(GameTestHelper helper) {
        CommandCenterBlockEntity station = poweredStation(helper, STATION, 50);
        UUID uuid = UUID.randomUUID();
        String id = newDroneId();
        DroneEntity drone = owned(helper, 4, 1, 6, uuid, id);
        ServerPlayer player = spawnPlayer(helper, uuid, 4.5, 1, 1.5, 0);
        try {
            openMenu(player, station);
            give(player, Mode.MOVE, null, id);
            helper.assertTrue(drone.getOrder() == null && drone.getState() == DroneState.IDLE, "A Move without a spot must be refused, state=" + drone.getState());
        } finally {
            removePlayer(helper, player);
        }
        helper.succeed();
    }

    // =====================================================================================================
    // Which drones are skipped
    // =====================================================================================================

    @GameTest(template = "empty", timeoutTicks = 20, batch = RADIUS_BATCH)
    public static void dronesOutOfReachOrNotThePlayersAreSkippedAndTheRestStillGetTheOrder(GameTestHelper helper) {
        CommandCenterBlockEntity station = poweredStation(helper, new BlockPos(0, 1, 0), 50);
        UUID uuid = UUID.randomUUID();
        String near = newDroneId();
        String far = newDroneId();
        String theirs = newDroneId();
        DroneEntity nearDrone = owned(helper, 2, 1, 2, uuid, near);
        DroneEntity farDrone = owned(helper, 8, 1, 8, uuid, far);
        farDrone.setNoGravity(true);
        DroneEntity theirDrone = spawnDrone(helper, 3, 1, 3, foreignDroneData(theirs));
        ServerPlayer op = spawnPlayer(helper, uuid, 1.5, 1, 3.5, 4);
        try {
            openMenu(op, station);
            helper.assertTrue(op.hasPermissions(2), "Fixture: a server operator");
            give(op, Mode.HOLD, null, near, far, theirs, newDroneId(), near);
            helper.assertValueEqual(nearDrone.getState(), DroneState.HOLDING, "the drone in reach");
            helper.assertTrue(farDrone.getState() != DroneState.HOLDING, "A drone outside the area is skipped, state=" + farDrone.getState());
            helper.assertTrue(theirDrone.getState() != DroneState.HOLDING, "Another player's drone is skipped even for a server op, state=" + theirDrone.getState());
        } finally {
            removePlayer(helper, op);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void strikeSkipsDronesWithoutExplosive(GameTestHelper helper) {
        CommandCenterBlockEntity station = poweredStation(helper, STATION, 50);
        UUID uuid = UUID.randomUUID();
        String plainId = newDroneId();
        String boomId = newDroneId();
        DroneEntity plain = owned(helper, 3, 1, 6, uuid, plainId);
        DroneEntity boom = spawnDrone(helper, 5, 1, 6, droneData(boomId).withOwnerId(Optional.of(uuid)).withUpgradeCount(UpgradeType.EXPLOSIVE, 1));
        ServerPlayer player = spawnPlayer(helper, uuid, 4.5, 1, 1.5, 0);
        try {
            openMenu(player, station);
            give(player, Mode.STRIKE, spotAt(helper, 7.5, 7.5), plainId, boomId);
            helper.assertValueEqual(boom.getState(), DroneState.STRIKING, "the Explosive drone");
            helper.assertTrue(boom.getOrder() != null && boom.getOrder().order() == DroneOrder.STRIKE, "It carries a Strike order");
            helper.assertTrue(plain.getState() == DroneState.IDLE && plain.getOrder() == null, "A drone without Explosive is left out, state=" + plain.getState());
        } finally {
            removePlayer(helper, player);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void patrolHereSkipsDronesWithoutPatrolAndMovesTheCenterKeepingItsHeight(GameTestHelper helper) {
        CommandCenterBlockEntity station = poweredStation(helper, STATION, 50);
        UUID uuid = UUID.randomUUID();
        String plainId = newDroneId();
        String patrolId = newDroneId();
        DroneEntity plain = owned(helper, 3, 1, 6, uuid, plainId);
        DroneEntity patrolling = spawnDrone(helper, 5, 1, 6, droneData(patrolId).withOwnerId(Optional.of(uuid)).withUpgradeCount(UpgradeType.PATROL, 1));
        BlockPos oldCenter = helper.absolutePos(new BlockPos(1, 5, 1));
        patrolling.setPatrolCenter(GlobalPos.of(helper.getLevel().dimension(), oldCenter));
        ServerPlayer player = spawnPlayer(helper, uuid, 4.5, 1, 1.5, 0);
        try {
            openMenu(player, station);
            CommandCenterOrderPayload.Spot spot = spotAt(helper, 7.3, 2.8);
            give(player, Mode.PATROL_HERE, spot, plainId, patrolId);
            GlobalPos center = patrolling.getPatrolCenter();
            helper.assertTrue(center != null, "The Patrol drone should have a center");
            helper.assertTrue(center.pos().getX() == (int) Math.floor(spot.x()) && center.pos().getZ() == (int) Math.floor(spot.z()),
                    "The center's x/z should be the spot's block (" + (int) Math.floor(spot.x()) + ", " + (int) Math.floor(spot.z()) + "), got " + center.pos());
            helper.assertTrue(center.pos().getY() == oldCenter.getY(), "The patrol height is kept: expected y=" + oldCenter.getY() + ", got " + center.pos().getY());
            helper.assertTrue(plain.snapshotData().config().patrolCenter().isEmpty(), "A drone without Patrol is left out");
            helper.assertValueEqual(patrolling.getState(), DroneState.IDLE, "Patrol Here is a setting, not an order");
            helper.assertTrue(patrolling.getOrder() == null, "No order is given by Patrol Here");
        } finally {
            removePlayer(helper, player);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void patrolHereWithoutACenterUsesTheDronesOwnHeight(GameTestHelper helper) {
        CommandCenterBlockEntity station = poweredStation(helper, STATION, 50);
        UUID uuid = UUID.randomUUID();
        String id = newDroneId();
        DroneEntity drone = spawnDrone(helper, 5, 3, 6, droneData(id).withOwnerId(Optional.of(uuid)).withUpgradeCount(UpgradeType.PATROL, 1));
        helper.assertTrue(drone.snapshotData().config().patrolCenter().isEmpty(), "Fixture: no configured center");
        int y = drone.blockPosition().getY();
        ServerPlayer player = spawnPlayer(helper, uuid, 4.5, 1, 1.5, 0);
        try {
            openMenu(player, station);
            CommandCenterOrderPayload.Spot spot = spotAt(helper, 2.5, 2.5);
            give(player, Mode.PATROL_HERE, spot, id);
            Optional<GlobalPos> center = drone.snapshotData().config().patrolCenter();
            helper.assertTrue(center.isPresent(), "A configured center should be set");
            helper.assertTrue(center.get().pos().getX() == (int) Math.floor(spot.x()) && center.get().pos().getZ() == (int) Math.floor(spot.z()), "x/z of the spot, got " + center.get().pos());
            helper.assertTrue(center.get().pos().getY() == y, "Without a center the drone's own height is used: expected " + y + ", got " + center.get().pos().getY());
        } finally {
            removePlayer(helper, player);
        }
        helper.succeed();
    }

    // =====================================================================================================
    // What each mode does
    // =====================================================================================================

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void moveSendsEachDroneAboveTheTopBlockMovedByTheSpread(GameTestHelper helper) {
        CommandCenterBlockEntity station = poweredStation(helper, STATION, 50);
        helper.setBlock(new BlockPos(7, 0, 7), net.minecraft.world.level.block.Blocks.STONE);
        UUID uuid = UUID.randomUUID();
        List<String> ids = new ArrayList<>();
        List<DroneEntity> drones = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String id = newDroneId();
            ids.add(id);
            drones.add(owned(helper, 1 + i, 1, 6, uuid, id));
        }
        ServerPlayer player = spawnPlayer(helper, uuid, 4.5, 1, 1.5, 0);
        try {
            openMenu(player, station);
            CommandCenterOrderPayload.Spot spot = spotAt(helper, 7.5, 7.5);
            Vec3 top = station.orderTop(helper.getLevel(), spot.x(), spot.z()).orElseThrow();
            give(player, Mode.MOVE, spot, ids.toArray(String[]::new));
            double spread = ServerConfig.get(ServerConfig.COMMAND_CENTER_MOVE_SPREAD);
            double height = ServerConfig.get(ServerConfig.COMMAND_CENTER_MOVE_HEIGHT);
            java.util.Set<Vec3> distinct = new java.util.HashSet<>();
            for (DroneEntity drone : drones) {
                helper.assertValueEqual(drone.getState(), DroneState.MOVING, "state after Move");
                DroneOrder.Info info = drone.getOrder();
                helper.assertTrue(info != null && info.order() == DroneOrder.MOVE, "A Move order, got " + info);
                Vec3 expected = top.add(0, height, 0);
                helper.assertTrue(Math.abs(info.spot().x - expected.x) <= spread + 1e-6 && Math.abs(info.spot().y - expected.y) <= spread + 1e-6
                        && Math.abs(info.spot().z - expected.z) <= spread + 1e-6, "The spot should be within " + spread + " of " + expected + " along each axis, got " + info.spot());
                distinct.add(info.spot());
            }
            helper.assertTrue(distinct.size() > 1, "The spread should keep a group from stacking on one spot, all got " + distinct);
        } finally {
            removePlayer(helper, player);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void strikeSendsTheDroneToTheTopBlockItself(GameTestHelper helper) {
        CommandCenterBlockEntity station = poweredStation(helper, STATION, 50);
        helper.setBlock(new BlockPos(7, 0, 7), net.minecraft.world.level.block.Blocks.STONE);
        UUID uuid = UUID.randomUUID();
        String id = newDroneId();
        DroneEntity drone = spawnDrone(helper, 2, 1, 6, droneData(id).withOwnerId(Optional.of(uuid)).withUpgradeCount(UpgradeType.EXPLOSIVE, 1));
        ServerPlayer player = spawnPlayer(helper, uuid, 4.5, 1, 1.5, 0);
        try {
            openMenu(player, station);
            CommandCenterOrderPayload.Spot spot = spotAt(helper, 7.5, 7.5);
            Vec3 top = station.orderTop(helper.getLevel(), spot.x(), spot.z()).orElseThrow();
            give(player, Mode.STRIKE, spot, id);
            helper.assertValueEqual(drone.getState(), DroneState.STRIKING, "state after Strike");
            helper.assertTrue(drone.getOrder() != null && drone.getOrder().spot().equals(top), "A Strike goes to the top of the top block " + top + " with no spread or height, got " + drone.getOrder());
        } finally {
            removePlayer(helper, player);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void holdMakesTheDronesHoldAndReleaseEndsIt(GameTestHelper helper) {
        CommandCenterBlockEntity station = poweredStation(helper, STATION, 50);
        UUID uuid = UUID.randomUUID();
        String a = newDroneId();
        String b = newDroneId();
        DroneEntity first = owned(helper, 3, 1, 6, uuid, a);
        DroneEntity second = owned(helper, 5, 1, 6, uuid, b);
        ServerPlayer player = spawnPlayer(helper, uuid, 4.5, 1, 1.5, 0);
        try {
            openMenu(player, station);
            give(player, Mode.HOLD, null, a, b);
            helper.assertValueEqual(first.getState(), DroneState.HOLDING, "first drone after Hold");
            helper.assertValueEqual(second.getState(), DroneState.HOLDING, "second drone after Hold");
            give(player, Mode.RELEASE, null, a);
            helper.assertTrue(!first.hasResumableOrder() && first.getState() == DroneState.IDLE, "Release ends the hold at once, state=" + first.getState());
            helper.assertValueEqual(second.getState(), DroneState.HOLDING, "a drone not named in the Release stays held");
        } finally {
            removePlayer(helper, player);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void releaseEndsAMoveOrder(GameTestHelper helper) {
        CommandCenterBlockEntity station = poweredStation(helper, STATION, 50);
        UUID uuid = UUID.randomUUID();
        String id = newDroneId();
        DroneEntity drone = owned(helper, 3, 1, 6, uuid, id);
        ServerPlayer player = spawnPlayer(helper, uuid, 4.5, 1, 1.5, 0);
        try {
            openMenu(player, station);
            give(player, Mode.MOVE, spotAt(helper, 7.5, 7.5), id);
            helper.assertValueEqual(drone.getState(), DroneState.MOVING, "state after Move");
            give(player, Mode.RELEASE, null, id);
            helper.assertTrue(drone.getState() == DroneState.IDLE && drone.getOrder() == null && !drone.hasResumableOrder(), "Release drops the Move order, state=" + drone.getState());
        } finally {
            removePlayer(helper, player);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void returnToChargeSendsTheDroneToAUsableStation(GameTestHelper helper) {
        CommandCenterBlockEntity station = poweredStation(helper, STATION, 50);
        BlockPos stationRel = new BlockPos(7, 3, 7);
        ChargingStationBlockEntity charger = placeChargingStation(helper, stationRel);
        charger.getEnergyStorage().receiveEnergy(Integer.MAX_VALUE, false);
        UUID uuid = UUID.randomUUID();
        // The station must be placed by an operator of the drone to be usable by an owned drone.
        ChargingStationRegistry.get(helper.getLevel()).add(helper.absolutePos(stationRel), Optional.of(uuid));
        String id = newDroneId();
        DroneEntity drone = spawnDrone(helper, 1, 3, 1, droneData(id).withOwnerId(Optional.of(uuid)));
        ServerPlayer player = spawnPlayer(helper, uuid, 4.5, 1, 1.5, 0);
        try {
            openMenu(player, station);
            give(player, Mode.RETURN_TO_CHARGE, null, id);
            helper.assertValueEqual(drone.getState(), DroneState.RETURNING, "state after Return to Charge");
            helper.assertValueEqual(drone.getChargingStation(), helper.absolutePos(stationRel), "target station");
        } finally {
            removePlayer(helper, player);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void returnToChargeWithoutAUsableStationLeavesTheDroneAlone(GameTestHelper helper) {
        CommandCenterBlockEntity station = poweredStation(helper, STATION, 50);
        UUID uuid = UUID.randomUUID();
        String id = newDroneId();
        // Owned by someone no station of another test belongs to, so none is usable.
        DroneEntity drone = owned(helper, 3, 3, 6, uuid, id);
        ServerPlayer player = spawnPlayer(helper, uuid, 4.5, 1, 1.5, 0);
        try {
            openMenu(player, station);
            give(player, Mode.RETURN_TO_CHARGE, null, id);
            helper.assertTrue(drone.getState() == DroneState.IDLE && drone.getChargingStation() == null, "No usable station: nothing happens, state=" + drone.getState());
        } finally {
            removePlayer(helper, player);
        }
        helper.succeed();
    }

    // =====================================================================================================
    // Move and Strike end piloting (section 7.10)
    // =====================================================================================================

    private static void orderEndsPiloting(GameTestHelper helper, Mode mode, DroneState expected) {
        CommandCenterBlockEntity station = poweredStation(helper, STATION, 50);
        String id = newDroneId();
        UUID uuid = UUID.randomUUID();
        DroneData data = cameraDroneData(id).withOwnerId(Optional.of(uuid)).withUpgradeCount(UpgradeType.EXPLOSIVE, 1);
        DroneEntity drone = spawnDrone(helper, 4, 1, 6, data);
        ServerPlayer player = spawnPlayer(helper, uuid, 4.5, 1, 1.5, 0);
        try {
            openMenu(player, station);
            FpvFeeds.startFromRemote(player, id, true);
            helper.assertTrue(drone.isPiloted() && FpvFeeds.pilotedDrone(player) == drone, "Fixture: the drone is piloted through the Command Center");
            give(player, mode, spotAt(helper, 7.5, 7.5), id);
            helper.assertTrue(!drone.isPiloted() && FpvFeeds.pilotedDrone(player) == null, "The " + mode + " order should end piloting");
            helper.assertValueEqual(drone.getState(), expected, "state after the order");
        } finally {
            removePlayer(helper, player);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void moveEndsPiloting(GameTestHelper helper) {
        orderEndsPiloting(helper, Mode.MOVE, DroneState.MOVING);
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void strikeEndsPiloting(GameTestHelper helper) {
        orderEndsPiloting(helper, Mode.STRIKE, DroneState.STRIKING);
    }
}
