package com.elpinho.seekerdrones.gametest;

import static com.elpinho.seekerdrones.gametest.GameTestUtil.runCommand;

import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.drone.DroneConfig;
import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.drone.DroneItem;
import com.elpinho.seekerdrones.drone.DroneStats;
import com.elpinho.seekerdrones.drone.UpgradeType;
import com.elpinho.seekerdrones.programming.ProgrammingStationBlockEntity;
import com.elpinho.seekerdrones.registry.ModBlocks;
import com.elpinho.seekerdrones.registry.ModEntityTypes;
import com.elpinho.seekerdrones.registry.ModItems;

import com.mojang.serialization.DataResult;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Silencer upgrade GameTests (DESIGN.md sections 2.9 and 4). Client-only sound behavior is not covered. */
@GameTestHolder("seekerdrones")
@PrefixGameTestTemplate(false)
public class SilencerGameTests {
    private static final BlockPos DRONE_REL = new BlockPos(4, 3, 4);

    // --- 1. Volume ---

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void silencerVolumeFallsByAQuarterPerUpgradeAndNeverGoesNegative(GameTestHelper helper) {
        float[] expected = {1.0F, 0.75F, 0.5F, 0.25F, 0.0F};
        for (int count = 0; count < expected.length; count++) {
            float actual = DroneStats.silencerVolume(count);
            helper.assertTrue(Math.abs(actual - expected[count]) < 1e-6, count + " Silencers should give " + expected[count] + ", was " + actual);
        }
        for (int count : new int[] {5, 6, 10, 100}) {
            helper.assertTrue(DroneStats.silencerVolume(count) == 0.0F, count + " Silencers should give 0, was " + DroneStats.silencerVolume(count));
        }
        helper.succeed();
    }

