package com.elpinho.seekerdrones.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.deploying.DeployingStationBlockEntity;
import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.drone.DroneState;
import com.elpinho.seekerdrones.drone.DroneStats;
import com.elpinho.seekerdrones.drone.LowPowerLevel;
import com.elpinho.seekerdrones.drone.TargetEntry;
import com.elpinho.seekerdrones.drone.UpgradeType;
import com.elpinho.seekerdrones.programming.ProgrammingStationBlockEntity;
import com.elpinho.seekerdrones.registry.ModBlocks;
import com.elpinho.seekerdrones.registry.ModEntityTypes;
import com.elpinho.seekerdrones.registry.ModItems;
import com.elpinho.seekerdrones.registry.ModSounds;
import com.elpinho.seekerdrones.station.ChargingStationBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.PlayLevelSoundEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Server-side parts of the drone sounds feature (DESIGN.md sections 2.9, 4, 5.2, 5.3, 3.5, 7.2, 7.3): the synced
 * low-power level and the one-shot sounds. Sounds are observed through NeoForge's {@link PlayLevelSoundEvent.AtPosition}
 * (fired from {@code ServerLevel.playSeededSound}); each test only looks at mod sounds played near its own structure
 * after it started, so parallel tests don't interfere. Nothing here changes {@link ServerConfig}.
 */
@GameTestHolder("seekerdrones")
@PrefixGameTestTemplate(false)
public class SoundGameTests {

    private record Played(SoundEvent sound, ServerLevel level, Vec3 pos, long gameTime) {
    }

    private static final List<Played> PLAYED = new CopyOnWriteArrayList<>();

    static {
        NeoForge.EVENT_BUS.addListener(SoundGameTests::onSound);
    }

