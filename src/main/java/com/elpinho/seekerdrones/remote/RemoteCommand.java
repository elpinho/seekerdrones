package com.elpinho.seekerdrones.remote;

import java.util.function.IntFunction;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.ByIdMap;

/** The commands of a Drone Remote (DESIGN.md section 2.10). */
public enum RemoteCommand {
    RECALL,
    HOLD,
    RESUME,
    /** Return to charge. */
    CHARGE,
    /** Patrol center here. */
    CENTER;

    private static final IntFunction<RemoteCommand> BY_ID = ByIdMap.continuous(Enum::ordinal, values(), ByIdMap.OutOfBoundsStrategy.ZERO);
    public static final StreamCodec<ByteBuf, RemoteCommand> STREAM_CODEC = ByteBufCodecs.idMapper(BY_ID, Enum::ordinal);
}