    // --- 2. Cap and energy ---

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void silencerHasPerTypeCapOfFourAndEnergyFactorOnePointThree(GameTestHelper helper) {
        helper.assertValueEqual(UpgradeType.SILENCER.maxCount(), 4, "Silencer per-type cap");
        helper.assertTrue(UpgradeType.SILENCER.energyFactor() == 1.3, "Default Silencer energy factor is 1.3, was " + UpgradeType.SILENCER.energyFactor());
        helper.assertTrue(DroneStats.withinUpgradeLimits(silencerData(4).upgrades()), "4 Silencers are within limits");
        helper.assertFalse(DroneStats.withinUpgradeLimits(silencerData(5).upgrades()), "5 Silencers exceed the per-type cap");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void silencerCompoundsIntoEnergyUsageMultiplier(GameTestHelper helper) {
        double two = DroneStats.energyUsageMultiplier(silencerData(2));
        helper.assertTrue(Math.abs(two - 1.3 * 1.3) < 1e-9, "2 Silencers should give 1.3^2, was " + two);
        DroneData sight = DroneData.createNew().withUpgradeCount(UpgradeType.SIGHT, 2);
        double expected = DroneStats.energyUsageMultiplier(sight) * 1.3;
        double actual = DroneStats.energyUsageMultiplier(sight.withUpgradeCount(UpgradeType.SILENCER, 1));
        helper.assertTrue(Math.abs(actual - expected) < 1e-9, "Silencer should multiply another upgrade's factor by 1.3, expected " + expected + ", was " + actual);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void programmingStationInstallsSilencerUntilTheTypeCap(GameTestHelper helper) {
        ProgrammingStationBlockEntity station = placeProgramming(helper);
        station.getItems().setStackInSlot(0, DroneItem.createStack(silencerData(4)));
        station.getItems().setStackInSlot(ProgrammingStationBlockEntity.inputSlot(UpgradeType.SILENCER), new ItemStack(ModItems.upgrade(UpgradeType.SILENCER).get(), 1));
        station.getEnergyStorage().receiveEnergy(500_000, false);
        station.requestInstall(UpgradeType.SILENCER);
        helper.assertTrue(station.getInstalling() == null, "A fifth Silencer should be refused");

        station.getItems().setStackInSlot(0, DroneItem.createStack(silencerData(3)));
        station.requestInstall(UpgradeType.SILENCER);
        helper.assertTrue(station.getInstalling() == UpgradeType.SILENCER, "The fourth Silencer should be accepted");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void upgradeCommandEnforcesTheSilencerCap(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), DRONE_REL);
        runCommand(helper, "seekerdrones upgrade set silencer 5 @e[type=seekerdrones:drone,distance=..3]");
        helper.assertValueEqual(drone.snapshotData().upgradeCount(UpgradeType.SILENCER), 0, "Setting 5 Silencers should be refused");
        runCommand(helper, "seekerdrones upgrade set silencer 4 @e[type=seekerdrones:drone,distance=..3]");
        helper.assertValueEqual(drone.snapshotData().upgradeCount(UpgradeType.SILENCER), 4, "Setting 4 Silencers should work");
        helper.succeed();
    }

    // --- 3. Siren and Silencer coexist ---

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void programmingStationInstallsSilencerOnADroneWithASiren(GameTestHelper helper) {
        ProgrammingStationBlockEntity station = placeProgramming(helper);
        station.getItems().setStackInSlot(0, DroneItem.createStack(DroneData.createNew().withUpgradeCount(UpgradeType.SIREN, 1)));
        station.getItems().setStackInSlot(ProgrammingStationBlockEntity.inputSlot(UpgradeType.SILENCER), new ItemStack(ModItems.upgrade(UpgradeType.SILENCER).get(), 1));
        station.getEnergyStorage().receiveEnergy(500_000, false);
        station.requestInstall(UpgradeType.SILENCER);
        helper.assertTrue(station.getInstalling() == UpgradeType.SILENCER, "A Silencer should install on a drone that has a Siren");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void programmingStationInstallsSirenOnADroneWithASilencer(GameTestHelper helper) {
        ProgrammingStationBlockEntity station = placeProgramming(helper);
        station.getItems().setStackInSlot(0, DroneItem.createStack(silencerData(1)));
        station.getItems().setStackInSlot(ProgrammingStationBlockEntity.inputSlot(UpgradeType.SIREN), new ItemStack(ModItems.upgrade(UpgradeType.SIREN).get(), 1));
        station.getEnergyStorage().receiveEnergy(500_000, false);
        station.requestInstall(UpgradeType.SIREN);
        helper.assertTrue(station.getInstalling() == UpgradeType.SIREN, "A Siren should install on a drone that has a Silencer");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void upgradeCommandAllowsSirenAndSilencerTogether(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), DRONE_REL);
        runCommand(helper, "seekerdrones upgrade set siren 1 @e[type=seekerdrones:drone,distance=..3]");
        runCommand(helper, "seekerdrones upgrade set silencer 1 @e[type=seekerdrones:drone,distance=..3]");
        helper.assertValueEqual(drone.snapshotData().upgradeCount(UpgradeType.SIREN), 1, "Siren count");
        helper.assertValueEqual(drone.snapshotData().upgradeCount(UpgradeType.SILENCER), 1, "Silencer count");
        helper.succeed();
    }

    // --- 4. Nameplate and synced state ---

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void labeledDroneWithASilencerIsSilencedAndHidesItsNameplate(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), DRONE_REL);
        drone.setDroneData(silencerData(1).withConfig(DroneConfig.createDefault().withLabel("Scout")));
        helper.assertTrue(drone.getLabel().equals("Scout"), "Sanity: the label should be set, was '" + drone.getLabel() + "'");
        helper.assertTrue(drone.isSilenced(), "A drone with a Silencer should be silenced");
        helper.assertFalse(drone.shouldShowName(), "A silenced drone should not show its nameplate");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void labeledDroneWithoutASilencerShowsItsNameplate(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), DRONE_REL);
        drone.setDroneData(DroneData.createNew().withConfig(DroneConfig.createDefault().withLabel("Scout")));
        helper.assertFalse(drone.isSilenced(), "A drone without a Silencer is not silenced");
        helper.assertTrue(drone.shouldShowName(), "A labeled drone without a Silencer should show its nameplate");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void replacingDroneDataUpdatesTheSyncedSilencerState(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), DRONE_REL);
        DroneConfig labeled = DroneConfig.createDefault().withLabel("Scout");
        drone.setDroneData(DroneData.createNew().withConfig(labeled));
        helper.assertFalse(drone.isSilenced(), "Starts without a Silencer");
        helper.assertTrue(drone.shouldShowName(), "Starts with a visible nameplate");

        drone.setDroneData(silencerData(2).withConfig(labeled));
        helper.assertTrue(drone.isSilenced(), "Installing a Silencer should silence the drone");
        helper.assertFalse(drone.shouldShowName(), "Installing a Silencer should hide the nameplate");

        drone.setDroneData(DroneData.createNew().withConfig(labeled));
        helper.assertFalse(drone.isSilenced(), "Removing the Silencer should end the silence");
        helper.assertTrue(drone.shouldShowName(), "Removing the Silencer should show the nameplate again");
        helper.succeed();
    }

    // --- 5. Volume multiplier on a drone ---

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void droneVolumeMultiplierMatchesSilencerVolume(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), DRONE_REL);
        helper.assertTrue(drone.getSilencerVolumeMultiplier() == 1.0F, "No Silencer should give 1.0, was " + drone.getSilencerVolumeMultiplier());
        for (int count = 1; count <= 4; count++) {
            drone.setDroneData(silencerData(count));
            float expected = DroneStats.silencerVolume(count);
            helper.assertTrue(drone.getSilencerVolumeMultiplier() == expected,
                    count + " Silencers: multiplier should be " + expected + ", was " + drone.getSilencerVolumeMultiplier());
        }
        helper.assertTrue(drone.getSilencerVolumeMultiplier() == 0.0F, "4 Silencers should be fully silent");
        helper.succeed();
    }

    // --- 6. Programming Station slots ---

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void silencerItemOnlyFitsItsOwnInputSlot(GameTestHelper helper) {
        ProgrammingStationBlockEntity station = placeProgramming(helper);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        var menu = (com.elpinho.seekerdrones.programming.ProgrammingStationMenu) station.createMenu(1, player.getInventory(), player);
        ItemStack silencer = new ItemStack(ModItems.upgrade(UpgradeType.SILENCER).get());
        for (UpgradeType type : UpgradeType.values()) {
            Slot slot = menu.slots.get(ProgrammingStationBlockEntity.inputSlot(type));
            helper.assertTrue(slot.mayPlace(silencer) == (type == UpgradeType.SILENCER),
                    type + " input slot " + (type == UpgradeType.SILENCER ? "should accept" : "must refuse") + " a Silencer");
        }
        helper.succeed();
    }

    // --- 7. Persistence ---

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void silencerCountRoundTripsThroughSavedDroneDataByName(GameTestHelper helper) {
        DroneData data = silencerData(3).withUpgradeCount(UpgradeType.SIREN, 1);
        Tag tag = DroneData.CODEC.encodeStart(NbtOps.INSTANCE, data).getOrThrow();
        helper.assertTrue(tag instanceof CompoundTag, "Saved data should be a compound, was " + tag);
        String saved = tag.toString();
        helper.assertTrue(saved.contains("silencer"), "Saved upgrades should be keyed by name 'silencer', was " + saved);
        DataResult<DroneData> loaded = DroneData.CODEC.parse(NbtOps.INSTANCE, tag);
        helper.assertTrue(loaded.result().isPresent(), "Saved data should load: " + loaded);
        helper.assertValueEqual(loaded.result().get().upgradeCount(UpgradeType.SILENCER), 3, "Loaded Silencer count");
        helper.assertValueEqual(loaded.result().get().upgradeCount(UpgradeType.SIREN), 1, "Loaded Siren count");

        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), DRONE_REL);
        drone.setDroneData(silencerData(2));
        CompoundTag entityTag = new CompoundTag();
        drone.saveWithoutId(entityTag);
        drone.discard();
        DroneEntity copy = ModEntityTypes.DRONE.get().create(helper.getLevel());
        copy.load(entityTag);
        helper.assertValueEqual(copy.snapshotData().upgradeCount(UpgradeType.SILENCER), 2, "Silencer count after an entity save and load");
        helper.assertTrue(copy.isSilenced(), "A reloaded drone should be silenced");
        helper.succeed();
    }

    // --- 8. Config ---

    @GameTest(template = "empty", timeoutTicks = 20, batch = "silencer_config")
    public static void silencerVolumeFollowsTheConfiguredVolumePerUpgrade(GameTestHelper helper) {
        double original = ServerConfig.get(ServerConfig.UPGRADES_SILENCER_VOLUME_PER_UPGRADE);
        try {
            ServerConfig.UPGRADES_SILENCER_VOLUME_PER_UPGRADE.set(0.1);
            helper.assertTrue(Math.abs(DroneStats.silencerVolume(2) - 0.8F) < 1e-5, "At 0.1 per upgrade 2 Silencers should give 0.8, was " + DroneStats.silencerVolume(2));
            helper.assertTrue(Math.abs(DroneStats.silencerVolume(4) - 0.6F) < 1e-5, "At 0.1 per upgrade 4 Silencers should give 0.6, was " + DroneStats.silencerVolume(4));
            helper.assertTrue(DroneStats.silencerVolume(10) == 0.0F, "10 Silencers at 0.1 should give 0, was " + DroneStats.silencerVolume(10));
        } finally {
            ServerConfig.UPGRADES_SILENCER_VOLUME_PER_UPGRADE.set(original);
        }
        helper.succeed();
    }

    // --- 9. Drone volumes ---

    private static double loudness(int total) {
        return Math.min(1.0 + 0.05 * total, 2.0);
    }

    private static void assertVolumes(GameTestHelper helper, DroneEntity drone, int upgrades, int silencers) {
        double factor = loudness(upgrades) * Math.max(0.0, 1.0 - 0.25 * silencers);
        String ctx = " (" + upgrades + " upgrades, " + silencers + " Silencers)";
        helper.assertTrue(Math.abs(drone.deployVolume() - 0.5 * factor) < 1e-5, "deployVolume should be " + 0.5 * factor + ", was " + drone.deployVolume() + ctx);
        helper.assertTrue(Math.abs(drone.lockOnVolume() - 0.3 * factor) < 1e-5, "lockOnVolume should be " + 0.3 * factor + ", was " + drone.lockOnVolume() + ctx);
        helper.assertTrue(Math.abs(drone.targetLostVolume() - 0.3 * factor) < 1e-5, "targetLostVolume should be " + 0.3 * factor + ", was " + drone.targetLostVolume() + ctx);
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void deployLockOnAndTargetLostVolumesFallWithEachSilencer(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), DRONE_REL);
        for (int count = 0; count <= 4; count++) {
            drone.setDroneData(silencerData(count));
            assertVolumes(helper, drone, count, count);
        }
        helper.assertTrue(drone.deployVolume() == 0.0F && drone.lockOnVolume() == 0.0F && drone.targetLostVolume() == 0.0F,
                "4 Silencers should silence all three exactly");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void silencerIsAFractionOfTheTotalVolumeIncludingUpgradeLoudness(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), DRONE_REL);
        drone.setDroneData(DroneData.createNew().withUpgradeCount(UpgradeType.SIGHT, 10).withUpgradeCount(UpgradeType.SILENCER, 2));
        helper.assertTrue(Math.abs(drone.deployVolume() - 0.5 * 1.6 * 0.5) < 1e-5, "deployVolume should be 0.4, was " + drone.deployVolume());
        assertVolumes(helper, drone, 12, 2);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void silencerDoesNotChangeThePlainUpgradeLoudness(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), DRONE_REL);
        drone.setDroneData(DroneData.createNew().withUpgradeCount(UpgradeType.SIGHT, 6));
        float without = drone.getSoundVolumeMultiplier();
        drone.setDroneData(DroneData.createNew().withUpgradeCount(UpgradeType.SIGHT, 4).withUpgradeCount(UpgradeType.SILENCER, 2));
        helper.assertTrue(drone.getSoundVolumeMultiplier() == without,
                "Same total upgrades should give the same loudness, " + without + " vs " + drone.getSoundVolumeMultiplier());
        helper.assertTrue(Math.abs(without - 1.3F) < 1e-5, "6 upgrades should give 1.3, was " + without);
        helper.succeed();
    }

    // --- 10. Explosive approach beeps ---

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void approachBeepIntervalRunsFromFastToSlow(GameTestHelper helper) {
        helper.assertValueEqual(DroneStats.approachBeepInterval(0), 2, "interval at 0");
        helper.assertValueEqual(DroneStats.approachBeepInterval(48), 8, "interval at 48");
        helper.assertValueEqual(DroneStats.approachBeepInterval(100), 8, "interval at 100");
        helper.assertValueEqual(DroneStats.approachBeepInterval(24), 5, "interval at 24");
        int previous = DroneStats.approachBeepInterval(48);
        for (int distance = 47; distance >= 0; distance--) {
            int interval = DroneStats.approachBeepInterval(distance);
            helper.assertTrue(interval <= previous && interval >= 1, "interval at " + distance + " was " + interval + " after " + previous);
            previous = interval;
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void approachBeepVolumeFadesLinearlyAndRangeIsExclusive(GameTestHelper helper) {
        helper.assertTrue(Math.abs(DroneStats.approachBeepVolume(0) - 1.0F) < 1e-5, "volume at 0 was " + DroneStats.approachBeepVolume(0));
        helper.assertTrue(Math.abs(DroneStats.approachBeepVolume(24) - 0.5F) < 1e-5, "volume at 24 was " + DroneStats.approachBeepVolume(24));
        helper.assertTrue(DroneStats.approachBeepVolume(48) == 0.0F, "volume at 48 was " + DroneStats.approachBeepVolume(48));
        helper.assertTrue(DroneStats.approachBeepVolume(60) == 0.0F, "volume at 60 was " + DroneStats.approachBeepVolume(60));
        helper.assertTrue(DroneStats.inApproachBeepRange(0), "in range at 0");
        helper.assertTrue(DroneStats.inApproachBeepRange(47.9), "in range at 47.9");
        helper.assertFalse(DroneStats.inApproachBeepRange(48), "not in range at 48");
        helper.assertFalse(DroneStats.inApproachBeepRange(60), "not in range at 60");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20, batch = "silencer_config")
    public static void approachBeepFollowsTheConfiguredRangeAndIntervals(GameTestHelper helper) {
        double range = ServerConfig.get(ServerConfig.SOUNDS_EXPLOSIVE_APPROACH_RANGE);
        int slow = ServerConfig.get(ServerConfig.SOUNDS_EXPLOSIVE_APPROACH_SLOW_INTERVAL);
        int fast = ServerConfig.get(ServerConfig.SOUNDS_EXPLOSIVE_APPROACH_FAST_INTERVAL);
        double volume = ServerConfig.get(ServerConfig.SOUNDS_EXPLOSIVE_APPROACH_VOLUME);
        try {
            ServerConfig.SOUNDS_EXPLOSIVE_APPROACH_RANGE.set(20.0);
            ServerConfig.SOUNDS_EXPLOSIVE_APPROACH_SLOW_INTERVAL.set(10);
            ServerConfig.SOUNDS_EXPLOSIVE_APPROACH_FAST_INTERVAL.set(4);
            helper.assertValueEqual(DroneStats.approachBeepInterval(10), 7, "interval at 10");
            float expected = (float) (volume * 0.5);
            helper.assertTrue(Math.abs(DroneStats.approachBeepVolume(10) - expected) < 1e-5, "volume at 10 should be " + expected + ", was " + DroneStats.approachBeepVolume(10));
            helper.assertFalse(DroneStats.inApproachBeepRange(20), "not in range at 20");
        } finally {
            ServerConfig.SOUNDS_EXPLOSIVE_APPROACH_RANGE.set(range);
            ServerConfig.SOUNDS_EXPLOSIVE_APPROACH_SLOW_INTERVAL.set(slow);
            ServerConfig.SOUNDS_EXPLOSIVE_APPROACH_FAST_INTERVAL.set(fast);
        }
        helper.succeed();
    }

    // --- Helpers ---

    private static DroneData silencerData(int count) {
        return DroneData.createNew().withUpgradeCount(UpgradeType.SILENCER, count);
    }

    private static ProgrammingStationBlockEntity placeProgramming(GameTestHelper helper) {
        helper.setBlock(DRONE_REL, ModBlocks.PROGRAMMING_STATION.get());
        return (ProgrammingStationBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(DRONE_REL));
    }
}
