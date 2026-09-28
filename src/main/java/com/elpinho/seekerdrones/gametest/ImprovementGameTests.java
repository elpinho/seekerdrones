package com.elpinho.seekerdrones.gametest;

import java.util.List;
import java.util.Map;

import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.drone.DroneState;
import com.elpinho.seekerdrones.drone.TargetEntry;
import com.elpinho.seekerdrones.drone.UpgradeType;
import com.elpinho.seekerdrones.network.DroneStatusPayload;
import com.elpinho.seekerdrones.registry.ModEntityTypes;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * GameTests for three improvements (docs/IMPROVEMENTS.md): sight range in the {@link DroneStatusPayload}
 * (DESIGN.md sections 2.4, 3.3), invisible-entity hiding (sections 3.3, 3.5) and the
 * {@code /seekerdrones config color} debug command (section 2.6).
 *
 * All tests share the {@code seekerdrones:empty} structure template. Zombies used as bait are stationary
 * ({@code setNoAi(true)}, {@code setNoGravity(true)}) and invulnerable, matching the convention in
 * {@code SeekingGameTests}.
 */
@GameTestHolder("seekerdrones")
@PrefixGameTestTemplate(false)
public class ImprovementGameTests {

    private static final String SELECTOR = "@e[type=seekerdrones:drone,distance=..3]";

    // --- 1. Sight range in the DroneStatusPayload (DESIGN.md 2.4, 3.3) ---

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void statusPayloadReportsSightRangeIncludingSightUpgrades(GameTestHelper helper) {
        int base = ServerConfig.get(ServerConfig.DRONE_BASE_SIGHT_RANGE);
        int perUpgrade = ServerConfig.get(ServerConfig.UPGRADES_SIGHT_PER_UPGRADE);
        int expectedWithUpgrades = base + 2 * perUpgrade;

        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        DroneData noUpgrades = DroneData.createNew();
        drone.setDroneData(noUpgrades);

        DroneStatusPayload entityNoUpgrades = DroneStatusPayload.of(drone, true);
        helper.assertTrue(entityNoUpgrades.sightRange() == base,
                "Entity payload with 0 Sight upgrades should report the base sight range (" + base + "), was " + entityNoUpgrades.sightRange());

        DroneData withUpgrades = dataWithUpgrades(noUpgrades, Map.of(UpgradeType.SIGHT, 2));
        drone.setDroneData(withUpgrades);

        DroneStatusPayload entityWithUpgrades = DroneStatusPayload.of(drone, true);
        helper.assertTrue(entityWithUpgrades.sightRange() == expectedWithUpgrades,
                "Entity payload with 2 Sight upgrades should report base + 2*perUpgrade (" + expectedWithUpgrades + "), was "
                        + entityWithUpgrades.sightRange());

        DroneStatusPayload itemNoUpgrades = DroneStatusPayload.ofItem(noUpgrades);
        helper.assertTrue(itemNoUpgrades.sightRange() == base,
                "Item payload with 0 Sight upgrades should report the base sight range (" + base + "), was " + itemNoUpgrades.sightRange());

        DroneStatusPayload itemWithUpgrades = DroneStatusPayload.ofItem(withUpgrades);
        helper.assertTrue(itemWithUpgrades.sightRange() == expectedWithUpgrades,
                "Item payload with 2 Sight upgrades should report base + 2*perUpgrade (" + expectedWithUpgrades + "), was "
                        + itemWithUpgrades.sightRange());
        helper.succeed();
    }

