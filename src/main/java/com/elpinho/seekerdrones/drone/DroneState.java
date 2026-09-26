package com.elpinho.seekerdrones.drone;

import java.util.Locale;
import java.util.function.IntFunction;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.ByIdMap;

/**
 * Drone AI states (DESIGN.md section 3.1), shown in the status GUI.
 */
public enum DroneState {
    IDLE,
    PATROLLING,
    CHASING,
    FOLLOWING,
    RETURNING,
    CHARGING;

    public static final IntFunction<DroneState> BY_ID = ByIdMap.continuous(Enum::ordinal, values(), ByIdMap.OutOfBoundsStrategy.ZERO);
    public static final StreamCodec<ByteBuf, DroneState> STREAM_CODEC = ByteBufCodecs.idMapper(BY_ID, Enum::ordinal);

    public String getTranslationKey() {
        return "drone_state.seekerdrones." + name().toLowerCase(Locale.ROOT);
    }
}
