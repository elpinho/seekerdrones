package com.elpinho.seekerdrones.gametest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;

import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.drone.DroneConfig;
import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.drone.DroneIndex;
import com.elpinho.seekerdrones.drone.DroneItem;
import com.elpinho.seekerdrones.drone.DroneState;
import com.elpinho.seekerdrones.drone.TargetClaims;
import com.elpinho.seekerdrones.drone.TargetEntry;
import com.elpinho.seekerdrones.drone.UpgradeType;
import com.elpinho.seekerdrones.network.EditRemoteSettingsPayload;
import com.elpinho.seekerdrones.operator.OperatorGroups;
import com.elpinho.seekerdrones.registry.ModDataComponents;
import com.elpinho.seekerdrones.registry.ModEntityTypes;
import com.elpinho.seekerdrones.registry.ModItems;
import com.elpinho.seekerdrones.remote.RemoteCommand;
import com.elpinho.seekerdrones.remote.RemoteControl;
import com.elpinho.seekerdrones.remote.RemoteLink;
import com.elpinho.seekerdrones.remote.RemoteReach;
import com.elpinho.seekerdrones.station.ChargingStationBlockEntity;
import com.mojang.authlib.GameProfile;

import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * GameTests for the Drone Remote (DESIGN.md section 2.10): linking, reach, commands (Recall, Hold, Resume, Return to
 * charge, Patrol center here), settings, persistence and the interaction with the drone. They call the server-side
 * logic of {@link RemoteControl} directly instead of simulating packets.
 * <p>
 * Tests that change {@link ServerConfig} values run in their own batch and restore the value in an {@code @AfterBatch}.
 */
@GameTestHolder("seekerdrones")
@PrefixGameTestTemplate(false)
public class RemoteGameTests {

    private static final String RANGE_BATCH = "config_remote_range_4";
    private static final String HOVER_BATCH = "config_remote_hover_30";
    private static final String CONE_BATCH = "config_remote_cone_30";
    private static final String LINK_RANGE_BATCH = "config_remote_link_range_4";
    private static int originalLinkRange;

    @BeforeBatch(batch = LINK_RANGE_BATCH)
    public static void beforeLinkRangeBatch(ServerLevel level) {
        originalLinkRange = ServerConfig.get(ServerConfig.REMOTE_LINK_RANGE);
        ServerConfig.REMOTE_LINK_RANGE.set(4);
    }

    @AfterBatch(batch = LINK_RANGE_BATCH)
    public static void afterLinkRangeBatch(ServerLevel level) {
        ServerConfig.REMOTE_LINK_RANGE.set(originalLinkRange);
    }

    private static int originalRange;
    private static int originalHover;
    private static double originalCone;

    @BeforeBatch(batch = RANGE_BATCH)
    public static void beforeRangeBatch(ServerLevel level) {
        originalRange = ServerConfig.get(ServerConfig.REMOTE_RANGE);
        ServerConfig.REMOTE_RANGE.set(4);
    }

    @AfterBatch(batch = RANGE_BATCH)
    public static void afterRangeBatch(ServerLevel level) {
        ServerConfig.REMOTE_RANGE.set(originalRange);
    }

    @BeforeBatch(batch = HOVER_BATCH)
    public static void beforeHoverBatch(ServerLevel level) {
        originalHover = ServerConfig.get(ServerConfig.REMOTE_RECALL_HOVER_TICKS);
        ServerConfig.REMOTE_RECALL_HOVER_TICKS.set(30);
    }

    @AfterBatch(batch = HOVER_BATCH)
    public static void afterHoverBatch(ServerLevel level) {
        ServerConfig.REMOTE_RECALL_HOVER_TICKS.set(originalHover);
    }

    @BeforeBatch(batch = CONE_BATCH)
    public static void beforeConeBatch(ServerLevel level) {
        originalCone = ServerConfig.get(ServerConfig.REMOTE_LINK_CONE_ANGLE);
        ServerConfig.REMOTE_LINK_CONE_ANGLE.set(30.0);
    }

    @AfterBatch(batch = CONE_BATCH)
    public static void afterConeBatch(ServerLevel level) {
        ServerConfig.REMOTE_LINK_CONE_ANGLE.set(originalCone);
    }

    // =====================================================================================================
    // 1. Link and permission (sections 2.10, 6.2, 6.3)
    // =====================================================================================================

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void linkStoresIdLabelAndColorAndNeverSetsOwner(GameTestHelper helper) {
        String id = newId();
        DroneData base = data(id);
        DroneData data = base.withConfig(base.config().withLabel("Scout").withColor(DyeColor.RED));
        DroneEntity drone = spawnDrone(helper, 4, 3, 4, data);
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 1.5, 0);
        ItemStack remote = new ItemStack(ModItems.DRONE_REMOTE.get());

        RemoteControl.link(player, remote, drone);

        RemoteLink link = remote.get(ModDataComponents.REMOTE_LINK);
        helper.assertTrue(link != null, "The remote should be linked");
        helper.assertValueEqual(link, new RemoteLink(id, "Scout", DyeColor.RED), "stored link");
        helper.assertValueEqual(drone.snapshotData().ownerId(), Optional.empty(), "linking must not set the owner");
        helper.assertValueEqual(drone.snapshotData().groupId(), Optional.empty(), "linking must not set a group");
        removePlayer(helper, player);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void unownedDroneIsLinkableByAnyPlayerWithoutOwnerChange(GameTestHelper helper) {
        DroneEntity drone = spawnDrone(helper, 4, 3, 4, data(newId()));
        ServerPlayer a = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 1.5, 0);
        ItemStack remote = new ItemStack(ModItems.DRONE_REMOTE.get());
        RemoteControl.link(a, remote, drone);
        helper.assertTrue(remote.get(ModDataComponents.REMOTE_LINK) != null, "An unowned drone should be linkable by anyone");
        helper.assertValueEqual(drone.snapshotData().ownerId(), Optional.empty(), "owner");
        removePlayer(helper, a);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void nonOperatorCannotLinkOwnedDrone(GameTestHelper helper) {
        DroneData owned = data(newId()).withOwnerId(Optional.of(UUID.randomUUID()));
        DroneEntity drone = spawnDrone(helper, 4, 3, 4, owned);
        ServerPlayer stranger = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 1.5, 0);
        ItemStack remote = new ItemStack(ModItems.DRONE_REMOTE.get());
        RemoteControl.link(stranger, remote, drone);
        helper.assertTrue(remote.get(ModDataComponents.REMOTE_LINK) == null, "A non-operator must not be able to link, got " + remote.get(ModDataComponents.REMOTE_LINK));
        removePlayer(helper, stranger);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void nonOperatorCannotLinkGroupDrone(GameTestHelper helper) {
        UUID groupId = OperatorGroups.get(server(helper)).createGroup(UUID.randomUUID());
        DroneEntity drone = spawnDrone(helper, 4, 3, 4, data(newId()).withGroupId(Optional.of(groupId)));
        ServerPlayer stranger = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 1.5, 0);
        ItemStack remote = new ItemStack(ModItems.DRONE_REMOTE.get());
        RemoteControl.link(stranger, remote, drone);
        helper.assertTrue(remote.get(ModDataComponents.REMOTE_LINK) == null, "A non-operator of the group must not be able to link");
        removePlayer(helper, stranger);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void groupOperatorCanLink(GameTestHelper helper) {
        UUID owner = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        OperatorGroups groups = OperatorGroups.get(server(helper));
        UUID groupId = groups.createGroup(owner);
        groups.addOperator(groupId, member);
        DroneEntity drone = spawnDrone(helper, 4, 3, 4, data(newId()).withGroupId(Optional.of(groupId)));
        ServerPlayer player = spawnPlayer(helper, member, 4.5, 1, 1.5, 0);
        ItemStack remote = new ItemStack(ModItems.DRONE_REMOTE.get());
        RemoteControl.link(player, remote, drone);
        helper.assertTrue(remote.get(ModDataComponents.REMOTE_LINK) != null, "A group operator should be able to link");
        removePlayer(helper, player);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void serverOpBypassesOperatorCheckWhenLinking(GameTestHelper helper) {
        DroneData owned = data(newId()).withOwnerId(Optional.of(UUID.randomUUID()));
        DroneEntity drone = spawnDrone(helper, 4, 3, 4, owned);
        ServerPlayer admin = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 1.5, 2);
        ItemStack remote = new ItemStack(ModItems.DRONE_REMOTE.get());
        RemoteControl.link(admin, remote, drone);
        helper.assertTrue(remote.get(ModDataComponents.REMOTE_LINK) != null, "A permission level 2 player should bypass the check");
        helper.assertValueEqual(drone.snapshotData().ownerId(), owned.ownerId(), "owner must stay the same");
        removePlayer(helper, admin);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void linkingAnotherDroneReplacesTheLink(GameTestHelper helper) {
        String idA = newId();
        String idB = newId();
        DroneEntity a = spawnDrone(helper, 2, 3, 4, data(idA));
        DroneEntity b = spawnDrone(helper, 6, 3, 4, data(idB));
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 1.5, 0);
        ItemStack remote = new ItemStack(ModItems.DRONE_REMOTE.get());
        RemoteControl.link(player, remote, a);
        RemoteControl.link(player, remote, b);
        helper.assertValueEqual(remote.get(ModDataComponents.REMOTE_LINK).droneId(), idB, "link after relinking");
        removePlayer(helper, player);
        helper.succeed();
    }

