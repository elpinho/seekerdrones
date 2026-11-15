package com.elpinho.seekerdrones.commandcenter;

import static com.elpinho.seekerdrones.gametest.GameTestUtil.assertAlarm;
import static com.elpinho.seekerdrones.gametest.GameTestUtil.cameraDroneData;
import static com.elpinho.seekerdrones.gametest.GameTestUtil.clearTestBarriers;
import static com.elpinho.seekerdrones.gametest.GameTestUtil.droneData;
import static com.elpinho.seekerdrones.gametest.GameTestUtil.foreignDroneData;
import static com.elpinho.seekerdrones.gametest.GameTestUtil.newDroneId;
import static com.elpinho.seekerdrones.gametest.GameTestUtil.placeTwoBlock;
import static com.elpinho.seekerdrones.gametest.GameTestUtil.randomTag;
import static com.elpinho.seekerdrones.gametest.GameTestUtil.removePlayer;
import static com.elpinho.seekerdrones.gametest.GameTestUtil.server;
import static com.elpinho.seekerdrones.gametest.GameTestUtil.setSeekTarget;
import static com.elpinho.seekerdrones.gametest.GameTestUtil.spawnDrone;
import static com.elpinho.seekerdrones.gametest.GameTestUtil.spawnPlayer;
import static com.elpinho.seekerdrones.gametest.GameTestUtil.spawnStationaryZombie;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.drone.DroneOrder;
import com.elpinho.seekerdrones.drone.DroneState;
import com.elpinho.seekerdrones.drone.DroneStats;
import com.elpinho.seekerdrones.drone.UpgradeType;
import com.elpinho.seekerdrones.feed.FpvFeeds;
import com.elpinho.seekerdrones.network.CommandCenterContactsPayload;
import com.elpinho.seekerdrones.network.CommandCenterContactsPayload.Contact;
import com.elpinho.seekerdrones.network.CommandCenterContactsPayload.Details;
import com.elpinho.seekerdrones.network.CommandCenterContactsPayload.Kind;
import com.elpinho.seekerdrones.network.CommandCenterContactsPayload.OwnDetails;
import com.elpinho.seekerdrones.network.CommandCenterTerrainPayload;
import com.elpinho.seekerdrones.network.MapMarkers;
import com.elpinho.seekerdrones.operator.OperatorGroups;
import com.elpinho.seekerdrones.radar.ColorMap;
import com.elpinho.seekerdrones.registry.ModBlocks;
import com.elpinho.seekerdrones.remote.RemoteCommand;
import com.elpinho.seekerdrones.remote.RemoteControl;
import com.elpinho.seekerdrones.remote.RemoteReach;
import com.elpinho.seekerdrones.station.ChargingStationRegistry;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Drone Command Center GameTests (DESIGN.md section 7.10): the block's shape, energy, the POWERED state, the per-viewer
 * own, other and unowned split with details, the terrain slices, the Charging Stations, the intruder alarm, the surface
 * rule for order spots and the station reach rule with a remote session opened through it. The orders themselves are in
 * {@link CommandCenterOrdersGameTests}. They live in the commandcenter package to reach the package-private menu
 * constructor and the protected snapshot builder. Viewers are never registered on a powered station here: the fake
 * connections of test players can't take its custom payloads, so the snapshot is built directly (as
 * {@code UplinkGameTests} does).
 */
@GameTestHolder("seekerdrones")
@PrefixGameTestTemplate(false)
public class CommandCenterGameTests {
    static final String RADIUS_BATCH = "config_command_center_radius_8";
    private static int originalRadius;

    @BeforeBatch(batch = RADIUS_BATCH)
    public static void beforeRadius(ServerLevel level) {
        originalRadius = ServerConfig.get(ServerConfig.COMMAND_CENTER_RADIUS);
        ServerConfig.COMMAND_CENTER_RADIUS.set(8);
    }

    @AfterBatch(batch = RADIUS_BATCH)
    public static void afterRadius(ServerLevel level) {
        ServerConfig.COMMAND_CENTER_RADIUS.set(originalRadius);
    }

    // =====================================================================================================
    // Helpers (package-private: the orders tests use them too)
    // =====================================================================================================

    static CommandCenterBlockEntity placeStation(GameTestHelper helper, BlockPos rel) {
        return placeTwoBlock(helper, rel, ModBlocks.COMMAND_CENTER.get());
    }

    static int perTick() {
        return ServerConfig.get(ServerConfig.COMMAND_CENTER_ENERGY_PER_TICK);
    }

    /** A station with {@code ticks} ticks of FE that has already had its first tick, so it is powered right away. */
    static CommandCenterBlockEntity poweredStation(GameTestHelper helper, BlockPos rel, int ticks) {
        CommandCenterBlockEntity station = placeStation(helper, rel);
        station.getEnergyStorage().receiveEnergy(perTick() * ticks, false);
        station.serverTick(helper.getLevel());
        helper.assertTrue(station.isPowered(), "Fixture: the Command Center should be powered");
        return station;
    }

    static CommandCenterMenu openMenu(ServerPlayer player, CommandCenterBlockEntity station) {
        CommandCenterMenu menu = new CommandCenterMenu(7, station);
        player.containerMenu = menu;
        return menu;
    }

    private static CommandCenterContactsPayload contacts(GameTestHelper helper, CommandCenterBlockEntity station, ServerPlayer viewer) {
        return (CommandCenterContactsPayload) station.contactsPayload(helper.getLevel(), viewer);
    }

    private static Optional<Contact> named(CommandCenterContactsPayload payload, String id) {
        return payload.contacts().stream().filter(c -> c.droneId().equals(id)).findFirst();
    }

    private static Contact namedOrFail(GameTestHelper helper, CommandCenterContactsPayload payload, String id) {
        Optional<Contact> contact = named(payload, id);
        helper.assertTrue(contact.isPresent(), "Expected a contact for " + id + ", saw " + payload.contacts().stream().map(Contact::droneId).toList());
        return contact.get();
    }

    /** The IDs of the contacts that have one and belong to this test (their ID ends with {@code tag}). */
    private static Set<String> ids(CommandCenterContactsPayload payload, String tag) {
        return payload.contacts().stream().map(Contact::droneId).filter(id -> id.endsWith(tag)).collect(java.util.stream.Collectors.toSet());
    }

    /** The contact at the drone's position (the only way to find an unowned drone, which has no ID). */
    private static Optional<Contact> at(CommandCenterContactsPayload payload, CommandCenterBlockEntity station, DroneEntity drone) {
        Vec3 center = Vec3.atCenterOf(station.getBlockPos());
        return payload.contacts().stream().filter(c -> Math.abs(c.dx() - (drone.getX() - center.x)) < 1e-3
                && Math.abs(c.dz() - (drone.getZ() - center.z)) < 1e-3 && Math.abs(c.dy() - (drone.getY() - station.getBlockPos().getY())) < 1e-3).findFirst();
    }

    /** Whether some contact of this kind is at the drone's position (several drones may share a spot). */
    private static boolean listed(CommandCenterContactsPayload payload, CommandCenterBlockEntity station, DroneEntity drone, Kind kind) {
        Vec3 center = Vec3.atCenterOf(station.getBlockPos());
        return payload.contacts().stream().anyMatch(c -> c.kind() == kind && Math.abs(c.dx() - (drone.getX() - center.x)) < 1e-3
                && Math.abs(c.dz() - (drone.getZ() - center.z)) < 1e-3 && Math.abs(c.dy() - (drone.getY() - station.getBlockPos().getY())) < 1e-3);
    }

    /** A spot inside the station's radius whose chunk isn't loaded, or null. */
    static Vec3 findUnloadedSpot(ServerLevel level, CommandCenterBlockEntity station) {
        BlockPos center = station.getBlockPos();
        int radius = station.radius();
        for (int dx = -(radius - 16); dx <= radius - 16; dx += 16) {
            for (int dz = -(radius - 16); dz <= radius - 16; dz += 16) {
                BlockPos column = center.offset(dx, 0, dz);
                if (!level.hasChunkAt(column) && station.inArea(column.getX() + 0.5, column.getZ() + 0.5)) {
                    return new Vec3(column.getX() + 0.5, center.getY(), column.getZ() + 0.5);
                }
            }
        }
        return null;
    }

