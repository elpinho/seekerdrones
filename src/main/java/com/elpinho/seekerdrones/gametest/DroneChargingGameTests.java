package com.elpinho.seekerdrones.gametest;

import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.DroneItem;
import com.elpinho.seekerdrones.drone.DroneStats;
import com.elpinho.seekerdrones.drone.UpgradeType;
import com.elpinho.seekerdrones.programming.ProgramRules;
import com.elpinho.seekerdrones.programming.ProgrammingMode;
import com.elpinho.seekerdrones.programming.ProgrammingStationBlockEntity;
import com.elpinho.seekerdrones.registry.ModBlocks;
import com.elpinho.seekerdrones.registry.ModDataComponents;
import com.elpinho.seekerdrones.registry.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Chargeable drone item and Programming Station charging (DESIGN.md sections 2.1, 7.2, 7.6). Tests that change
 * {@link ServerConfig} run in their own batch and restore the value in an {@code @AfterBatch}.
 */
@GameTestHolder("seekerdrones")
@PrefixGameTestTemplate(false)
public class DroneChargingGameTests {
    private static final BlockPos REL = new BlockPos(4, 3, 4);

    private static final String STATION_RATE_BATCH = "config_station_charge_rate_300";
    private static final String ITEM_RATE_BATCH = "config_item_charge_rate_500";
    private static final String SLOTS_BATCH = "config_station_total_slots_1";
    private static int originalStationRate;
    private static int originalItemRate;
    private static int originalSlots;

    @BeforeBatch(batch = STATION_RATE_BATCH)
    public static void beforeStationRate(ServerLevel level) {
        originalStationRate = ServerConfig.get(ServerConfig.PROGRAMMING_STATION_CHARGE_RATE);
        ServerConfig.PROGRAMMING_STATION_CHARGE_RATE.set(300);
    }

    @AfterBatch(batch = STATION_RATE_BATCH)
    public static void afterStationRate(ServerLevel level) {
        ServerConfig.PROGRAMMING_STATION_CHARGE_RATE.set(originalStationRate);
    }

    @BeforeBatch(batch = ITEM_RATE_BATCH)
    public static void beforeItemRate(ServerLevel level) {
        originalItemRate = ServerConfig.get(ServerConfig.DRONE_ITEM_CHARGE_RATE);
        ServerConfig.DRONE_ITEM_CHARGE_RATE.set(500);
    }

    @AfterBatch(batch = ITEM_RATE_BATCH)
    public static void afterItemRate(ServerLevel level) {
        ServerConfig.DRONE_ITEM_CHARGE_RATE.set(originalItemRate);
    }

    @BeforeBatch(batch = SLOTS_BATCH)
    public static void beforeSlots(ServerLevel level) {
        originalSlots = ServerConfig.get(ServerConfig.UPGRADES_TOTAL_SLOTS);
    }

    @AfterBatch(batch = SLOTS_BATCH)
    public static void afterSlots(ServerLevel level) {
        ServerConfig.UPGRADES_TOTAL_SLOTS.set(originalSlots);
    }

