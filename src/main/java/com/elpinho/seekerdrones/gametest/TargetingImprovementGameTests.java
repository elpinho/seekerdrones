package com.elpinho.seekerdrones.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.drone.DroneConfig;
import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.drone.DroneItem;
import com.elpinho.seekerdrones.drone.DroneState;
import com.elpinho.seekerdrones.drone.DroneStats;
import com.elpinho.seekerdrones.drone.TargetBlacklist;
import com.elpinho.seekerdrones.drone.TargetEntry;
import com.elpinho.seekerdrones.drone.TargetMatcher;
import com.elpinho.seekerdrones.drone.UpgradeType;
import com.elpinho.seekerdrones.programming.ProgramRules;
import com.elpinho.seekerdrones.registry.ModEntityTypes;
import com.elpinho.seekerdrones.registry.ModItems;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * GameTests for the sight/pursuit range defaults and cap (DESIGN.md 3.3, 3.5, 9), creative-mode hand-deploying (2.8)
 * and the target blacklist (3.3, 7.2, 9). Tests that change {@link ServerConfig} values each run in their own batch and
 * restore the values (and the blacklist cache) when done.
 */
@GameTestHolder("seekerdrones")
@PrefixGameTestTemplate(false)
public class TargetingImprovementGameTests {

    private static final String SELECTOR = "@e[type=seekerdrones:drone,distance=..3]";

    // --- 1. Sight range and pursuit cap ---

    @GameTest(template = "empty", timeoutTicks = 5)
    public static void sightRangeIsBaseAndPursuitIsSightTimesMultiplier(GameTestHelper helper) {
        DroneData data = DroneData.createNew();
        double base = ServerConfig.get(ServerConfig.DRONE_BASE_SIGHT_RANGE);
        double mult = ServerConfig.get(ServerConfig.DRONE_PURSUIT_MULTIPLIER);
        double cap = ServerConfig.get(ServerConfig.DRONE_MAX_PURSUIT_RANGE);
        helper.assertTrue(DroneStats.sightRange(data) == base, "Sight range without upgrades should be the base " + base + ", was " + DroneStats.sightRange(data));
        double expected = Math.max(base, Math.min(base * mult, cap));
        helper.assertTrue(Math.abs(DroneStats.pursuitRange(data) - expected) < 1.0E-6,
                "Pursuit range should be " + expected + ", was " + DroneStats.pursuitRange(data));
        helper.assertTrue(base == 16 && mult == 1.5 && cap == 128, "Defaults should be 16 / 1.5 / 128, were " + base + " / " + mult + " / " + cap);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 5, batch = "config_max_pursuit_cap")
    public static void pursuitRangeIsCappedByMaxPursuitRange(GameTestHelper helper) {
        DroneData data = DroneData.createNew();
        int original = ServerConfig.get(ServerConfig.DRONE_MAX_PURSUIT_RANGE);
        try {
            double sight = DroneStats.sightRange(data);
            double uncapped = sight * ServerConfig.get(ServerConfig.DRONE_PURSUIT_MULTIPLIER);
            int cap = (int) Math.floor((sight + uncapped) / 2);
            helper.assertTrue(cap > sight && cap < uncapped, "Test setup: cap " + cap + " should lie between " + sight + " and " + uncapped);
            ServerConfig.DRONE_MAX_PURSUIT_RANGE.set(cap);
            helper.assertTrue(DroneStats.pursuitRange(data) == cap, "Pursuit should equal the cap " + cap + ", was " + DroneStats.pursuitRange(data));
            helper.succeed();
        } finally {
            ServerConfig.DRONE_MAX_PURSUIT_RANGE.set(original);
        }
    }

    @GameTest(template = "empty", timeoutTicks = 5, batch = "config_max_pursuit_below_sight")
    public static void pursuitRangeNeverDropsBelowSightRange(GameTestHelper helper) {
        DroneData data = DroneData.createNew();
        int original = ServerConfig.get(ServerConfig.DRONE_MAX_PURSUIT_RANGE);
        try {
            ServerConfig.DRONE_MAX_PURSUIT_RANGE.set(1);
            double sight = DroneStats.sightRange(data);
            helper.assertTrue(DroneStats.pursuitRange(data) == sight, "Pursuit should equal the sight range " + sight + ", was " + DroneStats.pursuitRange(data));
            helper.succeed();
        } finally {
            ServerConfig.DRONE_MAX_PURSUIT_RANGE.set(original);
        }
    }

