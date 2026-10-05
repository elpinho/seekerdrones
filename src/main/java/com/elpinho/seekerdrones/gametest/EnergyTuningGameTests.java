package com.elpinho.seekerdrones.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleSupplier;

import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.drone.DroneState;
import com.elpinho.seekerdrones.drone.DroneStats;
import com.elpinho.seekerdrones.drone.UpgradeType;
import com.elpinho.seekerdrones.registry.ModDataComponents;
import com.elpinho.seekerdrones.registry.ModEntityTypes;
import com.elpinho.seekerdrones.registry.ModItems;
import com.elpinho.seekerdrones.station.ChargingStationBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Energy tuning rework GameTests (DESIGN.md sections 4, 5.1, 5.2, 5.3, 7.2, 7.4): Energy upgrades double max energy
 * and arrive empty, the upgrade usage multiplier M, M on the drain and the return threshold, queued drones paying only
 * base hover, the Charging Station's Energy upgrades (rate and capacity), healing as a percentage of max HP, and the
 * station keeping its upgrades on the dropped item. None of them touch {@code ServerConfig}.
 */
@GameTestHolder("seekerdrones")
@PrefixGameTestTemplate(false)
public class EnergyTuningGameTests {

    private static ItemStack energyUpgrades(int count) {
        return new ItemStack(ModItems.upgrade(UpgradeType.ENERGY).get(), count);
    }

    // --- 1. Max energy (5.1) ---

    @GameTest(template = "empty", timeoutTicks = 5)
    public static void maxEnergyDoublesPerEnergyUpgrade(GameTestHelper helper) {
        DroneData base = DroneData.createNew();
        helper.assertTrue(DroneStats.maxEnergy(base) == 800_000, "No upgrades: 800000, was " + DroneStats.maxEnergy(base));
        helper.assertTrue(DroneStats.maxEnergy(base.withUpgradeCount(UpgradeType.ENERGY, 1)) == 1_600_000, "1 Energy upgrade: 1600000");
        helper.assertTrue(DroneStats.maxEnergy(base.withUpgradeCount(UpgradeType.ENERGY, 6)) == 51_200_000,
                "6 Energy upgrades: 51200000, was " + DroneStats.maxEnergy(base.withUpgradeCount(UpgradeType.ENERGY, 6)));
        helper.succeed();
    }

    // --- 2. Energy upgrades arrive empty, removal clamps (4, 7.2) ---

    @GameTest(template = "empty", timeoutTicks = 5)
    public static void energyUpgradeLeavesEnergyUnchangedAndRemovalClamps(GameTestHelper helper) {
        DroneData full = DroneData.createNew();
        helper.assertTrue(full.energy() == 800_000, "Sanity: a new drone is full, energy=" + full.energy());
        DroneData one = full.withUpgradeCount(UpgradeType.ENERGY, 1);
        helper.assertTrue(one.energy() == 800_000, "Installing leaves energy unchanged, was " + one.energy());
        DroneData topped = one.withEnergy(1_600_000);
        DroneData two = topped.withUpgradeCount(UpgradeType.ENERGY, 2);
        helper.assertTrue(two.energy() == 1_600_000, "Installing a second leaves energy unchanged, was " + two.energy());
        DroneData back = two.withEnergy(DroneStats.maxEnergy(two)).withUpgradeCount(UpgradeType.ENERGY, 1);
        helper.assertTrue(back.energy() == 1_600_000, "Removing one clamps to the new max 1600000, was " + back.energy());
        helper.succeed();
    }

    // --- 4. Usage multiplier (5.1) ---

