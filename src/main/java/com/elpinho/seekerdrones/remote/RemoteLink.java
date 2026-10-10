package com.elpinho.seekerdrones.remote;

import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.DroneItem;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.DyeColor;

/**
 * The drone a Drone Remote is linked to, stored as the {@code seekerdrones:remote_link} item component (DESIGN.md
 * section 2.10). Only the ID is used to find the drone. The label and color are what the drone had when it was linked,
 * for the tooltip and the item's tint.
 */
public record RemoteLink(String droneId, String label, DyeColor color) {
    public static final Codec<RemoteLink> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("drone_id").forGetter(RemoteLink::droneId),
            Codec.STRING.optionalFieldOf("label", "").forGetter(RemoteLink::label),
            DyeColor.CODEC.optionalFieldOf("color", DyeColor.BLUE).forGetter(RemoteLink::color)
    ).apply(instance, RemoteLink::new));

    public static final StreamCodec<ByteBuf, RemoteLink> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, RemoteLink::droneId,
            ByteBufCodecs.STRING_UTF8, RemoteLink::label,
            DyeColor.STREAM_CODEC, RemoteLink::color,
            RemoteLink::new);

    public static RemoteLink of(DroneData data) {
        return new RemoteLink(data.droneId(), data.config().label(), data.config().color());
    }

    /** "label - ID" in the drone's color, like the drone item's first tooltip line. */
    public MutableComponent identity() {
        return DroneItem.identity(droneId, label, color);
    }
}
