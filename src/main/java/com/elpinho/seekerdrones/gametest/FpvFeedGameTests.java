package com.elpinho.seekerdrones.gametest;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;

import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.drone.DroneStats;
import com.elpinho.seekerdrones.drone.UpgradeType;
import com.elpinho.seekerdrones.feed.FpvFeeds;
import com.elpinho.seekerdrones.programming.ProgramRules;
import com.elpinho.seekerdrones.registry.ModDataComponents;
import com.elpinho.seekerdrones.registry.ModEntityTypes;
import com.elpinho.seekerdrones.registry.ModItems;
import com.elpinho.seekerdrones.remote.RemoteLink;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * GameTests for the FPV Camera upgrade and the server side of the FPV feed (DESIGN.md section 2.12): starting rules,
 * chunk tracking centered on the drone, the conditions that end a feed, the operator command, the energy cost and
 * the upgrade cap. Tests that change {@link ServerConfig} values run in their own batch and restore the value afterwards.
 */
@GameTestHolder("seekerdrones")
@PrefixGameTestTemplate(false)
public class FpvFeedGameTests {

    private static final String RANGE_BATCH = "config_fpv_remote_range_4";
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
    // 1. Starting from a remote
    // =====================================================================================================

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void operatorWithLinkedRemoteStartsAFeed(GameTestHelper helper) {
        String id = newId();
        UUID uuid = UUID.randomUUID();
        DroneEntity drone = spawnDrone(helper, 4, 3, 5, cameraData(id).withOwnerId(Optional.of(uuid)));
        ServerPlayer player = spawnPlayer(helper, uuid, 4.5, 1, 1.5, 0);
        linkedRemote(player, drone);

        FpvFeeds.startFromRemote(player, id);

        helper.assertTrue(FpvFeeds.isViewing(player), "The feed should have started");
        helper.assertTrue(FpvFeeds.viewedDrone(player) == drone, "The viewed drone should be the linked one");
        removePlayer(helper, player);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void startFromRemoteDoesNothingWithoutTheCamera(GameTestHelper helper) {
        String id = newId();
        DroneEntity drone = spawnDrone(helper, 4, 3, 5, data(id));
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 1.5, 0);
        linkedRemote(player, drone);
        FpvFeeds.startFromRemote(player, id);
        helper.assertFalse(FpvFeeds.isViewing(player), "No FPV Camera: no feed");
        removePlayer(helper, player);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void startFromRemoteDoesNothingWithoutTheRemote(GameTestHelper helper) {
        String id = newId();
        spawnDrone(helper, 4, 3, 5, cameraData(id));
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 1.5, 0);
        FpvFeeds.startFromRemote(player, id);
        helper.assertFalse(FpvFeeds.isViewing(player), "Empty hands: no feed");
        // A remote linked to another drone doesn't count either.
        ItemStack other = new ItemStack(ModItems.DRONE_REMOTE.get());
        other.set(ModDataComponents.REMOTE_LINK, new RemoteLink(newId(), "", net.minecraft.world.item.DyeColor.BLUE));
        player.setItemInHand(InteractionHand.MAIN_HAND, other);
        FpvFeeds.startFromRemote(player, id);
        helper.assertFalse(FpvFeeds.isViewing(player), "A remote linked to another drone: no feed");
        removePlayer(helper, player);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void startFromRemoteDoesNothingForANonOperator(GameTestHelper helper) {
        String id = newId();
        DroneEntity drone = spawnDrone(helper, 4, 3, 5, cameraData(id).withOwnerId(Optional.of(UUID.randomUUID())));
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 1.5, 0);
        linkedRemote(player, drone);
        FpvFeeds.startFromRemote(player, id);
        helper.assertFalse(FpvFeeds.isViewing(player), "A non-operator must not get a feed");
        removePlayer(helper, player);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void serverOpWithLinkedRemoteStartsAFeedOnSomeoneElsesDrone(GameTestHelper helper) {
        String id = newId();
        DroneEntity drone = spawnDrone(helper, 4, 3, 5, cameraData(id).withOwnerId(Optional.of(UUID.randomUUID())));
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 1.5, 2);
        linkedRemote(player, drone);
        FpvFeeds.startFromRemote(player, id);
        helper.assertTrue(FpvFeeds.isViewing(player), "Permission level 2 bypasses the operator check");
        removePlayer(helper, player);
        helper.succeed();
    }

