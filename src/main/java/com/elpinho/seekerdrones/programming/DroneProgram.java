package com.elpinho.seekerdrones.programming;

import java.util.HashMap;
import java.util.Map;

import com.elpinho.seekerdrones.drone.DroneConfig;
import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.DroneStats;
import com.elpinho.seekerdrones.drone.UpgradeType;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * What the Programming Station works toward (DESIGN.md section 7.2): upgrade counts and the drone's settings. In
 * Template mode this is the stored template. In Direct mode it is read from the drone in the slot.
 *
 * @param upgrades the upgrade counts, without zero entries
 */
public record DroneProgram(Map<UpgradeType, Integer> upgrades, DroneConfig config) {
    public static final Codec<DroneProgram> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.unboundedMap(UpgradeType.CODEC, Codec.INT).optionalFieldOf("upgrades", Map.of()).forGetter(DroneProgram::upgrades),
            DroneConfig.CODEC.fieldOf("config").forGetter(DroneProgram::config)
    ).apply(instance, DroneProgram::new));

    private static final StreamCodec<ByteBuf, Map<UpgradeType, Integer>> UPGRADES_STREAM_CODEC =
            ByteBufCodecs.map(HashMap::new, UpgradeType.STREAM_CODEC, ByteBufCodecs.VAR_INT);

    public static final StreamCodec<ByteBuf, DroneProgram> STREAM_CODEC = StreamCodec.composite(
            UPGRADES_STREAM_CODEC, DroneProgram::upgrades,
            DroneConfig.STREAM_CODEC, DroneProgram::config,
            DroneProgram::new);

    public DroneProgram {
        Map<UpgradeType, Integer> positive = new HashMap<>();
        upgrades.forEach((type, count) -> {
            if (count > 0) {
                positive.put(type, count);
            }
        });
        upgrades = Map.copyOf(positive);
    }

    /** No upgrades and a new drone's default settings. */
    public static DroneProgram createDefault() {
        return new DroneProgram(Map.of(), DroneConfig.createDefault());
    }

    /** The drone's own upgrades and settings. */
    public static DroneProgram of(DroneData data) {
        return new DroneProgram(data.upgrades(), data.config());
    }

    public int upgradeCount(UpgradeType type) {
        return upgrades.getOrDefault(type, 0);
    }

    public DroneProgram withUpgradeCount(UpgradeType type, int count) {
        Map<UpgradeType, Integer> updated = new HashMap<>(upgrades);
        updated.put(type, count);
        return new DroneProgram(updated, config);
    }

    public DroneProgram withConfig(DroneConfig config) {
        return new DroneProgram(upgrades, config);
    }

    /** Target slots for the programmed Multi-target count (section 2.7). */
    public int allowedTargetCount() {
        return DroneStats.allowedTargetCount(upgradeCount(UpgradeType.MULTI_TARGET));
    }

    /** Whether the drone's upgrades and settings are exactly the program's. */
    public boolean matches(DroneData data) {
        return upgrades.equals(DroneProgram.of(data).upgrades()) && config.equals(data.config());
    }
}
