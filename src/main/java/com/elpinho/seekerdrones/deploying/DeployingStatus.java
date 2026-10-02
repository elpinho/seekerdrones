package com.elpinho.seekerdrones.deploying;

import java.util.function.IntFunction;

import net.minecraft.util.ByIdMap;

/** The status line of the Deploying Station GUI (DESIGN.md section 7.3). */
public enum DeployingStatus {
    /** No drone in the slot. */
    IDLE("idle"),
    /** Auto-deploy is off and the drone can be deployed: waiting for the button or a redstone pulse. */
    READY("ready"),
    NO_ENERGY("no_energy"),
    BLOCKED("blocked");

    public static final IntFunction<DeployingStatus> BY_ID = ByIdMap.continuous(Enum::ordinal, values(), ByIdMap.OutOfBoundsStrategy.ZERO);

    private final String name;

    DeployingStatus(String name) {
        this.name = name;
    }

    public String getTranslationKey() {
        return "screen.seekerdrones.deploying_station.status." + name;
    }

    /** What to do about it, shown after the status in the GUI's status strip. */
    public String getHintKey() {
        return "screen.seekerdrones.deploying_station.hint." + name;
    }
}
