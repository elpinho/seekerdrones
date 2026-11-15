package com.elpinho.seekerdrones.commandcenter;

import java.util.Map;

import com.elpinho.seekerdrones.radar.RemoteStationBlock;
import com.elpinho.seekerdrones.registry.ModBlockEntities;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Drone Command Center (DESIGN.md section 7.10), the top radar tier: a holotable. The two-block rules are shared with
 * the Radar Station, and the placer and alarm with the Satellite Uplink ({@link RemoteStationBlock}). The upper block
 * holds only the hologram, so it has no collision; its outline is the hologram's volume.
 */
public class CommandCenterBlock extends RemoteStationBlock {
    public static final MapCodec<CommandCenterBlock> CODEC = simpleCodec(CommandCenterBlock::new);

    /** Shapes in model space (front = north), rotated per facing. They follow the model roughly. */
    private static final VoxelShape LOWER_NORTH = Shapes.or(
            Block.box(0, 0, 0, 16, 3, 16),          // plinth
            Block.box(2.5, 3, 2.5, 13.5, 10, 14.5), // pedestal and conduits
            Block.box(0, 10, 0, 16, 12, 16),        // table top
            Block.box(0.5, 12, 0, 15.5, 13, 2.5),   // front lip
            Block.box(5, 12, 5, 11, 13, 11),        // emitter well
            Block.box(1, 12, 4, 2, 14, 5),          // emitter pins
            Block.box(14, 12, 4, 15, 14, 5),
            Block.box(1, 12, 14, 2, 14, 15),
            Block.box(14, 12, 14, 15, 14, 15));
    /** The hologram's volume: the disc and the blips floating above it. */
    private static final VoxelShape UPPER_NORTH = Block.box(1.5, 0, 1.5, 14.5, 9, 14.5);
    private static final Map<Direction, VoxelShape> LOWER_SHAPES = rotations(LOWER_NORTH);
    private static final Map<Direction, VoxelShape> UPPER_SHAPES = rotations(UPPER_NORTH);

    public CommandCenterBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected Map<Direction, VoxelShape> lowerShapes() {
        return LOWER_SHAPES;
    }

    @Override
    protected Map<Direction, VoxelShape> upperShapes() {
        return UPPER_SHAPES;
    }

    /** Players can reach into the hologram: the upper block has no collision. */
    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return isLower(state) ? super.getCollisionShape(state, level, pos, context) : Shapes.empty();
    }

    @Override
    protected BlockEntityType<CommandCenterBlockEntity> blockEntityType() {
        return ModBlockEntities.COMMAND_CENTER.get();
    }
}
