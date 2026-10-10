package com.elpinho.seekerdrones.drone;

import java.util.function.IntFunction;

import net.minecraft.util.ByIdMap;

/**
 * Which low-power beep loop a drone plays (DESIGN.md section 2.9). Synced to clients.
 */
public enum LowPowerLevel {
    NONE,
    /** RETURNING to a station, including while waiting by a busy one. */
    RETURNING,
    /** Undocked, with energy for less than {@code sounds.lowPower.criticalSeconds} of hovering. */
    CRITICAL;

    public static final IntFunction<LowPowerLevel> BY_ID = ByIdMap.continuous(Enum::ordinal, values(), ByIdMap.OutOfBoundsStrategy.ZERO);
}
