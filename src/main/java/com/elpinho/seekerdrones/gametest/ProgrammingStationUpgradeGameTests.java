package com.elpinho.seekerdrones.gametest;

import java.util.Map;

import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.DroneItem;
import com.elpinho.seekerdrones.drone.DroneStats;
import com.elpinho.seekerdrones.drone.UpgradeType;
import com.elpinho.seekerdrones.programming.ProgrammingMode;
import com.elpinho.seekerdrones.programming.ProgrammingStationBlockEntity;
import com.elpinho.seekerdrones.programming.ProgrammingStationMenu;
import com.elpinho.seekerdrones.registry.ModBlocks;
import com.elpinho.seekerdrones.registry.ModDataComponents;
import com.elpinho.seekerdrones.registry.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Programming Station Energy upgrades (DESIGN.md section 7.2). All tests use config defaults: 4 upgrades max, x2 per
 * upgrade, 200 000 FE and 2 000 FE/tick base.
 */
@GameTestHolder("seekerdrones")
@PrefixGameTestTemplate(false)
public class ProgrammingStationUpgradeGameTests {
    private static final BlockPos REL = new BlockPos(4, 3, 4);

    // --- 1. Capacity and slot rules ---

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void capacityIs200kWithNoUpgradesAnd800kWithTwo(GameTestHelper helper) {
        ProgrammingStationBlockEntity station = place(helper, REL);
        helper.assertValueEqual(station.getEnergyStorage().getMaxEnergyStored(), 200_000, "capacity with 0 upgrades");
        station.getUpgrades().setStackInSlot(0, energy(2));
        helper.assertValueEqual(station.getEnergyUpgrades(), 2, "upgrade count");
        helper.assertValueEqual(station.getEnergyStorage().getMaxEnergyStored(), 800_000, "capacity with 2 upgrades");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void fifthEnergyUpgradeIsNotAccepted(GameTestHelper helper) {
        ProgrammingStationBlockEntity station = place(helper, REL);
        ItemStack remainder = station.getUpgrades().insertItem(0, energy(5), false);
        helper.assertValueEqual(station.getEnergyUpgrades(), 4, "accepted count");
        helper.assertValueEqual(remainder.getCount(), 1, "remainder");
        helper.assertTrue(!station.getUpgrades().insertItem(0, energy(1), true).isEmpty(), "A fifth should be rejected when full");
        helper.assertValueEqual(station.getEnergyStorage().getMaxEnergyStored(), 3_200_000, "capacity with 4 upgrades");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void onlyEnergyUpgradesFitTheUpgradeSlot(GameTestHelper helper) {
        ProgrammingStationBlockEntity station = place(helper, REL);
        helper.assertTrue(!station.getUpgrades().insertItem(0, new ItemStack(ModItems.upgrade(UpgradeType.SIGHT).get()), true).isEmpty(),
                "Sight upgrade should be rejected");
        helper.assertTrue(!station.getUpgrades().insertItem(0, new ItemStack(Items.DIRT), true).isEmpty(), "Dirt should be rejected");
        helper.assertTrue(station.getUpgrades().insertItem(0, energy(1), true).isEmpty(), "Energy upgrade should be accepted");
        helper.succeed();
    }

    // --- 2. Charge rate ---

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void chargeRateWithNoUpgradesIs4000PerTick(GameTestHelper helper) {
        checkChargeRate(helper, 0, 4_000);
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void chargeRateWithTwoUpgradesIs16000PerTick(GameTestHelper helper) {
        checkChargeRate(helper, 2, 16_000);
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void chargeRateWithFourUpgradesIs64000PerTick(GameTestHelper helper) {
        checkChargeRate(helper, 4, 64_000);
    }

    private static void checkChargeRate(GameTestHelper helper, int upgrades, int expectedRate) {
        ProgrammingStationBlockEntity station = place(helper, REL);
        if (upgrades > 0) {
            station.getUpgrades().setStackInSlot(0, energy(upgrades));
        }
        helper.assertValueEqual(station.getChargeRate(), expectedRate, "getChargeRate");
        station.getEnergyStorage().receiveEnergy(500_000, false);
        DroneData empty = DroneData.createNew();
        station.getItems().setStackInSlot(0, DroneItem.createStack(empty.withEnergy(0)));
        int[] last = { -1 };
        int[] deltas = { 0 };
        helper.succeedWhen(() -> {
            int current = station.getDrone().orElseThrow().energy();
            if (last[0] >= 0 && current != last[0]) {
                helper.assertValueEqual(current - last[0], expectedRate, "FE gained in one tick");
                deltas[0]++;
            }
            last[0] = current;
            helper.assertTrue(deltas[0] >= 3, "Waiting for 3 charge ticks, saw " + deltas[0]);
        });
    }

    // --- 3. Clamp on removal ---

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void removingUpgradesClampsStoredEnergyToNewCapacity(GameTestHelper helper) {
        ProgrammingStationBlockEntity station = place(helper, REL);
        station.getUpgrades().setStackInSlot(0, energy(2));
        station.getEnergyStorage().receiveEnergy(800_000, false);
        helper.assertValueEqual(station.getEnergyStorage().getEnergyStored(), 800_000, "filled");
        station.getUpgrades().extractItem(0, 1, false);
        helper.assertValueEqual(station.getEnergyStorage().getEnergyStored(), 400_000, "after removing one");
        station.getUpgrades().extractItem(0, 1, false);
        helper.assertValueEqual(station.getEnergyStorage().getEnergyStored(), 200_000, "after removing both");
        helper.succeed();
    }

    // --- 4. Install steps unaffected ---

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void installStepTimeAndCostAreUnchangedByUpgrades(GameTestHelper helper) {
        ProgrammingStationBlockEntity station = place(helper, REL);
        station.getUpgrades().setStackInSlot(0, energy(2));
        // A full drone isn't charged, so the buffer only pays for the step.
        station.getItems().setStackInSlot(0, DroneItem.createStack(DroneData.createNew()));
        station.getItems().setStackInSlot(ProgrammingStationBlockEntity.inputSlot(UpgradeType.SIGHT),
                new ItemStack(ModItems.upgrade(UpgradeType.SIGHT).get(), 1));
        int cost = UpgradeType.SIGHT.baseCost();
        int initial = cost + 50_000;
        station.getEnergyStorage().receiveEnergy(initial, false);
        station.requestInstall(UpgradeType.SIGHT);
        helper.assertTrue(station.getInstalling() == UpgradeType.SIGHT, "Step should start");
        int start = (int) helper.getTick();
        helper.runAfterDelay(ProgrammingStationBlockEntity.installTime() - 6, () ->
                helper.assertTrue(station.getDrone().orElseThrow().upgradeCount(UpgradeType.SIGHT) == 0, "Shouldn't be installed early"));
        helper.succeedWhen(() -> {
            helper.assertTrue(station.getDrone().orElseThrow().upgradeCount(UpgradeType.SIGHT) == 1, "Not installed yet");
            int elapsed = (int) helper.getTick() - start;
            helper.assertTrue(Math.abs(elapsed - ProgrammingStationBlockEntity.installTime()) <= 2,
                    "Install should take about " + ProgrammingStationBlockEntity.installTime() + " ticks, took " + elapsed);
            helper.assertValueEqual(station.getEnergyStorage().getEnergyStored(), initial - cost, "FE left");
        });
    }

    // --- 5. Automation ---

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void automationHandlerCannotReachUpgradeSlots(GameTestHelper helper) {
        ProgrammingStationBlockEntity station = place(helper, REL);
        station.getUpgrades().setStackInSlot(0, energy(3));
        helper.assertValueEqual(station.getAutomationItems().getSlots(), ProgrammingStationBlockEntity.SLOT_COUNT, "automation slot count");
        ServerLevel level = helper.getLevel();
        BlockPos abs = helper.absolutePos(REL);
        for (Direction dir : new Direction[] { Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, null }) {
            IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, abs, dir);
            helper.assertTrue(handler != null, "Handler should exist on " + dir);
            helper.assertValueEqual(handler.getSlots(), ProgrammingStationBlockEntity.SLOT_COUNT, "slots on " + dir);
            for (int i = 0; i < handler.getSlots(); i++) {
                helper.assertTrue(handler.getStackInSlot(i).isEmpty(), "Slot " + i + " on " + dir + " exposes the upgrades");
            }
        }
        // Inserting Energy Upgrades goes to the input slot and leaves the tab untouched.
        station.getAutomationItems().insertItem(ProgrammingStationBlockEntity.inputSlot(UpgradeType.ENERGY), energy(10), false);
        helper.assertValueEqual(station.getEnergyUpgrades(), 3, "tab untouched");
        helper.assertValueEqual(station.getItems().getStackInSlot(ProgrammingStationBlockEntity.inputSlot(UpgradeType.ENERGY)).getCount(), 10,
                "input received");
        helper.succeed();
    }

    // --- 6. Persistence ---

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void upgradesSurviveSaveAndLoad(GameTestHelper helper) {
        ProgrammingStationBlockEntity station = place(helper, REL);
        station.getUpgrades().setStackInSlot(0, energy(3));
        ServerLevel level = helper.getLevel();
        CompoundTag tag = station.saveWithFullMetadata(level.registryAccess());
        ProgrammingStationBlockEntity copy = new ProgrammingStationBlockEntity(helper.absolutePos(REL), level.getBlockState(helper.absolutePos(REL)));
        copy.loadWithComponents(tag, level.registryAccess());
        helper.assertValueEqual(copy.getEnergyUpgrades(), 3, "loaded count");
        helper.assertTrue(copy.getUpgrades().getStackInSlot(0).is(ModItems.upgrade(UpgradeType.ENERGY).get()), "loaded item");
        helper.assertValueEqual(copy.getEnergyStorage().getMaxEnergyStored(), 1_600_000, "loaded capacity");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void breakingKeepsUpgradesWithTemplateSettingsAndPlacingRestores(GameTestHelper helper) {
        ProgrammingStationBlockEntity station = place(helper, REL);
        station.getUpgrades().setStackInSlot(0, energy(3));
        station.setMode(ProgrammingMode.TEMPLATE);
        BlockPos abs = helper.absolutePos(REL);
        Vec3 center = Vec3.atCenterOf(abs);
        helper.getLevel().destroyBlock(abs, true);

        ItemStack dropped = findDropped(helper, center);
        helper.assertTrue(dropped != null, "The station item should drop");
        Map<UpgradeType, Integer> saved = dropped.get(ModDataComponents.MACHINE_UPGRADES);
        helper.assertTrue(saved != null && saved.equals(Map.of(UpgradeType.ENERGY, 3)), "Item should carry {energy:3}, was " + saved);
        helper.assertTrue(dropped.get(ModDataComponents.PROGRAMMING_STATION) != null, "Item should also carry PROGRAMMING_STATION");
        helper.assertTrue(findDroppedUpgrade(helper, center) == 0, "The tab's upgrades must not drop as loose items");

        BlockPos otherRel = new BlockPos(7, 3, 7);
        ProgrammingStationBlockEntity placed = place(helper, otherRel);
        helper.assertValueEqual(placed.getEnergyUpgrades(), 0, "fresh station");
        placed.applyComponentsFromItemStack(dropped);
        helper.assertValueEqual(placed.getEnergyUpgrades(), 3, "restored count");
        helper.assertTrue(placed.getMode() == ProgrammingMode.TEMPLATE, "Mode restored");
        helper.assertValueEqual(placed.getEnergyStorage().getMaxEnergyStored(), 1_600_000, "restored capacity");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void breakingWithDefaultSettingsKeepsOnlyUpgradeComponent(GameTestHelper helper) {
        ProgrammingStationBlockEntity station = place(helper, REL);
        station.getUpgrades().setStackInSlot(0, energy(2));
        BlockPos abs = helper.absolutePos(REL);
        Vec3 center = Vec3.atCenterOf(abs);
        helper.getLevel().destroyBlock(abs, true);
        ItemStack dropped = findDropped(helper, center);
        helper.assertTrue(dropped != null, "The station item should drop");
        helper.assertTrue(Map.of(UpgradeType.ENERGY, 2).equals(dropped.get(ModDataComponents.MACHINE_UPGRADES)), "Item should carry {energy:2}");
        helper.assertTrue(dropped.get(ModDataComponents.PROGRAMMING_STATION) == null, "No PROGRAMMING_STATION for default settings");
        helper.succeed();
    }

    // --- 7. Menu ---

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void menuHasUpgradeSlotAfterPlayerSlots(GameTestHelper helper) {
        ProgrammingStationBlockEntity station = place(helper, REL);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        ProgrammingStationMenu menu = (ProgrammingStationMenu) station.createMenu(1, player.getInventory(), player);
        int expectedTotal = ProgrammingStationBlockEntity.SLOT_COUNT + 36 + 1;
        helper.assertValueEqual(ProgrammingStationMenu.SLOT_TOTAL, expectedTotal, "SLOT_TOTAL");
        helper.assertValueEqual(menu.slots.size(), expectedTotal, "slots.size");
        helper.assertValueEqual(menu.getUpgradeSlots().size(), 1, "upgrade slots");
        helper.assertTrue(menu.slots.get(expectedTotal - 1) == menu.getUpgradeSlots().get(0), "Upgrade slot is last");
        helper.assertTrue(menu.slots.get(expectedTotal - 1).mayPlace(energy(1)), "Energy accepted");
        helper.assertTrue(!menu.slots.get(expectedTotal - 1).mayPlace(new ItemStack(ModItems.upgrade(UpgradeType.SIGHT).get())), "Sight rejected");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void quickMoveFromUpgradeSlotGoesToPlayerInventory(GameTestHelper helper) {
        ProgrammingStationBlockEntity station = place(helper, REL);
        station.getUpgrades().setStackInSlot(0, energy(3));
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        ProgrammingStationMenu menu = (ProgrammingStationMenu) station.createMenu(1, player.getInventory(), player);
        menu.quickMoveStack(player, ProgrammingStationMenu.SLOT_TOTAL - 1);
        helper.assertTrue(station.getUpgrades().getStackInSlot(0).isEmpty(), "Tab emptied");
        helper.assertValueEqual(countEnergy(player), 3, "player received");
        helper.assertTrue(station.getItems().getStackInSlot(ProgrammingStationBlockEntity.inputSlot(UpgradeType.ENERGY)).isEmpty(),
                "Input slot must stay empty");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void quickMoveEnergyUpgradeFromInventoryGoesToInputSlotNotTab(GameTestHelper helper) {
        ProgrammingStationBlockEntity station = place(helper, REL);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.getInventory().setItem(0, energy(5));
        ProgrammingStationMenu menu = (ProgrammingStationMenu) station.createMenu(1, player.getInventory(), player);
        menu.quickMoveStack(player, ProgrammingStationBlockEntity.SLOT_COUNT + 27); // hotbar slot 0
        helper.assertValueEqual(station.getItems().getStackInSlot(ProgrammingStationBlockEntity.inputSlot(UpgradeType.ENERGY)).getCount(), 5,
                "input slot count");
        helper.assertValueEqual(station.getEnergyUpgrades(), 0, "tab untouched");
        helper.assertValueEqual(countEnergy(player), 0, "player gave all");
        helper.succeed();
    }

    // --- Helpers ---

    private static ProgrammingStationBlockEntity place(GameTestHelper helper, BlockPos rel) {
        helper.setBlock(rel, ModBlocks.PROGRAMMING_STATION.get());
        return (ProgrammingStationBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(rel));
    }

    private static ItemStack energy(int count) {
        return new ItemStack(ModItems.upgrade(UpgradeType.ENERGY).get(), count);
    }

    private static int countEnergy(Player player) {
        int n = 0;
        for (ItemStack s : player.getInventory().items) {
            if (s.is(ModItems.upgrade(UpgradeType.ENERGY).get())) {
                n += s.getCount();
            }
        }
        return n;
    }

    private static ItemStack findDropped(GameTestHelper helper, Vec3 pos) {
        for (ItemEntity entity : helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(pos, pos).inflate(3.0))) {
            if (entity.getItem().is(ModItems.PROGRAMMING_STATION.get())) {
                return entity.getItem();
            }
        }
        return null;
    }

    private static int findDroppedUpgrade(GameTestHelper helper, Vec3 pos) {
        int total = 0;
        for (ItemEntity entity : helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(pos, pos).inflate(3.0))) {
            if (entity.getItem().is(ModItems.upgrade(UpgradeType.ENERGY).get())) {
                total += entity.getItem().getCount();
            }
        }
        return total;
    }
}