    private static void onSound(PlayLevelSoundEvent.AtPosition event) {
        if (event.getSound() == null || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        SoundEvent sound = event.getSound().value();
        // Other mods can play unregistered SoundEvents; getKey returns null for those, and an exception here would
        // propagate into the caller's tick and crash the server.
        ResourceLocation key = BuiltInRegistries.SOUND_EVENT.getKey(sound);
        if (key == null || !"seekerdrones".equals(key.getNamespace())) {
            return;
        }
        PLAYED.add(new Played(sound, level, event.getPosition(), level.getGameTime()));
    }

    /** The mod sounds played near a test's structure since the test started. */
    private static final class Watch {
        private final GameTestHelper helper;
        private final Vec3 center;
        private final long since;

        Watch(GameTestHelper helper) {
            this.helper = helper;
            this.center = helper.absoluteVec(new Vec3(4.5, 3, 4.5));
            this.since = helper.getLevel().getGameTime();
        }

        /** Game times at which the sound was played. */
        List<Long> times(SoundEvent sound) {
            List<Long> result = new ArrayList<>();
            for (Played played : PLAYED) {
                if (played.sound() == sound && played.level() == helper.getLevel() && played.gameTime() >= since
                        && played.pos().distanceToSqr(center) <= 8.0 * 8.0) {
                    result.add(played.gameTime());
                }
            }
            return result;
        }

        int count(SoundEvent sound) {
            return times(sound).size();
        }
    }

    // --- Low power level (section 2.9, 5.2, 5.3) ---

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void idleDroneWithPlentyOfEnergyHasNoLowPower(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        helper.runAfterDelay(10, () -> {
            helper.assertTrue(drone.getLowPower() == LowPowerLevel.NONE, "Idle drone with full energy should be NONE, was " + drone.getLowPower());
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void droneStartingToReturnIsReturningLevelNotCritical(GameTestHelper helper) {
        BlockPos stationRel = new BlockPos(7, 3, 7);
        ChargingStationBlockEntity station = EnergyGameTests.placeChargingStation(helper, stationRel);
        station.getEnergyStorage().receiveEnergy(Integer.MAX_VALUE, false);
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(1, 3, 1));
        double threshold = DroneStats.returnThreshold(drone.snapshotData(),
                drone.position().distanceTo(ChargingStationBlockEntity.dockPosition(helper.absolutePos(stationRel))));
        DroneData data = drone.snapshotData().withEnergy(Math.max(1, (int) threshold - 2));
        helper.assertTrue(!DroneStats.isCriticalEnergy(data), "Sanity: the return threshold energy must be above the critical level");
        drone.setDroneData(data);
        helper.assertTrue(drone.getLowPower() == LowPowerLevel.NONE, "Before it starts returning the level should be NONE, was " + drone.getLowPower());

        EnergyGameTests.pollUntil(helper, () -> drone.getState() == DroneState.RETURNING, 40, () -> {
            helper.assertTrue(drone.getLowPower() == LowPowerLevel.RETURNING, "A returning drone should be RETURNING, was " + drone.getLowPower());
            helper.succeed();
        }, () -> helper.fail("Drone never started returning, state=" + drone.getState() + " energy=" + drone.getEnergy()));
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void droneWaitingByBusyStationStaysReturningLevelAndDockingClearsIt(GameTestHelper helper) {
        BlockPos stationRel = new BlockPos(4, 3, 6);
        ChargingStationBlockEntity station = EnergyGameTests.placeChargingStation(helper, stationRel);
        station.getEnergyStorage().receiveEnergy(Integer.MAX_VALUE, false);
        BlockPos stationAbs = helper.absolutePos(stationRel);

        DroneEntity holder = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 5));
        holder.setDroneData(holder.snapshotData().withEnergy(DroneStats.maxEnergy(holder.snapshotData()) - 300_000));
        EnergyGameTests.forceReturning(holder, stationAbs);

        EnergyGameTests.pollUntil(helper, () -> holder.getState() == DroneState.CHARGING, 40, () -> {
            helper.assertTrue(holder.getLowPower() == LowPowerLevel.NONE, "A docked drone should be NONE, was " + holder.getLowPower());
            DroneEntity waiter = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 2));
            waiter.setDroneData(waiter.snapshotData().withEnergy(DroneStats.maxEnergy(waiter.snapshotData()) - 500));
            EnergyGameTests.forceReturning(waiter, stationAbs);
            int[] ticksWaiting = {0};
            EnergyGameTests.pollUntil(helper, () -> {
                helper.assertTrue(waiter.getState() == DroneState.RETURNING, "Waiter should be RETURNING behind the busy station, was " + waiter.getState());
                helper.assertTrue(holder.getState() == DroneState.CHARGING, "Sanity: the holder should still be charging, was " + holder.getState());
                helper.assertTrue(waiter.getLowPower() == LowPowerLevel.RETURNING,
                        "A drone waiting by a busy station should be RETURNING, was " + waiter.getLowPower());
                return ++ticksWaiting[0] >= 40;
            }, 60, helper::succeed, () -> helper.fail("Polling ran out"));
        }, () -> helper.fail("Holder never docked, state=" + holder.getState()));
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void dockingClearsLowPowerLevel(GameTestHelper helper) {
        BlockPos stationRel = new BlockPos(4, 3, 7);
        ChargingStationBlockEntity station = EnergyGameTests.placeChargingStation(helper, stationRel);
        station.getEnergyStorage().receiveEnergy(Integer.MAX_VALUE, false);
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        // Critical energy too: docking must clear even that.
        drone.setDroneData(drone.snapshotData().withEnergy(2000));
        EnergyGameTests.forceReturning(drone, helper.absolutePos(stationRel));
        helper.assertTrue(drone.getLowPower() == LowPowerLevel.CRITICAL, "Sanity: starts critical, was " + drone.getLowPower());

        EnergyGameTests.pollUntil(helper, () -> drone.getState() == DroneState.CHARGING, 60, () -> {
            helper.assertTrue(drone.getLowPower() == LowPowerLevel.NONE, "A docked drone should be NONE, was " + drone.getLowPower());
            helper.succeed();
        }, () -> helper.fail("Drone never docked, state=" + drone.getState()));
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void returningDroneBelowCriticalThresholdIsCritical(GameTestHelper helper) {
        BlockPos stationRel = new BlockPos(4, 3, 7);
        EnergyGameTests.placeChargingStation(helper, stationRel);
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(1, 3, 1));
        drone.setDroneData(drone.snapshotData().withEnergy(2000));
        EnergyGameTests.forceReturning(drone, helper.absolutePos(stationRel));
        helper.assertTrue(drone.getState() == DroneState.RETURNING, "Sanity: should be RETURNING, was " + drone.getState());
        helper.assertTrue(DroneStats.isCriticalEnergy(drone.snapshotData()), "Sanity: 2000 FE should be below the critical threshold");
        helper.assertTrue(drone.getLowPower() == LowPowerLevel.CRITICAL, "A returning drone with little energy should be CRITICAL, was " + drone.getLowPower());
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void idleDroneWithNoStationAndLittleEnergyIsCritical(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        // Owned by someone random, so no placer-less station left by a neighbouring test copy is usable.
        drone.setDroneData(drone.snapshotData().withEnergy(2000).withOwnerId(Optional.of(UUID.randomUUID())));
        helper.assertTrue(drone.getState() == DroneState.IDLE, "Sanity: should be IDLE, was " + drone.getState());
        helper.assertTrue(drone.getLowPower() == LowPowerLevel.CRITICAL, "An idle drone with little energy and no station should be CRITICAL, was " + drone.getLowPower());
        helper.runAfterDelay(10, () -> {
            helper.assertTrue(drone.getLowPower() == LowPowerLevel.CRITICAL, "It should stay CRITICAL, was " + drone.getLowPower());
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void patrollingDroneWithLittleEnergyIsCritical(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        DroneData base = EnergyGameTests.dataWithUpgrades(DroneData.createNew(), Map.of(UpgradeType.PATROL, 1));
        drone.setDroneData(base.withConfig(base.config().withPatrolRadius(Optional.of(4))).withEnergy(2000)
                .withOwnerId(Optional.of(UUID.randomUUID()))); // no usable station from neighbouring test copies
        helper.runAfterDelay(10, () -> {
            helper.assertTrue(drone.getState() == DroneState.PATROLLING, "Sanity: should be PATROLLING, was " + drone.getState());
            helper.assertTrue(drone.getLowPower() == LowPowerLevel.CRITICAL, "A patrolling drone with little energy should be CRITICAL, was " + drone.getLowPower());
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void criticalBoundaryFollowsHoverCostTimesCriticalSeconds(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        // Owned by someone random, so no placer-less station left by a neighbouring test copy is usable.
        DroneData data = drone.snapshotData().withOwnerId(Optional.of(UUID.randomUUID()));
        int boundary = (int) (ServerConfig.get(ServerConfig.SOUNDS_LOW_POWER_CRITICAL_SECONDS) * 20
                * ServerConfig.get(ServerConfig.DRONE_HOVER_ENERGY_PER_TICK) * DroneStats.energyUsageMultiplier(data));
        drone.setDroneData(data.withEnergy(boundary + 1));
        helper.assertTrue(drone.getLowPower() == LowPowerLevel.NONE, "Just above the boundary (" + boundary + ") should be NONE, was " + drone.getLowPower());
        drone.setDroneData(data.withEnergy(boundary));
        helper.assertTrue(drone.getLowPower() == LowPowerLevel.CRITICAL, "At the boundary (" + boundary + ") should be CRITICAL, was " + drone.getLowPower());
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void batchedEnergyDrainRaisesLevelToCritical(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        // Owned by someone random, so no placer-less station left by a neighbouring test copy is usable.
        DroneData data = drone.snapshotData().withOwnerId(Optional.of(UUID.randomUUID()));
        int boundary = (int) (ServerConfig.get(ServerConfig.SOUNDS_LOW_POWER_CRITICAL_SECONDS) * 20
                * ServerConfig.get(ServerConfig.DRONE_HOVER_ENERGY_PER_TICK) * DroneStats.energyUsageMultiplier(data));
        // Just above the boundary, so one batched drain interval (interval x hover FE) crosses it.
        drone.setDroneData(data.withEnergy(boundary + 20));
        helper.assertTrue(drone.getLowPower() == LowPowerLevel.NONE, "Sanity: just above the boundary should be NONE, was " + drone.getLowPower());
        EnergyGameTests.pollUntil(helper, () -> drone.getLowPower() == LowPowerLevel.CRITICAL, 80, helper::succeed,
                () -> helper.fail("The batched drain never raised the level to CRITICAL, energy=" + drone.getEnergy() + " boundary=" + boundary
                        + " level=" + drone.getLowPower()));
    }

    // --- Siren (section 4) ---

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void sirenRepeatIntervalDefaultIs140(GameTestHelper helper) {
        helper.assertTrue(ServerConfig.get(ServerConfig.UPGRADES_SIREN_REPEAT_INTERVAL) == 140,
                "Default siren repeat interval should be 140, was " + ServerConfig.get(ServerConfig.UPGRADES_SIREN_REPEAT_INTERVAL));
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void sirenDroneReplaysSirenAfterRepeatInterval(GameTestHelper helper) {
        Watch watch = new Watch(helper);
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        spawnStationaryZombie(helper, 4, 1, 7);
        drone.setDroneData(EnergyGameTests.dataWithUpgrades(zombieTargeting(), Map.of(UpgradeType.SIREN, 1)));
        int interval = ServerConfig.get(ServerConfig.UPGRADES_SIREN_REPEAT_INTERVAL);

        helper.succeedWhen(() -> {
            List<Long> times = watch.times(ModSounds.DRONE_SIREN.get());
            helper.assertTrue(times.size() >= 2, "Expected the siren to play at least twice, played " + times.size() + " time(s)");
            long gap = times.get(1) - times.get(0);
            helper.assertTrue(Math.abs(gap - interval) <= 1, "Siren should replay " + interval + " ticks after the first play, gap was " + gap);
        });
    }

    // --- One-shots (section 2.9) ---

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void handDeployPlaysDeploySound(GameTestHelper helper) {
        Watch watch = new Watch(helper);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        Vec3 pos = helper.absoluteVec(new Vec3(4, 1, 4));
        player.moveTo(pos.x, pos.y, pos.z, 0.0F, 0.0F);
        player.setShiftKeyDown(true);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.DRONE.get()));
        ModItems.DRONE.get().use(helper.getLevel(), player, InteractionHand.MAIN_HAND);

        helper.assertTrue(watch.count(ModSounds.DRONE_DEPLOY.get()) == 1, "Hand-deploy should play the deploy sound once, played " + watch.count(ModSounds.DRONE_DEPLOY.get()));
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void deployingStationLaunchPlaysDeployAndLaunchSounds(GameTestHelper helper) {
        Watch watch = new Watch(helper);
        BlockPos rel = new BlockPos(4, 3, 4);
        helper.setBlock(rel, ModBlocks.DEPLOYING_STATION.get());
        DeployingStationBlockEntity station = (DeployingStationBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(rel));
        station.getEnergyStorage().receiveEnergy(ServerConfig.get(ServerConfig.DEPLOYING_STATION_ENERGY_PER_DEPLOY) * 2, false);
        station.getItems().setStackInSlot(0, com.elpinho.seekerdrones.drone.DroneItem.createStack(DroneData.createNew().withDroneId("SND0-0001")));

        helper.succeedWhen(() -> {
            helper.assertTrue(watch.count(ModSounds.DEPLOYING_STATION_LAUNCH.get()) == 1,
                    "Expected one launch sound, got " + watch.count(ModSounds.DEPLOYING_STATION_LAUNCH.get()));
            helper.assertTrue(watch.count(ModSounds.DRONE_DEPLOY.get()) == 1,
                    "Expected one deploy sound, got " + watch.count(ModSounds.DRONE_DEPLOY.get()));
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void dockingPlaysDockSoundOnce(GameTestHelper helper) {
        Watch watch = new Watch(helper);
        BlockPos stationRel = new BlockPos(4, 3, 7);
        ChargingStationBlockEntity station = EnergyGameTests.placeChargingStation(helper, stationRel);
        station.getEnergyStorage().receiveEnergy(Integer.MAX_VALUE, false);
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        drone.setDroneData(drone.snapshotData().withEnergy(DroneStats.maxEnergy(drone.snapshotData()) - 300_000));
        EnergyGameTests.forceReturning(drone, helper.absolutePos(stationRel));
        helper.assertTrue(watch.count(ModSounds.CHARGING_STATION_DOCK.get()) == 0, "No dock sound before docking");

        EnergyGameTests.pollUntil(helper, () -> drone.getState() == DroneState.CHARGING, 60, () ->
                helper.runAfterDelay(10, () -> {
                    helper.assertTrue(watch.count(ModSounds.CHARGING_STATION_DOCK.get()) == 1,
                            "Docking should play the dock sound once, played " + watch.count(ModSounds.CHARGING_STATION_DOCK.get()));
                    helper.assertTrue(watch.count(ModSounds.DRONE_CHARGED.get()) == 0, "The charged chirp must not play while still charging");
                    helper.succeed();
                }), () -> helper.fail("Drone never docked, state=" + drone.getState()));
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void finishingChargingPlaysChargedSound(GameTestHelper helper) {
        Watch watch = new Watch(helper);
        BlockPos stationRel = new BlockPos(4, 3, 7);
        ChargingStationBlockEntity station = EnergyGameTests.placeChargingStation(helper, stationRel);
        station.getEnergyStorage().receiveEnergy(Integer.MAX_VALUE, false);
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        drone.setDroneData(drone.snapshotData().withEnergy(DroneStats.maxEnergy(drone.snapshotData()) - 40_000));
        EnergyGameTests.forceReturning(drone, helper.absolutePos(stationRel));

        helper.succeedWhen(() -> {
            helper.assertTrue(drone.getState() != DroneState.CHARGING && drone.getState() != DroneState.RETURNING && drone.getChargingStation() == null,
                    "Drone hasn't finished charging yet, state=" + drone.getState());
            helper.assertTrue(watch.count(ModSounds.DRONE_CHARGED.get()) == 1,
                    "Finishing charging should play the charged chirp once, played " + watch.count(ModSounds.DRONE_CHARGED.get()));
        });
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void spottingATargetPlaysLockOnSoundOnce(GameTestHelper helper) {
        Watch watch = new Watch(helper);
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        Zombie zombie = spawnStationaryZombie(helper, 4, 1, 7);
        drone.setDroneData(zombieTargeting());

        EnergyGameTests.pollUntil(helper, () -> drone.getSeekTarget() == zombie, 30, () -> helper.runAfterDelay(10, () -> {
            helper.assertTrue(watch.count(ModSounds.DRONE_LOCK_ON.get()) == 1,
                    "Acquiring a target should play the lock-on beep once, played " + watch.count(ModSounds.DRONE_LOCK_ON.get()));
            helper.assertTrue(watch.count(ModSounds.DRONE_TARGET_LOST.get()) == 0, "No target-lost beep while still holding the target");
            helper.succeed();
        }), () -> helper.fail("Drone never spotted the zombie"));
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void losingTheTargetPlaysTargetLostSound(GameTestHelper helper) {
        Watch watch = new Watch(helper);
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        Zombie zombie = spawnStationaryZombie(helper, 4, 1, 7);
        drone.setDroneData(zombieTargeting());

        EnergyGameTests.pollUntil(helper, () -> drone.getSeekTarget() == zombie, 30, () -> {
            zombie.discard();
            EnergyGameTests.pollUntil(helper, () -> drone.getSeekTarget() == null, 20, () -> {
                helper.assertTrue(watch.count(ModSounds.DRONE_TARGET_LOST.get()) == 1,
                        "Losing the target should play the target-lost beep once, played " + watch.count(ModSounds.DRONE_TARGET_LOST.get()));
                helper.succeed();
            }, () -> helper.fail("Drone never dropped the removed target"));
        }, () -> helper.fail("Drone never spotted the zombie"));
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void explosiveDroneExplodingPlaysNoTargetLostSound(GameTestHelper helper) {
        Watch watch = new Watch(helper);
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        spawnStationaryZombie(helper, 4, 1, 7);
        drone.setDroneData(EnergyGameTests.dataWithUpgrades(zombieTargeting(), Map.of(UpgradeType.EXPLOSIVE, 1)));

        helper.succeedWhen(() -> {
            helper.assertTrue(drone.isRemoved(), "Drone hasn't exploded yet");
            helper.assertTrue(watch.count(ModSounds.DRONE_LOCK_ON.get()) >= 1, "Sanity: it should have locked on first");
            helper.assertTrue(watch.count(ModSounds.DRONE_TARGET_LOST.get()) == 0,
                    "An exploding drone should not play the target-lost beep, played " + watch.count(ModSounds.DRONE_TARGET_LOST.get()));
        });
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void destroyedDronePlaysDestroySound(GameTestHelper helper) {
        Watch watch = new Watch(helper);
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        drone.hurt(helper.getLevel().damageSources().generic(), 1000.0F);
        helper.runAfterDelay(5, () -> {
            helper.assertTrue(drone.isRemoved(), "Drone should be removed after lethal damage");
            helper.assertTrue(watch.count(ModSounds.DRONE_DESTROY.get()) == 1,
                    "Destruction should play the destroy sound once, played " + watch.count(ModSounds.DRONE_DESTROY.get()));
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void programmingStationPlaysInstallSoundPerInstalledUpgrade(GameTestHelper helper) {
        Watch watch = new Watch(helper);
        BlockPos rel = new BlockPos(4, 3, 4);
        helper.setBlock(rel, ModBlocks.PROGRAMMING_STATION.get());
        ProgrammingStationBlockEntity station = (ProgrammingStationBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(rel));
        station.getItems().setStackInSlot(0, com.elpinho.seekerdrones.drone.DroneItem.createStack(DroneData.createNew()));
        station.getItems().setStackInSlot(ProgrammingStationBlockEntity.inputSlot(UpgradeType.SIGHT), new ItemStack(ModItems.upgrade(UpgradeType.SIGHT).get(), 1));
        station.getEnergyStorage().receiveEnergy(100_000, false);
        station.requestInstall(UpgradeType.SIGHT);

        helper.succeedWhen(() -> {
            helper.assertTrue(station.getDrone().orElseThrow().upgradeCount(UpgradeType.SIGHT) == 1, "Upgrade not installed yet");
            helper.assertTrue(watch.count(ModSounds.PROGRAMMING_STATION_INSTALL.get()) == 1,
                    "One install should play one click, played " + watch.count(ModSounds.PROGRAMMING_STATION_INSTALL.get()));
        });
    }

    // --- Helpers ---

    private static DroneData zombieTargeting() {
        DroneData base = DroneData.createNew();
        return base.withConfig(base.config().withTargets(List.of(new TargetEntry(TargetEntry.Kind.ENTITY_TYPE, "minecraft:zombie"))));
    }

    private static Zombie spawnStationaryZombie(GameTestHelper helper, int x, int y, int z) {
        Zombie zombie = helper.spawn(EntityType.ZOMBIE, new BlockPos(x, y, z));
        zombie.setNoAi(true);
        zombie.setNoGravity(true);
        zombie.setInvulnerable(true);
        return zombie;
    }
}
