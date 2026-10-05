package com.elpinho.seekerdrones.gametest;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.IntConsumer;

import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.drone.DroneItem;
import com.elpinho.seekerdrones.drone.DroneState;
import com.elpinho.seekerdrones.drone.DroneStats;
import com.elpinho.seekerdrones.drone.UpgradeType;
import com.elpinho.seekerdrones.factory.DroneFactoryBlockEntity;
import com.elpinho.seekerdrones.machine.MachineWorkingState;
import com.elpinho.seekerdrones.programming.ProgrammingStationBlockEntity;
import com.elpinho.seekerdrones.registry.ModBlocks;
import com.elpinho.seekerdrones.registry.ModEntityTypes;
import com.elpinho.seekerdrones.registry.ModItems;
import com.elpinho.seekerdrones.station.ChargingStationBlock;
import com.elpinho.seekerdrones.station.ChargingStationBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Machine visuals GameTests (DESIGN.md section 7.6) and the "drones don't track falls" rule (section 2.5): the
 * {@code working} / {@code repairing} block states of the Factory, Programming Station and Charging Station.
 * No test changes ServerConfig.
 */
@GameTestHolder("seekerdrones")
@PrefixGameTestTemplate(false)
public class MachineVisualsGameTests {
    private static final BlockPos REL = new BlockPos(4, 3, 4);

