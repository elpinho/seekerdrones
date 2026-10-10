package com.elpinho.seekerdrones.gametest;

import java.util.Map;
import java.util.Optional;

import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.drone.DroneConfig;
import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.drone.DroneStats;
import com.elpinho.seekerdrones.drone.UpgradeType;
import com.elpinho.seekerdrones.programming.ProgramRules;
import com.elpinho.seekerdrones.registry.ModEntityTypes;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** GameTests for the configurable patrol speed (DESIGN.md sections 3.2 and 3.4). */
@GameTestHolder("seekerdrones")
@PrefixGameTestTemplate(false)
public class PatrolSpeedGameTests {

    private static final String SELECTOR = "@e[type=seekerdrones:drone,distance=..3]";

    private static DroneData patrolData(int count) {
        DroneData d = DroneData.createNew();
        return new DroneData(d.droneId(), d.groupId(), d.ownerId(), d.ownerName(), d.energy(), d.health(),
                Map.of(UpgradeType.PATROL, count), d.config());
    }

    private static boolean close(double a, double b) {
        return Math.abs(a - b) < 1e-9;
    }

    @GameTest(template = "empty", timeoutTicks = 5)
    public static void unconfiguredPatrolSpeedIsMaxForUpgradeCount(GameTestHelper helper) {
        helper.assertTrue(close(DroneStats.patrolSpeed(patrolData(1)), 0.25), "1 Patrol: " + DroneStats.patrolSpeed(patrolData(1)));
        helper.assertTrue(close(DroneStats.patrolSpeed(patrolData(3)), 0.45), "3 Patrol: " + DroneStats.patrolSpeed(patrolData(3)));
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 5)
    public static void configuredPatrolSpeedBelowMaxIsUsedAsIs(GameTestHelper helper) {
        DroneData base = patrolData(3);
        DroneData data = base.withConfig(base.config().withPatrolSpeed(Optional.of(0.1)));
        helper.assertTrue(close(DroneStats.patrolSpeed(data), 0.1), "speed=" + DroneStats.patrolSpeed(data));
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 5)
    public static void configuredPatrolSpeedAboveMaxIsClampedButStored(GameTestHelper helper) {
        DroneData base = patrolData(1);
        DroneData data = base.withConfig(base.config().withPatrolSpeed(Optional.of(0.9)));
        helper.assertTrue(close(DroneStats.patrolSpeed(data), 0.25), "speed=" + DroneStats.patrolSpeed(data));
        helper.assertValueEqual(data.config().patrolSpeed(), Optional.of(0.9), "stored speed");
        // With more upgrades the stored value takes effect (up to its own value).
        DroneData more = patrolData(8).withConfig(data.config());
        helper.assertTrue(close(DroneStats.patrolSpeed(more), 0.9), "speed with 8 upgrades=" + DroneStats.patrolSpeed(more));
        helper.succeed();
    }

