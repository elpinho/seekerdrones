package com.elpinho.seekerdrones.machine;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/**
 * The {@link #WORKING} block state of the machines (DESIGN.md section 7). It only drives client visuals (particles in
 * {@code animateTick}), so the server writes it only when it changes: one block update per start or stop, never per
 * tick. A machine shows as idle only after {@link #IDLE_DELAY} ticks without work, so one that works in bursts (e.g.
 * short on FE) doesn't flicker.
 */
public final class MachineWorkingState {
    public static final BooleanProperty WORKING = BooleanProperty.create("working");
    public static final int IDLE_DELAY = 20;

    /** Game time of the last work. Starts idle; game time is never negative. */
    private long lastWork = -IDLE_DELAY - 1;

    /** Call once per server tick with whether the machine made progress this tick. */
    public void update(Level level, BlockPos pos, boolean worked) {
        long time = level.getGameTime();
        if (worked) {
            lastWork = time;
        }
        set(level, pos, time - lastWork <= IDLE_DELAY);
    }

    /** Sets {@link #WORKING} at {@code pos} if the block has it and it differs. */
    public static void set(Level level, BlockPos pos, boolean working) {
        BlockState state = level.getBlockState(pos);
        if (state.hasProperty(WORKING) && state.getValue(WORKING) != working) {
            level.setBlock(pos, state.setValue(WORKING, working), Block.UPDATE_CLIENTS);
        }
    }
}
