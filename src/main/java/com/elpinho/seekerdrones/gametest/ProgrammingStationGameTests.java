package com.elpinho.seekerdrones.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.drone.DroneConfig;
import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.DroneItem;
import com.elpinho.seekerdrones.drone.DroneStats;
import com.elpinho.seekerdrones.drone.TargetEntry;
import com.elpinho.seekerdrones.drone.UpgradeType;
import com.elpinho.seekerdrones.programming.ProgramRules;
import com.elpinho.seekerdrones.programming.ProgrammingMode;
import com.elpinho.seekerdrones.programming.ProgrammingStationBlockEntity;
import com.elpinho.seekerdrones.programming.ProgrammingStationSettings;
import com.elpinho.seekerdrones.registry.ModBlocks;
import com.elpinho.seekerdrones.registry.ModDataComponents;
import com.elpinho.seekerdrones.registry.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * M7 (Drone Programming Station) GameTests, DESIGN.md sections 4, 7.2 and 9. They drive the block entity directly
 * (and the automation {@link IItemHandler}); no GUI. All tests use config defaults (install time 20 ticks, base cost
 * 10 000 FE, station capacity 200 000 FE) and the {@code seekerdrones:empty} structure template.
 */
@GameTestHolder("seekerdrones")
@PrefixGameTestTemplate(false)
public class ProgrammingStationGameTests {
    private static final BlockPos REL = new BlockPos(4, 3, 4);