    // Own batch: it changes a global config value.
    @GameTest(template = "empty", timeoutTicks = 5, batch = "config_patrol_per_upgrade_speed")
    public static void maxPatrolSpeedNeverExceedsDroneMaxSpeed(GameTestHelper helper) {
        double original = ServerConfig.get(ServerConfig.UPGRADES_PATROL_PER_UPGRADE_SPEED);
        ServerConfig.UPGRADES_PATROL_PER_UPGRADE_SPEED.set(30.0);
        try {
            double maxSpeed = ServerConfig.get(ServerConfig.DRONE_MAX_SPEED);
            helper.assertTrue(close(DroneStats.maxPatrolSpeed(1), 0.25), "count 1: " + DroneStats.maxPatrolSpeed(1));
            helper.assertTrue(close(DroneStats.maxPatrolSpeed(4), maxSpeed),
                    "count 4 should be capped at drone.maxSpeed=" + maxSpeed + ", was " + DroneStats.maxPatrolSpeed(4));
            DroneData data = patrolData(4).withConfig(DroneConfig.createDefault().withPatrolSpeed(Optional.of(1.5)));
            helper.assertTrue(close(DroneStats.patrolSpeed(data), maxSpeed), "patrolSpeed=" + DroneStats.patrolSpeed(data));
        } finally {
            ServerConfig.UPGRADES_PATROL_PER_UPGRADE_SPEED.set(original);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 5)
    public static void patrolSpeedSurvivesCodecAndStreamCodecRoundTrips(GameTestHelper helper) {
        for (Optional<Double> speed : new Optional[] { Optional.of(0.37), Optional.empty() }) {
            @SuppressWarnings("unchecked")
            Optional<Double> expected = (Optional<Double>) speed;
            DroneConfig config = DroneConfig.createDefault().withPatrolSpeed(expected);

            Tag tag = DroneConfig.CODEC.encodeStart(NbtOps.INSTANCE, config).getOrThrow();
            if (expected.isPresent()) {
                helper.assertTrue(tag.toString().contains("patrol_speed"), "NBT should hold patrol_speed: " + tag);
            }
            DroneConfig fromNbt = DroneConfig.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();
            helper.assertValueEqual(fromNbt.patrolSpeed(), expected, "NBT round trip");

            ByteBuf buf = Unpooled.buffer();
            DroneConfig.STREAM_CODEC.encode(buf, config);
            DroneConfig fromStream = DroneConfig.STREAM_CODEC.decode(buf);
            helper.assertValueEqual(fromStream.patrolSpeed(), expected, "stream round trip");
            helper.assertValueEqual(fromStream, config, "whole config stream round trip");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void patrolSpeedSetAndClearCommandsUpdateStoredSpeed(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));

        runCommand(helper, "seekerdrones config patrolspeed set 10 " + SELECTOR);
        helper.runAfterDelay(3, () -> {
            helper.assertValueEqual(drone.snapshotData().config().patrolSpeed(), Optional.of(0.5), "after set");
            runCommand(helper, "seekerdrones config patrolspeed clear " + SELECTOR);
            helper.runAfterDelay(3, () -> {
                helper.assertValueEqual(drone.snapshotData().config().patrolSpeed(), Optional.empty(), "after clear");
                helper.succeed();
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void patrolSpeedSetCommandBelowMinimumIsRejected(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        runCommand(helper, "seekerdrones config patrolspeed set 0 " + SELECTOR);
        helper.runAfterDelay(3, () -> {
            helper.assertValueEqual(drone.snapshotData().config().patrolSpeed(), Optional.empty(), "speed 0 must not be stored");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 5)
    public static void programRulesRejectsNonPositiveAndNanPatrolSpeed(GameTestHelper helper) {
        DroneConfig previous = DroneConfig.createDefault();
        var dim = helper.getLevel().dimension();
        for (double bad : new double[] { 0.0, -0.5, Double.NaN }) {
            DroneConfig requested = previous.withPatrolSpeed(Optional.of(bad));
            helper.assertTrue(ProgramRules.checkConfig(requested, previous, Map.of(), dim).isEmpty(),
                    "patrolSpeed " + bad + " should be rejected");
        }
        var ok = ProgramRules.checkConfig(previous.withPatrolSpeed(Optional.of(0.3)), previous, Map.of(), dim);
        helper.assertTrue(ok.isPresent() && ok.get().patrolSpeed().equals(Optional.of(0.3)), "0.3 should be accepted: " + ok);
        helper.assertTrue(ProgramRules.checkConfig(previous, previous, Map.of(), dim).isPresent(), "empty speed should be accepted");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 140)
    public static void patrollingDroneCoversDistancePerTickCloseToConfiguredSpeed(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        DroneData base = patrolData(1);
        drone.setDroneData(base.withConfig(base.config().withPatrolRadius(Optional.of(4)).withPatrolSpeed(Optional.of(0.1))));

        Vec3[] last = new Vec3[1];
        double[] total = new double[1];
        int[] ticks = new int[1];
        helper.runAfterDelay(40, () -> {
            last[0] = drone.position();
            helper.succeedWhen(() -> {
                total[0] += drone.position().distanceTo(last[0]);
                last[0] = drone.position();
                ticks[0]++;
                helper.assertTrue(ticks[0] >= 60, "sampling");
                double avg = total[0] / ticks[0];
                helper.assertTrue(avg > 0.07 && avg < 0.13, "Average speed should be about 0.1, was " + avg);
            });
        });
    }

    private static void runCommand(GameTestHelper helper, String command) {
        MinecraftServer server = helper.getLevel().getServer();
        CommandSourceStack source = server.createCommandSourceStack()
                .withLevel(helper.getLevel())
                .withPosition(helper.absoluteVec(new Vec3(4, 3, 4)))
                .withPermission(4);
        server.getCommands().performPrefixedCommand(source, command);
    }
}
