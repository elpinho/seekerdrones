package com.elpinho.seekerdrones.radar;

import java.util.Map;

import com.elpinho.seekerdrones.registry.ModBlockEntities;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Drone Radar Station (DESIGN.md section 7.8). Anyone may open it; each viewer only sees their own drones. The
 * two-block rules are shared with the Uplink ({@link TwoBlockMachineBlock}). {@link #POWERED} turns the head and lights
 * the model (section 7.6).
 */
public class RadarStationBlock extends TwoBlockMachineBlock {
    public static final MapCodec<RadarStationBlock> CODEC = simpleCodec(RadarStationBlock::new);

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

    @Override
    protected BlockEntityType<RadarStationBlockEntity> blockEntityType() {
        return ModBlockEntities.RADAR_STATION.get();
    }
}
