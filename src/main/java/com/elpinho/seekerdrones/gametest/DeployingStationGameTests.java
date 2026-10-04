package com.elpinho.seekerdrones.gametest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.annotation.Nullable;

import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.deploying.DeployingShaft;
import com.elpinho.seekerdrones.deploying.DeployingStationBlock;
import com.elpinho.seekerdrones.deploying.DeployingStationBlockEntity;
import com.elpinho.seekerdrones.deploying.DeployingStatus;
import com.elpinho.seekerdrones.drone.DroneConfig;
import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.drone.DroneItem;
import com.elpinho.seekerdrones.drone.UpgradeType;
import com.elpinho.seekerdrones.factory.DroneFactoryBlockEntity;
import com.elpinho.seekerdrones.programming.ProgrammingMode;
import com.elpinho.seekerdrones.programming.ProgrammingStationBlockEntity;
import com.elpinho.seekerdrones.registry.ModBlocks;
import com.elpinho.seekerdrones.registry.ModDataComponents;
import com.elpinho.seekerdrones.registry.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * M8 (Drone Deploying Station) GameTests, DESIGN.md sections 7.3, 2.2, 3.2, 2.8 and 6.3. All tests use config
 * defaults (5 000 FE per deploy, launch height 0.8, check interval 10 ticks, deploy drag 0.9) and the
 * {@code seekerdrones:empty} template (all-air 9x6x9). Stations are fed only through their capabilities.
 */
@GameTestHolder("seekerdrones")
@PrefixGameTestTemplate(false)
public class DeployingStationGameTests {
    private static final BlockPos REL = new BlockPos(4, 3, 4);
    /** Retry interval (10) plus margin. */
    private static final int RETRY_WINDOW = 13;

    // --- 1. Auto-deploy ---

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void autoDeployLaunchesDroneAboveStationCostsEnergyAndComesToRest(GameTestHelper helper) {
        DeployingStationBlockEntity station = place(helper);
        int perDeploy = ServerConfig.get(ServerConfig.DEPLOYING_STATION_ENERGY_PER_DEPLOY);
        int initial = perDeploy + 1234;
        helper.assertTrue(giveEnergy(helper, initial) == initial, "Station should accept " + initial + " FE");
        helper.assertTrue(insertDrone(helper, DroneData.createNew().withDroneId("DEPL-0001")).isEmpty(), "Drone should be inserted");

        BlockPos abs = helper.absolutePos(REL);
        double topY = abs.getY() + 1;
        helper.succeedWhen(() -> {
            DroneEntity drone = findDrone(helper, abs);
            helper.assertTrue(drone != null, "No drone deployed yet");
            helper.assertTrue(station.getItems().getStackInSlot(0).isEmpty(), "Slot should be empty after the deploy");
            helper.assertTrue(station.getEnergyStorage().getEnergyStored() == initial - perDeploy,
                    "Exactly " + perDeploy + " FE should be spent, stored=" + station.getEnergyStorage().getEnergyStored());
            helper.assertTrue(Math.abs(drone.getX() - (abs.getX() + 0.5)) < 0.01 && Math.abs(drone.getZ() - (abs.getZ() + 0.5)) < 0.01,
                    "Drone should be centered on the station, at " + drone.position());
            helper.assertTrue(!drone.isDrifting(), "Drone should have come to rest");
            double rise = drone.getY() - topY;
            double launch = ServerConfig.get(ServerConfig.DEPLOYING_STATION_LAUNCH_HEIGHT);
            helper.assertTrue(rise > 0.05 && rise <= launch + 0.001 && rise <= 1.0,
                    "Rise above the top face should be in (0.05, " + launch + "], was " + rise);
            helper.assertTrue(abs.above().equals(drone.getRestPosition()), "Rest position should be the block above the station, was " + drone.getRestPosition());
        });
    }

