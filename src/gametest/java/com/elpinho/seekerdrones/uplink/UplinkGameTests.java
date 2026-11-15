package com.elpinho.seekerdrones.uplink;

import static com.elpinho.seekerdrones.gametest.GameTestUtil.assertAlarm;
import static com.elpinho.seekerdrones.gametest.GameTestUtil.placeTwoBlock;
import static com.elpinho.seekerdrones.gametest.GameTestUtil.removePlayer;
import static com.elpinho.seekerdrones.gametest.GameTestUtil.server;
import static com.elpinho.seekerdrones.gametest.GameTestUtil.setSeekTarget;
import static com.elpinho.seekerdrones.gametest.GameTestUtil.signal;
import static com.elpinho.seekerdrones.gametest.GameTestUtil.spawnDrone;
import static com.elpinho.seekerdrones.gametest.GameTestUtil.spawnPlayer;
import static com.elpinho.seekerdrones.gametest.GameTestUtil.spawnStationaryZombie;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.drone.DroneState;
import com.elpinho.seekerdrones.drone.DroneStats;
import com.elpinho.seekerdrones.drone.UpgradeType;
import com.elpinho.seekerdrones.feed.FpvFeeds;
import com.elpinho.seekerdrones.network.MapMarkers;
import com.elpinho.seekerdrones.network.UplinkContactsPayload;
import com.elpinho.seekerdrones.operator.OperatorGroups;
import com.elpinho.seekerdrones.registry.ModBlocks;
import com.elpinho.seekerdrones.registry.ModEntityTypes;
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
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.entity.ComparatorBlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Drone Satellite Uplink GameTests (DESIGN.md section 7.9): energy, the POWERED state, the per-viewer identified or
 * unidentified split, the area, Charging Station visibility, the tracking target, the intruder alarm and the remote
 * session opened through it. They live in the uplink package to reach the package-private placer and menu. Viewers are
 * never registered on a powered Uplink here: the fake connections of test players can't take its custom payloads, so
 * the contact snapshot is built directly (as {@code RadarStationGameTests} does).
 */
@GameTestHolder("seekerdrones")
@PrefixGameTestTemplate(false)
public class UplinkGameTests {
    private static final String RADIUS_BATCH = "config_uplink_radius_8";
    /** The same config in a batch of its own, for tests that move drones out of the area. */
    private static final String ALONE_BATCH = "config_uplink_radius_8_alone";
    private static int originalRadius;

    @BeforeBatch(batch = RADIUS_BATCH)
    public static void beforeRadius(ServerLevel level) {
        originalRadius = ServerConfig.get(ServerConfig.UPLINK_RADIUS);
        ServerConfig.UPLINK_RADIUS.set(8);
    }

    @AfterBatch(batch = RADIUS_BATCH)
    public static void afterRadius(ServerLevel level) {
        ServerConfig.UPLINK_RADIUS.set(originalRadius);
    }

    @BeforeBatch(batch = ALONE_BATCH)
    public static void beforeAlone(ServerLevel level) {
        originalRadius = ServerConfig.get(ServerConfig.UPLINK_RADIUS);
        ServerConfig.UPLINK_RADIUS.set(8);
    }

    @AfterBatch(batch = ALONE_BATCH)
    public static void afterAlone(ServerLevel level) {
        ServerConfig.UPLINK_RADIUS.set(originalRadius);
    }

    // =====================================================================================================
    // Helpers
    // =====================================================================================================

    private static UplinkBlockEntity placeUplink(GameTestHelper helper, BlockPos rel) {
        return placeTwoBlock(helper, rel, ModBlocks.UPLINK.get());
    }

    private static int perTick() {
        return ServerConfig.get(ServerConfig.UPLINK_ENERGY_PER_TICK);
    }

    /** An Uplink with {@code ticks} ticks of FE that has already had its first tick, so it is powered right away. */
    private static UplinkBlockEntity poweredUplink(GameTestHelper helper, BlockPos rel, int ticks) {
        UplinkBlockEntity uplink = placeUplink(helper, rel);
        uplink.getEnergyStorage().receiveEnergy(perTick() * ticks, false);
        uplink.serverTick(helper.getLevel());
        helper.assertTrue(uplink.isPowered(), "Fixture: the Uplink should be powered");
        return uplink;
    }

    private static UplinkContactsPayload contacts(GameTestHelper helper, UplinkBlockEntity uplink, ServerPlayer viewer) {
        return (UplinkContactsPayload) uplink.contactsPayload(helper.getLevel(), viewer);
    }

    private static Set<String> ids(UplinkContactsPayload payload) {
        return payload.identified().stream().map(UplinkContactsPayload.Identified::droneId).collect(Collectors.toSet());
    }

    private static boolean listedUnidentified(UplinkContactsPayload payload, UplinkBlockEntity uplink, DroneEntity drone) {
        Vec3 center = Vec3.atCenterOf(uplink.getBlockPos());
        return payload.unidentified().stream().anyMatch(u -> Math.abs(u.dx() - (drone.getX() - center.x)) < 1e-3
                && Math.abs(u.dz() - (drone.getZ() - center.z)) < 1e-3 && Math.abs(u.dy() - (drone.getY() - uplink.getBlockPos().getY())) < 1e-3);
    }

