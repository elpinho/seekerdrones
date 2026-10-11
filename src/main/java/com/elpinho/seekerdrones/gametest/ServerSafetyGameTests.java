package com.elpinho.seekerdrones.gametest;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.deploying.DeployingStationBlockEntity;
import com.elpinho.seekerdrones.deploying.DeployingStatus;
import com.elpinho.seekerdrones.drone.DeployedDrones;
import com.elpinho.seekerdrones.drone.DroneConfig;
import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.drone.DroneItem;
import com.elpinho.seekerdrones.drone.DroneStats;
import com.elpinho.seekerdrones.drone.ExplosiveBlockDamage;
import com.elpinho.seekerdrones.drone.TargetEntry;
import com.elpinho.seekerdrones.drone.TargetFilter;
import com.elpinho.seekerdrones.drone.TargetMatcher;
import com.elpinho.seekerdrones.drone.UpgradeType;
import com.elpinho.seekerdrones.operator.OperatorGroups;
import com.elpinho.seekerdrones.programming.ProgramRules;
import com.elpinho.seekerdrones.registry.ModBlocks;
import com.elpinho.seekerdrones.registry.ModEntityTypes;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * GameTests for the server-safety configs: Explosive block damage (DESIGN.md 3.1), the target allowlist (3.3), player
 * targets off (4) and the deployed drone limit (2.2, 7.3). Every test that changes {@link ServerConfig} runs in its own
 * batch and restores the values (and the filter caches) when done. Limit tests use random owner UUIDs and drone IDs.
 */
@GameTestHolder("seekerdrones")
@PrefixGameTestTemplate(false)
public class ServerSafetyGameTests {
    private static final BlockPos STATION_REL = new BlockPos(4, 3, 4);

    // --- 1. Explosive block damage ---

    @GameTest(template = "empty", timeoutTicks = 100, batch = "config_explosive_never")
    public static void explosiveDroneBreaksNoBlocksWithNever(GameTestHelper helper) {
        explosiveBlocksTest(helper, ExplosiveBlockDamage.NEVER, null, false);
    }

    @GameTest(template = "empty", timeoutTicks = 100, batch = "config_explosive_always")
    public static void explosiveDroneBreaksBlocksWithAlways(GameTestHelper helper) {
        explosiveBlocksTest(helper, ExplosiveBlockDamage.ALWAYS, null, true);
    }

    @GameTest(template = "empty", timeoutTicks = 100, batch = "config_explosive_mobgriefing_on")
    public static void explosiveDroneBreaksBlocksWithMobGriefingWhenRuleOn(GameTestHelper helper) {
        explosiveBlocksTest(helper, ExplosiveBlockDamage.MOB_GRIEFING, true, true);
    }

    @GameTest(template = "empty", timeoutTicks = 100, batch = "config_explosive_mobgriefing_off")
    public static void explosiveDroneBreaksNoBlocksWithMobGriefingWhenRuleOff(GameTestHelper helper) {
        explosiveBlocksTest(helper, ExplosiveBlockDamage.MOB_GRIEFING, false, false);
    }

    @GameTest(template = "empty", timeoutTicks = 5)
    public static void explosiveBlockDamageDefaultsToNever(GameTestHelper helper) {
        helper.assertValueEqual(ServerConfig.get(ServerConfig.UPGRADES_EXPLOSIVE_BLOCK_DAMAGE), ExplosiveBlockDamage.NEVER, "default");
        helper.succeed();
    }

