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
 */
public record RemoteStatusPayload(boolean open, RemoteLink link, RemoteReach reach, int distance, Optional<DroneStatusPayload> status)
        implements CustomPacketPayload {
    public static final Type<RemoteStatusPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SeekerDrones.MODID, "remote_status"));

    public static final StreamCodec<ByteBuf, RemoteStatusPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, RemoteStatusPayload::open,
            RemoteLink.STREAM_CODEC, RemoteStatusPayload::link,
            RemoteReach.STREAM_CODEC, RemoteStatusPayload::reach,
            ByteBufCodecs.VAR_INT, RemoteStatusPayload::distance,
            ByteBufCodecs.optional(DroneStatusPayload.STREAM_CODEC), RemoteStatusPayload::status,
            RemoteStatusPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