    @GameTest(template = "empty", timeoutTicks = 5)
    public static void energyUsageMultiplierCompoundsPerUpgradeAndSkipsFirstPatrol(GameTestHelper helper) {
        helper.assertTrue(close(DroneStats.energyUsageMultiplier(DroneData.createNew()), 1.0), "No upgrades: 1");
        helper.assertTrue(close(DroneStats.energyUsageMultiplier(with(Map.of(UpgradeType.PATROL, 1))), 1.0), "1 Patrol: 1");
        helper.assertTrue(close(DroneStats.energyUsageMultiplier(with(Map.of(UpgradeType.PATROL, 2))), 1.3), "2 Patrol: 1.3");
        helper.assertTrue(close(DroneStats.energyUsageMultiplier(with(Map.of(UpgradeType.SIGHT, 1))), 1.3), "1 Sight: 1.3");
        double mixed = DroneStats.energyUsageMultiplier(with(Map.of(UpgradeType.SIGHT, 2, UpgradeType.SIREN, 1)));
        helper.assertTrue(close(mixed, Math.pow(1.3, 3)), "2 Sight + 1 Siren: 1.3^3, was " + mixed);
        helper.assertTrue(close(DroneStats.energyUsageMultiplier(with(Map.of(UpgradeType.ENERGY, 4))), 1.0), "Energy upgrades: no change");
        helper.assertTrue(close(DroneStats.energyUsageMultiplier(with(Map.of(UpgradeType.ENERGY, 3, UpgradeType.SIGHT, 1))), 1.3), "Energy + 1 Sight: 1.3");
        helper.succeed();
    }

    // --- 5. Drain is multiplied by M (5.1) ---

