package com.elpinho.seekerdrones.deploying;

import net.minecraft.util.StringRepresentable;

/**
 * What the Deploying Station's launch shaft shows (DESIGN.md section 7.6), for the block model only: the side chevrons
 * are dark, the lower one lit while a drone is in the slot, or both sweeping upward for a moment after a deploy.
 */
public enum DeployingShaft implements StringRepresentable {
    EMPTY("empty"),
    LOADED("loaded"),
    LAUNCHING("launching");

    private final String name;

    DeployingShaft(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }
}
