package com.elpinho.seekerdrones.drone;

import java.util.List;
import java.util.Optional;

import com.elpinho.seekerdrones.config.ServerConfig;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.item.DyeColor;

/**
 * A drone's base configuration (DESIGN.md section 2.6), set by the Programming Station.
 */
public record DroneConfig(List<TargetEntry> targets, int followDistance, Optional<GlobalPos> patrolCenter, Optional<Integer> patrolRadius,
        String label, DyeColor color) {
    public static final DyeColor DEFAULT_COLOR = DyeColor.BLUE;

    public static final Codec<DroneConfig> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            TargetEntry.CODEC.listOf().optionalFieldOf("targets", List.of()).forGetter(DroneConfig::targets),
            Codec.INT.fieldOf("follow_distance").forGetter(DroneConfig::followDistance),
            GlobalPos.CODEC.optionalFieldOf("patrol_center").forGetter(DroneConfig::patrolCenter),
            ExtraCodecs.POSITIVE_INT.optionalFieldOf("patrol_radius").forGetter(DroneConfig::patrolRadius),
            Codec.STRING.optionalFieldOf("label", "").forGetter(DroneConfig::label),
            DyeColor.CODEC.optionalFieldOf("color", DEFAULT_COLOR).forGetter(DroneConfig::color)
    ).apply(instance, DroneConfig::new));

    private static final StreamCodec<ByteBuf, List<TargetEntry>> TARGETS_STREAM_CODEC = TargetEntry.STREAM_CODEC.apply(ByteBufCodecs.list());
    private static final StreamCodec<ByteBuf, Optional<GlobalPos>> PATROL_CENTER_STREAM_CODEC = ByteBufCodecs.optional(GlobalPos.STREAM_CODEC);
    private static final StreamCodec<ByteBuf, Optional<Integer>> OPTIONAL_INT_STREAM_CODEC = ByteBufCodecs.optional(ByteBufCodecs.VAR_INT);

    /** Written by hand: {@link StreamCodec#composite} only goes up to six fields. */
    public static final StreamCodec<ByteBuf, DroneConfig> STREAM_CODEC = StreamCodec.of(
            (buf, config) -> {
                TARGETS_STREAM_CODEC.encode(buf, config.targets());
                ByteBufCodecs.VAR_INT.encode(buf, config.followDistance());
                PATROL_CENTER_STREAM_CODEC.encode(buf, config.patrolCenter());
                OPTIONAL_INT_STREAM_CODEC.encode(buf, config.patrolRadius());
                ByteBufCodecs.STRING_UTF8.encode(buf, config.label());
                DyeColor.STREAM_CODEC.encode(buf, config.color());
            },
            buf -> new DroneConfig(
                    TARGETS_STREAM_CODEC.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    PATROL_CENTER_STREAM_CODEC.decode(buf),
                    OPTIONAL_INT_STREAM_CODEC.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    DyeColor.STREAM_CODEC.decode(buf)));

    public DroneConfig {
        targets = List.copyOf(targets);
    }

    public static DroneConfig createDefault() {
        return new DroneConfig(List.of(), ServerConfig.get(ServerConfig.DRONE_DEFAULT_FOLLOW_DISTANCE), Optional.empty(), Optional.empty(),
                "", DEFAULT_COLOR);
    }

    public DroneConfig withTargets(List<TargetEntry> targets) {
        return new DroneConfig(targets, followDistance, patrolCenter, patrolRadius, label, color);
    }

    public DroneConfig withFollowDistance(int followDistance) {
        return new DroneConfig(targets, followDistance, patrolCenter, patrolRadius, label, color);
    }

    public DroneConfig withPatrolCenter(Optional<GlobalPos> patrolCenter) {
        return new DroneConfig(targets, followDistance, patrolCenter, patrolRadius, label, color);
    }

    /** The wanted patrol radius, or empty for the largest the Patrol upgrades allow (section 3.2). */
    public DroneConfig withPatrolRadius(Optional<Integer> patrolRadius) {
        return new DroneConfig(targets, followDistance, patrolCenter, patrolRadius, label, color);
    }

    public DroneConfig withLabel(String label) {
        return new DroneConfig(targets, followDistance, patrolCenter, patrolRadius, label, color);
    }

    public DroneConfig withColor(DyeColor color) {
        return new DroneConfig(targets, followDistance, patrolCenter, patrolRadius, label, color);
    }
}
