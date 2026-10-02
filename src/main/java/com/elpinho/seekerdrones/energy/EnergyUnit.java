package com.elpinho.seekerdrones.energy;

import com.mojang.serialization.Codec;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.ByIdMap;
import net.minecraft.util.StringRepresentable;

/**
 * A player's choice of unit for showing energy (DESIGN.md section 5.4). Energy is always stored and moved as FE; the
 * unit only changes how numbers are displayed.
 */
public enum EnergyUnit implements StringRepresentable {
    /** Joules when Mekanism is installed, FE otherwise. */
    AUTO(0, "auto"),
    FE(1, "fe"),
    /** Mekanism Joules, at Mekanism's configured FE conversion rate. */
    JOULES(2, "joules");

    public static final Codec<EnergyUnit> CODEC = StringRepresentable.fromEnum(EnergyUnit::values);
    public static final StreamCodec<ByteBuf, EnergyUnit> STREAM_CODEC = ByteBufCodecs.idMapper(
            ByIdMap.continuous(EnergyUnit::id, values(), ByIdMap.OutOfBoundsStrategy.ZERO), EnergyUnit::id);

    private final int id;
    private final String name;

    EnergyUnit(int id, String name) {
        this.id = id;
        this.name = name;
    }

    public int id() {
        return id;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    public String getTranslationKey() {
        return "energy_unit.seekerdrones." + name;
    }

    /** The unit numbers are actually shown in: FE or Joules. Joules fall back to FE when Mekanism isn't installed. */
    public EnergyUnit resolve() {
        if (!MekanismEnergy.isLoaded()) {
            return FE;
        }
        return this == AUTO ? JOULES : this;
    }

    /** The next choice for the unit side tab: Auto, FE, Joules, then Auto again. */
    public EnergyUnit next() {
        EnergyUnit[] units = values();
        return units[(ordinal() + 1) % units.length];
    }

    /** The symbol shown after a number. Only meaningful for a resolved unit. */
    public String symbol() {
        return this == JOULES ? "J" : "FE";
    }

    /** Converts an FE amount into this (resolved) unit. */
    public double fromFe(double fe) {
        return this == JOULES ? fe * MekanismEnergy.joulesPerFe() : fe;
    }
}
