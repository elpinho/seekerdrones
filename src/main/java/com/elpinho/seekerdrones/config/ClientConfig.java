package com.elpinho.seekerdrones.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Per-player settings kept on this computer ({@code seekerdrones-client.toml}): conveniences that never affect
 * gameplay, like how the station screens sort their drone lists (DESIGN.md sections 7.9 and 7.10).
 */
public final class ClientConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.EnumValue<ListSort> UPLINK_LIST_SORT;
    public static final ModConfigSpec.EnumValue<ListSort> COMMAND_CENTER_LIST_SORT;

    public static final ModConfigSpec SPEC;

    static {
        BUILDER.comment("Station screens (DESIGN.md sections 7.9 and 7.10).").push("stations");
        UPLINK_LIST_SORT = BUILDER
                .comment("How the Satellite Uplink sorts its My drones list. STATE isn't offered there and falls back to distance.")
                .defineEnum("uplinkListSort", ListSort.DISTANCE_NEAREST);
        COMMAND_CENTER_LIST_SORT = BUILDER
                .comment("How the Command Center sorts its My drones list.")
                .defineEnum("commandCenterListSort", ListSort.DISTANCE_NEAREST);
        BUILDER.pop();
        SPEC = BUILDER.build();
    }

    /** Reads a value, falling back to its default while the config isn't loaded. */
    public static <T> T get(ModConfigSpec.ConfigValue<T> value) {
        return SPEC.isLoaded() ? value.get() : value.getDefault();
    }

    /** Sets and saves a value, if the config is loaded. */
    public static <T> void set(ModConfigSpec.ConfigValue<T> value, T newValue) {
        if (SPEC.isLoaded()) {
            value.set(newValue);
            value.save();
        }
    }

    private ClientConfig() {}
}
