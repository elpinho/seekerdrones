package com.elpinho.seekerdrones.config;

/**
 * How a station screen's My drones list is sorted (DESIGN.md sections 7.9 and 7.10), cycled by the sort button and
 * saved in the client config. The Uplink only offers distance and name; the Command Center adds state. New values go
 * at the end; the config saves them by name.
 */
public enum ListSort {
    /** Nearest to the station first (the default). */
    DISTANCE_NEAREST("sort_distance", true),
    DISTANCE_FARTHEST("sort_distance", false),
    NAME_A_TO_Z("sort_name", true),
    NAME_Z_TO_A("sort_name", false),
    /** Busy drones first: striking, chasing, on an order, then patrolling, then idle (Command Center only). */
    STATE("sort_state", true);

    private final String icon;
    private final boolean down;

    ListSort(String icon, boolean down) {
        this.icon = icon;
        this.down = down;
    }

    /** The sort button's glyph, under {@code icon/}. */
    public String icon() {
        return icon;
    }

    /** Whether the button shows a down arrow (nearest, A first, busiest first) rather than an up arrow. */
    public boolean down() {
        return down;
    }

    public String getTranslationKey() {
        return "screen.seekerdrones.uplink.sort." + name().toLowerCase(java.util.Locale.ROOT);
    }
}