    // --- 2. Invisibility (DESIGN.md 3.3, 3.5) ---

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void invisibleUnequippedZombieIsNotAcquired(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        spawnInvisibleZombie(helper, 4, 1, 7);
        drone.setDroneData(droneTargetingZombieEntity());

        helper.runAfterDelay(30, () -> {
            helper.assertTrue(drone.getSeekTarget() == null,
                    "Drone should not acquire an invisible, unequipped zombie, target=" + drone.getSeekTarget());
            helper.assertTrue(drone.getState() == DroneState.IDLE, "Drone should stay IDLE, state=" + drone.getState());
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void xrayDroneDoesNotAcquireInvisibleUnequippedZombie(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        spawnInvisibleZombie(helper, 4, 1, 7);
        drone.setDroneData(dataWithUpgrades(droneTargetingZombieEntity(), Map.of(UpgradeType.XRAY, 1)));

        helper.runAfterDelay(30, () -> {
            helper.assertTrue(drone.getSeekTarget() == null,
                    "X-ray drone should not reveal an invisible, unequipped zombie, target=" + drone.getSeekTarget());
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void invisibleZombieWearingArmorIsStillAcquired(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        Zombie zombie = spawnInvisibleZombie(helper, 4, 1, 7);
        zombie.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        drone.setDroneData(droneTargetingZombieEntity());

        helper.succeedWhen(() -> helper.assertTrue(drone.getSeekTarget() == zombie,
                "Drone should still acquire an invisible zombie wearing an armor piece, target=" + drone.getSeekTarget()));
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void invisibleZombieHoldingItemIsStillAcquired(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        Zombie zombie = spawnInvisibleZombie(helper, 4, 1, 7);
        zombie.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        drone.setDroneData(droneTargetingZombieEntity());

        helper.succeedWhen(() -> helper.assertTrue(drone.getSeekTarget() == zombie,
                "Drone should still acquire an invisible zombie holding an item in its main hand, target=" + drone.getSeekTarget()));
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void glowingInvisibleZombieIsStillAcquired(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        Zombie zombie = spawnInvisibleZombie(helper, 4, 1, 7);
        zombie.addEffect(new MobEffectInstance(MobEffects.GLOWING, 200));
        drone.setDroneData(droneTargetingZombieEntity());

        helper.succeedWhen(() -> helper.assertTrue(drone.getSeekTarget() == zombie,
                "Drone should still acquire a glowing invisible zombie, target=" + drone.getSeekTarget()));
    }

    // Own batch: it changes a global config value, which would leak into tests running concurrently in its batch.
    @GameTest(template = "empty", timeoutTicks = 40, batch = "config_invisibility_hides")
    public static void invisibilityHidesFalseAllowsAcquiringInvisibleZombie(GameTestHelper helper) {
        boolean original = ServerConfig.get(ServerConfig.DRONE_INVISIBILITY_HIDES);
        ServerConfig.DRONE_INVISIBILITY_HIDES.set(false);
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        Zombie zombie = spawnInvisibleZombie(helper, 4, 1, 7);
        drone.setDroneData(droneTargetingZombieEntity());

        helper.runAfterDelay(30, () -> {
            ServerConfig.DRONE_INVISIBILITY_HIDES.set(original);
            helper.assertTrue(drone.getSeekTarget() == zombie,
                    "With invisibilityHides=false, the drone should acquire an invisible, unequipped zombie, target=" + drone.getSeekTarget());
            helper.succeed();
        });
    }

    // These three tests wait out the default drone.lostSightTimeout instead of lowering it: config values are global,
    // so changing them affects every test running concurrently in the same batch.

    @GameTest(template = "empty", timeoutTicks = 220)
    public static void chasingDroneLosesTargetAfterItTurnsInvisibleOncePastLostSightTimeout(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        Zombie zombie = spawnStationaryZombie(helper, 4, 1, 7);
        drone.setDroneData(droneTargetingZombieEntity());

        helper.runAfterDelay(30, () -> {
            helper.assertTrue(drone.getSeekTarget() == zombie, "Sanity: drone should have acquired the visible zombie, target=" + drone.getSeekTarget());

            int timeout = ServerConfig.get(ServerConfig.DRONE_LOST_SIGHT_TIMEOUT);
            int scanInterval = ServerConfig.get(ServerConfig.DRONE_SCAN_INTERVAL);
            clearEquipment(zombie);
            zombie.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, timeout + scanInterval * 4));

            helper.runAfterDelay(5, () -> {
                helper.assertTrue(drone.getSeekTarget() == zombie,
                        "Drone should keep its target for a short time right after it turns invisible (well within the lost-sight timeout), target="
                                + drone.getSeekTarget());

                helper.runAfterDelay(timeout + scanInterval * 2, () -> {
                    helper.assertTrue(drone.getState() == DroneState.IDLE,
                            "Drone should lose a target that stayed invisible past the lost-sight timeout, state=" + drone.getState());
                    helper.assertTrue(drone.getSeekTarget() == null, "Drone should have no target after the invisibility timeout");
                    helper.succeed();
                });
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 220)
    public static void xrayChasingDroneLosesTargetAfterItTurnsInvisibleOncePastLostSightTimeout(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        Zombie zombie = spawnStationaryZombie(helper, 4, 1, 7);
        drone.setDroneData(dataWithUpgrades(droneTargetingZombieEntity(), Map.of(UpgradeType.XRAY, 1)));

        helper.runAfterDelay(30, () -> {
            helper.assertTrue(drone.getSeekTarget() == zombie,
                    "Sanity: X-ray drone should have acquired the visible zombie, target=" + drone.getSeekTarget());

            int timeout = ServerConfig.get(ServerConfig.DRONE_LOST_SIGHT_TIMEOUT);
            int scanInterval = ServerConfig.get(ServerConfig.DRONE_SCAN_INTERVAL);
            clearEquipment(zombie);
            zombie.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, timeout + scanInterval * 4));

            helper.runAfterDelay(timeout + scanInterval * 2, () -> {
                helper.assertTrue(drone.getState() == DroneState.IDLE,
                        "An X-ray drone should still lose a target that stayed invisible past the lost-sight timeout, state=" + drone.getState());
                helper.assertTrue(drone.getSeekTarget() == null, "X-ray drone should have no target after the invisibility timeout");
                helper.succeed();
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 220)
    public static void chasingDroneKeepsTargetIfInvisibilityRemovedBeforeLostSightTimeout(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        Zombie zombie = spawnStationaryZombie(helper, 4, 1, 7);
        drone.setDroneData(droneTargetingZombieEntity());

        helper.runAfterDelay(30, () -> {
            helper.assertTrue(drone.getSeekTarget() == zombie, "Sanity: drone should have acquired the visible zombie, target=" + drone.getSeekTarget());

            int timeout = ServerConfig.get(ServerConfig.DRONE_LOST_SIGHT_TIMEOUT);
            int scanInterval = ServerConfig.get(ServerConfig.DRONE_SCAN_INTERVAL);
            clearEquipment(zombie);
            zombie.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, timeout + scanInterval * 4));

            helper.runAfterDelay(scanInterval, () -> {
                zombie.removeEffect(MobEffects.INVISIBILITY);

                // Waits well beyond the lost-sight timeout, counted from when invisibility started, to prove the
                // drone never lost its target: invisibility was removed only scanInterval ticks in, long before the
                // timeout could apply.
                helper.runAfterDelay(timeout + scanInterval * 2, () -> {
                    helper.assertTrue(drone.getSeekTarget() == zombie,
                            "Drone should keep its target once invisibility is removed before the lost-sight timeout elapses, target="
                                    + drone.getSeekTarget());
                    helper.assertTrue(drone.getState() == DroneState.CHASING || drone.getState() == DroneState.FOLLOWING,
                            "Drone should still be chasing or following its target, state=" + drone.getState());
                    helper.succeed();
                });
            });
        });
    }

    // --- 3. /seekerdrones config color (DESIGN.md 2.6) ---

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void colorCommandSetsDroneColorAndSyncedEntityColorAtPermissionLevel2(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        helper.assertTrue(drone.snapshotData().config().color() == DyeColor.BLUE,
                "Sanity: default color should be blue, was " + drone.snapshotData().config().color());
        helper.assertTrue(drone.getColor() == DyeColor.BLUE, "Sanity: default synced entity color should be blue, was " + drone.getColor());

        runCommand(helper, "seekerdrones config color red " + SELECTOR, 2);

        helper.runAfterDelay(3, () -> {
            helper.assertTrue(drone.snapshotData().config().color() == DyeColor.RED,
                    "Command run at permission level 2 should have set the drone's color to red, was " + drone.snapshotData().config().color());
            helper.assertTrue(drone.getColor() == DyeColor.RED,
                    "The synced entity color should also be red, was " + drone.getColor());
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void colorCommandWithUnknownColorFailsAndLeavesDroneUnchanged(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        DyeColor before = drone.snapshotData().config().color();

        runCommand(helper, "seekerdrones config color notacolor " + SELECTOR, 4);

        helper.runAfterDelay(3, () -> {
            helper.assertTrue(drone.snapshotData().config().color() == before,
                    "An unknown color name should fail and leave the drone's color unchanged, was " + drone.snapshotData().config().color());
            helper.assertTrue(drone.getColor() == before, "The synced entity color should also be unchanged, was " + drone.getColor());
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void colorCommandRequiresPermissionLevel2(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        DyeColor before = drone.snapshotData().config().color();

        runCommand(helper, "seekerdrones config color red " + SELECTOR, 1);

        helper.runAfterDelay(3, () -> {
            helper.assertTrue(drone.snapshotData().config().color() == before,
                    "Running the color command at permission level 1 should be rejected and leave the drone's color unchanged, was "
                            + drone.snapshotData().config().color());
            helper.assertTrue(drone.getColor() == before,
                    "The synced entity color should also be unchanged after a rejected permission-1 attempt, was " + drone.getColor());
            helper.succeed();
        });
    }

    // --- Helpers ---

    private static MinecraftServer server(GameTestHelper helper) {
        return helper.getLevel().getServer();
    }

    private static void runCommand(GameTestHelper helper, String command, int permission) {
        MinecraftServer server = server(helper);
        Vec3 pos = helper.absoluteVec(new Vec3(4, 3, 4));
        CommandSourceStack source = server.createCommandSourceStack()
                .withLevel(helper.getLevel())
                .withPosition(pos)
                .withPermission(permission);
        server.getCommands().performPrefixedCommand(source, command);
    }

    /** A drone data set with a single {@code minecraft:zombie} entity-type target entry, otherwise default. */
    private static DroneData droneTargetingZombieEntity() {
        DroneData base = DroneData.createNew();
        return base.withConfig(base.config().withTargets(List.of(new TargetEntry(TargetEntry.Kind.ENTITY_TYPE, "minecraft:zombie"))));
    }

    private static DroneData dataWithUpgrades(DroneData data, Map<UpgradeType, Integer> upgrades) {
        return new DroneData(data.droneId(), data.groupId(), data.ownerId(), data.ownerName(), data.energy(), data.health(), upgrades, data.config());
    }

    /** A stationary bait zombie: no AI, no gravity, invulnerable (so daylight or stray hits don't remove it). */
    private static Zombie spawnStationaryZombie(GameTestHelper helper, int x, int y, int z) {
        Zombie zombie = helper.spawn(EntityType.ZOMBIE, new BlockPos(x, y, z));
        zombie.setNoAi(true);
        zombie.setNoGravity(true);
        zombie.setInvulnerable(true);
        return zombie;
    }

    /**
     * A stationary, invulnerable, invisible zombie with every equipment slot cleared: zombies can spawn with random
     * gear, and any held item or armor piece would make it detectable regardless of invisibility (section 3.3).
     */
    private static Zombie spawnInvisibleZombie(GameTestHelper helper, int x, int y, int z) {
        Zombie zombie = spawnStationaryZombie(helper, x, y, z);
        clearEquipment(zombie);
        zombie.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 200));
        return zombie;
    }

    private static void clearEquipment(Zombie zombie) {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            zombie.setItemSlot(slot, ItemStack.EMPTY);
        }
    }
}
