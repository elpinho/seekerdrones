package com.elpinho.seekerdrones.radar;

/**
 * A radar machine's terrain map (DESIGN.md sections 7.8 and 7.9): a square of {@code (2 * radius + 1)²} columns
 * centered on the machine, north (negative Z) first, one row per Z.
 */
public interface RadarMap {
    int radius();

    static int size(int radius) {
        return radius * 2 + 1;
    }

    default int size() {
        return size(radius());
    }

    default int index(int column, int row) {
        return row * size() + column;
    }
}
