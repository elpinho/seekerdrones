package com.elpinho.seekerdrones.drone;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * All drone state, stored as the {@code seekerdrones:drone_data} item component and in the drone entity's NBT
 * (DESIGN.md section 8.2). Max energy and max health are derived (see {@link DroneStats}), never stored.
 *
 * @param droneId the readable drone ID, or an empty string if not assigned yet (section 2.8)
 * @param groupId the Operator Group, empty for drones not built by a Factory
 */
public record DroneData(String droneId, Optional<UUID> groupId, int energy, float health, Map<UpgradeType, Integer> upgrades, DroneConfig config) {
    public static final Codec<DroneData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.optionalFieldOf("drone_id", "").forGetter(DroneData::droneId),
            UUIDUtil.CODEC.optionalFieldOf("group_id").forGetter(DroneData::groupId),
            Codec.INT.fieldOf("energy").forGetter(DroneData::energy),
            Codec.FLOAT.fieldOf("health").forGetter(DroneData::health),
            Codec.unboundedMap(UpgradeType.CODEC, Codec.INT).optionalFieldOf("upgrades", Map.of()).forGetter(DroneData::upgrades),
            DroneConfig.CODEC.fieldOf("config").forGetter(DroneData::config)
    ).apply(instance, DroneData::new));

    public static final StreamCodec<ByteBuf, DroneData> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, DroneData::droneId,
            ByteBufCodecs.optional(UUIDUtil.STREAM_CODEC), DroneData::groupId,
            ByteBufCodecs.VAR_INT, DroneData::energy,
            ByteBufCodecs.FLOAT, DroneData::health,
            ByteBufCodecs.<ByteBuf, UpgradeType, Integer, Map<UpgradeType, Integer>>map(HashMap::new, UpgradeType.STREAM_CODEC, ByteBufCodecs.VAR_INT), DroneData::upgrades,
            DroneConfig.STREAM_CODEC, DroneData::config,
            DroneData::new);

    public DroneData {
        upgrades = Map.copyOf(upgrades);
    }

    /** A fresh drone: no ID or group yet, fully charged, full health, no upgrades, default config. */
    public static DroneData createNew() {
        DroneData empty = new DroneData("", Optional.empty(), 0, 0, Map.of(), DroneConfig.createDefault());
        return empty.withEnergy(DroneStats.maxEnergy(empty)).withHealth(DroneStats.maxHealth(empty));
    }

    public boolean hasDroneId() {
        return !droneId.isEmpty();
    }

    public int upgradeCount(UpgradeType type) {
        return upgrades.getOrDefault(type, 0);
    }

    public DroneData withDroneId(String droneId) {
        return new DroneData(droneId, groupId, energy, health, upgrades, config);
    }

    public DroneData withGroupId(Optional<UUID> groupId) {
        return new DroneData(droneId, groupId, energy, health, upgrades, config);
    }

    public DroneData withEnergy(int energy) {
        return new DroneData(droneId, groupId, energy, health, upgrades, config);
    }

    public DroneData withHealth(float health) {
        return new DroneData(droneId, groupId, energy, health, upgrades, config);
    }

    public DroneData withConfig(DroneConfig config) {
        return new DroneData(droneId, groupId, energy, health, upgrades, config);
    }
}