    // --- 1. Install step cost (sections 4, 7.2) ---

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void directInstallSecondSightCostsTwiceBaseCostSpentOverInstallTime(GameTestHelper helper) {
        ProgrammingStationBlockEntity station = place(helper);
        station.getItems().setStackInSlot(0, droneStack(DroneData.createNew().withUpgradeCount(UpgradeType.SIGHT, 1)));
        station.getItems().setStackInSlot(1, new ItemStack(ModItems.upgrade(UpgradeType.SIGHT).get(), 1));
        station.getEnergyStorage().receiveEnergy(50_000, false);
        int cost = 2 * UpgradeType.SIGHT.baseCost();
        helper.assertTrue(cost == ProgramRules.installCost(UpgradeType.SIGHT, 2), "installCost(SIGHT, 2) should be baseCost x 2");

        station.requestInstall(UpgradeType.SIGHT);
        helper.assertTrue(station.getInstalling() == UpgradeType.SIGHT, "requestInstall should start a step");

        helper.runAfterDelay(8, () -> {
            helper.assertTrue(station.getDrone().orElseThrow().upgradeCount(UpgradeType.SIGHT) == 1, "Upgrade shouldn't be installed halfway");
            int stored = station.getEnergyStorage().getEnergyStored();
            helper.assertTrue(stored < 50_000 && stored > 50_000 - cost, "FE should be partly spent halfway, stored=" + stored);
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(station.getDrone().orElseThrow().upgradeCount(UpgradeType.SIGHT) == 2,
                    "Second Sight upgrade not installed yet");
            helper.assertTrue(station.getEnergyStorage().getEnergyStored() == 50_000 - cost,
                    "Station should have spent exactly " + cost + " FE, stored=" + station.getEnergyStorage().getEnergyStored());
            helper.assertTrue(station.getInstalling() == null, "Step should be finished");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void energyUpgradeCostsBaseCostTimesIndexAndEnergyArrivesEmpty(GameTestHelper helper) {
        ProgrammingStationBlockEntity station = place(helper);
        DroneData before = DroneData.createNew();
        int baseMax = DroneStats.maxEnergy(before);
        station.getItems().setStackInSlot(0, droneStack(before));
        station.getItems().setStackInSlot(1, new ItemStack(ModItems.upgrade(UpgradeType.ENERGY).get(), 1));
        int cost = UpgradeType.ENERGY.baseCost();
        helper.assertTrue(ProgramRules.installCost(UpgradeType.ENERGY, 1) == cost, "installCost(ENERGY, 1) should be baseCost x 1");
        helper.assertTrue(ProgramRules.installCost(UpgradeType.ENERGY, 3) == 3 * cost, "installCost(ENERGY, 3) should be baseCost x 3, with no capacity added");
        station.getEnergyStorage().receiveEnergy(cost + 5_000, false);

        station.requestInstall(UpgradeType.ENERGY);
        helper.succeedWhen(() -> {
            DroneData after = station.getDrone().orElseThrow();
            helper.assertTrue(after.upgradeCount(UpgradeType.ENERGY) == 1, "Energy upgrade not installed yet");
            helper.assertTrue(station.getEnergyStorage().getEnergyStored() == 5_000,
                    "Energy install should cost baseCost x index = " + cost + ", stored=" + station.getEnergyStorage().getEnergyStored());
            helper.assertTrue(DroneStats.maxEnergy(after) == 2 * baseMax, "Max energy should double, was " + DroneStats.maxEnergy(after));
            helper.assertTrue(after.energy() == before.energy(),
                    "Drone energy should be unchanged (the extra capacity arrives empty), was " + after.energy() + " expected " + before.energy());
        });
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void secondEnergyUpgradeCostsTwiceBaseCost(GameTestHelper helper) {
        ProgrammingStationBlockEntity station = place(helper);
        station.getItems().setStackInSlot(0, droneStack(DroneData.createNew().withUpgradeCount(UpgradeType.ENERGY, 1)));
        station.getItems().setStackInSlot(1, new ItemStack(ModItems.upgrade(UpgradeType.ENERGY).get(), 1));
        int cost = 2 * UpgradeType.ENERGY.baseCost();
        station.getEnergyStorage().receiveEnergy(cost + 3_000, false);

        station.requestInstall(UpgradeType.ENERGY);
        helper.succeedWhen(() -> {
            helper.assertTrue(station.getDrone().orElseThrow().upgradeCount(UpgradeType.ENERGY) == 2, "Second Energy upgrade not installed yet");
            helper.assertTrue(station.getEnergyStorage().getEnergyStored() == 3_000,
                    "Second Energy install should cost 2 x baseCost = " + cost + ", stored=" + station.getEnergyStorage().getEnergyStored());
        });
    }

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void removingEnergyUpgradeClampsDroneEnergyToNewMax(GameTestHelper helper) {
        ProgrammingStationBlockEntity station = place(helper);
        DroneData withUpgrade = DroneData.createNew().withUpgradeCount(UpgradeType.ENERGY, 1);
        DroneData full = withUpgrade.withEnergy(DroneStats.maxEnergy(withUpgrade));
        station.getItems().setStackInSlot(0, droneStack(full));
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);

        station.removeUpgrade(UpgradeType.ENERGY, player);
        DroneData after = station.getDrone().orElseThrow();
        helper.assertTrue(after.upgradeCount(UpgradeType.ENERGY) == 0, "Energy upgrade should be removed");
        helper.assertTrue(after.energy() == DroneStats.maxEnergy(after),
                "Energy should be clamped to the new max " + DroneStats.maxEnergy(after) + ", was " + after.energy());
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void healthUpgradeArrivesFull(GameTestHelper helper) {
        ProgrammingStationBlockEntity station = place(helper);
        DroneData damaged = DroneData.createNew().withHealth(5f);
        station.getItems().setStackInSlot(0, droneStack(damaged));
        station.getItems().setStackInSlot(1, new ItemStack(ModItems.upgrade(UpgradeType.HEALTH).get(), 1));
        station.getEnergyStorage().receiveEnergy(UpgradeType.HEALTH.baseCost(), false);

        station.requestInstall(UpgradeType.HEALTH);
        helper.succeedWhen(() -> {
            DroneData after = station.getDrone().orElseThrow();
            helper.assertTrue(after.upgradeCount(UpgradeType.HEALTH) == 1, "Health upgrade not installed yet");
            float extra = DroneStats.maxHealth(after) - DroneStats.maxHealth(damaged);
            helper.assertTrue(Math.abs(after.health() - (5f + extra)) < 0.001f,
                    "Health should rise by the extra max (" + extra + "), was " + after.health());
        });
    }

    // --- 2. Pause on low FE ---

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void installStepPausesWithoutEnoughEnergyAndContinuesWhenAdded(GameTestHelper helper) {
        ProgrammingStationBlockEntity station = place(helper);
        station.getItems().setStackInSlot(0, droneStack(DroneData.createNew()));
        station.getItems().setStackInSlot(1, new ItemStack(ModItems.upgrade(UpgradeType.PATROL).get(), 1));
        int cost = UpgradeType.PATROL.baseCost();
        int perTick = cost / 20;
        // Enough for 4 ticks of progress and a bit less than one more tick.
        int initial = perTick * 4 + perTick / 2;
        station.getEnergyStorage().receiveEnergy(initial, false);

        station.requestInstall(UpgradeType.PATROL);
        helper.runAfterDelay(15, () -> {
            helper.assertTrue(station.getInstalling() == UpgradeType.PATROL, "Step should still be waiting for FE");
            helper.assertTrue(station.getDrone().orElseThrow().upgradeCount(UpgradeType.PATROL) == 0, "Upgrade shouldn't install without FE");
            int stored = station.getEnergyStorage().getEnergyStored();
            helper.assertTrue(stored == initial - perTick * 4, "Paused step should have spent exactly 4 ticks of FE, stored=" + stored);
            helper.runAfterDelay(5, () -> {
                helper.assertTrue(station.getEnergyStorage().getEnergyStored() == stored, "Paused step must not spend FE");
                station.getEnergyStorage().receiveEnergy(cost, false);
            });
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(helper.getTick() > 20, "Too early to be resumed");
            helper.assertTrue(station.getDrone().orElseThrow().upgradeCount(UpgradeType.PATROL) == 1, "Step should finish after FE is added");
            helper.assertTrue(station.getEnergyStorage().getEnergyStored() == initial + cost - cost,
                    "Total spent should equal the step cost, stored=" + station.getEnergyStorage().getEnergyStored());
        });
    }

    // --- 3. Direct mode requestInstall ---

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void directRequestInstallDoesNothingWithoutUpgradeInInput(GameTestHelper helper) {
        ProgrammingStationBlockEntity station = place(helper);
        station.getItems().setStackInSlot(0, droneStack(DroneData.createNew()));
        // A different type is in the input: still nothing for Patrol.
        station.getItems().setStackInSlot(1, new ItemStack(ModItems.upgrade(UpgradeType.SIGHT).get(), 1));
        station.getEnergyStorage().receiveEnergy(100_000, false);

        station.requestInstall(UpgradeType.PATROL);
        helper.assertTrue(station.getInstalling() == null, "No step should start without a matching upgrade in the input");
        helper.runAfterDelay(25, () -> {
            helper.assertTrue(station.getDrone().orElseThrow().upgrades().isEmpty(), "Drone should have no upgrades");
            helper.assertTrue(station.getEnergyStorage().getEnergyStored() == 100_000, "No FE should be spent");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void directRequestInstallInstallsAndTakesOneItem(GameTestHelper helper) {
        ProgrammingStationBlockEntity station = place(helper);
        station.getItems().setStackInSlot(0, droneStack(DroneData.createNew()));
        station.getItems().setStackInSlot(3, new ItemStack(ModItems.upgrade(UpgradeType.PATROL).get(), 3));
        station.getEnergyStorage().receiveEnergy(100_000, false);

        station.requestInstall(UpgradeType.PATROL);
        helper.assertTrue(station.getInstalling() == UpgradeType.PATROL, "A step should start");
        helper.runAfterDelay(10, () -> helper.assertTrue(station.getItems().getStackInSlot(3).getCount() == 3,
                "The item is only taken when the step finishes"));
        helper.succeedWhen(() -> {
            helper.assertTrue(station.getDrone().orElseThrow().upgradeCount(UpgradeType.PATROL) == 1, "Patrol not installed yet");
            helper.assertTrue(station.getItems().getStackInSlot(3).getCount() == 2,
                    "Exactly one item should be taken, stack is " + station.getItems().getStackInSlot(3));
        });
    }

    // --- 4. Caps ---

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void directInstallRefusesPastPerTypeCap(GameTestHelper helper) {
        ProgrammingStationBlockEntity station = place(helper);
        int cap = UpgradeType.SIGHT.maxCount();
        station.getItems().setStackInSlot(0, droneStack(DroneData.createNew().withUpgradeCount(UpgradeType.SIGHT, cap)));
        station.getItems().setStackInSlot(1, new ItemStack(ModItems.upgrade(UpgradeType.SIGHT).get(), 1));
        station.getEnergyStorage().receiveEnergy(200_000, false);

        station.requestInstall(UpgradeType.SIGHT);
        helper.assertTrue(station.getInstalling() == null, "Install beyond the per-type cap (" + cap + ") should be refused");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void directInstallRefusesPastTotalSlotLimit(GameTestHelper helper) {
        ProgrammingStationBlockEntity station = place(helper);
        DroneData full = fillToTotalLimit(DroneData.createNew());
        helper.assertTrue(DroneStats.totalUpgrades(full.upgrades()) == ServerConfig.get(ServerConfig.UPGRADES_TOTAL_SLOTS),
                "Fixture should fill the total slot limit, total=" + DroneStats.totalUpgrades(full.upgrades()));
        station.getItems().setStackInSlot(0, droneStack(full));
        station.getItems().setStackInSlot(1, new ItemStack(ModItems.upgrade(UpgradeType.PLAYER_SEEK).get(), 1));
        station.getEnergyStorage().receiveEnergy(200_000, false);

        station.requestInstall(UpgradeType.PLAYER_SEEK);
        helper.assertTrue(station.getInstalling() == null, "Install beyond the total slot limit should be refused");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void setProgramCountRefusesPastPerTypeCap(GameTestHelper helper) {
        ProgrammingStationBlockEntity station = place(helper);
        station.setMode(ProgrammingMode.TEMPLATE);
        int cap = UpgradeType.TRANSMITTER.maxCount();
        station.setProgramCount(UpgradeType.TRANSMITTER, cap);
        helper.assertTrue(station.getTemplate().upgradeCount(UpgradeType.TRANSMITTER) == cap, "Setting the count to the cap should work");
        station.setProgramCount(UpgradeType.TRANSMITTER, cap + 1);
        helper.assertTrue(station.getTemplate().upgradeCount(UpgradeType.TRANSMITTER) == cap, "Setting the count above the cap should be refused");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void setProgramCountRefusesPastTotalSlotLimit(GameTestHelper helper) {
        ProgrammingStationBlockEntity station = place(helper);
        station.setMode(ProgrammingMode.TEMPLATE);
        for (UpgradeType type : UpgradeType.values()) {
            int room = ServerConfig.get(ServerConfig.UPGRADES_TOTAL_SLOTS) - totalOf(station);
            station.setProgramCount(type, Math.min(type.maxCount(), room));
        }
        int total = totalOf(station);
        helper.assertTrue(total == ServerConfig.get(ServerConfig.UPGRADES_TOTAL_SLOTS), "Fixture should fill the total limit, total=" + total);
        // Something below its cap must be refused now.
        UpgradeType open = null;
        for (UpgradeType type : UpgradeType.values()) {
            if (station.getTemplate().upgradeCount(type) < type.maxCount()) {
                open = type;
                break;
            }
        }
        helper.assertTrue(open != null, "Fixture needs a type below its cap");
        station.setProgramCount(open, station.getTemplate().upgradeCount(open) + 1);
        helper.assertTrue(totalOf(station) == total, "Program must not exceed the total slot limit, total=" + totalOf(station));
        helper.succeed();
    }

    // --- 5. Manual removal ---

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void removeUpgradeGivesItemToPlayerInventory(GameTestHelper helper) {
        ProgrammingStationBlockEntity station = place(helper);
        station.getItems().setStackInSlot(0, droneStack(DroneData.createNew().withUpgradeCount(UpgradeType.SIGHT, 2)));
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);

        station.removeUpgrade(UpgradeType.SIGHT, player);
        helper.assertTrue(station.getDrone().orElseThrow().upgradeCount(UpgradeType.SIGHT) == 1, "Count should drop to 1");
        helper.assertTrue(player.getInventory().countItem(ModItems.upgrade(UpgradeType.SIGHT).get()) == 1,
                "Player should get one Sight upgrade item");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void removeUpgradeDropsAtPlayersFeetWhenInventoryFull(GameTestHelper helper) {
        ProgrammingStationBlockEntity station = place(helper);
        station.getItems().setStackInSlot(0, droneStack(DroneData.createNew().withUpgradeCount(UpgradeType.SIGHT, 1)));
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        Vec3 feet = helper.absoluteVec(new Vec3(8.5, 3, 8.5));
        player.setPos(feet);
        for (int i = 0; i < 36; i++) {
            player.getInventory().setItem(i, new ItemStack(Items.DIRT, 64));
        }

        station.removeUpgrade(UpgradeType.SIGHT, player);
        helper.assertTrue(station.getDrone().orElseThrow().upgradeCount(UpgradeType.SIGHT) == 0, "Count should drop to 0");
        helper.assertTrue(player.getInventory().countItem(ModItems.upgrade(UpgradeType.SIGHT).get()) == 0, "Full inventory can't take it");
        helper.assertTrue(findDropped(helper, feet, ModItems.upgrade(UpgradeType.SIGHT).get()) != null,
                "The upgrade item should drop near the player's feet");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void templateModeRemovalOnlyForUpgradesBeyondTemplate(GameTestHelper helper) {
        ProgrammingStationBlockEntity station = place(helper);
        DroneData drone = DroneData.createNew().withUpgradeCount(UpgradeType.SIGHT, 2).withUpgradeCount(UpgradeType.PATROL, 1);
        station.getItems().setStackInSlot(0, droneStack(drone));
        station.setMode(ProgrammingMode.TEMPLATE);
        station.setProgramCount(UpgradeType.SIGHT, 1);
        station.setProgramCount(UpgradeType.PATROL, 1);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);

        station.removeUpgrade(UpgradeType.PATROL, player);
        helper.assertTrue(station.getDrone().orElseThrow().upgradeCount(UpgradeType.PATROL) == 1,
                "Patrol matches the template, so removal should be refused");
        station.removeUpgrade(UpgradeType.SIGHT, player);
        helper.assertTrue(station.getDrone().orElseThrow().upgradeCount(UpgradeType.SIGHT) == 1, "Extra Sight should be removable");
        station.removeUpgrade(UpgradeType.SIGHT, player);
        helper.assertTrue(station.getDrone().orElseThrow().upgradeCount(UpgradeType.SIGHT) == 1,
                "Sight now matches the template, so removal should be refused");
        helper.assertTrue(player.getInventory().countItem(ModItems.upgrade(UpgradeType.SIGHT).get()) == 1, "Only one Sight item refunded");
        helper.assertTrue(player.getInventory().countItem(ModItems.upgrade(UpgradeType.PATROL).get()) == 0, "No Patrol item refunded");
        helper.succeed();
    }

    // --- 6-8. Template mode ---

    @GameTest(template = "empty", timeoutTicks = 140)
    public static void templateModeWritesSettingsInstallsUpgradesAndCompletes(GameTestHelper helper) {
        ProgrammingStationBlockEntity station = place(helper);
        station.setMode(ProgrammingMode.TEMPLATE);
        station.setProgramCount(UpgradeType.PATROL, 1);
        station.setProgramCount(UpgradeType.HEALTH, 1);
        DroneConfig config = new DroneConfig(
                List.of(new TargetEntry(TargetEntry.Kind.ENTITY_TYPE, "minecraft:zombie"), new TargetEntry(TargetEntry.Kind.TAG, "minecraft:raiders")),
                10, Optional.of(GlobalPos.of(helper.getLevel().dimension(), helper.absolutePos(new BlockPos(6, 5, 6)))), Optional.of(3),
                "Bob", DyeColor.RED);
        station.setConfig(config);
        helper.assertValueEqual(station.getTemplate().config(), config, "Template config should be accepted");

        station.getEnergyStorage().receiveEnergy(200_000, false);
        station.getItems().setStackInSlot(1, new ItemStack(ModItems.upgrade(UpgradeType.PATROL).get(), 1));
        station.getItems().setStackInSlot(2, new ItemStack(ModItems.upgrade(UpgradeType.HEALTH).get(), 1));
        IItemHandler automation = station.getAutomationItems();
        ItemStack left = automation.insertItem(0, droneStack(DroneData.createNew()), false);
        helper.assertTrue(left.isEmpty(), "Drone should be inserted");
        helper.assertTrue(!station.isComplete(), "Not complete before the work is done");

        helper.succeedWhen(() -> {
            helper.assertTrue(station.isComplete(), "Station should be complete, drone=" + station.getDrone().orElseThrow());
            DroneData drone = station.getDrone().orElseThrow();
            helper.assertValueEqual(drone.config(), config, "Drone config should equal the template's");
            helper.assertTrue(drone.upgradeCount(UpgradeType.PATROL) == 1 && drone.upgradeCount(UpgradeType.HEALTH) == 1,
                    "Drone should have the programmed upgrades, has " + drone.upgrades());
            helper.assertTrue(station.getItems().getStackInSlot(1).isEmpty() && station.getItems().getStackInSlot(2).isEmpty(),
                    "Input upgrades should be consumed");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void templateModeWaitsWhenUpgradeMissingFromInput(GameTestHelper helper) {
        ProgrammingStationBlockEntity station = place(helper);
        station.setMode(ProgrammingMode.TEMPLATE);
        station.setProgramCount(UpgradeType.SIGHT, 1);
        station.getEnergyStorage().receiveEnergy(100_000, false);
        station.getItems().insertItem(0, droneStack(DroneData.createNew()), false);

        helper.runAfterDelay(30, () -> {
            helper.assertTrue(station.getInstalling() == null, "No step should run without the upgrade in the input");
            helper.assertTrue(station.getDrone().orElseThrow().upgrades().isEmpty(), "No upgrade should be installed");
            helper.assertTrue(!station.isComplete(), "Must not be complete");
            helper.assertTrue(station.getEnergyStorage().getEnergyStored() == 100_000, "No FE should be spent");
            // Supplying the upgrade later lets it proceed.
            station.getItems().setStackInSlot(4, new ItemStack(ModItems.upgrade(UpgradeType.SIGHT).get(), 1));
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(helper.getTick() > 30, "Waiting for the delayed input");
            helper.assertTrue(station.isComplete(), "Should complete once the upgrade is supplied");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void modeSwitchDoesNotChangeDroneAlreadyInSlotUntilReinserted(GameTestHelper helper) {
        ProgrammingStationBlockEntity station = place(helper);
        DroneData original = DroneData.createNew().withConfig(DroneConfig.createDefault().withLabel("Old"));
        station.getItems().setStackInSlot(0, droneStack(original));
        station.getItems().setStackInSlot(1, new ItemStack(ModItems.upgrade(UpgradeType.SIGHT).get(), 1));
        station.getEnergyStorage().receiveEnergy(100_000, false);

        station.setMode(ProgrammingMode.TEMPLATE);
        station.setProgramCount(UpgradeType.SIGHT, 1);
        station.setConfig(DroneConfig.createDefault().withLabel("New"));
        helper.assertTrue(station.getTemplate().config().label().equals("New"), "Template label should be set");

        helper.runAfterDelay(40, () -> {
            helper.assertValueEqual(station.getDrone().orElseThrow(), original, "Drone already in the slot must be left alone");
            helper.assertTrue(!station.isComplete(), "Must not be complete for a pre-existing drone");
            helper.assertTrue(station.getInstalling() == null, "No step should run");
            helper.assertTrue(station.getEnergyStorage().getEnergyStored() == 100_000, "No FE spent");

            ItemStack drone = station.getItems().extractItem(0, 1, false);
            helper.assertTrue(!drone.isEmpty(), "Player can take the drone out");
            station.getItems().insertItem(0, drone, false);
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(helper.getTick() > 40, "Waiting for reinsertion");
            helper.assertTrue(station.isComplete(), "Re-inserted drone should be programmed and complete");
            DroneData drone = station.getDrone().orElseThrow();
            helper.assertTrue(drone.config().label().equals("New") && drone.upgradeCount(UpgradeType.SIGHT) == 1,
                    "Template should apply after reinsertion, drone=" + drone);
        });
    }

    // --- 9. Automation ---

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void automationInsertsDroneAndUpgradesButNeverExtractsUpgrades(GameTestHelper helper) {
        ProgrammingStationBlockEntity station = place(helper);
        IItemHandler automation = station.getAutomationItems();

        helper.assertTrue(automation.insertItem(0, droneStack(DroneData.createNew()), false).isEmpty(), "Drone should go into slot 0");
        for (int slot = 1; slot <= 9; slot++) {
            ItemStack rest = automation.insertItem(slot, new ItemStack(ModItems.upgrade(UpgradeType.SIGHT).get(), 1), false);
            helper.assertTrue(rest.isEmpty(), "Upgrade should go into slot " + slot);
        }
        helper.assertTrue(!automation.insertItem(1, new ItemStack(Items.DIRT), false).isEmpty(), "Non-upgrade must be rejected in the input");
        helper.assertTrue(!automation.insertItem(0, new ItemStack(ModItems.upgrade(UpgradeType.SIGHT).get()), true).isEmpty(),
                "Upgrade must be rejected in the drone slot");
        helper.assertTrue(!automation.insertItem(2, droneStack(DroneData.createNew()), true).isEmpty(), "Drone must be rejected in the input");

        for (int slot = 1; slot <= 9; slot++) {
            helper.assertTrue(automation.extractItem(slot, 64, false).isEmpty(), "Upgrades must not be extractable, slot " + slot);
            helper.assertTrue(station.getItems().getStackInSlot(slot).getCount() == 1, "Slot " + slot + " should be untouched");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void automationCannotExtractDroneInDirectMode(GameTestHelper helper) {
        ProgrammingStationBlockEntity station = place(helper);
        station.getAutomationItems().insertItem(0, droneStack(DroneData.createNew()), false);
        helper.assertTrue(station.getAutomationItems().extractItem(0, 1, false).isEmpty(), "Direct mode: no automation extraction");
        helper.assertTrue(!station.getItems().getStackInSlot(0).isEmpty(), "Drone should still be there");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void automationExtractsDroneInTemplateModeOnlyWhenComplete(GameTestHelper helper) {
        ProgrammingStationBlockEntity station = place(helper);
        IItemHandler automation = station.getAutomationItems();
        station.setMode(ProgrammingMode.TEMPLATE);
        station.setProgramCount(UpgradeType.SIGHT, 1);
        automation.insertItem(0, droneStack(DroneData.createNew()), false);
        helper.assertTrue(!station.isComplete(), "Not complete: Sight is missing");
        helper.assertTrue(automation.extractItem(0, 1, false).isEmpty(), "Not complete: no extraction");
        helper.assertTrue(automation.extractItem(0, 1, true).isEmpty(), "Not complete: no simulated extraction either");

        // A program the drone matches exactly.
        station.setProgramCount(UpgradeType.SIGHT, 0);
        helper.assertTrue(station.isComplete(), "Now the drone matches the empty program");
        ItemStack out = automation.extractItem(0, 1, false);
        helper.assertTrue(!out.isEmpty(), "Complete: automation should extract the drone");
        helper.assertTrue(station.getItems().getStackInSlot(0).isEmpty(), "Slot should be empty after extraction");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void automationCannotExtractDroneWithExtraUpgrades(GameTestHelper helper) {
        ProgrammingStationBlockEntity station = place(helper);
        IItemHandler automation = station.getAutomationItems();
        station.setMode(ProgrammingMode.TEMPLATE);
        automation.insertItem(0, droneStack(DroneData.createNew().withUpgradeCount(UpgradeType.SIGHT, 1)), false);
        helper.assertTrue(!station.isComplete(), "A drone with extra upgrades never matches");
        helper.assertTrue(automation.extractItem(0, 1, false).isEmpty(), "No extraction for a drone with extra upgrades");
        helper.succeed();
    }

    // --- 10. setConfig validation ---

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void setConfigRejectsInvalidSettings(GameTestHelper helper) {
        ProgrammingStationBlockEntity station = place(helper);
        // drone.targetSlots (default 3) slots; no Player Seek.
        DroneData drone = DroneData.createNew();
        station.getItems().setStackInSlot(0, droneStack(drone));
        DroneConfig base = drone.config();
        TargetEntry zombie = new TargetEntry(TargetEntry.Kind.ENTITY_TYPE, "minecraft:zombie");
        TargetEntry skeleton = new TargetEntry(TargetEntry.Kind.ENTITY_TYPE, "minecraft:skeleton");
        TargetEntry creeper = new TargetEntry(TargetEntry.Kind.ENTITY_TYPE, "minecraft:creeper");
        TargetEntry spider = new TargetEntry(TargetEntry.Kind.ENTITY_TYPE, "minecraft:spider");

        expectRejected(helper, station, base.withTargets(List.of(new TargetEntry(TargetEntry.Kind.ENTITY_TYPE, "minecraft:not_a_thing"))),
                "unknown entity type");
        expectRejected(helper, station, base.withTargets(List.of(zombie, zombie)), "duplicate target");
        expectRejected(helper, station, base.withTargets(List.of(new TargetEntry(TargetEntry.Kind.PLAYER_NAME, "Steve"))),
                "player name without Player Seek");
        expectRejected(helper, station, base.withTargets(List.of(zombie, skeleton, creeper, spider)), "target beyond the slots");
        expectRejected(helper, station, base.withFollowDistance(ServerConfig.get(ServerConfig.DRONE_MAX_FOLLOW_DISTANCE) + 1),
                "follow distance above max");
        expectRejected(helper, station, base.withLabel("x".repeat(ProgramRules.MAX_LABEL_LENGTH + 1)), "label of 33 characters");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void setConfigAcceptsValidSettings(GameTestHelper helper) {
        ProgrammingStationBlockEntity station = place(helper);
        DroneData drone = DroneData.createNew().withUpgradeCount(UpgradeType.PLAYER_SEEK, 1);
        station.getItems().setStackInSlot(0, droneStack(drone));
        DroneConfig valid = drone.config()
                .withTargets(List.of(new TargetEntry(TargetEntry.Kind.ENTITY_TYPE, "minecraft:zombie"), new TargetEntry(TargetEntry.Kind.PLAYER_NAME, "Steve")))
                .withFollowDistance(ServerConfig.get(ServerConfig.DRONE_MAX_FOLLOW_DISTANCE))
                .withLabel("x".repeat(ProgramRules.MAX_LABEL_LENGTH))
                .withColor(DyeColor.GREEN);
        station.setConfig(valid);
        helper.assertValueEqual(station.getDrone().orElseThrow().config(), valid, "Valid settings should be written to the drone");
        helper.succeed();
    }

    // --- 11. Breaking and placing ---

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void breakingKeepsModeAndTemplateOnItemAndDropsContentsThenPlacingRestores(GameTestHelper helper) {
        ProgrammingStationBlockEntity station = place(helper);
        station.setMode(ProgrammingMode.TEMPLATE);
        station.setProgramCount(UpgradeType.SIGHT, 2);
        station.setConfig(DroneConfig.createDefault().withLabel("Tpl").withColor(DyeColor.PINK));
        DroneData droneData = DroneData.createNew().withUpgradeCount(UpgradeType.HEALTH, 1);
        station.getItems().setStackInSlot(0, droneStack(droneData));
        station.getItems().setStackInSlot(1, new ItemStack(ModItems.upgrade(UpgradeType.SIREN).get(), 1));
        var template = station.getTemplate();

        BlockPos abs = helper.absolutePos(REL);
        Vec3 center = Vec3.atCenterOf(abs);
        helper.getLevel().destroyBlock(abs, true);

        ItemStack stationItem = findDropped(helper, center, ModItems.PROGRAMMING_STATION.get());
        helper.assertTrue(stationItem != null, "The station item should drop");
        ProgrammingStationSettings settings = stationItem.get(ModDataComponents.PROGRAMMING_STATION);
        helper.assertTrue(settings != null, "Dropped item should carry the programming_station component");
        helper.assertTrue(settings.mode() == ProgrammingMode.TEMPLATE, "Mode should be kept, was " + settings.mode());
        helper.assertValueEqual(settings.template(), template, "Template should be kept");
        ItemStack droppedDrone = findDropped(helper, center, ModItems.DRONE.get());
        helper.assertTrue(droppedDrone != null && DroneItem.getData(droppedDrone).equals(droneData), "The drone should drop unchanged");
        helper.assertTrue(findDropped(helper, center, ModItems.upgrade(UpgradeType.SIREN).get()) != null, "The input upgrade should drop");

        // Placing the item again restores mode and template.
        BlockPos other = new BlockPos(8, 3, 8);
        helper.setBlock(other, ModBlocks.PROGRAMMING_STATION.get());
        ProgrammingStationBlockEntity placed = (ProgrammingStationBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(other));
        placed.applyComponentsFromItemStack(stationItem);
        helper.assertTrue(placed.getMode() == ProgrammingMode.TEMPLATE, "Placed station should be in Template mode");
        helper.assertValueEqual(placed.getTemplate(), template, "Placed station should have the template");
        helper.succeed();
    }

    // --- Helpers ---

    private static ProgrammingStationBlockEntity place(GameTestHelper helper) {
        helper.setBlock(REL, ModBlocks.PROGRAMMING_STATION.get());
        return (ProgrammingStationBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(REL));
    }

    private static ItemStack droneStack(DroneData data) {
        return DroneItem.createStack(data);
    }

    private static int totalOf(ProgrammingStationBlockEntity station) {
        return DroneStats.totalUpgrades(station.getTemplate().upgrades());
    }

    /** Fills the drone's upgrades to the total slot limit, respecting the per-type caps. */
    private static DroneData fillToTotalLimit(DroneData drone) {
        int limit = ServerConfig.get(ServerConfig.UPGRADES_TOTAL_SLOTS);
        for (UpgradeType type : UpgradeType.values()) {
            int room = limit - DroneStats.totalUpgrades(drone.upgrades());
            drone = drone.withUpgradeCount(type, Math.min(type.maxCount(), room));
        }
        return drone;
    }

    private static void expectRejected(GameTestHelper helper, ProgrammingStationBlockEntity station, DroneConfig requested, String what) {
        DroneConfig before = station.getDrone().orElseThrow().config();
        station.setConfig(requested);
        DroneConfig after = station.getDrone().orElseThrow().config();
        if (!after.equals(before)) {
            throw new GameTestAssertException("setConfig should reject " + what + " but the drone config changed to " + after);
        }
    }

    private static ItemStack findDropped(GameTestHelper helper, Vec3 pos, net.minecraft.world.item.Item item) {
        List<ItemEntity> entities = new ArrayList<>(helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(pos, pos).inflate(3.0)));
        for (ItemEntity entity : entities) {
            if (entity.getItem().getItem() == item) {
                return entity.getItem();
            }
        }
        return null;
    }
}
