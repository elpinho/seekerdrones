package com.elpinho.seekerdrones.radar;

import java.util.EnumMap;
import java.util.Map;

import javax.annotation.Nullable;

import com.elpinho.seekerdrones.registry.ModBlockEntities;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
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
 * Drone Radar Station (DESIGN.md section 7.8). Anyone may open it; each viewer only sees their own drones. It faces
 * the player when placed (cosmetic only) and is two blocks tall, like a door: the lower half holds the block entity,
 * the energy and the model, and the upper half is an invisible placeholder for the mast and the turning head.
 * {@link #POWERED} (lower half only) turns the head and lights the model (section 7.6).
 */
public class RadarStationBlock extends BaseEntityBlock {
    public static final MapCodec<RadarStationBlock> CODEC = simpleCodec(RadarStationBlock::new);
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;
    public static final EnumProperty<DoubleBlockHalf> HALF = BlockStateProperties.DOUBLE_BLOCK_HALF;

    /** Shapes in model space (front = north), rotated per facing. They follow the model roughly. */
    private static final VoxelShape LOWER_NORTH = Shapes.or(
            Block.box(0, 0, 0, 16, 3, 16),      // plinth
            Block.box(1, 3, 0.5, 15, 6, 6),     // desk
            Block.box(2, 6, 2, 14, 14, 5),      // the leaning screen panel
            Block.box(1, 3, 6, 15, 12, 15),     // body
            Block.box(0, 4, 7, 16, 11, 14),     // fins
            Block.box(2, 12, 6, 14, 14, 15),    // deck
            Block.box(5, 14, 7, 11, 15.5, 13),  // turntable seat
            Block.box(7, 15.5, 9, 9, 16, 11));  // mast foot
    /** The mast, and the area the head sweeps as it turns about (8, 10) (a column, since the dish turns). */
    private static final VoxelShape UPPER_NORTH = Shapes.or(
            Block.box(7, 0, 9, 9, 7, 11),
            Block.box(1, 7, 3, 15, 16, 16));
    private static final Map<Direction, VoxelShape> LOWER_SHAPES = rotations(LOWER_NORTH);
    private static final Map<Direction, VoxelShape> UPPER_SHAPES = rotations(UPPER_NORTH);

    public RadarStationBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(POWERED, false)
                .setValue(HALF, DoubleBlockHalf.LOWER));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

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

    // --- Placing and breaking (section 7.8) ---

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
     * Only the lower half drops the item (its loot table has a {@code half=lower} condition). Breaking the upper half
     * destroys the lower one through {@link #updateShape}, which drops it; in creative (or without the right tool) the
     * lower half is removed first without drops, like vanilla's double plants.
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
        return (isLower(state) ? LOWER_SHAPES : UPPER_SHAPES).get(state.getValue(FACING));
    }

    /** The lower half draws the whole model (the head through the block entity renderer); the upper half draws nothing. */
    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return isLower(state) ? RenderShape.MODEL : RenderShape.INVISIBLE;
    }

    private static Map<Direction, VoxelShape> rotations(VoxelShape north) {
        Map<Direction, VoxelShape> shapes = new EnumMap<>(Direction.class);
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            // Quarter turns clockwise (seen from above) from north, like the blockstate's y rotation.
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
        return isLower(state) ? new RadarStationBlockEntity(pos, state) : null;
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide() || !isLower(state) ? null
                : createTickerHelper(type, ModBlockEntities.RADAR_STATION.get(), RadarStationBlockEntity::serverTick);
    }

    /** Either half opens the lower half's screen. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        BlockPos lower = lowerPos(state, pos);
        if (player instanceof ServerPlayer serverPlayer && level.getBlockEntity(lower) instanceof RadarStationBlockEntity station) {
            serverPlayer.openMenu(station, buf -> buf.writeBlockPos(lower));
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }
}
