package com.elpinho.seekerdrones.network;

import java.util.List;

import com.elpinho.seekerdrones.SeekerDrones;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.DyeColor;

/**
 * Server to client: the viewer's own drones inside a Radar Station's area (DESIGN.md section 7.8), sent every
 * {@code radarStation.scanInterval} ticks to each player with its screen open. Every viewer gets their own list.
 */
public record RadarContactsPayload(BlockPos pos, List<Contact> contacts) implements CustomPacketPayload {
    public static final Type<RadarContactsPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SeekerDrones.MODID, "radar_contacts"));

    public static final StreamCodec<ByteBuf, RadarContactsPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, RadarContactsPayload::pos,
            Contact.STREAM_CODEC.apply(ByteBufCodecs.list()), RadarContactsPayload::contacts,
            RadarContactsPayload::new);

    /**
     * One drone on the radar.
     *
     * @param dx, dz the drone's position relative to the station's center, in blocks
     */
    public record Contact(String droneId, float dx, float dz, String label, DyeColor color) {
        public static final StreamCodec<ByteBuf, Contact> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Contact::droneId,
                ByteBufCodecs.FLOAT, Contact::dx,
                ByteBufCodecs.FLOAT, Contact::dz,
                ByteBufCodecs.STRING_UTF8, Contact::label,
                DyeColor.STREAM_CODEC, Contact::color,
                Contact::new);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
