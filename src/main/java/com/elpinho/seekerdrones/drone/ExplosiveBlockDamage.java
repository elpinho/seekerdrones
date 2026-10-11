package com.elpinho.seekerdrones.drone;

import net.minecraft.world.level.Level;

/** Whether an Explosive drone's explosion breaks blocks ({@code upgrades.explosive.blockDamage}, DESIGN.md section 3.1). */
public enum ExplosiveBlockDamage {
    NEVER(Level.ExplosionInteraction.NONE),
    /** Only while the {@code mobGriefing} game rule is on, like a creeper, with the mob explosion drop decay. */
    MOB_GRIEFING(Level.ExplosionInteraction.MOB),
    /** Always, like TNT, with the TNT explosion drop decay. */
    ALWAYS(Level.ExplosionInteraction.TNT);

    private final Level.ExplosionInteraction interaction;

    ExplosiveBlockDamage(Level.ExplosionInteraction interaction) {
        this.interaction = interaction;
    }

    public Level.ExplosionInteraction interaction() {
        return interaction;
    }
}