    // --- Factory ---

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void factoryIsNotWorkingWhenIdle(GameTestHelper helper) {
        helper.setBlock(REL, ModBlocks.DRONE_FACTORY.get());
        helper.runAfterDelay(10, () -> {
            helper.assertTrue(!working(helper, REL), "An idle Factory should not be working");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void factoryWorkingStaysTrueWhileProgressingThenTurnsOffAfterInputsRemoved(GameTestHelper helper) {
        DroneFactoryBlockEntity factory = placeFactory(helper);
        fillFactory(factory);
        factory.getEnergyStorage().receiveEnergy(Integer.MAX_VALUE, false);

        tickLoop(helper, 70, tick -> {
            if (tick >= 3 && tick < 30) {
                helper.assertTrue(working(helper, REL), "Factory should be working every tick while progressing, tick " + tick + " progress=" + factory.getProgress());
            }
            if (tick == 30) {
                helper.assertTrue(factory.getProgress() > 0, "Sanity: build should have progressed");
                factory.getItems().setStackInSlot(5, ItemStack.EMPTY); // progress stops
            }
            if (tick > 30 && tick <= 30 + 15) {
                helper.assertTrue(working(helper, REL), "Factory should still show working within the idle delay, tick " + tick);
            }
            if (tick == 30 + MachineWorkingState.IDLE_DELAY + 6) {
                helper.assertTrue(!working(helper, REL), "Factory should be idle >20 ticks after progress stopped");
                helper.succeed();
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 330)
    public static void factoryTurnsOffAfterBuildFinishesAndDroneSitsInOutput(GameTestHelper helper) {
        DroneFactoryBlockEntity factory = placeFactory(helper);
        fillFactory(factory);
        factory.getEnergyStorage().receiveEnergy(Integer.MAX_VALUE, false);

        int[] doneAt = {-1};
        tickLoop(helper, 320, tick -> {
            boolean done = !factory.getItems().getStackInSlot(DroneFactoryBlockEntity.OUTPUT_SLOT).isEmpty();
            if (done && doneAt[0] < 0) {
                doneAt[0] = tick;
            }
            if (doneAt[0] < 0) {
                if (tick >= 3) {
                    helper.assertTrue(working(helper, REL), "Factory should be working every tick during the build, tick " + tick);
                }
            } else if (tick == doneAt[0] + MachineWorkingState.IDLE_DELAY + 6) {
                helper.assertTrue(!working(helper, REL), "Factory should be idle after the build finished");
                helper.succeed();
            }
        });
    }

    // --- Programming Station ---

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void programmingStationWorkingDuringInstallStepThenTurnsOff(GameTestHelper helper) {
        helper.setBlock(REL, ModBlocks.PROGRAMMING_STATION.get());
        ProgrammingStationBlockEntity station = (ProgrammingStationBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(REL));
        helper.assertTrue(!working(helper, REL), "Idle Programming Station should not be working");
        station.getItems().setStackInSlot(0, DroneItem.createStack(DroneData.createNew()));
        station.getItems().setStackInSlot(ProgrammingStationBlockEntity.inputSlot(UpgradeType.PATROL), new ItemStack(ModItems.upgrade(UpgradeType.PATROL).get(), 1));
        station.getEnergyStorage().receiveEnergy(100_000, false);
        station.requestInstall(UpgradeType.PATROL);

        int[] doneAt = {-1};
        tickLoop(helper, 90, tick -> {
            boolean done = station.getInstalling() == null;
            if (done && doneAt[0] < 0) {
                doneAt[0] = tick;
                helper.assertTrue(station.getDrone().orElseThrow().upgradeCount(UpgradeType.PATROL) == 1, "Sanity: upgrade should be installed");
            }
            if (doneAt[0] < 0) {
                if (tick >= 2) {
                    helper.assertTrue(working(helper, REL), "Programming Station should be working every tick during the step, tick " + tick);
                }
            } else if (tick == doneAt[0] + 10) {
                helper.assertTrue(working(helper, REL), "Should still show working within the idle delay after finishing");
            } else if (tick == doneAt[0] + MachineWorkingState.IDLE_DELAY + 6) {
                helper.assertTrue(!working(helper, REL), "Programming Station should be idle after the step finished");
                helper.succeed();
            }
        });
    }

    // --- Charging Station ---

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void chargingStationWorkingWhileChargingThenOffAfterFullCharge(GameTestHelper helper) {
        BlockPos stationRel = new BlockPos(4, 3, 7);
        ChargingStationBlockEntity station = placeStation(helper, stationRel);
        station.getEnergyStorage().receiveEnergy(Integer.MAX_VALUE, false);
        helper.assertTrue(!working(helper, stationRel), "Idle Charging Station should not be working");

        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        int max = DroneStats.maxEnergy(drone.snapshotData());
        drone.setDroneData(drone.snapshotData().withEnergy(max - 40_000));
        forceReturning(drone, helper.absolutePos(stationRel));

        boolean[] sawWorking = {false};
        int[] doneAt = {-1};
        tickLoop(helper, 190, tick -> {
            if (working(helper, stationRel)) {
                sawWorking[0] = true;
                helper.assertTrue(!repairing(helper, stationRel), "No repair fluid, so repairing must stay false");
            }
            boolean finished = sawWorking[0] && drone.getState() == DroneState.IDLE && drone.getChargingStation() == null;
            if (finished && doneAt[0] < 0) {
                doneAt[0] = tick;
            }
            if (doneAt[0] >= 0 && tick == doneAt[0] + 50) {
                helper.assertTrue(!working(helper, stationRel), "Station should be idle after the drone finished charging");
                helper.succeed();
            }
            if (tick == 189) {
                throw new GameTestAssertException("Charging never completed, sawWorking=" + sawWorking[0] + " state=" + drone.getState());
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void chargingStationRepairingWhileHealingThenOffWhenDroneDiscarded(GameTestHelper helper) {
        BlockPos stationRel = new BlockPos(4, 3, 7);
        ChargingStationBlockEntity station = placeStation(helper, stationRel);
        station.getEnergyStorage().receiveEnergy(Integer.MAX_VALUE, false);
        station.getFluidHandler().fill(new FluidStack(Fluids.LAVA, 4000), IFluidHandler.FluidAction.EXECUTE);

        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        drone.setDroneData(drone.snapshotData().withEnergy(DroneStats.maxEnergy(drone.snapshotData())));
        drone.setHealth(1.0F);
        forceReturning(drone, helper.absolutePos(stationRel));

        boolean[] discarded = {false};
        int[] discardedAt = {-1};
        tickLoop(helper, 190, tick -> {
            if (!discarded[0]) {
                if (working(helper, stationRel) && repairing(helper, stationRel)) {
                    discarded[0] = true;
                    discardedAt[0] = tick;
                    drone.discard();
                }
                if (tick == 100) {
                    throw new GameTestAssertException("Never saw working+repairing, working=" + working(helper, stationRel)
                            + " repairing=" + repairing(helper, stationRel) + " health=" + drone.getHealth() + " state=" + drone.getState());
                }
            } else {
                int since = tick - discardedAt[0];
                if (since == MachineWorkingState.IDLE_DELAY + 30) {
                    helper.assertTrue(!working(helper, stationRel) && !repairing(helper, stationRel),
                            "Station should be idle within ~50 ticks of the drone vanishing, working=" + working(helper, stationRel)
                                    + " repairing=" + repairing(helper, stationRel));
                    helper.succeed();
                }
            }
        });
    }

    // --- Drones don't track falls (DESIGN.md 2.5) ---

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void droneFallingKeepsZeroFallDistanceAndDoesNotTrampleFarmland(GameTestHelper helper) {
        BlockPos farmRel = new BlockPos(4, 2, 4);
        helper.setBlock(farmRel, Blocks.FARMLAND);
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 6, 4));
        drone.setNoAi(true);
        double startY = drone.getY();

        boolean[] fell = {false};
        String[] logHolder = {""};
        tickLoop(helper, 60, tick -> {
            if (drone.getY() > farmRel.getY() + helper.absolutePos(BlockPos.ZERO).getY() + 1.0) {
                drone.move(net.minecraft.world.entity.MoverType.SELF, new net.minecraft.world.phys.Vec3(0, -0.4, 0));
            }
            helper.assertTrue(drone.fallDistance == 0.0F, "Drone fallDistance must stay 0, was " + drone.fallDistance + " at tick " + tick);
            helper.assertTrue(helper.getBlockState(farmRel).is(Blocks.FARMLAND), "Farmland should not be trampled by a drone");
            if (tick < 3) {
                logHolder[0] += " [t" + tick + " y=" + drone.getY() + " og=" + drone.onGround() + " below=" + helper.getLevel().getBlockState(drone.blockPosition().below()) + " ny=" + drone.noPhysics + "]";
            }
            if (drone.getY() < startY - 2.0) {
                fell[0] = true;
            }
            if (tick == 55) {
                helper.assertTrue(fell[0], logHolder[0] + "Sanity: the drone should have moved down, thresholdY=" + (farmRel.getY() + helper.absolutePos(BlockPos.ZERO).getY() + 1.0) + " alive=" + drone.isAlive() + " y=" + drone.getY() + " start=" + startY);
                helper.succeed();
            }
        });
    }

    // --- Helpers ---

    private static boolean working(GameTestHelper helper, BlockPos rel) {
        return helper.getBlockState(rel).getValue(MachineWorkingState.WORKING);
    }

    private static boolean repairing(GameTestHelper helper, BlockPos rel) {
        return helper.getBlockState(rel).getValue(ChargingStationBlock.REPAIRING);
    }

    private static DroneFactoryBlockEntity placeFactory(GameTestHelper helper) {
        helper.setBlock(REL, ModBlocks.DRONE_FACTORY.get());
        return (DroneFactoryBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(REL));
    }

    private static void fillFactory(DroneFactoryBlockEntity factory) {
        for (int i = 0; i < 4; i++) {
            factory.getItems().setStackInSlot(i, new ItemStack(ModItems.DRONE_ROTOR.get(), 1));
        }
        factory.getItems().setStackInSlot(4, new ItemStack(ModItems.SEEKER_CORE.get(), 1));
        factory.getItems().setStackInSlot(5, new ItemStack(Items.IRON_INGOT, 4));
        factory.getFluidHandler().fill(new FluidStack(Fluids.LAVA, 1000), IFluidHandler.FluidAction.EXECUTE);
    }

    private static ChargingStationBlockEntity placeStation(GameTestHelper helper, BlockPos rel) {
        helper.setBlock(rel, ModBlocks.CHARGING_STATION.get());
        return (ChargingStationBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(rel));
    }

    private static void forceReturning(DroneEntity drone, BlockPos stationAbs) {
        try {
            Method startReturning = DroneEntity.class.getDeclaredMethod("startReturning", BlockPos.class);
            startReturning.setAccessible(true);
            startReturning.invoke(drone, stationAbs);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new RuntimeException(e);
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e.getCause());
        }
    }

    /** Runs {@code step} once per tick with the ticks elapsed since the loop started (0 first), for {@code ticks} ticks. */
    private static void tickLoop(GameTestHelper helper, int ticks, IntConsumer step) {
        loop(helper, (int) helper.getTick(), ticks, step);
    }

    private static void loop(GameTestHelper helper, int start, int ticks, IntConsumer step) {
        int elapsed = (int) helper.getTick() - start;
        step.accept(elapsed);
        if (elapsed + 1 >= ticks) {
            return;
        }
        helper.runAtTickTime(helper.getTick() + 1, () -> loop(helper, start, ticks, step));
    }
}
