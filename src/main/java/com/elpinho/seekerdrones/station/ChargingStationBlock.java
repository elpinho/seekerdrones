package com.elpinho.seekerdrones.station;

import java.util.Optional;
import java.util.UUID;

import javax.annotation.Nullable;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Drone Charging Station (DESIGN.md section 7.4). Registers itself in the dimension's station registry when placed,
 * with its placer's UUID if a player placed it, and unregisters when removed.
 */
public class ChargingStationBlock extends BaseEntityBlock {
    public static final MapCodec<ChargingStationBlock> CODEC = simpleCodec(ChargingStationBlock::new);

    public ChargingStationBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ChargingStationBlockEntity(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        // Any placement registers, e.g. by command or a block placer. setPlacedBy adds the owner for players. For a
        // player placing the item, NeoForge calls this after setPlacedBy, so keep the owner the block entity already has.
        if (level instanceof ServerLevel serverLevel && !oldState.is(this)) {
            Optional<UUID> owner = level.getBlockEntity(pos) instanceof ChargingStationBlockEntity station ? station.getOwner() : Optional.empty();
            ChargingStationRegistry.get(serverLevel).add(pos, owner);
        }
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level instanceof ServerLevel serverLevel && placer instanceof Player player
                && level.getBlockEntity(pos) instanceof ChargingStationBlockEntity station) {
            station.setOwner(player.getUUID());
            ChargingStationRegistry.get(serverLevel).add(pos, Optional.of(player.getUUID()));
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (level instanceof ServerLevel serverLevel && !newState.is(this)) {
            ChargingStationRegistry.get(serverLevel).remove(pos);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
