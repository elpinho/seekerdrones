package com.elpinho.seekerdrones.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.elpinho.seekerdrones.SeekerDrones;
import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.drone.DroneConfig;
import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.drone.DroneItem;
import com.elpinho.seekerdrones.drone.DronePermissions;
import com.elpinho.seekerdrones.drone.DroneState;
import com.elpinho.seekerdrones.drone.DroneStats;
import com.elpinho.seekerdrones.drone.TargetEntry;
import com.elpinho.seekerdrones.drone.TargetMatcher;
import com.elpinho.seekerdrones.drone.UpgradeItem;
import com.elpinho.seekerdrones.drone.UpgradeType;
import com.elpinho.seekerdrones.operator.OperatorGroups;
import com.elpinho.seekerdrones.registry.ModEntityTypes;
import com.elpinho.seekerdrones.registry.ModItems;
import com.mojang.authlib.GameProfile;

import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * M4 (upgrades) GameTests: upgrade items, {@link DroneData#withUpgradeCount} energy/health arrive-full and clamp
 * rules, the {@code /seekerdrones upgrade} caps, Patrol (state, flight, center, radius, resuming after losing a
 * target), Explosive (chase, never FOLLOWING, explode without block damage), Transmitter (operator notification and
 * cooldown), the drone owner (DESIGN.md section 6.3) and NBT persistence of patrol/owner/rest-position fields
 * (DESIGN.md sections 2.2, 3.1, 3.2, 3.5, 4, 6.2, 6.3, 8.2, 9).
 *
 * All tests share the {@code seekerdrones:empty} structure template. Zombies used as bait are stationary
 * ({@code setNoAi(true)}, {@code setNoGravity(true)}) and invulnerable unless a test needs them to take damage or
 * die, matching the convention in {@code SeekingGameTests}.
 */
@GameTestHolder("seekerdrones")
@PrefixGameTestTemplate(false)
public class UpgradeGameTests {

    private static final String SELECTOR = "@e[type=seekerdrones:drone,distance=..3]";

    // --- 1. Upgrade items (DESIGN.md section 4) ---

    @GameTest(template = "empty", timeoutTicks = 5)
    public static void everyUpgradeTypeHasARegisteredUpgradeItem(GameTestHelper helper) {
        for (UpgradeType type : UpgradeType.values()) {
            UpgradeItem item = ModItems.upgrade(type).get();
            helper.assertTrue(item.getType() == type, "Upgrade item for " + type + " should report its own type, was " + item.getType());
            ResourceLocation expectedId = ResourceLocation.fromNamespaceAndPath(SeekerDrones.MODID, UpgradeItem.itemName(type));
            ResourceLocation actualId = BuiltInRegistries.ITEM.getKey(item);
            helper.assertTrue(expectedId.equals(actualId),
                    "Upgrade item for " + type + " should be registered as " + expectedId + ", was " + actualId);
        }
        helper.succeed();
    }

    // --- 2. DroneData.withUpgradeCount: Energy/Health arrive full, and clamp/remove the map key (DESIGN.md 4, 8.2) ---

    @GameTest(template = "empty", timeoutTicks = 5)
    public static void withUpgradeCountRaisesAndClampsEnergyAndHealth(GameTestHelper helper) {
        DroneData fresh = DroneData.createNew();
        int perEnergy = ServerConfig.get(ServerConfig.UPGRADES_ENERGY_PER_UPGRADE);
        double perHealth = ServerConfig.get(ServerConfig.UPGRADES_HEALTH_PER_UPGRADE);
        int baseMaxEnergy = DroneStats.maxEnergy(fresh);
        float baseMaxHealth = DroneStats.maxHealth(fresh);

        // A drone at partial energy/health stays that far below max once the extra capacity arrives full.
        DroneData partial = fresh.withEnergy(baseMaxEnergy / 2).withHealth(baseMaxHealth / 2);

        DroneData withEnergyUpgrade = partial.withUpgradeCount(UpgradeType.ENERGY, 1);
        helper.assertTrue(DroneStats.maxEnergy(withEnergyUpgrade) == baseMaxEnergy + perEnergy,
                "Adding 1 Energy upgrade should raise max energy by the per-upgrade amount, max=" + DroneStats.maxEnergy(withEnergyUpgrade));
        helper.assertTrue(withEnergyUpgrade.energy() == partial.energy() + perEnergy,
                "Adding 1 Energy upgrade should raise current energy by the same added capacity, was " + withEnergyUpgrade.energy());

        DroneData withHealthUpgrade = partial.withUpgradeCount(UpgradeType.HEALTH, 1);
        helper.assertTrue(DroneStats.maxHealth(withHealthUpgrade) == baseMaxHealth + perHealth,
                "Adding 1 Health upgrade should raise max HP by the per-upgrade amount, max=" + DroneStats.maxHealth(withHealthUpgrade));
        helper.assertTrue(Math.abs(withHealthUpgrade.health() - (partial.health() + perHealth)) < 1.0E-4,
                "Adding 1 Health upgrade should raise current HP by the same added capacity, was " + withHealthUpgrade.health());

        // Removing the upgrade clamps current energy/HP down to the new (lower) max, and drops the map key entirely.
        DroneData fullEnergy = withEnergyUpgrade.withEnergy(DroneStats.maxEnergy(withEnergyUpgrade));
        DroneData energyRemoved = fullEnergy.withUpgradeCount(UpgradeType.ENERGY, 0);
        helper.assertTrue(energyRemoved.energy() == baseMaxEnergy,
                "Removing the Energy upgrade should clamp current energy down to the new max, was " + energyRemoved.energy());
        helper.assertFalse(energyRemoved.upgrades().containsKey(UpgradeType.ENERGY),
                "Removing the last Energy upgrade should drop the map key entirely, upgrades=" + energyRemoved.upgrades());

        DroneData fullHealth = withHealthUpgrade.withHealth(DroneStats.maxHealth(withHealthUpgrade));
        DroneData healthRemoved = fullHealth.withUpgradeCount(UpgradeType.HEALTH, 0);
        helper.assertTrue(Math.abs(healthRemoved.health() - baseMaxHealth) < 1.0E-4,
                "Removing the Health upgrade should clamp current HP down to the new max, was " + healthRemoved.health());
        helper.assertFalse(healthRemoved.upgrades().containsKey(UpgradeType.HEALTH),
                "Removing the last Health upgrade should drop the map key entirely, upgrades=" + healthRemoved.upgrades());
        helper.succeed();
    }

    // --- 3. /seekerdrones upgrade caps and effects (DESIGN.md section 4) ---

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void upgradeSetOverPerTypeCapFailsAndLeavesDroneUnchanged(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        DroneData before = drone.snapshotData();

        // X-ray has a per-type cap of 1 by default.
        runCommand(helper, "seekerdrones upgrade set xray 2 " + SELECTOR);

        helper.runAfterDelay(3, () -> {
            helper.assertValueEqual(drone.snapshotData(), before,
                    "Exceeding the per-type cap should fail and leave the drone's data unchanged");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void upgradeSetOverTotalSlotsFailsAndLeavesDroneUnchanged(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        DroneData before = drone.snapshotData();
        int originalSlots = ServerConfig.get(ServerConfig.UPGRADES_TOTAL_SLOTS);
        ServerConfig.UPGRADES_TOTAL_SLOTS.set(1);

        // 2 Sight upgrades is within the per-type cap (4) but over the (lowered) total slot limit of 1.
        runCommand(helper, "seekerdrones upgrade set sight 2 " + SELECTOR);

        helper.runAfterDelay(3, () -> {
            ServerConfig.UPGRADES_TOTAL_SLOTS.set(originalSlots);
            helper.assertValueEqual(drone.snapshotData(), before,
                    "Exceeding the total slot limit should fail and leave the drone's data unchanged");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void validUpgradeSetAppliesToEntityAndUpdatesMaxHealthAttribute(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));

        runCommand(helper, "seekerdrones upgrade set health 2 " + SELECTOR);

        helper.runAfterDelay(3, () -> {
            DroneData data = drone.snapshotData();
            helper.assertTrue(data.upgradeCount(UpgradeType.HEALTH) == 2,
                    "Command should have set 2 Health upgrades, was " + data.upgradeCount(UpgradeType.HEALTH));
            float expectedMax = DroneStats.maxHealth(data);
            AttributeInstance maxHealthAttr = drone.getAttribute(Attributes.MAX_HEALTH);
            helper.assertTrue(maxHealthAttr != null && Math.abs(maxHealthAttr.getValue() - expectedMax) < 1.0E-4,
                    "Entity's max health attribute should reflect the installed Health upgrades, was "
                            + (maxHealthAttr != null ? maxHealthAttr.getValue() : "null") + " expected " + expectedMax);
            helper.assertTrue(Math.abs(drone.getHealth() - expectedMax) < 1.0E-4,
                    "Health upgrades should arrive full, health=" + drone.getHealth() + " max=" + expectedMax);
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void upgradeClearRemovesAllInstalledUpgrades(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        drone.setDroneData(dataWithUpgrades(drone.snapshotData(), Map.of(UpgradeType.SIGHT, 2, UpgradeType.HEALTH, 1)));

        runCommand(helper, "seekerdrones upgrade clear " + SELECTOR);

        helper.runAfterDelay(3, () -> {
            helper.assertTrue(drone.snapshotData().upgrades().isEmpty(),
                    "upgrade clear should remove every upgrade, upgrades=" + drone.snapshotData().upgrades());
            helper.succeed();
        });
    }

    // --- 4. Patrol (DESIGN.md sections 3.2, 3.5) ---

    @GameTest(template = "empty", timeoutTicks = 150)
    public static void patrolDroneFliesCircleAroundItsPatrolCenter(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        BlockPos spawnPos = drone.blockPosition();
        DroneData base = dataWithUpgrades(DroneData.createNew(), Map.of(UpgradeType.PATROL, 1));
        DroneData data = base.withConfig(base.config().withPatrolRadius(Optional.of(4)));
        drone.setDroneData(data);

        helper.runAfterDelay(10, () -> {
            helper.assertTrue(drone.getState() == DroneState.PATROLLING,
                    "A drone with a Patrol upgrade, no target and not drifting should be PATROLLING, state=" + drone.getState());
            GlobalPos center = drone.getPatrolCenter();
            helper.assertTrue(center != null, "Drone should have picked a patrol center (summoned-drone fallback, section 3.2)");
            helper.assertValueEqual(drone.getRestPosition(), spawnPos,
                    "A summoned drone with no configured center or rest position should record its spawn spot as its rest position");
            Vec3 start = drone.position();

            helper.runAfterDelay(60, () -> {
                helper.assertTrue(drone.position().distanceTo(start) > 0.5,
                        "Patrolling drone should have moved over time, stayed at " + drone.position());
                Vec3 centerPos = Vec3.atBottomCenterOf(center.pos());
                double dx = drone.getX() - centerPos.x;
                double dz = drone.getZ() - centerPos.z;
                double horizontal = Math.sqrt(dx * dx + dz * dz);
                helper.assertTrue(Math.abs(horizontal - 4.0) <= 1.5,
                        "Drone should be flying near its configured patrol radius (4), horizontal distance to center=" + horizontal);
                helper.succeed();
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 5)
    public static void configuredPatrolRadiusAboveUpgradeMaxIsCappedAtMax(GameTestHelper helper) {
        DroneData base = dataWithUpgrades(DroneData.createNew(), Map.of(UpgradeType.PATROL, 1));
        int max = DroneStats.maxPatrolRadius(base);
        DroneData configured = base.withConfig(base.config().withPatrolRadius(Optional.of(max + 500)));
        helper.assertTrue(DroneStats.patrolRadius(configured) == max,
                "A configured patrol radius above the upgrade max should be capped at the max, was "
                        + DroneStats.patrolRadius(configured) + " (max=" + max + ")");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 90)
    public static void configuredPatrolCenterUsedInSameDimensionIgnoredInAnother(GameTestHelper helper) {
        // Drift to rest first, so the drone has a real (non-null) rest position to fall back to below.
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        drone.setDeltaMovement(0.3, 0.0, 0.0);
        drone.startDrifting();

        helper.runAfterDelay(60, () -> {
            helper.assertFalse(drone.isDrifting(), "Sanity: drone should have come to rest");
            BlockPos restPos = drone.getRestPosition();
            helper.assertTrue(restPos != null, "Sanity: drone should have recorded a rest position");

            DroneData base = dataWithUpgrades(drone.snapshotData(), Map.of(UpgradeType.PATROL, 1));
            GlobalPos sameDim = GlobalPos.of(helper.getLevel().dimension(), helper.absolutePos(new BlockPos(2, 3, 2)));
            drone.setDroneData(base.withConfig(base.config().withPatrolCenter(Optional.of(sameDim))));

            helper.assertValueEqual(drone.getPatrolCenter(), sameDim,
                    "A patrol center configured in the drone's own dimension should be used as-is, ignoring the rest position");

            GlobalPos otherDim = GlobalPos.of(Level.NETHER, sameDim.pos());
            drone.setDroneData(base.withConfig(base.config().withPatrolCenter(Optional.of(otherDim))));

            GlobalPos fallback = drone.getPatrolCenter();
            helper.assertTrue(fallback != null && fallback.pos().equals(restPos),
                    "A patrol center configured in another dimension should be ignored, falling back to the drone's rest position ("
                            + restPos + "), was " + fallback);
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void removingPatrolUpgradesMidPatrolGoesIdle(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        DroneData patrolling = dataWithUpgrades(DroneData.createNew(), Map.of(UpgradeType.PATROL, 1));
        drone.setDroneData(patrolling);

        helper.runAfterDelay(10, () -> {
            helper.assertTrue(drone.getState() == DroneState.PATROLLING, "Sanity: drone should be patrolling, state=" + drone.getState());

            drone.setDroneData(dataWithUpgrades(patrolling, Map.of()));

            helper.runAfterDelay(5, () -> {
                helper.assertTrue(drone.getState() == DroneState.IDLE,
                        "Removing all Patrol upgrades mid-patrol should return the drone to IDLE, state=" + drone.getState());
                helper.succeed();
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void patrolDroneReturnsToPatrollingAfterLosingItsTarget(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        Zombie zombie = spawnStationaryZombie(helper, 4, 1, 7);
        DroneData data = dataWithUpgrades(droneTargetingZombieEntity(), Map.of(UpgradeType.PATROL, 1));
        drone.setDroneData(data);

        helper.runAfterDelay(20, () -> {
            helper.assertTrue(drone.getSeekTarget() == zombie, "Sanity: drone should have acquired the zombie, target=" + drone.getSeekTarget());
            zombie.setInvulnerable(false);
            zombie.kill();

            helper.succeedWhen(() -> helper.assertTrue(drone.getState() == DroneState.PATROLLING,
                    "A Patrol drone should go back to patrolling after losing its target, state=" + drone.getState()));
        });
    }

    // --- 5. Explosive (DESIGN.md sections 2.5, 3.1) ---

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void explosiveDroneChasesNeverFollowsAndExplodesWithoutBreakingBlocks(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        Zombie zombie = helper.spawn(EntityType.ZOMBIE, new BlockPos(4, 1, 7));
        zombie.setNoAi(true);
        zombie.setNoGravity(true);
        // Block sunlight above the zombie's column so it can't catch fire and die before the explosion. This is well
        // above the drone's straight-line raycast to the zombie's eyes, so it doesn't affect detection.
        helper.setBlock(new BlockPos(4, 5, 7), Blocks.STONE);
        BlockPos guardA = new BlockPos(3, 1, 7);
        BlockPos guardB = new BlockPos(5, 1, 7);
        helper.setBlock(guardA, Blocks.STONE);
        helper.setBlock(guardB, Blocks.STONE);
        float startHealth = zombie.getHealth();

        DroneData data = dataWithUpgrades(droneTargetingZombieEntity(), Map.of(UpgradeType.EXPLOSIVE, 1));
        drone.setDroneData(data);

        // Poll every tick: while the drone is still alive it must never be FOLLOWING (the invariant this test cares
        // about), and once it's gone (exploded), check the aftermath. The chase can close very fast at full speed,
        // so sampling a single fixed tick isn't reliable.
        helper.succeedWhen(() -> {
            if (!drone.isRemoved()) {
                helper.assertTrue(drone.getState() != DroneState.FOLLOWING,
                        "Explosive drone should never enter FOLLOWING while pursuing its target, state=" + drone.getState());
                throw new GameTestAssertException("Explosive drone hasn't reached its target yet");
            }
            helper.assertItemEntityNotPresent(ModItems.DRONE.get());
            helper.assertTrue(zombie.getHealth() < startHealth,
                    "Target should take damage from the explosion, health=" + zombie.getHealth() + " (started at " + startHealth + ")");
            helper.assertBlockPresent(Blocks.STONE, guardA);
            helper.assertBlockPresent(Blocks.STONE, guardB);
        });
    }

    // --- 6. Transmitter (DESIGN.md section 4) ---

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void transmitterNotifiesOnlineGroupOperatorsOnTargetAcquisition(GameTestHelper helper) {
        MinecraftServer server = server(helper);
        UUID owner = UUID.randomUUID();
        UUID groupId = OperatorGroups.get(server).createGroup(owner);
        UUID operatorUuid = UUID.randomUUID();
        OperatorGroups.get(server).addOperator(groupId, operatorUuid);

        List<Component> received = new ArrayList<>();
        ServerPlayer operator = spawnMessageCapturingOperator(helper, operatorUuid, received);
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        Zombie zombie = spawnStationaryZombie(helper, 4, 1, 7);
        DroneData data = dataWithUpgrades(droneTargetingZombieEntity(), Map.of(UpgradeType.TRANSMITTER, 1)).withGroupId(Optional.of(groupId));
        drone.setDroneData(data);

        helper.runAfterDelay(20, () -> {
            try {
                helper.assertTrue(drone.getSeekTarget() == zombie, "Sanity: drone should have acquired the zombie, target=" + drone.getSeekTarget());
                long messages = countTransmitterMessages(received);
                helper.assertTrue(messages == 1,
                        "The drone's online group operator should get exactly one Transmitter message, got " + messages);
            } finally {
                server.getPlayerList().remove(operator);
            }
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void transmitterNotifiesOwnerWhenDroneHasNoGroup(GameTestHelper helper) {
        MinecraftServer server = server(helper);
        UUID ownerUuid = UUID.randomUUID();

        List<Component> received = new ArrayList<>();
        ServerPlayer owner = spawnMessageCapturingOperator(helper, ownerUuid, received);
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        Zombie zombie = spawnStationaryZombie(helper, 4, 1, 7);
        DroneData data = dataWithUpgrades(droneTargetingZombieEntity(), Map.of(UpgradeType.TRANSMITTER, 1)).withOwnerId(Optional.of(ownerUuid));
        drone.setDroneData(data);

        helper.runAfterDelay(20, () -> {
            try {
                helper.assertTrue(drone.getSeekTarget() == zombie, "Sanity: drone should have acquired the zombie, target=" + drone.getSeekTarget());
                long messages = countTransmitterMessages(received);
                helper.assertTrue(messages == 1,
                        "A groupless drone's online owner should get exactly one Transmitter message, got " + messages);
            } finally {
                server.getPlayerList().remove(owner);
            }
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 5)
    public static void unownedDroneHasNoOnlineOperatorsToNotify(GameTestHelper helper) {
        MinecraftServer server = server(helper);
        DroneData data = DroneData.createNew(); // no group, no owner
        helper.assertTrue(DronePermissions.onlineOperators(server, data).isEmpty(),
                "An unowned drone should have no online operators to notify, got " + DronePermissions.onlineOperators(server, data));
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void transmitterCooldownSuppressesSecondMessageWithinWindow(GameTestHelper helper) {
        MinecraftServer server = server(helper);
        UUID owner = UUID.randomUUID();
        UUID groupId = OperatorGroups.get(server).createGroup(owner);
        UUID operatorUuid = UUID.randomUUID();
        OperatorGroups.get(server).addOperator(groupId, operatorUuid);

        List<Component> received = new ArrayList<>();
        ServerPlayer operator = spawnMessageCapturingOperator(helper, operatorUuid, received);
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        Zombie first = spawnStationaryZombie(helper, 4, 1, 7);
        DroneData data = dataWithUpgrades(droneTargetingZombieEntity(), Map.of(UpgradeType.TRANSMITTER, 1)).withGroupId(Optional.of(groupId));
        drone.setDroneData(data);

        helper.runAfterDelay(20, () -> {
            helper.assertTrue(drone.getSeekTarget() == first, "Sanity: drone should have acquired the first zombie, target=" + drone.getSeekTarget());
            helper.assertTrue(countTransmitterMessages(received) == 1,
                    "First acquisition should send exactly one message, got " + countTransmitterMessages(received));

            first.setInvulnerable(false);
            first.kill();
            Zombie second = spawnStationaryZombie(helper, 4, 1, 7);

            helper.runAfterDelay(20, () -> {
                try {
                    helper.assertTrue(drone.getSeekTarget() == second,
                            "Sanity: drone should have re-acquired a new target within the cooldown window, target=" + drone.getSeekTarget());
                    long messages = countTransmitterMessages(received);
                    helper.assertTrue(messages == 1,
                            "A second acquisition within the Transmitter cooldown should send no additional message, got " + messages);
                } finally {
                    server.getPlayerList().remove(operator);
                }
                helper.succeed();
            });
        });
    }

    // --- 7. Drone owner (DESIGN.md section 6.3) ---

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void handDeployingOwnerlessDroneSetsOwnerToDeployingPlayer(GameTestHelper helper) {
        Player player = spawnSneakingPlayer(helper, 4, 1, 4);
        ItemStack stack = DroneItem.createStack(DroneData.createNew());
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);

        ModItems.DRONE.get().use(helper.getLevel(), player, InteractionHand.MAIN_HAND);

        DroneEntity drone = helper.findOneEntity(ModEntityTypes.DRONE.get());
        helper.assertValueEqual(drone.snapshotData().ownerId(), Optional.of(player.getUUID()),
                "Hand-deploying an ownerless drone should set its owner to the deploying player");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void handDeployingAlreadyOwnedDroneKeepsOriginalOwner(GameTestHelper helper) {
        UUID originalOwner = UUID.randomUUID();
        DroneData owned = DroneData.createNew().withOwnerId(Optional.of(originalOwner));
        // A different player, using the server-operator bypass (section 6.2), so deploying someone else's owned drone is allowed.
        Player admin = spawnPlayer(helper, UUID.randomUUID(), 4, 1, 4, true, 2);
        ItemStack stack = DroneItem.createStack(owned);
        admin.setItemInHand(InteractionHand.MAIN_HAND, stack);

        ModItems.DRONE.get().use(helper.getLevel(), admin, InteractionHand.MAIN_HAND);

        DroneEntity drone = helper.findOneEntity(ModEntityTypes.DRONE.get());
        helper.assertValueEqual(drone.snapshotData().ownerId(), Optional.of(originalOwner),
                "Hand-deploying an already-owned drone should keep its original owner, even when deployed by someone else");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void ownerOnlyGrouplessDronePermissionChecks(GameTestHelper helper) {
        MinecraftServer server = server(helper);
        UUID owner = UUID.randomUUID();
        DroneData data = DroneData.createNew().withOwnerId(Optional.of(owner));

        Player ownerPlayer = spawnPlayer(helper, owner, 4, 1, 4, true, 0);
        helper.assertTrue(DronePermissions.canInteract(ownerPlayer, data), "The owner of a groupless drone should be able to interact with it");

        Player stranger = spawnPlayer(helper, UUID.randomUUID(), 4, 1, 4, true, 0);
        helper.assertFalse(DronePermissions.canInteract(stranger, data), "A non-owner should not be able to interact with a groupless owned drone");

        Player admin = spawnPlayer(helper, UUID.randomUUID(), 4, 1, 4, true, 2);
        helper.assertTrue(DronePermissions.canInteract(admin, data), "A permission-level-2 player should bypass the owner check");

        helper.assertTrue(DronePermissions.isOperator(server, data, owner), "isOperator should be true for the owner");
        helper.assertFalse(DronePermissions.isOperator(server, data, stranger.getUUID()), "isOperator should be false for a non-owner");
        helper.assertFalse(DronePermissions.isOperator(server, data, admin.getUUID()),
                "isOperator must ignore the permission bypass; the bypassing admin is still not an operator");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void groupIgnoresOwnerFieldWhenBothArePresent(GameTestHelper helper) {
        MinecraftServer server = server(helper);
        UUID owner = UUID.randomUUID(); // stored on the drone's "ownerId" field, distinct from the group
        UUID groupOwner = UUID.randomUUID();
        UUID groupId = OperatorGroups.get(server).createGroup(groupOwner);
        DroneData data = DroneData.createNew().withOwnerId(Optional.of(owner)).withGroupId(Optional.of(groupId));

        helper.assertFalse(DronePermissions.isOperator(server, data, owner),
                "With a group present, the owner field should be ignored: the owner isn't automatically an operator");
        helper.assertTrue(DronePermissions.isOperator(server, data, groupOwner), "Sanity: the group's own owner should still be an operator");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void playerSeekNeverTargetsOwnerOfGrouplessDrone(GameTestHelper helper) {
        Player owner = spawnPlayer(helper, UUID.randomUUID(), 4, 1, 4, false, 0);
        TargetEntry playerEntry = new TargetEntry(TargetEntry.Kind.PLAYER_NAME, owner.getGameProfile().getName());
        DroneData base = DroneData.createNew().withConfig(DroneConfig.createDefault().withTargets(List.of(playerEntry)));
        DroneData data = dataWithUpgrades(base, Map.of(UpgradeType.PLAYER_SEEK, 1)).withOwnerId(Optional.of(owner.getUUID()));
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(2, 3, 2));

        helper.assertFalse(TargetMatcher.of(data).matches(drone, owner),
                "The owner of a groupless drone should never be targeted, even with Player Seek and a matching entry");

        DroneData withoutOwnerExemption = data.withOwnerId(Optional.empty());
        helper.assertTrue(TargetMatcher.of(withoutOwnerExemption).matches(drone, owner),
                "Sanity: the same player without the owner exemption should be a valid target");
        helper.succeed();
    }

    // --- 8. Persistence (DESIGN.md sections 3.2, 6.3, 8.2) ---

    @GameTest(template = "empty", timeoutTicks = 90)
    public static void patrolCenterRadiusOwnerAndRestPositionSurviveNbtRoundTrip(GameTestHelper helper) {
        DroneEntity original = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        original.setDeltaMovement(0.3, 0.0, 0.0);
        original.startDrifting();

        helper.runAfterDelay(60, () -> {
            helper.assertFalse(original.isDrifting(), "Sanity: drone should have come to rest");
            BlockPos restPos = original.getRestPosition();
            helper.assertTrue(restPos != null, "Sanity: drone should have recorded a rest position");

            UUID owner = UUID.randomUUID();
            GlobalPos configuredCenter = GlobalPos.of(helper.getLevel().dimension(), helper.absolutePos(new BlockPos(2, 3, 2)));
            DroneData base = dataWithUpgrades(DroneData.createNew(), Map.of(UpgradeType.PATROL, 2));
            DroneData data = base.withOwnerId(Optional.of(owner))
                    .withConfig(base.config().withPatrolCenter(Optional.of(configuredCenter)).withPatrolRadius(Optional.of(12)));
            original.setDroneData(data);

            // saveWithoutId still writes the "UUID" tag despite its name, so discard the original first: otherwise
            // the loaded copy would collide with it (same UUID, both alive) and never get added/ticked properly.
            CompoundTag tag = original.saveWithoutId(new CompoundTag());
            original.discard();
            DroneEntity loaded = ModEntityTypes.DRONE.get().create(helper.getLevel());
            loaded.load(tag);
            helper.getLevel().addFreshEntity(loaded);

            DroneData loadedData = loaded.snapshotData();
            helper.assertValueEqual(loadedData.ownerId(), Optional.of(owner), "ownerId after NBT round-trip");
            helper.assertValueEqual(loadedData.config().patrolCenter(), Optional.of(configuredCenter), "patrol center (with dimension) after NBT round-trip");
            helper.assertValueEqual(loadedData.config().patrolRadius(), Optional.of(12), "patrol radius after NBT round-trip");
            helper.assertValueEqual(loaded.getRestPosition(), restPos, "entity rest position after NBT round-trip");
            helper.succeed();
        });
    }

    // --- Helpers ---

    private static MinecraftServer server(GameTestHelper helper) {
        return helper.getLevel().getServer();
    }

    /**
     * How many Transmitter notifications a captured message list contains. Filters out unrelated system messages
     * that a real online player also receives, e.g. other concurrently running tests' players joining/leaving.
     */
    private static long countTransmitterMessages(List<Component> received) {
        return received.stream().filter(UpgradeGameTests::isTransmitterMessage).count();
    }

    private static boolean isTransmitterMessage(Component component) {
        return component.getContents() instanceof TranslatableContents contents
                && contents.getKey().equals("message.seekerdrones.transmitter");
    }

    private static void runCommand(GameTestHelper helper, String command) {
        MinecraftServer server = server(helper);
        Vec3 pos = helper.absoluteVec(new Vec3(4, 3, 4));
        CommandSourceStack source = server.createCommandSourceStack()
                .withLevel(helper.getLevel())
                .withPosition(pos)
                .withPermission(4);
        server.getCommands().performPrefixedCommand(source, command);
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

    private static Player spawnSneakingPlayer(GameTestHelper helper, double x, double y, double z) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        Vec3 pos = helper.absoluteVec(new Vec3(x, y, z));
        player.moveTo(pos.x, pos.y, pos.z, 0.0F, 0.0F);
        player.setShiftKeyDown(true);
        return player;
    }

    /** A mock player at a fixed UUID and permission level, positioned in the test structure. */
    private static Player spawnPlayer(GameTestHelper helper, UUID uuid, double x, double y, double z, boolean sneaking, int permissionLevel) {
        Player player = new Player(helper.getLevel(), BlockPos.ZERO, 0.0F, new GameProfile(uuid, "upgrade-test-player-" + uuid)) {
            @Override
            public boolean isSpectator() {
                return false;
            }

            @Override
            public boolean isCreative() {
                return false;
            }

            @Override
            protected int getPermissionLevel() {
                return permissionLevel;
            }
        };
        Vec3 pos = helper.absoluteVec(new Vec3(x, y, z));
        player.moveTo(pos.x, pos.y, pos.z, 0.0F, 0.0F);
        player.setShiftKeyDown(sneaking);
        return player;
    }

    /**
     * A real, online {@link ServerPlayer} (added to the server's player list, like {@link OperatorGroups}
     * lookups require) at the given UUID, whose {@code sendSystemMessage} calls are captured into {@code received}
     * instead of being sent over a (non-functional, in a GameTest) network connection.
     */
    private static ServerPlayer spawnMessageCapturingOperator(GameTestHelper helper, UUID uuid, List<Component> received) {
        MinecraftServer server = server(helper);
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(uuid, "transmitter-test-operator-" + uuid), false);
        ServerPlayer player = new ServerPlayer(server, helper.getLevel(), cookie.gameProfile(), cookie.clientInformation()) {
            @Override
            public boolean isSpectator() {
                return false;
            }

            @Override
            public boolean isCreative() {
                return false;
            }

            @Override
            public void sendSystemMessage(Component message) {
                received.add(message);
            }
        };
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        return player;
    }
}
