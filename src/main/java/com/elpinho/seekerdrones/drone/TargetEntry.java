package com.elpinho.seekerdrones.drone;

import java.util.Locale;
import java.util.Optional;
import java.util.function.IntFunction;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.util.ByIdMap;
import net.minecraft.util.StringUtil;
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

    /** Whether both entries name the same target. Player names are compared ignoring case. */
    public boolean sameAs(TargetEntry other) {
        return kind == other.kind && (kind == Kind.PLAYER_NAME ? value.equalsIgnoreCase(other.value) : value.equals(other.value));
    }

    /** Whether the entry names a registered entity type, an existing entity tag or a valid player name. */
    public boolean isValid() {
        return switch (kind) {
            case ENTITY_TYPE -> {
                ResourceLocation id = ResourceLocation.tryParse(value);
                yield id != null && BuiltInRegistries.ENTITY_TYPE.containsKey(id);
            }
            case TAG -> {
                ResourceLocation id = ResourceLocation.tryParse(value);
                yield id != null && BuiltInRegistries.ENTITY_TYPE.getTag(TagKey.create(Registries.ENTITY_TYPE, id)).isPresent();
            }
            case PLAYER_NAME -> !value.isEmpty() && StringUtil.isValidPlayerName(value);
        };
    }

    /**
     * Parses text typed by a player (Programming Station, section 7.2). IDs without a namespace get {@code minecraft:},
     * and a leading {@code #} on a tag is dropped. Empty if the text doesn't name a valid target of that kind.
     */
    public static Optional<TargetEntry> parse(Kind kind, String text) {
        String trimmed = text.trim();
        if (kind != Kind.PLAYER_NAME) {
            if (kind == Kind.TAG && trimmed.startsWith("#")) {
                trimmed = trimmed.substring(1);
            }
            ResourceLocation id = ResourceLocation.tryParse(trimmed.toLowerCase(Locale.ROOT));
            if (id == null) {
                return Optional.empty();
            }
            trimmed = id.toString();
        }
        TargetEntry entry = new TargetEntry(kind, trimmed);
        return entry.isValid() ? Optional.of(entry) : Optional.empty();
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
