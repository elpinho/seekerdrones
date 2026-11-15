package com.elpinho.seekerdrones.gametest;

import static com.elpinho.seekerdrones.gametest.GameTestUtil.mockPlayer;
import static com.elpinho.seekerdrones.gametest.GameTestUtil.placeTwoBlock;
import static com.elpinho.seekerdrones.gametest.GameTestUtil.removePlayer;
import static com.elpinho.seekerdrones.gametest.GameTestUtil.spawnPlayer;

import java.util.UUID;

import com.elpinho.seekerdrones.radar.RadarMachineBlockEntity;
import com.elpinho.seekerdrones.radar.RemoteStationBlockEntity;
import com.elpinho.seekerdrones.radar.TwoBlockMachineBlock;
import com.elpinho.seekerdrones.registry.ModBlocks;
import com.elpinho.seekerdrones.registry.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The shared two-block rules (DESIGN.md sections 7.8 to 7.10) for the radar machines, the Radar Station, the
 * Satellite Uplink and the Command Center: placement needs a free block above, breaking either half breaks both and drops one item (none in
 * creative), pistons can't push either half, and rotating or mirroring keeps the halves aligned. The Radar Station's
 * own placing and breaking tests are in {@code RadarStationGameTests}; here they run against the Uplink, and the rules
 * that were not covered yet run against both.
 */
@GameTestHolder("seekerdrones")
@PrefixGameTestTemplate(false)
public class TwoBlockMachineGameTests {
    /** A machine under test. */
    private enum Machine {
        RADAR, UPLINK, COMMAND_CENTER;

        TwoBlockMachineBlock block() {
            return switch (this) {
                case RADAR -> ModBlocks.RADAR_STATION.get();
                case UPLINK -> ModBlocks.UPLINK.get();
                case COMMAND_CENTER -> ModBlocks.COMMAND_CENTER.get();
            };
        }

        BlockItem item() {
            return switch (this) {
                case RADAR -> ModItems.RADAR_STATION.get();
                case UPLINK -> ModItems.UPLINK.get();
                case COMMAND_CENTER -> ModItems.COMMAND_CENTER.get();
            };
        }
    }

