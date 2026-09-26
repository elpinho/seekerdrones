package com.elpinho.seekerdrones.drone;

import net.minecraft.util.RandomSource;

/**
 * Generates readable drone IDs like {@code K7F3-Q9MX} (DESIGN.md section 2.8).
 */
public final class DroneIds {
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private DroneIds() {}

    public static String generate(RandomSource random) {
        StringBuilder id = new StringBuilder(9);
        for (int i = 0; i < 8; i++) {
            if (i == 4) {
                id.append('-');
            }
            id.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return id.toString();
    }
}
