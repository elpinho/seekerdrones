package com.elpinho.seekerdrones.station;

import java.util.Optional;
import java.util.UUID;

import javax.annotation.Nullable;

import com.elpinho.seekerdrones.machine.MachineWorkingState;
import com.elpinho.seekerdrones.network.StationStatusPayload;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.fluids.FluidUtil;

/**
 * Drone Charging Station (DESIGN.md section 7.4). Registers itself in the dimension's station registry when placed,
 * with its placer's UUID if a player placed it, and unregisters when removed. {@link MachineWorkingState#WORKING} is
 * set while it serves a drone, and {@link #REPAIRING} while that drone is also being healed. It faces the player
 * when placed; the facing is only cosmetic.
 */
public class ChargingStationBlock extends BaseEntityBlock {
    public static final MapCodec<ChargingStationBlock> CODEC = simpleCodec(ChargingStationBlock::new);
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    public static final BooleanProperty REPAIRING = BooleanProperty.create("repairing");

    public ChargingStationBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(MachineWorkingState.WORKING, false)
                .setValue(REPAIRING, false));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, MachineWorkingState.WORKING, REPAIRING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    /** The station has no ticker: this scheduled tick turns the working state off once the drone stops reporting work. */
    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (level.getBlockEntity(pos) instanceof ChargingStationBlockEntity station) {
            station.checkIdle();
        }
    }

    /** While serving a drone: electric sparks around the dock, plus hearts while repairing. */
    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (!state.getValue(MachineWorkingState.WORKING)) {
            return;
        }
        for (int i = 0; i < 3; i++) {
            level.addParticle(ParticleTypes.ELECTRIC_SPARK, pos.getX() + 0.15 + random.nextDouble() * 0.7, pos.getY() + 1.05,
                    pos.getZ() + 0.15 + random.nextDouble() * 0.7, 0, 0.1 + random.nextDouble() * 0.1, 0);
        }
        if (state.getValue(REPAIRING)) {
            level.addParticle(ParticleTypes.HEART, pos.getX() + 0.3 + random.nextDouble() * 0.4, pos.getY() + 1.3,
                    pos.getZ() + 0.3 + random.nextDouble() * 0.4, 0, 0, 0);
        }
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

    /** Buckets and other fluid containers fill the repair fluid tank. */
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

    /**
     * Opens the station screen. Anyone may look (section 6.2). Only players who may manage the upgrades get the
     * Upgrades tab and their inventory, decided here when the screen opens.
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (player instanceof ServerPlayer serverPlayer && level instanceof ServerLevel serverLevel
                && level.getBlockEntity(pos) instanceof ChargingStationBlockEntity station) {
            boolean canManage = ChargingStationAccess.canManage(serverPlayer, station);
            StationStatusPayload status = StationStatusPayload.of(serverLevel, station, canManage);
            serverPlayer.openMenu(new SimpleMenuProvider((containerId, inventory, menuPlayer) -> new ChargingStationMenu(containerId, inventory,
                    station, canManage), Component.translatable("screen.seekerdrones.charging_station")),
                    buf -> StationStatusPayload.STREAM_CODEC.encode(buf, status));
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (level instanceof ServerLevel serverLevel && !newState.is(this)) {
            ChargingStationRegistry.get(serverLevel).remove(pos);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
