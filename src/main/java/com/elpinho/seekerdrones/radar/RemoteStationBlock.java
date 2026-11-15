package com.elpinho.seekerdrones.radar;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The blocks of the radar machines a remote can be opened through (DESIGN.md sections 7.9 and 7.10). They record
 * their placer for the intruder alarm, which comparators read from either half.
 */
public abstract class RemoteStationBlock extends TwoBlockMachineBlock {
    protected RemoteStationBlock(Properties properties) {
        super(properties);
    }

    /** Records the placer for the intruder alarm (machines judge by the placer, never the viewer). */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide() && placer instanceof Player player && level.getBlockEntity(pos) instanceof RemoteStationBlockEntity<?> station) {
            station.setPlacer(player.getUUID());
        }
    }

    // --- Intruder alarm (comparator output) ---

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        return level.getBlockEntity(lowerPos(state, pos)) instanceof RemoteStationBlockEntity<?> station ? station.getAlarm() : 0;
    }
}
