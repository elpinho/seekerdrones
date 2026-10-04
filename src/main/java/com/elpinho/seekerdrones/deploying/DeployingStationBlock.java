package com.elpinho.seekerdrones.deploying;

import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.elpinho.seekerdrones.registry.ModBlockEntities;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
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
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Drone Deploying Station (DESIGN.md section 7.3). Anyone may use it (section 6.2). With auto-deploy off, a redstone
 * pulse (rising edge) deploys the drone in the slot; {@link #TRIGGERED} remembers the last signal, like a dispenser.
 * {@link #SHAFT} shows the slot and launches on the model (section 7.6). It faces the player when placed; the facing is
 * only cosmetic.
 */
public class DeployingStationBlock extends BaseEntityBlock {
    public static final MapCodec<DeployingStationBlock> CODEC = simpleCodec(DeployingStationBlock::new);
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    public static final BooleanProperty TRIGGERED = BlockStateProperties.TRIGGERED;
    public static final EnumProperty<DeployingShaft> SHAFT = EnumProperty.create("shaft", DeployingShaft.class);
    /** How long (ticks) {@link DeployingShaft#LAUNCHING} shows after a deploy. Only visual, so not in the config. */
    public static final int LAUNCH_TICKS = 10;
    /** Block event sent when a drone is deployed; clients show a burst of cloud particles. */
    public static final int EVENT_DEPLOYED = 0;
    /** Told about each deploy on the client, so an open Deploying Station screen can play its launch. Set by the client. */
    private static Consumer<BlockPos> clientDeployListener = pos -> {};

    public DeployingStationBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(TRIGGERED, false)
                .setValue(SHAFT, DeployingShaft.EMPTY));
    }

    public static void setClientDeployListener(Consumer<BlockPos> listener) {
        clientDeployListener = listener;
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, TRIGGERED, SHAFT);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // Placing it next to an active signal isn't a pulse.
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite())
                .setValue(TRIGGERED, context.getLevel().hasNeighborSignal(context.getClickedPos()));
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    /** Scheduled by a deploy: the launch is over, so the shaft shows the slot again. */
    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (level.getBlockEntity(pos) instanceof DeployingStationBlockEntity station) {
            station.endLaunch(level);
        }
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock, BlockPos neighborPos, boolean movedByPiston) {
        boolean powered = level.hasNeighborSignal(pos);
        if (powered == state.getValue(TRIGGERED)) {
            return;
        }
        level.setBlock(pos, state.setValue(TRIGGERED, powered), Block.UPDATE_CLIENTS);
        if (powered && level.getBlockEntity(pos) instanceof DeployingStationBlockEntity station) {
            station.requestDeploy();
        }
    }

    /** A deploy: a small burst of clouds from the top, under the launched drone. */
    @Override
    protected boolean triggerEvent(BlockState state, Level level, BlockPos pos, int id, int param) {
        if (id != EVENT_DEPLOYED) {
            return super.triggerEvent(state, level, pos, id, param);
        }
        if (level.isClientSide()) {
            clientDeployListener.accept(pos);
            for (int i = 0; i < 8; i++) {
                double angle = i * Math.PI / 4;
                double speed = 0.05 + level.getRandom().nextDouble() * 0.03;
                level.addParticle(ParticleTypes.CLOUD, pos.getX() + 0.5, pos.getY() + 1.05, pos.getZ() + 0.5,
                        Math.cos(angle) * speed, 0.02, Math.sin(angle) * speed);
            }
        }
        // True on the server, so the event is sent to clients.
        return true;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new DeployingStationBlockEntity(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide() ? null
                : createTickerHelper(type, ModBlockEntities.DEPLOYING_STATION.get(), DeployingStationBlockEntity::serverTick);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (player instanceof ServerPlayer serverPlayer && level.getBlockEntity(pos) instanceof DeployingStationBlockEntity station) {
            serverPlayer.openMenu(station, buf -> buf.writeBlockPos(pos));
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof DeployingStationBlockEntity station) {
            station.getDrop().ifPresent(stack -> Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), stack));
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
