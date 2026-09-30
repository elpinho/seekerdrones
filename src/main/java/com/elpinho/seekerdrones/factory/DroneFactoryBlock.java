package com.elpinho.seekerdrones.factory;

import javax.annotation.Nullable;

import com.elpinho.seekerdrones.machine.MachineWorkingState;
import com.elpinho.seekerdrones.network.FactoryOperatorsPayload;
import com.elpinho.seekerdrones.operator.OperatorGroups;
import com.elpinho.seekerdrones.registry.ModBlockEntities;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.fluids.FluidUtil;

/**
 * Drone Factory (DESIGN.md section 7.1). The first player to place it owns a new Operator Group; a Factory item that
 * already carries a group ID reconnects to that group instead (section 6.1).
 */
public class DroneFactoryBlock extends BaseEntityBlock {
    public static final MapCodec<DroneFactoryBlock> CODEC = simpleCodec(DroneFactoryBlock::new);

    public DroneFactoryBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(MachineWorkingState.WORKING, false));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(MachineWorkingState.WORKING);
    }

    /** While building: smoke rising from the top. */
    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (!state.getValue(MachineWorkingState.WORKING)) {
            return;
        }
        for (int i = 0; i < 2; i++) {
            level.addParticle(ParticleTypes.SMOKE, pos.getX() + 0.2 + random.nextDouble() * 0.6, pos.getY() + 1.0,
                    pos.getZ() + 0.2 + random.nextDouble() * 0.6, 0, 0.03, 0);
        }
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new DroneFactoryBlockEntity(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide() ? null : createTickerHelper(type, ModBlockEntities.DRONE_FACTORY.get(), DroneFactoryBlockEntity::serverTick);
    }

    /** Runs after the item's components were applied, so a group ID from the item is already set. */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide() && placer instanceof Player player && level.getBlockEntity(pos) instanceof DroneFactoryBlockEntity factory
                && factory.getGroupId().isEmpty() && level.getServer() != null) {
            factory.setGroupId(OperatorGroups.get(level.getServer()).createGroup(player.getUUID()));
        }
    }

    /** Buckets and other fluid containers fill or empty the tank. */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
            BlockHitResult hitResult) {
        if (FluidUtil.getFluidHandler(stack).isPresent()) {
            return FluidUtil.interactWithFluidHandler(player, hand, level, pos, hitResult.getDirection())
                    ? ItemInteractionResult.sidedSuccess(level.isClientSide())
                    : ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    /** Anyone may use the machine (section 6.2). The Operator list inside is owner-only. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (player instanceof ServerPlayer serverPlayer && level.getBlockEntity(pos) instanceof DroneFactoryBlockEntity factory) {
            serverPlayer.openMenu(factory, buf -> buf.writeBlockPos(pos));
            if (serverPlayer.containerMenu instanceof DroneFactoryMenu menu) {
                FactoryOperatorsPayload.send(serverPlayer, menu, null);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof DroneFactoryBlockEntity factory) {
            factory.getDrops().forEach(stack -> Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), stack));
            level.updateNeighbourForOutputSignal(pos, this);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
