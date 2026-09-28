package com.elpinho.seekerdrones.programming;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * The mode and template a broken Programming Station keeps on its item, as the
 * {@code seekerdrones:programming_station} component (DESIGN.md section 7.2).
 */
public record ProgrammingStationSettings(ProgrammingMode mode, DroneProgram template) {
    public static final Codec<ProgrammingStationSettings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ProgrammingMode.CODEC.optionalFieldOf("mode", ProgrammingMode.DIRECT).forGetter(ProgrammingStationSettings::mode),
            DroneProgram.CODEC.fieldOf("template").forGetter(ProgrammingStationSettings::template)
    ).apply(instance, ProgrammingStationSettings::new));

    public static final StreamCodec<ByteBuf, ProgrammingStationSettings> STREAM_CODEC = StreamCodec.composite(
            ProgrammingMode.STREAM_CODEC, ProgrammingStationSettings::mode,
            DroneProgram.STREAM_CODEC, ProgrammingStationSettings::template,
            ProgrammingStationSettings::new);
}