    private static void explosiveBlocksTest(GameTestHelper helper, ExplosiveBlockDamage mode, Boolean mobGriefing, boolean expectBroken) {
        ExplosiveBlockDamage originalMode = ServerConfig.get(ServerConfig.UPGRADES_EXPLOSIVE_BLOCK_DAMAGE);
        GameRules.BooleanValue rule = helper.getLevel().getGameRules().getRule(GameRules.RULE_MOBGRIEFING);
        boolean originalRule = rule.get();
        MinecraftServer server = helper.getLevel().getServer();
        Runnable restore = () -> {
            ServerConfig.UPGRADES_EXPLOSIVE_BLOCK_DAMAGE.set(originalMode);
            rule.set(originalRule, server);
        };
        ServerConfig.UPGRADES_EXPLOSIVE_BLOCK_DAMAGE.set(mode);
        if (mobGriefing != null) {
            rule.set(mobGriefing, server);
        }
        helper.runAfterDelay(95, restore);

        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        Zombie zombie = helper.spawn(EntityType.ZOMBIE, new BlockPos(4, 1, 7));
        zombie.setNoAi(true);
        zombie.setNoGravity(true);
        helper.setBlock(new BlockPos(4, 5, 7), Blocks.STONE); // shade, so the zombie doesn't burn
        BlockPos[] dirt = {new BlockPos(3, 1, 7), new BlockPos(5, 1, 7), new BlockPos(4, 1, 8)};
        for (BlockPos pos : dirt) {
            helper.setBlock(pos, Blocks.DIRT);
        }
        drone.setDroneData(EnergyGameTests.dataWithUpgrades(zombieTargeting(), Map.of(UpgradeType.EXPLOSIVE, 3)));

        AtomicBoolean restored = new AtomicBoolean(false);
        helper.succeedWhen(() -> {
            if (!drone.isRemoved()) {
                throw new GameTestAssertException("Explosive drone hasn't reached its target yet");
            }
            if (restored.compareAndSet(false, true)) {
                restore.run();
            }
            for (BlockPos pos : dirt) {
                if (expectBroken) {
                    helper.assertBlockNotPresent(Blocks.DIRT, pos);
                } else {
                    helper.assertBlockPresent(Blocks.DIRT, pos);
                }
            }
        });
    }

    // --- 2. Target allowlist ---

    @GameTest(template = "empty", timeoutTicks = 5)
    public static void emptyAllowlistHasNoEffect(GameTestHelper helper) {
        helper.assertTrue(ServerConfig.get(ServerConfig.DRONE_TARGET_ALLOWLIST).isEmpty(), "Allowlist should be empty by default");
        TargetFilter.invalidate();
        helper.assertFalse(TargetFilter.isBlocked(EntityType.ZOMBIE), "zombie not blocked with an empty allowlist");
        helper.assertFalse(TargetFilter.isBlocked(EntityType.PLAYER), "player not blocked with an empty allowlist");
        helper.assertFalse(TargetFilter.blocks(entity("minecraft:zombie")), "entity entry not refused");
        helper.assertFalse(TargetFilter.blocks(new TargetEntry(TargetEntry.Kind.PLAYER_NAME, "Steve")), "player entry not refused");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 5, batch = "config_allowlist_types")
    public static void allowlistBlocksUnlistedTypesAndAllowsListedOnes(GameTestHelper helper) {
        try {
            withAllowlist(List.of("minecraft:skeleton"));
            helper.assertTrue(TargetFilter.isBlocked(EntityType.ZOMBIE), "unlisted zombie should be blocked");
            helper.assertFalse(TargetFilter.isBlocked(EntityType.SKELETON), "listed skeleton should be allowed");
            helper.assertTrue(TargetFilter.blocks(entity("minecraft:zombie")), "unlisted entity entry refused");
            helper.assertFalse(TargetFilter.blocks(entity("minecraft:skeleton")), "listed entity entry allowed");
            helper.succeed();
        } finally {
            withAllowlist(List.of());
        }
    }

    @GameTest(template = "empty", timeoutTicks = 5, batch = "config_allowlist_tags")
    public static void allowlistTagRules(GameTestHelper helper) {
        try {
            withAllowlist(List.of("#minecraft:zombies"));
            helper.assertFalse(TargetFilter.blocks(tag("minecraft:zombies")), "listed tag allowed");
            helper.assertFalse(TargetFilter.isBlocked(EntityType.ZOMBIE), "member of a listed tag allowed");
            helper.assertTrue(TargetFilter.blocks(tag("minecraft:raiders")), "unlisted tag whose members are all blocked is refused");
            helper.assertTrue(TargetFilter.blocks(tag("seekerdrones_test:nonexistent_empty_tag")), "unlisted empty tag refused under an active allowlist");

            withAllowlist(List.of("minecraft:pillager", "minecraft:zombie"));
            helper.assertFalse(TargetFilter.blocks(tag("minecraft:raiders")), "unlisted tag with some allowed members is not refused");

            withAllowlist(List.of("#seekerdrones_test:nonexistent_empty_tag"));
            helper.assertFalse(TargetFilter.blocks(tag("seekerdrones_test:nonexistent_empty_tag")), "listed empty tag is not refused");

            withAllowlist(List.of());
            helper.assertFalse(TargetFilter.blocks(tag("seekerdrones_test:nonexistent_empty_tag")), "empty tag not refused without allowlist");
            helper.succeed();
        } finally {
            withAllowlist(List.of());
        }
    }