    // =====================================================================================================
    // 2. Link by aiming (section 2.10). The player looks along +Z (yaw 0), drones are placed relative to the eye.
    // =====================================================================================================

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void aimingLinksTheDroneNearestTheCrosshair(GameTestHelper helper) {
        String near = newId();
        String far = newId();
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 1.5, 0);
        Vec3 eye = player.getEyePosition();
        // 5 degrees off, then 2 degrees off, both 5 blocks ahead.
        spawnDroneCentered(helper, eye.add(5 * Math.tan(Math.toRadians(5)), 0, 5), data(far));
        spawnDroneCentered(helper, eye.add(-5 * Math.tan(Math.toRadians(2)), 0, 5), data(near));
        ItemStack remote = new ItemStack(ModItems.DRONE_REMOTE.get());

        RemoteControl.linkByAiming(player, remote);

        RemoteLink link = remote.get(ModDataComponents.REMOTE_LINK);
        helper.assertTrue(link != null, "A drone within the cone should be linked");
        helper.assertValueEqual(link.droneId(), near, "the drone nearest the crosshair");
        removePlayer(helper, player);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void aimingIgnoresDroneOutsideTheCone(GameTestHelper helper) {
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 1.5, 0);
        Vec3 eye = player.getEyePosition();
        // 25 degrees off with the default 10 degree cone.
        spawnDroneCentered(helper, eye.add(5 * Math.tan(Math.toRadians(25)), 0, 5), data(newId()));
        ItemStack remote = new ItemStack(ModItems.DRONE_REMOTE.get());

        RemoteControl.linkByAiming(player, remote);

