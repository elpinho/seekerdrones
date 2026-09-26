package com.elpinho.seekerdrones.drone;

import java.util.function.IntFunction;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.ByIdMap;
import net.minecraft.util.StringRepresentable;

/**
 * One entry in a drone's target list (DESIGN.md section 2.6). Each entry has its own kind, so kinds can be mixed.
 *
 * @param value an entity type ID ({@code minecraft:zombie}), an entity tag ID without the {@code #}
 *              ({@code minecraft:raiders}) or a player name, depending on {@code kind}
 */
public record TargetEntry(Kind kind, String value) {
    public static final Codec<TargetEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Kind.CODEC.fieldOf("kind").forGetter(TargetEntry::kind),
            Codec.STRING.fieldOf("value").forGetter(TargetEntry::value)
    ).apply(instance, TargetEntry::new));

    public static final StreamCodec<ByteBuf, TargetEntry> STREAM_CODEC = StreamCodec.composite(
            Kind.STREAM_CODEC, TargetEntry::kind,
            ByteBufCodecs.STRING_UTF8, TargetEntry::value,
            TargetEntry::new);

    /** How the entry is shown to players: tags get a {@code #} prefix. */
    public String displayString() {
        return kind == Kind.TAG ? "#" + value : value;
    }

    public enum Kind implements StringRepresentable {
        ENTITY_TYPE("entity_type"),
        TAG("tag"),
        PLAYER_NAME("player_name");

        public static final StringRepresentable.EnumCodec<Kind> CODEC = StringRepresentable.fromEnum(Kind::values);
        private static final IntFunction<Kind> BY_ID = ByIdMap.continuous(Enum::ordinal, values(), ByIdMap.OutOfBoundsStrategy.ZERO);
        public static final StreamCodec<ByteBuf, Kind> STREAM_CODEC = ByteBufCodecs.idMapper(BY_ID, Enum::ordinal);

        private final String name;

        Kind(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }
}