    @GameTest(template = "empty", timeoutTicks = 120, batch = "config_max_pursuit_behavior")
    public static void chasingDroneLosesTargetJustBeyondCappedPursuitRange(GameTestHelper helper) {
        int original = ServerConfig.get(ServerConfig.DRONE_MAX_PURSUIT_RANGE);
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        Zombie zombie = spawnStationaryZombie(helper, 4, 1, 7);
        drone.setDroneData(droneTargetingZombie());
        int cap = (int) DroneStats.sightRange(drone.snapshotData()) + 4;
        ServerConfig.DRONE_MAX_PURSUIT_RANGE.set(cap);
        AtomicBoolean moved = new AtomicBoolean(false);
        helper.onEachTick(() -> {
            if (!moved.get() && drone.getSeekTarget() == zombie) {
                double pursuit = DroneStats.pursuitRange(drone.snapshotData());
                if (pursuit != cap) {
                    ServerConfig.DRONE_MAX_PURSUIT_RANGE.set(original);
                    helper.fail("Pursuit should be capped at " + cap + ", was " + pursuit);
                    return;
                }
                // Uncapped, the pursuit range would be sight * 1.5, well beyond this distance.
                zombie.moveTo(zombie.getX(), drone.getY() + pursuit + 2.0, zombie.getZ());
                moved.set(true);
            }
            if (moved.get() && drone.getSeekTarget() == null && drone.getState() == DroneState.IDLE) {
                ServerConfig.DRONE_MAX_PURSUIT_RANGE.set(original);
                helper.succeed();
            }
        });
        helper.runAfterDelay(115, () -> ServerConfig.DRONE_MAX_PURSUIT_RANGE.set(original));
    }