    @GameTest(template = "empty", timeoutTicks = 5, batch = "config_allowlist_plus_denylist")
    public static void denylistStillAppliesOnTopOfAllowlist(GameTestHelper helper) {
        try {
            ServerConfig.DRONE_TARGET_DENYLIST.set(List.of("minecraft:zombie"));
            withAllowlist(List.of("minecraft:zombie", "minecraft:skeleton"));
            helper.assertTrue(TargetFilter.isBlocked(EntityType.ZOMBIE), "allowlisted but denylisted zombie is blocked");
            helper.assertFalse(TargetFilter.isBlocked(EntityType.SKELETON), "skeleton is allowed");
            helper.assertTrue(TargetFilter.blocks(entity("minecraft:zombie")), "entry refused");
            helper.succeed();
        } finally {
            ServerConfig.DRONE_TARGET_DENYLIST.set(List.of());
            withAllowlist(List.of());
        }
    }

    @GameTest(template = "empty", timeoutTicks = 5, batch = "config_allowlist_player_names")
    public static void playerNameEntriesFollowWhetherPlayerIsAllowlisted(GameTestHelper helper) {
        TargetEntry name = new TargetEntry(TargetEntry.Kind.PLAYER_NAME, "Steve");
        try {
            withAllowlist(List.of("minecraft:zombie"));
            helper.assertTrue(TargetFilter.blocks(name), "player name blocked when minecraft:player isn't allowlisted");
            withAllowlist(List.of("minecraft:zombie", "minecraft:player"));
            helper.assertFalse(TargetFilter.blocks(name), "player name allowed when minecraft:player is allowlisted");
            helper.succeed();
        } finally {
            withAllowlist(List.of());
        }
    }