    @GameTest(template = "empty", timeoutTicks = 150)
    public static void hoverDrainIsMultipliedByUpgradeMultiplier(GameTestHelper helper) {
        DroneEntity plain = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(2, 3, 4));
        DroneEntity sighted = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(6, 3, 4));
        sighted.setDroneData(with(Map.of(UpgradeType.SIGHT, 2)));
        int interval = ServerConfig.get(ServerConfig.DRONE_ENERGY_DRAIN_INTERVAL);
        int hover = ServerConfig.get(ServerConfig.DRONE_HOVER_ENERGY_PER_TICK);

        List<double[]> plainEvents = new ArrayList<>();
        List<double[]> sightedEvents = new ArrayList<>();
        boolean[] done = new boolean[2];
        Runnable check = () -> {
            if (!done[0] || !done[1]) {
                return;
            }
            helper.assertTrue(plainEvents.size() >= 3 && sightedEvents.size() >= 3, "Expected at least 3 drain events each");
            double p = plainEvents.get(1)[1];
            double s = sightedEvents.get(1)[1];
            double expectedPlain = interval * hover;
            helper.assertTrue(Math.abs(p - expectedPlain) <= 2, "Plain drone should drain " + expectedPlain + " per interval, was " + p);
            helper.assertTrue(Math.abs(s - expectedPlain * 1.69) <= 3,
                    "2 Sight drone should drain about " + (expectedPlain * 1.69) + " per interval, was " + s + " (plain " + p + ")");
            helper.succeed();
        };
        int ticks = interval * 3 + 10;
        EnergyGameTests.trackEnergyDrain(helper, plain, ticks, new int[] {plain.getEnergy()}, new double[1], new Vec3[1], plainEvents, () -> {
            done[0] = true;
            check.run();
        });
        EnergyGameTests.trackEnergyDrain(helper, sighted, ticks, new int[] {sighted.getEnergy()}, new double[1], new Vec3[1], sightedEvents, () -> {
            done[1] = true;
            check.run();
        });
    }

    // --- 6. Queued drones pay only base hover (5.1, 5.3) ---

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void droneQueuedAtBusyStationPaysOnlyBaseHover(GameTestHelper helper) {
        BlockPos stationRel = new BlockPos(4, 3, 6);
        ChargingStationBlockEntity station = EnergyGameTests.placeChargingStation(helper, stationRel);
        station.getEnergyStorage().receiveEnergy(Integer.MAX_VALUE, false);
        BlockPos stationAbs = helper.absolutePos(stationRel);

        DroneEntity holder = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 5));
        // 380 000 FE missing: about 190 ticks at the default 2000 FE/tick, so the station stays busy through the measurement.
        holder.setDroneData(holder.snapshotData().withEnergy(DroneStats.maxEnergy(holder.snapshotData()) - 380_000));
        EnergyGameTests.forceReturning(holder, stationAbs);

        EnergyGameTests.pollUntil(helper, () -> holder.getState() == DroneState.CHARGING, 60, () -> {
            DroneEntity queued = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(1, 3, 6));
            DroneData data = with(Map.of(UpgradeType.SIGHT, 2, UpgradeType.SIREN, 1));
            queued.setDroneData(data);
            double multiplier = DroneStats.energyUsageMultiplier(data);
            helper.assertTrue(multiplier > 2.0, "Sanity: the queued drone should have M > 2, was " + multiplier);
            EnergyGameTests.forceReturning(queued, stationAbs);
            int interval = ServerConfig.get(ServerConfig.DRONE_ENERGY_DRAIN_INTERVAL);
            int hover = ServerConfig.get(ServerConfig.DRONE_HOVER_ENERGY_PER_TICK);

            helper.runAfterDelay(40, () -> {
                helper.assertTrue(queued.getState() == DroneState.RETURNING, "Sanity: the drone should be waiting in the queue, state=" + queued.getState());
                List<double[]> events = new ArrayList<>();
                EnergyGameTests.trackEnergyDrain(helper, queued, interval * 3 + 10, new int[] {queued.getEnergy()}, new double[1], new Vec3[1], events, () -> {
                    helper.assertTrue(holder.getState() == DroneState.CHARGING, "Sanity: the station should still be busy, holder=" + holder.getState());
                    helper.assertTrue(queued.getState() == DroneState.RETURNING, "Sanity: still queued, state=" + queued.getState());
                    helper.assertTrue(events.size() >= 3, "Expected at least 3 drain events, got " + events.size());
                    double expected = (double) interval * hover;
                    for (int i = 1; i <= 2; i++) {
                        double delta = events.get(i)[1];
                        helper.assertTrue(Math.abs(delta - expected) <= 45,
                                "A queued drone should pay base hover only (" + expected + " FE per interval, not x" + multiplier + "), event " + i + " was " + delta
                                        + " (flew " + events.get(i)[0] + " blocks)");
                    }
                    helper.succeed();
                });
            });
        }, () -> helper.fail("The holder never docked, state=" + holder.getState()));
    }

    // --- 7. Return threshold (5.2) ---

    @GameTest(template = "empty", timeoutTicks = 5)
    public static void returnThresholdFormulaIncludesMultiplierHoverTermAndUnmultipliedBuffer(GameTestHelper helper) {
        double perBlock = ServerConfig.get(ServerConfig.DRONE_ENERGY_PER_BLOCK) + ServerConfig.get(ServerConfig.DRONE_HOVER_ENERGY_PER_TICK)
                / ServerConfig.get(ServerConfig.DRONE_CRUISE_SPEED);
        double margin = ServerConfig.get(ServerConfig.DRONE_RETURN_SAFETY_MARGIN);
        double buffer = (double) ServerConfig.get(ServerConfig.DRONE_RETURN_WAIT_BUFFER) * ServerConfig.get(ServerConfig.DRONE_HOVER_ENERGY_PER_TICK);
        helper.assertTrue(buffer > 0, "Sanity: the default wait buffer is non-zero");
        DroneData plain = DroneData.createNew();
        DroneData sighted = with(Map.of(UpgradeType.SIGHT, 2));
        double m = DroneStats.energyUsageMultiplier(sighted);
        for (double distance : new double[] {0, 10, 50}) {
            double expectedPlain = distance * perBlock * margin + buffer;
            double expectedSighted = m * distance * perBlock * margin + buffer;
            helper.assertTrue(Math.abs(DroneStats.returnThreshold(plain, distance) - expectedPlain) < 0.01,
                    "Plain threshold at " + distance + " should be " + expectedPlain + ", was " + DroneStats.returnThreshold(plain, distance));
            helper.assertTrue(Math.abs(DroneStats.returnThreshold(sighted, distance) - expectedSighted) < 0.01,
                    "2 Sight threshold at " + distance + " should be " + expectedSighted + ", was " + DroneStats.returnThreshold(sighted, distance));
        }
        helper.assertTrue(Math.abs(DroneStats.returnThreshold(sighted, 0) - buffer) < 0.01, "At distance 0 only the unmultiplied buffer is left");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void upgradeMultiplierRaisesTheEnergyAtWhichADroneReturns(GameTestHelper helper) {
        BlockPos stationRel = new BlockPos(4, 3, 8);
        EnergyGameTests.placeChargingStation(helper, stationRel).getEnergyStorage().receiveEnergy(Integer.MAX_VALUE, false);
        BlockPos stationAbs = helper.absolutePos(stationRel);

        DroneEntity plain = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(2, 3, 1));
        DroneEntity sighted = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(6, 3, 1));
        DroneData sightedData = with(Map.of(UpgradeType.SIGHT, 2));
        sighted.setDroneData(sightedData);

        double distance = sighted.position().distanceTo(ChargingStationBlockEntity.dockPosition(stationAbs));
        double plainThreshold = DroneStats.returnThreshold(DroneData.createNew(), distance);
        double sightedThreshold = DroneStats.returnThreshold(sightedData, distance);
        helper.assertTrue(sightedThreshold - plainThreshold > 600, "Sanity: the thresholds should be well apart, gap=" + (sightedThreshold - plainThreshold));
        int energy = (int) ((plainThreshold + sightedThreshold) / 2);
        plain.setDroneData(plain.snapshotData().withEnergy(energy));
        sighted.setDroneData(sighted.snapshotData().withEnergy(energy));

        EnergyGameTests.pollUntil(helper, () -> sighted.getState() == DroneState.RETURNING || sighted.getState() == DroneState.CHARGING, 40, () -> {
            helper.assertTrue(plain.getState() != DroneState.RETURNING && plain.getState() != DroneState.CHARGING,
                    "The plain drone at the same energy is above its own threshold and should not return, state=" + plain.getState()
                            + " energy=" + plain.getEnergy() + " threshold=" + plainThreshold);
            helper.succeed();
        }, () -> helper.fail("The 2 Sight drone (energy " + energy + " <= threshold " + sightedThreshold + ") never started returning, state=" + sighted.getState()
                + " energy=" + sighted.getEnergy()));
    }

    // --- 8. Charging Station upgrades (7.4) ---

    @GameTest(template = "empty", timeoutTicks = 5)
    public static void chargingStationRateAndCapacityDoublePerEnergyUpgrade(GameTestHelper helper) {
        ChargingStationBlockEntity station = EnergyGameTests.placeChargingStation(helper, new BlockPos(4, 3, 4));
        helper.assertTrue(station.getChargeRate() == 2000, "No upgrades: 2000 FE/t, was " + station.getChargeRate());
        helper.assertTrue(station.getEnergyStorage().getMaxEnergyStored() == 400_000, "No upgrades: 400000 capacity");
        station.getUpgrades().setStackInSlot(0, energyUpgrades(2));
        helper.assertTrue(station.getEnergyUpgrades() == 2, "Sanity: 2 upgrades installed");
        helper.assertTrue(station.getChargeRate() == 8000, "2 upgrades: 8000 FE/t, was " + station.getChargeRate());
        helper.assertTrue(station.getEnergyStorage().getMaxEnergyStored() == 1_600_000,
                "2 upgrades: 1600000 capacity, was " + station.getEnergyStorage().getMaxEnergyStored());
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 5)
    public static void removingStationUpgradesClampsStoredEnergy(GameTestHelper helper) {
        ChargingStationBlockEntity station = EnergyGameTests.placeChargingStation(helper, new BlockPos(4, 3, 4));
        station.getUpgrades().setStackInSlot(0, energyUpgrades(2));
        station.getEnergyStorage().receiveEnergy(Integer.MAX_VALUE, false);
        helper.assertTrue(station.getEnergy() == 1_600_000, "Sanity: filled to the upgraded capacity, was " + station.getEnergy());
        station.getUpgrades().setStackInSlot(0, energyUpgrades(1));
        helper.assertTrue(station.getEnergy() == 800_000, "Going to 1 upgrade clamps to 800000, was " + station.getEnergy());
        station.getUpgrades().setStackInSlot(0, ItemStack.EMPTY);
        helper.assertTrue(station.getEnergy() == 400_000, "Removing all clamps to 400000, was " + station.getEnergy());
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void dockedDroneTakesAtMostTheBaseChargeRatePerTick(GameTestHelper helper) {
        dockedRateTest(helper, 0, 2000);
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void dockedDroneTakesTheUpgradedChargeRatePerTick(GameTestHelper helper) {
        dockedRateTest(helper, 2, 8000);
    }

    private static void dockedRateTest(GameTestHelper helper, int upgrades, int expectedRate) {
        BlockPos stationRel = new BlockPos(4, 3, 6);
        ChargingStationBlockEntity station = EnergyGameTests.placeChargingStation(helper, stationRel);
        if (upgrades > 0) {
            station.getUpgrades().setStackInSlot(0, energyUpgrades(upgrades));
        }
        station.getEnergyStorage().receiveEnergy(Integer.MAX_VALUE, false);
        helper.assertTrue(station.getChargeRate() == expectedRate, "Sanity: station rate " + station.getChargeRate());
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 5));
        drone.setDroneData(drone.snapshotData().withEnergy(DroneStats.maxEnergy(drone.snapshotData()) - 300_000));
        EnergyGameTests.forceReturning(drone, helper.absolutePos(stationRel));

        EnergyGameTests.pollUntil(helper, () -> drone.getState() == DroneState.CHARGING, 40, () -> {
            List<Double> deltas = new ArrayList<>();
            double[] prev = {drone.getEnergy()};
            sample(helper, 8, () -> {
                double now = drone.getEnergy();
                double delta = now - prev[0];
                prev[0] = now;
                return delta;
            }, deltas, () -> {
                double max = deltas.stream().mapToDouble(Double::doubleValue).max().orElse(0);
                helper.assertTrue(max == expectedRate, "The drone should take exactly " + expectedRate + " FE per tick (never more), per-tick gains: " + deltas);
                helper.succeed();
            });
        }, () -> helper.fail("Drone never docked, state=" + drone.getState()));
    }

    // --- 9. Healing is a percentage of max HP per second (5.3) ---

    @GameTest(template = "empty", timeoutTicks = 150)
    public static void dockedDroneHealsTenPercentOfMaxHpPerSecond(GameTestHelper helper) {
        healingTest(helper, 0);
    }

    @GameTest(template = "empty", timeoutTicks = 150)
    public static void healingIsUnaffectedByStationUpgrades(GameTestHelper helper) {
        healingTest(helper, 3);
    }

    private static void healingTest(GameTestHelper helper, int upgrades) {
        BlockPos stationRel = new BlockPos(4, 3, 6);
        ChargingStationBlockEntity station = EnergyGameTests.placeChargingStation(helper, stationRel);
        if (upgrades > 0) {
            station.getUpgrades().setStackInSlot(0, energyUpgrades(upgrades));
        }
        station.getEnergyStorage().receiveEnergy(Integer.MAX_VALUE, false);
        station.getFluidHandler().fill(new FluidStack(Fluids.LAVA, ServerConfig.get(ServerConfig.CHARGING_STATION_TANK_CAPACITY)), IFluidHandler.FluidAction.EXECUTE);
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 5));
        drone.setDroneData(drone.snapshotData().withEnergy(DroneStats.maxEnergy(drone.snapshotData())));
        float maxHp = drone.getMaxHealth();
        drone.setHealth(maxHp / 2);
        EnergyGameTests.forceReturning(drone, helper.absolutePos(stationRel));

        EnergyGameTests.pollUntil(helper, () -> drone.getState() == DroneState.CHARGING, 40, () -> {
            float healthStart = drone.getHealth();
            int fluidStart = station.getFluid().getAmount();
            int ticks = 20;
            sample(helper, ticks, () -> 0, new ArrayList<>(), () -> {
                double gained = drone.getHealth() - healthStart;
                double expected = ticks * maxHp * 0.005;
                helper.assertTrue(Math.abs(gained - expected) <= expected * 0.06,
                        "Over " + ticks + " ticks the drone should heal about " + expected + " HP (10% of " + maxHp + " per second), healed " + gained);
                int perHp = ServerConfig.get(ServerConfig.CHARGING_STATION_REPAIR_FLUID_PER_HP);
                double fluidUsed = fluidStart - station.getFluid().getAmount();
                helper.assertTrue(Math.abs(fluidUsed - gained * perHp) <= 2 + perHp * 0.05,
                        "Fluid use should be proportional (" + perHp + " mB per HP): used " + fluidUsed + " for " + gained + " HP");
                helper.succeed();
            });
        }, () -> helper.fail("Drone never docked, state=" + drone.getState()));
    }

    // --- 10. Breaking keeps the upgrades on the item (7.4) ---

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void breakingStationKeepsEnergyUpgradesOnItemAndPlacingRestoresThem(GameTestHelper helper) {
        BlockPos rel = new BlockPos(4, 3, 4);
        ChargingStationBlockEntity station = EnergyGameTests.placeChargingStation(helper, rel);
        station.getUpgrades().setStackInSlot(0, energyUpgrades(3));
        BlockPos abs = helper.absolutePos(rel);
        Vec3 center = Vec3.atCenterOf(abs);

        helper.getLevel().destroyBlock(abs, true);
        ItemStack dropped = null;
        for (ItemEntity entity : helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(center, center).inflate(3.0))) {
            if (entity.getItem().getItem() == ModItems.CHARGING_STATION.get()) {
                dropped = entity.getItem();
            }
        }
        helper.assertTrue(dropped != null, "The Charging Station item should drop");
        Map<UpgradeType, Integer> saved = dropped.get(ModDataComponents.MACHINE_UPGRADES);
        helper.assertTrue(saved != null && saved.equals(Map.of(UpgradeType.ENERGY, 3)),
                "The dropped item should carry MACHINE_UPGRADES {energy:3}, was " + saved);

        BlockPos otherRel = new BlockPos(7, 3, 7);
        ChargingStationBlockEntity placed = EnergyGameTests.placeChargingStation(helper, otherRel);
        helper.assertTrue(placed.getEnergyUpgrades() == 0, "Sanity: a fresh station has no upgrades");
        placed.applyComponentsFromItemStack(dropped);
        helper.assertTrue(placed.getEnergyUpgrades() == 3, "Placing the item should restore 3 upgrades, was " + placed.getEnergyUpgrades());
        helper.assertTrue(placed.getChargeRate() == 16_000, "Restored upgrades should apply: rate 16000, was " + placed.getChargeRate());
        helper.succeed();
    }

    // --- Helpers ---

    private static DroneData with(Map<UpgradeType, Integer> upgrades) {
        return EnergyGameTests.dataWithUpgrades(DroneData.createNew(), upgrades);
    }

    private static boolean close(double a, double b) {
        return Math.abs(a - b) < 1.0E-9;
    }

    /** Calls {@code supplier} once per tick for {@code ticks} ticks, collecting the values, then runs {@code onDone}. */
    private static void sample(GameTestHelper helper, int ticks, DoubleSupplier supplier, List<Double> out, Runnable onDone) {
        helper.runAtTickTime(helper.getTick() + 1, () -> {
            out.add(supplier.getAsDouble());
            if (ticks <= 1) {
                onDone.run();
            } else {
                sample(helper, ticks - 1, supplier, out, onDone);
            }
        });
    }
}
