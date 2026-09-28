package com.elpinho.seekerdrones.programming;

import java.util.function.IntFunction;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.ByIdMap;
import net.minecraft.util.StringRepresentable;

/** The Programming Station's modes (DESIGN.md section 7.2). */
public enum ProgrammingMode implements StringRepresentable {
    /** A player edits the drone in the slot by hand. */
    DIRECT("direct"),
    /** Every drone in the slot is brought to the stored template. */
    TEMPLATE("template");

    public static final StringRepresentable.EnumCodec<ProgrammingMode> CODEC = StringRepresentable.fromEnum(ProgrammingMode::values);
    public static final IntFunction<ProgrammingMode> BY_ID = ByIdMap.continuous(Enum::ordinal, values(), ByIdMap.OutOfBoundsStrategy.ZERO);
    public static final StreamCodec<ByteBuf, ProgrammingMode> STREAM_CODEC = ByteBufCodecs.idMapper(BY_ID, Enum::ordinal);

    private final String name;

    ProgrammingMode(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    public String getTranslationKey() {
        return "screen.seekerdrones.programming_station.mode." + name;
    }
}
