package com.elpinho.seekerdrones.gametest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.drone.DroneConfig;
import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.drone.DroneState;
import com.elpinho.seekerdrones.drone.TargetBlacklist;
import com.elpinho.seekerdrones.drone.TargetEntry;
import com.elpinho.seekerdrones.drone.TargetMatcher;
import com.elpinho.seekerdrones.registry.ModEntityTypes;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * GameTests for drones targeting other drones (DESIGN.md 3.3 Detection, "Other drones"): only enemy drones match,
 * allies (same group, or same owner without a group) never do, unowned drones are enemies to everyone, and the
 * target blacklist still applies.
 */
@GameTestHolder("seekerdrones")
@PrefixGameTestTemplate(false)
public class DroneVsDroneGameTests {

    // --- 1. Matcher ---

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void matcherMatchesEnemyDroneOfDifferentGroup(GameTestHelper helper) {
        DroneEntity hunter = spawn(helper, 2, 3, 2, hunterData().withGroupId(Optional.of(UUID.randomUUID())));
        DroneEntity other = spawn(helper, 6, 3, 6, plainData().withGroupId(Optional.of(UUID.randomUUID())));
        helper.assertTrue(TargetMatcher.of(hunter.snapshotData()).matches(hunter, other), "Drone of a different group should match");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void matcherSkipsSameGroupAlly(GameTestHelper helper) {
        UUID group = UUID.randomUUID();
        DroneEntity hunter = spawn(helper, 2, 3, 2, hunterData().withGroupId(Optional.of(group)).withOwnerId(Optional.of(UUID.randomUUID())));
        DroneEntity other = spawn(helper, 6, 3, 6, plainData().withGroupId(Optional.of(group)).withOwnerId(Optional.of(UUID.randomUUID())));
        helper.assertFalse(TargetMatcher.of(hunter.snapshotData()).matches(hunter, other), "Same-group drone should not match");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void matcherSkipsSameOwnerAllyWithoutGroup(GameTestHelper helper) {
        UUID owner = UUID.randomUUID();
        DroneEntity hunter = spawn(helper, 2, 3, 2, hunterData().withOwnerId(Optional.of(owner)));
        DroneEntity other = spawn(helper, 6, 3, 6, plainData().withOwnerId(Optional.of(owner)));
        helper.assertFalse(TargetMatcher.of(hunter.snapshotData()).matches(hunter, other), "Same-owner ungrouped drone should not match");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void matcherMatchesDroneOfSameOwnerButDifferentGroupSetup(GameTestHelper helper) {
        UUID owner = UUID.randomUUID();
        DroneEntity hunter = spawn(helper, 2, 3, 2, hunterData().withOwnerId(Optional.of(owner)).withGroupId(Optional.of(UUID.randomUUID())));
        DroneEntity other = spawn(helper, 6, 3, 6, plainData().withOwnerId(Optional.of(owner)));
        helper.assertTrue(TargetMatcher.of(hunter.snapshotData()).matches(hunter, other),
                "A grouped drone and an ungrouped drone of the same owner are different teams, so they are enemies");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void matcherMatchesOtherUnownedDrone(GameTestHelper helper) {
        DroneEntity hunter = spawn(helper, 2, 3, 2, hunterData());
        DroneEntity other = spawn(helper, 6, 3, 6, plainData());
        helper.assertTrue(TargetMatcher.of(hunter.snapshotData()).matches(hunter, other), "Two unowned drones are enemies");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void matcherNeverMatchesItself(GameTestHelper helper) {
        DroneEntity hunter = spawn(helper, 2, 3, 2, hunterData());
        helper.assertFalse(TargetMatcher.of(hunter.snapshotData()).matches(hunter, hunter), "A drone should never target itself");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void matcherSkipsDroneWithoutDroneEntry(GameTestHelper helper) {
        DroneData data = DroneData.createNew().withConfig(DroneConfig.createDefault()
                .withTargets(List.of(new TargetEntry(TargetEntry.Kind.ENTITY_TYPE, "minecraft:zombie"))));
        DroneEntity hunter = spawn(helper, 2, 3, 2, data);
        DroneEntity other = spawn(helper, 6, 3, 6, plainData());
        helper.assertFalse(TargetMatcher.of(hunter.snapshotData()).matches(hunter, other), "Without a drone entry, drones are not targets");
        helper.succeed();
    }

    // Own batch: it changes a global config value.
    @GameTest(template = "empty", timeoutTicks = 10, batch = "config_blacklist_drone")
    public static void matcherSkipsBlacklistedDroneType(GameTestHelper helper) {
        DroneEntity hunter = spawn(helper, 2, 3, 2, hunterData());
        DroneEntity other = spawn(helper, 6, 3, 6, plainData());
        try {
            helper.assertTrue(TargetMatcher.of(hunter.snapshotData()).matches(hunter, other), "Sanity: matches without blacklist");
            withBlacklist(List.of("seekerdrones:drone"));
            helper.assertFalse(TargetMatcher.of(hunter.snapshotData()).matches(hunter, other), "Blacklisting seekerdrones:drone should protect drones");
            helper.succeed();
        } finally {
            withBlacklist(List.of());
        }
    }

    // --- 2. End to end ---

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void hunterAcquiresEnemyDroneInSight(GameTestHelper helper) {
        DroneEntity hunter = spawn(helper, 4, 3, 2, hunterData().withGroupId(Optional.of(UUID.randomUUID())));
        DroneEntity enemy = spawn(helper, 4, 3, 7, plainData().withGroupId(Optional.of(UUID.randomUUID())));
        helper.succeedWhen(() -> {
            helper.assertTrue(hunter.getSeekTarget() == enemy, "Hunter should target the enemy drone, target=" + hunter.getSeekTarget());
            helper.assertTrue(hunter.getState() == DroneState.CHASING || hunter.getState() == DroneState.FOLLOWING,
                    "Hunter should be chasing, state=" + hunter.getState());
        });
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void hunterIgnoresAllyDrone(GameTestHelper helper) {
        UUID group = UUID.randomUUID();
        DroneEntity hunter = spawn(helper, 4, 3, 2, hunterData().withGroupId(Optional.of(group)));
        spawn(helper, 4, 3, 7, plainData().withGroupId(Optional.of(group)));
        helper.runAfterDelay(40, () -> {
            helper.assertTrue(hunter.getSeekTarget() == null, "Hunter should ignore an ally drone, target=" + hunter.getSeekTarget());
            helper.assertTrue(hunter.getState() == DroneState.IDLE, "Hunter should stay IDLE, state=" + hunter.getState());
            helper.succeed();
        });
    }

    // --- 3. Losing the target when it becomes an ally ---

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void hunterLosesDroneThatJoinsItsGroup(GameTestHelper helper) {
        UUID group = UUID.randomUUID();
        DroneEntity hunter = spawn(helper, 4, 3, 2, hunterData().withGroupId(Optional.of(group)));
        DroneEntity enemy = spawn(helper, 4, 3, 7, plainData().withGroupId(Optional.of(UUID.randomUUID())));
        helper.runAfterDelay(30, () -> {
            helper.assertTrue(hunter.getSeekTarget() == enemy, "Sanity: hunter should have acquired the enemy, target=" + hunter.getSeekTarget());
            enemy.setDroneData(enemy.snapshotData().withGroupId(Optional.of(group)));
            helper.succeedWhen(() -> helper.assertTrue(hunter.getSeekTarget() == null,
                    "Hunter should drop a drone that joined its group, target=" + hunter.getSeekTarget()));
        });
    }

    // --- helpers ---

    private static void withBlacklist(List<String> entries) {
        ServerConfig.DRONE_TARGET_BLACKLIST.set(entries);
        TargetBlacklist.invalidate();
    }

    private static DroneEntity spawn(GameTestHelper helper, int x, int y, int z, DroneData data) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(x, y, z));
        drone.setDroneData(data);
        return drone;
    }

    /** Targets drones only. */
    private static DroneData hunterData() {
        return DroneData.createNew().withConfig(DroneConfig.createDefault()
                .withTargets(List.of(new TargetEntry(TargetEntry.Kind.ENTITY_TYPE, "seekerdrones:drone"))));
    }

    /** A passive drone with no targets. */
    private static DroneData plainData() {
        return DroneData.createNew().withConfig(DroneConfig.createDefault().withTargets(List.of()));
    }
}
