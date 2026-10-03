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
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.crafting.SizedIngredient;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;

/**
 * {@code seekerdrones:drone_assembly} (DESIGN.md sections 7.1 and 7.5): item ingredients by slot role, a fluid with an
 * amount, total FE and processing time. Every rotor slot takes one {@code rotor}, the core slot one {@code core}, and
 * the plating slots take the {@code plating} ingredients in order, with their counts. A plating slot the recipe doesn't
 * use must be empty. The result is always a new drone, which the Factory fills in.
 */
public record DroneAssemblyRecipe(Ingredient rotor, Ingredient core, List<SizedIngredient> plating, SizedFluidIngredient fluid, int energy,
        int time) implements Recipe<DroneAssemblyInput> {
    public static final MapCodec<DroneAssemblyRecipe> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Ingredient.CODEC_NONEMPTY.fieldOf("rotor").forGetter(DroneAssemblyRecipe::rotor),
            Ingredient.CODEC_NONEMPTY.fieldOf("core").forGetter(DroneAssemblyRecipe::core),
            SizedIngredient.FLAT_CODEC.listOf(1, FactorySlots.PLATING_SLOTS).fieldOf("plating").forGetter(DroneAssemblyRecipe::plating),
            SizedFluidIngredient.FLAT_CODEC.fieldOf("fluid").forGetter(DroneAssemblyRecipe::fluid),
            Codec.intRange(0, Integer.MAX_VALUE).fieldOf("energy").forGetter(DroneAssemblyRecipe::energy),
            Codec.intRange(1, Integer.MAX_VALUE).fieldOf("time").forGetter(DroneAssemblyRecipe::time)
    ).apply(instance, DroneAssemblyRecipe::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, DroneAssemblyRecipe> STREAM_CODEC = StreamCodec.composite(
            Ingredient.CONTENTS_STREAM_CODEC, DroneAssemblyRecipe::rotor,
            Ingredient.CONTENTS_STREAM_CODEC, DroneAssemblyRecipe::core,
            SizedIngredient.STREAM_CODEC.apply(ByteBufCodecs.list(FactorySlots.PLATING_SLOTS)), DroneAssemblyRecipe::plating,
            SizedFluidIngredient.STREAM_CODEC, DroneAssemblyRecipe::fluid,
            ByteBufCodecs.VAR_INT, DroneAssemblyRecipe::energy,
            ByteBufCodecs.VAR_INT, DroneAssemblyRecipe::time,
            DroneAssemblyRecipe::new);

    public DroneAssemblyRecipe {
        plating = List.copyOf(plating);
    }

    /** The ingredient the recipe wants in an input slot, or empty if the slot must stay empty. */
    public Optional<Ingredient> ingredientFor(int slot) {
        return switch (FactorySlots.role(slot)) {
            case ROTOR -> Optional.of(rotor);
            case CORE -> Optional.of(core);
            case PLATING -> {
                int index = slot - FactorySlots.FIRST_PLATING;
                yield index < plating.size() ? Optional.of(plating.get(index).ingredient()) : Optional.empty();
            }
        };
    }

    /** How many items the recipe takes from an input slot (0 for a slot it doesn't use). */
    public int countFor(int slot) {
        return switch (FactorySlots.role(slot)) {
            case ROTOR, CORE -> 1;
            case PLATING -> {
                int index = slot - FactorySlots.FIRST_PLATING;
                yield index < plating.size() ? plating.get(index).count() : 0;
            }
        };
    }

    @Override
    public boolean matches(DroneAssemblyInput input, Level level) {
        return fluid.test(input.fluid()) && itemsMatch(input.items());
    }

    /** Whether every input slot holds what the recipe wants there, ignoring the fluid. */
    public boolean itemsMatch(List<ItemStack> slots) {
        for (int slot = 0; slot < FactorySlots.INPUT_SLOTS; slot++) {
            ItemStack stack = slot < slots.size() ? slots.get(slot) : ItemStack.EMPTY;
            Optional<Ingredient> ingredient = ingredientFor(slot);
            if (ingredient.isEmpty()) {
                if (!stack.isEmpty()) {
                    return false;
                }
            } else if (stack.isEmpty() || !ingredient.get().test(stack) || stack.getCount() < countFor(slot)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether an item fits an input slot: some loaded recipe has a matching ingredient for that slot (section 7.1).
     * Used for players and automation alike.
     */
    public static boolean accepts(RecipeManager recipes, int slot, ItemStack stack) {
        if (slot < 0 || slot >= FactorySlots.INPUT_SLOTS || stack.isEmpty()) {
            return false;
        }
        for (RecipeHolder<DroneAssemblyRecipe> holder : recipes.getAllRecipesFor(ModRecipeTypes.DRONE_ASSEMBLY.get())) {
            Optional<Ingredient> ingredient = holder.value().ingredientFor(slot);
            if (ingredient.isPresent() && ingredient.get().test(stack)) {
                return true;
            }
        }
        return false;
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

    /** One ingredient per used input slot, in slot order. */
    @Override
    public NonNullList<Ingredient> getIngredients() {
        NonNullList<Ingredient> ingredients = NonNullList.create();
        for (int slot = 0; slot < FactorySlots.INPUT_SLOTS; slot++) {
            ingredientFor(slot).ifPresent(ingredients::add);
        }
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
