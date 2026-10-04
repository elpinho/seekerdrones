package com.elpinho.seekerdrones.gametest;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import javax.annotation.Nullable;

import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.drone.DroneConfig;
import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.drone.DroneState;
import com.elpinho.seekerdrones.drone.DroneStats;
import com.elpinho.seekerdrones.drone.TargetEntry;
import com.elpinho.seekerdrones.drone.TargetMatcher;
import com.elpinho.seekerdrones.drone.UpgradeType;
import com.elpinho.seekerdrones.operator.OperatorGroups;
import com.elpinho.seekerdrones.registry.ModEntityTypes;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * M3 (seeking AI) GameTests: detection, sticky targeting, the fail-safe target-slot and Player Seek rules, the
 * group-operator/creative/spectator exclusions, following, losing a target and chase speed bounds
 * (DESIGN.md sections 2.2, 2.6, 2.7, 3.1, 3.3, 3.4, 3.5, 8.2).
 *
 * All tests share the {@code seekerdrones:empty} structure template. Zombies used as bait are always
 * {@code setNoAi(true)}, {@code setNoGravity(true)} and {@code setInvulnerable(true)} unless a test needs them to
 * actually die, so they stay put and don't burn in daylight or fall.
 */
@GameTestHolder("seekerdrones")
@PrefixGameTestTemplate(false)
public class SeekingGameTests {

