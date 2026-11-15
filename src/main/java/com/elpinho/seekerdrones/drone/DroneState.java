package com.elpinho.seekerdrones.drone;

import java.util.Locale;
import java.util.function.IntFunction;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.ByIdMap;

/**
 * Drone AI states (DESIGN.md section 3.1), shown in the status GUI. New states go at the end: the stream codec goes by
 * ordinal.
 */
public enum DroneState {
    IDLE,
    PATROLLING,
    CHASING,
    FOLLOWING,
    RETURNING,
    CHARGING,
    /** Hovering where it is until resumed (Drone Remote, section 2.10). */
    HOLDING,
    /** Flying to the player who recalled it, or hovering there (Drone Remote, section 2.10). */
    RECALLED,
    /** Flown by hand by an operator through its FPV feed; the AI is paused (section 2.13). */
    PILOTED,
    /** Flying to the spot of a Command Center's Move order, passive (section 7.10). */
    MOVING,
    /** Guarding the spot of a Move order: hovering there between chases (section 7.10). */
    GUARDING,
    /** Rushing to the spot of a Command Center's Strike order to detonate there, passive (section 7.10). */
    STRIKING;

    public static final IntFunction<DroneState> BY_ID = ByIdMap.continuous(Enum::ordinal, values(), ByIdMap.OutOfBoundsStrategy.ZERO);
    public static final StreamCodec<ByteBuf, DroneState> STREAM_CODEC = ByteBufCodecs.idMapper(BY_ID, Enum::ordinal);

    /** The name used in NBT. */
    public String getSerializedName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The state with this NBT name, or {@link #IDLE} if unknown. */
    public static DroneState bySerializedName(String name) {
        for (DroneState state : values()) {
            if (state.getSerializedName().equals(name)) {
                return state;
            }
        }
        return IDLE;
    }

    /** An order's state that Resume (the remote) or Release (the Command Center) ends (sections 2.10 and 7.10). */
    public boolean isResumable() {
        return this == HOLDING || this == RECALLED || this == MOVING || this == GUARDING || this == STRIKING;
    }

    public String getTranslationKey() {
        return "drone_state.seekerdrones." + getSerializedName();
    }
}
