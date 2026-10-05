package com.elpinho.seekerdrones.gametest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.drone.DroneConfig;
import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.drone.DroneState;
import com.elpinho.seekerdrones.drone.DroneStats;
import com.elpinho.seekerdrones.drone.TargetEntry;
import com.elpinho.seekerdrones.drone.UpgradeType;
import com.elpinho.seekerdrones.registry.ModBlocks;
import com.elpinho.seekerdrones.registry.ModEntityTypes;
import com.elpinho.seekerdrones.station.ChargingStationBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * GameTests for shared target claims (DESIGN.md 3.3, config row {@code drone.maxExplosiveDronesPerTarget} in section
 * 9). Tests that change {@link ServerConfig} values run in their own batch and restore the value when done.
 */
@GameTestHolder("seekerdrones")
@PrefixGameTestTemplate(false)
public class TargetClaimGameTests {

    // --- 1/2. Same team: one drone per entity ---

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void unownedNonExplosiveDronesShareOneTarget(GameTestHelper helper) {
        oneOfTwoTargets(helper, zombieData(), zombieData());
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void sameOwnerNonExplosiveDronesShareOneTarget(GameTestHelper helper) {
        UUID owner = UUID.randomUUID();
        oneOfTwoTargets(helper, zombieData().withOwnerId(Optional.of(owner)), zombieData().withOwnerId(Optional.of(owner)));
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void sameGroupDronesWithDifferentOwnersShareOneTarget(GameTestHelper helper) {
        UUID group = UUID.randomUUID();
        oneOfTwoTargets(helper,
                zombieData().withGroupId(Optional.of(group)).withOwnerId(Optional.of(UUID.randomUUID())),
                zombieData().withGroupId(Optional.of(group)).withOwnerId(Optional.of(UUID.randomUUID())));
    }

    @GameTest(template = "empty", timeoutTicks = 90)
    public static void sameTeamDronesTakeDifferentTargetsWhenThereAreTwoMobs(GameTestHelper helper) {
        DroneEntity a = spawnDrone(helper, 3, 3, 3, zombieData());
        DroneEntity b = spawnDrone(helper, 5, 3, 3, zombieData());
        Zombie z1 = stationaryZombie(helper, 3, 1, 7);
        Zombie z2 = stationaryZombie(helper, 5, 1, 7);
        helper.succeedWhen(() -> {
            Entity ta = a.getSeekTarget();
            Entity tb = b.getSeekTarget();
            helper.assertTrue(ta != null && tb != null, "Both drones should have a target, a=" + ta + " b=" + tb);
            helper.assertTrue(ta != tb, "Drones should target different mobs, both target " + ta);
            helper.assertTrue((ta == z1 || ta == z2) && (tb == z1 || tb == z2), "Targets should be the two zombies");
        });
    }

    // --- 3. Release / handover ---

    @GameTest(template = "empty", timeoutTicks = 90)
    public static void removedClaimingDroneHandsTargetToTheOtherDrone(GameTestHelper helper) {
        DroneEntity a = spawnDrone(helper, 3, 3, 3, zombieData());
        DroneEntity b = spawnDrone(helper, 5, 3, 3, zombieData());
        Zombie zombie = stationaryZombie(helper, 4, 1, 7);
        AtomicBoolean discarded = new AtomicBoolean(false);
        DroneEntity[] loser = new DroneEntity[1];
        helper.succeedWhen(() -> {
            if (!discarded.get()) {
                DroneEntity winner = a.getSeekTarget() == zombie ? a : b.getSeekTarget() == zombie ? b : null;
                helper.assertTrue(winner != null, "One drone should acquire the zombie first");
                loser[0] = winner == a ? b : a;
                helper.assertTrue(loser[0].getSeekTarget() == null, "The other drone should have no target while the claim is held");
                winner.discard();
                discarded.set(true);
                throw new net.minecraft.gametest.framework.GameTestAssertException("Claiming drone discarded, waiting for handover");
            }
            helper.assertTrue(loser[0].getSeekTarget() == zombie, "Other drone should take over the zombie, target=" + loser[0].getSeekTarget());
        });
    }

    @GameTest(template = "empty", timeoutTicks = 140)
    public static void claimIsReleasedWhenDroneLosesTargetOutOfPursuitRange(GameTestHelper helper) {
        // A sees the zombie at first. B hovers high above and only sees it after it moved up, out of A's pursuit range.
        // (The structure's horizontal extent is walled off with barriers, so distance has to be vertical.)
        DroneEntity a = spawnDrone(helper, 4, 3, 4, zombieData());
        DroneEntity b = spawnDrone(helper, 4, 30, 4, zombieData());
        Zombie zombie = stationaryZombie(helper, 4, 1, 7);
        AtomicBoolean moved = new AtomicBoolean(false);
        helper.onEachTick(() -> {
            if (!moved.get() && a.getSeekTarget() == zombie) {
                helper.assertTrue(b.getSeekTarget() == null, "B is out of sight range and should have no target yet");
                Vec3 pos = helper.absoluteVec(new Vec3(4.5, 29, 7.5));
                zombie.moveTo(pos.x, pos.y, pos.z);
                moved.set(true);
            }
            if (moved.get() && b.getSeekTarget() == zombie && a.getSeekTarget() == null && a.getState() == DroneState.IDLE) {
                helper.succeed();
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void claimIsReleasedWhenDroneStartsReturningForEnergy(GameTestHelper helper) {
        BlockPos stationRel = new BlockPos(7, 3, 7);
        helper.setBlock(stationRel, ModBlocks.CHARGING_STATION.get());
        DroneEntity a = spawnDrone(helper, 1, 3, 1, zombieData());
        DroneEntity b = spawnDrone(helper, 3, 3, 1, zombieData());
        Zombie zombie = stationaryZombie(helper, 2, 1, 5);
        AtomicBoolean drained = new AtomicBoolean(false);
        DroneEntity[] winner = new DroneEntity[1];
        helper.onEachTick(() -> {
            if (!drained.get()) {
                DroneEntity w = a.getSeekTarget() == zombie ? a : b.getSeekTarget() == zombie ? b : null;
                if (w == null) {
                    return;
                }
                DroneEntity l = w == a ? b : a;
                helper.assertTrue(l.getSeekTarget() == null, "The other drone should be blocked, target=" + l.getSeekTarget());
                winner[0] = w;
                // Nearly empty: below the return threshold the drone heads for the station (5.2) and must free the target.
                double distance = w.position().distanceTo(ChargingStationBlockEntity.dockPosition(helper.absolutePos(stationRel)));
                double threshold = DroneStats.returnThreshold(w.snapshotData(), distance);
                w.setDroneData(w.snapshotData().withEnergy(Math.max(1, (int) threshold - 2)));
                drained.set(true);
                return;
            }
            DroneEntity l = winner[0] == a ? b : a;
            DroneState ws = winner[0].getState();
            if ((ws == DroneState.RETURNING || ws == DroneState.CHARGING) && winner[0].getSeekTarget() == null && l.getSeekTarget() == zombie) {
                helper.succeed();
            }
        });
    }

    // --- 4. Different teams ---

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void dronesWithDifferentOwnersBothChaseTheSameMob(GameTestHelper helper) {
        bothTarget(helper, zombieData().withOwnerId(Optional.of(UUID.randomUUID())), zombieData().withOwnerId(Optional.of(UUID.randomUUID())));
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void dronesWithDifferentGroupsBothChaseTheSameMob(GameTestHelper helper) {
        bothTarget(helper, zombieData().withGroupId(Optional.of(UUID.randomUUID())), zombieData().withGroupId(Optional.of(UUID.randomUUID())));
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void groupDroneAndOwnerOnlyDroneOfSameOwnerAreDifferentTeams(GameTestHelper helper) {
        UUID owner = UUID.randomUUID();
        bothTarget(helper,
                zombieData().withOwnerId(Optional.of(owner)).withGroupId(Optional.of(UUID.randomUUID())),
                zombieData().withOwnerId(Optional.of(owner)));
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void ownedDroneAndUnownedDroneAreDifferentTeams(GameTestHelper helper) {
        bothTarget(helper, zombieData().withOwnerId(Optional.of(UUID.randomUUID())), zombieData());
    }

    // --- 5. Skipped entity is not announced ---

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void droneSkippingClaimedMobNeverEntersChasing(GameTestHelper helper) {
        DroneData transmitting = zombieData().withUpgradeCount(UpgradeType.SIREN, 1).withUpgradeCount(UpgradeType.TRANSMITTER, 1);
        DroneEntity a = spawnDrone(helper, 3, 3, 3, transmitting);
        DroneEntity b = spawnDrone(helper, 5, 3, 3, transmitting);
        stationaryZombie(helper, 4, 1, 7);
        AtomicInteger chasing = new AtomicInteger();
        helper.onEachTick(() -> {
            // Every acquisition passes through CHASING, so at most one drone may ever have shown it.
            if (a.getState() == DroneState.CHASING || a.getState() == DroneState.FOLLOWING) {
                chasing.incrementAndGet();
            }
        });
        helper.runAfterDelay(40, () -> {
            boolean aHas = a.getSeekTarget() != null;
            boolean bHas = b.getSeekTarget() != null;
            helper.assertTrue(aHas ^ bHas, "Exactly one drone should hold the target, a=" + aHas + " b=" + bHas);
            DroneEntity skipper = aHas ? b : a;
            helper.assertTrue(skipper.getState() == DroneState.IDLE, "Skipping drone should stay IDLE, state=" + skipper.getState());
            helper.succeed();
        });
    }

    // --- 6. Explosive drones ---

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void explosiveDronesAllGoAfterTheSameMobByDefault(GameTestHelper helper) {
        helper.assertTrue(ServerConfig.get(ServerConfig.DRONE_MAX_EXPLOSIVE_DRONES_PER_TARGET) == 0, "Default should be 0 (no limit)");
        DroneEntity a = spawnDrone(helper, 3, 84, 3, explosiveData());
        DroneEntity b = spawnDrone(helper, 5, 84, 3, explosiveData());
        Zombie zombie = stationaryZombie(helper, 4, 94, 3);
        AtomicBoolean aHad = new AtomicBoolean();
        AtomicBoolean bHad = new AtomicBoolean();
        helper.succeedWhen(() -> {
            aHad.compareAndSet(false, a.getSeekTarget() == zombie);
            bHad.compareAndSet(false, b.getSeekTarget() == zombie);
            helper.assertTrue(aHad.get() && bHad.get(), "Both Explosive drones should go after the zombie, a=" + aHad + " b=" + bHad);
        });
    }

    @GameTest(template = "empty", timeoutTicks = 40, batch = "config_max_explosive_1")
    public static void explosiveLimitOneLetsOnlyOneExplosiveDroneGoAfterTheMob(GameTestHelper helper) {
        limitedExplosiveTest(helper, 1, 2, 120);
    }

    @GameTest(template = "empty", timeoutTicks = 40, batch = "config_max_explosive_2")
    public static void explosiveLimitTwoLetsOnlyTwoOfThreeExplosiveDronesGoAfterTheMob(GameTestHelper helper) {
        limitedExplosiveTest(helper, 2, 3, 160);
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void explosiveDroneIgnoresNonExplosiveClaim(GameTestHelper helper) {
        DroneEntity follower = spawnDrone(helper, 4, 212, 3, zombieData());
        DroneEntity bomb = spawnDrone(helper, 4, 204, 3, explosiveData());
        Zombie zombie = stationaryZombie(helper, 4, 214, 3);
        helper.succeedWhen(() -> {
            helper.assertTrue(!follower.isRemoved() && follower.getSeekTarget() == zombie, "Non-Explosive drone should follow the zombie");
            helper.assertTrue(!bomb.isRemoved() && bomb.getSeekTarget() == zombie, "Explosive drone should still go after the followed zombie");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void nonExplosiveDroneIgnoresExplosiveClaim(GameTestHelper helper) {
        DroneEntity bomb = spawnDrone(helper, 4, 244, 3, explosiveData());
        DroneEntity follower = spawnDrone(helper, 4, 252, 3, zombieData());
        Zombie zombie = stationaryZombie(helper, 4, 254, 3);
        helper.succeedWhen(() -> {
            helper.assertTrue(!bomb.isRemoved() && bomb.getSeekTarget() == zombie, "Explosive drone should chase the zombie");
            helper.assertTrue(!follower.isRemoved() && follower.getSeekTarget() == zombie,
                    "Non-Explosive drone should still follow a zombie an Explosive drone is chasing");
        });
    }

    // --- 7. Players ---

    @GameTest(template = "empty", timeoutTicks = 70)
    public static void sameTeamDronesWithPlayerSeekShareOnePlayerTarget(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        Vec3 pos = helper.absoluteVec(new Vec3(4.5, 1, 7.5));
        player.moveTo(pos.x, pos.y, pos.z, 0.0F, 0.0F);
        helper.getLevel().addFreshEntity(player);
        TargetEntry entry = new TargetEntry(TargetEntry.Kind.PLAYER_NAME, player.getGameProfile().getName());
        DroneData data = DroneData.createNew().withConfig(DroneConfig.createDefault().withTargets(List.of(entry)))
                .withUpgradeCount(UpgradeType.PLAYER_SEEK, 1);
        DroneEntity a = spawnDrone(helper, 3, 3, 3, data);
        DroneEntity b = spawnDrone(helper, 5, 3, 3, data);
        helper.runAfterDelay(45, () -> {
            boolean aHas = a.getSeekTarget() == player;
            boolean bHas = b.getSeekTarget() == player;
            helper.assertTrue(aHas || bHas, "Precondition: a drone should target the mock player (is it in the level?), a=" + a.getSeekTarget()
                    + " b=" + b.getSeekTarget());
            helper.assertTrue(aHas ^ bHas, "Only one same-team drone should target the player, a=" + aHas + " b=" + bHas);
            helper.succeed();
        });
    }

    // --- 8. Reload ---

    @GameTest(template = "empty", timeoutTicks = 90)
    public static void reloadedDroneReclaimsItsSavedTargetWhenThereIsRoom(GameTestHelper helper) {
        DroneEntity a = spawnDrone(helper, 4, 3, 3, zombieData());
        Zombie zombie = stationaryZombie(helper, 4, 1, 7);
        AtomicBoolean reloaded = new AtomicBoolean(false);
        DroneEntity[] copy = new DroneEntity[1];
        helper.succeedWhen(() -> {
            if (!reloaded.get()) {
                helper.assertTrue(a.getSeekTarget() == zombie, "Drone should acquire the zombie first");
                CompoundTag tag = a.saveWithoutId(new CompoundTag());
                a.discard();
                copy[0] = loadDrone(helper, tag);
                reloaded.set(true);
                throw new net.minecraft.gametest.framework.GameTestAssertException("Reloaded, waiting for the target to resolve");
            }
            helper.assertTrue(copy[0].getSeekTarget() == zombie, "Reloaded drone should hold the zombie again, target=" + copy[0].getSeekTarget());
        });
    }

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void reloadedDroneDropsSavedTargetWhenTeamLimitIsReached(GameTestHelper helper) {
        DroneEntity a = spawnDrone(helper, 4, 3, 3, zombieData());
        Zombie zombie = stationaryZombie(helper, 4, 1, 7);
        AtomicInteger phase = new AtomicInteger();
        CompoundTag[] saved = new CompoundTag[1];
        DroneEntity[] b = new DroneEntity[1];
        DroneEntity[] copy = new DroneEntity[1];
        AtomicInteger ticksAfterReload = new AtomicInteger();
        helper.onEachTick(() -> {
            switch (phase.get()) {
                case 0 -> {
                    if (a.getSeekTarget() == zombie) {
                        saved[0] = a.saveWithoutId(new CompoundTag());
                        a.discard();
                        b[0] = spawnDrone(helper, 5, 3, 3, zombieData());
                        phase.set(1);
                    }
                }
                case 1 -> {
                    if (b[0].getSeekTarget() == zombie) {
                        copy[0] = loadDrone(helper, saved[0]);
                        phase.set(2);
                    }
                }
                default -> {
                    if (b[0].getSeekTarget() != zombie) {
                        helper.fail("The claiming drone should keep its target, target=" + b[0].getSeekTarget());
                    }
                    if (copy[0].getSeekTarget() != null) {
                        helper.fail("Reloaded drone must not hold the target while the team's claim is taken");
                    }
                    if (ticksAfterReload.incrementAndGet() == 30) {
                        helper.assertTrue(copy[0].getState() == DroneState.IDLE, "Reloaded drone should be IDLE, state=" + copy[0].getState());
                        helper.succeed();
                    }
                }
            }
        });
    }

    // --- 9. Team change mid-chase ---

    @GameTest(template = "empty", timeoutTicks = 90)
    public static void movingToATeamThatAlreadyClaimsTheMobDropsTheTarget(GameTestHelper helper) {
        UUID x = UUID.randomUUID();
        UUID y = UUID.randomUUID();
        DroneEntity a = spawnDrone(helper, 3, 3, 3, zombieData().withOwnerId(Optional.of(x)));
        DroneEntity b = spawnDrone(helper, 5, 3, 3, zombieData().withOwnerId(Optional.of(y)));
        Zombie zombie = stationaryZombie(helper, 4, 1, 7);
        AtomicBoolean switched = new AtomicBoolean(false);
        AtomicInteger after = new AtomicInteger();
        helper.onEachTick(() -> {
            if (!switched.get()) {
                if (a.getSeekTarget() == zombie && b.getSeekTarget() == zombie) {
                    a.setDroneData(a.snapshotData().withOwnerId(Optional.of(y)));
                    switched.set(true);
                    helper.assertTrue(a.getSeekTarget() == null, "Drone should drop the target right away, target=" + a.getSeekTarget());
                    helper.assertTrue(a.getState() == DroneState.IDLE, "Drone should be IDLE, state=" + a.getState());
                }
                return;
            }
            if (a.getSeekTarget() != null) {
                helper.fail("Drone in the claiming team must not reacquire the mob, target=" + a.getSeekTarget());
            }
            if (b.getSeekTarget() != zombie) {
                helper.fail("The other drone keeps its target");
            }
            if (after.incrementAndGet() == 30) {
                helper.succeed();
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 90)
    public static void movingTeamsFreesTheClaimForTheOldTeam(GameTestHelper helper) {
        UUID x = UUID.randomUUID();
        UUID y = UUID.randomUUID();
        DroneEntity a = spawnDrone(helper, 3, 3, 3, zombieData().withOwnerId(Optional.of(x)));
        DroneEntity b = spawnDrone(helper, 5, 3, 3, zombieData().withOwnerId(Optional.of(x)));
        Zombie zombie = stationaryZombie(helper, 4, 1, 7);
        AtomicBoolean switched = new AtomicBoolean(false);
        DroneEntity[] winner = new DroneEntity[1];
        helper.onEachTick(() -> {
            if (!switched.get()) {
                DroneEntity w = a.getSeekTarget() == zombie ? a : b.getSeekTarget() == zombie ? b : null;
                if (w == null) {
                    return;
                }
                winner[0] = w;
                DroneEntity loser = w == a ? b : a;
                helper.assertTrue(loser.getSeekTarget() == null, "The other same-team drone should be blocked, target=" + loser.getSeekTarget());
                w.setDroneData(w.snapshotData().withOwnerId(Optional.of(y)));
                helper.assertTrue(w.getSeekTarget() == zombie, "Drone moving to an empty team keeps its target");
                switched.set(true);
                return;
            }
            DroneEntity loser = winner[0] == a ? b : a;
            if (loser.getSeekTarget() == zombie && winner[0].getSeekTarget() == zombie) {
                helper.succeed();
            }
        });
    }

    // --- helpers ---

    private static void oneOfTwoTargets(GameTestHelper helper, DroneData dataA, DroneData dataB) {
        DroneEntity a = spawnDrone(helper, 3, 3, 3, dataA);
        DroneEntity b = spawnDrone(helper, 5, 3, 3, dataB);
        Zombie zombie = stationaryZombie(helper, 4, 1, 7);
        helper.runAfterDelay(40, () -> {
            boolean aHas = a.getSeekTarget() == zombie;
            boolean bHas = b.getSeekTarget() == zombie;
            helper.assertTrue(aHas ^ bHas, "Exactly one drone should target the zombie, a=" + a.getSeekTarget() + " b=" + b.getSeekTarget());
            DroneEntity claimer = aHas ? a : b;
            DroneEntity other = aHas ? b : a;
            helper.assertTrue(claimer.getState() == DroneState.CHASING || claimer.getState() == DroneState.FOLLOWING,
                    "Claiming drone should be CHASING or FOLLOWING, state=" + claimer.getState());
            helper.assertTrue(other.getState() == DroneState.IDLE || other.getState() == DroneState.PATROLLING,
                    "Other drone should stay IDLE or PATROLLING, state=" + other.getState());
            helper.succeed();
        });
    }

    private static void bothTarget(GameTestHelper helper, DroneData dataA, DroneData dataB) {
        DroneEntity a = spawnDrone(helper, 3, 3, 3, dataA);
        DroneEntity b = spawnDrone(helper, 5, 3, 3, dataB);
        Zombie zombie = stationaryZombie(helper, 4, 1, 7);
        helper.succeedWhen(() -> helper.assertTrue(a.getSeekTarget() == zombie && b.getSeekTarget() == zombie,
                "Drones of different teams should both target the zombie, a=" + a.getSeekTarget() + " b=" + b.getSeekTarget()));
    }

    /** {@code count} same-team Explosive drones, limit {@code limit}: never more than {@code limit} live drones on the mob. */
    private static void limitedExplosiveTest(GameTestHelper helper, int limit, int count, int band) {
        int original = ServerConfig.get(ServerConfig.DRONE_MAX_EXPLOSIVE_DRONES_PER_TARGET);
        ServerConfig.DRONE_MAX_EXPLOSIVE_DRONES_PER_TARGET.set(limit);
        try {
            Zombie zombie = stationaryZombie(helper, 4, band + 10, 3);
            DroneEntity[] drones = new DroneEntity[count];
            for (int i = 0; i < count; i++) {
                drones[i] = spawnDrone(helper, 2 + i, band, 3, explosiveData());
            }
            AtomicInteger ticks = new AtomicInteger();
            AtomicInteger maxSeen = new AtomicInteger();
            helper.onEachTick(() -> {
                int live = 0;
                for (DroneEntity drone : drones) {
                    if (!drone.isRemoved() && drone.getSeekTarget() == zombie) {
                        live++;
                    }
                }
                maxSeen.set(Math.max(maxSeen.get(), live));
                if (live > limit) {
                    ServerConfig.DRONE_MAX_EXPLOSIVE_DRONES_PER_TARGET.set(original);
                    helper.fail(live + " Explosive drones target the mob with maxExplosiveDronesPerTarget=" + limit);
                    return;
                }
                if (ticks.incrementAndGet() == 12) {
                    ServerConfig.DRONE_MAX_EXPLOSIVE_DRONES_PER_TARGET.set(original);
                    if (maxSeen.get() != limit) {
                        helper.fail("Expected exactly " + limit + " Explosive drone(s) on the mob at once, most seen was " + maxSeen.get());
                        return;
                    }
                    helper.succeed();
                }
            });
            helper.runAfterDelay(35, () -> ServerConfig.DRONE_MAX_EXPLOSIVE_DRONES_PER_TARGET.set(original));
        } catch (RuntimeException e) {
            ServerConfig.DRONE_MAX_EXPLOSIVE_DRONES_PER_TARGET.set(original);
            throw e;
        }
    }

    private static DroneEntity loadDrone(GameTestHelper helper, CompoundTag tag) {
        DroneEntity drone = ModEntityTypes.DRONE.get().create(helper.getLevel());
        drone.load(tag);
        helper.getLevel().addFreshEntity(drone);
        return drone;
    }

    private static DroneEntity spawnDrone(GameTestHelper helper, int x, int y, int z, DroneData data) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(x, y, z));
        drone.setDroneData(data);
        return drone;
    }

    private static DroneData zombieData() {
        DroneData base = DroneData.createNew();
        return base.withConfig(base.config().withTargets(List.of(new TargetEntry(TargetEntry.Kind.ENTITY_TYPE, "minecraft:zombie"))));
    }

    private static DroneData explosiveData() {
        return zombieData().withUpgradeCount(UpgradeType.EXPLOSIVE, 1);
    }

    private static Zombie stationaryZombie(GameTestHelper helper, int x, int y, int z) {
        Zombie zombie = helper.spawn(EntityType.ZOMBIE, new BlockPos(x, y, z));
        zombie.setNoAi(true);
        zombie.setNoGravity(true);
        zombie.setInvulnerable(true);
        return zombie;
    }
}