    // --- 2. Creative-mode hand-deploying (DESIGN.md 2.8) ---

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void creativeDeployOfDroneWithIdUsesUpItem(GameTestHelper helper) {
        Player player = deployer(helper, GameType.CREATIVE);
        ItemStack stack = DroneItem.createStack(DroneData.createNew().withDroneId("AB-1234"));
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        stack.getItem().use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
        helper.assertTrue(player.getItemInHand(InteractionHand.MAIN_HAND).isEmpty(), "Creative deploy of a drone with an ID should use up the item, stack=" + stack);
        List<DroneEntity> drones = dronesNear(helper);
        helper.assertTrue(drones.size() == 1, "One drone should have spawned, found " + drones.size());
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void creativeDeployOfUnassignedItemKeepsItemAndAssignsFreshIds(GameTestHelper helper) {
        Player player = deployer(helper, GameType.CREATIVE);
        ItemStack stack = new ItemStack(ModItems.DRONE.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        stack.getItem().use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
        helper.assertTrue(stack.getCount() == 1 && !DroneItem.getData(stack).hasDroneId(),
                "Unassigned item should stay in a creative hand with no ID, stack=" + stack + " data=" + DroneItem.getData(stack));
        stack.getItem().use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
        helper.assertTrue(stack.getCount() == 1 && !DroneItem.getData(stack).hasDroneId(), "Item still unchanged after the second deploy, stack=" + stack);
        List<DroneEntity> drones = dronesNear(helper);
        helper.assertTrue(drones.size() == 2, "Two drones should have spawned, found " + drones.size());
        String a = drones.get(0).snapshotData().droneId();
        String b = drones.get(1).snapshotData().droneId();
        helper.assertTrue(!a.isEmpty() && !b.isEmpty() && !a.equals(b), "Drones should have distinct fresh IDs, were '" + a + "' and '" + b + "'");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void survivalDeployUsesUpItemWithAndWithoutId(GameTestHelper helper) {
        Player player = deployer(helper, GameType.SURVIVAL);
        ItemStack unassigned = new ItemStack(ModItems.DRONE.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, unassigned);
        unassigned.getItem().use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
        helper.assertTrue(unassigned.isEmpty(), "Survival deploy of an unassigned item should use it up, stack=" + unassigned);
        ItemStack withId = DroneItem.createStack(DroneData.createNew().withDroneId("CD-5678"));
        player.setItemInHand(InteractionHand.MAIN_HAND, withId);
        withId.getItem().use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
        helper.assertTrue(withId.isEmpty(), "Survival deploy of a drone with an ID should use it up, stack=" + withId);
        helper.assertTrue(dronesNear(helper).size() == 2, "Two drones should have spawned");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void deployWithoutSneakingSpawnsNothing(GameTestHelper helper) {
        Player player = deployer(helper, GameType.SURVIVAL);
        player.setShiftKeyDown(false);
        ItemStack stack = DroneItem.createStack(DroneData.createNew().withDroneId("EF-0001"));
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        stack.getItem().use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
        helper.assertTrue(stack.getCount() == 1 && dronesNear(helper).isEmpty(), "Plain right-click should not deploy");
        helper.succeed();
    }

    // --- 3. Target blacklist ---

    @GameTest(template = "empty", timeoutTicks = 60, batch = "config_blacklist_zombie_type")
    public static void blacklistedEntityTypeIsNotAcquired(GameTestHelper helper) {
        withBlacklist(List.of("minecraft:zombie"));
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        spawnStationaryZombie(helper, 4, 1, 7);
        drone.setDroneData(droneTargetingZombie());
        helper.runAfterDelay(35, () -> {
            try {
                helper.assertTrue(drone.getSeekTarget() == null, "Blacklisted zombie should not be acquired, target=" + drone.getSeekTarget());
                helper.succeed();
            } finally {
                withBlacklist(List.of());
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 60, batch = "config_blacklist_zombie_tag")
    public static void blacklistedTagContainingTypeIsNotAcquired(GameTestHelper helper) {
        withBlacklist(List.of("#minecraft:zombies"));
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        spawnStationaryZombie(helper, 4, 1, 7);
        drone.setDroneData(droneTargetingZombie());
        helper.runAfterDelay(35, () -> {
            try {
                helper.assertTrue(EntityType.ZOMBIE.is(EntityTypeTags.ZOMBIES), "Test setup: zombie should be in #minecraft:zombies");
                helper.assertTrue(drone.getSeekTarget() == null, "Zombie in a blacklisted tag should not be acquired, target=" + drone.getSeekTarget());
                helper.succeed();
            } finally {
                withBlacklist(List.of());
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100, batch = "config_blacklist_resume")
    public static void programmedDroneKeepsBlacklistedEntryAndResumesTargetingAfterClear(GameTestHelper helper) {
        withBlacklist(List.of("minecraft:zombie"));
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        Zombie zombie = spawnStationaryZombie(helper, 4, 1, 7);
        drone.setDroneData(droneTargetingZombie());
        helper.runAfterDelay(35, () -> {
            try {
                helper.assertTrue(drone.getSeekTarget() == null, "Blacklisted zombie should not be acquired, target=" + drone.getSeekTarget());
                helper.assertTrue(drone.snapshotData().config().targets().equals(droneTargetingZombie().config().targets()),
                        "Drone should keep its blacklisted entry, targets=" + drone.snapshotData().config().targets());
            } finally {
                withBlacklist(List.of());
            }
            helper.succeedWhen(() -> helper.assertTrue(drone.getSeekTarget() == zombie,
                    "Drone should acquire the zombie once the blacklist is cleared, target=" + drone.getSeekTarget()));
        });
    }

    @GameTest(template = "empty", timeoutTicks = 20, batch = "config_blacklist_player")
    public static void blacklistedPlayerTypeIsNotTargetedEvenWithPlayerSeek(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        Vec3 pos = helper.absoluteVec(new Vec3(4, 1, 4));
        player.moveTo(pos.x, pos.y, pos.z, 0.0F, 0.0F);
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(2, 3, 2));
        TargetEntry entry = new TargetEntry(TargetEntry.Kind.PLAYER_NAME, player.getGameProfile().getName());
        DroneData base = DroneData.createNew().withConfig(DroneConfig.createDefault().withTargets(List.of(entry)));
        DroneData data = new DroneData(base.droneId(), base.groupId(), base.ownerId(), base.ownerName(), base.energy(), base.health(),
                Map.of(UpgradeType.PLAYER_SEEK, 1), base.config());
        try {
            helper.assertTrue(TargetMatcher.of(data).matches(drone, player), "Sanity: player should match without blacklist");
            withBlacklist(List.of("minecraft:player"));
            helper.assertFalse(TargetMatcher.of(data).matches(drone, player), "Blacklisted minecraft:player should protect players");
            helper.succeed();
        } finally {
            withBlacklist(List.of());
        }
    }

    @GameTest(template = "empty", timeoutTicks = 20, batch = "config_blacklist_blocks")
    public static void blacklistBlocksTypesTagsAndFullyBlacklistedTagsButNotPlayerNames(GameTestHelper helper) {
        List<String> allZombies = new ArrayList<>();
        BuiltInRegistries.ENTITY_TYPE.getTag(EntityTypeTags.ZOMBIES).ifPresent(set -> {
            for (Holder<EntityType<?>> h : set) {
                allZombies.add(BuiltInRegistries.ENTITY_TYPE.getKey(h.value()).toString());
            }
        });
        try {
            withBlacklist(List.of("minecraft:zombie", "#minecraft:raiders"));
            helper.assertTrue(TargetBlacklist.blocks(new TargetEntry(TargetEntry.Kind.ENTITY_TYPE, "minecraft:zombie")), "blacklisted type should block");
            helper.assertFalse(TargetBlacklist.blocks(new TargetEntry(TargetEntry.Kind.ENTITY_TYPE, "minecraft:skeleton")), "other type should not block");
            helper.assertTrue(TargetBlacklist.blocks(new TargetEntry(TargetEntry.Kind.TAG, "minecraft:raiders")), "blacklisted tag should block");
            helper.assertFalse(TargetBlacklist.blocks(new TargetEntry(TargetEntry.Kind.TAG, "minecraft:zombies")),
                    "tag with some non-blacklisted members should not block (zombies tag has " + allZombies + ")");
            helper.assertFalse(TargetBlacklist.blocks(new TargetEntry(TargetEntry.Kind.PLAYER_NAME, "minecraft:zombie")), "player names are never blocked");
            helper.assertFalse(TargetBlacklist.blocks(new TargetEntry(TargetEntry.Kind.PLAYER_NAME, "Steve")), "player names are never blocked");

            helper.assertTrue(allZombies.size() > 1, "Test setup: zombies tag should have several members, was " + allZombies);
            withBlacklist(allZombies);
            helper.assertTrue(TargetBlacklist.blocks(new TargetEntry(TargetEntry.Kind.TAG, "minecraft:zombies")),
                    "tag whose members are all blacklisted should block");
            helper.succeed();
        } finally {
            withBlacklist(List.of());
        }
    }

    @GameTest(template = "empty", timeoutTicks = 20, batch = "config_blacklist_rules")
    public static void programRulesRejectNewBlacklistedEntryButKeepExistingOne(GameTestHelper helper) {
        TargetEntry zombie = new TargetEntry(TargetEntry.Kind.ENTITY_TYPE, "minecraft:zombie");
        TargetEntry skeleton = new TargetEntry(TargetEntry.Kind.ENTITY_TYPE, "minecraft:skeleton");
        try {
            withBlacklist(List.of("minecraft:zombie"));
            String problem = ProgramRules.targetProblem(List.of(zombie), 0, List.of(), Map.of());
            helper.assertTrue(problem != null && problem.endsWith("target.blacklisted"), "New blacklisted entry should be rejected, problem=" + problem);
            helper.assertTrue(ProgramRules.targetProblem(List.of(zombie), 0, List.of(zombie), Map.of()) == null,
                    "Existing blacklisted entry (in previous) should be accepted");
            helper.assertTrue(ProgramRules.targetProblem(List.of(skeleton), 0, List.of(), Map.of()) == null, "Unlisted entry should be accepted");

            DroneConfig empty = DroneConfig.createDefault();
            DroneConfig previous = empty.withTargets(List.of(zombie));
            var dim = helper.getLevel().dimension();
            helper.assertTrue(ProgramRules.checkConfig(empty.withTargets(List.of(zombie)), empty, Map.of(), dim).isEmpty(),
                    "checkConfig should reject a new blacklisted entry");
            helper.assertTrue(ProgramRules.checkConfig(previous, previous, Map.of(), dim).isPresent(),
                    "checkConfig should accept an unchanged existing blacklisted entry");
            helper.succeed();
        } finally {
            withBlacklist(List.of());
        }
    }

    @GameTest(template = "empty", timeoutTicks = 20, batch = "config_blacklist_command")
    public static void configTargetAddCommandRefusesBlacklistedEntityType(GameTestHelper helper) {
        withBlacklist(List.of("minecraft:zombie"));
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        try {
            boolean ok = runCommand(helper, "seekerdrones config target add entity minecraft:zombie " + SELECTOR);
            helper.assertFalse(ok, "Command should fail for a blacklisted type");
            helper.assertTrue(drone.snapshotData().config().targets().isEmpty(),
                    "Drone targets should be unchanged, targets=" + drone.snapshotData().config().targets());
            boolean ok2 = runCommand(helper, "seekerdrones config target add entity minecraft:skeleton " + SELECTOR);
            helper.assertTrue(ok2 && drone.snapshotData().config().targets().size() == 1, "A non-blacklisted type should still be added");
            helper.succeed();
        } finally {
            withBlacklist(List.of());
        }
    }

    // --- targetableEntries (DESIGN.md 2.1, 2.4, 2.7, 3.3) ---

    private static DroneData targetsData(Map<UpgradeType, Integer> upgrades, TargetEntry... entries) {
        DroneData base = DroneData.createNew();
        return new DroneData(base.droneId(), base.groupId(), base.ownerId(), base.ownerName(), base.energy(), base.health(), upgrades,
                base.config().withTargets(List.of(entries)));
    }

    @GameTest(template = "empty", timeoutTicks = 5)
    public static void targetableEntriesRespectsMultiTargetCount(GameTestHelper helper) {
        TargetEntry skeleton = new TargetEntry(TargetEntry.Kind.ENTITY_TYPE, "minecraft:skeleton");
        TargetEntry zombie = new TargetEntry(TargetEntry.Kind.ENTITY_TYPE, "minecraft:zombie");
        helper.assertValueEqual(TargetMatcher.targetableEntries(targetsData(Map.of(), skeleton, zombie)), List.of(skeleton),
                "targetable entries without Multi-target");
        helper.assertValueEqual(TargetMatcher.targetableEntries(targetsData(Map.of(UpgradeType.MULTI_TARGET, 1), skeleton, zombie)),
                List.of(skeleton, zombie), "targetable entries with 1 Multi-target upgrade");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 5)
    public static void targetableEntriesExcludesPlayerNameWithoutPlayerSeek(GameTestHelper helper) {
        TargetEntry player = new TargetEntry(TargetEntry.Kind.PLAYER_NAME, "Steve");
        helper.assertTrue(TargetMatcher.targetableEntries(targetsData(Map.of(), player)).isEmpty(),
                "player name should be excluded without Player Seek");
        helper.assertValueEqual(TargetMatcher.targetableEntries(targetsData(Map.of(UpgradeType.PLAYER_SEEK, 1), player)), List.of(player),
                "player name should be included with Player Seek");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 5)
    public static void droneItemTooltipCountsTargetableEntries(GameTestHelper helper) {
        TargetEntry skeleton = new TargetEntry(TargetEntry.Kind.ENTITY_TYPE, "minecraft:skeleton");
        TargetEntry zombie = new TargetEntry(TargetEntry.Kind.ENTITY_TYPE, "minecraft:zombie");
        helper.assertValueEqual(tooltipTargetCount(targetsData(Map.of(), skeleton, zombie)), 1, "tooltip count without Multi-target");
        helper.assertValueEqual(tooltipTargetCount(targetsData(Map.of(UpgradeType.MULTI_TARGET, 1), skeleton, zombie)), 2,
                "tooltip count with Multi-target");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 10, batch = "config_blacklist_targetable")
    public static void targetableEntriesExcludesBlacklistedEntries(GameTestHelper helper) {
        TargetEntry skeleton = new TargetEntry(TargetEntry.Kind.ENTITY_TYPE, "minecraft:skeleton");
        TargetEntry zombie = new TargetEntry(TargetEntry.Kind.ENTITY_TYPE, "minecraft:zombie");
        DroneData data = targetsData(Map.of(UpgradeType.MULTI_TARGET, 1), zombie, skeleton);
        try {
            withBlacklist(List.of("minecraft:zombie"));
            helper.assertValueEqual(TargetMatcher.targetableEntries(data), List.of(skeleton), "blacklisted zombie should be excluded");
            helper.assertValueEqual(tooltipTargetCount(data), 1, "tooltip count should skip the blacklisted entry");
            withBlacklist(List.of());
            helper.assertValueEqual(TargetMatcher.targetableEntries(data), List.of(zombie, skeleton), "both entries once not blacklisted");
            helper.succeed();
        } finally {
            withBlacklist(List.of());
        }
    }

    private static Object tooltipTargetCount(DroneData data) {
        List<net.minecraft.network.chat.Component> lines = new ArrayList<>();
        DroneItem.createStack(data).getItem().appendHoverText(DroneItem.createStack(data), net.minecraft.world.item.Item.TooltipContext.EMPTY,
                lines, net.minecraft.world.item.TooltipFlag.NORMAL);
        for (net.minecraft.network.chat.Component line : lines) {
            if (line.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t
                    && t.getKey().equals("tooltip.seekerdrones.drone.targets")) {
                return t.getArgs()[0];
            }
        }
        return "missing";
    }

    // --- helpers ---

    private static void withBlacklist(List<String> entries) {
        ServerConfig.DRONE_TARGET_BLACKLIST.set(entries);
        TargetBlacklist.invalidate();
    }

    /** Runs a command and returns whether it succeeded. */
    private static boolean runCommand(GameTestHelper helper, String command) {
        MinecraftServer server = helper.getLevel().getServer();
        AtomicBoolean success = new AtomicBoolean(false);
        CommandSourceStack source = server.createCommandSourceStack()
                .withLevel(helper.getLevel())
                .withPosition(helper.absoluteVec(new Vec3(4, 3, 4)))
                .withPermission(4)
                .withSuppressedOutput()
                .withCallback((ok, result) -> success.set(ok));
        server.getCommands().performPrefixedCommand(source, command);
        return success.get();
    }

    private static Player deployer(GameTestHelper helper, GameType type) {
        Player player = helper.makeMockPlayer(type);
        player.getAbilities().instabuild = type.isCreative();
        Vec3 pos = helper.absoluteVec(new Vec3(4.5, 2, 4.5));
        player.moveTo(pos.x, pos.y, pos.z, 0.0F, 0.0F);
        player.setShiftKeyDown(true);
        return player;
    }

    private static List<DroneEntity> dronesNear(GameTestHelper helper) {
        Vec3 c = helper.absoluteVec(new Vec3(4.5, 3, 4.5));
        return helper.getLevel().getEntitiesOfClass(DroneEntity.class, new AABB(c, c).inflate(6));
    }

    private static DroneData droneTargetingZombie() {
        DroneData base = DroneData.createNew();
        return base.withConfig(base.config().withTargets(List.of(new TargetEntry(TargetEntry.Kind.ENTITY_TYPE, "minecraft:zombie"))));
    }

    private static Zombie spawnStationaryZombie(GameTestHelper helper, int x, int y, int z) {
        Zombie zombie = helper.spawn(EntityType.ZOMBIE, new BlockPos(x, y, z));
        zombie.setNoAi(true);
        zombie.setNoGravity(true);
        zombie.setInvulnerable(true);
        return zombie;
    }
}