    // --- 2. Drone data ---

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void deployKeepsUnownedDroneUnownedAndAssignsMissingId(GameTestHelper helper) {
        place(helper);
        giveEnergy(helper, 5000);
        DroneData noId = DroneData.createNew().withUpgradeCount(UpgradeType.SIGHT, 1);
        helper.assertTrue(!noId.hasDroneId() && noId.ownerId().isEmpty(), "Sanity: fixture has no ID and no owner");
        insertDrone(helper, noId);

        BlockPos abs = helper.absolutePos(REL);
        helper.succeedWhen(() -> {
            DroneEntity drone = findDrone(helper, abs);
            helper.assertTrue(drone != null, "No drone deployed yet");
            DroneData data = drone.snapshotData();
            helper.assertTrue(data.hasDroneId(), "A drone without an ID should get one");
            helper.assertTrue(data.ownerId().isEmpty() && data.ownerName().isEmpty(), "The station must not set an owner, data=" + data);
            helper.assertTrue(data.upgradeCount(UpgradeType.SIGHT) == 1, "Upgrades should be kept");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void deployKeepsExistingOwnerIdUpgradesAndEnergy(GameTestHelper helper) {
        place(helper);
        giveEnergy(helper, 5000);
        UUID owner = UUID.randomUUID();
        DroneData original = DroneData.createNew().withDroneId("KEEP-1234").withOwner(owner, "Alice")
                .withUpgradeCount(UpgradeType.SIGHT, 2).withUpgradeCount(UpgradeType.HEALTH, 1).withEnergy(777)
                .withConfig(DroneConfig.createDefault().withLabel("Keeper").withColor(DyeColor.BLUE));
        insertDrone(helper, original);

        BlockPos abs = helper.absolutePos(REL);
        helper.succeedWhen(() -> {
            DroneEntity drone = findDrone(helper, abs);
            helper.assertTrue(drone != null, "No drone deployed yet");
            DroneData data = drone.snapshotData();
            helper.assertValueEqual(data.ownerId(), Optional.of(owner), "Owner id");
            helper.assertTrue(data.ownerName().equals("Alice"), "Owner name should be kept, was " + data.ownerName());
            helper.assertTrue(data.droneId().equals("KEEP-1234"), "Drone ID should be kept, was " + data.droneId());
            helper.assertTrue(data.upgradeCount(UpgradeType.SIGHT) == 2 && data.upgradeCount(UpgradeType.HEALTH) == 1, "Upgrades should be kept");
            helper.assertTrue(data.energy() == 777, "The station doesn't charge the drone, energy=" + data.energy());
            helper.assertValueEqual(data.config(), original.config(), "Config should be kept");
        });
    }

    // --- 3. Not enough FE ---

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void withoutEnoughEnergyDroneWaitsThenDeploysWhenEnergyAdded(GameTestHelper helper) {
        DeployingStationBlockEntity station = place(helper);
        int perDeploy = ServerConfig.get(ServerConfig.DEPLOYING_STATION_ENERGY_PER_DEPLOY);
        giveEnergy(helper, perDeploy - 1);
        insertDrone(helper, DroneData.createNew());
        BlockPos abs = helper.absolutePos(REL);

        helper.runAfterDelay(25, () -> {
            helper.assertTrue(findDrone(helper, abs) == null, "No drone should spawn without enough FE");
            helper.assertTrue(!station.getItems().getStackInSlot(0).isEmpty(), "The drone should stay in the slot");
            helper.assertTrue(station.getEnergyStorage().getEnergyStored() == perDeploy - 1, "FE must be untouched, stored=" + station.getEnergyStorage().getEnergyStored());
            helper.assertTrue(station.getStatus() == DeployingStatus.NO_ENERGY, "Status should be NO_ENERGY, was " + station.getStatus());
            giveEnergy(helper, 1);
            helper.runAfterDelay(RETRY_WINDOW, () -> {
                helper.assertTrue(findDrone(helper, abs) != null, "Drone should deploy within the check interval after FE is added");
                helper.assertTrue(station.getItems().getStackInSlot(0).isEmpty(), "Slot should be empty");
                helper.assertTrue(station.getEnergyStorage().getEnergyStored() == 0, "Deploy should cost exactly " + perDeploy);
                helper.succeed();
            });
        });
    }

    // --- 4. Blocked by a block ---

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void blockDirectlyAboveBlocksDeployUntilRemoved(GameTestHelper helper) {
        DeployingStationBlockEntity station = place(helper);
        giveEnergy(helper, 5000);
        helper.setBlock(REL.above(), Blocks.STONE);
        insertDrone(helper, DroneData.createNew());
        expectBlockedThenClearedDeploys(helper, station, REL.above());
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void blockInsideLaunchHeightRangeBlocksDeployUntilRemoved(GameTestHelper helper) {
        DeployingStationBlockEntity station = place(helper);
        giveEnergy(helper, 5000);
        // The drone is 0.4 tall, so its box stretched up by 0.8 reaches 1.2 above the top face: this block (starting
        // 1.0 above the top face) is only hit by the stretched box.
        BlockPos high = REL.above(2);
        helper.setBlock(high, Blocks.STONE);
        insertDrone(helper, DroneData.createNew());
        expectBlockedThenClearedDeploys(helper, station, high);
    }

    private static void expectBlockedThenClearedDeploys(GameTestHelper helper, DeployingStationBlockEntity station, BlockPos blockRel) {
        BlockPos abs = helper.absolutePos(REL);
        helper.runAfterDelay(25, () -> {
            helper.assertTrue(findDrone(helper, abs) == null, "No drone should spawn into a block");
            helper.assertTrue(!station.getItems().getStackInSlot(0).isEmpty(), "The drone should stay in the slot");
            helper.assertTrue(station.getStatus() == DeployingStatus.BLOCKED, "Status should be BLOCKED, was " + station.getStatus());
            helper.assertTrue(station.getEnergyStorage().getEnergyStored() == 5000, "No FE should be spent");
            helper.setBlock(blockRel, Blocks.AIR);
            helper.runAfterDelay(RETRY_WINDOW, () -> {
                helper.assertTrue(findDrone(helper, abs) != null, "Drone should deploy after the block is removed");
                helper.assertTrue(station.getEnergyStorage().getEnergyStored() == 0, "Deploy should spend the FE");
                helper.succeed();
            });
        });
    }

    // --- 5. Blocked by another drone ---

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void droneHoveringAboveBlocksNextDeployUntilItIsRemoved(GameTestHelper helper) {
        DeployingStationBlockEntity station = place(helper);
        giveEnergy(helper, 10_000);
        insertDrone(helper, DroneData.createNew().withDroneId("FIRST-001"));
        BlockPos abs = helper.absolutePos(REL);

        helper.runAfterDelay(30, () -> {
            DroneEntity first = findDrone(helper, abs);
            helper.assertTrue(first != null, "First drone should be deployed");
            insertDrone(helper, DroneData.createNew().withDroneId("SECOND-02"));
            helper.runAfterDelay(25, () -> {
                helper.assertTrue(!station.getItems().getStackInSlot(0).isEmpty(), "Second drone should wait in the slot");
                helper.assertTrue(station.getStatus() == DeployingStatus.BLOCKED, "Status should be BLOCKED, was " + station.getStatus());
                helper.assertTrue(station.getEnergyStorage().getEnergyStored() == 5000, "Second deploy must not spend FE yet");
                helper.assertTrue(countDrones(helper, abs) == 1, "Only the first drone should exist");
                first.discard();
                helper.runAfterDelay(RETRY_WINDOW, () -> {
                    helper.assertTrue(station.getItems().getStackInSlot(0).isEmpty(), "Second drone should deploy once the first is gone");
                    DroneEntity second = findDrone(helper, abs);
                    helper.assertTrue(second != null && second.snapshotData().droneId().equals("SECOND-02"), "Second drone should exist");
                    helper.succeed();
                });
            });
        });
    }

    // --- 6. Auto-deploy off ---

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void autoDeployOffDroneWaitsReadyAndRequestDeployLaunchesIt(GameTestHelper helper) {
        DeployingStationBlockEntity station = place(helper);
        station.setAutoDeploy(false);
        giveEnergy(helper, 5000);
        insertDrone(helper, DroneData.createNew());
        BlockPos abs = helper.absolutePos(REL);

        helper.runAfterDelay(30, () -> {
            helper.assertTrue(findDrone(helper, abs) == null, "No deploy on its own with auto-deploy off");
            helper.assertTrue(station.getStatus() == DeployingStatus.READY, "Status should be READY, was " + station.getStatus());
            helper.assertTrue(station.getEnergyStorage().getEnergyStored() == 5000, "No FE should be spent");
            station.requestDeploy();
            helper.assertTrue(findDrone(helper, abs) != null, "requestDeploy should deploy the drone");
            helper.assertTrue(station.getItems().getStackInSlot(0).isEmpty(), "Slot should be empty");
            helper.assertTrue(station.getEnergyStorage().getEnergyStored() == 0, "Deploy should spend the FE");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void redstoneRisingEdgeDeploysAndHeldSignalDoesNotDeployNextDrone(GameTestHelper helper) {
        DeployingStationBlockEntity station = place(helper);
        station.setAutoDeploy(false);
        giveEnergy(helper, 10_000);
        insertDrone(helper, DroneData.createNew().withDroneId("REDST-001"));
        BlockPos abs = helper.absolutePos(REL);
        BlockPos powerRel = REL.east();

        helper.runAfterDelay(15, () -> {
            helper.assertTrue(findDrone(helper, abs) == null, "No deploy before the pulse");
            helper.setBlock(powerRel, Blocks.REDSTONE_BLOCK);
            helper.assertTrue(findDrone(helper, abs) != null, "A rising edge should deploy the drone");
            DroneEntity first = findDrone(helper, abs);
            first.discard();

            // The signal stays on: a new drone must not deploy.
            insertDrone(helper, DroneData.createNew().withDroneId("REDST-002"));
            helper.runAfterDelay(25, () -> {
                helper.assertTrue(findDrone(helper, abs) == null, "A held signal must not deploy the next drone");
                helper.assertTrue(!station.getItems().getStackInSlot(0).isEmpty(), "Second drone should wait");
                helper.assertTrue(station.getStatus() == DeployingStatus.READY, "Status should be READY, was " + station.getStatus());
                helper.setBlock(powerRel, Blocks.AIR);
                helper.assertTrue(findDrone(helper, abs) == null, "Falling edge must not deploy");
                helper.setBlock(powerRel, Blocks.REDSTONE_BLOCK);
                DroneEntity second = findDrone(helper, abs);
                helper.assertTrue(second != null && second.snapshotData().droneId().equals("REDST-002"), "The next rising edge should deploy it");
                helper.succeed();
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void requestDeployAndRedstoneDoNothingExtraWhileAutoDeployIsOn(GameTestHelper helper) {
        DeployingStationBlockEntity station = place(helper);
        insertDrone(helper, DroneData.createNew());
        BlockPos abs = helper.absolutePos(REL);

        helper.runAfterDelay(15, () -> {
            helper.assertTrue(station.getStatus() == DeployingStatus.NO_ENERGY, "Sanity: waiting for FE, status=" + station.getStatus());
            // Add FE without changing the slot: the station only notices at its next interval check.
            giveEnergy(helper, 5000);
            station.requestDeploy();
            helper.assertTrue(findDrone(helper, abs) == null, "requestDeploy must be a no-op with auto-deploy on");
            helper.setBlock(REL.east(), Blocks.REDSTONE_BLOCK);
            helper.assertTrue(findDrone(helper, abs) == null, "Redstone must be ignored with auto-deploy on");
            helper.runAfterDelay(RETRY_WINDOW, () -> {
                helper.assertTrue(findDrone(helper, abs) != null, "The normal retry should still deploy it");
                helper.succeed();
            });
        });
    }

    // --- 7. Automation ---

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void automationCannotExtractRejectsNonDronesAndHoldsOnlyOne(GameTestHelper helper) {
        DeployingStationBlockEntity station = place(helper);
        station.setAutoDeploy(false);
        DroneData data = DroneData.createNew().withDroneId("AUTO-0001");

        for (Direction side : Direction.values()) {
            helper.assertTrue(itemHandler(helper, side) != null, "Item capability should exist on side " + side);
            helper.assertTrue(helper.getLevel().getCapability(Capabilities.EnergyStorage.BLOCK, helper.absolutePos(REL), side) != null,
                    "Energy capability should exist on side " + side);
        }
        IItemHandler handler = itemHandler(helper, null);
        helper.assertTrue(!handler.isItemValid(0, new ItemStack(Items.DIRT)), "Dirt is not valid");
        ItemStack dirt = new ItemStack(Items.DIRT, 3);
        helper.assertTrue(ItemStack.matches(handler.insertItem(0, dirt.copy(), false), dirt), "Dirt should be rejected");
        helper.assertTrue(station.getItems().getStackInSlot(0).isEmpty(), "Slot should still be empty");

        helper.assertTrue(handler.insertItem(0, DroneItem.createStack(data), false).isEmpty(), "First drone accepted");
        ItemStack second = DroneItem.createStack(DroneData.createNew().withDroneId("AUTO-0002"));
        helper.assertTrue(!handler.insertItem(0, second.copy(), false).isEmpty(), "A second drone must not fit");
        helper.assertTrue(DroneItem.getData(station.getItems().getStackInSlot(0)).droneId().equals("AUTO-0001"), "Slot keeps the first drone");

        helper.assertTrue(handler.extractItem(0, 1, true).isEmpty(), "Simulated extraction must return empty");
        helper.assertTrue(handler.extractItem(0, 1, false).isEmpty(), "Extraction must return empty");
        helper.assertTrue(!station.getItems().getStackInSlot(0).isEmpty(), "Drone should still be in the slot");
        helper.succeed();
    }

    // --- 8. Breaking and placing ---

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void breakingWithAutoDeployOffDropsDroneAndItemKeepsSetting(GameTestHelper helper) {
        DeployingStationBlockEntity station = place(helper);
        station.setAutoDeploy(false);
        DroneData data = DroneData.createNew().withDroneId("BREAK-001");
        insertDrone(helper, data);

        BlockPos abs = helper.absolutePos(REL);
        Vec3 center = Vec3.atCenterOf(abs);
        helper.getLevel().destroyBlock(abs, true);
        ItemStack stationItem = findDropped(helper, center, ModItems.DEPLOYING_STATION.get());
        helper.assertTrue(stationItem != null, "The station item should drop");
        helper.assertValueEqual(stationItem.get(ModDataComponents.DEPLOYING_STATION), Boolean.FALSE, "Component on the dropped item");
        ItemStack droppedDrone = findDropped(helper, center, ModItems.DRONE.get());
        helper.assertTrue(droppedDrone != null && DroneItem.getData(droppedDrone).equals(data), "The drone should drop unchanged");

        BlockPos other = new BlockPos(7, 3, 7);
        helper.setBlock(other, ModBlocks.DEPLOYING_STATION.get());
        DeployingStationBlockEntity placed = (DeployingStationBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(other));
        helper.assertTrue(placed.isAutoDeploy(), "A fresh station has auto-deploy on");
        placed.applyComponentsFromItemStack(stationItem);
        helper.assertTrue(!placed.isAutoDeploy(), "Placing the item should restore auto-deploy off");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void breakingWithAutoDeployOnDropsItemWithoutComponent(GameTestHelper helper) {
        DeployingStationBlockEntity station = place(helper);
        station.setAutoDeploy(true);
        // No FE, so the drone stays in the slot.
        insertDrone(helper, DroneData.createNew().withDroneId("BREAK-002"));

        BlockPos abs = helper.absolutePos(REL);
        Vec3 center = Vec3.atCenterOf(abs);
        helper.getLevel().destroyBlock(abs, true);
        ItemStack stationItem = findDropped(helper, center, ModItems.DEPLOYING_STATION.get());
        helper.assertTrue(stationItem != null, "The station item should drop");
        helper.assertTrue(!stationItem.has(ModDataComponents.DEPLOYING_STATION), "No component with auto-deploy on");
        helper.assertTrue(findDropped(helper, center, ModItems.DRONE.get()) != null, "The drone should drop");
        helper.succeed();
    }

    // --- 9. Patrol fallback ---

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void patrolDroneWithoutCenterUsesBlockAboveStationAsCenter(GameTestHelper helper) {
        place(helper);
        giveEnergy(helper, 5000);
        DroneData patrol = DroneData.createNew().withUpgradeCount(UpgradeType.PATROL, 1)
                .withConfig(DroneConfig.createDefault().withPatrolRadius(Optional.of(4)));
        helper.assertTrue(patrol.config().patrolCenter().isEmpty(), "Sanity: no configured center");
        insertDrone(helper, patrol);

        BlockPos abs = helper.absolutePos(REL);
        helper.succeedWhen(() -> {
            DroneEntity drone = findDrone(helper, abs);
            helper.assertTrue(drone != null, "No drone deployed yet");
            helper.assertValueEqual(drone.getPatrolCenter(), GlobalPos.of(helper.getLevel().dimension(), abs.above()), "Patrol center");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void configuredPatrolCenterIsKeptOverStationFallback(GameTestHelper helper) {
        place(helper);
        giveEnergy(helper, 5000);
        GlobalPos configured = GlobalPos.of(helper.getLevel().dimension(), helper.absolutePos(new BlockPos(1, 3, 1)));
        DroneData patrol = DroneData.createNew().withUpgradeCount(UpgradeType.PATROL, 1)
                .withConfig(DroneConfig.createDefault().withPatrolCenter(Optional.of(configured)).withPatrolRadius(Optional.of(4)));
        insertDrone(helper, patrol);

        BlockPos abs = helper.absolutePos(REL);
        helper.succeedWhen(() -> {
            DroneEntity drone = findDrone(helper, abs);
            helper.assertTrue(drone != null, "No drone deployed yet");
            helper.assertValueEqual(drone.getPatrolCenter(), configured, "Patrol center");
        });
    }

    // --- 10. Full pipeline ---

    @GameTest(template = "empty", timeoutTicks = 500)
    public static void factoryToProgrammingStationToDeployingStationPipelineDeploysProgrammedDrone(GameTestHelper helper) {
        // Column layout, all at z = 4: Factory (1,4) over hopper (1,3) facing east into the Programming Station (2,3);
        // a hopper under it (2,2) facing east into the Deploying Station (3,2).
        BlockPos factoryRel = new BlockPos(1, 4, 4);
        BlockPos hopper1Rel = new BlockPos(1, 3, 4);
        BlockPos progRel = new BlockPos(2, 3, 4);
        BlockPos hopper2Rel = new BlockPos(2, 2, 4);
        BlockPos deployRel = new BlockPos(3, 2, 4);

        Player owner = helper.makeMockPlayer(GameType.SURVIVAL);
        owner.setUUID(UUID.randomUUID());
        helper.setBlock(factoryRel, ModBlocks.DRONE_FACTORY.get());
        BlockPos factoryAbs = helper.absolutePos(factoryRel);
        BlockState factoryState = helper.getLevel().getBlockState(factoryAbs);
        ModBlocks.DRONE_FACTORY.get().setPlacedBy(helper.getLevel(), factoryAbs, factoryState, owner, new ItemStack(ModItems.DRONE_FACTORY.get()));
        DroneFactoryBlockEntity factory = (DroneFactoryBlockEntity) helper.getLevel().getBlockEntity(factoryAbs);
        UUID groupId = factory.getGroupId().orElseThrow();

        helper.setBlock(hopper1Rel, Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.EAST));
        helper.setBlock(progRel, ModBlocks.PROGRAMMING_STATION.get());
        helper.setBlock(hopper2Rel, Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.EAST));
        helper.setBlock(deployRel, ModBlocks.DEPLOYING_STATION.get());
        ProgrammingStationBlockEntity prog = (ProgrammingStationBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(progRel));
        DeployingStationBlockEntity deploy = (DeployingStationBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(deployRel));

        prog.setMode(ProgrammingMode.TEMPLATE);
        prog.setProgramCount(UpgradeType.SIGHT, 1);
        prog.setConfig(DroneConfig.createDefault().withLabel("Pipe").withColor(DyeColor.GREEN));
        helper.assertTrue(prog.getTemplate().config().label().equals("Pipe"), "Template label should be accepted");

        // Everything is fed through capabilities.
        IItemHandler factoryItems = helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, factoryAbs, null);
        for (int rotorSlot = 0; rotorSlot < 4; rotorSlot++) {
            helper.assertTrue(factoryItems.insertItem(rotorSlot, new ItemStack(ModItems.DRONE_ROTOR.get(), 1), false).isEmpty(), "Rotor inserted");
        }
        helper.assertTrue(factoryItems.insertItem(4, new ItemStack(ModItems.SEEKER_CORE.get(), 1), false).isEmpty(), "Core inserted");
        helper.assertTrue(factoryItems.insertItem(5, new ItemStack(Items.IRON_INGOT, 4), false).isEmpty(), "Iron inserted");
        IFluidHandler tank = helper.getLevel().getCapability(Capabilities.FluidHandler.BLOCK, factoryAbs, null);
        helper.assertTrue(tank.fill(new FluidStack(Fluids.LAVA, 1000), IFluidHandler.FluidAction.EXECUTE) == 1000, "Lava filled");
        helper.assertTrue(helper.getLevel().getCapability(Capabilities.EnergyStorage.BLOCK, factoryAbs, null)
                .receiveEnergy(Integer.MAX_VALUE, false) > 0, "Factory FE");
        IItemHandler progItems = helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, helper.absolutePos(progRel), null);
        helper.assertTrue(progItems.insertItem(1, new ItemStack(ModItems.upgrade(UpgradeType.SIGHT).get(), 1), false).isEmpty(), "Upgrade inserted");
        helper.assertTrue(helper.getLevel().getCapability(Capabilities.EnergyStorage.BLOCK, helper.absolutePos(progRel), null)
                .receiveEnergy(200_000, false) > 0, "Programming Station FE");
        helper.assertTrue(helper.getLevel().getCapability(Capabilities.EnergyStorage.BLOCK, helper.absolutePos(deployRel), null)
                .receiveEnergy(50_000, false) > 0, "Deploying Station FE");

        BlockPos deployAbs = helper.absolutePos(deployRel);
        helper.succeedWhen(() -> {
            DroneEntity drone = findDrone(helper, deployAbs);
            if (drone == null) {
                throw new GameTestAssertException("No drone deployed yet: factory progress=" + factory.getProgress()
                        + ", programming slot=" + prog.getItems().getStackInSlot(0) + ", installing=" + prog.getInstalling()
                        + ", deploy slot=" + deploy.getItems().getStackInSlot(0) + ", status=" + deploy.getStatus());
            }
            DroneData data = drone.snapshotData();
            helper.assertTrue(data.upgradeCount(UpgradeType.SIGHT) == 1, "Deployed drone should have the template's Sight upgrade, data=" + data);
            helper.assertTrue(data.config().label().equals("Pipe") && data.config().color() == DyeColor.GREEN,
                    "Deployed drone should carry the template's label and color, config=" + data.config());
            helper.assertTrue(data.hasDroneId(), "Deployed drone should have a drone ID");
            helper.assertValueEqual(data.groupId(), Optional.of(groupId), "Group linkage from the Factory");
            helper.assertTrue(deploy.getItems().getStackInSlot(0).isEmpty(), "Deploying Station slot should be empty");
        });
    }

    // --- 11. Block states (DESIGN.md 7.6) ---

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void newStationStartsWithEmptyShaft(GameTestHelper helper) {
        place(helper);
        helper.assertTrue(shaft(helper) == DeployingShaft.EMPTY, "A new station should be EMPTY, was " + shaft(helper));
        helper.runAfterDelay(12, () -> {
            helper.assertTrue(shaft(helper) == DeployingShaft.EMPTY, "Still EMPTY after ticking, was " + shaft(helper));
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void autoDeployOffInsertedDroneLoadsShaftThenDeployLaunchesAndReturnsToEmpty(GameTestHelper helper) {
        DeployingStationBlockEntity station = place(helper);
        station.setAutoDeploy(false);
        giveEnergy(helper, 5000);
        insertDrone(helper, DroneData.createNew());
        BlockPos abs = helper.absolutePos(REL);

        helper.runAfterDelay(3, () -> {
            helper.assertTrue(shaft(helper) == DeployingShaft.LOADED, "Shaft should be LOADED, was " + shaft(helper));
            helper.assertTrue(findDrone(helper, abs) == null, "No deploy with auto-deploy off");
            station.requestDeploy();
            helper.assertTrue(findDrone(helper, abs) != null, "requestDeploy should deploy");
            helper.assertTrue(shaft(helper) == DeployingShaft.LAUNCHING, "Shaft should be LAUNCHING right away, was " + shaft(helper));
            helper.runAfterDelay(DeployingStationBlock.LAUNCH_TICKS / 2, () -> {
                helper.assertTrue(shaft(helper) == DeployingShaft.LAUNCHING, "Still LAUNCHING mid-launch, was " + shaft(helper));
                helper.runAfterDelay(DeployingStationBlock.LAUNCH_TICKS / 2 + 3, () -> {
                    helper.assertTrue(shaft(helper) == DeployingShaft.EMPTY, "Shaft should be EMPTY after the launch, was " + shaft(helper));
                    helper.succeed();
                });
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void redstonePulseDeploySetsShaftLaunchingThenEmpty(GameTestHelper helper) {
        DeployingStationBlockEntity station = place(helper);
        station.setAutoDeploy(false);
        giveEnergy(helper, 5000);
        insertDrone(helper, DroneData.createNew());
        helper.runAfterDelay(3, () -> {
            helper.assertTrue(shaft(helper) == DeployingShaft.LOADED, "Shaft should be LOADED, was " + shaft(helper));
            helper.setBlock(REL.east(), Blocks.REDSTONE_BLOCK);
            helper.assertTrue(shaft(helper) == DeployingShaft.LAUNCHING, "Shaft should be LAUNCHING after the pulse, was " + shaft(helper));
            helper.runAfterDelay(DeployingStationBlock.LAUNCH_TICKS + 3, () -> {
                helper.assertTrue(shaft(helper) == DeployingShaft.EMPTY, "Shaft should be EMPTY after the launch, was " + shaft(helper));
                helper.succeed();
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void autoDeployOnInsertedDroneLaunchesShaftThenEmpty(GameTestHelper helper) {
        DeployingStationBlockEntity station = place(helper);
        giveEnergy(helper, 5000);
        insertDrone(helper, DroneData.createNew());
        BlockPos abs = helper.absolutePos(REL);

        helper.runAfterDelay(3, () -> {
            helper.assertTrue(findDrone(helper, abs) != null, "Drone should have deployed");
            helper.assertTrue(station.getItems().getStackInSlot(0).isEmpty(), "Slot should be empty");
            helper.assertTrue(shaft(helper) == DeployingShaft.LAUNCHING, "Shaft should be LAUNCHING, was " + shaft(helper));
            helper.runAfterDelay(DeployingStationBlock.LAUNCH_TICKS + 3, () -> {
                helper.assertTrue(shaft(helper) == DeployingShaft.EMPTY, "Shaft should be EMPTY after the launch, was " + shaft(helper));
                helper.succeed();
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void droneBlockedBySpaceKeepsShaftLoadedUntilExtractedByHand(GameTestHelper helper) {
        DeployingStationBlockEntity station = place(helper);
        giveEnergy(helper, 5000);
        helper.setBlock(REL.above(), Blocks.STONE);
        insertDrone(helper, DroneData.createNew());
        expectLoadedThenExtractedEmpties(helper, station);
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void droneWithoutEnergyKeepsShaftLoadedUntilExtractedByHand(GameTestHelper helper) {
        DeployingStationBlockEntity station = place(helper);
        insertDrone(helper, DroneData.createNew());
        expectLoadedThenExtractedEmpties(helper, station);
    }

    private static void expectLoadedThenExtractedEmpties(GameTestHelper helper, DeployingStationBlockEntity station) {
        helper.runAfterDelay(15, () -> {
            helper.assertTrue(shaft(helper) == DeployingShaft.LOADED, "Shaft should stay LOADED, was " + shaft(helper));
            helper.assertTrue(!station.getItems().getStackInSlot(0).isEmpty(), "Drone should still be in the slot");
            helper.assertTrue(!station.getItems().extractItem(0, 1, false).isEmpty(), "Hand extraction should return the drone");
            helper.runAfterDelay(3, () -> {
                helper.assertTrue(shaft(helper) == DeployingShaft.EMPTY, "Shaft should be EMPTY after extraction, was " + shaft(helper));
                helper.succeed();
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void defaultFacingIsNorthAndRotateChangesIt(GameTestHelper helper) {
        BlockState state = ModBlocks.DEPLOYING_STATION.get().defaultBlockState();
        helper.assertTrue(state.getValue(DeployingStationBlock.FACING) == Direction.NORTH, "Default FACING should be NORTH");
        helper.assertTrue(state.getValue(DeployingStationBlock.SHAFT) == DeployingShaft.EMPTY, "Default SHAFT should be EMPTY");
        helper.assertTrue(state.rotate(Rotation.CLOCKWISE_90).getValue(DeployingStationBlock.FACING) == Direction.EAST, "Rotating NORTH by 90 should give EAST");
        helper.assertTrue(state.rotate(Rotation.CLOCKWISE_180).getValue(DeployingStationBlock.FACING) == Direction.SOUTH, "Rotating NORTH by 180 should give SOUTH");
        helper.succeed();
    }

    // --- Helpers ---

    private static DeployingShaft shaft(GameTestHelper helper) {
        return helper.getLevel().getBlockState(helper.absolutePos(REL)).getValue(DeployingStationBlock.SHAFT);
    }

    private static DeployingStationBlockEntity place(GameTestHelper helper) {
        helper.setBlock(REL, ModBlocks.DEPLOYING_STATION.get());
        return (DeployingStationBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(REL));
    }

    @Nullable
    private static IItemHandler itemHandler(GameTestHelper helper, @Nullable Direction side) {
        return helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, helper.absolutePos(REL), side);
    }

    /** Gives FE through the energy capability; returns the amount accepted. */
    private static int giveEnergy(GameTestHelper helper, int amount) {
        return helper.getLevel().getCapability(Capabilities.EnergyStorage.BLOCK, helper.absolutePos(REL), null).receiveEnergy(amount, false);
    }

    /** Inserts a drone through the item capability; returns what didn't fit. */
    private static ItemStack insertDrone(GameTestHelper helper, DroneData data) {
        return itemHandler(helper, Direction.NORTH).insertItem(0, DroneItem.createStack(data), false);
    }

    @Nullable
    private static DroneEntity findDrone(GameTestHelper helper, BlockPos stationAbs) {
        List<DroneEntity> drones = dronesNear(helper, stationAbs);
        return drones.isEmpty() ? null : drones.get(0);
    }

    private static int countDrones(GameTestHelper helper, BlockPos stationAbs) {
        return dronesNear(helper, stationAbs).size();
    }

    private static List<DroneEntity> dronesNear(GameTestHelper helper, BlockPos stationAbs) {
        return helper.getLevel().getEntitiesOfClass(DroneEntity.class, new AABB(stationAbs).inflate(2.5));
    }

    @Nullable
    private static ItemStack findDropped(GameTestHelper helper, Vec3 pos, Item item) {
        for (ItemEntity entity : helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(pos, pos).inflate(3.0))) {
            if (entity.getItem().getItem() == item) {
                return entity.getItem();
            }
        }
        return null;
    }
}
