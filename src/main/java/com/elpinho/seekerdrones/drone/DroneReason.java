package com.elpinho.seekerdrones.drone;

import java.util.Locale;
import java.util.function.IntFunction;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.ByIdMap;

/**
 * Why a drone is in its current state (DESIGN.md section 2.4), shown as a short line under the state on the status
 * screen, the Drone Remote screen and in Jade. Worked out on the server only when one of them asks.
 */
public enum DroneReason {
    /** Nothing to explain. */
    NONE,
    /** The drone has no target entries. */
    NO_TARGET_ENTRIES,
    /** It has target entries, but none it can use (Player Seek missing, or blocked by the server's filter). */
    NO_USABLE_TARGETS,
    /** A teammate's drone already claimed the only target in range. */
    TARGET_CLAIMED,
    /** The target is out of sight. The argument is the seconds left before the drone gives it up. */
    TARGET_OUT_OF_SIGHT,
    /** No usable Charging Station in range, so the drone drops as an item at 0 energy. */
    NO_STATION,
    /** Waiting next to a Charging Station another drone holds. */
    WAITING_FOR_STATION,
    /** A station was skipped because the drone couldn't reach it. */
    STATION_UNREACHABLE,
    /** Docked at a Charging Station that has no FE. */
    STATION_NO_POWER,
    /** The drone's Operator Group doesn't exist, so it has no operators. */
    UNKNOWN_GROUP;

    public static final IntFunction<DroneReason> BY_ID = ByIdMap.continuous(Enum::ordinal, values(), ByIdMap.OutOfBoundsStrategy.ZERO);
    public static final StreamCodec<ByteBuf, DroneReason> STREAM_CODEC = ByteBufCodecs.idMapper(BY_ID, Enum::ordinal);

    public String getTranslationKey() {
        return "drone_reason.seekerdrones." + name().toLowerCase(Locale.ROOT);
    }

    /**
     * A reason with its argument.
     *
     * @param seconds only for {@link #TARGET_OUT_OF_SIGHT}: the seconds left on the lost-sight countdown
     */
    public record Info(DroneReason reason, int seconds) {
        public static final Info NONE = new Info(DroneReason.NONE, 0);

        public static final StreamCodec<ByteBuf, Info> STREAM_CODEC = StreamCodec.composite(
                DroneReason.STREAM_CODEC, Info::reason,
                ByteBufCodecs.VAR_INT, Info::seconds,
                Info::new);

        public static Info of(DroneReason reason) {
            return reason == DroneReason.NONE ? NONE : new Info(reason, 0);
        }

        public boolean isPresent() {
            return reason != DroneReason.NONE;
        }

        public Component text() {
            return Component.translatable(reason.getTranslationKey(), seconds);
        }
    }
}
