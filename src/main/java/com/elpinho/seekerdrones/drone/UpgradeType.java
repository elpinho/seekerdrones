package com.elpinho.seekerdrones.drone;

import java.util.function.IntFunction;

import com.elpinho.seekerdrones.config.ServerConfig;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.ByIdMap;
import net.minecraft.util.StringRepresentable;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Drone upgrade types (DESIGN.md section 4).
 */
public enum UpgradeType implements StringRepresentable {
    PATROL("patrol", ServerConfig.UPGRADES_PATROL_MAX_COUNT, ServerConfig.PROGRAMMING_STATION_BASE_COST_PATROL),
    SIGHT("sight", ServerConfig.UPGRADES_SIGHT_MAX_COUNT, ServerConfig.PROGRAMMING_STATION_BASE_COST_SIGHT),
    EXPLOSIVE("explosive", ServerConfig.UPGRADES_EXPLOSIVE_MAX_COUNT, ServerConfig.PROGRAMMING_STATION_BASE_COST_EXPLOSIVE),
    SIREN("siren", ServerConfig.UPGRADES_SIREN_MAX_COUNT, ServerConfig.PROGRAMMING_STATION_BASE_COST_SIREN),
    TRANSMITTER("transmitter", ServerConfig.UPGRADES_TRANSMITTER_MAX_COUNT, ServerConfig.PROGRAMMING_STATION_BASE_COST_TRANSMITTER),
    ENERGY("energy", ServerConfig.UPGRADES_ENERGY_MAX_COUNT, ServerConfig.PROGRAMMING_STATION_BASE_COST_ENERGY),
    HEALTH("health", ServerConfig.UPGRADES_HEALTH_MAX_COUNT, ServerConfig.PROGRAMMING_STATION_BASE_COST_HEALTH),
    PLAYER_SEEK("player_seek", ServerConfig.UPGRADES_PLAYER_SEEK_MAX_COUNT, ServerConfig.PROGRAMMING_STATION_BASE_COST_PLAYER_SEEK),
    MULTI_TARGET("multi_target", ServerConfig.UPGRADES_MULTI_TARGET_MAX_COUNT, ServerConfig.PROGRAMMING_STATION_BASE_COST_MULTI_TARGET),
    XRAY("xray", ServerConfig.UPGRADES_XRAY_MAX_COUNT, ServerConfig.PROGRAMMING_STATION_BASE_COST_XRAY);

    public static final StringRepresentable.EnumCodec<UpgradeType> CODEC = StringRepresentable.fromEnum(UpgradeType::values);
    private static final IntFunction<UpgradeType> BY_ID = ByIdMap.continuous(Enum::ordinal, values(), ByIdMap.OutOfBoundsStrategy.ZERO);
    public static final StreamCodec<ByteBuf, UpgradeType> STREAM_CODEC = ByteBufCodecs.idMapper(BY_ID, Enum::ordinal);

    private final String name;
    private final ModConfigSpec.IntValue maxCount;
    private final ModConfigSpec.IntValue baseCost;

    UpgradeType(String name, ModConfigSpec.IntValue maxCount, ModConfigSpec.IntValue baseCost) {
        this.name = name;
        this.maxCount = maxCount;
        this.baseCost = baseCost;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    public String getTranslationKey() {
        return "upgrade.seekerdrones." + name;
    }

    /** The per-type cap (section 4). */
    public int maxCount() {
        return ServerConfig.get(maxCount);
    }

    /** The Programming Station's base FE cost to install one, multiplied by the install index (section 7.2). */
    public int baseCost() {
        return ServerConfig.get(baseCost);
    }
}
