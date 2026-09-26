package com.elpinho.seekerdrones.gametest;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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

    // --- 1. Detection: line of sight required, X-ray bypasses it (DESIGN.md 3.3) ---

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
    public static void ignoresZombieFullyEnclosedWithoutXray(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        Zombie zombie = spawnStationaryZombie(helper, 4, 1, 7);
        sealBox(helper, 4, 1, 7);
        drone.setDroneData(droneTargetingZombieEntity());

        helper.runAfterDelay(30, () -> {
            helper.assertTrue(drone.getSeekTarget() == null,
                    "Drone without X-ray should never spot a fully walled-off zombie, target=" + drone.getSeekTarget());
            helper.assertTrue(drone.getState() == DroneState.IDLE, "Drone should stay IDLE, state=" + drone.getState());
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void xrayDetectsZombieThroughEnclosure(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        Zombie zombie = spawnStationaryZombie(helper, 4, 1, 7);
        sealBox(helper, 4, 1, 7);
        drone.setDroneData(dataWithUpgrades(droneTargetingZombieEntity(), Map.of(UpgradeType.XRAY, 1)));

        helper.succeedWhen(() -> helper.assertTrue(drone.getSeekTarget() == zombie,
                "X-ray drone should detect the zombie through the walls, target=" + drone.getSeekTarget()));
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
    public static void activeEntriesRespectsMultiTargetFailSafeCount(GameTestHelper helper) {
        TargetEntry skeleton = new TargetEntry(TargetEntry.Kind.ENTITY_TYPE, "minecraft:skeleton");
        TargetEntry zombie = new TargetEntry(TargetEntry.Kind.ENTITY_TYPE, "minecraft:zombie");
        DroneData base = DroneData.createNew();
        DroneData noUpgrade = base.withConfig(base.config().withTargets(List.of(skeleton, zombie)));
        helper.assertValueEqual(TargetMatcher.activeEntries(noUpgrade), List.of(skeleton),
                "active entries with no Multi-target upgrade (base drone only has 1 target slot)");

        DroneData withUpgrade = dataWithUpgrades(noUpgrade, Map.of(UpgradeType.MULTI_TARGET, 1));
        helper.assertValueEqual(TargetMatcher.activeEntries(withUpgrade), List.of(skeleton, zombie),
                "active entries with 1 Multi-target upgrade (2 target slots)");
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
    public static void multiTargetUpgradeEnablesIgnoredSecondTargetSlot(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        Zombie zombie = spawnStationaryZombie(helper, 4, 1, 7);
        List<TargetEntry> targets = List.of(
                new TargetEntry(TargetEntry.Kind.ENTITY_TYPE, "minecraft:skeleton"),
                new TargetEntry(TargetEntry.Kind.ENTITY_TYPE, "minecraft:zombie"));
        DroneData base = DroneData.createNew();
        DroneData noMultiTarget = base.withConfig(base.config().withTargets(targets));
        drone.setDroneData(noMultiTarget);

        helper.runAfterDelay(30, () -> {
            helper.assertTrue(drone.getSeekTarget() == null,
                    "Without Multi-target, the drone should ignore the 2nd target entry (zombie) and stay idle, target=" + drone.getSeekTarget());
            helper.assertTrue(drone.getState() == DroneState.IDLE, "Drone should stay IDLE, state=" + drone.getState());

            drone.setDroneData(dataWithUpgrades(noMultiTarget, Map.of(UpgradeType.MULTI_TARGET, 1)));

            helper.runAfterDelay(30, () -> {
                helper.assertTrue(drone.getSeekTarget() == zombie,
                        "With 1 Multi-target upgrade, the drone should now use the 2nd target entry and detect the zombie, target=" + drone.getSeekTarget());
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
            double minY = zombie.getEyeY() + ServerConfig.get(ServerConfig.DRONE_FOLLOW_HEIGHT_OFFSET) - 0.1;
            helper.assertTrue(drone.getY() >= minY,
                    "Drone should stay at/above the target's eyes plus the follow height offset, droneY=" + drone.getY() + " minY=" + minY);
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
            // the (default) follow exit distance from where the drone currently is.
            zombie.moveTo(zombie.getX() + 6.0, zombie.getY(), zombie.getZ());

            helper.runAfterDelay(3, () -> {
                helper.assertTrue(drone.getState() == DroneState.CHASING,
                        "Drone should switch back to CHASING once its target moves beyond the follow exit distance, state="
                                + drone.getState());

                helper.succeedWhen(() -> helper.assertTrue(drone.getState() == DroneState.FOLLOWING,
                        "Drone should return to FOLLOWING once it catches back up to the moved target, state=" + drone.getState()));
            });
        });
    }

    // --- 7. Losing the target (DESIGN.md 3.5) ---

    @GameTest(template = "empty", timeoutTicks = 90)
    public static void losesTargetOnDeath(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        Zombie zombie = spawnStationaryZombie(helper, 4, 1, 7);
        drone.setDroneData(droneTargetingZombieEntity());

        helper.runAfterDelay(30, () -> {
            helper.assertTrue(drone.getSeekTarget() == zombie,
                    "Sanity: drone should have acquired the zombie before killing it, target=" + drone.getSeekTarget());
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
        });
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

    @GameTest(template = "empty", timeoutTicks = 100)
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
        return new DroneData(data.droneId(), data.groupId(), data.ownerId(), data.energy(), data.health(), upgrades, data.config());
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
