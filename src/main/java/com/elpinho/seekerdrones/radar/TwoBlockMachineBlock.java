package com.elpinho.seekerdrones.radar;

import java.util.EnumMap;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The radar machines' shared block (DESIGN.md sections 7.8 and 7.9): two blocks tall like a door, facing the player
 * when placed (cosmetic only). The lower half holds the block entity, the energy and the model. The upper half is an
 * invisible placeholder for the mast and the head: using it opens the lower half's screen. {@link #POWERED} (lower
 * half only) is written by the block entity.
 * <p>
 * Placement needs a replaceable block above, inside the world. Breaking either half breaks both, and only the lower
 * half drops the item (its loot table checks {@code half=lower}). Pistons can't push it (the blocks' push reaction).
 */
public abstract class TwoBlockMachineBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;
    public static final EnumProperty<DoubleBlockHalf> HALF = BlockStateProperties.DOUBLE_BLOCK_HALF;

    protected TwoBlockMachineBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(POWERED, false)
                .setValue(HALF, DoubleBlockHalf.LOWER));
    }

    /** The collision and outline shapes of the lower half, by facing. */
    protected abstract Map<Direction, VoxelShape> lowerShapes();

    /** The collision and outline shapes of the upper half, by facing. */
    protected abstract Map<Direction, VoxelShape> upperShapes();

    /** The lower half's block entity type, which ticks on the server. */
    protected abstract BlockEntityType<? extends RadarMachineBlockEntity<?>> blockEntityType();

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, POWERED, HALF);
    }

    public static boolean isLower(BlockState state) {
        return state.getValue(HALF) == DoubleBlockHalf.LOWER;
    }

    /** The lower half's position, where the block entity is, from either half. */
    public static BlockPos lowerPos(BlockState state, BlockPos pos) {
        return isLower(state) ? pos : pos.below();
    }

    // --- Placing and breaking ---

    /** Only where the block above is free and inside the world, like a door. */
    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockPos pos = context.getClickedPos();
        Level level = context.getLevel();
        if (pos.getY() >= level.getMaxBuildHeight() - 1 || !level.getBlockState(pos.above()).canBeReplaced(context)) {
            return null;
        }
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        level.setBlock(pos.above(), state.setValue(HALF, DoubleBlockHalf.UPPER).setValue(POWERED, false), Block.UPDATE_ALL);
    }

    /** A half whose other half is gone turns into air, so breaking either half breaks both. */
    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState, LevelAccessor level,
                                     BlockPos pos, BlockPos neighborPos) {
        boolean lower = isLower(state);
        if (direction == (lower ? Direction.UP : Direction.DOWN)) {
            boolean otherHalf = neighborState.is(this) && isLower(neighborState) != lower;
            return otherHalf ? state : Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }

    /**
     * Only the lower half drops the item. Breaking the upper half destroys the lower one through {@link #updateShape},
     * which drops it; in creative (or without the right tool) the lower half is removed first without drops, like
     * vanilla's double plants.
     */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide() && !isLower(state) && (player.isCreative() || !player.hasCorrectToolForDrops(state))) {
            BlockPos below = pos.below();
            BlockState belowState = level.getBlockState(below);
            if (belowState.is(this) && isLower(belowState)) {
                BlockState empty = belowState.getFluidState().is(Fluids.WATER) ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();
                level.setBlock(below, empty, Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
                level.levelEvent(player, 2001, below, Block.getId(belowState));
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    // --- Shape and rendering ---

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return (isLower(state) ? lowerShapes() : upperShapes()).get(state.getValue(FACING));
    }

    /** The lower half draws the whole model (the head through the block entity renderer); the upper half draws nothing. */
    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return isLower(state) ? RenderShape.MODEL : RenderShape.INVISIBLE;
    }

    /** A shape in model space (front = north), turned for each horizontal facing like the blockstate's y rotation. */
    protected static Map<Direction, VoxelShape> rotations(VoxelShape north) {
        Map<Direction, VoxelShape> shapes = new EnumMap<>(Direction.class);
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            // Quarter turns clockwise (seen from above) from north.
            int turns = (facing.get2DDataValue() - Direction.NORTH.get2DDataValue() + 4) % 4;
            VoxelShape shape = Shapes.empty();
            for (var box : north.toAabbs()) {
                double x0 = box.minX, z0 = box.minZ, x1 = box.maxX, z1 = box.maxZ;
                for (int i = 0; i < turns; i++) {
                    double nx0 = 1 - z1, nx1 = 1 - z0;
                    z0 = x0;
                    z1 = x1;
                    x0 = nx0;
                    x1 = nx1;
                }
                shape = Shapes.or(shape, Shapes.box(x0, box.minY, z0, x1, box.maxY, z1));
            }
            shapes.put(facing, shape.optimize());
        }
        return shapes;
    }

    // --- Block entity (lower half only) ---

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return isLower(state) ? blockEntityType().create(pos, state) : null;
    }

    @Nullable
    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide() || !isLower(state) || type != blockEntityType()) {
            return null;
        }
        return (BlockEntityTicker<T>) (BlockEntityTicker<RadarMachineBlockEntity<?>>) (tickLevel, pos, tickState, machine) ->
                machine.serverTick((ServerLevel) tickLevel);
    }

    /** Either half opens the lower half's screen. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        BlockPos lower = lowerPos(state, pos);
        if (player instanceof ServerPlayer serverPlayer && level.getBlockEntity(lower) instanceof MenuProvider machine) {
            serverPlayer.openMenu(machine, buf -> buf.writeBlockPos(lower));
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }
}
