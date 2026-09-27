package com.elpinho.seekerdrones.gametest;

import java.util.List;
import java.util.Optional;

import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.drone.DroneItem;
import com.elpinho.seekerdrones.drone.TargetEntry;
import com.elpinho.seekerdrones.registry.ModEntityTypes;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * M3 GameTests for the {@code /seekerdrones config} subcommands (DESIGN.md sections 2.6, 2.7), acting on drone
 * entities picked by a selector scoped to this test's structure, plus one best-effort test of the held-item form.
 *
 * All tests share the {@code seekerdrones:empty} structure template.
 */
@GameTestHolder("seekerdrones")
@PrefixGameTestTemplate(false)
public class ConfigCommandGameTests {

    private static final String SELECTOR = "@e[type=seekerdrones:drone,distance=..3]";

    // --- target add (DESIGN.md 2.6) ---

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void targetAddEntityCommandAddsEntryAndEnablesDetection(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        Zombie zombie = spawnStationaryZombie(helper, 4, 1, 7);

        runCommand(helper, "seekerdrones config target add entity minecraft:zombie " + SELECTOR);

        helper.runAfterDelay(3, () -> {
            List<TargetEntry> targets = drone.snapshotData().config().targets();
            helper.assertTrue(targets.equals(List.of(new TargetEntry(TargetEntry.Kind.ENTITY_TYPE, "minecraft:zombie"))),
                    "Command should have added the zombie entity-type entry, targets=" + targets);

            helper.succeedWhen(() -> helper.assertTrue(drone.getSeekTarget() == zombie,
                    "Drone should detect the zombie once the target entry was added via command, target=" + drone.getSeekTarget()));
        });
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void targetAddTagCommandAddsEntry(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));

        runCommand(helper, "seekerdrones config target add tag minecraft:zombies " + SELECTOR);

