package com.elpinho.seekerdrones.drone;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntFunction;

import com.elpinho.seekerdrones.config.ServerConfig;
import com.mojang.serialization.Codec;

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
    PATROL("patrol", ServerConfig.UPGRADES_PATROL_MAX_COUNT, ServerConfig.PROGRAMMING_STATION_BASE_COST_PATROL,
            ServerConfig.UPGRADES_PATROL_ENERGY_FACTOR),
    SIGHT("sight", ServerConfig.UPGRADES_SIGHT_MAX_COUNT, ServerConfig.PROGRAMMING_STATION_BASE_COST_SIGHT,
            ServerConfig.UPGRADES_SIGHT_ENERGY_FACTOR),
    EXPLOSIVE("explosive", ServerConfig.UPGRADES_EXPLOSIVE_MAX_COUNT, ServerConfig.PROGRAMMING_STATION_BASE_COST_EXPLOSIVE,
            ServerConfig.UPGRADES_EXPLOSIVE_ENERGY_FACTOR),
    SIREN("siren", ServerConfig.UPGRADES_SIREN_MAX_COUNT, ServerConfig.PROGRAMMING_STATION_BASE_COST_SIREN,
            ServerConfig.UPGRADES_SIREN_ENERGY_FACTOR),
    TRANSMITTER("transmitter", ServerConfig.UPGRADES_TRANSMITTER_MAX_COUNT, ServerConfig.PROGRAMMING_STATION_BASE_COST_TRANSMITTER,
            ServerConfig.UPGRADES_TRANSMITTER_ENERGY_FACTOR),
    ENERGY("energy", ServerConfig.UPGRADES_ENERGY_MAX_COUNT, ServerConfig.PROGRAMMING_STATION_BASE_COST_ENERGY,
            ServerConfig.UPGRADES_ENERGY_ENERGY_FACTOR),
    HEALTH("health", ServerConfig.UPGRADES_HEALTH_MAX_COUNT, ServerConfig.PROGRAMMING_STATION_BASE_COST_HEALTH,
            ServerConfig.UPGRADES_HEALTH_ENERGY_FACTOR),
    PLAYER_SEEK("player_seek", ServerConfig.UPGRADES_PLAYER_SEEK_MAX_COUNT, ServerConfig.PROGRAMMING_STATION_BASE_COST_PLAYER_SEEK,
            ServerConfig.UPGRADES_PLAYER_SEEK_ENERGY_FACTOR);

    public static final StringRepresentable.EnumCodec<UpgradeType> CODEC = StringRepresentable.fromEnum(UpgradeType::values);
    private static final IntFunction<UpgradeType> BY_ID = ByIdMap.continuous(Enum::ordinal, values(), ByIdMap.OutOfBoundsStrategy.ZERO);
    public static final StreamCodec<ByteBuf, UpgradeType> STREAM_CODEC = ByteBufCodecs.idMapper(BY_ID, Enum::ordinal);

    /**
     * Upgrade counts by type name. Names of removed upgrade types (e.g. {@code xray}, {@code multi_target}) are dropped
     * on load instead of failing the whole map, so old drones and templates still load.
     */
    public static final Codec<Map<UpgradeType, Integer>> COUNTS_CODEC = Codec.unboundedMap(Codec.STRING, Codec.INT).xmap(
            counts -> {
                Map<UpgradeType, Integer> known = new HashMap<>();
                counts.forEach((name, count) -> {
                    UpgradeType type = CODEC.byName(name);
                    if (type != null) {
                        known.put(type, count);
                    }
                });
                return known;
            },
            counts -> {
                Map<String, Integer> named = new HashMap<>();
                counts.forEach((type, count) -> named.put(type.getSerializedName(), count));
                return named;
            });

    private final String name;
    private final ModConfigSpec.IntValue maxCount;
    private final ModConfigSpec.IntValue baseCost;
    private final ModConfigSpec.DoubleValue energyFactor;

    UpgradeType(String name, ModConfigSpec.IntValue maxCount, ModConfigSpec.IntValue baseCost, ModConfigSpec.DoubleValue energyFactor) {
        this.name = name;
        this.maxCount = maxCount;
        this.baseCost = baseCost;
        this.energyFactor = energyFactor;
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

    /** The drone's energy usage multiplier per upgrade of this type (section 5.1). */
    public double energyFactor() {
        return ServerConfig.get(energyFactor);
    }
}
