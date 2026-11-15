package com.elpinho.seekerdrones.uplink;

import java.util.Map;

import javax.annotation.Nullable;

import com.elpinho.seekerdrones.radar.TwoBlockMachineBlock;
import com.elpinho.seekerdrones.registry.ModBlockEntities;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Drone Satellite Uplink (DESIGN.md section 7.9), the Advanced radar tier. The two-block rules are shared with the
 * Radar Station ({@link TwoBlockMachineBlock}). It records its placer for the intruder alarm, which comparators read
 * from either half.
 */
public class UplinkBlock extends TwoBlockMachineBlock {
    public static final MapCodec<UplinkBlock> CODEC = simpleCodec(UplinkBlock::new);

    /** Shapes in model space (front = north), rotated per facing. They follow the model roughly. */
    private static final VoxelShape LOWER_NORTH = Shapes.or(
            Block.box(0, 0, 0, 16, 3, 16),        // plinth
            Block.box(1, 3, 3, 15, 13, 16),       // body and conduits
            Block.box(2, 4.5, 1, 14, 13.5, 3),    // the flat-panel screen and its bracket
            Block.box(2, 13, 3, 14, 14, 15),      // deck
            Block.box(4, 14, 5.5, 12, 15, 13.5),  // turntable seat
            Block.box(6.5, 15, 8, 9.5, 16, 11));  // pedestal
    /** The pedestal, the mount, and the area the dish sweeps as it turns and tilts about (8, 9.5). */
    private static final VoxelShape UPPER_NORTH = Shapes.or(
            Block.box(6.5, 0, 8, 9.5, 2, 11),
            Block.box(4, 2, 6.5, 12, 7, 12.5),
            Block.box(1, 4, 1, 15, 16, 16));
    private static final Map<Direction, VoxelShape> LOWER_SHAPES = rotations(LOWER_NORTH);
    private static final Map<Direction, VoxelShape> UPPER_SHAPES = rotations(UPPER_NORTH);

    public UplinkBlock(Properties properties) {
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
    protected BlockEntityType<UplinkBlockEntity> blockEntityType() {
        return ModBlockEntities.UPLINK.get();
    }

    /** Records the placer for the intruder alarm (machines judge by the placer, never the viewer). */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide() && placer instanceof Player player && level.getBlockEntity(pos) instanceof UplinkBlockEntity uplink) {
            uplink.setPlacer(player.getUUID());
        }
    }

    // --- Intruder alarm (comparator output) ---

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        return level.getBlockEntity(lowerPos(state, pos)) instanceof UplinkBlockEntity uplink ? uplink.getAlarm() : 0;
    }
}
