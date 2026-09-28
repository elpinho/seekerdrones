package com.elpinho.seekerdrones.network;

import com.elpinho.seekerdrones.SeekerDrones;
import com.elpinho.seekerdrones.programming.DroneProgram;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client: the template of the Programming Station whose menu the player has open (DESIGN.md section 7.2),
 * sent when the menu opens and whenever the template changes.
 */
public record ProgramTemplatePayload(int containerId, DroneProgram template) implements CustomPacketPayload {
    public static final Type<ProgramTemplatePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SeekerDrones.MODID, "program_template"));

    public static final StreamCodec<ByteBuf, ProgramTemplatePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ProgramTemplatePayload::containerId,
            DroneProgram.STREAM_CODEC, ProgramTemplatePayload::template,
            ProgramTemplatePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
