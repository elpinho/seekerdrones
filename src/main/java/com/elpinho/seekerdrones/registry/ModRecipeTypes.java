package com.elpinho.seekerdrones.registry;

import com.elpinho.seekerdrones.SeekerDrones;
import com.elpinho.seekerdrones.factory.DroneAssemblyRecipe;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModRecipeTypes {
    public static final DeferredRegister<RecipeType<?>> RECIPE_TYPES =
            DeferredRegister.create(Registries.RECIPE_TYPE, SeekerDrones.MODID);
    public static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS =
            DeferredRegister.create(Registries.RECIPE_SERIALIZER, SeekerDrones.MODID);

    public static final DeferredHolder<RecipeType<?>, RecipeType<DroneAssemblyRecipe>> DRONE_ASSEMBLY =
            RECIPE_TYPES.register("drone_assembly", () -> RecipeType.simple(ResourceLocation.fromNamespaceAndPath(SeekerDrones.MODID, "drone_assembly")));
    public static final DeferredHolder<RecipeSerializer<?>, DroneAssemblyRecipe.Serializer> DRONE_ASSEMBLY_SERIALIZER =
            RECIPE_SERIALIZERS.register("drone_assembly", DroneAssemblyRecipe.Serializer::new);
}