        helper.assertTrue(remote.get(ModDataComponents.REMOTE_LINK) == null, "A drone outside the cone must not be linked, got " + remote.get(ModDataComponents.REMOTE_LINK));
        removePlayer(helper, player);
        helper.succeed();
    }

    // Own batch: widens the cone to 30 degrees so the 25 degree drone is a candidate.
    @GameTest(template = "empty", timeoutTicks = 20, batch = CONE_BATCH)
    public static void aimingConeAngleIsConfigurable(GameTestHelper helper) {
        String id = newId();
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 1.5, 0);
        Vec3 eye = player.getEyePosition();
        spawnDroneCentered(helper, eye.add(5 * Math.tan(Math.toRadians(25)), 0, 5), data(id));
        ItemStack remote = new ItemStack(ModItems.DRONE_REMOTE.get());

        RemoteControl.linkByAiming(player, remote);

        RemoteLink link = remote.get(ModDataComponents.REMOTE_LINK);
        helper.assertTrue(link != null && link.droneId().equals(id), "With a 30 degree cone the 25 degree drone should be linked, got " + link);
        removePlayer(helper, player);
        helper.succeed();
    }

    // Own batch: remote.linkRange is 4.
    @GameTest(template = "empty", timeoutTicks = 20, batch = LINK_RANGE_BATCH)
    public static void aimingLinksADroneWithinLinkRange(GameTestHelper helper) {
        String id = newId();
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 1.5, 0);
        spawnDroneCentered(helper, player.getEyePosition().add(0, 0, 3), data(id));
        ItemStack remote = new ItemStack(ModItems.DRONE_REMOTE.get());
        RemoteControl.linkByAiming(player, remote);
        RemoteLink link = remote.get(ModDataComponents.REMOTE_LINK);
        helper.assertTrue(link != null && link.droneId().equals(id), "A drone 3 blocks ahead is within a link range of 4, got " + link);
        removePlayer(helper, player);
        helper.succeed();
    }

    // Own batch: remote.linkRange is 4.
    @GameTest(template = "empty", timeoutTicks = 20, batch = LINK_RANGE_BATCH)
    public static void aimingIgnoresDroneBeyondLinkRange(GameTestHelper helper) {
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 0.5, 0);
        spawnDroneCentered(helper, player.getEyePosition().add(0, 0, 6), data(newId()));
        ItemStack remote = new ItemStack(ModItems.DRONE_REMOTE.get());
        RemoteControl.linkByAiming(player, remote);
        helper.assertTrue(remote.get(ModDataComponents.REMOTE_LINK) == null, "A drone 6 blocks ahead is beyond a link range of 4, got " + remote.get(ModDataComponents.REMOTE_LINK));
        removePlayer(helper, player);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void aimingIgnoresDroneBehindAWall(GameTestHelper helper) {
        String id = newId();
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 1.5, 0);
        Vec3 eye = player.getEyePosition();
        spawnDroneCentered(helper, eye.add(0, 0, 5), data(id));
        BlockPos wall = BlockPos.containing(eye.add(0, 0, 2.5));
        helper.getLevel().setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState());
        ItemStack remote = new ItemStack(ModItems.DRONE_REMOTE.get());

        RemoteControl.linkByAiming(player, remote);

        helper.assertTrue(remote.get(ModDataComponents.REMOTE_LINK) == null, "A drone behind a wall must not be linked, got " + remote.get(ModDataComponents.REMOTE_LINK));
        helper.getLevel().setBlockAndUpdate(wall, Blocks.AIR.defaultBlockState());
        removePlayer(helper, player);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void aimingSkipsDronesThePlayerCannotOperate(GameTestHelper helper) {
        String locked = newId();
        String open = newId();
        UUID groupId = OperatorGroups.get(server(helper)).createGroup(UUID.randomUUID());
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 1.5, 0);
        Vec3 eye = player.getEyePosition();
        // The locked drone is dead ahead and closer, the open one 4 degrees off.
        spawnDroneCentered(helper, eye.add(0, 0, 4), data(locked).withGroupId(Optional.of(groupId)));
        spawnDroneCentered(helper, eye.add(5 * Math.tan(Math.toRadians(4)), 0, 5), data(open));
        ItemStack remote = new ItemStack(ModItems.DRONE_REMOTE.get());

        RemoteControl.linkByAiming(player, remote);

        RemoteLink link = remote.get(ModDataComponents.REMOTE_LINK);
        helper.assertTrue(link != null, "The drone the player may use should be linked");
        helper.assertValueEqual(link.droneId(), open, "linked drone");
        removePlayer(helper, player);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void aimingAtOnlyAnUnoperableDroneLinksNothing(GameTestHelper helper) {
        UUID groupId = OperatorGroups.get(server(helper)).createGroup(UUID.randomUUID());
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 1.5, 0);
        Vec3 eye = player.getEyePosition();
        spawnDroneCentered(helper, eye.add(0, 0, 4), data(newId()).withGroupId(Optional.of(groupId)));
        ItemStack remote = new ItemStack(ModItems.DRONE_REMOTE.get());
        RemoteControl.linkByAiming(player, remote);
        helper.assertTrue(remote.get(ModDataComponents.REMOTE_LINK) == null, "An unoperable drone must not be linked");
        removePlayer(helper, player);
        helper.succeed();
    }

    // =====================================================================================================
    // 3. DroneIndex (section 2.10)
    // =====================================================================================================

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void indexFindsDeployedDroneById(GameTestHelper helper) {
        String id = newId();
        DroneEntity drone = spawnDrone(helper, 4, 3, 4, data(id));
        helper.assertTrue(DroneIndex.find(helper.getLevel(), id) == drone, "The index should find the deployed drone");
        helper.assertTrue(DroneIndex.find(server(helper), id) == drone, "The server-wide lookup should find it too");
        helper.assertTrue(DroneIndex.find(helper.getLevel(), newId()) == null, "An unknown ID finds nothing");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void indexLosesDroneAfterDiscard(GameTestHelper helper) {
        String id = newId();
        DroneEntity drone = spawnDrone(helper, 4, 3, 4, data(id));
        drone.discard();
        helper.assertTrue(DroneIndex.find(helper.getLevel(), id) == null, "A discarded drone must leave the index");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void indexLosesDroneAfterPickup(GameTestHelper helper) {
        String id = newId();
        DroneEntity drone = spawnDrone(helper, 4, 3, 4, data(id));
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 4.5, 0);
        player.setShiftKeyDown(true);
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        drone.interact(player, InteractionHand.MAIN_HAND);
        helper.assertTrue(drone.isRemoved(), "Sanity: the drone should have been picked up");
        helper.assertTrue(DroneIndex.find(helper.getLevel(), id) == null, "A picked-up drone must leave the index");
        removePlayer(helper, player);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void indexFollowsAnIdChange(GameTestHelper helper) {
        String oldId = newId();
        String newId = newId();
        DroneEntity drone = spawnDrone(helper, 4, 3, 4, data(oldId));
        drone.setDroneData(drone.snapshotData().withDroneId(newId));
        helper.assertTrue(DroneIndex.find(helper.getLevel(), oldId) == null, "The old ID should not find the drone any more");
        helper.assertTrue(DroneIndex.find(helper.getLevel(), newId) == drone, "The new ID should find the drone");
        helper.succeed();
    }

    // =====================================================================================================
    // 4. Reach (section 2.10)
    // =====================================================================================================

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void findReportsNotDeployedForUnknownOrEmptyId(GameTestHelper helper) {
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 4.5, 0);
        helper.assertValueEqual(RemoteControl.find(player, newId()).reach(), RemoteReach.NOT_DEPLOYED, "unknown ID");
        helper.assertValueEqual(RemoteControl.find(player, "").reach(), RemoteReach.NOT_DEPLOYED, "empty ID");
        removePlayer(helper, player);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void findReportsNotDeployedAfterPickup(GameTestHelper helper) {
        String id = newId();
        DroneEntity drone = spawnDrone(helper, 4, 3, 4, data(id));
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 1.5, 0);
        helper.assertValueEqual(RemoteControl.find(player, id).reach(), RemoteReach.OK, "before");
        drone.discard();
        helper.assertValueEqual(RemoteControl.find(player, id).reach(), RemoteReach.NOT_DEPLOYED, "after discard");
        removePlayer(helper, player);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void findReportsOkWithDroneAndDistance(GameTestHelper helper) {
        String id = newId();
        DroneEntity drone = spawnDrone(helper, 4, 3, 7, data(id));
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 1.5, 0);
        RemoteControl.Target target = RemoteControl.find(player, id);
        helper.assertValueEqual(target.reach(), RemoteReach.OK, "reach");
        helper.assertTrue(target.drone() == drone, "the drone should be returned");
        helper.assertTrue(target.distance() >= 4 && target.distance() <= 7, "distance should be roughly 5-6 blocks, was " + target.distance());
        removePlayer(helper, player);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void findReportsDeniedForNonOperator(GameTestHelper helper) {
        String id = newId();
        spawnDrone(helper, 4, 3, 4, data(id).withOwnerId(Optional.of(UUID.randomUUID())));
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 1.5, 0);
        RemoteControl.Target target = RemoteControl.find(player, id);
        helper.assertValueEqual(target.reach(), RemoteReach.DENIED, "reach");
        helper.assertTrue(target.drone() == null, "a denied lookup must not reveal the drone");
        removePlayer(helper, player);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void findReportsOtherDimension(GameTestHelper helper) {
        ServerLevel nether = server(helper).getLevel(Level.NETHER);
        helper.assertTrue(nether != null, "Sanity: the Nether should be loaded on the GameTest server");
        String id = newId();
        DroneEntity drone = ModEntityTypes.DRONE.get().create(nether);
        nether.getChunk(0, 0);
        drone.moveTo(8.5, 100, 8.5);
        drone.setDroneData(data(id));
        nether.addFreshEntity(drone);
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 1.5, 0);
        try {
            helper.assertTrue(DroneIndex.find(nether, id) == drone, "Sanity: the drone should be indexed in the Nether");
            helper.assertValueEqual(RemoteControl.find(player, id).reach(), RemoteReach.OTHER_DIMENSION, "reach");
        } finally {
            drone.discard();
            removePlayer(helper, player);
        }
        helper.succeed();
    }

    // Own batch: remote.range is 4.
    @GameTest(template = "empty", timeoutTicks = 20, batch = RANGE_BATCH)
    public static void findReportsOutOfRangeBeyondRemoteRange(GameTestHelper helper) {
        String far = newId();
        String near = newId();
        spawnDrone(helper, 4, 3, 8, data(far));
        spawnDrone(helper, 4, 3, 2, data(near));
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 0.5, 0);
        helper.assertValueEqual(RemoteControl.find(player, far).reach(), RemoteReach.OUT_OF_RANGE, "7.5 blocks with range 4");
        helper.assertValueEqual(RemoteControl.find(player, near).reach(), RemoteReach.OK, "about 2 blocks with range 4");
        removePlayer(helper, player);
        helper.succeed();
    }

    // Own batch: remote.range is 4.
    @GameTest(template = "empty", timeoutTicks = 20, batch = RANGE_BATCH)
    public static void commandAndSettingsAreRefusedWhenOutOfRange(GameTestHelper helper) {
        String id = newId();
        DroneEntity drone = spawnDrone(helper, 4, 3, 8, data(id));
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 0.5, 0);
        linkedRemote(player, drone);
        RemoteControl.command(player, id, RemoteCommand.HOLD);
        helper.assertTrue(drone.getState() != DroneState.HOLDING, "Hold must be refused out of range, state=" + drone.getState());
        RemoteControl.editSettings(player, new EditRemoteSettingsPayload(id, 2, Optional.empty(), Optional.empty(), "Nope", DyeColor.RED));
        helper.assertValueEqual(drone.snapshotData().config().label(), "", "label after refused edit");
        removePlayer(helper, player);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void commandAndSettingsAreRefusedWhenNotDeployed(GameTestHelper helper) {
        String id = newId();
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 0.5, 0);
        ItemStack remote = new ItemStack(ModItems.DRONE_REMOTE.get());
        remote.set(ModDataComponents.REMOTE_LINK, new RemoteLink(id, "", DyeColor.BLUE));
        player.setItemInHand(InteractionHand.MAIN_HAND, remote);
        // Nothing to find: neither call may throw.
        RemoteControl.command(player, id, RemoteCommand.HOLD);
        RemoteControl.editSettings(player, new EditRemoteSettingsPayload(id, 2, Optional.empty(), Optional.empty(), "x", DyeColor.RED));
        removePlayer(helper, player);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void commandIsRefusedWithoutAMatchingRemoteInHand(GameTestHelper helper) {
        String id = newId();
        DroneEntity drone = spawnDrone(helper, 4, 3, 4, data(id));
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 1.5, 0);
        RemoteControl.command(player, id, RemoteCommand.HOLD);
        helper.assertTrue(drone.getState() != DroneState.HOLDING, "No remote in hand: refused");
        ItemStack other = new ItemStack(ModItems.DRONE_REMOTE.get());
        other.set(ModDataComponents.REMOTE_LINK, new RemoteLink(newId(), "", DyeColor.BLUE));
        player.setItemInHand(InteractionHand.MAIN_HAND, other);
        RemoteControl.command(player, id, RemoteCommand.HOLD);
        helper.assertTrue(drone.getState() != DroneState.HOLDING, "A remote linked to another drone: refused");
        removePlayer(helper, player);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void commandAndSettingsAreRefusedForNonOperators(GameTestHelper helper) {
        String id = newId();
        DroneEntity drone = spawnDrone(helper, 4, 3, 4, data(id).withOwnerId(Optional.of(UUID.randomUUID())));
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 1.5, 0);
        ItemStack remote = new ItemStack(ModItems.DRONE_REMOTE.get());
        remote.set(ModDataComponents.REMOTE_LINK, new RemoteLink(id, "", DyeColor.BLUE));
        player.setItemInHand(InteractionHand.MAIN_HAND, remote);
        RemoteControl.command(player, id, RemoteCommand.HOLD);
        helper.assertTrue(drone.getState() != DroneState.HOLDING, "Hold by a non-operator must be refused");
        RemoteControl.editSettings(player, new EditRemoteSettingsPayload(id, 2, Optional.empty(), Optional.empty(), "Mine", DyeColor.RED));
        helper.assertValueEqual(drone.snapshotData().config().label(), "", "label after refused edit");
        removePlayer(helper, player);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void permissionIsRecheckedAtUseTime(GameTestHelper helper) {
        UUID owner = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        OperatorGroups groups = OperatorGroups.get(server(helper));
        UUID groupId = groups.createGroup(owner);
        groups.addOperator(groupId, member);
        String id = newId();
        DroneEntity drone = spawnDrone(helper, 4, 3, 4, data(id).withGroupId(Optional.of(groupId)));
        ServerPlayer player = spawnPlayer(helper, member, 4.5, 1, 1.5, 0);
        linkedRemote(player, drone);

        RemoteControl.command(player, id, RemoteCommand.HOLD);
        helper.assertValueEqual(drone.getState(), DroneState.HOLDING, "Hold as an operator");

        groups.removeOperator(groupId, member);
        RemoteControl.command(player, id, RemoteCommand.RESUME);
        helper.assertValueEqual(drone.getState(), DroneState.HOLDING, "Resume after the operator was removed must fail");
        RemoteControl.editSettings(player, new EditRemoteSettingsPayload(id, 2, Optional.empty(), Optional.empty(), "Late", DyeColor.RED));
        helper.assertValueEqual(drone.snapshotData().config().label(), "", "label after revoked edit");
        removePlayer(helper, player);
        helper.succeed();
    }

    // =====================================================================================================
    // 5. Recall (sections 2.10, 3.1, 3.3)
    // =====================================================================================================

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void recallDropsTargetAndClaimAndFliesInFrontOfThePlayer(GameTestHelper helper) {
        String id = newId();
        DroneEntity drone = spawnDrone(helper, 4, 3, 7, zombieData(id));
        DroneEntity rival = spawnDrone(helper, 7, 3, 7, data(newId()));
        rival.setNoGravity(true);
        Zombie zombie = stationaryZombie(helper, 2, 1, 7);
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 0.5, 0);
        linkedRemote(player, drone);
        double distance = ServerConfig.get(ServerConfig.REMOTE_RECALL_DISTANCE);

        pollUntil(helper, () -> drone.getSeekTarget() == zombie, 40, () -> {
            // The rival can't claim a zombie the other drone already holds (non-Explosive: one per team and target).
            helper.assertFalse(TargetClaims.canClaim(rival, zombie), "Sanity: the first drone should hold a claim on the zombie");
            RemoteControl.command(player, id, RemoteCommand.RECALL);
            helper.assertValueEqual(drone.getState(), DroneState.RECALLED, "state right after Recall");
            helper.assertTrue(drone.getSeekTarget() == null, "Recall drops the target");
            helper.assertTrue(TargetClaims.canClaim(rival, zombie), "Recall releases the claim");
            Vec3 eye = player.getEyePosition();
            AtomicInteger settled = new AtomicInteger();
            helper.onEachTick(() -> {
                helper.assertTrue(drone.getSeekTarget() == null, "A recalled drone must not scan for a target even with one in view");
                helper.assertTrue(drone.isPassive(), "Should stay RECALLED (flying) or HOLDING (arrived), state=" + drone.getState());
                Vec3 center = drone.position().add(0, drone.getBbHeight() / 2, 0);
                double horizontal = Math.hypot(center.x - eye.x, center.z - eye.z);
                if (Math.abs(horizontal - distance) < 0.9 && Math.abs(center.y - eye.y) < 0.9 && center.z > eye.z) {
                    if (settled.incrementAndGet() >= 40) {
                        removePlayer(helper, player);
                        helper.succeed();
                    }
                } else {
                    settled.set(0);
                }
            });
        }, () -> helper.fail("Sanity: the drone never acquired the zombie, state=" + drone.getState()));
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void recalledDroneStaysRecalledWithTargetInViewAndIgnoresAttacks(GameTestHelper helper) {
        String id = newId();
        DroneEntity drone = spawnDrone(helper, 4, 3, 7, zombieData(id));
        Zombie zombie = stationaryZombie(helper, 3, 1, 5);
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 0.5, 0);
        linkedRemote(player, drone);
        RemoteControl.command(player, id, RemoteCommand.RECALL);
        AtomicInteger ticks = new AtomicInteger();
        helper.onEachTick(() -> {
            helper.assertTrue(drone.getSeekTarget() == null, "A recalled drone must not hold a target, got " + drone.getSeekTarget());
            helper.assertTrue(drone.isPassive(), "state=" + drone.getState());
            if (ticks.incrementAndGet() >= 45) {
                helper.assertTrue(zombie.isAlive(), "sanity");
                removePlayer(helper, player);
                helper.succeed();
            }
        });
    }

    // Own batch: recallHoverTicks is 30.
    @GameTest(template = "empty", timeoutTicks = 200, batch = HOVER_BATCH)
    public static void recalledPatrolDroneGoesBackToPatrollingAfterTheHover(GameTestHelper helper) {
        String id = newId();
        DroneData base = data(id).withUpgradeCount(UpgradeType.PATROL, 1);
        DroneEntity drone = spawnDrone(helper, 4, 3, 7, base.withConfig(base.config().withPatrolRadius(Optional.of(4))));
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 0.5, 0);
        linkedRemote(player, drone);
        RemoteControl.command(player, id, RemoteCommand.RECALL);
        AtomicInteger recalledTicks = new AtomicInteger();
        helper.succeedWhen(() -> {
            if (drone.isPassive()) {
                recalledTicks.incrementAndGet();
                throw new GameTestAssertException("still RECALLED/HOLDING");
            }
            helper.assertTrue(recalledTicks.get() >= 30, "It should have been recalled at least the 30 hover ticks, was " + recalledTicks.get());
            helper.assertTrue(drone.getState() == DroneState.IDLE || drone.getState() == DroneState.PATROLLING,
                    "After the hover the drone patrols again, state=" + drone.getState());
            removePlayer(helper, player);
        });
    }

    // Own batch: recallHoverTicks is 30.
    @GameTest(template = "empty", timeoutTicks = 200, batch = HOVER_BATCH)
    public static void recalledDroneWithoutPatrolReturnsToWhereItWasRecalled(GameTestHelper helper) {
        String id = newId();
        DroneEntity drone = spawnDrone(helper, 1, 3, 7, data(id));
        Vec3 home = Vec3.atBottomCenterOf(drone.blockPosition());
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 6.5, 1, 0.5, 0);
        linkedRemote(player, drone);
        RemoteControl.command(player, id, RemoteCommand.RECALL);
        AtomicBoolean leftHome = new AtomicBoolean();
        AtomicBoolean ended = new AtomicBoolean();
        helper.succeedWhen(() -> {
            if (drone.position().distanceTo(home) > 2.5) {
                leftHome.set(true);
            }
            if (!drone.isPassive()) {
                ended.set(true);
            }
            helper.assertTrue(leftHome.get(), "Sanity: the drone should fly to the player first");
            helper.assertTrue(ended.get(), "waiting for the hover to end");
            helper.assertTrue(drone.position().distanceTo(home) < 1.0, "The drone should be back at " + home + ", is at " + drone.position());
            removePlayer(helper, player);
        });
    }

    @GameTest(template = "empty", timeoutTicks = 500)
    public static void recallOnADockedDroneUndocksItAndReleasesTheStation(GameTestHelper helper) {
        BlockPos stationRel = new BlockPos(7, 3, 7);
        ChargingStationBlockEntity station = EnergyGameTests.placeChargingStation(helper, stationRel);
        station.getEnergyStorage().receiveEnergy(Integer.MAX_VALUE, false);
        String id = newId();
        DroneEntity drone = spawnDrone(helper, 5, 3, 7, data(id));
        drone.setDroneData(drone.snapshotData().withEnergy(2000));
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 2.5, 1, 1.5, 0);
        linkedRemote(player, drone);
        EnergyGameTests.forceReturning(drone, helper.absolutePos(stationRel));
        pollUntil(helper, () -> drone.getState() == DroneState.CHARGING, 150, () -> {
            helper.assertTrue(station.getClaimant().isPresent(), "Sanity: the station should be claimed while docked");
            RemoteControl.command(player, id, RemoteCommand.RECALL);
            helper.assertValueEqual(drone.getState(), DroneState.RECALLED, "state after Recall");
            helper.assertTrue(drone.getChargingStation() == null, "The drone should have no station any more");
            helper.assertTrue(station.getClaimant().isEmpty(), "The station should be released, claimed by " + station.getClaimant());
            removePlayer(helper, player);
            helper.succeed();
        }, () -> helper.fail("Sanity: the drone never docked, state=" + drone.getState()));
    }

    // =====================================================================================================
    // 6. Hold / Resume (sections 2.10, 3.1, 3.3)
    // =====================================================================================================

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void holdReleasesClaimAndStaysHoldingWithTargetInView(GameTestHelper helper) {
        String id = newId();
        DroneEntity drone = spawnDrone(helper, 4, 3, 7, zombieData(id));
        DroneEntity rival = spawnDrone(helper, 7, 3, 7, data(newId()));
        rival.setNoGravity(true);
        Zombie zombie = stationaryZombie(helper, 2, 1, 7);
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 0.5, 0);
        linkedRemote(player, drone);
        pollUntil(helper, () -> drone.getSeekTarget() == zombie, 40, () -> {
            helper.assertFalse(TargetClaims.canClaim(rival, zombie), "Sanity: claimed");
            RemoteControl.command(player, id, RemoteCommand.HOLD);
            helper.assertValueEqual(drone.getState(), DroneState.HOLDING, "state");
            helper.assertTrue(drone.getSeekTarget() == null, "Hold drops the target");
            helper.assertTrue(TargetClaims.canClaim(rival, zombie), "Hold releases the claim");
            Vec3 at = drone.position();
            AtomicInteger ticks = new AtomicInteger();
            helper.onEachTick(() -> {
                helper.assertTrue(drone.getSeekTarget() == null, "A held drone must not scan or chase");
                helper.assertTrue(drone.getState() == DroneState.HOLDING, "state=" + drone.getState());
                helper.assertTrue(drone.position().distanceTo(at) < 1.0, "A held drone hovers in place, moved " + drone.position().distanceTo(at));
                if (ticks.incrementAndGet() >= 50) {
                    removePlayer(helper, player);
                    helper.succeed();
                }
            });
        }, () -> helper.fail("Sanity: the drone never acquired the zombie"));
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void resumeEndsHoldAndAPatrolDronePatrolsAgain(GameTestHelper helper) {
        String id = newId();
        DroneData base = data(id).withUpgradeCount(UpgradeType.PATROL, 1);
        DroneEntity drone = spawnDrone(helper, 4, 3, 4, base.withConfig(base.config().withPatrolRadius(Optional.of(4))));
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 0.5, 0);
        linkedRemote(player, drone);
        RemoteControl.command(player, id, RemoteCommand.HOLD);
        helper.assertValueEqual(drone.getState(), DroneState.HOLDING, "state after Hold");
        AtomicBoolean fired = new AtomicBoolean();
        helper.runAfterDelay(20, () -> {
            if (!fired.compareAndSet(false, true)) {
                return; // runAfterDelay callbacks that register tick handlers can run a second time
            }
            helper.assertValueEqual(drone.getState(), DroneState.HOLDING, "still held");
            Vec3 at = drone.position();
            RemoteControl.command(player, id, RemoteCommand.RESUME);
            helper.assertFalse(drone.getState() == DroneState.HOLDING, "Resume ends Hold, state=" + drone.getState());
            AtomicInteger moved = new AtomicInteger();
            helper.onEachTick(() -> {
                if (drone.position().distanceTo(at) > 1.5) {
                    removePlayer(helper, player);
                    helper.succeed();
                }
                moved.incrementAndGet();
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void resumeOnADroneWithoutPatrolHoversWhereItIs(GameTestHelper helper) {
        String id = newId();
        DroneEntity drone = spawnDrone(helper, 4, 3, 4, data(id));
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 0.5, 0);
        linkedRemote(player, drone);
        RemoteControl.command(player, id, RemoteCommand.HOLD);
        helper.runAfterDelay(10, () -> {
            Vec3 at = drone.position();
            RemoteControl.command(player, id, RemoteCommand.RESUME);
            helper.assertValueEqual(drone.getState(), DroneState.IDLE, "state after Resume");
            helper.runAfterDelay(30, () -> {
                helper.assertTrue(drone.position().distanceTo(at) < 1.0, "Without Patrol the drone hovers where it is, moved " + drone.position().distanceTo(at));
                helper.assertValueEqual(drone.getState(), DroneState.IDLE, "state later");
                removePlayer(helper, player);
                helper.succeed();
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void resumeEndsRecallToo(GameTestHelper helper) {
        String id = newId();
        DroneEntity drone = spawnDrone(helper, 4, 3, 7, data(id));
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 0.5, 0);
        linkedRemote(player, drone);
        RemoteControl.command(player, id, RemoteCommand.RECALL);
        helper.runAfterDelay(5, () -> {
            helper.assertTrue(drone.isPassive(), "still recalled");
            RemoteControl.command(player, id, RemoteCommand.RESUME);
            helper.assertValueEqual(drone.getState(), DroneState.IDLE, "state after Resume");
            removePlayer(helper, player);
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void holdOnADockedDroneUndocksIt(GameTestHelper helper) {
        BlockPos stationRel = new BlockPos(7, 3, 7);
        ChargingStationBlockEntity station = EnergyGameTests.placeChargingStation(helper, stationRel);
        station.getEnergyStorage().receiveEnergy(Integer.MAX_VALUE, false);
        String id = newId();
        DroneEntity drone = spawnDrone(helper, 5, 3, 7, data(id));
        drone.setDroneData(drone.snapshotData().withEnergy(2000));
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 2.5, 1, 1.5, 0);
        linkedRemote(player, drone);
        EnergyGameTests.forceReturning(drone, helper.absolutePos(stationRel));
        pollUntil(helper, () -> drone.getState() == DroneState.CHARGING, 90, () -> {
            RemoteControl.command(player, id, RemoteCommand.HOLD);
            helper.assertValueEqual(drone.getState(), DroneState.HOLDING, "state after Hold");
            helper.assertTrue(station.getClaimant().isEmpty(), "The station should be released");
            removePlayer(helper, player);
            helper.succeed();
        }, () -> helper.fail("Sanity: never docked, state=" + drone.getState()));
    }

    // =====================================================================================================
    // 7. Energy override (sections 2.10, 5.2)
    // =====================================================================================================

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void heldDroneStillLosesEnergy(GameTestHelper helper) {
        passiveDroneLosesEnergy(helper, RemoteCommand.HOLD, DroneState.HOLDING);
    }

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void recalledDroneStillLosesEnergy(GameTestHelper helper) {
        passiveDroneLosesEnergy(helper, RemoteCommand.RECALL, DroneState.RECALLED);
    }

    private static void passiveDroneLosesEnergy(GameTestHelper helper, RemoteCommand command, DroneState expected) {
        String id = newId();
        DroneEntity drone = spawnDrone(helper, 4, 3, 7, data(id));
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 0.5, 0);
        linkedRemote(player, drone);
        int start = drone.getEnergy();
        RemoteControl.command(player, id, command);
        helper.assertValueEqual(drone.getState(), expected, "state");
        pollUntil(helper, () -> drone.getEnergy() < start, 100, () -> {
            helper.assertTrue(drone.isPassive(), "still passive when the energy dropped, state=" + drone.getState());
            removePlayer(helper, player);
            helper.succeed();
        }, () -> helper.fail("The " + expected + " drone never lost energy, energy=" + drone.getEnergy() + " start=" + start));
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void heldDroneReturnsAtThresholdAndIsNotHeldAfterCharging(GameTestHelper helper) {
        thresholdOverridesPassive(helper, RemoteCommand.HOLD, DroneState.HOLDING);
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void recalledDroneReturnsAtThresholdAndIsNotRecalledAfterCharging(GameTestHelper helper) {
        thresholdOverridesPassive(helper, RemoteCommand.RECALL, DroneState.RECALLED);
    }

    private static void thresholdOverridesPassive(GameTestHelper helper, RemoteCommand command, DroneState passive) {
        BlockPos stationRel = new BlockPos(7, 3, 7);
        ChargingStationBlockEntity station = EnergyGameTests.placeChargingStation(helper, stationRel);
        station.getEnergyStorage().receiveEnergy(Integer.MAX_VALUE, false);
        String id = newId();
        DroneEntity drone = spawnDrone(helper, 1, 3, 1, data(id));
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 1.5, 1, 0.5, 0);
        linkedRemote(player, drone);
        RemoteControl.command(player, id, command);
        helper.assertValueEqual(drone.getState(), passive, "state after command");
        int maxEnergy = com.elpinho.seekerdrones.drone.DroneStats.maxEnergy(drone.snapshotData());
        // Just under the return threshold from where the drone is.
        double distance = drone.position().distanceTo(ChargingStationBlockEntity.dockPosition(helper.absolutePos(stationRel)));
        int below = Math.max(1, (int) com.elpinho.seekerdrones.drone.DroneStats.returnThreshold(drone.snapshotData(), distance) - 2);
        drone.setDroneData(drone.snapshotData().withEnergy(below));
        pollUntil(helper, () -> drone.getState() == DroneState.RETURNING || drone.getState() == DroneState.CHARGING, 60, () -> {
            drone.setDroneData(drone.snapshotData().withEnergy(maxEnergy - 500));
            pollUntil(helper, () -> drone.getEnergy() >= maxEnergy && drone.getState() == DroneState.IDLE && drone.getChargingStation() == null,
                    450, () -> {
                        helper.assertFalse(drone.isPassive(), "After charging the drone is not held or recalled, state=" + drone.getState());
                        removePlayer(helper, player);
                        helper.succeed();
                    }, () -> helper.fail("The drone never finished charging, state=" + drone.getState() + " energy=" + drone.getEnergy()));
        }, () -> helper.fail("The " + passive + " drone never started returning below its threshold, state=" + drone.getState()
                + " energy=" + drone.getEnergy()));
    }

    // =====================================================================================================
    // 8. Return to charge (sections 2.10, 5.2)
    // =====================================================================================================

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void returnToChargeForcesReturningAtHighEnergy(GameTestHelper helper) {
        BlockPos stationRel = new BlockPos(7, 3, 7);
        EnergyGameTests.placeChargingStation(helper, stationRel).getEnergyStorage().receiveEnergy(Integer.MAX_VALUE, false);
        String id = newId();
        DroneEntity drone = spawnDrone(helper, 1, 3, 1, data(id));
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 1.5, 1, 0.5, 0);
        linkedRemote(player, drone);
        helper.assertTrue(drone.getEnergy() >= com.elpinho.seekerdrones.drone.DroneStats.maxEnergy(drone.snapshotData()) - 1, "Sanity: full energy");
        RemoteControl.command(player, id, RemoteCommand.CHARGE);
        helper.assertValueEqual(drone.getState(), DroneState.RETURNING, "state after Charge");
        helper.assertValueEqual(drone.getChargingStation(), helper.absolutePos(stationRel), "target station");
        removePlayer(helper, player);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void returnToChargeOverridesAChasingExplosiveDrone(GameTestHelper helper) {
        BlockPos stationRel = new BlockPos(7, 3, 1);
        EnergyGameTests.placeChargingStation(helper, stationRel).getEnergyStorage().receiveEnergy(Integer.MAX_VALUE, false);
        String id = newId();
        DroneData explosive = zombieData(id).withUpgradeCount(UpgradeType.EXPLOSIVE, 3);
        DroneEntity drone = spawnDrone(helper, 1, 3, 1, explosive);
        Zombie zombie = stationaryZombie(helper, 1, 1, 8);
        helper.setBlock(new BlockPos(1, 5, 8), Blocks.STONE);
        float zombieHealth = zombie.getHealth();
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 1.5, 1, 0.5, 0);
        linkedRemote(player, drone);
        AtomicBoolean sent = new AtomicBoolean();
        helper.onEachTick(() -> {
            if (!sent.get()) {
                helper.assertTrue(!drone.isRemoved(), "The drone exploded before the command could be sent");
                if (drone.getSeekTarget() == zombie) {
                    RemoteControl.command(player, id, RemoteCommand.CHARGE);
                    sent.set(true);
                    helper.assertValueEqual(drone.getState(), DroneState.RETURNING, "state right after Charge");
                    helper.assertTrue(drone.getSeekTarget() == null, "The target should have been dropped");
                }
                return;
            }
            helper.assertTrue(!drone.isRemoved(), "The drone should not have exploded");
            helper.assertTrue(drone.getState() == DroneState.RETURNING || drone.getState() == DroneState.CHARGING, "state=" + drone.getState());
            if (drone.getState() == DroneState.CHARGING) {
                helper.assertTrue(zombie.getHealth() == zombieHealth, "The zombie should be unharmed");
                removePlayer(helper, player);
                helper.succeed();
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void returnToChargeIsRefusedWithoutAUsableStation(GameTestHelper helper) {
        String id = newId();
        // Owned, so stations other tests place within the search radius aren't usable (an unowned drone may use any).
        UUID owner = UUID.randomUUID();
        DroneEntity drone = spawnDrone(helper, 4, 3, 4, data(id).withOwnerId(Optional.of(owner)));
        ServerPlayer player = spawnPlayer(helper, owner, 4.5, 1, 0.5, 0);
        linkedRemote(player, drone);
        helper.assertFalse(drone.returnToCharge(), "No station: refused");
        RemoteControl.command(player, id, RemoteCommand.CHARGE);
        helper.assertValueEqual(drone.getState(), DroneState.IDLE, "state");
        helper.assertTrue(drone.getChargingStation() == null, "no station");
        removePlayer(helper, player);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void returnToChargeIsRefusedWhileReturningOrCharging(GameTestHelper helper) {
        BlockPos stationRel = new BlockPos(7, 3, 7);
        EnergyGameTests.placeChargingStation(helper, stationRel).getEnergyStorage().receiveEnergy(Integer.MAX_VALUE, false);
        String id = newId();
        DroneEntity drone = spawnDrone(helper, 5, 3, 7, data(id));
        drone.setDroneData(drone.snapshotData().withEnergy(2000));
        EnergyGameTests.forceReturning(drone, helper.absolutePos(stationRel));
        helper.assertValueEqual(drone.getState(), DroneState.RETURNING, "Sanity");
        helper.assertFalse(drone.returnToCharge(), "Refused while RETURNING");
        pollUntil(helper, () -> drone.getState() == DroneState.CHARGING, 80, () -> {
            helper.assertFalse(drone.returnToCharge(), "Refused while CHARGING");
            helper.assertValueEqual(drone.getState(), DroneState.CHARGING, "state");
            helper.succeed();
        }, () -> helper.fail("Sanity: never docked, state=" + drone.getState()));
    }

    // =====================================================================================================
    // 9. Patrol center here (sections 2.10, 3.2)
    // =====================================================================================================

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void patrolCenterHereSetsCenterToPlayerEyePosition(GameTestHelper helper) {
        String id = newId();
        DroneEntity drone = spawnDrone(helper, 4, 3, 4, data(id).withUpgradeCount(UpgradeType.PATROL, 1));
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 2.5, 1, 1.5, 0);
        linkedRemote(player, drone);
        RemoteControl.command(player, id, RemoteCommand.CENTER);
        GlobalPos expected = GlobalPos.of(helper.getLevel().dimension(), BlockPos.containing(player.getEyePosition()));
        helper.assertValueEqual(drone.snapshotData().config().patrolCenter(), Optional.of(expected), "patrol center");
        removePlayer(helper, player);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void patrolCenterHereIsRefusedWithoutPatrol(GameTestHelper helper) {
        String id = newId();
        DroneEntity drone = spawnDrone(helper, 4, 3, 4, data(id));
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 2.5, 1, 1.5, 0);
        linkedRemote(player, drone);
        RemoteControl.command(player, id, RemoteCommand.CENTER);
        helper.assertValueEqual(drone.snapshotData().config().patrolCenter(), Optional.empty(), "patrol center");
        helper.assertFalse(drone.setPatrolCenter(GlobalPos.of(helper.getLevel().dimension(), BlockPos.ZERO)), "direct call refused too");
        removePlayer(helper, player);
        helper.succeed();
    }

    // =====================================================================================================
    // 10. Settings (sections 2.10, 7.2)
    // =====================================================================================================

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void settingsAreAppliedForAPatrolDrone(GameTestHelper helper) {
        String id = newId();
        DroneData base = data(id).withUpgradeCount(UpgradeType.PATROL, 2);
        DroneEntity drone = spawnDrone(helper, 4, 3, 4, base);
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 0.5, 0);
        linkedRemote(player, drone);
        int follow = base.config().followDistance() == 1 ? 2 : 1;
        RemoteControl.editSettings(player, new EditRemoteSettingsPayload(id, follow, Optional.of(3), Optional.of(0.2), "Scout", DyeColor.GREEN));
        DroneConfig config = drone.snapshotData().config();
        helper.assertValueEqual(config.followDistance(), follow, "follow distance");
        helper.assertValueEqual(config.patrolRadius(), Optional.of(3), "patrol radius");
        helper.assertValueEqual(config.patrolSpeed(), Optional.of(0.2), "patrol speed");
        helper.assertValueEqual(config.label(), "Scout", "label");
        helper.assertValueEqual(config.color(), DyeColor.GREEN, "color");
        removePlayer(helper, player);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void settingsIgnoreRadiusAndSpeedWithoutPatrol(GameTestHelper helper) {
        String id = newId();
        DroneData base = data(id);
        DroneEntity drone = spawnDrone(helper, 4, 3, 4, base);
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 0.5, 0);
        linkedRemote(player, drone);
        int follow = base.config().followDistance() == 1 ? 2 : 1;
        RemoteControl.editSettings(player, new EditRemoteSettingsPayload(id, follow, Optional.of(3), Optional.of(0.2), "Plain", DyeColor.PINK));
        DroneConfig config = drone.snapshotData().config();
        helper.assertValueEqual(config.patrolRadius(), Optional.empty(), "patrol radius");
        helper.assertValueEqual(config.patrolSpeed(), Optional.empty(), "patrol speed");
        helper.assertValueEqual(config.followDistance(), follow, "follow distance is still applied");
        helper.assertValueEqual(config.label(), "Plain", "label is still applied");
        helper.assertValueEqual(config.color(), DyeColor.PINK, "color is still applied");
        removePlayer(helper, player);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void settingsWithFollowDistanceAboveTheMaximumAreRejected(GameTestHelper helper) {
        String id = newId();
        DroneData base = data(id);
        DroneEntity drone = spawnDrone(helper, 4, 3, 4, base);
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 0.5, 0);
        linkedRemote(player, drone);
        int tooFar = ServerConfig.get(ServerConfig.DRONE_MAX_FOLLOW_DISTANCE) + 1;
        RemoteControl.editSettings(player, new EditRemoteSettingsPayload(id, tooFar, Optional.empty(), Optional.empty(), "Changed", DyeColor.RED));
        helper.assertValueEqual(drone.snapshotData().config(), base.config(), "The whole edit is rejected");
        removePlayer(helper, player);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void settingsWithATooLongLabelAreRejected(GameTestHelper helper) {
        String id = newId();
        DroneData base = data(id);
        DroneEntity drone = spawnDrone(helper, 4, 3, 4, base);
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 0.5, 0);
        linkedRemote(player, drone);
        RemoteControl.editSettings(player, new EditRemoteSettingsPayload(id, base.config().followDistance(), Optional.empty(), Optional.empty(),
                "x".repeat(33), DyeColor.RED));
        helper.assertValueEqual(drone.snapshotData().config(), base.config(), "The whole edit is rejected");
        removePlayer(helper, player);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void settingsEditLeavesTargetsAndUpgradesUntouched(GameTestHelper helper) {
        String id = newId();
        DroneData base = zombieData(id).withUpgradeCount(UpgradeType.PATROL, 1).withUpgradeCount(UpgradeType.SIGHT, 2);
        DroneEntity drone = spawnDrone(helper, 4, 3, 4, base);
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 0.5, 0);
        linkedRemote(player, drone);
        RemoteControl.editSettings(player, new EditRemoteSettingsPayload(id, base.config().followDistance(), Optional.empty(), Optional.empty(),
                "Renamed", DyeColor.ORANGE));
        DroneData after = drone.snapshotData();
        helper.assertValueEqual(after.config().label(), "Renamed", "Sanity: the edit applied");
        helper.assertValueEqual(after.config().targets(), base.config().targets(), "targets");
        helper.assertValueEqual(after.upgrades(), base.upgrades(), "upgrades");
        removePlayer(helper, player);
        helper.succeed();
    }

    // =====================================================================================================
    // 11. Persistence (section 2.10)
    // =====================================================================================================

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void holdingSurvivesAnNbtRoundTrip(GameTestHelper helper) {
        String id = newId();
        DroneEntity original = spawnDrone(helper, 4, 3, 4, data(id));
        original.hold();
        DroneEntity loaded = reload(helper, original);
        helper.assertValueEqual(loaded.getState(), DroneState.HOLDING, "state after loading");
        helper.assertTrue(loaded.isPassive(), "passive");
        helper.runAfterDelay(10, () -> {
            helper.assertValueEqual(loaded.getState(), DroneState.HOLDING, "state a few ticks later");
            helper.succeed();
        });
    }

    // Own batch: recallHoverTicks is 30.
    @GameTest(template = "empty", timeoutTicks = 120, batch = HOVER_BATCH)
    public static void recallHoldSurvivesAnNbtRoundTripWithItsTimer(GameTestHelper helper) {
        String id = newId();
        DroneEntity original = spawnDrone(helper, 4, 3, 4, data(id));
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 1.5, 0);
        original.recall(player);
        DroneEntity[] current = {original};
        pollUntil(helper, () -> original.getState() == DroneState.HOLDING, 60, () -> {
            DroneEntity loaded = reload(helper, original);
            current[0] = loaded;
            helper.assertValueEqual(loaded.getState(), DroneState.HOLDING, "state after loading");
            pollUntil(helper, () -> !loaded.isPassive(), 60, () -> {
                removePlayer(helper, player);
                helper.succeed();
            }, () -> helper.fail("The reloaded recall-hold never timed out, state=" + loaded.getState()));
        }, () -> helper.fail("Sanity: the recalled drone never arrived, state=" + original.getState()));
    }

    // Own batch: recallHoverTicks is 30.
    @GameTest(template = "empty", timeoutTicks = 100, batch = HOVER_BATCH)
    public static void plainHoldHasNoTimeout(GameTestHelper helper) {
        String id = newId();
        DroneEntity drone = spawnDrone(helper, 4, 3, 4, data(id));
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 0.5, 0);
        linkedRemote(player, drone);
        RemoteControl.command(player, id, RemoteCommand.HOLD);
        helper.runAfterDelay(70, () -> {
            helper.assertValueEqual(drone.getState(), DroneState.HOLDING, "a Hold from the remote never times out");
            removePlayer(helper, player);
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void recalledSurvivesAnNbtRoundTrip(GameTestHelper helper) {
        String id = newId();
        DroneEntity original = spawnDrone(helper, 4, 3, 7, data(id));
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 0.5, 0);
        original.recall(player);
        DroneEntity loaded = reload(helper, original);
        helper.assertValueEqual(loaded.getState(), DroneState.RECALLED, "state after loading");
        helper.assertTrue(loaded.isPassive(), "passive");
        removePlayer(helper, player);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void pickedUpDroneItemAndRedeployedDroneAreNotHeld(GameTestHelper helper) {
        String id = newId();
        DroneEntity drone = spawnDrone(helper, 4, 3, 7, data(id));
        drone.hold();
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 1.5, 0);
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
        // The item data is a plain DroneData: no state is stored in it.
        helper.assertValueEqual(DroneItem.getData(stack).droneId(), id, "item drone id");
        player.setItemInHand(InteractionHand.MAIN_HAND, stack.copy());
        stack.setCount(0);
        ModItems.DRONE.get().use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
        DroneEntity redeployed = DroneIndex.find(helper.getLevel(), id);
        helper.assertTrue(redeployed != null, "The redeployed drone should be in the index");
        helper.assertFalse(redeployed.isPassive(), "A redeployed drone is in a normal state, was " + redeployed.getState());
        helper.assertTrue(redeployed.getState() != DroneState.HOLDING && redeployed.getState() != DroneState.RECALLED, "state=" + redeployed.getState());
        removePlayer(helper, player);
        helper.succeed();
    }

    // =====================================================================================================
    // 12. Interaction (section 2.10)
    // =====================================================================================================

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void interactingWithARemoteInHandLinksInsteadOfPickingUp(GameTestHelper helper) {
        interactLinks(helper, GameType.SURVIVAL, true, false);
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void interactingWithARemoteInCreativeLinksInsteadOfPickingUp(GameTestHelper helper) {
        interactLinks(helper, GameType.CREATIVE, true, false);
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void interactingWithARemoteWithoutSneakingLinksToo(GameTestHelper helper) {
        interactLinks(helper, GameType.SURVIVAL, false, false);
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void interactingWithAnAlreadyLinkedRemoteRelinksInSurvival(GameTestHelper helper) {
        interactLinks(helper, GameType.SURVIVAL, false, true);
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void interactingWithAnAlreadyLinkedRemoteRelinksInCreative(GameTestHelper helper) {
        interactLinks(helper, GameType.CREATIVE, true, true);
    }

    /** Goes through the real {@code Player.interactOn} path and checks the remote in the player's hand. */
    private static void interactLinks(GameTestHelper helper, GameType mode, boolean sneak, boolean previouslyLinked) {
        String id = newId();
        DroneEntity drone = spawnDrone(helper, 4, 3, 4, data(id));
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 1.5, 0);
        player.setGameMode(mode);
        player.setShiftKeyDown(sneak);
        ItemStack remote = new ItemStack(ModItems.DRONE_REMOTE.get());
        if (previouslyLinked) {
            remote.set(ModDataComponents.REMOTE_LINK, new RemoteLink(newId(), "Old", DyeColor.RED));
        }
        player.setItemInHand(InteractionHand.MAIN_HAND, remote);

        InteractionResult res = player.interactOn(drone, InteractionHand.MAIN_HAND);

        RemoteLink link = player.getItemInHand(InteractionHand.MAIN_HAND).get(ModDataComponents.REMOTE_LINK);
        helper.assertTrue(link != null && link.droneId().equals(id), "The remote in hand should be linked to " + id + ", got " + link + " (result " + res + ", mode " + mode + ")");
        helper.assertFalse(drone.isRemoved(), "The drone must not be picked up");
        removePlayer(helper, player);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void withoutARemoteShiftInteractStillPicksUp(GameTestHelper helper) {
        DroneEntity drone = spawnDrone(helper, 4, 3, 4, data(newId()));
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 1.5, 0);
        player.setShiftKeyDown(true);
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        player.interactOn(drone, InteractionHand.MAIN_HAND);
        helper.assertTrue(drone.isRemoved(), "Without a remote, Shift + interact picks the drone up");
        removePlayer(helper, player);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void aNonOperatorWithARemoteCannotLinkByInteracting(GameTestHelper helper) {
        DroneEntity drone = spawnDrone(helper, 4, 3, 4, data(newId()).withOwnerId(Optional.of(UUID.randomUUID())));
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 1.5, 0);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.DRONE_REMOTE.get()));
        player.interactOn(drone, InteractionHand.MAIN_HAND);
        helper.assertTrue(player.getMainHandItem().get(ModDataComponents.REMOTE_LINK) == null, "Not linked");
        helper.assertFalse(drone.isRemoved(), "Not picked up");
        removePlayer(helper, player);
        helper.succeed();
    }

    // =====================================================================================================
    // Helpers
    // =====================================================================================================

    private static MinecraftServer server(GameTestHelper helper) {
        return helper.getLevel().getServer();
    }

    private static String newId() {
        return "T" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private static DroneData data(String id) {
        return DroneData.createNew().withDroneId(id);
    }

    private static DroneData zombieData(String id) {
        DroneData base = data(id);
        return base.withConfig(base.config().withTargets(List.of(new TargetEntry(TargetEntry.Kind.ENTITY_TYPE, "minecraft:zombie"))));
    }

    private static DroneEntity spawnDrone(GameTestHelper helper, int x, int y, int z, DroneData data) {
        // The data goes in before the drone is added, so it is indexed by ID (see DroneIndex.add in onAddedToLevel).
        DroneEntity drone = ModEntityTypes.DRONE.get().create(helper.getLevel());
        drone.setPersistenceRequired();
        Vec3 pos = helper.absoluteVec(Vec3.atBottomCenterOf(new BlockPos(x, y, z)));
        drone.moveTo(pos.x, pos.y, pos.z, 0, 0);
        drone.setDroneData(data);
        helper.getLevel().addFreshEntity(drone);
        return drone;
    }

    /** Spawns a hovering drone whose bounding box center is at the given absolute position. */
    private static DroneEntity spawnDroneCentered(GameTestHelper helper, Vec3 center, DroneData data) {
        DroneEntity drone = ModEntityTypes.DRONE.get().create(helper.getLevel());
        drone.moveTo(center.x, center.y - drone.getBbHeight() / 2, center.z);
        drone.setDroneData(data);
        drone.setNoGravity(true);
        helper.getLevel().addFreshEntity(drone);
        return drone;
    }

    private static Zombie stationaryZombie(GameTestHelper helper, int x, int y, int z) {
        Zombie zombie = helper.spawn(EntityType.ZOMBIE, new BlockPos(x, y, z));
        zombie.setNoAi(true);
        zombie.setNoGravity(true);
        zombie.setInvulnerable(true);
        return zombie;
    }

    /** Puts a remote linked to the drone in the player's main hand. */
    private static ItemStack linkedRemote(ServerPlayer player, DroneEntity drone) {
        ItemStack remote = new ItemStack(ModItems.DRONE_REMOTE.get());
        remote.set(ModDataComponents.REMOTE_LINK, RemoteLink.of(drone.snapshotData()));
        player.setItemInHand(InteractionHand.MAIN_HAND, remote);
        return remote;
    }

    private static DroneEntity reload(GameTestHelper helper, DroneEntity original) {
        // saveWithoutId still writes the UUID: discard the original before adding the copy.
        CompoundTag tag = original.saveWithoutId(new CompoundTag());
        original.discard();
        DroneEntity loaded = ModEntityTypes.DRONE.get().create(helper.getLevel());
        loaded.load(tag);
        helper.getLevel().addFreshEntity(loaded);
        return loaded;
    }

    private static ServerPlayer spawnPlayer(GameTestHelper helper, UUID uuid, double x, double y, double z, int permissionLevel) {
        return spawnPlayer(helper, uuid, x, y, z, permissionLevel, 0);
    }

    /** A real {@link ServerPlayer} (in the player list, so {@code getPlayer(uuid)} finds it) at the given spot, facing +Z. */
    private static ServerPlayer spawnPlayer(GameTestHelper helper, UUID uuid, double x, double y, double z, int permissionLevel, float xRot) {
        MinecraftServer server = server(helper);
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(uuid, "remote-test-" + uuid.toString().substring(0, 8)), false);
        ServerPlayer player = new ServerPlayer(server, helper.getLevel(), cookie.gameProfile(), cookie.clientInformation()) {
            @Override
            public boolean isSpectator() {
                return false;
            }

            @Override
            public boolean isCreative() {
                return false;
            }

            @Override
            protected int getPermissionLevel() {
                return permissionLevel;
            }
        };
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        Vec3 pos = helper.absoluteVec(new Vec3(x, y, z));
        player.moveTo(pos.x, pos.y, pos.z, 0.0F, xRot);
        player.setNoGravity(true);
        // The GameTest server's default game mode is creative; tests that don't care use survival.
        player.setGameMode(GameType.SURVIVAL);
        return player;
    }

    private static void removePlayer(GameTestHelper helper, @Nullable ServerPlayer player) {
        if (player != null) {
            server(helper).getPlayerList().remove(player);
        }
    }

    /**
     * Polls {@code condition} each tick for up to {@code ticks} ticks, running exactly one of the callbacks. (A poll chain
     * can be run twice by the GameTest tick queue when callbacks register more handlers, so it latches when done.)
     */
    private static void pollUntil(GameTestHelper helper, java.util.function.BooleanSupplier condition, int ticks, Runnable onReady, Runnable onTimeout) {
        pollStep(helper, condition, ticks, onReady, onTimeout, new AtomicBoolean());
    }

    private static void pollStep(GameTestHelper helper, java.util.function.BooleanSupplier condition, int ticks, Runnable onReady, Runnable onTimeout,
            AtomicBoolean done) {
        if (done.get()) {
            return;
        }
        if (condition.getAsBoolean()) {
            done.set(true);
            onReady.run();
            return;
        }
        if (ticks <= 1) {
            done.set(true);
            onTimeout.run();
            return;
        }
        helper.runAtTickTime(helper.getTick() + 1, () -> pollStep(helper, condition, ticks - 1, onReady, onTimeout, done));
    }
}
