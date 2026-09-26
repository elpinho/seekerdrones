package com.elpinho.seekerdrones.drone;

import java.util.List;
import java.util.Optional;

import com.elpinho.seekerdrones.config.ServerConfig;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.DyeColor;

/**
 * A drone's base configuration (DESIGN.md section 2.6), set by the Programming Station.
 */
public record DroneConfig(List<TargetEntry> targets, int followDistance, Optional<BlockPos> patrolCenter, String label, DyeColor color) {
    public static final DyeColor DEFAULT_COLOR = DyeColor.BLUE;

    public static final Codec<DroneConfig> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            TargetEntry.CODEC.listOf().optionalFieldOf("targets", List.of()).forGetter(DroneConfig::targets),
            Codec.INT.fieldOf("follow_distance").forGetter(DroneConfig::followDistance),
            BlockPos.CODEC.optionalFieldOf("patrol_center").forGetter(DroneConfig::patrolCenter),
            Codec.STRING.optionalFieldOf("label", "").forGetter(DroneConfig::label),
            DyeColor.CODEC.optionalFieldOf("color", DEFAULT_COLOR).forGetter(DroneConfig::color)
    ).apply(instance, DroneConfig::new));

    public static final StreamCodec<ByteBuf, DroneConfig> STREAM_CODEC = StreamCodec.composite(
            TargetEntry.STREAM_CODEC.apply(ByteBufCodecs.list()), DroneConfig::targets,
            ByteBufCodecs.VAR_INT, DroneConfig::followDistance,
            ByteBufCodecs.optional(BlockPos.STREAM_CODEC), DroneConfig::patrolCenter,
            ByteBufCodecs.STRING_UTF8, DroneConfig::label,
            DyeColor.STREAM_CODEC, DroneConfig::color,
            DroneConfig::new);

    public DroneConfig {
        targets = List.copyOf(targets);
    }

    public static DroneConfig createDefault() {
        return new DroneConfig(List.of(), ServerConfig.get(ServerConfig.DRONE_DEFAULT_FOLLOW_DISTANCE), Optional.empty(), "", DEFAULT_COLOR);
    }

    public DroneConfig withTargets(List<TargetEntry> targets) {
        return new DroneConfig(targets, followDistance, patrolCenter, label, color);
    }

    public DroneConfig withFollowDistance(int followDistance) {
        return new DroneConfig(targets, followDistance, patrolCenter, label, color);
    }
}