    // =====================================================================================================
    // 1. Block shape (section 7.10)
    // =====================================================================================================

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void commandCenterUpperHalfHasNoCollisionButHasAnOutline(GameTestHelper helper) {
        BlockPos rel = new BlockPos(4, 1, 4);
        placeStation(helper, rel);
        ServerLevel level = helper.getLevel();
        BlockPos lowerAbs = helper.absolutePos(rel);
        BlockPos upperAbs = lowerAbs.above();
        BlockState lower = level.getBlockState(lowerAbs);
        BlockState upper = level.getBlockState(upperAbs);
        helper.assertTrue(!lower.getCollisionShape(level, lowerAbs).isEmpty(), "The lower half (the table) should have a collision shape");
        helper.assertTrue(upper.getCollisionShape(level, upperAbs).isEmpty(), "The upper half (the hologram) must have no collision shape");
        helper.assertTrue(level.noCollision(new AABB(upperAbs)), "Players should be able to reach into the upper block");
        helper.assertTrue(!upper.getShape(level, upperAbs).isEmpty(), "The upper half should still have a selection outline");
        helper.assertTrue(!lower.getShape(level, lowerAbs).isEmpty(), "The lower half should have an outline");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void commandCenterUpperHalfHasNoCollisionInEveryFacing(GameTestHelper helper) {
        BlockPos rel = new BlockPos(4, 1, 4);
        placeStation(helper, rel);
        ServerLevel level = helper.getLevel();
        BlockPos upperAbs = helper.absolutePos(rel.above());
        BlockState upper = level.getBlockState(upperAbs);
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            BlockState facingState = upper.setValue(com.elpinho.seekerdrones.radar.TwoBlockMachineBlock.FACING, facing);
            helper.assertTrue(facingState.getCollisionShape(level, upperAbs).isEmpty(), "No collision on the upper half facing " + facing);
            helper.assertTrue(!facingState.getShape(level, upperAbs).isEmpty(), "An outline on the upper half facing " + facing);
        }
        helper.succeed();
    }

