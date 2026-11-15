package com.elpinho.seekerdrones.drone;

import java.util.Locale;
import java.util.function.IntFunction;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.ByIdMap;
import net.minecraft.world.phys.Vec3;

/**
 * A Command Center order a drone is carrying out (DESIGN.md section 7.10), shown in the Command Center's tooltip. New
 * values go at the end: the stream codec goes by ordinal. Saved by name.
 */
public enum DroneOrder {
    /** Flying to the spot (MOVING). */
    MOVE,
    /** Guarding the spot after a Move (GUARDING, or chasing from it). */
    GUARD,
    /** Rushing to the spot to detonate (STRIKING). */
    STRIKE;

    public static final IntFunction<DroneOrder> BY_ID = ByIdMap.continuous(Enum::ordinal, values(), ByIdMap.OutOfBoundsStrategy.ZERO);
    public static final StreamCodec<ByteBuf, DroneOrder> STREAM_CODEC = ByteBufCodecs.idMapper(BY_ID, Enum::ordinal);

    public String getSerializedName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The order with this NBT name, or null if unknown. */
    public static DroneOrder bySerializedName(String name) {
        for (DroneOrder order : values()) {
            if (order.getSerializedName().equals(name)) {
                return order;
            }
        }
        return null;
    }

    public String getTranslationKey() {
        return "drone_order.seekerdrones." + getSerializedName();
    }

    /**
     * The order a drone carries out right now.
     *
     * @param ticksLeft ticks before a Move or Strike is dropped, or of guarding left
     * @param spot      where it flies: above the spot for Move and guarding, the top block for Strike
     */
    public record Info(DroneOrder order, int ticksLeft, Vec3 spot) {}
}
