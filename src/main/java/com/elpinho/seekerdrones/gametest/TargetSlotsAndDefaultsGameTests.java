package com.elpinho.seekerdrones.gametest;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.drone.DroneStats;
import com.elpinho.seekerdrones.drone.TargetEntry;
import com.elpinho.seekerdrones.drone.UpgradeType;
import com.elpinho.seekerdrones.programming.DroneProgram;
import com.elpinho.seekerdrones.programming.ProgramRules;
import com.elpinho.seekerdrones.registry.ModEntityTypes;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Tests for the fixed {@code drone.targetSlots} value (DESIGN.md 2.7, 7.2), the new defaults (base sight 12, Sight cap 6,
 * 20 total slots) and the lenient decoding of removed upgrade names (X-ray, Multi-target).
 */
@GameTestHolder("seekerdrones")
@PrefixGameTestTemplate(false)
public class TargetSlotsAndDefaultsGameTests {

    private static final String SELECTOR = "@e[type=seekerdrones:drone,distance=..3]";

    @GameTest(template = "empty", timeoutTicks = 5)
    public static void defaultsAreBaseSight12SightCap6TotalSlots20TargetSlots3(GameTestHelper helper) {
        helper.assertValueEqual(ServerConfig.get(ServerConfig.DRONE_BASE_SIGHT_RANGE), 12, "drone.baseSightRange");
        helper.assertValueEqual(ServerConfig.get(ServerConfig.UPGRADES_SIGHT_MAX_COUNT), 6, "upgrades.sight.maxCount");
        helper.assertValueEqual(ServerConfig.get(ServerConfig.UPGRADES_TOTAL_SLOTS), 20, "upgrades.totalSlots");
        helper.assertValueEqual(ServerConfig.get(ServerConfig.DRONE_TARGET_SLOTS), 3, "drone.targetSlots");
        helper.assertValueEqual(DroneStats.targetSlots(), 3, "DroneStats.targetSlots()");
        helper.assertTrue(DroneStats.sightRange(DroneData.createNew()) == 12.0, "A fresh drone's sight range should be 12");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 5)
    public static void canAddRefusesSeventhSightUpgrade(GameTestHelper helper) {
        helper.assertTrue(ProgramRules.canAdd(Map.of(UpgradeType.SIGHT, 5), UpgradeType.SIGHT), "6th Sight should be allowed");
        helper.assertFalse(ProgramRules.canAdd(Map.of(UpgradeType.SIGHT, 6), UpgradeType.SIGHT), "7th Sight should be refused");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void upgradeSetCommandRefusesSevenSightAndAcceptsSix(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        runCommand(helper, "seekerdrones upgrade set sight 7 " + SELECTOR);
        helper.runAfterDelay(3, () -> {
            helper.assertTrue(drone.snapshotData().upgradeCount(UpgradeType.SIGHT) == 0,
                    "Setting 7 Sight should be refused, has " + drone.snapshotData().upgrades());
            runCommand(helper, "seekerdrones upgrade set sight 6 " + SELECTOR);
            helper.runAfterDelay(3, () -> {
                helper.assertTrue(drone.snapshotData().upgradeCount(UpgradeType.SIGHT) == 6,
                        "Setting 6 Sight should work, has " + drone.snapshotData().upgrades());
                helper.succeed();
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 5)
    public static void totalSlotLimitOf20StopsFurtherUpgrades(GameTestHelper helper) {
        DroneData data = DroneData.createNew();
        for (UpgradeType type : UpgradeType.values()) {
            int room = 20 - DroneStats.totalUpgrades(data.upgrades());
            data = data.withUpgradeCount(type, Math.min(type.maxCount(), room));
        }
        helper.assertValueEqual(DroneStats.totalUpgrades(data.upgrades()), 20, "Fixture should reach exactly 20 upgrades");
        helper.assertFalse(ProgramRules.canAdd(data.upgrades(), UpgradeType.PLAYER_SEEK), "No room beyond 20 total upgrades");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 5)
    public static void programmingStationRefusesTargetAtIndexTargetSlotsAndAcceptsLastSlot(GameTestHelper helper) {
        List<TargetEntry> targets = List.of(
                new TargetEntry(TargetEntry.Kind.ENTITY_TYPE, "minecraft:zombie"),
                new TargetEntry(TargetEntry.Kind.ENTITY_TYPE, "minecraft:skeleton"),
                new TargetEntry(TargetEntry.Kind.ENTITY_TYPE, "minecraft:creeper"),
                new TargetEntry(TargetEntry.Kind.ENTITY_TYPE, "minecraft:spider"));
        int slots = DroneStats.targetSlots();
        helper.assertTrue(slots == 3, "Sanity: default target slots should be 3, was " + slots);
        helper.assertTrue(ProgramRules.targetProblem(targets, slots - 1, List.of(), Map.of()) == null,
                "Index targetSlots-1 should be accepted, was " + ProgramRules.targetProblem(targets, slots - 1, List.of(), Map.of()));
        String problem = ProgramRules.targetProblem(targets, slots, List.of(), Map.of());
        helper.assertTrue(problem != null && problem.endsWith("no_slot"), "Index targetSlots should be refused with no_slot, was " + problem);
        helper.succeed();
    }

    // Own batch: it changes a global config value.
    @GameTest(template = "empty", timeoutTicks = 5, batch = "config_target_slots_rules")
    public static void programmingStationTargetSlotRuleFollowsConfig(GameTestHelper helper) {
        int original = ServerConfig.get(ServerConfig.DRONE_TARGET_SLOTS);
        try {
            ServerConfig.DRONE_TARGET_SLOTS.set(1);
            List<TargetEntry> targets = List.of(
                    new TargetEntry(TargetEntry.Kind.ENTITY_TYPE, "minecraft:zombie"),
                    new TargetEntry(TargetEntry.Kind.ENTITY_TYPE, "minecraft:skeleton"));
            helper.assertTrue(ProgramRules.targetProblem(targets, 0, List.of(), Map.of()) == null, "Index 0 fits 1 slot");
            String problem = ProgramRules.targetProblem(targets, 1, List.of(), Map.of());
            helper.assertTrue(problem != null && problem.endsWith("no_slot"), "Index 1 should be no_slot with 1 slot, was " + problem);
            helper.succeed();
        } finally {
            ServerConfig.DRONE_TARGET_SLOTS.set(original);
        }
    }

    @GameTest(template = "empty", timeoutTicks = 5)
    public static void droneDataJsonWithRemovedUpgradesLoadsKeepingOnlySight(GameTestHelper helper) {
        JsonElement json = DroneData.CODEC.encodeStart(JsonOps.INSTANCE, DroneData.createNew()).getOrThrow();
        JsonObject upgrades = new JsonObject();
        upgrades.addProperty("xray", 1);
        upgrades.addProperty("multi_target", 2);
        upgrades.addProperty("sight", 3);
        json.getAsJsonObject().add("upgrades", upgrades);
        DroneData decoded = DroneData.CODEC.parse(JsonOps.INSTANCE, json).result().orElse(null);
        helper.assertTrue(decoded != null, "DroneData with removed upgrade names should still decode");
        helper.assertValueEqual(decoded.upgrades(), Map.of(UpgradeType.SIGHT, 3), "decoded upgrades");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 5)
    public static void droneDataNbtWithRemovedUpgradesLoadsKeepingOnlySight(GameTestHelper helper) {
        Tag tag = DroneData.CODEC.encodeStart(NbtOps.INSTANCE, DroneData.createNew()).getOrThrow();
        CompoundTag upgrades = new CompoundTag();
        upgrades.putInt("xray", 1);
        upgrades.putInt("multi_target", 2);
        upgrades.putInt("sight", 3);
        ((CompoundTag) tag).put("upgrades", upgrades);
        Optional<DroneData> decoded = DroneData.CODEC.parse(NbtOps.INSTANCE, tag).result();
        helper.assertTrue(decoded.isPresent(), "DroneData NBT with removed upgrade names should still decode");
        helper.assertValueEqual(decoded.get().upgrades(), Map.of(UpgradeType.SIGHT, 3), "decoded upgrades");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 5)
    public static void droneProgramJsonWithRemovedUpgradesLoadsKeepingOnlySight(GameTestHelper helper) {
        JsonElement json = DroneProgram.CODEC.encodeStart(JsonOps.INSTANCE, DroneProgram.createDefault()).getOrThrow();
        JsonObject upgrades = new JsonObject();
        upgrades.addProperty("xray", 1);
        upgrades.addProperty("multi_target", 2);
        upgrades.addProperty("sight", 3);
        json.getAsJsonObject().add("upgrades", upgrades);
        DroneProgram decoded = DroneProgram.CODEC.parse(JsonOps.INSTANCE, json).result().orElse(null);
        helper.assertTrue(decoded != null, "DroneProgram with removed upgrade names should still decode");
        helper.assertValueEqual(decoded.upgrades(), Map.of(UpgradeType.SIGHT, 3), "decoded upgrades");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 5)
    public static void droneProgramNbtWithRemovedUpgradesLoadsKeepingOnlySight(GameTestHelper helper) {
        Tag tag = DroneProgram.CODEC.encodeStart(NbtOps.INSTANCE, DroneProgram.createDefault()).getOrThrow();
        CompoundTag upgrades = new CompoundTag();
        upgrades.putInt("xray", 1);
        upgrades.putInt("multi_target", 2);
        upgrades.putInt("sight", 3);
        ((CompoundTag) tag).put("upgrades", upgrades);
        Optional<DroneProgram> decoded = DroneProgram.CODEC.parse(NbtOps.INSTANCE, tag).result();
        helper.assertTrue(decoded.isPresent(), "DroneProgram NBT with removed upgrade names should still decode");
        helper.assertValueEqual(decoded.get().upgrades(), Map.of(UpgradeType.SIGHT, 3), "decoded upgrades");
        helper.succeed();
    }

    private static void runCommand(GameTestHelper helper, String command) {
        MinecraftServer server = helper.getLevel().getServer();
        Vec3 pos = helper.absoluteVec(new Vec3(4, 3, 4));
        CommandSourceStack source = server.createCommandSourceStack()
                .withLevel(helper.getLevel())
                .withPosition(pos)
                .withPermission(4);
        server.getCommands().performPrefixedCommand(source, command);
    }
}
