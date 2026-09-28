package com.elpinho.seekerdrones.factory;

import java.util.List;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeInput;
import net.neoforged.neoforge.fluids.FluidStack;

/** The Drone Factory's input slots and tank, as seen by {@link DroneAssemblyRecipe}. */
public record DroneAssemblyInput(List<ItemStack> items, FluidStack fluid) implements RecipeInput {
    @Override
    public ItemStack getItem(int index) {
        return items.get(index);
    }

    @Override
    public int size() {
        return items.size();
    }
}
