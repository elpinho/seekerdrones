package com.elpinho.seekerdrones.gametest;

import java.util.Map;

import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.drone.DroneStats;
import com.elpinho.seekerdrones.drone.UpgradeType;
import com.elpinho.seekerdrones.registry.ModEntityTypes;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** GameTests for the visual drone size (DESIGN.md section 3.7). */
@GameTestHolder("seekerdrones")
@PrefixGameTestTemplate(false)
public class DroneSizeGameTests {

    private static DroneData upgraded(Map<UpgradeType, Integer> upgrades) {
        DroneData d = DroneData.createNew();
        return new DroneData(d.droneId(), d.groupId(), d.ownerId(), d.ownerName(), d.energy(), d.health(),
                upgrades, d.config());
    }

    private static boolean close(double a, double b) {
        return Math.abs(a - b) < 1e-4;
    }

    @GameTest(template = "empty", timeoutTicks = 5)
    public static void droneSizeNoUpgradesIsOne(GameTestHelper helper) {
        float s = DroneStats.visualScale(upgraded(Map.of()));
        helper.assertTrue(close(s, 1.0), "scale=" + s);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 5)
    public static void droneSizeGrowsPerUpgradeAcrossAllTypes(GameTestHelper helper) {
        float s = DroneStats.visualScale(upgraded(Map.of(UpgradeType.SIGHT, 2, UpgradeType.ENERGY, 1, UpgradeType.HEALTH, 1)));
        helper.assertTrue(close(s, 1.2), "4 upgrades expected 1.2, was " + s);
        float one = DroneStats.visualScale(upgraded(Map.of(UpgradeType.SIREN, 1)));
        helper.assertTrue(close(one, 1.05), "1 upgrade expected 1.05, was " + one);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 5)
    public static void droneSizeIsCappedAtDefaultMax(GameTestHelper helper) {
        // 20 upgrades would be 2.0 uncapped; the default cap is 1.5.
        float s = DroneStats.visualScale(upgraded(Map.of(UpgradeType.SIGHT, 10, UpgradeType.ENERGY, 10)));
        helper.assertTrue(close(s, 1.5), "expected cap 1.5, was " + s);
        helper.succeed();
    }

    // Own batches: they change global config values.
    @GameTest(template = "empty", timeoutTicks = 5, batch = "config_drone_size_per_upgrade")
    public static void droneSizeUsesConfiguredSizePerUpgrade(GameTestHelper helper) {
        double original = ServerConfig.get(ServerConfig.DRONE_SIZE_PER_UPGRADE);
        ServerConfig.DRONE_SIZE_PER_UPGRADE.set(0.1);
        try {
            float s = DroneStats.visualScale(upgraded(Map.of(UpgradeType.SIGHT, 3)));
            helper.assertTrue(close(s, 1.3), "expected 1.3, was " + s);
        } finally {
            ServerConfig.DRONE_SIZE_PER_UPGRADE.set(original);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 5, batch = "config_drone_max_visual_scale")
    public static void droneSizeUsesConfiguredCap(GameTestHelper helper) {
        double original = ServerConfig.get(ServerConfig.DRONE_MAX_VISUAL_SCALE);
        ServerConfig.DRONE_MAX_VISUAL_SCALE.set(1.1);
        try {
            float s = DroneStats.visualScale(upgraded(Map.of(UpgradeType.SIGHT, 5)));
            helper.assertTrue(close(s, 1.1), "expected cap 1.1, was " + s);
        } finally {
            ServerConfig.DRONE_MAX_VISUAL_SCALE.set(original);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void droneEntityScaleFollowsSetDroneData(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        helper.assertTrue(close(drone.getVisualScale(), 1.0), "initial scale=" + drone.getVisualScale());

        drone.setDroneData(upgraded(Map.of(UpgradeType.SIGHT, 2, UpgradeType.ENERGY, 2)));
        helper.assertTrue(close(drone.getVisualScale(), 1.2), "after 4 upgrades scale=" + drone.getVisualScale());

        drone.setDroneData(upgraded(Map.of(UpgradeType.SIGHT, 1)));
        helper.assertTrue(close(drone.getVisualScale(), 1.05), "after reducing to 1 upgrade scale=" + drone.getVisualScale());

        drone.setDroneData(upgraded(Map.of()));
        helper.assertTrue(close(drone.getVisualScale(), 1.0), "after clearing scale=" + drone.getVisualScale());
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void droneHitboxNeverChangesWithUpgrades(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        drone.setDroneData(upgraded(Map.of(UpgradeType.SIGHT, 10, UpgradeType.ENERGY, 10)));
        helper.assertTrue(drone.getVisualScale() > 1.4F, "scale should have grown: " + drone.getVisualScale());
        helper.runAfterDelay(3, () -> {
            helper.assertTrue(close(drone.getBbWidth(), 0.75), "width=" + drone.getBbWidth());
            helper.assertTrue(close(drone.getBbHeight(), 0.4), "height=" + drone.getBbHeight());
            helper.succeed();
        });
    }
}