    // --- 1. Item capability ---

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void droneItemHasEnergyCapabilityThatOnlyReceives(GameTestHelper helper) {
        ItemStack stack = DroneItem.createStack(DroneData.createNew().withEnergy(0));
        IEnergyStorage cap = stack.getCapability(Capabilities.EnergyStorage.ITEM);
        helper.assertTrue(cap != null, "Drone item should expose the energy capability");
        helper.assertTrue(cap.canReceive(), "canReceive should be true");
        helper.assertTrue(!cap.canExtract(), "canExtract should be false");
        helper.assertTrue(cap.getMaxEnergyStored() == DroneStats.maxEnergy(DroneItem.getData(stack)), "Max should be the drone's max energy");
        cap.receiveEnergy(1000, false);
        helper.assertTrue(cap.extractEnergy(1000, false) == 0, "extractEnergy must return 0");
        helper.assertTrue(cap.extractEnergy(1000, true) == 0, "simulated extractEnergy must return 0");
        helper.assertTrue(DroneItem.getData(stack).energy() == 1000, "Energy must not be extracted");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void receiveEnergyIsCappedPerCallAtItemChargeRate(GameTestHelper helper) {
        int rate = ServerConfig.get(ServerConfig.DRONE_ITEM_CHARGE_RATE);
        ItemStack stack = DroneItem.createStack(DroneData.createNew().withEnergy(0).withHealth(7f));
        IEnergyStorage cap = stack.getCapability(Capabilities.EnergyStorage.ITEM);
        int received = cap.receiveEnergy(rate * 5, false);
        helper.assertTrue(received == rate, "Should receive exactly the rate " + rate + ", got " + received);
        helper.assertTrue(DroneItem.getData(stack).energy() == rate, "Drone energy should be " + rate);
        helper.assertTrue(cap.receiveEnergy(10, false) == 10, "Small amounts are accepted whole");
        helper.assertTrue(DroneItem.getData(stack).energy() == rate + 10, "Energy should add up");
        helper.assertTrue(DroneItem.getData(stack).health() == 7f, "Health must not change");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 10, batch = ITEM_RATE_BATCH)
    public static void receiveEnergyFollowsConfiguredItemChargeRate(GameTestHelper helper) {
        ItemStack stack = DroneItem.createStack(DroneData.createNew().withEnergy(0));
        int received = stack.getCapability(Capabilities.EnergyStorage.ITEM).receiveEnergy(10_000, false);
        helper.assertTrue(received == 500, "Rate is 500 in this batch, got " + received);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void receiveEnergyIsCappedAtMaxEnergyAndEnergyUpgradesRaiseIt(GameTestHelper helper) {
        DroneData plain = DroneData.createNew();
        int max = DroneStats.maxEnergy(plain);
        ItemStack nearFull = DroneItem.createStack(plain.withEnergy(max - 100));
        helper.assertTrue(nearFull.getCapability(Capabilities.EnergyStorage.ITEM).receiveEnergy(1000, false) == 100, "Only the room left (100) fits");
        helper.assertTrue(DroneItem.getData(nearFull).energy() == max, "Should be full");
        helper.assertTrue(nearFull.getCapability(Capabilities.EnergyStorage.ITEM).receiveEnergy(1000, false) == 0, "Full drone receives nothing");

        DroneData upgraded = plain.withUpgradeCount(UpgradeType.ENERGY, 1);
        helper.assertTrue(DroneStats.maxEnergy(upgraded) > max, "Energy upgrade should raise max");
        ItemStack stack = DroneItem.createStack(upgraded.withEnergy(max));
        IEnergyStorage cap = stack.getCapability(Capabilities.EnergyStorage.ITEM);
        helper.assertTrue(cap.getMaxEnergyStored() == DroneStats.maxEnergy(upgraded), "Capability max follows the upgrade");
        int expected = Math.min(ServerConfig.get(ServerConfig.DRONE_ITEM_CHARGE_RATE), 1000);
        helper.assertTrue(cap.receiveEnergy(1000, false) == expected, "A drone with the upgrade has room above the old max");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void simulatedReceiveDoesNotChangeTheStack(GameTestHelper helper) {
        ItemStack stack = DroneItem.createStack(DroneData.createNew().withEnergy(500));
        ItemStack copy = stack.copy();
        int received = stack.getCapability(Capabilities.EnergyStorage.ITEM).receiveEnergy(1000, true);
        helper.assertTrue(received == 1000, "Simulate should report 1000, got " + received);
        helper.assertTrue(ItemStack.isSameItemSameComponents(stack, copy), "Stack must be unchanged");
        helper.assertTrue(DroneItem.getData(stack).energy() == 500, "Energy must be unchanged");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void stackWithoutDataCountsAsFullAndReceivesNothing(GameTestHelper helper) {
        ItemStack stack = new ItemStack(ModItems.DRONE.get());
        helper.assertTrue(!stack.has(ModDataComponents.DRONE_DATA), "Precondition: no data component");
        IEnergyStorage cap = stack.getCapability(Capabilities.EnergyStorage.ITEM);
        helper.assertTrue(cap != null, "Capability should exist");
        helper.assertTrue(cap.getEnergyStored() == cap.getMaxEnergyStored(), "Should count as full");
        helper.assertTrue(cap.receiveEnergy(1000, false) == 0, "Should receive 0");
        helper.assertTrue(!stack.has(ModDataComponents.DRONE_DATA), "Receiving must not add a component");
        helper.succeed();
    }

    // --- 2. Item bar ---

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void energyBarIsAlwaysVisibleAndScalesWithEnergy(GameTestHelper helper) {
        DroneData plain = DroneData.createNew();
        int max = DroneStats.maxEnergy(plain);
        DroneItem item = ModItems.DRONE.get();

        ItemStack empty = DroneItem.createStack(plain.withEnergy(0));
        helper.assertTrue(item.isBarVisible(empty), "Bar visible at empty");
        helper.assertTrue(item.getBarWidth(empty) == 0, "Empty width should be 0, was " + item.getBarWidth(empty));
        ItemStack full = DroneItem.createStack(plain);
        helper.assertTrue(item.isBarVisible(full), "Bar visible at full");
        helper.assertTrue(item.getBarWidth(full) == 13, "Full width should be 13, was " + item.getBarWidth(full));
        ItemStack third = DroneItem.createStack(plain.withEnergy(max / 3));
        helper.assertTrue(item.getBarWidth(third) == Math.round(13f / 3), "A third should be " + Math.round(13f / 3) + ", was " + item.getBarWidth(third));
        ItemStack half = DroneItem.createStack(plain.withEnergy(max / 2));
        helper.assertTrue(item.getBarWidth(half) == 7, "Half should round to 7, was " + item.getBarWidth(half));

        DroneData upgraded = plain.withUpgradeCount(UpgradeType.ENERGY, 1);
        ItemStack halfOfUpgraded = DroneItem.createStack(upgraded.withEnergy(max));
        helper.assertTrue(item.getBarWidth(halfOfUpgraded) == 7, "Old max is half of the upgraded max, was " + item.getBarWidth(halfOfUpgraded));
        helper.succeed();
    }

    // --- 3. Station charging, Direct mode ---

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void directModeChargesDroneFromBufferAtChargeRate(GameTestHelper helper) {
        int rate = ServerConfig.get(ServerConfig.PROGRAMMING_STATION_CHARGE_RATE);
        ProgrammingStationBlockEntity station = place(helper);
        station.getItems().setStackInSlot(0, DroneItem.createStack(DroneData.createNew().withEnergy(0)));
        station.getEnergyStorage().receiveEnergy(100_000, false);
        helper.succeedWhen(() -> {
            int energy = station.getDrone().orElseThrow().energy();
            int stored = station.getEnergyStorage().getEnergyStored();
            helper.assertTrue(energy >= 3 * rate, "Not charged for three ticks yet, energy=" + energy);
            helper.assertTrue(energy % rate == 0, "Drone should gain " + rate + " per tick, energy=" + energy);
            helper.assertTrue(stored == 100_000 - energy, "Buffer should drop by what the drone gained, stored=" + stored + " energy=" + energy);
            helper.assertTrue(station.isCharging(), "isCharging should be true while charging");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 40, batch = STATION_RATE_BATCH)
    public static void directModeChargeRateFollowsConfig(GameTestHelper helper) {
        ProgrammingStationBlockEntity station = place(helper);
        station.getItems().setStackInSlot(0, DroneItem.createStack(DroneData.createNew().withEnergy(0)));
        station.getEnergyStorage().receiveEnergy(100_000, false);
        helper.succeedWhen(() -> {
            int energy = station.getDrone().orElseThrow().energy();
            helper.assertTrue(energy >= 900, "Not charged for three ticks yet, energy=" + energy);
            helper.assertTrue(energy % 300 == 0, "Drone should gain 300 per tick, energy=" + energy);
            helper.assertTrue(station.getEnergyStorage().getEnergyStored() == 100_000 - energy, "Buffer should drop by the same amount");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void directModeStopsChargingAtMaxEnergy(GameTestHelper helper) {
        ProgrammingStationBlockEntity station = place(helper);
        DroneData plain = DroneData.createNew();
        int max = DroneStats.maxEnergy(plain);
        station.getItems().setStackInSlot(0, DroneItem.createStack(plain.withEnergy(max - 1000)));
        station.getEnergyStorage().receiveEnergy(100_000, false);
        helper.succeedWhen(() -> {
            helper.assertTrue(station.getDrone().orElseThrow().energy() == max, "Should reach exactly max, was " + station.getDrone().orElseThrow().energy());
            helper.assertTrue(station.getEnergyStorage().getEnergyStored() == 99_000, "Only the missing 1000 should be taken, stored=" + station.getEnergyStorage().getEnergyStored());
            helper.assertTrue(!station.isCharging(), "Not charging once full");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void emptyBufferChargesNothing(GameTestHelper helper) {
        ProgrammingStationBlockEntity station = place(helper);
        station.getItems().setStackInSlot(0, DroneItem.createStack(DroneData.createNew().withEnergy(1234)));
        helper.runAfterDelay(15, () -> {
            helper.assertTrue(station.getDrone().orElseThrow().energy() == 1234, "Energy must not change");
            helper.assertTrue(!station.isCharging(), "Not charging with no FE");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void stationNeverRestoresHealth(GameTestHelper helper) {
        ProgrammingStationBlockEntity station = place(helper);
        station.getItems().setStackInSlot(0, DroneItem.createStack(DroneData.createNew().withEnergy(0).withHealth(3f)));
        station.getEnergyStorage().receiveEnergy(100_000, false);
        helper.succeedWhen(() -> {
            DroneData drone = station.getDrone().orElseThrow();
            helper.assertTrue(drone.energy() >= 4000, "Should be charging, energy=" + drone.energy());
            helper.assertTrue(drone.health() == 3f, "Health must stay 3, was " + drone.health());
        });
    }

    // --- 4. Install priority ---

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void installStepTakesItsFeBeforeCharging(GameTestHelper helper) {
        ProgrammingStationBlockEntity station = place(helper);
        station.getItems().setStackInSlot(0, DroneItem.createStack(DroneData.createNew().withEnergy(0)));
        station.getItems().setStackInSlot(ProgrammingStationBlockEntity.inputSlot(UpgradeType.PATROL), new ItemStack(ModItems.upgrade(UpgradeType.PATROL).get(), 1));
        int perTick = ProgramRules.installCost(UpgradeType.PATROL, 1) / ProgrammingStationBlockEntity.installTime();
        int buffer = perTick + 100;
        station.getEnergyStorage().receiveEnergy(buffer, false);
        station.requestInstall(UpgradeType.PATROL);
        helper.assertTrue(station.getInstalling() == UpgradeType.PATROL, "Step should start");
        int[] maxProgressSeen = {0};
        helper.succeedWhen(() -> {
            DroneData drone = station.getDrone().orElseThrow();
            if (drone.upgradeCount(UpgradeType.PATROL) == 0) {
                // The buffer is topped up after every tick, so the step can only progress if it takes its share first.
                helper.assertTrue(drone.energy() % 100 == 0, "Charging should only get the 100 FE left over, energy=" + drone.energy());
                int top = buffer - station.getEnergyStorage().getEnergyStored();
                if (top > 0) {
                    station.getEnergyStorage().receiveEnergy(top, false);
                }
                maxProgressSeen[0] = Math.max(maxProgressSeen[0], station.getProgress());
                helper.assertTrue(false, "Install not finished yet, progress=" + station.getProgress());
            }
            helper.assertTrue(maxProgressSeen[0] >= 15, "The step should have progressed every tick, max progress " + maxProgressSeen[0]);
            int slack = ProgrammingStationBlockEntity.installTime() + 5;
            helper.assertTrue(drone.energy() > 0 && drone.energy() <= 100 * slack, "Drone got only the leftover, energy=" + drone.energy());
        });
    }

    // --- 5. Template mode ---

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void templateModeDroneIsNotCompleteUntilFull(GameTestHelper helper) {
        ProgrammingStationBlockEntity station = place(helper);
        station.setMode(ProgrammingMode.TEMPLATE);
        IItemHandler automation = station.getAutomationItems();
        DroneData plain = DroneData.createNew();
        int max = DroneStats.maxEnergy(plain);
        helper.assertTrue(automation.insertItem(0, DroneItem.createStack(plain.withEnergy(max - 5000)), false).isEmpty(), "Drone should be accepted");
        // No FE in the buffer: it matches the program but is not full.
        helper.runAfterDelay(8, () -> {
            helper.assertTrue(!station.isComplete(), "Not complete while not full");
            helper.assertTrue(automation.extractItem(0, 1, true).isEmpty(), "Simulated extract must be empty");
            helper.assertTrue(automation.extractItem(0, 1, false).isEmpty(), "Extract must be empty until full");
            helper.assertTrue(!station.getItems().getStackInSlot(0).isEmpty(), "Drone stays in the slot");
            station.getEnergyStorage().receiveEnergy(50_000, false);
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(helper.getTick() > 8, "Too early");
            helper.assertTrue(station.isComplete(), "Should be complete once full");
            ItemStack out = automation.extractItem(0, 1, false);
            helper.assertTrue(!out.isEmpty(), "Extract should succeed when full");
            helper.assertTrue(DroneItem.getData(out).energy() == max, "Extracted drone is full");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void droneAlreadyInSlotBeforeSwitchingToTemplateIsNotCharged(GameTestHelper helper) {
        ProgrammingStationBlockEntity station = place(helper);
        station.getItems().setStackInSlot(0, DroneItem.createStack(DroneData.createNew().withEnergy(1000)));
        station.setMode(ProgrammingMode.TEMPLATE);
        helper.assertTrue(!station.isAccepted(), "Precondition: drone is not accepted");
        station.getEnergyStorage().receiveEnergy(100_000, false);
        helper.runAfterDelay(15, () -> {
            helper.assertTrue(station.getDrone().orElseThrow().energy() == 1000, "Unaccepted drone must not be charged, energy=" + station.getDrone().orElseThrow().energy());
            helper.assertTrue(station.getEnergyStorage().getEnergyStored() == 100_000, "Buffer untouched");
            helper.assertTrue(!station.isCharging(), "Not charging");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 30, batch = SLOTS_BATCH)
    public static void invalidTemplateDoesNotCharge(GameTestHelper helper) {
        ProgrammingStationBlockEntity station = place(helper);
        station.setMode(ProgrammingMode.TEMPLATE);
        station.setProgramCount(UpgradeType.SIGHT, 2);
        helper.assertTrue(station.getTemplate().upgradeCount(UpgradeType.SIGHT) == 2, "Precondition: template has 2 Sight");
        ServerConfig.UPGRADES_TOTAL_SLOTS.set(1);
        helper.assertTrue(!station.isTemplateValid(), "Precondition: template is now over the limits");
        station.getEnergyStorage().receiveEnergy(100_000, false);
        station.getAutomationItems().insertItem(0, DroneItem.createStack(DroneData.createNew().withEnergy(1000)), false);
        helper.assertTrue(station.isAccepted(), "Precondition: drone accepted");
        helper.runAfterDelay(15, () -> {
            helper.assertTrue(station.getDrone().orElseThrow().energy() == 1000, "No charging with an invalid template, energy=" + station.getDrone().orElseThrow().energy());
            helper.assertTrue(station.getEnergyStorage().getEnergyStored() == 100_000, "Buffer untouched");
            helper.assertTrue(!station.isCharging(), "Not charging");
            helper.succeed();
        });
    }

    private static ProgrammingStationBlockEntity place(GameTestHelper helper) {
        helper.setBlock(REL, ModBlocks.PROGRAMMING_STATION.get());
        return (ProgrammingStationBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(REL));
    }
}
