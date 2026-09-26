package com.elpinho.seekerdrones.drone;

import java.util.function.IntFunction;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.ByIdMap;
import net.minecraft.util.StringRepresentable;

/**
 * Drone upgrade types (DESIGN.md section 4). Items and effects are added in M4.
 */
public enum UpgradeType implements StringRepresentable {
    PATROL("patrol"),
    SIGHT("sight"),
    EXPLOSIVE("explosive"),
    SIREN("siren"),
    TRANSMITTER("transmitter"),
    ENERGY("energy"),
    HEALTH("health"),
    PLAYER_SEEK("player_seek"),
    MULTI_TARGET("multi_target"),
    XRAY("xray");

    public static final StringRepresentable.EnumCodec<UpgradeType> CODEC = StringRepresentable.fromEnum(UpgradeType::values);
    private static final IntFunction<UpgradeType> BY_ID = ByIdMap.continuous(Enum::ordinal, values(), ByIdMap.OutOfBoundsStrategy.ZERO);
    public static final StreamCodec<ByteBuf, UpgradeType> STREAM_CODEC = ByteBufCodecs.idMapper(BY_ID, Enum::ordinal);

    private final String name;

    UpgradeType(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    public String getTranslationKey() {
        return "upgrade.seekerdrones." + name;
    }
}
