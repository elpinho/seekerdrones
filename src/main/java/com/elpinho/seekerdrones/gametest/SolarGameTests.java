package com.elpinho.seekerdrones.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.drone.DroneItem;
import com.elpinho.seekerdrones.drone.DroneState;
import com.elpinho.seekerdrones.drone.DroneStats;
import com.elpinho.seekerdrones.drone.UpgradeType;
import com.elpinho.seekerdrones.energy.EnergyFormat;
import com.elpinho.seekerdrones.network.DroneStatusPayload;
import com.elpinho.seekerdrones.network.FpvStatusPayload;
import com.elpinho.seekerdrones.programming.ProgrammingStationBlockEntity;
import com.elpinho.seekerdrones.programming.ProgrammingStationMenu;
import com.elpinho.seekerdrones.registry.ModBlocks;
import com.elpinho.seekerdrones.registry.ModEntityTypes;
import com.elpinho.seekerdrones.registry.ModItems;
import com.elpinho.seekerdrones.station.ChargingStationBlockEntity;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Solar upgrade GameTests (DESIGN.md sections 4, 5.1, 8.4).
 *
 * <p>The tests that depend on the time of day or the weather change global level state, so each kind runs in its own
 * batch ({@code solar_day}, {@code solar_night}, {@code solar_rain}, {@code solar_thunder}); batches run one after
 * another. The time and weather are put back afterwards.
 */
@GameTestHolder("seekerdrones")
@PrefixGameTestTemplate(false)
public class SolarGameTests {
    private static final BlockPos DRONE_REL = new BlockPos(4, 3, 4);
    private static final String DAY = "solar_day";
    private static final String NIGHT = "solar_night";
    /** Ticks to wait after changing the time or weather, so the level refreshes its sky darkness. */
    private static final int SETTLE = 3;

    // --- 1. Output ---

