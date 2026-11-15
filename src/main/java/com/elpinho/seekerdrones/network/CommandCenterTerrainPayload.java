package com.elpinho.seekerdrones.network;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.elpinho.seekerdrones.SeekerDrones;
import com.elpinho.seekerdrones.radar.ColorMap;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client: a slice of rows of a Command Center's terrain map in map colors (DESIGN.md section 7.10). The map
 * is too big for one packet at radius 256 (513 × 513 bytes), so it's sent in slices, in order, when the player opens
 * the screen and whenever the map is rebuilt. The client draws them as they arrive.
 *
 * @param firstRow the slice's first row of the map (north first)
 * @param colors   whole rows, one byte per column
 * @param last     whether this is the map's last slice
 */
public record CommandCenterTerrainPayload(BlockPos pos, int radius, int firstRow, byte[] colors, boolean last) implements CustomPacketPayload {
    public static final Type<CommandCenterTerrainPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SeekerDrones.MODID, "command_center_terrain"));
    /** The most bytes of map in one slice, well under the payload limit. */
    public static final int SLICE_BYTES = 32 * 1024;

    public static final StreamCodec<ByteBuf, CommandCenterTerrainPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, CommandCenterTerrainPayload::pos,
            ByteBufCodecs.VAR_INT, CommandCenterTerrainPayload::radius,
            ByteBufCodecs.VAR_INT, CommandCenterTerrainPayload::firstRow,
            ByteBufCodecs.BYTE_ARRAY, CommandCenterTerrainPayload::colors,
            ByteBufCodecs.BOOL, CommandCenterTerrainPayload::last,
            CommandCenterTerrainPayload::new);

    /** The map in slices of whole rows, each at most {@link #SLICE_BYTES} (at least one row). */
    public static List<CommandCenterTerrainPayload> slices(BlockPos pos, ColorMap map) {
        int size = map.size();
        int rowsPerSlice = Math.max(1, SLICE_BYTES / size);
        List<CommandCenterTerrainPayload> slices = new ArrayList<>();
        for (int row = 0; row < size; row += rowsPerSlice) {
            int end = Math.min(size, row + rowsPerSlice);
            slices.add(new CommandCenterTerrainPayload(pos, map.radius(), row, Arrays.copyOfRange(map.colors(), row * size, end * size), end == size));
        }
        return slices;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
