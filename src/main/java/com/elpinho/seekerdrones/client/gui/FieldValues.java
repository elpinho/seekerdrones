package com.elpinho.seekerdrones.client.gui;

import java.util.Locale;

import com.elpinho.seekerdrones.drone.DroneStats;

/**
 * Parsing and formatting for the number fields of the drone behavior editors (Programming Station and Drone Remote).
 * Speeds are stored in blocks/tick and shown in blocks/second.
 */
public final class FieldValues {
    private FieldValues() {}

    /** Shows a speed in blocks/second, to two decimals. */
    public static String formatSpeed(double blocksPerSecond) {
        return String.format(Locale.ROOT, "%.2f", blocksPerSecond);
    }

    public static double toSeconds(double blocksPerTick) {
        return blocksPerTick * DroneStats.TICKS_PER_SECOND;
    }

    public static double toTicks(double blocksPerSecond) {
        return blocksPerSecond / DroneStats.TICKS_PER_SECOND;
    }

    /** The trimmed text as an int, or {@code fallback} if it isn't one. */
    public static int parseInt(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** The trimmed text as a double, or {@code fallback} if it isn't one. */
    public static double parseDouble(String text, double fallback) {
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