    @GameTest(template = "empty", timeoutTicks = 60, batch = "config_allowlist_other_type")
    public static void droneDoesNotAcquireZombieWhenAllowlistOnlyListsSkeletons(GameTestHelper helper) {
        withAllowlist(List.of("minecraft:skeleton"));
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        spawnStationaryZombie(helper, 4, 1, 7);
        drone.setDroneData(zombieTargeting());
        helper.runAfterDelay(35, () -> {
            try {
                helper.assertTrue(drone.getSeekTarget() == null, "Zombie not allowlisted, target=" + drone.getSeekTarget());
                helper.succeed();
            } finally {
                withAllowlist(List.of());
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 60, batch = "config_allowlist_listed_type")
    public static void droneAcquiresZombieWhenAllowlisted(GameTestHelper helper) {
        withAllowlist(List.of("minecraft:zombie"));
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        Zombie zombie = spawnStationaryZombie(helper, 4, 1, 7);
        drone.setDroneData(zombieTargeting());
        helper.runAfterDelay(55, () -> withAllowlist(List.of()));
        helper.succeedWhen(() -> {
            helper.assertTrue(drone.getSeekTarget() == zombie, "Allowlisted zombie should be acquired, target=" + drone.getSeekTarget());
            withAllowlist(List.of());
        });
    }

    @GameTest(template = "empty", timeoutTicks = 5, batch = "config_allowlist_rules")
    public static void programRulesRejectNewEntryNotOnAllowlist(GameTestHelper helper) {
        TargetEntry zombie = entity("minecraft:zombie");
        TargetEntry skeleton = entity("minecraft:skeleton");
        try {
            withAllowlist(List.of("minecraft:skeleton"));
            String problem = ProgramRules.targetProblem(List.of(zombie), 0, List.of(), Map.of());
            helper.assertTrue(problem != null && problem.endsWith("target.not_allowed"), "Unlisted entry rejected, problem=" + problem);
            helper.assertTrue(ProgramRules.targetProblem(List.of(skeleton), 0, List.of(), Map.of()) == null, "Listed entry accepted");
            helper.assertTrue(ProgramRules.targetProblem(List.of(zombie), 0, List.of(zombie), Map.of()) == null, "Existing entry kept");
            helper.succeed();
        } finally {
            withAllowlist(List.of());
        }
    }

    // --- 3. Player targets off ---

    @GameTest(template = "empty", timeoutTicks = 5)
    public static void playerTargetsAllowedByDefaultKeepsPlayerSeekWorking(GameTestHelper helper) {
        DroneData data = playerSeekData("Steve");
        helper.assertTrue(ServerConfig.get(ServerConfig.DRONE_ALLOW_PLAYER_TARGETS), "default is true");
        helper.assertTrue(UpgradeType.PLAYER_SEEK.maxCount() > 0 && !UpgradeType.PLAYER_SEEK.isDisabled(), "Player Seek enabled");
        helper.assertTrue(ProgramRules.canAdd(Map.of(), UpgradeType.PLAYER_SEEK), "Player Seek can be added");
        helper.assertTrue(DroneStats.hasPlayerSeek(data), "hasPlayerSeek true");
        helper.assertTrue(ProgramRules.targetProblem(List.of(playerEntry("Steve")), 0, List.of(), Map.of(UpgradeType.PLAYER_SEEK, 1)) == null,
                "player entry accepted");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 5, batch = "config_players_off_rules")
    public static void playerTargetsOffDisablesPlayerSeekUpgrade(GameTestHelper helper) {
        boolean original = ServerConfig.get(ServerConfig.DRONE_ALLOW_PLAYER_TARGETS);
        try {
            setAllowPlayers(false);
            DroneData data = playerSeekData("Steve");
            helper.assertTrue(UpgradeType.PLAYER_SEEK.maxCount() == 0, "maxCount 0, was " + UpgradeType.PLAYER_SEEK.maxCount());
            helper.assertTrue(UpgradeType.PLAYER_SEEK.isDisabled(), "isDisabled");
            helper.assertFalse(ProgramRules.canAdd(Map.of(), UpgradeType.PLAYER_SEEK), "canAdd false");
            helper.assertFalse(DroneStats.hasPlayerSeek(data), "hasPlayerSeek false with the upgrade installed");
            helper.assertTrue(UpgradeType.SIGHT.maxCount() > 0 && !UpgradeType.SIGHT.isDisabled(), "other upgrades unaffected");
            String problem = ProgramRules.targetProblem(List.of(playerEntry("Steve")), 0, List.of(), Map.of(UpgradeType.PLAYER_SEEK, 1));
            helper.assertTrue(problem != null && problem.endsWith("target.players_disabled"), "players_disabled expected, was " + problem);
            helper.assertTrue(ProgramRules.targetProblem(List.of(entity("minecraft:zombie")), 0, List.of(), Map.of()) == null,
                    "entity entries unaffected");
            helper.succeed();
        } finally {
            setAllowPlayers(original);
        }
    }

    @GameTest(template = "empty", timeoutTicks = 5, batch = "config_players_off_matcher")
    public static void droneWithPlayerSeekDoesNotTargetPlayerWhenPlayerTargetsOff(GameTestHelper helper) {
        boolean original = ServerConfig.get(ServerConfig.DRONE_ALLOW_PLAYER_TARGETS);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        Vec3 pos = helper.absoluteVec(new Vec3(4, 1, 4));
        player.moveTo(pos.x, pos.y, pos.z, 0.0F, 0.0F);
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(2, 3, 2));
        DroneData data = playerSeekData(player.getGameProfile().getName());
        try {
            helper.assertTrue(TargetMatcher.of(data).matches(drone, player), "Sanity: matches while player targets are allowed");
            setAllowPlayers(false);
            helper.assertFalse(TargetMatcher.of(data).matches(drone, player), "Player must not match with player targets off");
            helper.assertTrue(TargetMatcher.targetableEntries(data).isEmpty(), "no targetable entries, was " + TargetMatcher.targetableEntries(data));
            helper.succeed();
        } finally {
            setAllowPlayers(original);
        }
    }

    // --- 4. Deployed drone limit ---

    @GameTest(template = "empty", timeoutTicks = 10, batch = "config_limit_owner")
    public static void ownedDronesCountAgainstOwnerAndLimitIsReached(GameTestHelper helper) {
        int original = ServerConfig.get(ServerConfig.DRONE_MAX_DEPLOYED_PER_PLAYER);
        MinecraftServer server = helper.getLevel().getServer();
        DeployedDrones deployed = DeployedDrones.get(server);
        UUID owner = UUID.randomUUID();
        try {
            ServerConfig.DRONE_MAX_DEPLOYED_PER_PLAYER.set(2);
            helper.assertFalse(deployed.wouldExceedLimit(server, ownedData(owner)), "nothing out yet");
            spawnDrone(helper, new BlockPos(1, 3, 1), ownedData(owner));
            helper.assertTrue(deployed.count(owner) == 1, "count 1, was " + deployed.count(owner));
            helper.assertFalse(deployed.wouldExceedLimit(server, ownedData(owner)), "1 of 2 out: not exceeded");
            spawnDrone(helper, new BlockPos(3, 3, 1), ownedData(owner));
            helper.assertTrue(deployed.count(owner) == 2, "count 2, was " + deployed.count(owner));
            helper.assertTrue(deployed.wouldExceedLimit(server, ownedData(owner)), "2 of 2 out: exceeded");
            helper.assertFalse(deployed.wouldExceedLimit(server, ownedData(UUID.randomUUID())), "another player unaffected");
            helper.succeed();
        } finally {
            ServerConfig.DRONE_MAX_DEPLOYED_PER_PLAYER.set(original);
        }
    }

    @GameTest(template = "empty", timeoutTicks = 10, batch = "config_limit_group")
    public static void groupedDroneCountsAgainstGroupOwnerNotDroneOwner(GameTestHelper helper) {
        int original = ServerConfig.get(ServerConfig.DRONE_MAX_DEPLOYED_PER_PLAYER);
        MinecraftServer server = helper.getLevel().getServer();
        DeployedDrones deployed = DeployedDrones.get(server);
        UUID groupOwner = UUID.randomUUID();
        UUID droneOwner = UUID.randomUUID();
        UUID group = OperatorGroups.get(server).createGroup(groupOwner);
        try {
            ServerConfig.DRONE_MAX_DEPLOYED_PER_PLAYER.set(1);
            DroneData data = ownedData(droneOwner).withGroupId(Optional.of(group));
            spawnDrone(helper, new BlockPos(1, 3, 1), data);
            helper.assertTrue(deployed.count(groupOwner) == 1, "group owner count 1, was " + deployed.count(groupOwner));
            helper.assertTrue(deployed.count(droneOwner) == 0, "drone owner count 0, was " + deployed.count(droneOwner));
            helper.assertTrue(deployed.wouldExceedLimit(server, ownedData(UUID.randomUUID()).withGroupId(Optional.of(group))), "group owner at limit");
            helper.assertFalse(deployed.wouldExceedLimit(server, ownedData(droneOwner)), "drone owner (ungrouped) not at limit");
            helper.succeed();
        } finally {
            ServerConfig.DRONE_MAX_DEPLOYED_PER_PLAYER.set(original);
        }
    }

    @GameTest(template = "empty", timeoutTicks = 10, batch = "config_limit_unowned")
    public static void unownedAndUnknownGroupDronesCountAgainstNobody(GameTestHelper helper) {
        int original = ServerConfig.get(ServerConfig.DRONE_MAX_DEPLOYED_PER_PLAYER);
        MinecraftServer server = helper.getLevel().getServer();
        DeployedDrones deployed = DeployedDrones.get(server);
        UUID droneOwner = UUID.randomUUID();
        try {
            ServerConfig.DRONE_MAX_DEPLOYED_PER_PLAYER.set(1);
            DroneData unowned = DroneData.createNew().withDroneId(uniqueId());
            DroneData unknownGroup = ownedData(droneOwner).withGroupId(Optional.of(UUID.randomUUID()));
            spawnDrone(helper, new BlockPos(1, 3, 1), unowned);
            spawnDrone(helper, new BlockPos(3, 3, 1), unknownGroup);
            helper.assertTrue(deployed.count(droneOwner) == 0, "drone in an unknown group counts against nobody, owner count=" + deployed.count(droneOwner));
            helper.assertFalse(deployed.wouldExceedLimit(server, unowned), "unowned never exceeds");
            helper.assertFalse(deployed.wouldExceedLimit(server, unknownGroup), "unknown group never exceeds");
            helper.assertFalse(deployed.remove(unowned.droneId()), "unowned drone was never tracked");
            helper.assertFalse(deployed.remove(unknownGroup.droneId()), "unknown-group drone was never tracked");
            helper.succeed();
        } finally {
            ServerConfig.DRONE_MAX_DEPLOYED_PER_PLAYER.set(original);
        }
    }

    @GameTest(template = "empty", timeoutTicks = 10, batch = "config_limit_discard")
    public static void discardingDroneFreesItsSlot(GameTestHelper helper) {
        int original = ServerConfig.get(ServerConfig.DRONE_MAX_DEPLOYED_PER_PLAYER);
        MinecraftServer server = helper.getLevel().getServer();
        DeployedDrones deployed = DeployedDrones.get(server);
        UUID owner = UUID.randomUUID();
        try {
            ServerConfig.DRONE_MAX_DEPLOYED_PER_PLAYER.set(1);
            DroneEntity drone = spawnDrone(helper, new BlockPos(1, 3, 1), ownedData(owner));
            helper.assertTrue(deployed.wouldExceedLimit(server, ownedData(owner)), "at the limit");
            drone.discard();
            helper.assertTrue(deployed.count(owner) == 0, "count 0 after discard, was " + deployed.count(owner));
            helper.assertFalse(deployed.wouldExceedLimit(server, ownedData(owner)), "slot free after discard");
            helper.succeed();
        } finally {
            ServerConfig.DRONE_MAX_DEPLOYED_PER_PLAYER.set(original);
        }
    }

    @GameTest(template = "empty", timeoutTicks = 60, batch = "config_limit_kill")
    public static void killingDroneFreesItsSlot(GameTestHelper helper) {
        int original = ServerConfig.get(ServerConfig.DRONE_MAX_DEPLOYED_PER_PLAYER);
        MinecraftServer server = helper.getLevel().getServer();
        DeployedDrones deployed = DeployedDrones.get(server);
        UUID owner = UUID.randomUUID();
        ServerConfig.DRONE_MAX_DEPLOYED_PER_PLAYER.set(1);
        helper.runAfterDelay(55, () -> ServerConfig.DRONE_MAX_DEPLOYED_PER_PLAYER.set(original));
        DroneEntity drone = spawnDrone(helper, new BlockPos(1, 3, 1), ownedData(owner));
        helper.assertTrue(deployed.count(owner) == 1, "counted while alive");
        drone.kill();
        helper.succeedWhen(() -> {
            helper.assertTrue(deployed.count(owner) == 0, "count should be 0 after the drone is killed, was " + deployed.count(owner));
            ServerConfig.DRONE_MAX_DEPLOYED_PER_PLAYER.set(original);
        });
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void noLimitByDefaultAndZeroMeansUnlimited(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        DeployedDrones deployed = DeployedDrones.get(server);
        helper.assertTrue(ServerConfig.get(ServerConfig.DRONE_MAX_DEPLOYED_PER_PLAYER) == 0, "default is 0");
        UUID owner = UUID.randomUUID();
        for (int i = 0; i < 4; i++) {
            spawnDrone(helper, new BlockPos(1 + 2 * (i % 3), 3, 1 + 2 * (i / 3)), ownedData(owner));
        }
        helper.assertTrue(deployed.count(owner) == 4, "all 4 counted, was " + deployed.count(owner));
        helper.assertFalse(deployed.wouldExceedLimit(server, ownedData(owner)), "limit 0 is unlimited");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 5)
    public static void removingUnknownDroneIdReturnsFalse(GameTestHelper helper) {
        DeployedDrones deployed = DeployedDrones.get(helper.getLevel().getServer());
        helper.assertFalse(deployed.remove("NO-SUCH-" + uniqueId()), "unknown ID");
        UUID owner = UUID.randomUUID();
        DroneData data = ownedData(owner);
        spawnDrone(helper, new BlockPos(1, 3, 1), data);
        helper.assertTrue(deployed.remove(data.droneId()), "known ID removed");
        helper.assertTrue(deployed.count(owner) == 0, "count 0 after remove");
        helper.assertFalse(deployed.remove(data.droneId()), "second remove returns false");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100, batch = "config_limit_station")
    public static void deployingStationReportsLimitKeepsDroneThenDeploysWhenSlotFrees(GameTestHelper helper) {
        int original = ServerConfig.get(ServerConfig.DRONE_MAX_DEPLOYED_PER_PLAYER);
        UUID owner = UUID.randomUUID();
        ServerConfig.DRONE_MAX_DEPLOYED_PER_PLAYER.set(1);
        helper.runAfterDelay(95, () -> ServerConfig.DRONE_MAX_DEPLOYED_PER_PLAYER.set(original));

        DroneEntity first = spawnDrone(helper, new BlockPos(0, 3, 0), ownedData(owner));
        helper.setBlock(STATION_REL, ModBlocks.DEPLOYING_STATION.get());
        BlockPos abs = helper.absolutePos(STATION_REL);
        DeployingStationBlockEntity station = (DeployingStationBlockEntity) helper.getLevel().getBlockEntity(abs);
        helper.getLevel().getCapability(Capabilities.EnergyStorage.BLOCK, abs, null).receiveEnergy(50000, false);
        ItemStack left = helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, abs, Direction.NORTH)
                .insertItem(0, DroneItem.createStack(ownedData(owner)), false);
        helper.assertTrue(left.isEmpty(), "drone inserted");

        helper.runAfterDelay(25, () -> {
            helper.assertTrue(station.getStatus() == DeployingStatus.LIMIT, "Status should be LIMIT, was " + station.getStatus());
            helper.assertFalse(station.getItems().getStackInSlot(0).isEmpty(), "Drone should stay in the slot");
            helper.assertTrue(dronesNear(helper, abs).isEmpty(), "Nothing deployed while at the limit");
            first.discard();
            helper.succeedWhen(() -> {
                helper.assertTrue(!dronesNear(helper, abs).isEmpty(), "Drone should deploy once the slot frees up");
                helper.assertTrue(station.getItems().getStackInSlot(0).isEmpty(), "Slot should be empty after the deploy");
                ServerConfig.DRONE_MAX_DEPLOYED_PER_PLAYER.set(original);
            });
        });
    }

    // --- helpers ---

    private static String uniqueId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static DroneData ownedData(UUID owner) {
        return DroneData.createNew().withDroneId("SAFE-" + uniqueId()).withOwnerId(Optional.of(owner));
    }

    private static DroneEntity spawnDrone(GameTestHelper helper, BlockPos rel, DroneData data) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), rel);
        drone.setDroneData(data);
        return drone;
    }

    private static List<DroneEntity> dronesNear(GameTestHelper helper, BlockPos stationAbs) {
        return helper.getLevel().getEntitiesOfClass(DroneEntity.class, new AABB(stationAbs).inflate(2.5));
    }

    private static TargetEntry entity(String id) {
        return new TargetEntry(TargetEntry.Kind.ENTITY_TYPE, id);
    }

    private static TargetEntry tag(String id) {
        return new TargetEntry(TargetEntry.Kind.TAG, id);
    }

    private static TargetEntry playerEntry(String name) {
        return new TargetEntry(TargetEntry.Kind.PLAYER_NAME, name);
    }

    private static DroneData zombieTargeting() {
        DroneData base = DroneData.createNew();
        return base.withConfig(base.config().withTargets(List.of(entity("minecraft:zombie"))));
    }

    private static DroneData playerSeekData(String playerName) {
        DroneData base = DroneData.createNew().withConfig(DroneConfig.createDefault().withTargets(List.of(playerEntry(playerName))));
        return EnergyGameTests.dataWithUpgrades(base, Map.of(UpgradeType.PLAYER_SEEK, 1));
    }

    private static void withAllowlist(List<String> entries) {
        ServerConfig.DRONE_TARGET_ALLOWLIST.set(entries);
        TargetFilter.invalidate();
        TargetMatcher.invalidateAll();
    }

    private static void setAllowPlayers(boolean allow) {
        ServerConfig.DRONE_ALLOW_PLAYER_TARGETS.set(allow);
        TargetMatcher.invalidateAll();
    }

    private static Zombie spawnStationaryZombie(GameTestHelper helper, int x, int y, int z) {
        Zombie zombie = helper.spawn(EntityType.ZOMBIE, new BlockPos(x, y, z));
        zombie.setNoAi(true);
        zombie.setNoGravity(true);
        zombie.setInvulnerable(true);
        return zombie;
    }
}
