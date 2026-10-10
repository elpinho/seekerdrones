package com.elpinho.seekerdrones.remote;

import java.util.function.IntFunction;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.ByIdMap;

/**
 * Whether a player can reach a Drone Remote's linked drone right now (DESIGN.md section 2.10). Anything but
 * {@link #OK} disables the remote's controls.
 */
public enum RemoteReach {
    OK,
    /** Not deployed, or not in a loaded chunk. */
    NOT_DEPLOYED,
    OTHER_DIMENSION,
    /** The player isn't an operator of the drone (any more). */
    DENIED,
    OUT_OF_RANGE;

    private static final IntFunction<RemoteReach> BY_ID = ByIdMap.continuous(Enum::ordinal, values(), ByIdMap.OutOfBoundsStrategy.ZERO);
    public static final StreamCodec<ByteBuf, RemoteReach> STREAM_CODEC = ByteBufCodecs.idMapper(BY_ID, Enum::ordinal);

    public String getTranslationKey() {
        return "screen.seekerdrones.drone_remote.reach." + name().toLowerCase(java.util.Locale.ROOT);
    }
}