    // --- 1. Detection: line of sight required, always required (DESIGN.md 3.3) ---

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void detectsZombieInSightWithLineOfSight(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        Zombie zombie = spawnStationaryZombie(helper, 4, 1, 7);
        drone.setDroneData(droneTargetingZombieEntity());

        helper.succeedWhen(() -> {
            helper.assertTrue(drone.getSeekTarget() == zombie,
                    "Drone should have spotted the visible zombie, target=" + drone.getSeekTarget());
            helper.assertTrue(drone.getState() == DroneState.CHASING || drone.getState() == DroneState.FOLLOWING,
                    "Drone should be chasing or following once it spots a target, state=" + drone.getState());
        });
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void ignoresZombieFullyEnclosedByBlocks(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        Zombie zombie = spawnStationaryZombie(helper, 4, 1, 7);
        sealBox(helper, 4, 1, 7);
        drone.setDroneData(droneTargetingZombieEntity());

        helper.runAfterDelay(30, () -> {
            helper.assertTrue(drone.getSeekTarget() == null,
                    "Drone should never spot a fully walled-off zombie, target=" + drone.getSeekTarget());
            helper.assertTrue(drone.getState() == DroneState.IDLE, "Drone should stay IDLE, state=" + drone.getState());
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 220)
    public static void chasingDroneLosesTargetThatStaysOutOfLineOfSightPastLostSightTimeout(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        Zombie zombie = spawnStationaryZombie(helper, 4, 1, 7);
        drone.setDroneData(droneTargetingZombieEntity());

        helper.runAfterDelay(30, () -> {
            helper.assertTrue(drone.getSeekTarget() == zombie, "Sanity: drone should have acquired the visible zombie, target=" + drone.getSeekTarget());
            sealBox(helper, 4, 1, 7);

            int timeout = ServerConfig.get(ServerConfig.DRONE_LOST_SIGHT_TIMEOUT);
            int scanInterval = ServerConfig.get(ServerConfig.DRONE_SCAN_INTERVAL);
            helper.runAfterDelay(timeout + scanInterval * 4, () -> {
                helper.assertTrue(drone.getSeekTarget() == null,
                        "Drone should lose a target that stayed out of line of sight past the lost-sight timeout, target=" + drone.getSeekTarget());
                helper.assertTrue(drone.getState() == DroneState.IDLE, "Drone should be IDLE, state=" + drone.getState());
                helper.succeed();
            });
        });
    }

    // --- 2. Nearest-first and sticky targeting (DESIGN.md 3.3) ---

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void picksNearestOfSeveralValidZombies(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        Zombie near = spawnStationaryZombie(helper, 6, 3, 4);
        spawnStationaryZombie(helper, 1, 3, 4);
        spawnStationaryZombie(helper, 8, 3, 4);
        drone.setDroneData(droneTargetingZombieEntity());

        helper.succeedWhen(() -> helper.assertTrue(drone.getSeekTarget() == near,
                "Drone should pick the nearest valid zombie, target=" + drone.getSeekTarget()));
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void stickyTargetDoesNotSwitchToNearerZombie(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        Zombie first = spawnStationaryZombie(helper, 4, 1, 7);
        drone.setDroneData(droneTargetingZombieEntity());

        helper.runAfterDelay(20, () -> {
            helper.assertTrue(drone.getSeekTarget() == first,
                    "Sanity: drone should have acquired the first zombie, target=" + drone.getSeekTarget());

            spawnStationaryZombie(helper, 4, 1, 5);

            helper.runAfterDelay(20, () -> {
                helper.assertTrue(drone.getSeekTarget() == first,
                        "Sticky target: drone should not switch to a nearer zombie once chasing, target=" + drone.getSeekTarget());
                helper.succeed();
            });
        });
    }

    // --- 3. Tag entries match (DESIGN.md 2.6) ---

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void tagEntryMatchesZombie(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        Zombie zombie = spawnStationaryZombie(helper, 4, 1, 7);
        DroneData base = DroneData.createNew();
        DroneData data = base.withConfig(base.config().withTargets(List.of(new TargetEntry(TargetEntry.Kind.TAG, "minecraft:zombies"))));
        drone.setDroneData(data);

        helper.succeedWhen(() -> helper.assertTrue(drone.getSeekTarget() == zombie,
                "Drone with a #minecraft:zombies tag entry should detect the zombie, target=" + drone.getSeekTarget()));
    }

    // --- 4. Runtime fail-safe: target slots and Player Seek (DESIGN.md 2.7) ---

    @GameTest(template = "empty", timeoutTicks = 5)
    public static void activeEntriesUsesOnlyFirstThreeTargetSlotsByDefault(GameTestHelper helper) {
        TargetEntry skeleton = new TargetEntry(TargetEntry.Kind.ENTITY_TYPE, "minecraft:skeleton");
        TargetEntry creeper = new TargetEntry(TargetEntry.Kind.ENTITY_TYPE, "minecraft:creeper");
        TargetEntry spider = new TargetEntry(TargetEntry.Kind.ENTITY_TYPE, "minecraft:spider");
        TargetEntry zombie = new TargetEntry(TargetEntry.Kind.ENTITY_TYPE, "minecraft:zombie");
        DroneData base = DroneData.createNew();
        helper.assertValueEqual(ServerConfig.get(ServerConfig.DRONE_TARGET_SLOTS), 3, "default drone.targetSlots");
        DroneData data = base.withConfig(base.config().withTargets(List.of(skeleton, creeper, spider, zombie)));
        helper.assertValueEqual(TargetMatcher.activeEntries(data), List.of(skeleton, creeper, spider),
                "active entries with 4 stored entries and 3 target slots");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 5)
    public static void activeEntriesDropsPlayerNameWithoutPlayerSeek(GameTestHelper helper) {
        TargetEntry playerEntry = new TargetEntry(TargetEntry.Kind.PLAYER_NAME, "Steve");
        DroneData base = DroneData.createNew();
        DroneData noSeek = base.withConfig(base.config().withTargets(List.of(playerEntry)));
        helper.assertTrue(TargetMatcher.activeEntries(noSeek).isEmpty(),
                "A player-name entry should be ignored without Player Seek");

        DroneData withSeek = dataWithUpgrades(noSeek, Map.of(UpgradeType.PLAYER_SEEK, 1));
        helper.assertValueEqual(TargetMatcher.activeEntries(withSeek), List.of(playerEntry),
                "A player-name entry should be used once Player Seek is installed");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 90)
    public static void fourthTargetEntryIsIgnoredWithDefaultTargetSlots(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        spawnStationaryZombie(helper, 4, 1, 7);
        List<TargetEntry> targets = List.of(
                new TargetEntry(TargetEntry.Kind.ENTITY_TYPE, "minecraft:skeleton"),
                new TargetEntry(TargetEntry.Kind.ENTITY_TYPE, "minecraft:creeper"),
                new TargetEntry(TargetEntry.Kind.ENTITY_TYPE, "minecraft:spider"),
                new TargetEntry(TargetEntry.Kind.ENTITY_TYPE, "minecraft:zombie"));
        DroneData base = DroneData.createNew();
        drone.setDroneData(base.withConfig(base.config().withTargets(targets)));

        helper.runAfterDelay(40, () -> {
            helper.assertTrue(drone.getSeekTarget() == null,
                    "The 4th target entry (zombie) is beyond the 3 target slots and should be ignored, target=" + drone.getSeekTarget());
            helper.assertTrue(drone.getState() == DroneState.IDLE, "Drone should stay IDLE, state=" + drone.getState());
            helper.succeed();
        });
    }

    // Own batch: it changes a global config value.
    @GameTest(template = "empty", timeoutTicks = 90, batch = "config_target_slots")
    public static void loweringTargetSlotsToOneIgnoresSecondEntryAndRaisingItRestoresIt(GameTestHelper helper) {
        int original = ServerConfig.get(ServerConfig.DRONE_TARGET_SLOTS);
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        Zombie zombie = spawnStationaryZombie(helper, 4, 1, 7);
        List<TargetEntry> targets = List.of(
                new TargetEntry(TargetEntry.Kind.ENTITY_TYPE, "minecraft:skeleton"),
                new TargetEntry(TargetEntry.Kind.ENTITY_TYPE, "minecraft:zombie"));
        DroneData base = DroneData.createNew();
        DroneData data = base.withConfig(base.config().withTargets(targets));
        ServerConfig.DRONE_TARGET_SLOTS.set(1);
        drone.setDroneData(data);

        helper.runAfterDelay(30, () -> {
            try {
                helper.assertValueEqual(TargetMatcher.activeEntries(data), List.of(targets.get(0)), "active entries with 1 target slot");
                helper.assertTrue(drone.getSeekTarget() == null,
                        "With targetSlots=1 the 2nd entry (zombie) should be ignored, target=" + drone.getSeekTarget());
                // Setting the value doesn't fire ModConfigEvent, so simulate the reload; no data re-apply.
                ServerConfig.DRONE_TARGET_SLOTS.set(2);
                TargetMatcher.invalidateAll();
            } catch (RuntimeException e) {
                ServerConfig.DRONE_TARGET_SLOTS.set(original);
                throw e;
            }
            helper.runAfterDelay(30, () -> {
                ServerConfig.DRONE_TARGET_SLOTS.set(original);
                helper.assertTrue(drone.getSeekTarget() == zombie,
                        "With targetSlots=2 the drone should use the 2nd entry and detect the zombie, target=" + drone.getSeekTarget());
                helper.succeed();
            });
        });
    }

    // Own batch: it changes a global config value.
    @GameTest(template = "empty", timeoutTicks = 120, batch = "config_target_slots_inflight")
    public static void loweringTargetSlotsMakesChasingDroneDropSecondEntryTarget(GameTestHelper helper) {
        int original = ServerConfig.get(ServerConfig.DRONE_TARGET_SLOTS);
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        Zombie zombie = spawnStationaryZombie(helper, 4, 1, 7);
        List<TargetEntry> targets = List.of(
                new TargetEntry(TargetEntry.Kind.ENTITY_TYPE, "minecraft:skeleton"),
                new TargetEntry(TargetEntry.Kind.ENTITY_TYPE, "minecraft:zombie"));
        DroneData base = DroneData.createNew();
        ServerConfig.DRONE_TARGET_SLOTS.set(2);
        drone.setDroneData(base.withConfig(base.config().withTargets(targets)));

        helper.runAfterDelay(25, () -> {
            try {
                helper.assertTrue(drone.getSeekTarget() == zombie,
                        "Sanity: with targetSlots=2 the drone should be chasing the zombie, target=" + drone.getSeekTarget());
                ServerConfig.DRONE_TARGET_SLOTS.set(1);
                TargetMatcher.invalidateAll();
            } catch (RuntimeException e) {
                ServerConfig.DRONE_TARGET_SLOTS.set(original);
                TargetMatcher.invalidateAll();
                throw e;
            }
            helper.runAfterDelay(30, () -> {
                ServerConfig.DRONE_TARGET_SLOTS.set(original);
                TargetMatcher.invalidateAll();
                helper.assertTrue(drone.getSeekTarget() == null,
                        "After lowering targetSlots to 1 the drone should drop the 2nd-entry target, target=" + drone.getSeekTarget());
                helper.assertTrue(drone.getState() == DroneState.IDLE, "Drone should be IDLE, state=" + drone.getState());
                helper.succeed();
            });
        });
    }

    // --- 5. Players are never targeted without the right conditions (DESIGN.md 3.3) ---

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void groupOperatorsAreNeverTargetedByPlayerSeek(GameTestHelper helper) {
        MinecraftServer server = server(helper);
        UUID owner = UUID.randomUUID();
        UUID groupId = OperatorGroups.get(server).createGroup(owner);

        Player player = spawnMockPlayer(helper, GameType.SURVIVAL, 4, 1, 4);
        helper.assertTrue(OperatorGroups.get(server).addOperator(groupId, player.getUUID()), "Sanity: should be able to add the player as an operator");

        TargetEntry playerEntry = new TargetEntry(TargetEntry.Kind.PLAYER_NAME, player.getGameProfile().getName());
        DroneData base = DroneData.createNew().withConfig(DroneConfig.createDefault().withTargets(List.of(playerEntry)));
        DroneData grouped = dataWithUpgrades(base, Map.of(UpgradeType.PLAYER_SEEK, 1)).withGroupId(Optional.of(groupId));
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(2, 3, 2));

        helper.assertFalse(TargetMatcher.of(grouped).matches(drone, player),
                "An operator of the drone's own group should never be targeted, even with Player Seek and a matching entry");

        // Sanity: the same player, without the group exemption, is a valid target.
        DroneData ungrouped = grouped.withGroupId(Optional.empty());
        helper.assertTrue(TargetMatcher.of(ungrouped).matches(drone, player),
                "Sanity: the same player without the group exemption should be a valid target");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void creativePlayersAreNeverTargeted(GameTestHelper helper) {
        Player player = spawnMockPlayer(helper, GameType.CREATIVE, 4, 1, 4);
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(2, 3, 2));
        TargetEntry playerEntry = new TargetEntry(TargetEntry.Kind.PLAYER_NAME, player.getGameProfile().getName());
        DroneData data = dataWithUpgrades(DroneData.createNew().withConfig(DroneConfig.createDefault().withTargets(List.of(playerEntry))),
                Map.of(UpgradeType.PLAYER_SEEK, 1));

        helper.assertFalse(TargetMatcher.of(data).matches(drone, player),
                "A creative-mode player should never be targeted, even with Player Seek and a matching entry");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void spectatorPlayersAreNeverTargeted(GameTestHelper helper) {
        Player player = spawnMockPlayer(helper, GameType.SPECTATOR, 4, 1, 4);
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(2, 3, 2));
        TargetEntry playerEntry = new TargetEntry(TargetEntry.Kind.PLAYER_NAME, player.getGameProfile().getName());
        DroneData data = dataWithUpgrades(DroneData.createNew().withConfig(DroneConfig.createDefault().withTargets(List.of(playerEntry))),
                Map.of(UpgradeType.PLAYER_SEEK, 1));

        helper.assertFalse(TargetMatcher.of(data).matches(drone, player),
                "A spectator should never be targeted, even with Player Seek and a matching entry");
        helper.succeed();
    }

    // --- 6. Following at follow distance, above the target's eyes (DESIGN.md 3.1) ---

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void followsAtFollowDistanceAboveTargetEyes(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(1, 3, 4));
        Zombie zombie = spawnStationaryZombie(helper, 5, 1, 4);
        DroneData data = droneTargetingZombieEntity();
        drone.setDroneData(data);

        helper.succeedWhen(() -> {
            helper.assertTrue(drone.getState() == DroneState.FOLLOWING,
                    "Drone should end up FOLLOWING a stationary target, state=" + drone.getState());
            double dx = drone.getX() - zombie.getX();
            double dz = drone.getZ() - zombie.getZ();
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            int followDistance = data.config().followDistance();
            helper.assertTrue(Math.abs(horizontal - followDistance) <= 1.0,
                    "Horizontal follow distance should be close to " + followDistance + ", was " + horizontal);
            // Section 3.1: the follow position is followHeightOffset above the target's eyes, not "at least" that
            // much, so this checks a tolerance band rather than a lower bound. The drone only brakes to a stop once
            // it's within followEnterDistance (1.0 default) of the ideal spot, so its final resting position can be
            // off by up to about that much in any direction, including vertically.
            double wantedY = zombie.getEyeY() + ServerConfig.get(ServerConfig.DRONE_FOLLOW_HEIGHT_OFFSET);
            helper.assertTrue(Math.abs(drone.getY() - wantedY) <= 1.0,
                    "Drone should settle close to followHeightOffset above the target's eyes, droneY=" + drone.getY() + " wantedY=" + wantedY);
        });
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void followingDroneGoesBackToChasingWhenTargetMovesBeyondExitDistance(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(1, 3, 4));
        Zombie zombie = spawnStationaryZombie(helper, 5, 1, 4);
        drone.setDroneData(droneTargetingZombieEntity());

        helper.runAfterDelay(100, () -> {
            helper.assertTrue(drone.getState() == DroneState.FOLLOWING,
                    "Sanity: drone should be FOLLOWING the stationary target before it moves, state=" + drone.getState());

            // Move the target horizontally, away from the drone, so the new follow position ends up well beyond
            // the (default) follow exit distance from where the drone currently is. The smoothed target position
            // (section 3.1) lags behind a teleport, so it takes a few ticks for the follow position to move far
            // enough for the exit-distance check to trip, not just 1-2.
            zombie.moveTo(zombie.getX() + 6.0, zombie.getY(), zombie.getZ());

            helper.runAfterDelay(15, () -> {
                helper.assertTrue(drone.getState() == DroneState.CHASING,
                        "Drone should switch back to CHASING once its target moves beyond the follow exit distance, state="
                                + drone.getState());

                helper.succeedWhen(() -> helper.assertTrue(drone.getState() == DroneState.FOLLOWING,
                        "Drone should return to FOLLOWING once it catches back up to the moved target, state=" + drone.getState()));
            });
        });
    }

    // --- 7. Losing the target (DESIGN.md 3.5) ---

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void losesTargetOnDeath(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        Zombie zombie = spawnStationaryZombie(helper, 4, 1, 7);
        drone.setDroneData(droneTargetingZombieEntity());

        // Poll for acquisition instead of a fixed-tick wait: the scan is staggered by (tickCount + entityId) % 10
        // (section 3.3), and the entity ID (and so this drone's stagger offset) depends on how many entities have
        // been created server-wide before it, which varies from run to run under GameTest's large concurrent test
        // batches. A fixed short wait can occasionally race that offset; polling with a generous timeout can't.
        pollUntil(helper, () -> drone.getSeekTarget() == zombie, 60, () -> {
            zombie.setInvulnerable(false);
            zombie.kill();

            helper.runAfterDelay(5, () -> {
                helper.assertTrue(drone.getState() == DroneState.IDLE, "Drone should go IDLE once its target dies, state=" + drone.getState());
                helper.assertTrue(drone.getSeekTarget() == null, "Drone should have no target after its target dies");

                helper.runAfterDelay(40, () -> {
                    helper.assertTrue(drone.getDeltaMovement().length() < 0.02,
                            "Drone should have hovered to a stop after losing its target, velocity=" + drone.getDeltaMovement());
                    helper.assertTrue(drone.isAlive() && !drone.isRemoved(), "Drone should stay airborne (not fall or despawn) after losing its target");
                    helper.succeed();
                });
            });
        }, () -> helper.fail("Drone never acquired the zombie, target=" + drone.getSeekTarget()));
    }

    @GameTest(template = "empty", timeoutTicks = 90)
    public static void losesTargetBeyondPursuitRange(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        Zombie zombie = spawnStationaryZombie(helper, 4, 1, 7);
        drone.setDroneData(droneTargetingZombieEntity());

        helper.runAfterDelay(30, () -> {
            helper.assertTrue(drone.getSeekTarget() == zombie,
                    "Sanity: drone should have acquired the zombie, target=" + drone.getSeekTarget());
            double pursuitRange = DroneStats.pursuitRange(drone.snapshotData());
            // Move straight up (not sideways) so this doesn't risk landing inside a neighboring test's structure.
            zombie.moveTo(zombie.getX(), zombie.getY() + pursuitRange + 5.0, zombie.getZ());

            helper.runAfterDelay(5, () -> {
                helper.assertTrue(drone.getState() == DroneState.IDLE,
                        "Drone should lose a target that flew beyond pursuit range, state=" + drone.getState());
                helper.assertTrue(drone.getSeekTarget() == null, "Drone should have no target beyond pursuit range");

                helper.runAfterDelay(30, () -> {
                    helper.assertTrue(drone.getDeltaMovement().length() < 0.02,
                            "Drone should have hovered to a stop, velocity=" + drone.getDeltaMovement());
                    helper.assertTrue(drone.isAlive() && !drone.isRemoved(), "Drone should stay airborne after losing its target");
                    helper.succeed();
                });
            });
        });
    }

    // Own batch: it lowers global config values, which would leak into tests running concurrently in its batch.
    @GameTest(template = "empty", timeoutTicks = 100, batch = "config_lost_sight_timeout")
    public static void losesTargetAfterLineOfSightTimeout(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        Zombie zombie = spawnStationaryZombie(helper, 4, 1, 7);
        drone.setDroneData(droneTargetingZombieEntity());

        helper.runAfterDelay(30, () -> {
            helper.assertTrue(drone.getSeekTarget() == zombie,
                    "Sanity: drone should have acquired the zombie, target=" + drone.getSeekTarget());

            int originalTimeout = ServerConfig.get(ServerConfig.DRONE_LOST_SIGHT_TIMEOUT);
            int originalInterval = ServerConfig.get(ServerConfig.DRONE_SCAN_INTERVAL);
            ServerConfig.DRONE_LOST_SIGHT_TIMEOUT.set(5);
            ServerConfig.DRONE_SCAN_INTERVAL.set(5);
            // Fully seal the (stationary) target so LOS can never be regained from any angle while we wait it out.
            sealBox(helper, 4, 1, 7);

            helper.runAfterDelay(25, () -> {
                ServerConfig.DRONE_LOST_SIGHT_TIMEOUT.set(originalTimeout);
                ServerConfig.DRONE_SCAN_INTERVAL.set(originalInterval);

                helper.assertTrue(drone.getState() == DroneState.IDLE,
                        "Drone should lose a target hidden for longer than the LOS timeout, state=" + drone.getState());
                helper.assertTrue(drone.getSeekTarget() == null, "Drone should have no target after the LOS timeout");
                helper.succeed();
            });
        });
    }

    // --- 8. Chase speed bounds (DESIGN.md 3.4) ---

    @GameTest(template = "empty", timeoutTicks = 5)
    public static void chaseSpeedStaysWithinCruiseAndMaxSpeedBounds(GameTestHelper helper) {
        double sightRange = 8.0;
        double cruise = ServerConfig.get(ServerConfig.DRONE_CRUISE_SPEED);
        double max = ServerConfig.get(ServerConfig.DRONE_MAX_SPEED);
        for (double d = 0.0; d <= sightRange * 1.5; d += 0.5) {
            double speed = DroneStats.chaseSpeed(d, sightRange);
            helper.assertTrue(speed >= cruise - 1.0E-6 && speed <= max + 1.0E-6,
                    "Chase speed at distance " + d + " should stay within [" + cruise + ", " + max + "], was " + speed);
        }
        helper.succeed();
    }

    // --- 9. A drifting drone that spots a target starts chasing (DESIGN.md 2.2, 3.2) ---

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void driftingDroneStartsChasingWhenSpottingTarget(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        drone.setDeltaMovement(0.3, 0.0, 0.0);
        drone.startDrifting();
        helper.assertTrue(drone.getRestPosition() == null,
                "Sanity: startDrifting() should clear the rest position, restPosition=" + drone.getRestPosition());
        drone.setDroneData(droneTargetingZombieEntity());
        spawnStationaryZombie(helper, 4, 1, 7);

        helper.succeedWhen(() -> {
            helper.assertTrue(drone.getState() == DroneState.CHASING,
                    "Drifting drone that spots a target should start chasing, state=" + drone.getState());
            helper.assertFalse(drone.isDrifting(), "Drone should no longer be drifting once it starts chasing");
            BlockPos restPosition = drone.getRestPosition();
            helper.assertTrue(restPosition != null,
                    "Drone should record its rest position at the moment it starts chasing while drifting");
            double distSqr = restPosition.distToCenterSqr(drone.position());
            helper.assertTrue(distSqr <= 4.0,
                    "Rest position should be close to where the drone was when it started chasing, restPosition="
                            + restPosition + " dronePos=" + drone.position());
        });
    }

    // --- 10. Target and state survive an NBT round-trip (DESIGN.md 8.2) ---

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void targetAndStateSurviveNbtRoundTrip(GameTestHelper helper) {
        DroneEntity original = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        Zombie zombie = spawnStationaryZombie(helper, 4, 1, 7);
        original.setDroneData(droneTargetingZombieEntity());

        helper.runAfterDelay(30, () -> {
            helper.assertTrue(original.getSeekTarget() == zombie,
                    "Sanity: original drone should have acquired the zombie, target=" + original.getSeekTarget());
            DroneState originalState = original.getState();
            helper.assertTrue(originalState == DroneState.CHASING || originalState == DroneState.FOLLOWING,
                    "Sanity: original drone should be chasing or following, state=" + originalState);

            // saveWithoutId still writes the "UUID" tag despite its name, so discard the original first: otherwise
            // the loaded copy would collide with it (same UUID, both alive) and never get added/ticked properly.
            CompoundTag tag = original.saveWithoutId(new CompoundTag());
            original.discard();
            DroneEntity loaded = ModEntityTypes.DRONE.get().create(helper.getLevel());
            loaded.load(tag);
            helper.getLevel().addFreshEntity(loaded);

            helper.runAfterDelay(3, () -> {
                helper.assertTrue(loaded.getSeekTarget() == zombie,
                        "Loaded drone should resolve the same target by UUID, target=" + loaded.getSeekTarget());
                DroneState loadedState = loaded.getState();
                helper.assertTrue(loadedState == DroneState.CHASING || loadedState == DroneState.FOLLOWING,
                        "Loaded drone should be chasing or following after the NBT round-trip, state=" + loadedState);
                helper.succeed();
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void unknownTargetUuidFallsBackToIdle(GameTestHelper helper) {
        DroneEntity template = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        template.setDroneData(DroneData.createNew());
        CompoundTag tag = template.saveWithoutId(new CompoundTag());
        tag.putString("State", "chasing");
        tag.putUUID("Target", UUID.randomUUID());
        // saveWithoutId still writes the "UUID" tag despite its name; discard the template so the loaded copy
        // doesn't collide with it (same UUID, both alive) and fail to be added/ticked properly.
        template.discard();

        DroneEntity loaded = ModEntityTypes.DRONE.get().create(helper.getLevel());
        loaded.load(tag);
        helper.getLevel().addFreshEntity(loaded);

        helper.runAfterDelay(20, () -> {
            helper.assertTrue(loaded.getState() == DroneState.IDLE,
                    "Drone with an unresolved target UUID should fall back to IDLE, state=" + loaded.getState());
            helper.assertTrue(loaded.getSeekTarget() == null, "Drone with an unresolved target UUID should have no target");
            helper.succeed();
        });
    }

    // --- 11. Inertia while chasing/following (DESIGN.md 3.4) ---

    @GameTest(template = "empty", timeoutTicks = 250)
    public static void chaseVelocityChangesByAtMostAccelerationEachTick(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(1, 3, 4));
        spawnStationaryZombie(helper, 7, 1, 4);
        drone.setDroneData(droneTargetingZombieEntity());

        double maxDelta = ServerConfig.get(ServerConfig.DRONE_ACCELERATION) + 1.0E-3;
        trackVelocityChanges(helper, drone, 180, maxDelta, null, null, helper::succeed);
    }

    // --- 12. Leash: small target movement is ignored, large movement is followed (DESIGN.md 3.1) ---

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void settledFollowingDroneIgnoresSmallTargetMovementButFollowsLargeOnes(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 1));
        Zombie zombie = spawnStationaryZombie(helper, 4, 1, 4);
        drone.setDroneData(droneTargetingZombieEntity());

        helper.runAfterDelay(150, () -> {
            helper.assertTrue(drone.getState() == DroneState.FOLLOWING,
                    "Sanity: drone should have settled into FOLLOWING before the leash is tested, state=" + drone.getState());

            // Well within followSlack (2 blocks default): should not move the settled drone at all.
            zombie.moveTo(zombie.getX(), zombie.getY(), zombie.getZ() + 1.0);

            trackDisplacement(helper, drone, 40, 0.3, () -> {
                // Well beyond followSlack: should make the drone move and settle back into FOLLOWING. Moved along a
                // different axis (x) than the settle/small-move axis (z), so the new target position can't coincide
                // with the drone's own resting spot (which sits behind the target along the original bearing) and
                // create a degenerate near-zero bearing vector. Kept within the shared "empty" structure's bounds
                // (0-8): going further risks landing inside a neighboring, concurrently-running test's structure.
                zombie.moveTo(zombie.getX() + 4.0, zombie.getY(), zombie.getZ());

                helper.runAfterDelay(200, () -> {
                    helper.assertTrue(drone.getState() == DroneState.FOLLOWING,
                            "Drone should settle back into FOLLOWING after the target moves well beyond the leash slack, state="
                                    + drone.getState());
                    double dx = drone.getX() - zombie.getX();
                    double dz = drone.getZ() - zombie.getZ();
                    double horizontal = Math.sqrt(dx * dx + dz * dz);
                    int followDistance = drone.snapshotData().config().followDistance();
                    helper.assertTrue(Math.abs(horizontal - followDistance) <= 1.0,
                            "Drone should be back at follow distance from the moved target, horizontal=" + horizontal);
                    helper.succeed();
                });
            });
        });
    }

    // --- 13. Smoothed target position filters small hops (DESIGN.md 3.1) ---

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void settledFollowingDroneIgnoresTargetHopping(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 1));
        Zombie zombie = spawnStationaryZombie(helper, 4, 1, 4);
        drone.setDroneData(droneTargetingZombieEntity());

        helper.runAfterDelay(150, () -> {
            helper.assertTrue(drone.getState() == DroneState.FOLLOWING,
                    "Sanity: drone should have settled into FOLLOWING before hopping starts, state=" + drone.getState());
            double baseY = zombie.getY();

            hopTarget(helper, zombie, baseY, 0, 10, () ->
                    trackDisplacement(helper, drone, 1, 0.3, helper::succeed));
        });
    }

    /** Teleports the target up/down by 0.5 blocks every 4 ticks, simulating small hops/jitter (section 3.1). */
    private static void hopTarget(GameTestHelper helper, Zombie zombie, double baseY, int hopIndex, int totalHops, Runnable onDone) {
        if (hopIndex >= totalHops) {
            onDone.run();
            return;
        }
        double offset = (hopIndex % 2 == 0) ? 0.5 : -0.5;
        zombie.moveTo(zombie.getX(), baseY + offset, zombie.getZ());
        helper.runAfterDelay(4, () -> hopTarget(helper, zombie, baseY, hopIndex + 1, totalHops, onDone));
    }

    // --- 14. Blocked follow position (DESIGN.md 3.1) ---

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void followingDroneSettlesInClearSpotUnderLowCeilingAndStaysCalm(GameTestHelper helper) {
        buildLowCorridor(helper);
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(1, 3, 4));
        Zombie zombie = spawnStationaryZombie(helper, 4, 2, 4);
        drone.setDroneData(droneTargetingZombieEntity());

        helper.runAfterDelay(200, () -> {
            helper.assertTrue(drone.getState() == DroneState.FOLLOWING,
                    "Sanity: drone should settle into FOLLOWING under a low ceiling, state=" + drone.getState());
            helper.assertTrue(helper.getLevel().noBlockCollision(drone, drone.getBoundingBox()),
                    "Drone's follow position under a low ceiling should not collide with blocks, pos=" + drone.position());

            trackDisplacementSum(helper, drone, 40, 1.0, 0.0, drone.position(), () -> {
                helper.assertTrue(helper.getLevel().noBlockCollision(drone, drone.getBoundingBox()),
                        "Drone should still be clear of blocks after settling, pos=" + drone.position());
                helper.succeed();
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void followingDroneUsesHalfDistanceWhenIdealSpotIsWalledOff(GameTestHelper helper) {
        Zombie zombie = spawnStationaryZombie(helper, 5, 3, 4);
        // A wall exactly at the ideal follow position (default follow distance 4, west of the target, the drone's
        // approach side), spanning every candidate height so all three height fallbacks are blocked there.
        for (int y = 1; y <= 6; y++) {
            for (int z = 3; z <= 5; z++) {
                helper.setBlock(new BlockPos(1, y, z), Blocks.STONE);
            }
        }
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(2, 3, 4));
        drone.setDroneData(droneTargetingZombieEntity());
        int followDistance = drone.snapshotData().config().followDistance();

        helper.runAfterDelay(200, () -> {
            helper.assertTrue(drone.getState() == DroneState.FOLLOWING,
                    "Sanity: drone should settle into FOLLOWING despite the wall, state=" + drone.getState());
            helper.assertTrue(helper.getLevel().noBlockCollision(drone, drone.getBoundingBox()),
                    "Drone should not clip into the wall, pos=" + drone.position());
            double dx = drone.getX() - zombie.getX();
            double dz = drone.getZ() - zombie.getZ();
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            helper.assertTrue(Math.abs(horizontal - followDistance / 2.0) <= 1.0,
                    "With the full follow distance walled off, the drone should settle at about half the follow distance ("
                            + (followDistance / 2.0) + "), horizontal=" + horizontal);
            helper.succeed();
        });
    }

    /**
     * A 3-block-wide, low-ceilinged corridor along the x-axis (floor at y=1, a 2-thick ceiling at y=4-5, walls at
     * z=2 and z=6), so the ideal follow height (followHeightOffset=1.5 above the target's eyes) can't fit, forcing
     * the height fallback. A single-block-thick ceiling isn't enough: with the default followHeightOffset (1.5) and
     * a zombie's eye height (~1.74), the ideal spot sits at about 1.5 + 1.74 = 3.24 above the target's feet, which
     * clears a ceiling that's only 1 block thick sitting right above the target's eyes; a 2-thick ceiling covers it.
     */
    private static void buildLowCorridor(GameTestHelper helper) {
        for (int x = 0; x <= 8; x++) {
            helper.setBlock(new BlockPos(x, 1, 4), Blocks.STONE);
            helper.setBlock(new BlockPos(x, 4, 4), Blocks.STONE);
            helper.setBlock(new BlockPos(x, 5, 4), Blocks.STONE);
            for (int y = 2; y <= 3; y++) {
                helper.setBlock(new BlockPos(x, y, 2), Blocks.STONE);
                helper.setBlock(new BlockPos(x, y, 6), Blocks.STONE);
            }
        }
    }

    // --- 15. Facing (DESIGN.md 3.4) ---

    @GameTest(template = "empty", timeoutTicks = 250)
    public static void followingDroneTurnsGraduallyAndFacesTargetOnceSettled(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(1, 3, 4));
        Zombie zombie = spawnStationaryZombie(helper, 6, 1, 7);
        drone.setDroneData(droneTargetingZombieEntity());

        double maxTurn = ServerConfig.get(ServerConfig.DRONE_TURN_SPEED) + 0.5;
        trackYawChanges(helper, drone, 180, maxTurn, drone.getYRot(), () -> {
            helper.assertTrue(drone.getState() == DroneState.FOLLOWING,
                    "Sanity: drone should have settled into FOLLOWING by now, state=" + drone.getState());
            double dx = zombie.getX() - drone.getX();
            double dz = zombie.getZ() - drone.getZ();
            float wantedYaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
            float diff = Mth.wrapDegrees(wantedYaw - drone.getYRot());
            double tolerance = ServerConfig.get(ServerConfig.DRONE_FACING_TOLERANCE) + 5.0;
            helper.assertTrue(Math.abs(diff) <= tolerance,
                    "Settled following drone should face its target within tolerance, diff=" + diff + " tolerance=" + tolerance);
            helper.assertTrue(drone.getYRot() == drone.getYHeadRot() && drone.getYRot() == drone.yBodyRot,
                    "Drone's yaw, head rotation and body rotation should stay equal on the server, yaw=" + drone.getYRot()
                            + " head=" + drone.getYHeadRot() + " body=" + drone.yBodyRot);
            helper.succeed();
        });
    }

    // --- Tick-polling helpers ---

    /**
     * Polls {@code condition} every tick for up to {@code ticksRemaining} ticks, running {@code onReady} as soon as
     * it's true, or {@code onTimeout} if it never becomes true in time. See {@link #trackVelocityChanges} for why
     * each step must reschedule with a fresh lambda.
     */
    private static void pollUntil(GameTestHelper helper, BooleanSupplier condition, int ticksRemaining, Runnable onReady, Runnable onTimeout) {
        if (condition.getAsBoolean()) {
            onReady.run();
            return;
        }
        if (ticksRemaining <= 1) {
            onTimeout.run();
            return;
        }
        helper.runAtTickTime(helper.getTick() + 1, () -> pollUntil(helper, condition, ticksRemaining - 1, onReady, onTimeout));
    }

    /**
     * Samples the drone's velocity every tick for {@code ticksRemaining} more ticks, asserting it never changes by
     * more than {@code maxDelta} between consecutive ticks (skipping ticks where the drone is touching a block, per
     * section 3.4's inertia rule). {@code perTick}, if not null, runs once per tick after the check.
     * <p>
     * Note: each step must reschedule itself with a <em>fresh</em> lambda, not a reused one. {@code GameTestInfo}
     * keys its {@code runAtTickTime} map by the {@code Runnable}'s identity and removes the fired entry by iterator
     * right after running it; re-registering the very same instance for a future tick from inside its own call gets
     * silently cancelled by that same removal, so the chain would only ever fire once.
     */
    private static void trackVelocityChanges(GameTestHelper helper, DroneEntity drone, int ticksRemaining, double maxDelta,
            @Nullable Vec3 previous, @Nullable Runnable perTick, Runnable onDone) {
        if (ticksRemaining <= 0 || drone.isRemoved()) {
            onDone.run();
            return;
        }
        Vec3 velocity = drone.getDeltaMovement();
        if (previous != null && !drone.horizontalCollision && !drone.verticalCollision) {
            double delta = velocity.subtract(previous).length();
            helper.assertTrue(delta <= maxDelta,
                    "Velocity should change by at most " + maxDelta + " blocks/tick, changed by " + delta
                            + " (from " + previous + " to " + velocity + ") at tick " + helper.getTick());
        }
        if (perTick != null) {
            perTick.run();
        }
        helper.runAtTickTime(helper.getTick() + 1,
                () -> trackVelocityChanges(helper, drone, ticksRemaining - 1, maxDelta, velocity, perTick, onDone));
    }

    /** Same idea as {@link #trackVelocityChanges}, but for the drone's yaw (degrees/tick), per section 3.4. */
    private static void trackYawChanges(GameTestHelper helper, DroneEntity drone, int ticksRemaining, double maxDelta, float previous, Runnable onDone) {
        if (ticksRemaining <= 0) {
            onDone.run();
            return;
        }
        float current = drone.getYRot();
        float delta = Mth.wrapDegrees(current - previous);
        helper.assertTrue(Math.abs(delta) <= maxDelta,
                "Yaw should change by at most " + maxDelta + " degrees/tick, changed by " + delta + " at tick " + helper.getTick());
        helper.runAtTickTime(helper.getTick() + 1, () -> trackYawChanges(helper, drone, ticksRemaining - 1, maxDelta, current, onDone));
    }

    /** Asserts the drone's net displacement from where it started stays under {@code maxDistance} after {@code ticks}. */
    private static void trackDisplacement(GameTestHelper helper, DroneEntity drone, int ticks, double maxDistance, Runnable onDone) {
        Vec3 start = drone.position();
        helper.runAfterDelay(ticks, () -> {
            double displacement = start.distanceTo(drone.position());
            helper.assertTrue(displacement <= maxDistance,
                    "Drone should not have moved noticeably, displacement=" + displacement + " over " + ticks + " ticks");
            onDone.run();
        });
    }

    /** Sums the drone's per-tick displacement over {@code ticksRemaining} more ticks, so brief bouncing back and forth still counts. */
    private static void trackDisplacementSum(GameTestHelper helper, DroneEntity drone, int ticksRemaining, double maxTotal, double total, Vec3 previous,
            Runnable onDone) {
        Vec3 current = drone.position();
        double updatedTotal = total + previous.distanceTo(current);
        if (ticksRemaining <= 1) {
            helper.assertTrue(updatedTotal <= maxTotal,
                    "Settled drone should stay calm (no fast bouncing), total displacement=" + updatedTotal);
            onDone.run();
            return;
        }
        helper.runAtTickTime(helper.getTick() + 1,
                () -> trackDisplacementSum(helper, drone, ticksRemaining - 1, maxTotal, updatedTotal, current, onDone));
    }

    // --- Helpers ---

    private static MinecraftServer server(GameTestHelper helper) {
        return helper.getLevel().getServer();
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

    private static Player spawnMockPlayer(GameTestHelper helper, GameType gameType, double x, double y, double z) {
        Player player = helper.makeMockPlayer(gameType);
        Vec3 pos = helper.absoluteVec(new Vec3(x, y, z));
        player.moveTo(pos.x, pos.y, pos.z, 0.0F, 0.0F);
        return player;
    }

    /**
     * Fully encloses the given relative coordinates in a solid stone shell (a 3x5x3 box with a 1x3x1 air column at
     * the center), so line of sight can never be regained from any angle. Used for the "behind a wall" detection
     * tests and to force a deterministic LOS timeout.
     */
    private static void sealBox(GameTestHelper helper, int cx, int cy, int cz) {
        for (int x = cx - 1; x <= cx + 1; x++) {
            for (int z = cz - 1; z <= cz + 1; z++) {
                for (int y = cy - 1; y <= cy + 3; y++) {
                    boolean interior = x == cx && z == cz && y >= cy && y <= cy + 2;
                    if (!interior) {
                        helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
                    }
                }
            }
        }
    }
}