    private static BlockPlaceContext placeContext(GameTestHelper helper, Machine machine, Player player, BlockPos supportAbs) {
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(supportAbs).add(0, 0.5, 0), Direction.UP, supportAbs, false);
        return new BlockPlaceContext(player, InteractionHand.MAIN_HAND, new ItemStack(machine.item()), hit);
    }

    private static int dropped(GameTestHelper helper, Machine machine, BlockPos rel) {
        Vec3 center = helper.absoluteVec(Vec3.atCenterOf(rel));
        int total = 0;
        for (ItemEntity entity : helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(center, center).inflate(4.0))) {
            if (entity.getItem().is(machine.item())) {
                total += entity.getItem().getCount();
            }
        }
        return total;
    }

    // --- Placement ---

    private static void placesBothHalves(GameTestHelper helper, Machine machine) {
        helper.setBlock(new BlockPos(4, 0, 4), Blocks.STONE);
        UUID uuid = UUID.randomUUID();
        Player player = mockPlayer(helper, uuid, 1, 1, 1, false, 0);
        InteractionResult result = machine.item().place(placeContext(helper, machine, player, helper.absolutePos(new BlockPos(4, 0, 4))));
        helper.assertTrue(result.consumesAction(), "Placing with free space above should succeed, got " + result);
        BlockState lower = helper.getBlockState(new BlockPos(4, 1, 4));
        BlockState upper = helper.getBlockState(new BlockPos(4, 2, 4));
        helper.assertTrue(lower.is(machine.block()) && lower.getValue(TwoBlockMachineBlock.HALF) == DoubleBlockHalf.LOWER, "Lower half should be placed");
        helper.assertTrue(upper.is(machine.block()) && upper.getValue(TwoBlockMachineBlock.HALF) == DoubleBlockHalf.UPPER, "Upper half should be placed");
        helper.assertTrue(lower.getValue(TwoBlockMachineBlock.FACING) == upper.getValue(TwoBlockMachineBlock.FACING), "Both halves should face the same way");
        helper.assertTrue(helper.getLevel().getBlockEntity(helper.absolutePos(new BlockPos(4, 1, 4))) instanceof RadarMachineBlockEntity<?>, "Lower half should have the block entity");
        helper.assertTrue(helper.getLevel().getBlockEntity(helper.absolutePos(new BlockPos(4, 2, 4))) == null, "Upper half should have no block entity");
        if (machine != Machine.RADAR) {
            RemoteStationBlockEntity<?> station = (RemoteStationBlockEntity<?>) helper.getLevel().getBlockEntity(helper.absolutePos(new BlockPos(4, 1, 4)));
            helper.assertTrue(station.getPlacer().equals(java.util.Optional.of(uuid)), "The " + machine + " should record its placer, got " + station.getPlacer());
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void uplinkPlacingTheItemPlacesBothHalvesAndRecordsThePlacer(GameTestHelper helper) {
        placesBothHalves(helper, Machine.UPLINK);
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void commandCenterPlacingTheItemPlacesBothHalvesAndRecordsThePlacer(GameTestHelper helper) {
        placesBothHalves(helper, Machine.COMMAND_CENTER);
    }

    private static void placingFailsWhenBlocked(GameTestHelper helper, Machine machine) {
        helper.setBlock(new BlockPos(4, 0, 4), Blocks.STONE);
        helper.setBlock(new BlockPos(4, 2, 4), Blocks.STONE);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        InteractionResult result = machine.item().place(placeContext(helper, machine, player, helper.absolutePos(new BlockPos(4, 0, 4))));
        helper.assertTrue(!result.consumesAction(), "Placing under a solid block should fail, got " + result);
        helper.assertTrue(helper.getBlockState(new BlockPos(4, 1, 4)).isAir(), "No lower half should be placed");
        helper.assertTrue(helper.getBlockState(new BlockPos(4, 2, 4)).is(Blocks.STONE), "The blocker should stay");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void uplinkPlacingFailsWhenSpaceAboveIsBlocked(GameTestHelper helper) {
        placingFailsWhenBlocked(helper, Machine.UPLINK);
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void commandCenterPlacingFailsWhenSpaceAboveIsBlocked(GameTestHelper helper) {
        placingFailsWhenBlocked(helper, Machine.COMMAND_CENTER);
    }

    private static void placingSucceedsOverReplaceable(GameTestHelper helper, Machine machine, Block above) {
        helper.setBlock(new BlockPos(4, 0, 4), Blocks.STONE);
        helper.setBlock(new BlockPos(4, 2, 4), above);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        InteractionResult result = machine.item().place(placeContext(helper, machine, player, helper.absolutePos(new BlockPos(4, 0, 4))));
        helper.assertTrue(result.consumesAction(), "A replaceable block above shouldn't block placing, got " + result);
        helper.assertTrue(helper.getBlockState(new BlockPos(4, 1, 4)).is(machine.block()), "The lower half should stay placed, is " + helper.getBlockState(new BlockPos(4, 1, 4)));
        helper.assertTrue(helper.getBlockState(new BlockPos(4, 2, 4)).getValue(TwoBlockMachineBlock.HALF) == DoubleBlockHalf.UPPER, "Upper half should replace the block");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void uplinkPlacingSucceedsWhenSpaceAboveIsReplaceable(GameTestHelper helper) {
        placingSucceedsOverReplaceable(helper, Machine.UPLINK, Blocks.SHORT_GRASS);
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void commandCenterPlacingSucceedsWhenSpaceAboveIsReplaceable(GameTestHelper helper) {
        placingSucceedsOverReplaceable(helper, Machine.COMMAND_CENTER, Blocks.SHORT_GRASS);
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void uplinkPlacingFailsAtTheWorldHeightLimit(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos column = helper.absolutePos(new BlockPos(4, 0, 4));
        int top = level.getMaxBuildHeight() - 1;
        BlockPos support = new BlockPos(column.getX(), top - 1, column.getZ());
        BlockPos high = support.above();
        try {
            level.setBlock(support, Blocks.STONE.defaultBlockState(), 3);
            Player player = helper.makeMockPlayer(GameType.SURVIVAL);
            InteractionResult atLimit = Machine.UPLINK.item().place(placeContext(helper, Machine.UPLINK, player, support));
            helper.assertTrue(!atLimit.consumesAction(), "Placing at the top block (no room for the upper half) should fail, got " + atLimit);
            helper.assertTrue(level.getBlockState(high).isAir(), "Nothing should be placed at the height limit");
        } finally {
            level.setBlock(high, Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(support, Blocks.AIR.defaultBlockState(), 3);
        }
        helper.succeed();
    }

    // --- Breaking ---

    private static void survivalBreak(GameTestHelper helper, Machine machine, boolean upperHalf) {
        BlockPos rel = new BlockPos(6, 1, 6);
        placeTwoBlock(helper, rel, machine.block());
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 1, 1, 1, 0);
        try {
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_PICKAXE));
            helper.assertTrue(player.gameMode.destroyBlock(helper.absolutePos(upperHalf ? rel.above() : rel)), "destroyBlock should go through");
            helper.assertTrue(helper.getBlockState(rel).isAir(), "Lower half should be gone");
            helper.assertTrue(helper.getBlockState(rel.above()).isAir(), "Upper half should be gone");
            helper.assertTrue(dropped(helper, machine, rel) == 1, "Exactly one item should drop, found " + dropped(helper, machine, rel));
        } finally {
            removePlayer(helper, player);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void uplinkBreakingLowerHalfInSurvivalRemovesBothAndDropsOneItem(GameTestHelper helper) {
        survivalBreak(helper, Machine.UPLINK, false);
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void uplinkBreakingUpperHalfInSurvivalRemovesBothAndDropsOneItem(GameTestHelper helper) {
        survivalBreak(helper, Machine.UPLINK, true);
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void commandCenterBreakingLowerHalfInSurvivalRemovesBothAndDropsOneItem(GameTestHelper helper) {
        survivalBreak(helper, Machine.COMMAND_CENTER, false);
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void commandCenterBreakingUpperHalfInSurvivalRemovesBothAndDropsOneItem(GameTestHelper helper) {
        survivalBreak(helper, Machine.COMMAND_CENTER, true);
    }

    @SuppressWarnings("deprecation")
    private static void creativeBreak(GameTestHelper helper, Machine machine, boolean upperHalf) {
        BlockPos rel = new BlockPos(6, 1, 6);
        placeTwoBlock(helper, rel, machine.block());
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        try {
            player.setGameMode(GameType.CREATIVE);
            player.moveTo(helper.absoluteVec(new Vec3(1, 1, 1)));
            helper.assertTrue(player.gameMode.destroyBlock(helper.absolutePos(upperHalf ? rel.above() : rel)), "destroyBlock should go through");
            helper.assertTrue(helper.getBlockState(rel).isAir(), "Lower half should be gone");
            helper.assertTrue(helper.getBlockState(rel.above()).isAir(), "Upper half should be gone");
            helper.assertTrue(dropped(helper, machine, rel) == 0, "Creative breaking drops nothing, found " + dropped(helper, machine, rel));
        } finally {
            removePlayer(helper, player);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void uplinkBreakingLowerHalfInCreativeRemovesBothAndDropsNothing(GameTestHelper helper) {
        creativeBreak(helper, Machine.UPLINK, false);
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void uplinkBreakingUpperHalfInCreativeRemovesBothAndDropsNothing(GameTestHelper helper) {
        creativeBreak(helper, Machine.UPLINK, true);
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void commandCenterBreakingLowerHalfInCreativeRemovesBothAndDropsNothing(GameTestHelper helper) {
        creativeBreak(helper, Machine.COMMAND_CENTER, false);
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void commandCenterBreakingUpperHalfInCreativeRemovesBothAndDropsNothing(GameTestHelper helper) {
        creativeBreak(helper, Machine.COMMAND_CENTER, true);
    }

    // --- Upper half routes to the lower one ---

    private static void rightClickingUpperHalfOpensTheLowerHalfsMenu(GameTestHelper helper, Machine machine) {
        BlockPos rel = new BlockPos(4, 1, 4);
        RadarMachineBlockEntity<?> station = placeTwoBlock(helper, rel, machine.block());
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4, 1, 2, 0);
        try {
            BlockPos upperAbs = helper.absolutePos(rel.above());
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(upperAbs), Direction.NORTH, upperAbs, false);
            // The fake connection of a test player can't take NeoForge's open-screen payload, so opening throws after
            // the menu was created; the lower half's block entity registering the viewer shows which half's menu it was.
            try {
                helper.getBlockState(rel.above()).useWithoutItem(helper.getLevel(), player, hit);
            } catch (RuntimeException expected) {
                helper.assertTrue(String.valueOf(expected.getMessage()).contains("advanced_open_screen"), "Unexpected failure: " + expected);
            }
            helper.assertTrue(station.viewers().contains(player), "Using the upper half should open the lower half's menu (the " + machine + " should have the viewer)");
        } finally {
            removePlayer(helper, player);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void uplinkRightClickingUpperHalfOpensTheLowerHalfsMenu(GameTestHelper helper) {
        rightClickingUpperHalfOpensTheLowerHalfsMenu(helper, Machine.UPLINK);
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void commandCenterRightClickingUpperHalfOpensTheLowerHalfsMenu(GameTestHelper helper) {
        rightClickingUpperHalfOpensTheLowerHalfsMenu(helper, Machine.COMMAND_CENTER);
    }

    // --- Pistons ---

    private static void pistonsCannotPush(GameTestHelper helper, Machine machine) {
        // Machine at (4, 1..2, 4); pistons at z = 2 facing south, each next to a redstone block that powers it.
        placeTwoBlock(helper, new BlockPos(4, 1, 4), machine.block());
        BlockState piston = Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.SOUTH);
        BlockPos lowerPiston = new BlockPos(4, 1, 3);
        BlockPos upperPiston = new BlockPos(4, 2, 3);
        helper.setBlock(lowerPiston, piston);
        helper.setBlock(upperPiston, piston);
        helper.setBlock(new BlockPos(3, 1, 3), Blocks.REDSTONE_BLOCK);
        helper.setBlock(new BlockPos(3, 2, 3), Blocks.REDSTONE_BLOCK);
        helper.runAfterDelay(10, () -> {
            BlockState lower = helper.getBlockState(new BlockPos(4, 1, 4));
            BlockState upper = helper.getBlockState(new BlockPos(4, 2, 4));
            helper.assertTrue(lower.is(machine.block()) && lower.getValue(TwoBlockMachineBlock.HALF) == DoubleBlockHalf.LOWER, "The lower half should not have moved, is " + lower);
            helper.assertTrue(upper.is(machine.block()) && upper.getValue(TwoBlockMachineBlock.HALF) == DoubleBlockHalf.UPPER, "The upper half should not have moved, is " + upper);
            helper.assertTrue(!helper.getBlockState(lowerPiston).getValue(PistonBaseBlock.EXTENDED), "The piston facing the lower half must not extend");
            helper.assertTrue(!helper.getBlockState(upperPiston).getValue(PistonBaseBlock.EXTENDED), "The piston facing the upper half must not extend");
            helper.assertTrue(lower.getPistonPushReaction() == PushReaction.BLOCK && upper.getPistonPushReaction() == PushReaction.BLOCK, "Both halves should block pistons");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void radarPistonsCannotPushEitherHalf(GameTestHelper helper) {
        pistonsCannotPush(helper, Machine.RADAR);
    }

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void uplinkPistonsCannotPushEitherHalf(GameTestHelper helper) {
        pistonsCannotPush(helper, Machine.UPLINK);
    }

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void commandCenterPistonsCannotPushEitherHalf(GameTestHelper helper) {
        pistonsCannotPush(helper, Machine.COMMAND_CENTER);
    }

    // --- Rotation and mirroring ---

    private static void rotationKeepsHalvesAligned(GameTestHelper helper, Machine machine) {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            BlockState lower = machine.block().defaultBlockState().setValue(TwoBlockMachineBlock.FACING, facing);
            BlockState upper = lower.setValue(TwoBlockMachineBlock.HALF, DoubleBlockHalf.UPPER);
            for (Rotation rotation : Rotation.values()) {
                BlockState rotatedLower = lower.rotate(rotation);
                BlockState rotatedUpper = upper.rotate(rotation);
                helper.assertTrue(rotatedLower.getValue(TwoBlockMachineBlock.FACING) == rotation.rotate(facing), "Lower half should rotate with " + rotation + ", facing " + facing);
                helper.assertTrue(rotatedUpper.getValue(TwoBlockMachineBlock.FACING) == rotatedLower.getValue(TwoBlockMachineBlock.FACING), "Halves should stay aligned after " + rotation);
                helper.assertTrue(rotatedUpper.getValue(TwoBlockMachineBlock.HALF) == DoubleBlockHalf.UPPER, "Rotating keeps the half");
            }
            for (Mirror mirror : Mirror.values()) {
                BlockState mirroredLower = lower.mirror(mirror);
                BlockState mirroredUpper = upper.mirror(mirror);
                helper.assertTrue(mirroredLower.getValue(TwoBlockMachineBlock.FACING) == mirror.mirror(facing), "Lower half should mirror with " + mirror + ", facing " + facing);
                helper.assertTrue(mirroredUpper.getValue(TwoBlockMachineBlock.FACING) == mirroredLower.getValue(TwoBlockMachineBlock.FACING), "Halves should stay aligned after " + mirror);
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void radarRotatingAndMirroringKeepsBothHalvesAligned(GameTestHelper helper) {
        rotationKeepsHalvesAligned(helper, Machine.RADAR);
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void uplinkRotatingAndMirroringKeepsBothHalvesAligned(GameTestHelper helper) {
        rotationKeepsHalvesAligned(helper, Machine.UPLINK);
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void commandCenterRotatingAndMirroringKeepsBothHalvesAligned(GameTestHelper helper) {
        rotationKeepsHalvesAligned(helper, Machine.COMMAND_CENTER);
    }
}
