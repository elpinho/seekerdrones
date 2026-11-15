package com.elpinho.seekerdrones.network;

import java.util.Optional;

import com.elpinho.seekerdrones.SeekerDrones;
import com.elpinho.seekerdrones.remote.RemoteLink;
import com.elpinho.seekerdrones.remote.RemoteReach;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client: what a Drone Remote's screen shows (DESIGN.md section 2.10).
 *
 * @param open     true to open the screen, false to refresh one that is already open
 * @param link     the remote's link, for the header while the drone can't be reached
 * @param distance how far the drone is from the player in blocks, only meaningful when it is reachable or out of range
 * @param status   the drone's values, present only when the drone is {@link RemoteReach#OK reachable}
 * @param pilot    the name of the player piloting the drone (section 2.13), empty if nobody does
 * @param uplink   opened through a Satellite Uplink (section 7.9) rather than a remote in hand; the distance is then from
 *                 the Uplink
 */
public record RemoteStatusPayload(boolean open, RemoteLink link, RemoteReach reach, int distance, Optional<DroneStatusPayload> status, String pilot,
        boolean uplink) implements CustomPacketPayload {
    public static final Type<RemoteStatusPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SeekerDrones.MODID, "remote_status"));

    public static final StreamCodec<ByteBuf, RemoteStatusPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                ByteBufCodecs.BOOL.encode(buf, payload.open());
                RemoteLink.STREAM_CODEC.encode(buf, payload.link());
                RemoteReach.STREAM_CODEC.encode(buf, payload.reach());
                ByteBufCodecs.VAR_INT.encode(buf, payload.distance());
                ByteBufCodecs.optional(DroneStatusPayload.STREAM_CODEC).encode(buf, payload.status());
                ByteBufCodecs.STRING_UTF8.encode(buf, payload.pilot());
                ByteBufCodecs.BOOL.encode(buf, payload.uplink());
            },
            buf -> new RemoteStatusPayload(ByteBufCodecs.BOOL.decode(buf), RemoteLink.STREAM_CODEC.decode(buf),
                    RemoteReach.STREAM_CODEC.decode(buf), ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.optional(DroneStatusPayload.STREAM_CODEC).decode(buf), ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.BOOL.decode(buf)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