    @GameTest(template = "empty", timeoutTicks = 20, skyAccess = true, batch = DAY)
    public static void solarOutputCompoundsPerUpgradeInDaylight(GameTestHelper helper) {
        helper.setDayTime(6000);
        helper.runAfterDelay(SETTLE, () -> {
            BlockPos pos = helper.absolutePos(DRONE_REL);
            double[] expected = {16, 32, 64, 128};
            for (int count = 1; count <= 4; count++) {
                double actual = DroneStats.solarPerTick(solarData(count), helper.getLevel(), pos);
                helper.assertTrue(Math.abs(actual - expected[count - 1]) < 1e-6, count + " Solar upgrades should give " + expected[count - 1] + " FE/t, was " + actual);
            }
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 20, skyAccess = true, batch = DAY)
    public static void noSolarUpgradeGeneratesNothingInDaylight(GameTestHelper helper) {
        helper.setDayTime(6000);
        helper.runAfterDelay(SETTLE, () -> {
            double actual = DroneStats.solarPerTick(DroneData.createNew(), helper.getLevel(), helper.absolutePos(DRONE_REL));
            helper.assertTrue(actual == 0, "A drone without Solar should generate 0, was " + actual);
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 20, skyAccess = true, batch = DAY)
    public static void solarIsZeroUnderARoof(GameTestHelper helper) {
        helper.setDayTime(6000);
        roof(helper);
        helper.runAfterDelay(SETTLE, () -> {
            double open = DroneStats.solarPerTick(solarData(4), helper.getLevel(), helper.absolutePos(new BlockPos(0, 3, 0)));
            helper.assertTrue(open > 0, "Sanity: beside the roof there is sky access, was " + open);
            double actual = DroneStats.solarPerTick(solarData(4), helper.getLevel(), helper.absolutePos(DRONE_REL));
            helper.assertTrue(actual == 0, "Under a roof Solar should generate 0, was " + actual);
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 20, skyAccess = true, batch = DAY)
    public static void solarIsZeroInDimensionWithoutSkylight(GameTestHelper helper) {
        helper.setDayTime(6000);
        ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
        helper.assertTrue(nether != null, "Sanity: the Nether should be loaded on the GameTest server");
        helper.assertFalse(nether.dimensionType().hasSkyLight(), "Sanity: the Nether has no skylight");
        helper.runAfterDelay(SETTLE, () -> {
            double actual = DroneStats.solarPerTick(solarData(4), nether, new BlockPos(0, 100, 0));
            helper.assertTrue(actual == 0, "A dimension without skylight should give 0, was " + actual);
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 30, skyAccess = true, batch = NIGHT)
    public static void solarIsZeroAtNight(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long original = level.getDayTime();
        helper.setDayTime(18000);
        helper.runAfterDelay(SETTLE, () -> {
            try {
                helper.assertTrue(!level.isDay(), "Sanity: 18000 should be night");
                helper.assertTrue(level.canSeeSky(helper.absolutePos(DRONE_REL)), "Sanity: the drone position has sky access");
                double actual = DroneStats.solarPerTick(solarData(4), level, helper.absolutePos(DRONE_REL));
                helper.assertTrue(actual == 0, "At night Solar should generate 0, was " + actual);
            } finally {
                level.setDayTime(original);
            }
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 40, skyAccess = true, batch = "solar_rain")
    public static void rainScalesSolarOutputByRainMultiplier(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setDayTime(6000);
        helper.setBiome(Biomes.PLAINS);
        level.setRainLevel(1.0F);
        level.setThunderLevel(0.0F);
        helper.runAfterDelay(SETTLE, () -> {
            try {
                BlockPos pos = helper.absolutePos(DRONE_REL);
                helper.assertTrue(level.isRainingAt(pos), "Sanity: it should be raining at the drone (raining=" + level.isRaining() + ", sky=" + level.canSeeSky(pos) + ")");
                helper.assertTrue(!level.isThundering(), "Sanity: not thundering");
                helper.assertTrue(level.isDay(), "Sanity: still day in the rain, skyDarken=" + level.getSkyDarken());
                double expected = ServerConfig.get(ServerConfig.UPGRADES_SOLAR_ENERGY_PER_TICK) * ServerConfig.get(ServerConfig.UPGRADES_SOLAR_RAIN_MULTIPLIER);
                double actual = DroneStats.solarPerTick(solarData(1), level, pos);
                helper.assertTrue(Math.abs(actual - expected) < 1e-6, "Rain should give 16 x 0.4 = " + expected + ", was " + actual);
            } finally {
                level.setRainLevel(0.0F);
                level.setThunderLevel(0.0F);
            }
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 40, skyAccess = true, batch = "solar_thunder")
    public static void thunderScalesSolarOutputByThunderMultiplier(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setDayTime(6000);
        helper.setBiome(Biomes.PLAINS);
        level.setRainLevel(1.0F);
        level.setThunderLevel(1.0F);
        helper.runAfterDelay(SETTLE, () -> {
            try {
                BlockPos pos = helper.absolutePos(DRONE_REL);
                helper.assertTrue(level.isThundering(), "Sanity: should be thundering");
                double expected = ServerConfig.get(ServerConfig.UPGRADES_SOLAR_ENERGY_PER_TICK) * ServerConfig.get(ServerConfig.UPGRADES_SOLAR_THUNDER_MULTIPLIER);
                double actual = DroneStats.solarPerTick(solarData(1), level, pos);
                helper.assertTrue(Math.abs(actual - expected) < 1e-6, "Thunder should give 16 x 0.2 = " + expected + ", was " + actual
                        + " (isDay=" + level.isDay() + ", skyDarken=" + level.getSkyDarken() + ")");
            } finally {
                level.setRainLevel(0.0F);
                level.setThunderLevel(0.0F);
            }
            helper.succeed();
        });
    }

    // --- 2. Energy ---

    @GameTest(template = "empty", timeoutTicks = 140, skyAccess = true, batch = DAY)
    public static void airborneDroneWithOneSolarUpgradeGainsEnergyInDaylight(GameTestHelper helper) {
        // 1 upgrade = 16 FE/t against 8 FE/t of hover: net +8 FE/t.
        checkEnergyEvent(helper, 1, false);
    }

    @GameTest(template = "empty", timeoutTicks = 140, skyAccess = true, batch = DAY)
    public static void airborneDroneWithFourSolarUpgradesGainsEnergyInDaylight(GameTestHelper helper) {
        checkEnergyEvent(helper, 4, false);
    }

    @GameTest(template = "empty", timeoutTicks = 140, skyAccess = true, batch = DAY)
    public static void droneUnderARoofWithSolarOnlyLosesEnergyAndReportsZeroSolar(GameTestHelper helper) {
        checkEnergyEvent(helper, 4, true);
    }

    private static void checkEnergyEvent(GameTestHelper helper, int upgrades, boolean roofed) {
        helper.setDayTime(6000);
        if (roofed) {
            roof(helper);
        }
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), DRONE_REL);
        DroneData data = solarData(upgrades);
        int start = DroneStats.maxEnergy(data) / 2;
        drone.setDroneData(data.withEnergy(start));
        int interval = ServerConfig.get(ServerConfig.DRONE_ENERGY_DRAIN_INTERVAL);
        int hover = ServerConfig.get(ServerConfig.DRONE_HOVER_ENERGY_PER_TICK);
        double mult = DroneStats.energyUsageMultiplier(data);
        helper.runAfterDelay(SETTLE, () -> {
            int[] prev = {drone.getEnergy()};
            List<double[]> events = new ArrayList<>();
            EnergyGameTests.trackEnergyDrain(helper, drone, interval * 3 + 10, prev, new double[] {0}, new Vec3[] {null}, events, () -> {
                helper.assertTrue(events.size() >= 3, "Expected at least 3 drain events, got " + events.size());
                double solar = roofed ? 0 : 16 * Math.pow(2, upgrades - 1);
                helper.assertTrue(Math.abs(drone.getSolarRate() - solar) < 1e-6, "getSolarRate() should be " + solar + ", was " + drone.getSolarRate());
                double expectedLoss = interval * (hover * mult - solar);
                double loss = events.get(1)[1];
                helper.assertTrue(Math.abs(loss - expectedLoss) <= 2,
                        "Energy lost per drain should be interval x (usage - solar) = " + expectedLoss + ", was " + loss);
                if (roofed) {
                    helper.assertTrue(loss > 0, "Without sky access energy should only fall, change=" + (-loss));
                } else {
                    helper.assertTrue(loss < 0 && drone.getEnergy() > start, "With enough Solar energy should rise, energy=" + drone.getEnergy() + " start=" + start);
                }
                helper.succeed();
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 140, skyAccess = true, batch = DAY)
    public static void solarEnergyIsClampedToMaxEnergy(GameTestHelper helper) {
        helper.setDayTime(6000);
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), DRONE_REL);
        DroneData data = solarData(4);
        int max = DroneStats.maxEnergy(data);
        drone.setDroneData(data.withEnergy(max - 100));
        int[] ticksAtMax = {0};
        helper.onEachTick(() -> {
            int energy = drone.getEnergy();
            helper.assertTrue(energy <= max, "Energy must never exceed the max, was " + energy + "/" + max);
            if (energy == max) {
                ticksAtMax[0]++;
            }
            if (ticksAtMax[0] >= 60) { // three drain intervals at the cap
                helper.succeed();
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 200, skyAccess = true, batch = DAY)
    public static void dockedDroneGetsNoSolar(GameTestHelper helper) {
        helper.setDayTime(6000);
        BlockPos stationRel = new BlockPos(4, 3, 7);
        EnergyGameTests.placeChargingStation(helper, stationRel); // no FE: a docked drone stays put
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), DRONE_REL);
        DroneData data = solarData(4);
        drone.setDroneData(data.withEnergy(DroneStats.maxEnergy(data) - 50_000));
        EnergyGameTests.forceReturning(drone, helper.absolutePos(stationRel));
        EnergyGameTests.pollUntil(helper, () -> drone.getState() == DroneState.CHARGING, 100, () -> helper.runAfterDelay(3, () -> {
            int energy = drone.getEnergy();
            helper.runAfterDelay(60, () -> {
                helper.assertTrue(drone.getState() == DroneState.CHARGING, "Sanity: still docked, state=" + drone.getState());
                helper.assertTrue(drone.getEnergy() == energy, "A docked drone should get no Solar, energy went from " + energy + " to " + drone.getEnergy());
                helper.succeed();
            });
        }), () -> helper.fail("Drone never docked, state=" + drone.getState()));
    }

    // --- 3. Return threshold uses the gross cost ---

    @GameTest(template = "empty", timeoutTicks = 20, batch = DAY)
    public static void returnThresholdIsTheSameWithAndWithoutSolar(GameTestHelper helper) {
        for (double distance : new double[] {5, 40, 200}) {
            double without = DroneStats.returnThreshold(DroneData.createNew(), distance);
            double with = DroneStats.returnThreshold(solarData(4), distance);
            helper.assertTrue(without == with, "Solar must not change the return threshold at " + distance + " blocks: " + without + " vs " + with);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 60, skyAccess = true, batch = DAY)
    public static void solarDroneUnderARoofStillReturnsAtTheGrossThreshold(GameTestHelper helper) {
        helper.setDayTime(6000);
        roof(helper);
        BlockPos stationRel = new BlockPos(4, 3, 7);
        ChargingStationBlockEntity station = EnergyGameTests.placeChargingStation(helper, stationRel);
        station.getEnergyStorage().receiveEnergy(Integer.MAX_VALUE, false);
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), DRONE_REL);
        double threshold = DroneStats.returnThreshold(solarData(4), drone.position().distanceTo(ChargingStationBlockEntity.dockPosition(helper.absolutePos(stationRel))));
        drone.setDroneData(solarData(4).withEnergy(Math.max(1, (int) threshold - 2)));
        EnergyGameTests.pollUntil(helper, () -> drone.getState() == DroneState.RETURNING || drone.getState() == DroneState.CHARGING, 40, helper::succeed,
                () -> helper.fail("A drone below the threshold should return, state=" + drone.getState() + " energy=" + drone.getEnergy() + " threshold=" + threshold));
    }

    // --- 4. Status payloads ---

    @GameTest(template = "empty", timeoutTicks = 100, skyAccess = true, batch = DAY)
    public static void getSolarRateReflectsTheLastDrainAndPayloadsCarryIt(GameTestHelper helper) {
        helper.setDayTime(6000);
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), DRONE_REL);
        helper.assertTrue(drone.getSolarRate() == 0, "Before any drain the rate is 0, was " + drone.getSolarRate());
        drone.setDroneData(solarData(2).withEnergy(DroneStats.maxEnergy(solarData(2)) / 2));
        EnergyGameTests.pollUntil(helper, () -> drone.getSolarRate() > 0, 60, () -> {
            helper.assertTrue(Math.abs(drone.getSolarRate() - 32) < 1e-6, "2 upgrades should report 32 FE/t, was " + drone.getSolarRate());
            DroneStatusPayload payload = DroneStatusPayload.of(drone, false);
            helper.assertTrue(payload.solarRate() == drone.getSolarRate(), "DroneStatusPayload.of should carry getSolarRate(), was " + payload.solarRate());
            helper.assertTrue(DroneStatusPayload.ofItem(drone.snapshotData()).solarRate() == 0, "ofItem should report 0");

            ByteBuf buf = Unpooled.buffer();
            DroneStatusPayload.STREAM_CODEC.encode(buf, payload);
            DroneStatusPayload decoded = DroneStatusPayload.STREAM_CODEC.decode(buf);
            helper.assertTrue(decoded.solarRate() == payload.solarRate() && buf.readableBytes() == 0,
                    "DroneStatusPayload codec should round trip solarRate, got " + decoded.solarRate());

            FpvStatusPayload fpv = new FpvStatusPayload(1000, 2000, 8.0F, (float) drone.getSolarRate());
            ByteBuf fpvBuf = Unpooled.buffer();
            FpvStatusPayload.STREAM_CODEC.encode(fpvBuf, fpv);
            FpvStatusPayload fpvDecoded = FpvStatusPayload.STREAM_CODEC.decode(fpvBuf);
            helper.assertTrue(fpvDecoded.equals(fpv) && fpvDecoded.solarRate() == 32.0F && fpvBuf.readableBytes() == 0,
                    "FpvStatusPayload codec should round trip solarRate, got " + fpvDecoded);
            helper.succeed();
        }, () -> helper.fail("Solar rate never became positive, was " + drone.getSolarRate()));
    }

    @GameTest(template = "empty", timeoutTicks = 100, skyAccess = true, batch = DAY)
    public static void solarRateDropsToZeroAfterLosingSkyAccess(GameTestHelper helper) {
        helper.setDayTime(6000);
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), DRONE_REL);
        drone.setDroneData(solarData(1).withEnergy(DroneStats.maxEnergy(solarData(1)) / 2));
        EnergyGameTests.pollUntil(helper, () -> drone.getSolarRate() > 0, 60, () -> {
            roof(helper);
            EnergyGameTests.pollUntil(helper, () -> drone.getSolarRate() == 0, 60, helper::succeed,
                    () -> helper.fail("getSolarRate() should follow the last drain down to 0 under a roof, was " + drone.getSolarRate()));
        }, () -> helper.fail("Solar rate never became positive"));
    }

    // --- 5. Energy factor, slot limits ---

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void solarDoesNotChangeEnergyUsageMultiplier(GameTestHelper helper) {
        helper.assertTrue(UpgradeType.SOLAR.energyFactor() == 1.0, "Default Solar energy factor is 1.0, was " + UpgradeType.SOLAR.energyFactor());
        helper.assertTrue(DroneStats.energyUsageMultiplier(solarData(4)) == 1.0, "4 Solar upgrades alone should leave the multiplier at 1");
        DroneData sight = DroneData.createNew().withUpgradeCount(UpgradeType.SIGHT, 2);
        helper.assertTrue(DroneStats.energyUsageMultiplier(sight) == DroneStats.energyUsageMultiplier(sight.withUpgradeCount(UpgradeType.SOLAR, 4)),
                "Adding Solar should not change another upgrade's multiplier");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void solarCountsTowardTheTotalSlotLimitAndHasPerTypeCapOfFour(GameTestHelper helper) {
        helper.assertValueEqual(UpgradeType.SOLAR.maxCount(), 4, "Solar per-type cap");
        helper.assertTrue(DroneStats.withinUpgradeLimits(solarData(4).upgrades()), "4 Solar is within limits");
        helper.assertFalse(DroneStats.withinUpgradeLimits(solarData(5).upgrades()), "5 Solar exceeds the per-type cap");
        DroneData full = fillOthers(DroneData.createNew(), ServerConfig.get(ServerConfig.UPGRADES_TOTAL_SLOTS));
        helper.assertValueEqual(DroneStats.totalUpgrades(full.upgrades()), ServerConfig.get(ServerConfig.UPGRADES_TOTAL_SLOTS), "Fixture should fill the total limit");
        helper.assertFalse(DroneStats.withinUpgradeLimits(full.withUpgradeCount(UpgradeType.SOLAR, 1).upgrades()), "Solar counts toward the total slot limit");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void programmingStationInstallsSolarUntilTheTypeCap(GameTestHelper helper) {
        ProgrammingStationBlockEntity station = placeProgramming(helper);
        station.getItems().setStackInSlot(0, DroneItem.createStack(DroneData.createNew().withUpgradeCount(UpgradeType.SOLAR, 4)));
        station.getItems().setStackInSlot(ProgrammingStationBlockEntity.inputSlot(UpgradeType.SOLAR), new ItemStack(ModItems.upgrade(UpgradeType.SOLAR).get(), 1));
        station.getEnergyStorage().receiveEnergy(500_000, false);
        station.requestInstall(UpgradeType.SOLAR);
        helper.assertTrue(station.getInstalling() == null, "A fifth Solar upgrade should be refused");

        station.getItems().setStackInSlot(0, DroneItem.createStack(DroneData.createNew().withUpgradeCount(UpgradeType.SOLAR, 3)));
        station.requestInstall(UpgradeType.SOLAR);
        helper.assertTrue(station.getInstalling() == UpgradeType.SOLAR, "The fourth Solar upgrade should be accepted");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void programmingStationRefusesSolarPastTheTotalSlotLimit(GameTestHelper helper) {
        ProgrammingStationBlockEntity station = placeProgramming(helper);
        int total = ServerConfig.get(ServerConfig.UPGRADES_TOTAL_SLOTS);
        station.getItems().setStackInSlot(ProgrammingStationBlockEntity.inputSlot(UpgradeType.SOLAR), new ItemStack(ModItems.upgrade(UpgradeType.SOLAR).get(), 1));
        station.getEnergyStorage().receiveEnergy(500_000, false);

        station.getItems().setStackInSlot(0, DroneItem.createStack(fillOthers(DroneData.createNew(), total)));
        station.requestInstall(UpgradeType.SOLAR);
        helper.assertTrue(station.getInstalling() == null, "Solar past the total slot limit should be refused");

        station.getItems().setStackInSlot(0, DroneItem.createStack(fillOthers(DroneData.createNew(), total - 1)));
        station.requestInstall(UpgradeType.SOLAR);
        helper.assertTrue(station.getInstalling() == UpgradeType.SOLAR, "Solar with exactly one slot left should be accepted");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void upgradeCommandEnforcesTheSolarCap(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), DRONE_REL);
        runCommand(helper, "seekerdrones upgrade set solar 5 @e[type=seekerdrones:drone,distance=..3]");
        helper.assertValueEqual(drone.snapshotData().upgradeCount(UpgradeType.SOLAR), 0, "Setting 5 Solar should be refused");
        runCommand(helper, "seekerdrones upgrade set solar 4 @e[type=seekerdrones:drone,distance=..3]");
        helper.assertValueEqual(drone.snapshotData().upgradeCount(UpgradeType.SOLAR), 4, "Setting 4 Solar should work");
        helper.succeed();
    }

    // --- 6. Net rate formatting ---

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void netRateShowsMinusWhenLosingAndPlusWhenGaining(GameTestHelper helper) {
        checkNetRate(helper, EnergyFormat.netRate(10, 3), "screen.seekerdrones.drone_status.energy_rate", EnergyFormat.rate(7));
        checkNetRate(helper, EnergyFormat.netRate(3, 10), "screen.seekerdrones.drone_status.energy_rate.gain", EnergyFormat.rate(7));
        checkNetRate(helper, EnergyFormat.netRate(8, 0), "screen.seekerdrones.drone_status.energy_rate", EnergyFormat.rate(8));
        checkNetRate(helper, EnergyFormat.netRate(8, 8), "screen.seekerdrones.drone_status.energy_rate", EnergyFormat.rate(0));
        helper.succeed();
    }

    private static void checkNetRate(GameTestHelper helper, Component component, String key, String arg) {
        helper.assertTrue(component.getContents() instanceof TranslatableContents, "Expected a translatable component, was " + component);
        TranslatableContents contents = (TranslatableContents) component.getContents();
        helper.assertTrue(contents.getKey().equals(key), "Expected key " + key + ", was " + contents.getKey());
        helper.assertTrue(contents.getArgs().length == 1 && contents.getArgs()[0].equals(arg), "Expected argument " + arg + ", was " + java.util.Arrays.toString(contents.getArgs()));
    }

    // --- 7. Programming Station menu ---

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void menuHasAnInputSlotForEveryUpgradeType(GameTestHelper helper) {
        ProgrammingStationBlockEntity station = placeProgramming(helper);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        ProgrammingStationMenu menu = (ProgrammingStationMenu) station.createMenu(1, player.getInventory(), player);
        helper.assertValueEqual(ProgrammingStationBlockEntity.INPUT_SLOTS, UpgradeType.values().length, "INPUT_SLOTS");
        for (UpgradeType type : UpgradeType.values()) {
            int index = ProgrammingStationBlockEntity.inputSlot(type);
            Slot slot = menu.slots.get(index);
            helper.assertTrue(slot.getContainerSlot() == index, type + " slot should map to station slot " + index + ", was " + slot.getContainerSlot());
            helper.assertTrue(slot.mayPlace(new ItemStack(ModItems.upgrade(type).get())), type + " input slot should accept its own upgrade");
            helper.assertTrue(type == UpgradeType.SOLAR || !slot.mayPlace(new ItemStack(ModItems.upgrade(UpgradeType.SOLAR).get())),
                    type + " input slot must not accept Solar");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void scrollingTheInputGridHidesTheFirstRowAndMovesTheRest(GameTestHelper helper) {
        ProgrammingStationBlockEntity station = placeProgramming(helper);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        ProgrammingStationMenu menu = (ProgrammingStationMenu) station.createMenu(1, player.getInventory(), player);
        helper.assertTrue(ProgrammingStationMenu.maxInputScroll() >= 1, "Sanity: with " + UpgradeType.values().length + " types the grid should scroll");

        for (UpgradeType type : UpgradeType.values()) {
            int i = type.ordinal();
            int row = i / ProgrammingStationMenu.INPUT_COLUMNS;
            Slot slot = menu.slots.get(ProgrammingStationBlockEntity.inputSlot(type));
            helper.assertTrue(slot.isActive() == (row < ProgrammingStationMenu.INPUT_ROWS), type + " active at scroll 0 should be " + (row < ProgrammingStationMenu.INPUT_ROWS));
        }

        menu.setInputScroll(1);
        for (UpgradeType type : UpgradeType.values()) {
            int row = type.ordinal() / ProgrammingStationMenu.INPUT_COLUMNS;
            Slot slot = menu.slots.get(ProgrammingStationBlockEntity.inputSlot(type));
            boolean visible = row - 1 >= 0 && row - 1 < ProgrammingStationMenu.INPUT_ROWS;
            helper.assertTrue(slot.isActive() == visible, type + " active at scroll 1 should be " + visible);
            if (visible) {
                int expectedY = ProgrammingStationMenu.INPUT_Y + (row - 1) * 18;
                helper.assertTrue(slot.y == expectedY, type + " y at scroll 1 should be " + expectedY + ", was " + slot.y);
                int expectedX = ProgrammingStationMenu.INPUT_X + type.ordinal() % ProgrammingStationMenu.INPUT_COLUMNS * 18;
                helper.assertTrue(slot.x == expectedX, type + " x should be " + expectedX + ", was " + slot.x);
            }
        }
        helper.assertTrue(menu.slots.get(ProgrammingStationBlockEntity.inputSlot(UpgradeType.SOLAR)).isActive(), "Solar should be visible at scroll 1");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void shiftClickPlacesSolarUpgradeIntoItsInputSlotWhileScrolledOut(GameTestHelper helper) {
        ProgrammingStationBlockEntity station = placeProgramming(helper);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.getInventory().setItem(0, new ItemStack(ModItems.upgrade(UpgradeType.SOLAR).get(), 3));
        ProgrammingStationMenu menu = (ProgrammingStationMenu) station.createMenu(1, player.getInventory(), player);
        menu.quickMoveStack(player, ProgrammingStationBlockEntity.SLOT_COUNT + 27); // hotbar slot 0
        ItemStack inSlot = station.getItems().getStackInSlot(ProgrammingStationBlockEntity.inputSlot(UpgradeType.SOLAR));
        helper.assertTrue(inSlot.is(ModItems.upgrade(UpgradeType.SOLAR).get()) && inSlot.getCount() == 3, "Solar should land in its input slot, was " + inSlot);
        helper.assertTrue(player.getInventory().getItem(0).isEmpty(), "The player should have given up all 3");
        helper.succeed();
    }

    // --- Helpers ---

    private static DroneData solarData(int count) {
        return DroneData.createNew().withUpgradeCount(UpgradeType.SOLAR, count);
    }

    /** A 5x5 stone roof above the drone's column, high enough not to touch the drone. */
    private static void roof(GameTestHelper helper) {
        for (int x = 2; x <= 6; x++) {
            for (int z = 2; z <= 6; z++) {
                helper.setBlock(new BlockPos(x, 6, z), Blocks.STONE);
            }
        }
    }

    /** Fills {@code slots} upgrade slots with every type except Solar, up to each type's cap. */
    private static DroneData fillOthers(DroneData data, int slots) {
        for (UpgradeType type : UpgradeType.values()) {
            if (type == UpgradeType.SOLAR) {
                continue;
            }
            int room = slots - DroneStats.totalUpgrades(data.upgrades());
            data = data.withUpgradeCount(type, Math.min(type.maxCount(), room));
        }
        return data;
    }

    private static ProgrammingStationBlockEntity placeProgramming(GameTestHelper helper) {
        helper.setBlock(DRONE_REL, ModBlocks.PROGRAMMING_STATION.get());
        return (ProgrammingStationBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(DRONE_REL));
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
