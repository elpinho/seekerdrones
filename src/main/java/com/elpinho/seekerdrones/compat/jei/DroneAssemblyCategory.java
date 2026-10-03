package com.elpinho.seekerdrones.compat.jei;

import java.text.DecimalFormat;
import java.util.Arrays;
import java.util.List;

import com.elpinho.seekerdrones.SeekerDrones;
import com.elpinho.seekerdrones.energy.EnergyFormat;
import com.elpinho.seekerdrones.factory.DroneAssemblyRecipe;
import com.elpinho.seekerdrones.factory.FactorySlots;
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
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * The Drone Factory's {@code drone_assembly} recipes (DESIGN.md section 7.1): the item inputs in the Factory's drone
 * layout (rotors in the corners, the core in the middle, plating above and below it), the fluid, and the FE and
 * processing time below.
 */
public class DroneAssemblyCategory implements IRecipeCategory<RecipeHolder<DroneAssemblyRecipe>> {
    public static final RecipeType<RecipeHolder<DroneAssemblyRecipe>> TYPE =
            RecipeType.createRecipeHolderType(ResourceLocation.fromNamespaceAndPath(SeekerDrones.MODID, "drone_assembly"));

    private static final int SLOT_SIZE = 18;
    /** Grid column and row of each Factory input slot ({@link FactorySlots}), in a 3x3 grid. */
    private static final int[] SLOT_COLUMN = {0, 2, 0, 2, 1, 1, 1};
    private static final int[] SLOT_ROW = {0, 0, 2, 2, 1, 0, 2};
    private static final int TANK_X = 3 * SLOT_SIZE + 4;
    private static final int TANK_WIDTH = 16;
    private static final int TANK_HEIGHT = 3 * SLOT_SIZE - 2;
    private static final int ARROW_X = TANK_X + TANK_WIDTH + 6;
    private static final int ARROW_Y = SLOT_SIZE + 1;
    private static final int OUTPUT_X = ARROW_X + 24 + 8;
    private static final int OUTPUT_Y = SLOT_SIZE + 1;
    private static final int TEXT_Y = 3 * SLOT_SIZE + 4;
    private static final int WIDTH = OUTPUT_X + 22;
    private static final int TANK_BORDER_COLOR = 0xFF373737;
    private static final int TANK_BACKGROUND_COLOR = 0xFF8B8B8B;
    private static final int TEXT_COLOR = 0xFF404040;
    private static final DecimalFormat SECONDS = new DecimalFormat("0.##");

    private final IDrawable icon;
    private final IDrawable slot;

    public DroneAssemblyCategory(IGuiHelper guiHelper) {
        this.icon = guiHelper.createDrawableItemLike(ModItems.DRONE_FACTORY.get());
        this.slot = guiHelper.getSlotDrawable();
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
        // Added in Factory slot order, so the recipe transfer maps the n-th item slot to Factory input slot n. A plating
        // slot the recipe doesn't use is always the last one, so it can be left out.
        for (int i = 0; i < FactorySlots.INPUT_SLOTS; i++) {
            int count = recipe.countFor(i);
            if (count == 0) {
                continue;
            }
            List<ItemStack> stacks = Arrays.stream(recipe.ingredientFor(i).orElseThrow().getItems())
                    .map(stack -> stack.copyWithCount(count))
                    .toList();
            builder.addSlot(RecipeIngredientRole.INPUT, slotX(i), slotY(i))
                    .setStandardSlotBackground()
                    .addItemStacks(stacks);
        }
        // Added after the items, which keeps the transfer's item slot order.
        List<FluidStack> fluids = Arrays.asList(recipe.fluid().getFluids());
        builder.addSlot(RecipeIngredientRole.INPUT, TANK_X + 1, 1)
                .setFluidRenderer(recipe.fluid().amount(), false, TANK_WIDTH, TANK_HEIGHT)
                .addIngredients(NeoForgeTypes.FLUID_STACK, fluids);
        builder.addSlot(RecipeIngredientRole.OUTPUT, OUTPUT_X, OUTPUT_Y)
                .setOutputSlotBackground()
                .addItemStack(new ItemStack(ModItems.DRONE.get()));
    }

    private static int slotX(int slot) {
        return 1 + SLOT_COLUMN[slot] * SLOT_SIZE;
    }

    private static int slotY(int slot) {
        return 1 + SLOT_ROW[slot] * SLOT_SIZE;
    }

    @Override
    public void createRecipeExtras(IRecipeExtrasBuilder builder, RecipeHolder<DroneAssemblyRecipe> holder, IFocusGroup focuses) {
        builder.addAnimatedRecipeArrow(holder.value().time()).setPosition(ARROW_X, ARROW_Y);
    }

    @Override
    public void draw(RecipeHolder<DroneAssemblyRecipe> holder, IRecipeSlotsView slots, GuiGraphics graphics, double mouseX, double mouseY) {
        DroneAssemblyRecipe recipe = holder.value();
        // Unused plating slots still show, empty, so the layout matches the Factory.
        for (int i = FactorySlots.FIRST_PLATING; i < FactorySlots.INPUT_SLOTS; i++) {
            if (recipe.countFor(i) == 0) {
                slot.draw(graphics, slotX(i) - 1, slotY(i) - 1);
            }
        }
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
