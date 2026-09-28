package com.elpinho.seekerdrones.factory;

import java.util.List;
import java.util.Optional;

import com.elpinho.seekerdrones.registry.ModItems;
import com.elpinho.seekerdrones.registry.ModRecipeTypes;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.crafting.SizedIngredient;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;

/**
 * {@code seekerdrones:drone_assembly} (DESIGN.md sections 7.1 and 7.5): item ingredients with counts, a fluid with an
 * amount, total FE and processing time. The result is always a new drone, which the Factory fills in.
 */
public record DroneAssemblyRecipe(List<SizedIngredient> items, SizedFluidIngredient fluid, int energy, int time) implements Recipe<DroneAssemblyInput> {
    public static final MapCodec<DroneAssemblyRecipe> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            SizedIngredient.FLAT_CODEC.listOf(1, Integer.MAX_VALUE).fieldOf("items").forGetter(DroneAssemblyRecipe::items),
            SizedFluidIngredient.FLAT_CODEC.fieldOf("fluid").forGetter(DroneAssemblyRecipe::fluid),
            Codec.intRange(0, Integer.MAX_VALUE).fieldOf("energy").forGetter(DroneAssemblyRecipe::energy),
            Codec.intRange(1, Integer.MAX_VALUE).fieldOf("time").forGetter(DroneAssemblyRecipe::time)
    ).apply(instance, DroneAssemblyRecipe::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, DroneAssemblyRecipe> STREAM_CODEC = StreamCodec.composite(
            SizedIngredient.STREAM_CODEC.apply(ByteBufCodecs.list()), DroneAssemblyRecipe::items,
            SizedFluidIngredient.STREAM_CODEC, DroneAssemblyRecipe::fluid,
            ByteBufCodecs.VAR_INT, DroneAssemblyRecipe::energy,
            ByteBufCodecs.VAR_INT, DroneAssemblyRecipe::time,
            DroneAssemblyRecipe::new);

    public DroneAssemblyRecipe {
        items = List.copyOf(items);
    }

    @Override
    public boolean matches(DroneAssemblyInput input, Level level) {
        return fluid.test(input.fluid()) && allocate(input.items()).isPresent();
    }

    /**
     * How many items to take from each input slot to cover every ingredient, or empty if the slots don't hold enough.
     * Ingredients are served in order, each taking from the first slots that match.
     */
    public Optional<int[]> allocate(List<ItemStack> slots) {
        int[] taken = new int[slots.size()];
        for (SizedIngredient ingredient : items) {
            int needed = ingredient.count();
            for (int i = 0; i < slots.size() && needed > 0; i++) {
                ItemStack stack = slots.get(i);
                int available = stack.getCount() - taken[i];
                if (available > 0 && ingredient.ingredient().test(stack)) {
                    int take = Math.min(available, needed);
                    taken[i] += take;
                    needed -= take;
                }
            }
            if (needed > 0) {
                return Optional.empty();
            }
        }
        return Optional.of(taken);
    }

    @Override
    public ItemStack assemble(DroneAssemblyInput input, HolderLookup.Provider registries) {
        return getResultItem(registries);
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return true;
    }

    /** A plain drone, for display. The Factory builds the real one with an ID and group (section 7.1). */
    @Override
    public ItemStack getResultItem(HolderLookup.Provider registries) {
        return new ItemStack(ModItems.DRONE.get());
    }

    @Override
    public NonNullList<Ingredient> getIngredients() {
        NonNullList<Ingredient> ingredients = NonNullList.create();
        items.forEach(ingredient -> ingredients.add(ingredient.ingredient()));
        return ingredients;
    }

    /** Keeps it out of the vanilla recipe book. */
    @Override
    public boolean isSpecial() {
        return true;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return ModRecipeTypes.DRONE_ASSEMBLY_SERIALIZER.get();
    }

    @Override
    public RecipeType<?> getType() {
        return ModRecipeTypes.DRONE_ASSEMBLY.get();
    }

    public static class Serializer implements RecipeSerializer<DroneAssemblyRecipe> {
        @Override
        public MapCodec<DroneAssemblyRecipe> codec() {
            return CODEC;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, DroneAssemblyRecipe> streamCodec() {
            return STREAM_CODEC;
        }
    }
}