    private static String tag() {
        return UUID.randomUUID().toString().substring(0, 6);
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

    private static DroneData foreign(String id) {
        return data(id).withOwnerId(Optional.of(UUID.randomUUID()));
    }

    private static UplinkMenu openMenu(ServerPlayer player, UplinkBlockEntity uplink) {
        UplinkMenu menu = new UplinkMenu(7, uplink);
        player.containerMenu = menu;
        return menu;
    }

    // =====================================================================================================
    // 1. Energy
    // =====================================================================================================

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void uplinkUsesExactlyEnergyPerTickEveryTick(GameTestHelper helper) {
        UplinkBlockEntity uplink = placeUplink(helper, new BlockPos(4, 1, 4));
        IEnergyStorage energy = uplink.getEnergyStorage();
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
    public static void uplinkWithLessThanOneTicksEnergyIsUnpoweredAndUsesNothing(GameTestHelper helper) {
        BlockPos rel = new BlockPos(4, 1, 4);
        UplinkBlockEntity uplink = placeUplink(helper, rel);
        int stored = perTick() - 1;
        uplink.getEnergyStorage().receiveEnergy(stored, false);
        helper.runAfterDelay(8, () -> {
            helper.assertTrue(uplink.getEnergyStorage().getEnergyStored() == stored, "An unpowered Uplink should use no FE, has " + uplink.getEnergyStorage().getEnergyStored() + " of " + stored);
            helper.assertTrue(!uplink.isPowered(), "Uplink should be unpowered");
            helper.assertTrue(!helper.getBlockState(rel).getValue(UplinkBlock.POWERED), "POWERED should be false");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void uplinkAcceptsEnergyOnEverySideOfLowerHalfOnly(GameTestHelper helper) {
        BlockPos rel = new BlockPos(4, 1, 4);
        placeUplink(helper, rel);
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
    public static void uplinkEnergyCapacityFollowsTheConfig(GameTestHelper helper) {
        UplinkBlockEntity uplink = placeUplink(helper, new BlockPos(4, 1, 4));
        int capacity = ServerConfig.get(ServerConfig.UPLINK_ENERGY_CAPACITY);
        helper.assertTrue(uplink.getEnergyStorage().getMaxEnergyStored() == capacity, "Capacity should be uplink.energyCapacity=" + capacity);
        helper.assertTrue(uplink.getEnergyStorage().receiveEnergy(capacity + 5000, false) == capacity, "It should only take up to its capacity");
        helper.succeed();
    }

    // =====================================================================================================
    // 2. POWERED state
    // =====================================================================================================

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void uplinkPoweredStateGoesFalseTrueFalseOnceAndNeverOnTheUpperHalf(GameTestHelper helper) {
        BlockPos rel = new BlockPos(4, 1, 4);
        UplinkBlockEntity uplink = placeUplink(helper, rel);
        int perTick = perTick();
        List<Boolean> seen = new ArrayList<>();
        List<Boolean> upperSeen = new ArrayList<>();
        helper.onEachTick(() -> {
            seen.add(helper.getBlockState(rel).getValue(UplinkBlock.POWERED));
            upperSeen.add(helper.getBlockState(rel.above()).getValue(UplinkBlock.POWERED));
        });
        helper.runAfterDelay(5, () -> uplink.getEnergyStorage().receiveEnergy(perTick * 2, false));
        helper.runAfterDelay(15, () -> {
            List<Boolean> runs = new ArrayList<>();
            for (boolean value : seen) {
                if (runs.isEmpty() || runs.get(runs.size() - 1) != value) {
                    runs.add(value);
                }
            }
            helper.assertTrue(runs.equals(List.of(false, true, false)), "POWERED should go false -> true -> false exactly once each, saw runs " + runs + " over " + seen);
            helper.assertTrue(upperSeen.stream().noneMatch(Boolean::booleanValue), "The upper half's POWERED must never be written: " + upperSeen);
            helper.assertTrue(uplink.getEnergyStorage().getEnergyStored() == 0, "All FE should be used");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void uplinkPoweredStateStaysTrueWhileEnergyLasts(GameTestHelper helper) {
        BlockPos rel = new BlockPos(4, 1, 4);
        UplinkBlockEntity uplink = placeUplink(helper, rel);
        uplink.getEnergyStorage().receiveEnergy(perTick() * 30, false);
        List<Boolean> seen = new ArrayList<>();
        helper.onEachTick(() -> {
            if (helper.getTick() >= 3) {
                seen.add(helper.getBlockState(rel).getValue(UplinkBlock.POWERED));
            }
        });
        helper.runAfterDelay(12, () -> {
            helper.assertTrue(seen.size() >= 5 && seen.stream().allMatch(Boolean::booleanValue), "POWERED should stay true while FE lasts: " + seen);
            helper.succeed();
        });
    }

    // =====================================================================================================
    // 3. Identified and unidentified drones, per viewer
    // =====================================================================================================

    @GameTest(template = "empty", timeoutTicks = 20, batch = RADIUS_BATCH)
    public static void uplinkIdentifiesOnlyTheViewersDronesAndListsTheRestUnidentified(GameTestHelper helper) {
        BlockPos rel = new BlockPos(4, 1, 4);
        UplinkBlockEntity uplink = placeUplink(helper, rel);
        OperatorGroups groups = OperatorGroups.get(server(helper));
        UUID viewerId = UUID.randomUUID();
        UUID otherOwner = UUID.randomUUID();
        UUID viewerGroup = groups.createGroup(otherOwner);
        groups.addOperator(viewerGroup, viewerId);
        UUID foreignGroup = groups.createGroup(otherOwner);
        String tag = tag();

        DroneData ownData = data("own-" + tag).withOwnerId(Optional.of(viewerId)).withUpgradeCount(UpgradeType.PATROL, 1);
        ownData = ownData.withConfig(ownData.config().withLabel("Alpha").withColor(DyeColor.RED).withPatrolRadius(Optional.of(4)));
        DroneEntity own = spawnDrone(helper, 2, 1, 2, ownData);
        own.setPatrolCenter(GlobalPos.of(helper.getLevel().dimension(), helper.absolutePos(new BlockPos(6, 1, 6))));
        DroneEntity grouped = spawnDrone(helper, 3, 1, 2, data("grp-" + tag).withGroupId(Optional.of(viewerGroup)).withOwnerId(Optional.of(otherOwner)));
        DroneEntity others = spawnDrone(helper, 4, 1, 2, data("oth-" + tag).withOwnerId(Optional.of(otherOwner)));
        DroneEntity unowned = spawnDrone(helper, 5, 1, 2, data("unowned-" + tag));
        DroneEntity foreignGrp = spawnDrone(helper, 6, 1, 2, data("foreign-" + tag).withGroupId(Optional.of(foreignGroup)));
        // The viewer is the owner, but the group takes over and it doesn't exist.
        DroneEntity unknown = spawnDrone(helper, 7, 1, 2, data("unknown-" + tag).withGroupId(Optional.of(UUID.randomUUID())).withOwnerId(Optional.of(viewerId)));

        ServerPlayer viewer = spawnPlayer(helper, viewerId, 1, 1, 6, 0);
        try {
            UplinkContactsPayload payload = contacts(helper, uplink, viewer);
            Set<String> ours = ids(payload).stream().filter(id -> id.endsWith(tag)).collect(Collectors.toSet());
            helper.assertTrue(ours.equals(Set.of("own-" + tag, "grp-" + tag)), "Viewer should identify only the owned and the group drone, saw " + ours);
            for (DroneEntity other : List.of(others, unowned, foreignGrp, unknown)) {
                helper.assertTrue(listedUnidentified(payload, uplink, other), "Drone " + other.getDroneId() + " should be listed unidentified");
            }
            helper.assertTrue(!listedUnidentified(payload, uplink, own) && !listedUnidentified(payload, uplink, grouped),
                    "The viewer's own drones must not also be listed unidentified");

            UplinkContactsPayload.Identified contact = payload.identified().stream().filter(c -> c.droneId().equals("own-" + tag)).findFirst().orElseThrow();
            Vec3 center = Vec3.atCenterOf(helper.absolutePos(rel));
            helper.assertTrue(Math.abs(contact.dx() - (own.getX() - center.x)) < 1e-3 && Math.abs(contact.dz() - (own.getZ() - center.z)) < 1e-3
                    && Math.abs(contact.dy() - (own.getY() - helper.absolutePos(rel).getY())) < 1e-3, "Contact should hold the position relative to the Uplink, got " + contact);
            helper.assertTrue(Math.abs(contact.yaw() - own.getYRot()) < 1e-3, "Contact should carry the heading");
            helper.assertTrue(contact.label().equals("Alpha") && contact.color() == DyeColor.RED, "Contact should carry the label and color, got " + contact.label() + " " + contact.color());
            helper.assertTrue(contact.patrol().isPresent(), "A drone with Patrol should carry its patrol ring");
            MapMarkers.Patrol patrol = contact.patrol().get();
            helper.assertTrue(Math.abs(patrol.dx() - 2.0F) < 1e-3 && Math.abs(patrol.dz() - 2.0F) < 1e-3, "Patrol center should be relative to the Uplink (2, 2), got " + patrol);
            helper.assertTrue(patrol.radius() == DroneStats.patrolRadius(own.snapshotData()) && patrol.radius() > 0, "Patrol radius should be the drone's, got " + patrol.radius());
            UplinkContactsPayload.Identified groupContact = payload.identified().stream().filter(c -> c.droneId().equals("grp-" + tag)).findFirst().orElseThrow();
            helper.assertTrue(groupContact.patrol().isEmpty(), "No Patrol upgrade: no ring");
            helper.assertTrue(groupContact.target().isEmpty(), "No target: none sent");
        } finally {
            removePlayer(helper, viewer);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20, batch = RADIUS_BATCH)
    public static void uplinkServerOperatorWhoIsNotTheOperatorSeesEveryDroneUnidentified(GameTestHelper helper) {
        UplinkBlockEntity uplink = placeUplink(helper, new BlockPos(4, 1, 4));
        UUID ownerId = UUID.randomUUID();
        String tag = tag();
        DroneEntity owned = spawnDrone(helper, 3, 1, 3, data("owned-" + tag).withOwnerId(Optional.of(ownerId)));
        DroneEntity unowned = spawnDrone(helper, 5, 1, 3, data("unowned-" + tag));
        ServerPlayer op = spawnPlayer(helper, UUID.randomUUID(), 1, 1, 6, 4);
        ServerPlayer owner = spawnPlayer(helper, ownerId, 2, 1, 6, 0);
        try {
            helper.assertTrue(op.hasPermissions(2), "Fixture: the player should be a server operator");
            UplinkContactsPayload opView = contacts(helper, uplink, op);
            helper.assertTrue(opView.identified().stream().noneMatch(c -> c.droneId().endsWith(tag)), "A server operator must not get any drone identified");
            helper.assertTrue(listedUnidentified(opView, uplink, owned) && listedUnidentified(opView, uplink, unowned), "Both drones should be listed unidentified for the op");
            UplinkContactsPayload ownerView = contacts(helper, uplink, owner);
            helper.assertTrue(ids(ownerView).equals(Set.of("owned-" + tag)), "The owner identifies exactly their drone, saw " + ids(ownerView));
            helper.assertTrue(listedUnidentified(ownerView, uplink, unowned) && !listedUnidentified(ownerView, uplink, owned), "The owner sees the unowned drone unidentified only");
        } finally {
            removePlayer(helper, op);
            removePlayer(helper, owner);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20, batch = RADIUS_BATCH)
    public static void uplinkPayloadForANonOperatorNeverContainsAnotherPlayersDroneIdOrLabel(GameTestHelper helper) {
        UplinkBlockEntity uplink = placeUplink(helper, new BlockPos(4, 1, 4));
        String tag = tag();
        DroneData secret = foreign("SECRETID" + tag);
        secret = secret.withConfig(secret.config().withLabel("SECRETLABEL" + tag));
        spawnDrone(helper, 3, 1, 3, secret);
        ServerPlayer viewer = spawnPlayer(helper, UUID.randomUUID(), 1, 1, 6, 0);
        try {
            UplinkContactsPayload payload = contacts(helper, uplink, viewer);
            helper.assertTrue(payload.unidentified().size() >= 1 && payload.identified().isEmpty(), "The drone should be unidentified for this viewer");
            RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), server(helper).registryAccess());
            UplinkContactsPayload.STREAM_CODEC.encode(buf, payload);
            String bytes = buf.toString(StandardCharsets.UTF_8);
            helper.assertTrue(!bytes.contains("SECRETID") && !bytes.contains("SECRETLABEL"), "The wire form must not leak the ID or the label");
        } finally {
            removePlayer(helper, viewer);
        }
        helper.succeed();
    }

    // =====================================================================================================
    // 4. Area (batch: uplink.radius = 8)
    // =====================================================================================================

    @GameTest(template = "empty", timeoutTicks = 20, batch = RADIUS_BATCH)
    public static void uplinkAreaIsHorizontalAndIncludesTheEdgeAtAnyHeight(GameTestHelper helper) {
        helper.assertTrue(ServerConfig.get(ServerConfig.UPLINK_RADIUS) == 8, "Fixture: radius should be 8 in this batch");
        UplinkBlockEntity uplink = placeUplink(helper, new BlockPos(0, 1, 0));
        UUID viewerId = UUID.randomUUID();
        String tag = tag();
        // Distances from the Uplink's center (0.5, 0.5): 5.1, exactly 8.0, 11.3, and 3.5 but 60 blocks up.
        spawnDrone(helper, 4, 1, 4, data("near-" + tag).withOwnerId(Optional.of(viewerId)));
        spawnDrone(helper, 8, 1, 0, data("edge-" + tag).withOwnerId(Optional.of(viewerId)));
        spawnDrone(helper, 8, 1, 8, data("far-" + tag).withOwnerId(Optional.of(viewerId)));
        DroneEntity high = spawnDrone(helper, 3, 1, 3, data("high-" + tag).withOwnerId(Optional.of(viewerId)));
        high.setNoGravity(true);
        high.moveTo(high.getX(), high.getY() + 60, high.getZ());
        DroneEntity othersEdge = spawnDrone(helper, 0, 1, 8, foreign("o-edge-" + tag));
        DroneEntity othersFar = spawnDrone(helper, 8, 1, 8, foreign("o-far-" + tag));
        othersFar.setNoGravity(true);

        ServerPlayer viewer = spawnPlayer(helper, viewerId, 1, 1, 6, 0);
        try {
            UplinkContactsPayload payload = contacts(helper, uplink, viewer);
            Set<String> seen = ids(payload);
            helper.assertTrue(seen.contains("near-" + tag), "A drone inside the radius should be identified, saw " + seen);
            helper.assertTrue(seen.contains("edge-" + tag), "A drone exactly at the radius should be identified, saw " + seen);
            helper.assertTrue(seen.contains("high-" + tag), "Height doesn't count: a drone 60 blocks up inside the radius should be identified, saw " + seen);
            helper.assertTrue(!seen.contains("far-" + tag), "A drone outside the radius must not be identified, saw " + seen);
            UplinkContactsPayload.Identified highContact = payload.identified().stream().filter(c -> c.droneId().equals("high-" + tag)).findFirst().orElseThrow();
            helper.assertTrue(Math.abs(highContact.dy() - 60.0F) < 1.0F, "The altitude should be relative to the Uplink, got " + highContact.dy());
            helper.assertTrue(listedUnidentified(payload, uplink, othersEdge), "Someone else's drone at the radius should be unidentified");
            helper.assertTrue(!listedUnidentified(payload, uplink, othersFar), "Someone else's drone outside the radius must not be listed");
        } finally {
            removePlayer(helper, viewer);
        }
        helper.succeed();
    }

    // =====================================================================================================
    // 5. Charging Stations (batch: uplink.radius = 8)
    // =====================================================================================================

    @GameTest(template = "empty", timeoutTicks = 20, batch = RADIUS_BATCH)
    public static void uplinkListsOnlyStationsPlacedByTheViewerOrAGroupTheyOperate(GameTestHelper helper) {
        BlockPos rel = new BlockPos(4, 1, 4);
        UplinkBlockEntity uplink = placeUplink(helper, rel);
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
            Set<MapMarkers.Station> stations = Set.copyOf(contacts(helper, uplink, viewer).stations());
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
    public static void uplinkHidesOtherPlayersStationsFromAServerOp(GameTestHelper helper) {
        UplinkBlockEntity uplink = placeUplink(helper, new BlockPos(4, 1, 4));
        ChargingStationRegistry registry = ChargingStationRegistry.get(helper.getLevel());
        UUID placer = UUID.randomUUID();
        BlockPos station = helper.absolutePos(new BlockPos(2, 1, 2));
        ServerPlayer op = spawnPlayer(helper, UUID.randomUUID(), 1, 1, 7, 4);
        try {
            registry.add(station, Optional.of(placer));
            helper.assertTrue(contacts(helper, uplink, op).stations().isEmpty(), "A server operator doesn't see other players' stations (no bypass)");
        } finally {
            registry.remove(station);
            removePlayer(helper, op);
        }
        helper.succeed();
    }

    // =====================================================================================================
    // 8. Tracking target
    // =====================================================================================================

    @GameTest(template = "empty", timeoutTicks = 20, batch = RADIUS_BATCH)
    public static void uplinkIdentifiedDroneCarriesItsTrackingTargetAndUnidentifiedNeverDo(GameTestHelper helper) {
        BlockPos rel = new BlockPos(4, 1, 4);
        UplinkBlockEntity uplink = placeUplink(helper, rel);
        UUID viewerId = UUID.randomUUID();
        String tag = tag();
        DroneEntity tracking = spawnDrone(helper, 2, 1, 2, data("trk-" + tag).withOwnerId(Optional.of(viewerId)));
        DroneEntity tracksPlayer = spawnDrone(helper, 3, 1, 2, data("plr-" + tag).withOwnerId(Optional.of(viewerId)));
        DroneEntity idle = spawnDrone(helper, 5, 1, 2, data("idle-" + tag).withOwnerId(Optional.of(viewerId)));
        DroneEntity others = spawnDrone(helper, 6, 1, 2, foreign("oth-" + tag));
        Zombie zombie = spawnStationaryZombie(helper, 7, 1, 6);
        ServerPlayer targetPlayer = spawnPlayer(helper, UUID.randomUUID(), 2.5, 1, 7.5, 0);
        ServerPlayer viewer = spawnPlayer(helper, viewerId, 1, 1, 7, 0);
        try {
            setSeekTarget(tracking, zombie);
            setSeekTarget(tracksPlayer, targetPlayer);
            setSeekTarget(others, zombie);
            UplinkContactsPayload payload = contacts(helper, uplink, viewer);
            Vec3 center = Vec3.atCenterOf(helper.absolutePos(rel));

            UplinkContactsPayload.Identified zombieContact = payload.identified().stream().filter(c -> c.droneId().equals("trk-" + tag)).findFirst().orElseThrow();
            helper.assertTrue(zombieContact.target().isPresent(), "A drone tracking a zombie should carry the target");
            MapMarkers.Target target = zombieContact.target().get();
            helper.assertTrue(Math.abs(target.dx() - (zombie.getX() - center.x)) < 1e-3 && Math.abs(target.dz() - (zombie.getZ() - center.z)) < 1e-3,
                    "Target position should be relative to the Uplink, got " + target);
            helper.assertTrue(target.type() == EntityType.ZOMBIE, "Target type should be zombie, got " + target.type());
            helper.assertTrue(target.name().getString().equals(zombie.getDisplayName().getString()), "Target name should be the display name, got " + target.name().getString());
            helper.assertTrue(target.playerName().isEmpty(), "A zombie has no player name, got '" + target.playerName() + "'");

            UplinkContactsPayload.Identified playerContact = payload.identified().stream().filter(c -> c.droneId().equals("plr-" + tag)).findFirst().orElseThrow();
            helper.assertTrue(playerContact.target().isPresent(), "A drone tracking a player should carry the target");
            helper.assertTrue(playerContact.target().get().type() == EntityType.PLAYER, "Target type should be player");
            helper.assertTrue(playerContact.target().get().playerName().equals(targetPlayer.getGameProfile().getName()), "Target should carry the player's name, got " + playerContact.target().get().playerName());

            UplinkContactsPayload.Identified idleContact = payload.identified().stream().filter(c -> c.droneId().equals("idle-" + tag)).findFirst().orElseThrow();
            helper.assertTrue(idleContact.target().isEmpty(), "A drone with no target carries none");

            // Someone else's drone tracking the same zombie is unidentified: the record has no target at all, and no ID.
            helper.assertTrue(listedUnidentified(payload, uplink, others), "The other player's tracking drone should be unidentified");
            helper.assertTrue(payload.identified().stream().noneMatch(c -> c.droneId().equals("oth-" + tag)), "...and never identified");
            helper.assertTrue(idle.isAlive(), "Fixture drone alive");
        } finally {
            removePlayer(helper, viewer);
            removePlayer(helper, targetPlayer);
        }
        helper.succeed();
    }

    // =====================================================================================================
    // 7. Intruder alarm (batch: uplink.radius = 8). Drones stay within 4 blocks of the Uplink's center.
    // =====================================================================================================

    @GameTest(template = "empty", timeoutTicks = 40, batch = RADIUS_BATCH)
    public static void uplinkAlarmCountsDronesInTheAreaThePlacerIsNotAnOperatorOfWithNobodyWatching(GameTestHelper helper) {
        BlockPos rel = new BlockPos(4, 1, 4);
        UplinkBlockEntity uplink = poweredUplink(helper, rel, 100);
        UUID placer = UUID.randomUUID();
        uplink.setPlacer(placer);
        helper.assertTrue(uplink.viewers().isEmpty(), "Fixture: nobody watches");
        OperatorGroups groups = OperatorGroups.get(server(helper));
        UUID groupOwner = UUID.randomUUID();
        UUID placerGroup = groups.createGroup(groupOwner);
        groups.addOperator(placerGroup, placer);
        // Not counted: the placer's own drone, and a drone of a group the placer operates.
        spawnDrone(helper, 2, 1, 2, data(newId()).withOwnerId(Optional.of(placer)));
        spawnDrone(helper, 3, 1, 2, data(newId()).withGroupId(Optional.of(placerGroup)));
        // Counted: two other players' drones and an unowned one (the placer isn't an operator of it).
        spawnDrone(helper, 5, 1, 2, foreign(newId()));
        spawnDrone(helper, 6, 1, 2, foreign(newId()));
        spawnDrone(helper, 7, 1, 2, data(newId()));
        helper.succeedWhen(() -> assertAlarm(helper, uplink, rel, 3));
    }

    @GameTest(template = "empty", timeoutTicks = 40, batch = RADIUS_BATCH)
    public static void uplinkAlarmWithNoPlacerCountsOwnedDronesAndNotUnownedOnes(GameTestHelper helper) {
        BlockPos rel = new BlockPos(4, 1, 4);
        UplinkBlockEntity uplink = poweredUplink(helper, rel, 100);
        helper.assertTrue(uplink.getPlacer().isEmpty(), "Fixture: no placer recorded");
        spawnDrone(helper, 2, 1, 2, foreign(newId()));
        spawnDrone(helper, 3, 1, 2, foreign(newId()));
        spawnDrone(helper, 5, 1, 2, data(newId()).withGroupId(Optional.of(UUID.randomUUID())));
        spawnDrone(helper, 6, 1, 2, data(newId()));
        spawnDrone(helper, 7, 1, 2, data(newId()));
        helper.succeedWhen(() -> assertAlarm(helper, uplink, rel, 3));
    }

    @GameTest(template = "empty", timeoutTicks = 40, batch = RADIUS_BATCH)
    public static void uplinkAlarmIsCappedAtFifteen(GameTestHelper helper) {
        BlockPos rel = new BlockPos(4, 1, 4);
        UplinkBlockEntity uplink = poweredUplink(helper, rel, 100);
        uplink.setPlacer(UUID.randomUUID());
        for (int i = 0; i < 18; i++) {
            spawnDrone(helper, i % 9, 1, 1 + (i / 9) * 6, foreign(newId()));
        }
        helper.succeedWhen(() -> assertAlarm(helper, uplink, rel, 15));
    }

    /**
     * Alone in its batch (with no neighbors in x within 8 blocks): it moves drones out of the area, north and south of
     * its own structure, where no other structure in the row is.
     */
    @GameTest(template = "empty", timeoutTicks = 60, batch = ALONE_BATCH)
    public static void uplinkAlarmIgnoresDronesOutsideTheAreaAndDropsWhenOneLeaves(GameTestHelper helper) {
        BlockPos rel = new BlockPos(4, 1, 4);
        UplinkBlockEntity uplink = poweredUplink(helper, rel, 100);
        uplink.setPlacer(UUID.randomUUID());
        spawnDrone(helper, 2, 1, 2, foreign(newId()));
        DroneEntity leaving = spawnDrone(helper, 3, 1, 2, foreign(newId()));
        DroneEntity far = spawnDrone(helper, 5, 1, 2, foreign(newId()));
        far.setNoGravity(true);
        far.moveTo(far.getX(), far.getY(), far.getZ() - 9);
        leaving.setNoGravity(true);
        AtomicBoolean moved = new AtomicBoolean();
        helper.onEachTick(() -> {
            helper.assertTrue(uplink.getAlarm() <= 2, "A drone outside the area must not count, alarm=" + uplink.getAlarm());
            if (!moved.get()) {
                // Two drones inside the area count, the one outside doesn't.
                if (uplink.getAlarm() == 2) {
                    moved.set(true);
                    leaving.moveTo(leaving.getX(), leaving.getY(), leaving.getZ() + 12);
                }
            } else if (uplink.getAlarm() == 1) {
                helper.succeed();
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 60, batch = RADIUS_BATCH)
    public static void uplinkAlarmDropsToZeroWhenItLosesPower(GameTestHelper helper) {
        BlockPos rel = new BlockPos(4, 1, 4);
        UplinkBlockEntity uplink = placeUplink(helper, rel);
        uplink.setPlacer(UUID.randomUUID());
        // Enough FE for about 25 ticks: a few scans, then it runs dry.
        uplink.getEnergyStorage().receiveEnergy(perTick() * 25, false);
        spawnDrone(helper, 2, 1, 2, foreign(newId()));
        spawnDrone(helper, 3, 1, 2, foreign(newId()));
        AtomicBoolean sawTwo = new AtomicBoolean();
        helper.runAfterDelay(55, () -> helper.fail("Never saw the alarm drop: sawTwo=" + sawTwo + ", powered=" + uplink.isPowered() + ", alarm=" + uplink.getAlarm()
                + ", FE=" + uplink.getEnergyStorage().getEnergyStored()));
        helper.onEachTick(() -> {
            if (uplink.isPowered() && uplink.getAlarm() == 2) {
                sawTwo.set(true);
                helper.assertTrue(signal(helper, rel) == 2 && signal(helper, rel.above()) == 2, "Both halves should show the alarm while powered");
            }
            if (!uplink.isPowered() && sawTwo.get()) {
                helper.assertTrue(uplink.getAlarm() == 0, "The alarm should drop to 0 when unpowered, is " + uplink.getAlarm());
                helper.assertTrue(signal(helper, rel) == 0 && signal(helper, rel.above()) == 0, "Both halves' comparator output should be 0 when unpowered");
                helper.succeed();
            }
        });
    }

    /**
     * A comparator beside one half reads the alarm. Once it shows the count, its stored output is damaged: any neighbor
     * update would make it fix itself on its next tick, so it stays damaged only while the Uplink sends none.
     */
    private static void comparatorsAreUpdatedOnlyWhenTheCountChanges(GameTestHelper helper, boolean lowerHalf) {
        BlockPos rel = new BlockPos(4, 1, 4);
        UplinkBlockEntity uplink = poweredUplink(helper, rel, 140);
        uplink.setPlacer(UUID.randomUUID());
        // They need a solid floor.
        BlockPos comparator = lowerHalf ? new BlockPos(5, 1, 4) : new BlockPos(3, 2, 4);
        helper.setBlock(lowerHalf ? new BlockPos(5, 0, 4) : new BlockPos(3, 1, 4), Blocks.STONE);
        helper.setBlock(comparator, Blocks.COMPARATOR.defaultBlockState().setValue(ComparatorBlock.FACING, lowerHalf ? Direction.WEST : Direction.EAST));
        ComparatorBlockEntity out = (ComparatorBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(comparator));
        helper.assertTrue(out != null, "Fixture: the comparator should have a block entity");

        List<DroneEntity> drones = new ArrayList<>();
        drones.add(spawnDrone(helper, 2, 1, 2, foreign(newId())));
        drones.add(spawnDrone(helper, 3, 1, 2, foreign(newId())));
        AtomicInteger phase = new AtomicInteger();
        AtomicInteger waited = new AtomicInteger();
        AtomicInteger maxAlarm = new AtomicInteger();
        AtomicInteger maxOut = new AtomicInteger();
        helper.onEachTick(() -> {
            maxAlarm.accumulateAndGet(uplink.getAlarm(), Math::max);
            maxOut.accumulateAndGet(out.getOutputSignal(), Math::max);
        });
        helper.runAfterDelay(145, () -> helper.fail("Stuck in phase " + phase.get() + " (max alarm " + maxAlarm + ", max comparator " + maxOut + "): alarm=" + uplink.getAlarm() + ", comparator=" + out.getOutputSignal()
                + ", powered=" + uplink.isPowered() + ", FE=" + uplink.getEnergyStorage().getEnergyStored()));
        helper.onEachTick(() -> {
            switch (phase.get()) {
                case 0 -> {
                    // The count goes 0 -> 2: the comparator follows.
                    if (out.getOutputSignal() == 2) {
                        out.setOutputSignal(9);
                        waited.set(0);
                        phase.set(1);
                    }
                }
                case 1 -> {
                    // The count stays 2 for several scans (the scan interval defaults to 10 ticks): no extra updates.
                    helper.assertTrue(uplink.getAlarm() == 2, "Fixture: the count should stay 2, is " + uplink.getAlarm());
                    helper.assertTrue(out.getOutputSignal() == 9, "An unchanged count must not update the neighbors again, but the comparator beside the "
                            + (lowerHalf ? "lower" : "upper") + " half was refreshed to " + out.getOutputSignal());
                    if (waited.incrementAndGet() >= 3 * ServerConfig.get(ServerConfig.UPLINK_SCAN_INTERVAL)) {
                        drones.add(spawnDrone(helper, 5, 1, 2, foreign(newId())));
                        phase.set(2);
                    }
                }
                case 2 -> {
                    // 2 -> 3: the neighbors are updated and the comparator fixes itself.
                    if (uplink.getAlarm() == 3 && out.getOutputSignal() == 3) {
                        helper.succeed();
                    }
                }
                default -> {
                }
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 150, batch = RADIUS_BATCH)
    public static void uplinkAlarmUpdatesAComparatorBesideTheUpperHalfOnlyWhenTheCountChanges(GameTestHelper helper) {
        comparatorsAreUpdatedOnlyWhenTheCountChanges(helper, false);
    }

    @GameTest(template = "empty", timeoutTicks = 150, batch = RADIUS_BATCH)
    public static void uplinkAlarmUpdatesAComparatorBesideTheLowerHalfOnlyWhenTheCountChanges(GameTestHelper helper) {
        comparatorsAreUpdatedOnlyWhenTheCountChanges(helper, true);
    }

    // =====================================================================================================
    // Placer saving
    // =====================================================================================================

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void uplinkSavesItsPlacerAndLoadsWithoutOne(GameTestHelper helper) {
        BlockPos rel = new BlockPos(4, 1, 4);
        UplinkBlockEntity uplink = placeUplink(helper, rel);
        UUID placer = UUID.randomUUID();
        uplink.setPlacer(placer);
        CompoundTag tag = uplink.saveWithFullMetadata(helper.getLevel().registryAccess());
        UplinkBlockEntity loaded = new UplinkBlockEntity(uplink.getBlockPos(), uplink.getBlockState());
        loaded.loadWithComponents(tag, helper.getLevel().registryAccess());
        helper.assertTrue(loaded.getPlacer().equals(Optional.of(placer)), "The placer should survive a save and load, got " + loaded.getPlacer());
        tag.remove("Placer");
        UplinkBlockEntity without = new UplinkBlockEntity(uplink.getBlockPos(), uplink.getBlockState());
        without.loadWithComponents(tag, helper.getLevel().registryAccess());
        helper.assertTrue(without.getPlacer().isEmpty(), "A save without a placer should load with none, got " + without.getPlacer());
        helper.succeed();
    }

    // =====================================================================================================
    // 9. Remote session through the Uplink
    // =====================================================================================================

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void uplinkRemoteAccessNeedsTheMenuOpenAndStillValid(GameTestHelper helper) {
        UplinkBlockEntity uplink = poweredUplink(helper, new BlockPos(4, 1, 4), 50);
        String id = newId();
        UUID uuid = UUID.randomUUID();
        spawnDrone(helper, 4, 1, 6, data(id).withOwnerId(Optional.of(uuid)));
        ServerPlayer player = spawnPlayer(helper, uuid, 4.5, 1, 1.5, 0);
        try {
            helper.assertTrue(RemoteControl.access(player, id).isEmpty(), "Without the menu open and no remote in hand there is no access");
            openMenu(player, uplink);
            Optional<RemoteControl.Access> access = RemoteControl.access(player, id);
            helper.assertTrue(access.isPresent() && access.get().viaStation() && access.get().station() == uplink, "With the menu open, access is through the Uplink");
            helper.assertTrue(access.get().link().droneId().equals(id), "The link should be for the drone");
            helper.assertTrue(RemoteControl.access(player, "").isEmpty(), "An empty drone ID gets no access");
            // Walks away: the menu is no longer valid (reach check).
            player.moveTo(helper.absoluteVec(new Vec3(4.5, 1, 60)));
            helper.assertTrue(player.containerMenu instanceof UplinkMenu && !player.containerMenu.stillValid(player), "Fixture: the menu is out of reach");
            helper.assertTrue(RemoteControl.access(player, id).isEmpty(), "An Uplink menu that is not still valid gives no access");
        } finally {
            removePlayer(helper, player);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void uplinkReachIsOkForAnOperatorsDroneInTheArea(GameTestHelper helper) {
        UplinkBlockEntity uplink = poweredUplink(helper, new BlockPos(4, 1, 4), 50);
        String id = newId();
        UUID uuid = UUID.randomUUID();
        DroneEntity drone = spawnDrone(helper, 4, 1, 8, data(id).withOwnerId(Optional.of(uuid)));
        ServerPlayer player = spawnPlayer(helper, uuid, 4.5, 1, 1.5, 0);
        try {
            openMenu(player, uplink);
            RemoteControl.Target target = RemoteControl.findThroughStation(player, id, uplink);
            helper.assertValueEqual(target.reach(), RemoteReach.OK, "reach");
            helper.assertTrue(target.drone() == drone, "The drone should be found");
            helper.assertTrue(Math.abs(target.distance() - 4) <= 1, "The distance is measured from the Uplink horizontally (about 4), got " + target.distance());
            helper.assertValueEqual(RemoteControl.find(player, id, RemoteControl.access(player, id).orElseThrow()).reach(), RemoteReach.OK, "reach via access");
        } finally {
            removePlayer(helper, player);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void uplinkReachIsNoPowerWhenTheUplinkIsUnpowered(GameTestHelper helper) {
        UplinkBlockEntity uplink = placeUplink(helper, new BlockPos(4, 1, 4));
        String id = newId();
        UUID uuid = UUID.randomUUID();
        spawnDrone(helper, 4, 1, 6, data(id).withOwnerId(Optional.of(uuid)));
        ServerPlayer player = spawnPlayer(helper, uuid, 4.5, 1, 1.5, 0);
        try {
            openMenu(player, uplink);
            helper.assertTrue(!uplink.isPowered(), "Fixture: unpowered");
            helper.assertValueEqual(RemoteControl.findThroughStation(player, id, uplink).reach(), RemoteReach.NO_POWER, "reach");
            helper.assertTrue(RemoteControl.findThroughStation(player, id, uplink).drone() == null, "No drone is revealed without power");
        } finally {
            removePlayer(helper, player);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void uplinkReachIsNotDeployedForAnUnknownDroneAndOtherDimensionForOneInTheNether(GameTestHelper helper) {
        UplinkBlockEntity uplink = poweredUplink(helper, new BlockPos(4, 1, 4), 50);
        ServerLevel nether = server(helper).getLevel(Level.NETHER);
        helper.assertTrue(nether != null, "Sanity: the Nether should be loaded");
        String id = newId();
        UUID uuid = UUID.randomUUID();
        DroneEntity drone = ModEntityTypes.DRONE.get().create(nether);
        nether.getChunk(0, 0);
        drone.moveTo(8.5, 100, 8.5);
        drone.setDroneData(data(id).withOwnerId(Optional.of(uuid)));
        nether.addFreshEntity(drone);
        ServerPlayer player = spawnPlayer(helper, uuid, 4.5, 1, 1.5, 0);
        try {
            openMenu(player, uplink);
            helper.assertValueEqual(RemoteControl.findThroughStation(player, newId(), uplink).reach(), RemoteReach.NOT_DEPLOYED, "unknown drone");
            helper.assertValueEqual(RemoteControl.findThroughStation(player, id, uplink).reach(), RemoteReach.OTHER_DIMENSION, "drone in the Nether");
            helper.assertValueEqual(RemoteControl.findThroughStation(player, "", uplink).reach(), RemoteReach.NOT_DEPLOYED, "empty ID");
        } finally {
            drone.discard();
            removePlayer(helper, player);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20, batch = RADIUS_BATCH)
    public static void uplinkReachIsOutOfAreaBeyondTheRadiusAndOkAtTheEdge(GameTestHelper helper) {
        UplinkBlockEntity uplink = poweredUplink(helper, new BlockPos(0, 1, 0), 50);
        String inside = newId();
        String outside = newId();
        UUID uuid = UUID.randomUUID();
        spawnDrone(helper, 8, 1, 0, data(inside).withOwnerId(Optional.of(uuid)));
        DroneEntity far = spawnDrone(helper, 8, 1, 8, data(outside).withOwnerId(Optional.of(uuid)));
        far.setNoGravity(true);
        ServerPlayer player = spawnPlayer(helper, uuid, 1.5, 1, 3.5, 0);
        try {
            openMenu(player, uplink);
            helper.assertValueEqual(RemoteControl.findThroughStation(player, inside, uplink).reach(), RemoteReach.OK, "exactly at the radius");
            RemoteControl.Target target = RemoteControl.findThroughStation(player, outside, uplink);
            helper.assertValueEqual(target.reach(), RemoteReach.OUT_OF_AREA, "outside the radius");
            helper.assertTrue(target.drone() == far, "The drone is still returned (with the distance) for the screen's header");
        } finally {
            removePlayer(helper, player);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void uplinkReachIsDeniedForANonOperatorEvenAServerOp(GameTestHelper helper) {
        UplinkBlockEntity uplink = poweredUplink(helper, new BlockPos(4, 1, 4), 50);
        String id = newId();
        spawnDrone(helper, 4, 1, 6, foreign(id));
        ServerPlayer op = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 1.5, 4);
        try {
            openMenu(op, uplink);
            helper.assertTrue(op.hasPermissions(2), "Fixture: a server operator");
            helper.assertValueEqual(RemoteControl.findThroughStation(op, id, uplink).reach(), RemoteReach.DENIED, "reach for an op who isn't the drone's operator");
            helper.assertTrue(RemoteControl.findThroughStation(op, id, uplink).drone() == null, "A non-operator learns nothing about the drone");
        } finally {
            removePlayer(helper, op);
        }
        helper.succeed();
    }

    // --- Commands ---

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void uplinkCommandWorksWithoutHoldingARemote(GameTestHelper helper) {
        UplinkBlockEntity uplink = poweredUplink(helper, new BlockPos(4, 1, 4), 50);
        String id = newId();
        UUID uuid = UUID.randomUUID();
        DroneEntity drone = spawnDrone(helper, 4, 1, 6, data(id).withOwnerId(Optional.of(uuid)));
        ServerPlayer player = spawnPlayer(helper, uuid, 4.5, 1, 1.5, 0);
        try {
            openMenu(player, uplink);
            RemoteControl.command(player, id, RemoteCommand.HOLD);
            helper.assertValueEqual(drone.getState(), DroneState.HOLDING, "state after Hold through the Uplink");
            RemoteControl.command(player, id, RemoteCommand.RESUME);
            helper.assertTrue(drone.getState() != DroneState.HOLDING, "Resume through the Uplink should end the hold, state=" + drone.getState());
        } finally {
            removePlayer(helper, player);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void uplinkCommandIsRefusedWithoutTheMenuOpenOrOnceItIsOutOfReach(GameTestHelper helper) {
        UplinkBlockEntity uplink = poweredUplink(helper, new BlockPos(4, 1, 4), 50);
        String id = newId();
        UUID uuid = UUID.randomUUID();
        DroneEntity drone = spawnDrone(helper, 4, 1, 6, data(id).withOwnerId(Optional.of(uuid)));
        ServerPlayer player = spawnPlayer(helper, uuid, 4.5, 1, 1.5, 0);
        try {
            RemoteControl.command(player, id, RemoteCommand.HOLD);
            helper.assertTrue(drone.getState() != DroneState.HOLDING, "No menu, no remote: refused");
            openMenu(player, uplink);
            player.moveTo(helper.absoluteVec(new Vec3(4.5, 1, 60)));
            RemoteControl.command(player, id, RemoteCommand.HOLD);
            helper.assertTrue(drone.getState() != DroneState.HOLDING, "A menu that isn't still valid: refused");
        } finally {
            removePlayer(helper, player);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void uplinkCommandIsRefusedForANonOperatorEvenAServerOp(GameTestHelper helper) {
        UplinkBlockEntity uplink = poweredUplink(helper, new BlockPos(4, 1, 4), 50);
        String id = newId();
        DroneEntity drone = spawnDrone(helper, 4, 1, 6, foreign(id));
        ServerPlayer op = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 1.5, 4);
        try {
            openMenu(op, uplink);
            RemoteControl.command(op, id, RemoteCommand.HOLD);
            helper.assertTrue(drone.getState() != DroneState.HOLDING, "A server operator who isn't the drone's operator must be refused through the Uplink");
        } finally {
            removePlayer(helper, op);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void uplinkCommandIsRefusedWhenTheUplinkIsUnpowered(GameTestHelper helper) {
        UplinkBlockEntity uplink = placeUplink(helper, new BlockPos(4, 1, 4));
        String id = newId();
        UUID uuid = UUID.randomUUID();
        DroneEntity drone = spawnDrone(helper, 4, 1, 6, data(id).withOwnerId(Optional.of(uuid)));
        ServerPlayer player = spawnPlayer(helper, uuid, 4.5, 1, 1.5, 0);
        try {
            openMenu(player, uplink);
            RemoteControl.command(player, id, RemoteCommand.HOLD);
            helper.assertTrue(drone.getState() != DroneState.HOLDING, "No power: refused");
        } finally {
            removePlayer(helper, player);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20, batch = RADIUS_BATCH)
    public static void uplinkCommandIsRefusedOutsideTheArea(GameTestHelper helper) {
        UplinkBlockEntity uplink = poweredUplink(helper, new BlockPos(0, 1, 0), 50);
        String id = newId();
        UUID uuid = UUID.randomUUID();
        DroneEntity drone = spawnDrone(helper, 8, 1, 8, data(id).withOwnerId(Optional.of(uuid)));
        drone.setNoGravity(true);
        ServerPlayer player = spawnPlayer(helper, uuid, 1.5, 1, 3.5, 0);
        try {
            openMenu(player, uplink);
            RemoteControl.command(player, id, RemoteCommand.HOLD);
            helper.assertTrue(drone.getState() != DroneState.HOLDING, "Outside the area: refused");
        } finally {
            removePlayer(helper, player);
        }
        helper.succeed();
    }

    // --- FPV feeds and piloting ---

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void uplinkFeedStartsThroughTheMenuWithoutARemoteAndEndsWhenTheMenuCloses(GameTestHelper helper) {
        UplinkBlockEntity uplink = poweredUplink(helper, new BlockPos(4, 1, 4), 50);
        String id = newId();
        UUID uuid = UUID.randomUUID();
        DroneEntity drone = spawnDrone(helper, 4, 1, 6, cameraData(id).withOwnerId(Optional.of(uuid)));
        ServerPlayer player = spawnPlayer(helper, uuid, 4.5, 1, 1.5, 0);
        try {
            FpvFeeds.startFromRemote(player, id);
            helper.assertTrue(!FpvFeeds.isViewing(player), "Without the menu there is no feed");
            openMenu(player, uplink);
            FpvFeeds.startFromRemote(player, id);
            helper.assertTrue(FpvFeeds.viewedDrone(player) == drone, "The feed should start through the Uplink without a remote in hand");
            player.closeContainer();
            helper.assertTrue(!FpvFeeds.isViewing(player), "Closing the Uplink menu should end the feed right away");
        } finally {
            removePlayer(helper, player);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void uplinkPilotingEndsWhenTheMenuCloses(GameTestHelper helper) {
        UplinkBlockEntity uplink = poweredUplink(helper, new BlockPos(4, 1, 4), 50);
        String id = newId();
        UUID uuid = UUID.randomUUID();
        DroneEntity drone = spawnDrone(helper, 4, 1, 6, cameraData(id).withOwnerId(Optional.of(uuid)));
        ServerPlayer player = spawnPlayer(helper, uuid, 4.5, 1, 1.5, 0);
        try {
            openMenu(player, uplink);
            FpvFeeds.startFromRemote(player, id, true);
            helper.assertTrue(drone.isPiloted() && FpvFeeds.pilotedDrone(player) == drone, "The drone should be piloted through the Uplink");
            player.closeContainer();
            helper.assertTrue(!FpvFeeds.isViewing(player) && FpvFeeds.pilotedDrone(player) == null, "Closing the menu should end the session");
            helper.assertTrue(!drone.isPiloted(), "The drone should be released from piloting");
        } finally {
            removePlayer(helper, player);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void uplinkFeedEndsRightAwayWhenTheUplinkLosesPower(GameTestHelper helper) {
        UplinkBlockEntity uplink = placeUplink(helper, new BlockPos(4, 1, 4));
        // About 10 ticks of FE; the feed's own recheck (camera.recheckInterval, 20 ticks) is much later than that.
        uplink.getEnergyStorage().receiveEnergy(perTick() * 10, false);
        String id = newId();
        UUID uuid = UUID.randomUUID();
        spawnDrone(helper, 4, 1, 6, cameraData(id).withOwnerId(Optional.of(uuid)));
        ServerPlayer player = spawnPlayer(helper, uuid, 4.5, 1, 1.5, 0);
        AtomicBoolean started = new AtomicBoolean();
        helper.runAfterDelay(2, () -> {
            helper.assertTrue(uplink.isPowered(), "Fixture: powered when the feed starts");
            openMenu(player, uplink);
            FpvFeeds.startFromRemote(player, id);
            helper.assertTrue(FpvFeeds.isViewing(player), "Fixture: the feed should have started");
            started.set(true);
        });
        helper.onEachTick(() -> {
            if (started.get() && !uplink.isPowered()) {
                boolean viewing = FpvFeeds.isViewing(player);
                removePlayer(helper, player);
                helper.assertTrue(!viewing, "The feed should end on the tick the Uplink loses power, not at the next recheck");
                helper.succeed();
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 70)
    public static void uplinkFeedSurvivesRechecksWithoutARemoteInHand(GameTestHelper helper) {
        UplinkBlockEntity uplink = poweredUplink(helper, new BlockPos(4, 1, 4), 100);
        String id = newId();
        UUID uuid = UUID.randomUUID();
        spawnDrone(helper, 4, 1, 6, cameraData(id).withOwnerId(Optional.of(uuid)));
        ServerPlayer player = spawnPlayer(helper, uuid, 4.5, 1, 1.5, 0);
        openMenu(player, uplink);
        FpvFeeds.startFromRemote(player, id);
        helper.assertTrue(FpvFeeds.isViewing(player), "Fixture: the feed should have started");
        int ticks = 2 * ServerConfig.get(ServerConfig.CAMERA_RECHECK_INTERVAL) + 3;
        AtomicInteger waited = new AtomicInteger();
        helper.onEachTick(() -> {
            helper.assertTrue(FpvFeeds.isViewing(player), "The feed should keep going (the Uplink replaces the remote) after " + waited.get() + " ticks");
            if (waited.incrementAndGet() >= ticks) {
                removePlayer(helper, player);
                helper.succeed();
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 70, batch = ALONE_BATCH)
    public static void uplinkFeedEndsWhenTheDroneLeavesTheArea(GameTestHelper helper) {
        UplinkBlockEntity uplink = poweredUplink(helper, new BlockPos(4, 1, 4), 100);
        String id = newId();
        UUID uuid = UUID.randomUUID();
        DroneEntity drone = spawnDrone(helper, 4, 1, 6, cameraData(id).withOwnerId(Optional.of(uuid)));
        ServerPlayer player = spawnPlayer(helper, uuid, 4.5, 1, 1.5, 0);
        openMenu(player, uplink);
        FpvFeeds.startFromRemote(player, id);
        helper.assertTrue(FpvFeeds.isViewing(player), "Fixture: the feed should have started inside the area");
        Vec3 away = helper.absoluteVec(new Vec3(4.5, 3, 24.5));
        drone.setNoGravity(true);
        drone.moveTo(away.x, away.y, away.z);
        helper.succeedWhen(() -> {
            helper.assertTrue(!FpvFeeds.isViewing(player), "The feed should end within camera.recheckInterval ticks of the drone leaving the area");
            removePlayer(helper, player);
        });
    }

    @GameTest(template = "empty", timeoutTicks = 70)
    public static void uplinkFeedEndsWhenThePlayerStopsBeingTheOperatorEvenAServerOp(GameTestHelper helper) {
        UplinkBlockEntity uplink = poweredUplink(helper, new BlockPos(4, 1, 4), 100);
        String id = newId();
        UUID uuid = UUID.randomUUID();
        DroneEntity drone = spawnDrone(helper, 4, 1, 6, cameraData(id).withOwnerId(Optional.of(uuid)));
        ServerPlayer player = spawnPlayer(helper, uuid, 4.5, 1, 1.5, 4);
        openMenu(player, uplink);
        FpvFeeds.startFromRemote(player, id);
        helper.assertTrue(FpvFeeds.isViewing(player), "Fixture: the feed should have started");
        drone.setDroneData(drone.snapshotData().withOwnerId(Optional.of(UUID.randomUUID())));
        helper.succeedWhen(() -> {
            helper.assertTrue(!FpvFeeds.isViewing(player), "The feed should end once the player isn't an operator, with no server-operator bypass");
            removePlayer(helper, player);
        });
    }

    @GameTest(template = "empty", timeoutTicks = 70)
    public static void uplinkFeedEndsWhenTheMenuIsReplacedWithoutTheUplinkClosing(GameTestHelper helper) {
        UplinkBlockEntity uplink = poweredUplink(helper, new BlockPos(4, 1, 4), 100);
        String id = newId();
        UUID uuid = UUID.randomUUID();
        spawnDrone(helper, 4, 1, 6, cameraData(id).withOwnerId(Optional.of(uuid)));
        ServerPlayer player = spawnPlayer(helper, uuid, 4.5, 1, 1.5, 0);
        openMenu(player, uplink);
        FpvFeeds.startFromRemote(player, id);
        helper.assertTrue(FpvFeeds.isViewing(player), "Fixture: the feed should have started");
        // The menu vanishes without removed() (e.g. a plain container swap): the recheck notices it's no longer open.
        player.containerMenu = player.inventoryMenu;
        helper.succeedWhen(() -> {
            helper.assertTrue(!FpvFeeds.isViewing(player), "The feed should end within camera.recheckInterval ticks once the Uplink menu isn't open");
            removePlayer(helper, player);
        });
    }
}