    // =====================================================================================================
    // 2. Energy and the POWERED state (section 7.10)
    // =====================================================================================================

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void commandCenterUsesExactlyEnergyPerTickEveryTick(GameTestHelper helper) {
        CommandCenterBlockEntity station = placeStation(helper, new BlockPos(4, 1, 4));
        IEnergyStorage energy = station.getEnergyStorage();
        int perTick = perTick();
        helper.assertTrue(perTick > 0, "Fixture needs a positive energyPerTick");
        energy.receiveEnergy(perTick * 20, false);
        int[] last = {-1};
        int[] drops = {0};
        helper.onEachTick(() -> {
            int now = energy.getEnergyStored();
            if (last[0] >= 0 && (now != last[0] || drops[0] > 0)) {
                helper.assertTrue(last[0] - now == perTick, "FE should drop by exactly " + perTick + " per tick, went " + last[0] + " -> " + now);
                drops[0]++;
                if (drops[0] >= 6) {
                    helper.succeed();
                }
            }
            last[0] = now;
        });
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void commandCenterWithLessThanOneTicksEnergyIsUnpoweredAndUsesNothing(GameTestHelper helper) {
        BlockPos rel = new BlockPos(4, 1, 4);
        CommandCenterBlockEntity station = placeStation(helper, rel);
        int stored = perTick() - 1;
        station.getEnergyStorage().receiveEnergy(stored, false);
        helper.runAfterDelay(8, () -> {
            helper.assertTrue(station.getEnergyStorage().getEnergyStored() == stored, "An unpowered Command Center should use no FE, has " + station.getEnergyStorage().getEnergyStored() + " of " + stored);
            helper.assertTrue(!station.isPowered(), "Command Center should be unpowered");
            helper.assertTrue(!helper.getBlockState(rel).getValue(com.elpinho.seekerdrones.radar.TwoBlockMachineBlock.POWERED), "POWERED should be false");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void commandCenterPoweredAtExactlyOneTicksEnergy(GameTestHelper helper) {
        BlockPos rel = new BlockPos(4, 1, 4);
        CommandCenterBlockEntity station = placeStation(helper, rel);
        station.getEnergyStorage().receiveEnergy(perTick(), false);
        station.serverTick(helper.getLevel());
        helper.assertTrue(station.isPowered(), "Exactly energyPerTick FE should be enough to be powered");
        helper.assertTrue(station.getEnergyStorage().getEnergyStored() == 0, "It should have used it");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void commandCenterAcceptsEnergyOnEverySideOfLowerHalfOnly(GameTestHelper helper) {
        BlockPos rel = new BlockPos(4, 1, 4);
        placeStation(helper, rel);
        ServerLevel level = helper.getLevel();
        for (Direction side : Direction.values()) {
            IEnergyStorage cap = level.getCapability(Capabilities.EnergyStorage.BLOCK, helper.absolutePos(rel), side);
            helper.assertTrue(cap != null && cap.canReceive(), "Lower half should accept FE from " + side);
            helper.assertTrue(cap.receiveEnergy(10, true) == 10, "Lower half should take FE from " + side);
            helper.assertTrue(level.getCapability(Capabilities.EnergyStorage.BLOCK, helper.absolutePos(rel.above()), side) == null,
                    "Upper half must have no energy access from " + side);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void commandCenterEnergyCapacityFollowsTheConfig(GameTestHelper helper) {
        CommandCenterBlockEntity station = placeStation(helper, new BlockPos(4, 1, 4));
        int capacity = ServerConfig.get(ServerConfig.COMMAND_CENTER_ENERGY_CAPACITY);
        helper.assertTrue(station.getEnergyStorage().getMaxEnergyStored() == capacity, "Capacity should be commandCenter.energyCapacity=" + capacity);
        helper.assertTrue(station.getEnergyStorage().receiveEnergy(capacity + 5000, false) == capacity, "It should only take up to its capacity");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void commandCenterPoweredStateGoesFalseTrueFalseOnceAndNeverOnTheUpperHalf(GameTestHelper helper) {
        BlockPos rel = new BlockPos(4, 1, 4);
        CommandCenterBlockEntity station = placeStation(helper, rel);
        int perTick = perTick();
        List<Boolean> seen = new java.util.ArrayList<>();
        List<Boolean> upperSeen = new java.util.ArrayList<>();
        helper.onEachTick(() -> {
            seen.add(helper.getBlockState(rel).getValue(com.elpinho.seekerdrones.radar.TwoBlockMachineBlock.POWERED));
            upperSeen.add(helper.getBlockState(rel.above()).getValue(com.elpinho.seekerdrones.radar.TwoBlockMachineBlock.POWERED));
        });
        helper.runAfterDelay(5, () -> station.getEnergyStorage().receiveEnergy(perTick * 2, false));
        helper.runAfterDelay(15, () -> {
            List<Boolean> runs = new java.util.ArrayList<>();
            for (boolean value : seen) {
                if (runs.isEmpty() || runs.get(runs.size() - 1) != value) {
                    runs.add(value);
                }
            }
            helper.assertTrue(runs.equals(List.of(false, true, false)), "POWERED should go false -> true -> false exactly once each, saw runs " + runs + " over " + seen);
            helper.assertTrue(upperSeen.stream().noneMatch(Boolean::booleanValue), "The upper half's POWERED must never be written: " + upperSeen);
            helper.assertTrue(station.getEnergyStorage().getEnergyStored() == 0, "All FE should be used");
            helper.succeed();
        });
    }

    // =====================================================================================================
    // 3. Snapshots per viewer (radius batch: commandCenter.radius = 8)
    // =====================================================================================================

    @GameTest(template = "empty", timeoutTicks = 20, batch = RADIUS_BATCH)
    public static void snapshotSplitsDronesIntoOwnOtherAndUnownedPerViewer(GameTestHelper helper) {
        BlockPos rel = new BlockPos(4, 1, 4);
        CommandCenterBlockEntity station = placeStation(helper, rel);
        OperatorGroups groups = OperatorGroups.get(server(helper));
        UUID viewerId = UUID.randomUUID();
        UUID otherOwner = UUID.randomUUID();
        UUID viewerGroup = groups.createGroup(otherOwner);
        groups.addOperator(viewerGroup, viewerId);
        UUID foreignGroup = groups.createGroup(otherOwner);
        String tag = randomTag();

        DroneData ownData = droneData("own-" + tag).withOwnerId(Optional.of(viewerId));
        ownData = ownData.withConfig(ownData.config().withLabel("Alpha").withColor(DyeColor.RED));
        DroneEntity own = spawnDrone(helper, 2, 1, 2, ownData);
        DroneEntity grouped = spawnDrone(helper, 3, 1, 2, droneData("grp-" + tag).withGroupId(Optional.of(viewerGroup)).withOwnerId(Optional.of(otherOwner)));
        DroneData otherData = droneData("oth-" + tag).withOwnerId(Optional.of(otherOwner));
        otherData = otherData.withConfig(otherData.config().withLabel("Bravo").withColor(DyeColor.BLUE));
        DroneEntity others = spawnDrone(helper, 4, 1, 2, otherData);
        DroneEntity foreignGrp = spawnDrone(helper, 5, 1, 2, droneData("fgrp-" + tag).withGroupId(Optional.of(foreignGroup)));
        DroneEntity unowned = spawnDrone(helper, 6, 1, 2, droneData("unowned-" + tag));
        // The viewer is the owner, but the group takes over and it doesn't exist: nobody owns it.
        DroneEntity unknown = spawnDrone(helper, 7, 1, 2, droneData("unknown-" + tag).withGroupId(Optional.of(UUID.randomUUID())).withOwnerId(Optional.of(viewerId)));

        ServerPlayer viewer = spawnPlayer(helper, viewerId, 1, 1, 6, 0);
        try {
            CommandCenterContactsPayload payload = contacts(helper, station, viewer);
            helper.assertTrue(ids(payload, tag).equals(Set.of("own-" + tag, "grp-" + tag, "oth-" + tag, "fgrp-" + tag)),
                    "Only owned drones (own, group and other players') carry an ID, saw " + ids(payload, tag));
            helper.assertValueEqual(namedOrFail(helper, payload, "own-" + tag).kind(), Kind.OWN, "kind of the viewer's drone");
            helper.assertValueEqual(namedOrFail(helper, payload, "grp-" + tag).kind(), Kind.OWN, "kind of a drone of a group the viewer is in");
            helper.assertValueEqual(namedOrFail(helper, payload, "oth-" + tag).kind(), Kind.OTHER, "kind of another player's drone");
            helper.assertValueEqual(namedOrFail(helper, payload, "fgrp-" + tag).kind(), Kind.OTHER, "kind of a drone of a group the viewer isn't in");
            helper.assertTrue(listed(payload, station, unowned, Kind.UNOWNED), "A drone with no owner is unowned");
            helper.assertTrue(listed(payload, station, unknown, Kind.UNOWNED), "A drone with an unknown group is unowned, even if the viewer is its owner");

            Vec3 center = Vec3.atCenterOf(helper.absolutePos(rel));
            Contact ownContact = namedOrFail(helper, payload, "own-" + tag);
            helper.assertTrue(Math.abs(ownContact.dx() - (own.getX() - center.x)) < 1e-3 && Math.abs(ownContact.dz() - (own.getZ() - center.z)) < 1e-3
                    && Math.abs(ownContact.dy() - (own.getY() - helper.absolutePos(rel).getY())) < 1e-3, "Contact should hold the position relative to the station, got " + ownContact);
            helper.assertTrue(Math.abs(ownContact.yaw() - own.getYRot()) < 1e-3, "Contact should carry the heading");
            helper.assertTrue(ownContact.label().equals("Alpha") && ownContact.color() == DyeColor.RED, "Own contact should carry its label and color, got " + ownContact.label() + " " + ownContact.color());
            Contact otherContact = namedOrFail(helper, payload, "oth-" + tag);
            helper.assertTrue(otherContact.label().equals("Bravo") && otherContact.color() == DyeColor.BLUE,
                    "Another player's drone still carries its label and color (the diamond is drawn in its color), got " + otherContact.label() + " " + otherContact.color());

            for (DroneEntity unnamed : List.of(unowned, unknown)) {
                Contact contact = at(payload, station, unnamed).orElseThrow();
                helper.assertTrue(contact.droneId().isEmpty() && contact.label().isEmpty(), "An unowned drone has no ID and no label, got '" + contact.droneId() + "' '" + contact.label() + "'");
            }
            helper.assertTrue(at(payload, station, grouped).isPresent() && at(payload, station, others).isPresent() && at(payload, station, foreignGrp).isPresent(),
                    "Every drone in the area is listed");
        } finally {
            removePlayer(helper, viewer);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20, batch = RADIUS_BATCH)
    public static void snapshotHasNoServerOperatorBypass(GameTestHelper helper) {
        CommandCenterBlockEntity station = placeStation(helper, new BlockPos(4, 1, 4));
        UUID ownerId = UUID.randomUUID();
        String tag = randomTag();
        DroneEntity owned = spawnDrone(helper, 3, 1, 3, droneData("owned-" + tag).withOwnerId(Optional.of(ownerId)));
        DroneEntity unowned = spawnDrone(helper, 5, 1, 3, droneData("unowned-" + tag));
        ServerPlayer op = spawnPlayer(helper, UUID.randomUUID(), 1, 1, 6, 4);
        ServerPlayer owner = spawnPlayer(helper, ownerId, 2, 1, 6, 0);
        try {
            helper.assertTrue(op.hasPermissions(2), "Fixture: the player should be a server operator");
            CommandCenterContactsPayload opView = contacts(helper, station, op);
            helper.assertTrue(opView.contacts().stream().noneMatch(c -> c.kind() == Kind.OWN && c.droneId().endsWith(tag)), "A server operator must not get any drone as their own");
            helper.assertValueEqual(namedOrFail(helper, opView, "owned-" + tag).kind(), Kind.OTHER, "the op's view of someone else's drone");
            helper.assertTrue(listed(opView, station, unowned, Kind.UNOWNED), "the op's view of an unowned drone");
            CommandCenterContactsPayload ownerView = contacts(helper, station, owner);
            helper.assertValueEqual(namedOrFail(helper, ownerView, "owned-" + tag).kind(), Kind.OWN, "the owner's view");
            helper.assertTrue(listed(ownerView, station, unowned, Kind.UNOWNED) && !listed(ownerView, station, owned, Kind.UNOWNED), "The owner sees the unowned drone as unowned only");
        } finally {
            removePlayer(helper, op);
            removePlayer(helper, owner);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20, batch = RADIUS_BATCH)
    public static void detailsCarryOwnDetailsForOwnDronesOnly(GameTestHelper helper) {
        BlockPos rel = new BlockPos(4, 1, 4);
        CommandCenterBlockEntity station = placeStation(helper, rel);
        UUID viewerId = UUID.randomUUID();
        UUID otherOwner = UUID.randomUUID();
        String tag = randomTag();
        GlobalPos center = GlobalPos.of(helper.getLevel().dimension(), helper.absolutePos(new BlockPos(6, 1, 6)));

        DroneData ownData = droneData("own-" + tag).withOwnerId(Optional.of(viewerId)).withUpgradeCount(UpgradeType.PATROL, 1).withUpgradeCount(UpgradeType.EXPLOSIVE, 2);
        ownData = ownData.withConfig(ownData.config().withPatrolRadius(Optional.of(4))).withEnergy(DroneStats.maxEnergy(ownData) / 2);
        DroneEntity own = spawnDrone(helper, 2, 1, 2, ownData);
        own.setPatrolCenter(center);
        DroneData otherData = droneData("oth-" + tag).withOwnerId(Optional.of(otherOwner)).withUpgradeCount(UpgradeType.PATROL, 1);
        DroneEntity others = spawnDrone(helper, 3, 1, 2, otherData);
        others.setPatrolCenter(center);
        DroneEntity othersNoPatrol = spawnDrone(helper, 4, 1, 2, droneData("oth2-" + tag).withOwnerId(Optional.of(otherOwner)));
        DroneEntity unowned = spawnDrone(helper, 5, 1, 2, droneData("unowned-" + tag).withUpgradeCount(UpgradeType.PATROL, 1));
        unowned.setPatrolCenter(center);
        // A Move order, so the details carry an order with its ticks left.
        own.moveTo(helper.absoluteVec(new Vec3(4.5, 1, 6.5)));

        ServerPlayer viewer = spawnPlayer(helper, viewerId, 1, 1, 6, 0);
        try {
            station.createMenu(7, viewer.getInventory(), viewer);
            CommandCenterContactsPayload payload = contacts(helper, station, viewer);

            Details ownDetails = namedOrFail(helper, payload, "own-" + tag).details().orElse(null);
            helper.assertTrue(ownDetails != null, "The first snapshot after opening carries details");
            helper.assertTrue(ownDetails.health() == own.getHealth() && ownDetails.maxHealth() == own.getMaxHealth(), "Health and max health, got " + ownDetails);
            helper.assertTrue(ownDetails.patrol().isPresent(), "An own drone with Patrol carries its ring");
            MapMarkers.Patrol ring = ownDetails.patrol().get();
            helper.assertTrue(Math.abs(ring.dx() - 2.0F) < 1e-3 && Math.abs(ring.dz() - 2.0F) < 1e-3 && ring.radius() == DroneStats.patrolRadius(own.snapshotData()),
                    "The ring is relative to the station (2, 2) with the drone's radius, got " + ring);
            helper.assertTrue(ownDetails.own().isPresent(), "An own drone carries OwnDetails");
            OwnDetails extra = ownDetails.own().get();
            helper.assertTrue(extra.energy() == own.getEnergy() && extra.maxEnergy() == DroneStats.maxEnergy(own.snapshotData()) && extra.maxEnergy() > extra.energy(),
                    "Energy and max energy, got " + extra.energy() + "/" + extra.maxEnergy());
            helper.assertTrue(Integer.valueOf(1).equals(extra.upgrades().get(UpgradeType.PATROL)) && Integer.valueOf(2).equals(extra.upgrades().get(UpgradeType.EXPLOSIVE)),
                    "Upgrades with their counts, got " + extra.upgrades());
            helper.assertValueEqual(extra.state(), DroneState.MOVING, "state in the details");
            helper.assertTrue(extra.order().isPresent() && extra.order().get().order() == DroneOrder.MOVE
                    && extra.order().get().ticksLeft() == ServerConfig.get(ServerConfig.COMMAND_CENTER_MOVE_TIMEOUT_TICKS),
                    "The order and its ticks left, got " + extra.order());

            Details otherDetails = namedOrFail(helper, payload, "oth-" + tag).details().orElse(null);
            helper.assertTrue(otherDetails != null, "Another player's drone carries details on the first snapshot too");
            helper.assertTrue(otherDetails.own().isEmpty(), "Another player's drone must have no OwnDetails (energy, upgrades, state, order)");
            helper.assertTrue(otherDetails.health() == others.getHealth() && otherDetails.patrol().isPresent(), "Health and the patrol ring only, got " + otherDetails);
            Details noPatrol = namedOrFail(helper, payload, "oth2-" + tag).details().orElseThrow();
            helper.assertTrue(noPatrol.patrol().isEmpty() && noPatrol.own().isEmpty(), "No Patrol upgrade: no ring, got " + noPatrol);
            helper.assertTrue(othersNoPatrol.isAlive(), "Fixture drone alive");

            Contact unownedContact = at(payload, station, unowned).orElseThrow();
            helper.assertTrue(unownedContact.details().isPresent(), "An unowned drone carries its health in every snapshot");
            Details unownedDetails = unownedContact.details().get();
            helper.assertTrue(unownedDetails.health() == unowned.getHealth() && unownedDetails.maxHealth() == unowned.getMaxHealth(), "Unowned health, got " + unownedDetails);
            helper.assertTrue(unownedDetails.patrol().isEmpty() && unownedDetails.own().isEmpty(), "An unowned drone carries nothing but its health, got " + unownedDetails);
        } finally {
            removePlayer(helper, viewer);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40, batch = RADIUS_BATCH)
    public static void detailsComeOnTheFirstSnapshotAndThenOnlyOnDetailTicksWhileUnownedHealthIsAlwaysThere(GameTestHelper helper) {
        BlockPos rel = new BlockPos(4, 1, 4);
        CommandCenterBlockEntity station = placeStation(helper, rel);
        UUID viewerId = UUID.randomUUID();
        String tag = randomTag();
        spawnDrone(helper, 2, 1, 2, droneData("own-" + tag).withOwnerId(Optional.of(viewerId)));
        spawnDrone(helper, 3, 1, 2, foreignDroneData("oth-" + tag));
        DroneEntity unowned = spawnDrone(helper, 5, 1, 2, droneData("unowned-" + tag));
        ServerPlayer viewer = spawnPlayer(helper, viewerId, 1, 1, 6, 0);
        station.createMenu(7, viewer.getInventory(), viewer);
        CommandCenterContactsPayload first = contacts(helper, station, viewer);
        helper.assertTrue(namedOrFail(helper, first, "own-" + tag).details().isPresent() && namedOrFail(helper, first, "oth-" + tag).details().isPresent(),
                "The first snapshot after opening carries details");
        AtomicInteger own = new AtomicInteger();
        AtomicInteger ownWith = new AtomicInteger();
        AtomicInteger ownWithout = new AtomicInteger();
        helper.onEachTick(() -> {
            CommandCenterContactsPayload payload = contacts(helper, station, viewer);
            boolean details = namedOrFail(helper, payload, "own-" + tag).details().isPresent();
            helper.assertTrue(namedOrFail(helper, payload, "oth-" + tag).details().isPresent() == details, "Own and other drones' details come together");
            helper.assertTrue(at(payload, station, unowned).orElseThrow().details().isPresent(), "Unowned health is in every snapshot, even without details");
            (details ? ownWith : ownWithout).incrementAndGet();
            if (own.incrementAndGet() >= 25) {
                helper.assertTrue(ownWith.get() > 0 && ownWithout.get() > 0,
                        "Over 25 ticks (detailInterval 20) some snapshots carry details and some don't: with=" + ownWith + " without=" + ownWithout);
                removePlayer(helper, viewer);
                helper.succeed();
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 20, batch = RADIUS_BATCH)
    public static void areaIsHorizontalAndIncludesTheEdgeAtAnyHeightForEveryKind(GameTestHelper helper) {
        helper.assertTrue(ServerConfig.get(ServerConfig.COMMAND_CENTER_RADIUS) == 8, "Fixture: radius should be 8 in this batch");
        CommandCenterBlockEntity station = placeStation(helper, new BlockPos(0, 1, 0));
        UUID viewerId = UUID.randomUUID();
        String tag = randomTag();
        // Distances from the station's center (0.5, 0.5): 5.1, exactly 8.0, 11.3, and 3.5 but 60 blocks up.
        spawnDrone(helper, 4, 1, 4, droneData("near-" + tag).withOwnerId(Optional.of(viewerId)));
        spawnDrone(helper, 8, 1, 0, droneData("edge-" + tag).withOwnerId(Optional.of(viewerId)));
        spawnDrone(helper, 8, 1, 8, droneData("far-" + tag).withOwnerId(Optional.of(viewerId)));
        DroneEntity high = spawnDrone(helper, 3, 1, 3, droneData("high-" + tag).withOwnerId(Optional.of(viewerId)));
        high.setNoGravity(true);
        high.moveTo(high.getX(), high.getY() + 60, high.getZ());
        DroneEntity othersEdge = spawnDrone(helper, 0, 1, 8, foreignDroneData("o-edge-" + tag));
        DroneEntity othersFar = spawnDrone(helper, 8, 1, 8, foreignDroneData("o-far-" + tag));
        othersFar.setNoGravity(true);
        DroneEntity unownedFar = spawnDrone(helper, 8, 1, 7, droneData("u-far-" + tag));
        unownedFar.setNoGravity(true);
        DroneEntity unownedEdge = spawnDrone(helper, 0, 1, 8, droneData("u-edge-" + tag));
        unownedEdge.setNoGravity(true);

        ServerPlayer viewer = spawnPlayer(helper, viewerId, 1, 1, 6, 0);
        try {
            CommandCenterContactsPayload payload = contacts(helper, station, viewer);
            Set<String> seen = ids(payload, tag);
            helper.assertTrue(seen.contains("near-" + tag), "A drone inside the radius should be listed, saw " + seen);
            helper.assertTrue(seen.contains("edge-" + tag), "A drone exactly at the radius should be listed, saw " + seen);
            helper.assertTrue(seen.contains("high-" + tag), "Height doesn't count: a drone 60 blocks up inside the radius should be listed, saw " + seen);
            helper.assertTrue(!seen.contains("far-" + tag), "A drone outside the radius must not be listed, saw " + seen);
            helper.assertTrue(Math.abs(namedOrFail(helper, payload, "high-" + tag).dy() - 60.0F) < 1.0F, "The altitude should be relative to the station");
            helper.assertTrue(listed(payload, station, othersEdge, Kind.OTHER), "Someone else's drone at the radius should be listed");
            helper.assertTrue(!seen.contains("o-far-" + tag), "Someone else's drone outside the radius must not be listed");
            helper.assertTrue(at(payload, station, othersFar).isEmpty(), "...not even by position");
            helper.assertTrue(listed(payload, station, unownedEdge, Kind.UNOWNED), "An unowned drone at the radius should be listed");
            helper.assertTrue(at(payload, station, unownedFar).isEmpty(), "An unowned drone outside the radius must not be listed");
        } finally {
            removePlayer(helper, viewer);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20, batch = RADIUS_BATCH)
    public static void everyDroneCarriesItsTrackingTargetNotOnlyTheViewersOwn(GameTestHelper helper) {
        BlockPos rel = new BlockPos(4, 1, 4);
        CommandCenterBlockEntity station = placeStation(helper, rel);
        UUID viewerId = UUID.randomUUID();
        String tag = randomTag();
        DroneEntity own = spawnDrone(helper, 2, 1, 2, droneData("own-" + tag).withOwnerId(Optional.of(viewerId)));
        DroneEntity idle = spawnDrone(helper, 3, 1, 2, droneData("idle-" + tag).withOwnerId(Optional.of(viewerId)));
        DroneEntity others = spawnDrone(helper, 5, 1, 2, foreignDroneData("oth-" + tag));
        DroneEntity unowned = spawnDrone(helper, 6, 1, 2, droneData("unowned-" + tag));
        Zombie zombie = spawnStationaryZombie(helper, 7, 1, 6);
        ServerPlayer targetPlayer = spawnPlayer(helper, UUID.randomUUID(), 2.5, 1, 7.5, 0);
        ServerPlayer viewer = spawnPlayer(helper, viewerId, 1, 1, 7, 0);
        try {
            setSeekTarget(own, zombie);
            setSeekTarget(others, zombie);
            setSeekTarget(unowned, targetPlayer);
            CommandCenterContactsPayload payload = contacts(helper, station, viewer);
            Vec3 center = Vec3.atCenterOf(helper.absolutePos(rel));

            for (Contact contact : List.of(namedOrFail(helper, payload, "own-" + tag), namedOrFail(helper, payload, "oth-" + tag))) {
                helper.assertTrue(contact.target().isPresent(), "A drone tracking a zombie should carry the target (" + contact.kind() + ")");
                MapMarkers.Target target = contact.target().get();
                helper.assertTrue(Math.abs(target.dx() - (zombie.getX() - center.x)) < 1e-3 && Math.abs(target.dz() - (zombie.getZ() - center.z)) < 1e-3,
                        "Target position should be relative to the station, got " + target);
                helper.assertTrue(target.type() == EntityType.ZOMBIE && target.playerName().isEmpty(), "Zombie type and no player name, got " + target);
            }
            Contact unownedContact = at(payload, station, unowned).orElseThrow();
            helper.assertTrue(unownedContact.target().isPresent() && unownedContact.target().get().type() == EntityType.PLAYER
                    && unownedContact.target().get().playerName().equals(targetPlayer.getGameProfile().getName()), "An unowned drone tracking a player carries the player's name, got " + unownedContact.target());
            helper.assertTrue(namedOrFail(helper, payload, "idle-" + tag).target().isEmpty(), "A drone with no target carries none");
            helper.assertTrue(idle.isAlive(), "Fixture drone alive");
        } finally {
            removePlayer(helper, viewer);
            removePlayer(helper, targetPlayer);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20, batch = RADIUS_BATCH)
    public static void unownedDronesNeverLeakAnIdOrLabelOnTheWireAndThePayloadRoundTrips(GameTestHelper helper) {
        CommandCenterBlockEntity station = placeStation(helper, new BlockPos(4, 1, 4));
        String tag = randomTag();
        DroneData secret = droneData("SECRETID" + tag);
        secret = secret.withConfig(secret.config().withLabel("SECRETLABEL" + tag).withColor(DyeColor.PINK));
        DroneEntity unowned = spawnDrone(helper, 3, 1, 3, secret);
        ServerPlayer viewer = spawnPlayer(helper, UUID.randomUUID(), 1, 1, 6, 0);
        try {
            CommandCenterContactsPayload payload = contacts(helper, station, viewer);
            Contact contact = at(payload, station, unowned).orElseThrow();
            helper.assertTrue(contact.kind() == Kind.UNOWNED && contact.color() == DyeColor.GRAY, "Unowned contact has no color (gray), got " + contact);
            RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), server(helper).registryAccess());
            CommandCenterContactsPayload.STREAM_CODEC.encode(buf, payload);
            String bytes = buf.toString(StandardCharsets.UTF_8);
            helper.assertTrue(!bytes.contains("SECRETID") && !bytes.contains("SECRETLABEL"), "The wire form must not leak an unowned drone's ID or label");
            CommandCenterContactsPayload decoded = CommandCenterContactsPayload.STREAM_CODEC.decode(buf);
            helper.assertTrue(decoded.equals(payload), "The payload should survive its stream codec");
        } finally {
            removePlayer(helper, viewer);
        }
        helper.succeed();
    }

    // =====================================================================================================
    // 4. Charging Stations (radius batch)
    // =====================================================================================================

    @GameTest(template = "empty", timeoutTicks = 20, batch = RADIUS_BATCH)
    public static void snapshotListsOnlyStationsPlacedByTheViewerOrAGroupTheyOperate(GameTestHelper helper) {
        BlockPos rel = new BlockPos(4, 1, 4);
        CommandCenterBlockEntity station = placeStation(helper, rel);
        ServerLevel level = helper.getLevel();
        ChargingStationRegistry registry = ChargingStationRegistry.get(level);
        OperatorGroups groups = OperatorGroups.get(server(helper));
        UUID viewerId = UUID.randomUUID();
        UUID groupOwner = UUID.randomUUID();
        UUID otherPlacer = UUID.randomUUID();
        UUID viewerGroup = groups.createGroup(groupOwner);
        groups.addOperator(viewerGroup, viewerId);
        groups.createGroup(otherPlacer);

        BlockPos mine = helper.absolutePos(new BlockPos(2, 1, 2));
        BlockPos viaGroup = helper.absolutePos(new BlockPos(6, 1, 2));
        BlockPos theirs = helper.absolutePos(new BlockPos(2, 1, 6));
        BlockPos noPlacer = helper.absolutePos(new BlockPos(6, 1, 6));
        BlockPos outside = helper.absolutePos(new BlockPos(4 + 12, 1, 4));
        ServerPlayer viewer = spawnPlayer(helper, viewerId, 1, 1, 7, 0);
        try {
            registry.add(mine, Optional.of(viewerId));
            registry.add(viaGroup, Optional.of(groupOwner));
            registry.add(theirs, Optional.of(otherPlacer));
            registry.add(noPlacer, Optional.empty());
            registry.add(outside, Optional.of(viewerId));
            Set<MapMarkers.Station> stations = Set.copyOf(contacts(helper, station, viewer).stations());
            BlockPos base = helper.absolutePos(rel);
            helper.assertTrue(stations.contains(new MapMarkers.Station(mine.getX() - base.getX(), mine.getZ() - base.getZ())), "The viewer's own station should be listed, saw " + stations);
            helper.assertTrue(stations.contains(new MapMarkers.Station(viaGroup.getX() - base.getX(), viaGroup.getZ() - base.getZ())),
                    "A station placed by the owner of a group the viewer operates should be listed, saw " + stations);
            helper.assertTrue(!stations.contains(new MapMarkers.Station(theirs.getX() - base.getX(), theirs.getZ() - base.getZ())), "Another player's station must be hidden");
            helper.assertTrue(!stations.contains(new MapMarkers.Station(noPlacer.getX() - base.getX(), noPlacer.getZ() - base.getZ())), "A station with no placer must be hidden");
            helper.assertTrue(!stations.contains(new MapMarkers.Station(outside.getX() - base.getX(), outside.getZ() - base.getZ())), "A station outside the area must be hidden");
        } finally {
            for (BlockPos pos : List.of(mine, viaGroup, theirs, noPlacer, outside)) {
                registry.remove(pos);
            }
            removePlayer(helper, viewer);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20, batch = RADIUS_BATCH)
    public static void snapshotHidesOtherPlayersStationsFromAServerOp(GameTestHelper helper) {
        CommandCenterBlockEntity station = placeStation(helper, new BlockPos(4, 1, 4));
        ChargingStationRegistry registry = ChargingStationRegistry.get(helper.getLevel());
        BlockPos pos = helper.absolutePos(new BlockPos(2, 1, 2));
        ServerPlayer op = spawnPlayer(helper, UUID.randomUUID(), 1, 1, 7, 4);
        try {
            registry.add(pos, Optional.of(UUID.randomUUID()));
            helper.assertTrue(contacts(helper, station, op).stations().isEmpty(), "A server operator doesn't see other players' stations (no bypass)");
        } finally {
            registry.remove(pos);
            removePlayer(helper, op);
        }
        helper.succeed();
    }

    // =====================================================================================================
    // 5. Terrain slices (section 7.10), at radius 256: a 513 x 513 map
    // =====================================================================================================

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void terrainSlicesCoverEveryRowExactlyOnceUnderTheSizeCapAtRadius256(GameTestHelper helper) {
        int radius = 256;
        int size = radius * 2 + 1;
        byte[] colors = new byte[size * size];
        for (int i = 0; i < colors.length; i++) {
            colors[i] = (byte) (i * 31 + i / size);
        }
        BlockPos pos = new BlockPos(1, 2, 3);
        List<CommandCenterTerrainPayload> slices = CommandCenterTerrainPayload.slices(pos, new ColorMap(radius, colors));
        helper.assertTrue(slices.size() > 1, "A 263 KB map needs several slices, got " + slices.size());
        int nextRow = 0;
        byte[] joined = new byte[colors.length];
        for (int i = 0; i < slices.size(); i++) {
            CommandCenterTerrainPayload slice = slices.get(i);
            helper.assertTrue(slice.pos().equals(pos) && slice.radius() == radius, "Every slice names the station and the radius, got " + slice.pos() + " " + slice.radius());
            helper.assertTrue(slice.colors().length <= CommandCenterTerrainPayload.SLICE_BYTES, "Slice " + i + " is " + slice.colors().length + " bytes, over the cap of " + CommandCenterTerrainPayload.SLICE_BYTES);
            helper.assertTrue(slice.colors().length > 0 && slice.colors().length % size == 0, "Slices hold whole rows, slice " + i + " has " + slice.colors().length + " bytes");
            helper.assertTrue(slice.firstRow() == nextRow, "Slice " + i + " should start at row " + nextRow + ", starts at " + slice.firstRow());
            System.arraycopy(slice.colors(), 0, joined, slice.firstRow() * size, slice.colors().length);
            nextRow += slice.colors().length / size;
            helper.assertTrue(slice.last() == (i == slices.size() - 1), "Only the final slice has the last flag, slice " + i + " has " + slice.last());
        }
        helper.assertTrue(nextRow == size, "The slices should cover all " + size + " rows exactly once, covered " + nextRow);
        helper.assertTrue(java.util.Arrays.equals(joined, colors), "The slices put together should be the map");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void terrainSliceStreamCodecRoundTripsAndASmallMapIsOneLastSlice(GameTestHelper helper) {
        byte[] colors = new byte[17 * 17];
        for (int i = 0; i < colors.length; i++) {
            colors[i] = (byte) i;
        }
        List<CommandCenterTerrainPayload> slices = CommandCenterTerrainPayload.slices(BlockPos.ZERO, new ColorMap(8, colors));
        helper.assertTrue(slices.size() == 1 && slices.get(0).last() && slices.get(0).firstRow() == 0, "A small map is a single final slice, got " + slices.size() + " slices");
        io.netty.buffer.ByteBuf buf = Unpooled.buffer();
        CommandCenterTerrainPayload.STREAM_CODEC.encode(buf, slices.get(0));
        CommandCenterTerrainPayload decoded = CommandCenterTerrainPayload.STREAM_CODEC.decode(buf);
        helper.assertTrue(decoded.radius() == 8 && decoded.last() && java.util.Arrays.equals(decoded.colors(), colors), "The slice should survive its codec");
        helper.succeed();
    }

    // =====================================================================================================
    // 6. Intruder alarm (radius batch). Drones stay within 4 blocks of the station's center.
    // =====================================================================================================

    @GameTest(template = "empty", timeoutTicks = 40, batch = RADIUS_BATCH)
    public static void alarmCountsDronesThePlacerIsNotAnOperatorOfAndBothHalvesReadIt(GameTestHelper helper) {
        BlockPos rel = new BlockPos(4, 1, 4);
        CommandCenterBlockEntity station = poweredStation(helper, rel, 100);
        UUID placer = UUID.randomUUID();
        station.setPlacer(placer);
        helper.assertTrue(station.viewers().isEmpty(), "Fixture: nobody watches");
        OperatorGroups groups = OperatorGroups.get(server(helper));
        UUID placerGroup = groups.createGroup(UUID.randomUUID());
        groups.addOperator(placerGroup, placer);
        // Not counted: the placer's own drone, and a drone of a group the placer operates.
        spawnDrone(helper, 2, 1, 2, droneData(newDroneId()).withOwnerId(Optional.of(placer)));
        spawnDrone(helper, 3, 1, 2, droneData(newDroneId()).withGroupId(Optional.of(placerGroup)));
        // Counted: two other players' drones and an unowned one.
        spawnDrone(helper, 5, 1, 2, foreignDroneData(newDroneId()));
        spawnDrone(helper, 6, 1, 2, foreignDroneData(newDroneId()));
        spawnDrone(helper, 7, 1, 2, droneData(newDroneId()));
        helper.succeedWhen(() -> assertAlarm(helper, station, rel, 3));
    }

    @GameTest(template = "empty", timeoutTicks = 40, batch = RADIUS_BATCH)
    public static void alarmIsCappedAtFifteen(GameTestHelper helper) {
        BlockPos rel = new BlockPos(4, 1, 4);
        CommandCenterBlockEntity station = poweredStation(helper, rel, 100);
        station.setPlacer(UUID.randomUUID());
        for (int i = 0; i < 18; i++) {
            spawnDrone(helper, i % 9, 1, 1 + (i / 9) * 6, foreignDroneData(newDroneId()));
        }
        helper.succeedWhen(() -> assertAlarm(helper, station, rel, 15));
    }

    @GameTest(template = "empty", timeoutTicks = 60, batch = RADIUS_BATCH)
    public static void alarmDropsToZeroWhenTheCommandCenterLosesPower(GameTestHelper helper) {
        BlockPos rel = new BlockPos(4, 1, 4);
        CommandCenterBlockEntity station = placeStation(helper, rel);
        station.setPlacer(UUID.randomUUID());
        // Enough FE for about 25 ticks: a few scans (every 5 ticks), then it runs dry.
        station.getEnergyStorage().receiveEnergy(perTick() * 25, false);
        spawnDrone(helper, 2, 1, 2, foreignDroneData(newDroneId()));
        spawnDrone(helper, 3, 1, 2, foreignDroneData(newDroneId()));
        AtomicBoolean sawTwo = new AtomicBoolean();
        helper.runAfterDelay(55, () -> helper.fail("Never saw the alarm drop: sawTwo=" + sawTwo + ", powered=" + station.isPowered() + ", alarm=" + station.getAlarm()
                + ", FE=" + station.getEnergyStorage().getEnergyStored()));
        helper.onEachTick(() -> {
            if (station.isPowered() && station.getAlarm() == 2) {
                sawTwo.set(true);
                helper.assertTrue(com.elpinho.seekerdrones.gametest.GameTestUtil.signal(helper, rel) == 2
                        && com.elpinho.seekerdrones.gametest.GameTestUtil.signal(helper, rel.above()) == 2, "Both halves should show the alarm while powered");
            }
            if (!station.isPowered() && sawTwo.get()) {
                assertAlarm(helper, station, rel, 0);
                helper.succeed();
            }
        });
    }

    // =====================================================================================================
    // 7. The surface rule for order spots (section 7.10)
    // =====================================================================================================

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void orderTopIsTheTopOfTheTopBlockAtTheSpotCenteredOnTheColumn(GameTestHelper helper) {
        clearTestBarriers(helper);
        CommandCenterBlockEntity station = placeStation(helper, new BlockPos(4, 1, 4));
        ServerLevel level = helper.getLevel();
        // A one-block column and a taller one.
        helper.setBlock(new BlockPos(6, 0, 6), Blocks.STONE);
        for (int y = 0; y <= 3; y++) {
            helper.setBlock(new BlockPos(2, y, 2), Blocks.STONE);
        }
        BlockPos low = helper.absolutePos(new BlockPos(6, 0, 6));
        BlockPos tall = helper.absolutePos(new BlockPos(2, 3, 2));
        Vec3 lowTop = station.orderTop(level, low.getX() + 0.3, low.getZ() + 0.8).orElse(null);
        helper.assertTrue(lowTop != null, "A loaded column has a top");
        helper.assertTrue(lowTop.x == low.getX() + 0.5 && lowTop.z == low.getZ() + 0.5 && lowTop.y == low.getY() + 1, "The top should be the block's top, centered: expected "
                + new Vec3(low.getX() + 0.5, low.getY() + 1, low.getZ() + 0.5) + ", got " + lowTop);
        Vec3 tallTop = station.orderTop(level, tall.getX() + 0.5, tall.getZ() + 0.5).orElse(null);
        helper.assertTrue(tallTop != null && tallTop.y == tall.getY() + 1, "A taller column's top is the highest block's top, expected y=" + (tall.getY() + 1) + ", got " + tallTop);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void orderTopIsEmptyInAnUnloadedChunk(GameTestHelper helper) {
        CommandCenterBlockEntity station = placeStation(helper, new BlockPos(4, 1, 4));
        ServerLevel level = helper.getLevel();
        Vec3 spot = findUnloadedSpot(level, station);
        helper.assertTrue(spot != null, "Fixture: expected to find an unloaded chunk within the area");
        helper.assertTrue(station.orderTop(level, spot.x, spot.z).isEmpty(), "No top in an unloaded chunk");
        helper.assertTrue(!level.hasChunkAt(BlockPos.containing(spot)), "...and asking must not load it");
        helper.succeed();
    }

    /** In the Nether (a dimension with a ceiling) the top is the highest non-air block at or below the station's Y. */
    @GameTest(template = "empty", timeoutTicks = 10)
    public static void orderTopInADimensionWithACeilingIgnoresBlocksAboveTheStation(GameTestHelper helper) {
        ServerLevel nether = server(helper).getLevel(Level.NETHER);
        helper.assertTrue(nether != null && nether.dimensionType().hasCeiling(), "Sanity: the Nether should be loaded and have a ceiling");
        nether.getChunk(0, 0);
        int stationY = 70;
        CommandCenterBlockEntity station = new CommandCenterBlockEntity(new BlockPos(8, stationY, 8), ModBlocks.COMMAND_CENTER.get().defaultBlockState());
        BlockPos floor = new BlockPos(10, stationY, 10);
        List<BlockPos> changed = new java.util.ArrayList<>();
        try {
            // A block at the station's own height is the highest one at or below it; blocks above must be ignored.
            for (int y = stationY; y <= stationY + 4; y++) {
                BlockPos pos = new BlockPos(floor.getX(), y, floor.getZ());
                changed.add(pos);
                nether.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
            }
            Vec3 top = station.orderTop(nether, floor.getX() + 0.5, floor.getZ() + 0.5).orElse(null);
            helper.assertTrue(top != null && top.y == stationY + 1 && top.x == floor.getX() + 0.5 && top.z == floor.getZ() + 0.5,
                    "With a ceiling the top is the highest block at or below the station's Y (" + (stationY + 1) + "), got " + top);
        } finally {
            for (BlockPos pos : changed) {
                nether.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            }
        }
        helper.succeed();
    }

    // =====================================================================================================
    // 8. The station reach rule, with a CommandCenterMenu open (sections 7.9 and 7.10)
    // =====================================================================================================

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void remoteAccessNeedsTheCommandCenterMenuOpenAndStillValid(GameTestHelper helper) {
        CommandCenterBlockEntity station = poweredStation(helper, new BlockPos(4, 1, 4), 50);
        String id = newDroneId();
        UUID uuid = UUID.randomUUID();
        spawnDrone(helper, 4, 1, 6, droneData(id).withOwnerId(Optional.of(uuid)));
        ServerPlayer player = spawnPlayer(helper, uuid, 4.5, 1, 1.5, 0);
        try {
            helper.assertTrue(RemoteControl.access(player, id).isEmpty(), "Without the menu open and no remote in hand there is no access");
            openMenu(player, station);
            Optional<RemoteControl.Access> access = RemoteControl.access(player, id);
            helper.assertTrue(access.isPresent() && access.get().viaStation() && access.get().station() == station, "With the menu open, access is through the Command Center");
            helper.assertTrue(access.get().link().droneId().equals(id), "The link should be for the drone");
            helper.assertTrue(RemoteControl.access(player, "").isEmpty(), "An empty drone ID gets no access");
            player.moveTo(helper.absoluteVec(new Vec3(4.5, 1, 60)));
            helper.assertTrue(player.containerMenu instanceof CommandCenterMenu && !player.containerMenu.stillValid(player), "Fixture: the menu is out of reach");
            helper.assertTrue(RemoteControl.access(player, id).isEmpty(), "A menu that is not still valid gives no access");
        } finally {
            removePlayer(helper, player);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void reachIsOkForAnOperatorsDroneInTheArea(GameTestHelper helper) {
        CommandCenterBlockEntity station = poweredStation(helper, new BlockPos(4, 1, 4), 50);
        String id = newDroneId();
        UUID uuid = UUID.randomUUID();
        DroneEntity drone = spawnDrone(helper, 4, 1, 8, droneData(id).withOwnerId(Optional.of(uuid)));
        ServerPlayer player = spawnPlayer(helper, uuid, 4.5, 1, 1.5, 0);
        try {
            openMenu(player, station);
            RemoteControl.Target target = RemoteControl.findThroughStation(player, id, station);
            helper.assertValueEqual(target.reach(), RemoteReach.OK, "reach");
            helper.assertTrue(target.drone() == drone, "The drone should be found");
            helper.assertTrue(Math.abs(target.distance() - 4) <= 1, "The distance is measured from the station horizontally (about 4), got " + target.distance());
        } finally {
            removePlayer(helper, player);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void reachIsCommandCenterNoPowerWhenUnpowered(GameTestHelper helper) {
        CommandCenterBlockEntity station = placeStation(helper, new BlockPos(4, 1, 4));
        String id = newDroneId();
        UUID uuid = UUID.randomUUID();
        spawnDrone(helper, 4, 1, 6, droneData(id).withOwnerId(Optional.of(uuid)));
        ServerPlayer player = spawnPlayer(helper, uuid, 4.5, 1, 1.5, 0);
        try {
            openMenu(player, station);
            helper.assertTrue(!station.isPowered(), "Fixture: unpowered");
            RemoteControl.Target target = RemoteControl.findThroughStation(player, id, station);
            helper.assertValueEqual(target.reach(), RemoteReach.COMMAND_CENTER_NO_POWER, "reach");
            helper.assertTrue(target.drone() == null, "No drone is revealed without power");
        } finally {
            removePlayer(helper, player);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20, batch = RADIUS_BATCH)
    public static void reachIsCommandCenterOutOfAreaBeyondTheRadiusAndOkAtTheEdge(GameTestHelper helper) {
        CommandCenterBlockEntity station = poweredStation(helper, new BlockPos(0, 1, 0), 50);
        String inside = newDroneId();
        String outside = newDroneId();
        UUID uuid = UUID.randomUUID();
        spawnDrone(helper, 8, 1, 0, droneData(inside).withOwnerId(Optional.of(uuid)));
        DroneEntity far = spawnDrone(helper, 8, 1, 8, droneData(outside).withOwnerId(Optional.of(uuid)));
        far.setNoGravity(true);
        ServerPlayer player = spawnPlayer(helper, uuid, 1.5, 1, 3.5, 0);
        try {
            openMenu(player, station);
            helper.assertValueEqual(RemoteControl.findThroughStation(player, inside, station).reach(), RemoteReach.OK, "exactly at the radius");
            RemoteControl.Target target = RemoteControl.findThroughStation(player, outside, station);
            helper.assertValueEqual(target.reach(), RemoteReach.COMMAND_CENTER_OUT_OF_AREA, "outside the radius");
            helper.assertTrue(target.drone() == far, "The drone is still returned (with the distance) for the screen's header");
        } finally {
            removePlayer(helper, player);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void reachIsDeniedForANonOperatorEvenAServerOp(GameTestHelper helper) {
        CommandCenterBlockEntity station = poweredStation(helper, new BlockPos(4, 1, 4), 50);
        String id = newDroneId();
        spawnDrone(helper, 4, 1, 6, foreignDroneData(id));
        ServerPlayer op = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 1.5, 4);
        try {
            openMenu(op, station);
            helper.assertTrue(op.hasPermissions(2), "Fixture: a server operator");
            helper.assertValueEqual(RemoteControl.findThroughStation(op, id, station).reach(), RemoteReach.DENIED, "reach for an op who isn't the drone's operator");
            helper.assertTrue(RemoteControl.findThroughStation(op, id, station).drone() == null, "A non-operator learns nothing about the drone");
        } finally {
            removePlayer(helper, op);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void remoteCommandsWorkThroughTheCommandCenterAndAreRefusedForAServerOpWhoIsNotTheOperator(GameTestHelper helper) {
        CommandCenterBlockEntity station = poweredStation(helper, new BlockPos(4, 1, 4), 50);
        UUID uuid = UUID.randomUUID();
        String mineId = newDroneId();
        String theirsId = newDroneId();
        DroneEntity mine = spawnDrone(helper, 4, 1, 6, droneData(mineId).withOwnerId(Optional.of(uuid)));
        DroneEntity theirs = spawnDrone(helper, 5, 1, 6, foreignDroneData(theirsId));
        ServerPlayer op = spawnPlayer(helper, uuid, 4.5, 1, 1.5, 4);
        try {
            openMenu(op, station);
            RemoteControl.command(op, mineId, RemoteCommand.HOLD);
            helper.assertValueEqual(mine.getState(), DroneState.HOLDING, "Hold through the Command Center");
            RemoteControl.command(op, mineId, RemoteCommand.RESUME);
            helper.assertTrue(mine.getState() != DroneState.HOLDING, "Resume through the Command Center, state=" + mine.getState());
            RemoteControl.command(op, theirsId, RemoteCommand.HOLD);
            helper.assertTrue(theirs.getState() != DroneState.HOLDING, "A server op who isn't the drone's operator is refused through the Command Center");
        } finally {
            removePlayer(helper, op);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void feedAndPilotingStartThroughTheMenuAndEndWhenItCloses(GameTestHelper helper) {
        CommandCenterBlockEntity station = poweredStation(helper, new BlockPos(4, 1, 4), 50);
        String id = newDroneId();
        UUID uuid = UUID.randomUUID();
        DroneEntity drone = spawnDrone(helper, 4, 1, 6, cameraDroneData(id).withOwnerId(Optional.of(uuid)));
        ServerPlayer player = spawnPlayer(helper, uuid, 4.5, 1, 1.5, 0);
        try {
            FpvFeeds.startFromRemote(player, id);
            helper.assertTrue(!FpvFeeds.isViewing(player), "Without the menu there is no feed");
            openMenu(player, station);
            FpvFeeds.startFromRemote(player, id);
            helper.assertTrue(FpvFeeds.viewedDrone(player) == drone, "The feed should start through the Command Center without a remote in hand");
            player.closeContainer();
            helper.assertTrue(!FpvFeeds.isViewing(player), "Closing the menu should end the feed right away");
            openMenu(player, station);
            FpvFeeds.startFromRemote(player, id, true);
            helper.assertTrue(drone.isPiloted() && FpvFeeds.pilotedDrone(player) == drone, "The drone should be piloted through the Command Center");
            player.closeContainer();
            helper.assertTrue(!FpvFeeds.isViewing(player) && !drone.isPiloted(), "Closing the menu should end piloting");
        } finally {
            removePlayer(helper, player);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void feedEndsRightAwayWhenTheCommandCenterLosesPower(GameTestHelper helper) {
        CommandCenterBlockEntity station = placeStation(helper, new BlockPos(4, 1, 4));
        // About 10 ticks of FE; the feed's own recheck (camera.recheckInterval, 20 ticks) is much later than that.
        station.getEnergyStorage().receiveEnergy(perTick() * 10, false);
        String id = newDroneId();
        UUID uuid = UUID.randomUUID();
        spawnDrone(helper, 4, 1, 6, cameraDroneData(id).withOwnerId(Optional.of(uuid)));
        ServerPlayer player = spawnPlayer(helper, uuid, 4.5, 1, 1.5, 0);
        AtomicBoolean started = new AtomicBoolean();
        helper.runAfterDelay(2, () -> {
            helper.assertTrue(station.isPowered(), "Fixture: powered when the feed starts");
            openMenu(player, station);
            FpvFeeds.startFromRemote(player, id);
            helper.assertTrue(FpvFeeds.isViewing(player), "Fixture: the feed should have started");
            started.set(true);
        });
        helper.onEachTick(() -> {
            if (started.get() && !station.isPowered()) {
                boolean viewing = FpvFeeds.isViewing(player);
                removePlayer(helper, player);
                helper.assertTrue(!viewing, "The feed should end on the tick the Command Center loses power, not at the next recheck");
                helper.succeed();
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void pilotingEndsRightAwayWhenTheCommandCenterLosesPower(GameTestHelper helper) {
        CommandCenterBlockEntity station = placeStation(helper, new BlockPos(4, 1, 4));
        station.getEnergyStorage().receiveEnergy(perTick() * 10, false);
        String id = newDroneId();
        UUID uuid = UUID.randomUUID();
        DroneEntity drone = spawnDrone(helper, 4, 1, 6, cameraDroneData(id).withOwnerId(Optional.of(uuid)));
        ServerPlayer player = spawnPlayer(helper, uuid, 4.5, 1, 1.5, 0);
        AtomicBoolean started = new AtomicBoolean();
        helper.runAfterDelay(2, () -> {
            openMenu(player, station);
            FpvFeeds.startFromRemote(player, id, true);
            helper.assertTrue(drone.isPiloted(), "Fixture: the drone should be piloted");
            started.set(true);
        });
        helper.onEachTick(() -> {
            if (started.get() && !station.isPowered()) {
                boolean piloted = drone.isPiloted() || FpvFeeds.isViewing(player);
                removePlayer(helper, player);
                helper.assertTrue(!piloted, "The session should end on the tick the Command Center loses power");
                helper.succeed();
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 70, batch = RADIUS_BATCH)
    public static void feedEndsWhenTheDroneLeavesTheArea(GameTestHelper helper) {
        CommandCenterBlockEntity station = poweredStation(helper, new BlockPos(0, 1, 0), 100);
        String id = newDroneId();
        UUID uuid = UUID.randomUUID();
        DroneEntity drone = spawnDrone(helper, 2, 1, 2, cameraDroneData(id).withOwnerId(Optional.of(uuid)));
        ServerPlayer player = spawnPlayer(helper, uuid, 1.5, 1, 3.5, 0);
        openMenu(player, station);
        FpvFeeds.startFromRemote(player, id);
        helper.assertTrue(FpvFeeds.isViewing(player), "Fixture: the feed should have started inside the area");
        Vec3 away = helper.absoluteVec(new Vec3(2.5, 3, 24.5));
        drone.setNoGravity(true);
        drone.moveTo(away.x, away.y, away.z);
        helper.succeedWhen(() -> {
            helper.assertTrue(!FpvFeeds.isViewing(player), "The feed should end within camera.recheckInterval ticks of the drone leaving the area");
            removePlayer(helper, player);
        });
    }

    @GameTest(template = "empty", timeoutTicks = 70)
    public static void feedEndsWhenThePlayerStopsBeingTheOperatorEvenAServerOp(GameTestHelper helper) {
        CommandCenterBlockEntity station = poweredStation(helper, new BlockPos(4, 1, 4), 100);
        String id = newDroneId();
        UUID uuid = UUID.randomUUID();
        DroneEntity drone = spawnDrone(helper, 4, 1, 6, cameraDroneData(id).withOwnerId(Optional.of(uuid)));
        ServerPlayer player = spawnPlayer(helper, uuid, 4.5, 1, 1.5, 4);
        openMenu(player, station);
        FpvFeeds.startFromRemote(player, id);
        helper.assertTrue(FpvFeeds.isViewing(player), "Fixture: the feed should have started");
        drone.setDroneData(drone.snapshotData().withOwnerId(Optional.of(UUID.randomUUID())));
        helper.succeedWhen(() -> {
            helper.assertTrue(!FpvFeeds.isViewing(player), "The feed should end once the player isn't an operator, with no server-operator bypass");
            removePlayer(helper, player);
        });
    }
}