        helper.runAfterDelay(3, () -> {
            List<TargetEntry> targets = drone.snapshotData().config().targets();
            helper.assertTrue(targets.equals(List.of(new TargetEntry(TargetEntry.Kind.TAG, "minecraft:zombies"))),
                    "Command should have added the tag entry, targets=" + targets);
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void targetAddPlayerCommandAddsEntry(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));

        runCommand(helper, "seekerdrones config target add player Steve " + SELECTOR);

        helper.runAfterDelay(3, () -> {
            List<TargetEntry> targets = drone.snapshotData().config().targets();
            helper.assertTrue(targets.equals(List.of(new TargetEntry(TargetEntry.Kind.PLAYER_NAME, "Steve"))),
                    "Command should have added the player-name entry, targets=" + targets);
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void addDuplicateTargetEntryFailsAndChangesNothing(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        drone.setDroneData(withTargets(List.of(new TargetEntry(TargetEntry.Kind.ENTITY_TYPE, "minecraft:zombie"))));

        runCommand(helper, "seekerdrones config target add entity minecraft:zombie " + SELECTOR);

        helper.runAfterDelay(3, () -> {
            List<TargetEntry> targets = drone.snapshotData().config().targets();
            helper.assertTrue(targets.equals(List.of(new TargetEntry(TargetEntry.Kind.ENTITY_TYPE, "minecraft:zombie"))),
                    "Adding a duplicate target entry should fail and leave the list unchanged, targets=" + targets);
            helper.succeed();
        });
    }

    // --- target remove / clear / list (DESIGN.md 2.6) ---

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void targetRemoveCommandRemovesByOneBasedIndex(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        TargetEntry skeleton = new TargetEntry(TargetEntry.Kind.ENTITY_TYPE, "minecraft:skeleton");
        TargetEntry zombie = new TargetEntry(TargetEntry.Kind.ENTITY_TYPE, "minecraft:zombie");
        drone.setDroneData(withTargets(List.of(skeleton, zombie)));

        runCommand(helper, "seekerdrones config target remove 1 " + SELECTOR);

        helper.runAfterDelay(3, () -> {
            List<TargetEntry> targets = drone.snapshotData().config().targets();
            helper.assertTrue(targets.equals(List.of(zombie)),
                    "Removing index 1 should drop the skeleton entry and keep the zombie one, targets=" + targets);
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void removeOutOfRangeIndexFailsAndChangesNothing(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        TargetEntry zombie = new TargetEntry(TargetEntry.Kind.ENTITY_TYPE, "minecraft:zombie");
        drone.setDroneData(withTargets(List.of(zombie)));

        runCommand(helper, "seekerdrones config target remove 5 " + SELECTOR);

        helper.runAfterDelay(3, () -> {
            List<TargetEntry> targets = drone.snapshotData().config().targets();
            helper.assertTrue(targets.equals(List.of(zombie)),
                    "Removing an out-of-range index should fail and leave the list unchanged, targets=" + targets);
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void targetClearCommandClearsAllEntries(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        drone.setDroneData(withTargets(List.of(
                new TargetEntry(TargetEntry.Kind.ENTITY_TYPE, "minecraft:skeleton"),
                new TargetEntry(TargetEntry.Kind.ENTITY_TYPE, "minecraft:zombie"))));

        runCommand(helper, "seekerdrones config target clear " + SELECTOR);

        helper.runAfterDelay(3, () -> {
            List<TargetEntry> targets = drone.snapshotData().config().targets();
            helper.assertTrue(targets.isEmpty(), "target clear should remove every entry, targets=" + targets);
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void targetListCommandDoesNotMutateData(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        List<TargetEntry> original = List.of(new TargetEntry(TargetEntry.Kind.ENTITY_TYPE, "minecraft:zombie"));
        drone.setDroneData(withTargets(original));

        runCommand(helper, "seekerdrones config target list " + SELECTOR);

        helper.runAfterDelay(3, () -> {
            helper.assertTrue(drone.snapshotData().config().targets().equals(original), "target list should be read-only and not change the targets");
            helper.succeed();
        });
    }

    // --- followdistance (DESIGN.md 2.6) ---

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void followDistanceCommandSetsDistance(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));

        runCommand(helper, "seekerdrones config followdistance 7 " + SELECTOR);

        helper.runAfterDelay(3, () -> {
            int distance = drone.snapshotData().config().followDistance();
            helper.assertTrue(distance == 7, "followdistance should have been set to 7, was " + distance);
            helper.succeed();
        });
    }

    // --- patrolaltitude (DESIGN.md 3.2, 9) ---

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void patrolAltitudeCommandSetsAndClearsOnEntities(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));

        runCommand(helper, "seekerdrones config patrolaltitude set 80 " + SELECTOR);

        helper.runAfterDelay(3, () -> {
            helper.assertTrue(drone.snapshotData().config().patrolAltitude().equals(Optional.of(80)),
                    "patrolaltitude set should store the given Y level, was " + drone.snapshotData().config().patrolAltitude());

            runCommand(helper, "seekerdrones config patrolaltitude clear " + SELECTOR);

            helper.runAfterDelay(3, () -> {
                helper.assertTrue(drone.snapshotData().config().patrolAltitude().isEmpty(),
                        "patrolaltitude clear should remove the configured altitude, was " + drone.snapshotData().config().patrolAltitude());
                helper.succeed();
            });
        });
    }

    // --- Held-item form (best effort; needs a real ServerPlayer, see report) ---

    @GameTest(template = "empty", timeoutTicks = 40)
    @SuppressWarnings("deprecation") // GameTestHelper.makeMockServerPlayerInLevel is deprecated for removal but still the only way to get a real ServerPlayer here.
    public static void heldItemFormAddsTargetEntry(GameTestHelper helper) {
        MinecraftServer server = server(helper);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        Vec3 pos = helper.absoluteVec(new Vec3(4, 1, 4));
        player.moveTo(pos.x, pos.y, pos.z, 0.0F, 0.0F);
        ItemStack stack = DroneItem.createStack(DroneData.createNew());
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);

        CommandSourceStack source = server.createCommandSourceStack()
                .withEntity(player)
                .withLevel(helper.getLevel())
                .withPosition(pos)
                .withPermission(4);
        server.getCommands().performPrefixedCommand(source, "seekerdrones config target add entity minecraft:zombie");

        helper.runAfterDelay(3, () -> {
            try {
                DroneData data = DroneItem.getData(player.getMainHandItem());
                helper.assertTrue(data.config().targets().equals(List.of(new TargetEntry(TargetEntry.Kind.ENTITY_TYPE, "minecraft:zombie"))),
                        "Held-item command should have added the target entry, targets=" + data.config().targets());
            } finally {
                server.getPlayerList().remove(player);
            }
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    @SuppressWarnings("deprecation") // GameTestHelper.makeMockServerPlayerInLevel is deprecated for removal but still the only way to get a real ServerPlayer here.
    public static void heldItemFormSetsAndClearsPatrolAltitude(GameTestHelper helper) {
        MinecraftServer server = server(helper);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        Vec3 pos = helper.absoluteVec(new Vec3(4, 1, 4));
        player.moveTo(pos.x, pos.y, pos.z, 0.0F, 0.0F);
        ItemStack stack = DroneItem.createStack(DroneData.createNew());
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);

        CommandSourceStack source = server.createCommandSourceStack()
                .withEntity(player)
                .withLevel(helper.getLevel())
                .withPosition(pos)
                .withPermission(4);
        server.getCommands().performPrefixedCommand(source, "seekerdrones config patrolaltitude set 64");

        helper.runAfterDelay(3, () -> {
            DroneData afterSet = DroneItem.getData(player.getMainHandItem());
            helper.assertTrue(afterSet.config().patrolAltitude().equals(Optional.of(64)),
                    "Held-item command should have set the patrol altitude, was " + afterSet.config().patrolAltitude());

            server.getCommands().performPrefixedCommand(source, "seekerdrones config patrolaltitude clear");

            helper.runAfterDelay(3, () -> {
                try {
                    DroneData afterClear = DroneItem.getData(player.getMainHandItem());
                    helper.assertTrue(afterClear.config().patrolAltitude().isEmpty(),
                            "Held-item command should have cleared the patrol altitude, was " + afterClear.config().patrolAltitude());
                } finally {
                    server.getPlayerList().remove(player);
                }
                helper.succeed();
            });
        });
    }

    // --- Helpers ---

    private static MinecraftServer server(GameTestHelper helper) {
        return helper.getLevel().getServer();
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

    private static DroneData withTargets(List<TargetEntry> targets) {
        DroneData base = DroneData.createNew();
        return base.withConfig(base.config().withTargets(targets));
    }

    private static Zombie spawnStationaryZombie(GameTestHelper helper, int x, int y, int z) {
        Zombie zombie = helper.spawn(EntityType.ZOMBIE, new BlockPos(x, y, z));
        zombie.setNoAi(true);
        zombie.setNoGravity(true);
        zombie.setInvulnerable(true);
        return zombie;
    }
}
