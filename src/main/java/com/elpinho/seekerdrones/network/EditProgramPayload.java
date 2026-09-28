package com.elpinho.seekerdrones.network;

import java.util.Optional;
import java.util.function.IntFunction;

import com.elpinho.seekerdrones.SeekerDrones;
import com.elpinho.seekerdrones.drone.DroneConfig;
import com.elpinho.seekerdrones.drone.UpgradeType;
import com.elpinho.seekerdrones.programming.ProgrammingMode;
import com.elpinho.seekerdrones.programming.ProgrammingStationBlockEntity;
import com.elpinho.seekerdrones.programming.ProgrammingStationMenu;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ByIdMap;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client to server: an edit in the Programming Station GUI (DESIGN.md section 7.2). The server checks that the sender
 * has this station's menu open, and the station checks the edit itself.
 *
 * @param upgrade the upgrade type, for the upgrade actions
 * @param value  the new mode ordinal or programmed count, for {@link Action#SET_MODE} and {@link Action#SET_COUNT}
 * @param config the new settings, for {@link Action#SET_CONFIG}
 */
public record EditProgramPayload(int containerId, Action action, UpgradeType upgrade, int value, Optional<DroneConfig> config)
        implements CustomPacketPayload {
    public static final Type<EditProgramPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SeekerDrones.MODID, "edit_program"));

    public static final StreamCodec<ByteBuf, EditProgramPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, EditProgramPayload::containerId,
            Action.STREAM_CODEC, EditProgramPayload::action,
            UpgradeType.STREAM_CODEC, EditProgramPayload::upgrade,
            ByteBufCodecs.VAR_INT, EditProgramPayload::value,
            ByteBufCodecs.optional(DroneConfig.STREAM_CODEC), EditProgramPayload::config,
            EditProgramPayload::new);

    public static EditProgramPayload setMode(int containerId, ProgrammingMode mode) {
        return new EditProgramPayload(containerId, Action.SET_MODE, UpgradeType.PATROL, mode.ordinal(), Optional.empty());
    }

    public static EditProgramPayload install(int containerId, UpgradeType type) {
        return new EditProgramPayload(containerId, Action.INSTALL, type, 0, Optional.empty());
    }

    public static EditProgramPayload remove(int containerId, UpgradeType type) {
        return new EditProgramPayload(containerId, Action.REMOVE, type, 0, Optional.empty());
    }

    public static EditProgramPayload setCount(int containerId, UpgradeType type, int count) {
        return new EditProgramPayload(containerId, Action.SET_COUNT, type, count, Optional.empty());
    }

    public static EditProgramPayload setConfig(int containerId, DroneConfig config) {
        return new EditProgramPayload(containerId, Action.SET_CONFIG, UpgradeType.PATROL, 0, Optional.of(config));
    }

    public static EditProgramPayload copyFromDrone(int containerId) {
        return new EditProgramPayload(containerId, Action.COPY_FROM_DRONE, UpgradeType.PATROL, 0, Optional.empty());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(EditProgramPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !(player.containerMenu instanceof ProgrammingStationMenu menu)
                || menu.containerId != payload.containerId() || !menu.stillValid(player)) {
            return;
        }
        ProgrammingStationBlockEntity station = menu.getStation();
        if (station == null) {
            return;
        }
        switch (payload.action()) {
            case SET_MODE -> station.setMode(ProgrammingMode.BY_ID.apply(payload.value()));
            case INSTALL -> station.requestInstall(payload.upgrade());
            case REMOVE -> station.removeUpgrade(payload.upgrade(), player);
            case SET_COUNT -> station.setProgramCount(payload.upgrade(), payload.value());
            case SET_CONFIG -> payload.config().ifPresent(station::setConfig);
            case COPY_FROM_DRONE -> station.copyFromDrone();
        }
    }

    public enum Action {
        SET_MODE,
        /** Direct mode: install one upgrade of the type from the input. */
        INSTALL,
        /** Manual removal of one upgrade of the type, refunded to the player. */
        REMOVE,
        /** Template mode: set the programmed count of the type. */
        SET_COUNT,
        SET_CONFIG,
        COPY_FROM_DRONE;

        private static final IntFunction<Action> BY_ID = ByIdMap.continuous(Enum::ordinal, values(), ByIdMap.OutOfBoundsStrategy.ZERO);
        public static final StreamCodec<ByteBuf, Action> STREAM_CODEC = ByteBufCodecs.idMapper(BY_ID, Enum::ordinal);
    }
}
