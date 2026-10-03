package com.elpinho.seekerdrones.factory;

import java.util.Locale;

/** What the Drone Factory is doing, for its GUI's status line (DESIGN.md section 7.1). Synced to the open menu. */
public enum FactoryStatus {
    IDLE,
    BUILDING,
    /** A build is waiting for FE. */
    NO_ENERGY,
    /** The input slots match a recipe, but the tank doesn't hold its fluid. */
    MISSING_FLUID,
    /** A build is ready to run, but the output slot is taken. */
    OUTPUT_FULL;

    private static final FactoryStatus[] VALUES = values();

    public static FactoryStatus byId(int id) {
        return id >= 0 && id < VALUES.length ? VALUES[id] : IDLE;
    }

    public String getTranslationKey() {
        return "screen.seekerdrones.drone_factory.status." + name().toLowerCase(Locale.ROOT);
    }

    public String getHintKey() {
        return getTranslationKey() + ".hint";
    }
}
