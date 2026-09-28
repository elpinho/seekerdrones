package com.elpinho.seekerdrones.station;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * The fluid Charging Stations heal drones with (DESIGN.md sections 5.3, 7.4 and 7.5): Ethene with Mekanism, otherwise
 * Lava. Never both.
 */
public final class RepairFluid {
    public static final String MEKANISM = "mekanism";
    public static final TagKey<Fluid> ETHENE = TagKey.create(Registries.FLUID, ResourceLocation.fromNamespaceAndPath("c", "ethene"));

    private RepairFluid() {}

    public static boolean usesEthene() {
        return ModList.get().isLoaded(MEKANISM);
    }

    public static boolean isRepairFluid(FluidStack stack) {
        return usesEthene() ? stack.is(ETHENE) : stack.is(Fluids.LAVA);
    }

    /** The fluid to name in the station screen when the tank is empty. */
    public static Fluid displayFluid() {
        if (usesEthene()) {
            return BuiltInRegistries.FLUID.getTag(ETHENE)
                    .flatMap(tag -> tag.stream().map(Holder::value).filter(fluid -> fluid.defaultFluidState().isSource()).findFirst())
                    .orElse(Fluids.EMPTY);
        }
        return Fluids.LAVA;
    }
}
