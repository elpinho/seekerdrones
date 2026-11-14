package com.elpinho.seekerdrones.feed;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * What a pilot's movement keys ask a piloted drone to do (DESIGN.md section 2.13). Each axis is -1, 0 or 1.
 *
 * @param forward  W (1) or S (-1)
 * @param left     A (1) or D (-1), like vanilla's left impulse
 * @param vertical Jump (1) or Sneak (-1)
 * @param boost    Sprint held
 * @param yaw      the camera gimbal's yaw (degrees), which W/A/S/D are relative to
 */
public record PilotInput(byte forward, byte left, byte vertical, boolean boost, float yaw) {
    public static final PilotInput NONE = new PilotInput((byte) 0, (byte) 0, (byte) 0, false, 0);

    public static final StreamCodec<ByteBuf, PilotInput> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BYTE, PilotInput::forward,
            ByteBufCodecs.BYTE, PilotInput::left,
            ByteBufCodecs.BYTE, PilotInput::vertical,
            ByteBufCodecs.BOOL, PilotInput::boost,
            ByteBufCodecs.FLOAT, PilotInput::yaw,
            PilotInput::new);

    /** Clamps every axis to -1..1, for input that came over the network. */
    public PilotInput sanitized() {
        return new PilotInput(sign(forward), sign(left), sign(vertical), boost, Float.isFinite(yaw) ? Mth.wrapDegrees(yaw) : 0);
    }

    private static byte sign(byte value) {
        return (byte) Integer.signum(value);
    }

    public boolean isMoving() {
        return forward != 0 || left != 0 || vertical != 0;
    }

    /** The unit direction the keys ask for: horizontal relative to the yaw only, so looking down doesn't dive. */
    public Vec3 direction() {
        if (!isMoving()) {
            return Vec3.ZERO;
        }
        float radians = yaw * Mth.DEG_TO_RAD;
        double sin = Mth.sin(radians);
        double cos = Mth.cos(radians);
        // Vanilla's Entity.moveRelative: forward is (-sin, cos), left is (cos, sin).
        double x = left * cos - forward * sin;
        double z = forward * cos + left * sin;
        return new Vec3(x, vertical, z).normalize();
    }
}
