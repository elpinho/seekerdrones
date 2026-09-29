package com.elpinho.seekerdrones.compat.jei;

import java.text.DecimalFormat;
import java.util.Arrays;
import java.util.List;

import com.elpinho.seekerdrones.SeekerDrones;
import com.elpinho.seekerdrones.energy.EnergyFormat;
import com.elpinho.seekerdrones.factory.DroneAssemblyRecipe;
import com.elpinho.seekerdrones.registry.ModItems;

import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.gui.widgets.IRecipeExtrasBuilder;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.neoforge.NeoForgeTypes;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.common.crafting.SizedIngredient;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * The Drone Factory's {@code drone_assembly} recipes (DESIGN.md section 7.1): up to six item inputs laid out like the
 * Factory's slots, the fluid, and the FE and processing time below.
 */
public class DroneAssemblyCategory implements IRecipeCategory<RecipeHolder<DroneAssemblyRecipe>> {
    public static final RecipeType<RecipeHolder<DroneAssemblyRecipe>> TYPE =
            RecipeType.createRecipeHolderType(ResourceLocation.fromNamespaceAndPath(SeekerDrones.MODID, "drone_assembly"));

    private static final int COLUMNS = 3;
    private static final int SLOT_SIZE = 18;
    private static final int TANK_X = COLUMNS * SLOT_SIZE + 4;
    private static final int TANK_WIDTH = 16;
    private static final int TANK_HEIGHT = 2 * SLOT_SIZE - 2;
    private static final int ARROW_X = TANK_X + TANK_WIDTH + 6;
    private static final int ARROW_Y = 9;
    private static final int OUTPUT_X = ARROW_X + 24 + 8;
    private static final int OUTPUT_Y = 10;
    private static final int TEXT_Y = 2 * SLOT_SIZE + 4;
    private static final int WIDTH = OUTPUT_X + 22;
    private static final int TANK_BORDER_COLOR = 0xFF373737;
    private static final int TANK_BACKGROUND_COLOR = 0xFF8B8B8B;
    private static final int TEXT_COLOR = 0xFF404040;
    private static final DecimalFormat SECONDS = new DecimalFormat("0.##");

    private final IDrawable icon;

    public DroneAssemblyCategory(IGuiHelper guiHelper) {
        this.icon = guiHelper.createDrawableItemLike(ModItems.DRONE_FACTORY.get());
    }

    @Override
    public RecipeType<RecipeHolder<DroneAssemblyRecipe>> getRecipeType() {
        return TYPE;
    }

    @Override
    public Component getTitle() {
        return Component.translatable("jei.seekerdrones.drone_assembly");
    }

    @Override
    public IDrawable getIcon() {
        return icon;
    }

    @Override
    public int getWidth() {
        return WIDTH;
    }

    @Override
    public int getHeight() {
        return TEXT_Y + 2 * (Minecraft.getInstance().font.lineHeight + 1);
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, RecipeHolder<DroneAssemblyRecipe> holder, IFocusGroup focuses) {
        DroneAssemblyRecipe recipe = holder.value();
        List<SizedIngredient> items = recipe.items();
        for (int i = 0; i < items.size(); i++) {
            builder.addSlot(RecipeIngredientRole.INPUT, 1 + i % COLUMNS * SLOT_SIZE, 1 + i / COLUMNS * SLOT_SIZE)
                    .setStandardSlotBackground()
                    .addItemStacks(Arrays.asList(items.get(i).getItems()));
        }
        // Added after the items so the recipe transfer maps item slot i to Factory input slot i.
        List<FluidStack> fluids = Arrays.asList(recipe.fluid().getFluids());
        builder.addSlot(RecipeIngredientRole.INPUT, TANK_X + 1, 1)
                .setFluidRenderer(recipe.fluid().amount(), false, TANK_WIDTH, TANK_HEIGHT)
                .addIngredients(NeoForgeTypes.FLUID_STACK, fluids);
        builder.addSlot(RecipeIngredientRole.OUTPUT, OUTPUT_X, OUTPUT_Y)
                .setOutputSlotBackground()
                .addItemStack(new ItemStack(ModItems.DRONE.get()));
    }

    @Override
    public void createRecipeExtras(IRecipeExtrasBuilder builder, RecipeHolder<DroneAssemblyRecipe> holder, IFocusGroup focuses) {
        builder.addAnimatedRecipeArrow(holder.value().time()).setPosition(ARROW_X, ARROW_Y);
    }

    @Override
    public void draw(RecipeHolder<DroneAssemblyRecipe> holder, IRecipeSlotsView slots, GuiGraphics graphics, double mouseX, double mouseY) {
        DroneAssemblyRecipe recipe = holder.value();
        graphics.fill(TANK_X, 0, TANK_X + TANK_WIDTH + 2, TANK_HEIGHT + 2, TANK_BORDER_COLOR);
        graphics.fill(TANK_X + 1, 1, TANK_X + TANK_WIDTH + 1, TANK_HEIGHT + 1, TANK_BACKGROUND_COLOR);

        Font font = Minecraft.getInstance().font;
        graphics.drawString(font, EnergyFormat.amount(recipe.energy()), 0, TEXT_Y, TEXT_COLOR, false);
        graphics.drawString(font, Component.translatable("jei.seekerdrones.drone_assembly.time", SECONDS.format(recipe.time() / 20.0)),
                0, TEXT_Y + font.lineHeight + 1, TEXT_COLOR, false);
    }

    @Override
    public ResourceLocation getRegistryName(RecipeHolder<DroneAssemblyRecipe> holder) {
        return holder.id();
    }
}