    // Own batch: remote.range is 4.
    @GameTest(template = "empty", timeoutTicks = 20, batch = RANGE_BATCH)
    public static void startFromRemoteDoesNothingOutOfRange(GameTestHelper helper) {
        String id = newId();
        DroneEntity far = spawnDrone(helper, 4, 3, 10, cameraData(id));
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 0.5, 0);
        linkedRemote(player, far);
        FpvFeeds.startFromRemote(player, id);
        helper.assertFalse(FpvFeeds.isViewing(player), "9.5 blocks with range 4: no feed");
        removePlayer(helper, player);
        helper.succeed();
    }

    // =====================================================================================================
    // 2. Chunk tracking
    // =====================================================================================================

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void chunkTrackingCentersOnTheDroneAndReturnsAfterStop(GameTestHelper helper) {
        DroneEntity drone = spawnDrone(helper, 4 + 48, 3, 4, cameraData(newId()));
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 1.5, 0);
        ChunkPos own = player.chunkPosition();
        ChunkPos droneChunk = drone.chunkPosition();
        helper.assertFalse(own.equals(droneChunk), "Sanity: the drone must be in another chunk than the player");
        helper.assertValueEqual(trackingCenter(helper, player), own, "tracking center before the feed");

        FpvFeeds.start(player, drone, false);

        helper.assertValueEqual(trackingCenter(helper, player), droneChunk, "tracking center while watching");
        ChunkTrackingView view = player.getChunkTrackingView();
        int cap = ServerConfig.get(ServerConfig.CAMERA_VIEW_DISTANCE);
        helper.assertTrue(view instanceof ChunkTrackingView.Positioned p && p.viewDistance() <= cap,
                "The view distance should be capped at camera.viewDistance=" + cap + ", view=" + view);

        helper.assertTrue(FpvFeeds.stop(player, false), "stop should report a running feed");
        helper.assertValueEqual(trackingCenter(helper, player), own, "tracking center after stop");
        removePlayer(helper, player);
        helper.succeed();
    }

    // =====================================================================================================
    // 3. Ending a feed
    // =====================================================================================================

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void feedEndsWhenTheDroneIsDiscarded(GameTestHelper helper) {
        DroneEntity drone = spawnDrone(helper, 4, 3, 5, cameraData(newId()));
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 1.5, 0);
        FpvFeeds.start(player, drone, false);
        helper.assertTrue(FpvFeeds.isViewing(player), "Sanity: watching");
        drone.discard();
        helper.succeedWhen(() -> {
            helper.assertFalse(FpvFeeds.isViewing(player), "The feed should end after the drone is gone");
            removePlayer(helper, player);
        });
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void feedEndsWhenTheDroneIsKilled(GameTestHelper helper) {
        DroneEntity drone = spawnDrone(helper, 4, 3, 5, cameraData(newId()));
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 1.5, 0);
        FpvFeeds.start(player, drone, false);
        drone.hurt(helper.getLevel().damageSources().genericKill(), 10000);
        helper.succeedWhen(() -> {
            helper.assertTrue(drone.isRemoved() || !drone.isAlive(), "Sanity: the drone should be dead");
            helper.assertFalse(FpvFeeds.isViewing(player), "The feed should end after the drone died");
            removePlayer(helper, player);
        });
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void feedEndsWhenThePlayerTakesDamage(GameTestHelper helper) {
        DroneEntity drone = spawnDrone(helper, 4, 3, 5, cameraData(newId()));
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 1.5, 0);
        FpvFeeds.start(player, drone, false);
        boolean hurt = player.hurt(helper.getLevel().damageSources().fellOutOfWorld(), 2.0F);
        helper.assertTrue(hurt, "Sanity: the player should have been hurt");
        helper.assertFalse(FpvFeeds.isViewing(player), "Damage ends the feed");
        removePlayer(helper, player);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void remoteFeedEndsAfterTheRemoteIsDropped(GameTestHelper helper) {
        String id = newId();
        UUID uuid = UUID.randomUUID();
        DroneEntity drone = spawnDrone(helper, 4, 3, 5, cameraData(id).withOwnerId(Optional.of(uuid)));
        ServerPlayer player = spawnPlayer(helper, uuid, 4.5, 1, 1.5, 0);
        linkedRemote(player, drone);
        FpvFeeds.startFromRemote(player, id);
        helper.assertTrue(FpvFeeds.isViewing(player), "Sanity: watching");
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
        helper.succeedWhen(() -> {
            helper.assertFalse(FpvFeeds.isViewing(player), "The feed should end within camera.recheckInterval ticks of dropping the remote");
            removePlayer(helper, player);
        });
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void remoteInTheOffHandKeepsTheFeedGoing(GameTestHelper helper) {
        String id = newId();
        UUID uuid = UUID.randomUUID();
        DroneEntity drone = spawnDrone(helper, 4, 3, 5, cameraData(id).withOwnerId(Optional.of(uuid)));
        ServerPlayer player = spawnPlayer(helper, uuid, 4.5, 1, 1.5, 0);
        ItemStack remote = linkedRemote(player, drone);
        FpvFeeds.startFromRemote(player, id);
        helper.assertTrue(FpvFeeds.isViewing(player), "Sanity: watching");
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        player.setItemInHand(InteractionHand.OFF_HAND, remote);
        stillViewingAfter(helper, player, 2 * ServerConfig.get(ServerConfig.CAMERA_RECHECK_INTERVAL) + 3);
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void remoteFeedEndsWhenTheDroneLosesTheCamera(GameTestHelper helper) {
        String id = newId();
        UUID uuid = UUID.randomUUID();
        DroneEntity drone = spawnDrone(helper, 4, 3, 5, cameraData(id).withOwnerId(Optional.of(uuid)));
        ServerPlayer player = spawnPlayer(helper, uuid, 4.5, 1, 1.5, 0);
        linkedRemote(player, drone);
        FpvFeeds.startFromRemote(player, id);
        helper.assertTrue(FpvFeeds.isViewing(player), "Sanity: watching");
        drone.setDroneData(drone.snapshotData().withUpgradeCount(UpgradeType.FPV_CAMERA, 0));
        helper.succeedWhen(() -> {
            helper.assertFalse(FpvFeeds.isViewing(player), "The feed should end once the camera is gone");
            removePlayer(helper, player);
        });
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void remoteFeedEndsWhenThePlayerIsNoLongerAnOperator(GameTestHelper helper) {
        String id = newId();
        UUID uuid = UUID.randomUUID();
        DroneEntity drone = spawnDrone(helper, 4, 3, 5, cameraData(id).withOwnerId(Optional.of(uuid)));
        ServerPlayer player = spawnPlayer(helper, uuid, 4.5, 1, 1.5, 0);
        linkedRemote(player, drone);
        FpvFeeds.startFromRemote(player, id);
        helper.assertTrue(FpvFeeds.isViewing(player), "Sanity: watching");
        drone.setDroneData(drone.snapshotData().withOwnerId(Optional.of(UUID.randomUUID())));
        helper.succeedWhen(() -> {
            helper.assertFalse(FpvFeeds.isViewing(player), "The feed should end once the player isn't an operator");
            removePlayer(helper, player);
        });
    }

    // Own batch: remote.range is 4.
    @GameTest(template = "empty", timeoutTicks = 60, batch = RANGE_BATCH)
    public static void remoteFeedEndsWhenTheDroneGoesOutOfRange(GameTestHelper helper) {
        String id = newId();
        DroneEntity drone = spawnDrone(helper, 4, 3, 3, cameraData(id));
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 0.5, 0);
        linkedRemote(player, drone);
        FpvFeeds.startFromRemote(player, id);
        helper.assertTrue(FpvFeeds.isViewing(player), "Sanity: watching within range");
        Vec3 away = helper.absoluteVec(new Vec3(4.5, 3, 12.5));
        drone.setNoGravity(true);
        drone.moveTo(away.x, away.y, away.z);
        helper.succeedWhen(() -> {
            helper.assertFalse(FpvFeeds.isViewing(player), "The feed should end once the drone is beyond remote.range");
            removePlayer(helper, player);
        });
    }

    // =====================================================================================================
    // 4. Command feeds
    // =====================================================================================================

    // Own batch: remote.range is 4, the drone is 11 blocks away and has no camera.
    @GameTest(template = "empty", timeoutTicks = 60, batch = RANGE_BATCH)
    public static void commandFeedIgnoresRangeAndCameraAndSurvivesRechecks(GameTestHelper helper) throws CommandSyntaxException {
        String id = newId();
        spawnDrone(helper, 4, 3, 12, data(id).withOwnerId(Optional.of(UUID.randomUUID())));
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 0.5, 0);

        runCommand(helper, player, "seekerdrones view " + id);

        helper.assertTrue(FpvFeeds.isViewing(player), "The command feed should start without remote, operator, range or camera");
        stillViewingAfter(helper, player, 2 * ServerConfig.get(ServerConfig.CAMERA_RECHECK_INTERVAL) + 3);
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void viewStopEndsACommandFeed(GameTestHelper helper) throws CommandSyntaxException {
        String id = newId();
        spawnDrone(helper, 4, 3, 5, data(id));
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 1.5, 0);
        runCommand(helper, player, "seekerdrones view " + id);
        helper.assertTrue(FpvFeeds.isViewing(player), "Sanity: watching");
        runCommand(helper, player, "seekerdrones view stop");
        helper.assertFalse(FpvFeeds.isViewing(player), "view stop should end the feed");
        removePlayer(helper, player);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void viewCanTargetAnotherPlayer(GameTestHelper helper) throws CommandSyntaxException {
        String id = newId();
        spawnDrone(helper, 4, 3, 5, data(id));
        ServerPlayer viewer = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 1.5, 0);
        ServerPlayer admin = spawnPlayer(helper, UUID.randomUUID(), 6.5, 1, 1.5, 2);
        runCommand(helper, admin, "seekerdrones view " + id + " " + viewer.getGameProfile().getName());
        helper.assertTrue(FpvFeeds.isViewing(viewer), "The named player should be watching");
        helper.assertFalse(FpvFeeds.isViewing(admin), "The sender should not");
        runCommand(helper, admin, "seekerdrones view stop " + viewer.getGameProfile().getName());
        helper.assertFalse(FpvFeeds.isViewing(viewer), "view stop <player> should end it");
        removePlayer(helper, viewer);
        removePlayer(helper, admin);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void viewStopWithoutAFeedFails(GameTestHelper helper) {
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 1.5, 0);
        helper.assertTrue(commandFails(helper, player, "seekerdrones view stop"), "view stop with no feed must fail");
        removePlayer(helper, player);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void viewUnknownDroneFails(GameTestHelper helper) {
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 1.5, 0);
        helper.assertTrue(commandFails(helper, player, "seekerdrones view " + newId()), "An unknown ID must fail");
        helper.assertFalse(FpvFeeds.isViewing(player), "No feed");
        removePlayer(helper, player);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void viewDroneInAnotherDimensionFails(GameTestHelper helper) {
        ServerLevel nether = server(helper).getLevel(Level.NETHER);
        helper.assertTrue(nether != null, "Sanity: the Nether should be loaded");
        String id = newId();
        DroneEntity drone = ModEntityTypes.DRONE.get().create(nether);
        nether.getChunk(0, 0);
        drone.moveTo(8.5, 100, 8.5);
        drone.setDroneData(data(id));
        nether.addFreshEntity(drone);
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 1.5, 0);
        try {
            helper.assertTrue(commandFails(helper, player, "seekerdrones view " + id), "A drone in another dimension must fail");
            helper.assertFalse(FpvFeeds.isViewing(player), "No feed");
        } finally {
            drone.discard();
            removePlayer(helper, player);
        }
        helper.succeed();
    }

    // =====================================================================================================
    // 5. Energy
    // =====================================================================================================

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void watchedDroneDrainsFeedEnergyPerTickMore(GameTestHelper helper) {
        int interval = ServerConfig.get(ServerConfig.DRONE_ENERGY_DRAIN_INTERVAL);
        int feed = ServerConfig.get(ServerConfig.CAMERA_FEED_ENERGY_PER_TICK);
        DroneEntity watched = spawnDrone(helper, 2, 3, 6, cameraData(newId()));
        DroneEntity other = spawnDrone(helper, 8, 3, 6, cameraData(newId()));
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 1.5, 0);
        FpvFeeds.start(player, watched, false);
        helper.runAfterDelay(2 * interval + 5, () -> {
            double a = watched.getDrainRate();
            double b = other.getDrainRate();
            helper.assertTrue(b > 0, "Sanity: the unwatched drone should have a drain rate, was " + b);
            helper.assertTrue(Math.abs((a - b) - feed) < 0.3,
                    "Watched drain " + a + " FE/t minus unwatched " + b + " should be about " + feed + " (camera.feedEnergyPerTick)");
            removePlayer(helper, player);
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void feedCostIsChargedOncePerDroneNotPerViewer(GameTestHelper helper) {
        int interval = ServerConfig.get(ServerConfig.DRONE_ENERGY_DRAIN_INTERVAL);
        int feed = ServerConfig.get(ServerConfig.CAMERA_FEED_ENERGY_PER_TICK);
        DroneEntity twoViewers = spawnDrone(helper, 2, 3, 6, cameraData(newId()));
        DroneEntity other = spawnDrone(helper, 8, 3, 6, cameraData(newId()));
        ServerPlayer p1 = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 1.5, 0);
        ServerPlayer p2 = spawnPlayer(helper, UUID.randomUUID(), 5.5, 1, 1.5, 0);
        FpvFeeds.start(p1, twoViewers, false);
        FpvFeeds.start(p2, twoViewers, false);
        helper.runAfterDelay(2 * interval + 5, () -> {
            double diff = twoViewers.getDrainRate() - other.getDrainRate();
            helper.assertTrue(Math.abs(diff - feed) < 0.3, "Two viewers should still cost only " + feed + " FE/t extra, was " + diff);
            removePlayer(helper, p1);
            removePlayer(helper, p2);
            helper.succeed();
        });
    }

    // =====================================================================================================
    // 6. isWatched
    // =====================================================================================================

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void isWatchedOnlyForTheWatchedDroneAndNotAfterStop(GameTestHelper helper) {
        DroneEntity a = spawnDrone(helper, 2, 3, 6, cameraData(newId()));
        DroneEntity b = spawnDrone(helper, 8, 3, 6, cameraData(newId()));
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4.5, 1, 1.5, 0);
        helper.assertFalse(FpvFeeds.isWatched(a), "Not watched before");
        FpvFeeds.start(player, a, false);
        helper.assertTrue(FpvFeeds.isWatched(a), "A is watched");
        helper.assertFalse(FpvFeeds.isWatched(b), "B is not");
        FpvFeeds.stop(player, false);
        helper.assertFalse(FpvFeeds.isWatched(a), "Not watched after stop");
        helper.assertFalse(FpvFeeds.stop(player, false), "A second stop reports no feed");
        removePlayer(helper, player);
        helper.succeed();
    }

    // =====================================================================================================
    // 7. Upgrade cap
    // =====================================================================================================

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void secondFpvCameraIsRefusedAtTheDefaultCap(GameTestHelper helper) {
        helper.assertValueEqual(UpgradeType.FPV_CAMERA.maxCount(), 1, "default FPV Camera cap");
        DroneEntity drone = spawnDrone(helper, 4, 3, 4, cameraData(newId()));
        helper.assertFalse(ProgramRules.canAdd(drone.snapshotData().upgrades(), UpgradeType.FPV_CAMERA), "Programming Station rules: no second camera");
        helper.assertFalse(DroneStats.withinUpgradeLimits(DroneData.createNew().withUpgradeCount(UpgradeType.FPV_CAMERA, 2).upgrades()),
                "Two cameras exceed the limits");
        helper.assertTrue(ProgramRules.canAdd(DroneData.createNew().upgrades(), UpgradeType.FPV_CAMERA), "The first camera fits");

        MinecraftServer server = server(helper);
        CommandSourceStack source = server.createCommandSourceStack()
                .withLevel(helper.getLevel())
                .withPosition(helper.absoluteVec(new Vec3(4, 3, 4)))
                .withPermission(4);
        server.getCommands().performPrefixedCommand(source, "seekerdrones upgrade set fpv_camera 2 @e[type=seekerdrones:drone,distance=..3]");
        helper.assertValueEqual(drone.snapshotData().upgradeCount(UpgradeType.FPV_CAMERA), 1, "cameras after the refused command");
        helper.succeed();
    }

    // =====================================================================================================
    // Helpers
    // =====================================================================================================

    private static void stillViewingAfter(GameTestHelper helper, ServerPlayer player, int ticks) {
        AtomicInteger waited = new AtomicInteger();
        helper.succeedWhen(() -> {
            helper.assertTrue(FpvFeeds.isViewing(player), "The feed should still be running");
            if (waited.incrementAndGet() < ticks) {
                throw new GameTestAssertException("waiting " + waited.get() + "/" + ticks);
            }
            removePlayer(helper, player);
        });
    }

    private static ChunkPos trackingCenter(GameTestHelper helper, ServerPlayer player) {
        ChunkTrackingView view = player.getChunkTrackingView();
        helper.assertTrue(view instanceof ChunkTrackingView.Positioned, "Expected a positioned tracking view, was " + view);
        return ((ChunkTrackingView.Positioned) view).center();
    }

    private static CommandSourceStack source(GameTestHelper helper, ServerPlayer player) {
        return server(helper).createCommandSourceStack()
                .withLevel(helper.getLevel())
                .withPosition(player.position())
                .withEntity(player)
                .withPermission(4);
    }

    private static void runCommand(GameTestHelper helper, ServerPlayer player, String command) throws CommandSyntaxException {
        server(helper).getCommands().getDispatcher().execute(command, source(helper, player));
    }

    private static boolean commandFails(GameTestHelper helper, ServerPlayer player, String command) {
        try {
            runCommand(helper, player, command);
            return false;
        } catch (CommandSyntaxException e) {
            return true;
        }
    }

    private static MinecraftServer server(GameTestHelper helper) {
        return helper.getLevel().getServer();
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

    private static DroneEntity spawnDrone(GameTestHelper helper, int x, int y, int z, DroneData data) {
        DroneEntity drone = ModEntityTypes.DRONE.get().create(helper.getLevel());
        drone.setPersistenceRequired();
        Vec3 pos = helper.absoluteVec(Vec3.atBottomCenterOf(new BlockPos(x, y, z)));
        drone.moveTo(pos.x, pos.y, pos.z, 0, 0);
        drone.setDroneData(data);
        helper.getLevel().addFreshEntity(drone);
        return drone;
    }

    private static ItemStack linkedRemote(ServerPlayer player, DroneEntity drone) {
        ItemStack remote = new ItemStack(ModItems.DRONE_REMOTE.get());
        remote.set(ModDataComponents.REMOTE_LINK, RemoteLink.of(drone.snapshotData()));
        player.setItemInHand(InteractionHand.MAIN_HAND, remote);
        return remote;
    }

    private static ServerPlayer spawnPlayer(GameTestHelper helper, UUID uuid, double x, double y, double z, int permissionLevel) {
        MinecraftServer server = server(helper);
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(uuid, "fpv" + uuid.toString().substring(0, 8)), false);
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
        player.moveTo(pos.x, pos.y, pos.z, 0.0F, 0.0F);
        player.setNoGravity(true);
        player.setGameMode(GameType.SURVIVAL);
        // Settle the chunk tracking on the new position, like the server does when a player moves between sections.
        helper.getLevel().getChunkSource().move(player);
        return player;
    }

    private static void removePlayer(GameTestHelper helper, @Nullable ServerPlayer player) {
        if (player != null) {
            server(helper).getPlayerList().remove(player);
        }
    }
}
