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
import net.minecraft.util.RandomSource;

/**
 * All drone state, stored as the {@code seekerdrones:drone_data} item component and in the drone entity's NBT
 * (DESIGN.md section 8.2). Max energy and max health are derived (see {@link DroneStats}), never stored.
 *
 * @param droneId the readable drone ID, or an empty string if not assigned yet (section 2.8)
 * @param groupId the Operator Group, empty for drones not built by a Factory
 * @param ownerId the player who first hand-deployed the drone (section 6.3). Only used while the drone has no group
 * @param ownerName the owner's name when ownership was set (for the tooltip, since clients can't resolve offline
 *        players), or an empty string if unknown
 */
public record DroneData(String droneId, Optional<UUID> groupId, Optional<UUID> ownerId, String ownerName, int energy, float health, Map<UpgradeType, Integer> upgrades,
        DroneConfig config) {
    public static final Codec<DroneData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.optionalFieldOf("drone_id", "").forGetter(DroneData::droneId),
            UUIDUtil.CODEC.optionalFieldOf("group_id").forGetter(DroneData::groupId),
            UUIDUtil.CODEC.optionalFieldOf("owner_id").forGetter(DroneData::ownerId),
            Codec.STRING.optionalFieldOf("owner_name", "").forGetter(DroneData::ownerName),
            Codec.INT.fieldOf("energy").forGetter(DroneData::energy),
            Codec.FLOAT.fieldOf("health").forGetter(DroneData::health),
            UpgradeType.COUNTS_CODEC.optionalFieldOf("upgrades", Map.of()).forGetter(DroneData::upgrades),
            DroneConfig.CODEC.fieldOf("config").forGetter(DroneData::config)
    ).apply(instance, DroneData::new));

    private static final StreamCodec<ByteBuf, Optional<UUID>> OPTIONAL_UUID = ByteBufCodecs.optional(UUIDUtil.STREAM_CODEC);
    private static final StreamCodec<ByteBuf, Map<UpgradeType, Integer>> UPGRADES_STREAM_CODEC =
            ByteBufCodecs.map(HashMap::new, UpgradeType.STREAM_CODEC, ByteBufCodecs.VAR_INT);

    /** Written by hand: {@link StreamCodec#composite} only goes up to six fields. */
    public static final StreamCodec<ByteBuf, DroneData> STREAM_CODEC = StreamCodec.of(
            (buf, data) -> {
                ByteBufCodecs.STRING_UTF8.encode(buf, data.droneId());
                OPTIONAL_UUID.encode(buf, data.groupId());
                OPTIONAL_UUID.encode(buf, data.ownerId());
                ByteBufCodecs.STRING_UTF8.encode(buf, data.ownerName());
                ByteBufCodecs.VAR_INT.encode(buf, data.energy());
                ByteBufCodecs.FLOAT.encode(buf, data.health());
                UPGRADES_STREAM_CODEC.encode(buf, data.upgrades());
                DroneConfig.STREAM_CODEC.encode(buf, data.config());
            },
            buf -> new DroneData(
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    OPTIONAL_UUID.decode(buf),
                    OPTIONAL_UUID.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.FLOAT.decode(buf),
                    UPGRADES_STREAM_CODEC.decode(buf),
                    DroneConfig.STREAM_CODEC.decode(buf)));

    public DroneData {
        upgrades = Map.copyOf(upgrades);
    }

    /** A fresh drone: no ID, group or owner yet, fully charged, full health, no upgrades, default config. */
    public static DroneData createNew() {
        DroneData empty = new DroneData("", Optional.empty(), Optional.empty(), "", 0, 0, Map.of(), DroneConfig.createDefault());
        return empty.withEnergy(DroneStats.maxEnergy(empty)).withHealth(DroneStats.maxHealth(empty));
    }

    public boolean hasDroneId() {
        return !droneId.isEmpty();
    }

    public int upgradeCount(UpgradeType type) {
        return upgrades.getOrDefault(type, 0);
    }

    public DroneData withDroneId(String droneId) {
        return new DroneData(droneId, groupId, ownerId, ownerName, energy, health, upgrades, config);
    }

    /** A drone without an ID gets one the first time it is deployed (section 2.8). */
    public DroneData withIdAssigned(RandomSource random) {
        return hasDroneId() ? this : withDroneId(DroneIds.generate(random));
    }

    public DroneData withGroupId(Optional<UUID> groupId) {
        return new DroneData(droneId, groupId, ownerId, ownerName, energy, health, upgrades, config);
    }

    /** Sets the owner without a known name (clears the stored name). */
    public DroneData withOwnerId(Optional<UUID> ownerId) {
        return new DroneData(droneId, groupId, ownerId, "", energy, health, upgrades, config);
    }

    public DroneData withOwner(UUID ownerId, String ownerName) {
        return new DroneData(droneId, groupId, Optional.of(ownerId), ownerName, energy, health, upgrades, config);
    }

    public DroneData withEnergy(int energy) {
        return new DroneData(droneId, groupId, ownerId, ownerName, energy, health, upgrades, config);
    }

    public DroneData withHealth(float health) {
        return new DroneData(droneId, groupId, ownerId, ownerName, energy, health, upgrades, config);
    }

    public DroneData withConfig(DroneConfig config) {
        return new DroneData(droneId, groupId, ownerId, ownerName, energy, health, upgrades, config);
    }

    /**
     * Sets how many upgrades of a type are installed, without checking the caps (section 4). Extra max energy and HP
     * arrive full, and anything above a lowered max is lost.
     */
    public DroneData withUpgradeCount(UpgradeType type, int count) {
        Map<UpgradeType, Integer> updated = new HashMap<>(upgrades);
        if (count > 0) {
            updated.put(type, count);
        } else {
            updated.remove(type);
        }
        DroneData result = new DroneData(droneId, groupId, ownerId, ownerName, energy, health, updated, config);
        long newEnergy = (long) energy + Math.max(0, DroneStats.maxEnergy(result) - DroneStats.maxEnergy(this));
        float newHealth = health + Math.max(0, DroneStats.maxHealth(result) - DroneStats.maxHealth(this));
        return result.withEnergy((int) Math.min(newEnergy, DroneStats.maxEnergy(result)))
                .withHealth(Math.min(newHealth, DroneStats.maxHealth(result)));
    }
}
