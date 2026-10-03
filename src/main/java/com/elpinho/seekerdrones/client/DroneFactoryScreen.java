package com.elpinho.seekerdrones.client;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import org.lwjgl.glfw.GLFW;

import com.elpinho.seekerdrones.client.gui.EntityPreview;
import com.elpinho.seekerdrones.client.gui.Gauges;
import com.elpinho.seekerdrones.client.gui.Kit;
import com.elpinho.seekerdrones.client.gui.KitWidget;
import com.elpinho.seekerdrones.client.gui.MachineScreen;
import com.elpinho.seekerdrones.client.gui.SideTab;
import com.elpinho.seekerdrones.client.gui.StatusStrip;
import com.elpinho.seekerdrones.energy.EnergyFormat;
import com.elpinho.seekerdrones.factory.DroneAssemblyRecipe;
import com.elpinho.seekerdrones.factory.DroneFactoryMenu;
import com.elpinho.seekerdrones.factory.FactorySlots;
import com.elpinho.seekerdrones.factory.FactoryStatus;
import com.elpinho.seekerdrones.network.EditFactoryOperatorsPayload;
import com.elpinho.seekerdrones.network.FactoryOperatorsPayload;
import com.elpinho.seekerdrones.registry.ModItems;
import com.elpinho.seekerdrones.registry.ModRecipeTypes;

import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Drone Factory screen (DESIGN.md section 7.1): the input slots in the drone layout over a blueprint that traces itself
 * as the build progresses, with ghosts of what goes in empty slots, the energy and fluid gauges, the progress arrow (a
 * JEI recipe link), the output, and a status strip. The Operator list (owner only) is a side tab that unfolds into a
 * panel with the operators' heads, a remove button per row and an add field.
 */
public class DroneFactoryScreen extends MachineScreen<DroneFactoryMenu> {
    /**
     * A little wider and taller than the other machines (176 x 194), so the rotor rings fit inside the blueprint. The
     * player inventory is centered.
     */
    private static final int WIDTH = 182;
    private static final int HEIGHT = 200;

    public static final int ARROW_X = 127;
    public static final int ARROW_Y = 47;
    public static final int ARROW_WIDTH = 18;
    public static final int ARROW_HEIGHT = 12;

    private static final int BLUEPRINT_X = 38;
    private static final int BLUEPRINT_Y = 16;
    private static final int BLUEPRINT_WIDTH = 86;
    private static final int BLUEPRINT_HEIGHT = 74;
    /** The blueprint's geometry inside the display (see generate_gui_sprites.py): the drone's center and its rotors. */
    private static final int CENTER_X = 42;
    private static final int CENTER_Y = 36;
    private static final int[][] ROTORS = {{14, 14}, {70, 14}, {14, 58}, {70, 58}};
    private static final float ROTOR_RING_RADIUS = 12.5F;
    private static final int ARM_TRACE = 0xFF86D0FF;
    private static final int ROTOR_RING = 0xFF3F86C6;

    private static final ResourceLocation BLUEPRINT = Kit.sprite("factory/blueprint");
    private static final ResourceLocation ARROW = Kit.sprite("factory/arrow");
    private static final ResourceLocation ARROW_FILLED = Kit.sprite("factory/arrow_filled");
    private static final ResourceLocation REMOVE = Kit.sprite("remove_button");
    private static final ResourceLocation REMOVE_HIGHLIGHTED = Kit.sprite("remove_button_highlighted");

    // The Operators panel, relative to its top-left corner.
    private static final int PANEL_WIDTH = 118;
    private static final int PANEL_HEIGHT = 114;
    private static final int PANEL_HEADER = 16;
    private static final int LIST_X = 8;
    private static final int LIST_Y = 26;
    private static final int LIST_WIDTH = 104;
    private static final int LIST_HEIGHT = 56;
    private static final int ROW_HEIGHT = 13;
    private static final int VISIBLE_ROWS = 3;
    private static final int REMOVE_X = LIST_X + 90;
    private static final int FIELD_X = 8;
    private static final int FIELD_Y = 86;
    private static final int FIELD_WIDTH = 82;
    private static final int FIELD_HEIGHT = 14;
    private static final int ADD_X = 92;
    private static final int ADD_WIDTH = 20;

    private final SideTab operatorsTab;
    private EditBox nameBox;
    private int operatorScroll;
    /** The server's reply to the owner's last edit, shown instead of the operator count. */
    @Nullable
    private Component operatorMessage;
    private int mouseX;
    private int mouseY;

    /** What each input slot can take, from the loaded recipes, for its ghost and tooltip. */
    private final List<List<ItemStack>> ghosts = new ArrayList<>();
    /** The recipe the input slots match, ignoring the fluid, found on the client for the tooltips and hints. */
    @Nullable
    private DroneAssemblyRecipe recipe;

    public DroneFactoryScreen(DroneFactoryMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, WIDTH, HEIGHT, DroneFactoryMenu.INVENTORY_Y);
        this.inventoryLabelX = DroneFactoryMenu.INVENTORY_X;
        operatorsTab = sideTabs.add(new SideTab(22, (graphics, x, y, mouseX, mouseY) -> EntityPreview.drawFace(graphics, playerName(), x + 8, y + 6, 9))
                .visibleWhen(this::isOwner)
                .tooltip(this::operatorsTooltip)
                .panel(PANEL_WIDTH, PANEL_HEIGHT, this::renderOperatorsPanel)
                .panelClick(PANEL_HEADER, this::clickOperatorsPanel));
        sideTabs.add(SideTab.energyUnit());
    }

    @Override
    protected void init() {
        super.init();
        addRenderableWidget(new Gauges.Energy(leftPos + 8, topPos + 16, 12, BLUEPRINT_HEIGHT, () -> Kit.fraction(menu.getEnergy(), menu.getEnergyCapacity())))
                .tooltip(this::energyTooltip);
        addRenderableWidget(new Gauges.FluidTank(leftPos + 23, topPos + 16, 12, BLUEPRINT_HEIGHT, menu::getFluid, menu::getFluidAmount, menu::getTankCapacity))
                .tooltip(this::fluidTooltip);
        addRenderableWidget(new Arrow(leftPos + ARROW_X, topPos + ARROW_Y));
        addRenderableWidget(new StatusStrip(leftPos + 8, topPos + 94, WIDTH - 16, this::status));

        String pendingName = nameBox != null ? nameBox.getValue() : "";
        nameBox = addRenderableWidget(new EditBox(font, 0, 0, FIELD_WIDTH, FIELD_HEIGHT,
                Component.translatable("screen.seekerdrones.drone_factory.operators.name")));
        nameBox.setMaxLength(16);
        nameBox.setHint(Component.translatable("screen.seekerdrones.drone_factory.operators.name").withStyle(ChatFormatting.DARK_GRAY));
        nameBox.setValue(pendingName);
        nameBox.visible = false;

        ghosts.clear();
        for (int slot = 0; slot < FactorySlots.INPUT_SLOTS; slot++) {
            ghosts.add(candidates(slot));
        }
        updateRecipe();
    }

    /** Every item some loaded recipe takes in the slot, one of each. */
    private List<ItemStack> candidates(int slot) {
        Set<Item> items = new LinkedHashSet<>();
        for (RecipeHolder<DroneAssemblyRecipe> holder : recipes()) {
            holder.value().ingredientFor(slot).ifPresent(ingredient -> {
                for (ItemStack stack : ingredient.getItems()) {
                    items.add(stack.getItem());
                }
            });
        }
        return items.stream().map(ItemStack::new).toList();
    }

    private List<RecipeHolder<DroneAssemblyRecipe>> recipes() {
        return minecraft.level.getRecipeManager().getAllRecipesFor(ModRecipeTypes.DRONE_ASSEMBLY.get());
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        updateRecipe();
        if (!isOwner() && operatorsTab.isExpanded()) {
            operatorsTab.setExpanded(false);
        }
    }

    private void updateRecipe() {
        List<ItemStack> inputs = new ArrayList<>(FactorySlots.INPUT_SLOTS);
        for (int slot = 0; slot < FactorySlots.INPUT_SLOTS; slot++) {
            inputs.add(menu.getSlot(slot).getItem());
        }
        recipe = recipes().stream().map(RecipeHolder::value).filter(candidate -> candidate.itemsMatch(inputs)).findFirst().orElse(null);
    }

    private float progress() {
        return Kit.fraction(menu.getProgress(), menu.getTime());
    }

    // --- Tooltips and status ---

    private List<Component> energyTooltip() {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("screen.seekerdrones.energy"));
        lines.add(Component.literal(EnergyFormat.ratio(menu.getEnergy(), menu.getEnergyCapacity())).withStyle(ChatFormatting.GRAY));
        if (recipe != null) {
            lines.add(Component.translatable("screen.seekerdrones.drone_factory.energy_use", EnergyFormat.rate(energyPerTick(recipe)))
                    .withStyle(ChatFormatting.GRAY));
        }
        return lines;
    }

    private static long energyPerTick(DroneAssemblyRecipe recipe) {
        return recipe.energy() / recipe.time();
    }

    private List<Component> fluidTooltip() {
        Fluid fluid = menu.getFluid();
        List<Component> lines = new ArrayList<>();
        lines.add(fluid == Fluids.EMPTY ? Component.translatable("screen.seekerdrones.drone_factory.empty") : fluid.getFluidType().getDescription());
        lines.add(Component.translatable("screen.seekerdrones.drone_factory.fluid_value", menu.getFluidAmount(), menu.getTankCapacity())
                .withStyle(ChatFormatting.GRAY));
        Component needed = neededFluid();
        if (needed != null) {
            lines.add(Component.translatable("screen.seekerdrones.drone_factory.fluid_use", needed).withStyle(ChatFormatting.GRAY));
        }
        return lines;
    }

    /** "1000 mB of Lava" for the matched recipe, or null without one. */
    @Nullable
    private Component neededFluid() {
        if (recipe == null) {
            return null;
        }
        FluidStack[] fluids = recipe.fluid().getFluids();
        Component name = fluids.length > 0 ? fluids[0].getHoverName() : Component.translatable("screen.seekerdrones.drone_factory.any_fluid");
        return Component.translatable("screen.seekerdrones.drone_factory.fluid_amount", recipe.fluid().amount(), name);
    }

    private StatusStrip.Status status() {
        FactoryStatus status = menu.getStatus();
        Kit.Light light = switch (status) {
            case IDLE -> Kit.Light.IDLE;
            case BUILDING -> Kit.Light.OK;
            case NO_ENERGY, OUTPUT_FULL -> Kit.Light.WARN;
            case MISSING_FLUID -> Kit.Light.BAD;
        };
        Component hint = switch (status) {
            case BUILDING -> Component.translatable(status.getHintKey(), Mth.floor(progress() * 100),
                    recipe != null ? EnergyFormat.rate(energyPerTick(recipe)) : "-");
            case NO_ENERGY -> recipe != null
                    ? Component.translatable(status.getHintKey(), EnergyFormat.rate(energyPerTick(recipe)))
                    : Component.translatable(status.getHintKey() + ".unknown");
            case MISSING_FLUID -> {
                Component needed = neededFluid();
                yield needed != null ? Component.translatable(status.getHintKey(), needed) : Component.translatable(status.getHintKey() + ".unknown");
            }
            default -> Component.translatable(status.getHintKey());
        };
        Component text = Component.literal(Kit.upper(Component.translatable(status.getTranslationKey())) + " · ").append(hint);
        return new StatusStrip.Status(light, status == FactoryStatus.MISSING_FLUID, text);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.mouseX = mouseX;
        this.mouseY = mouseY;
        boolean panelOpen = operatorsTab.isVisible() && operatorsTab.isExpanded();
        nameBox.visible = panelOpen;
        if (!panelOpen && getFocused() == nameBox) {
            setFocused(null);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
        if (hoveredSlot != null && !hoveredSlot.hasItem() && hoveredSlot.index < FactorySlots.INPUT_SLOTS && menu.getCarried().isEmpty()) {
            KitWidget.showTooltip(slotTooltip(hoveredSlot.index));
        }
    }

    /** An empty input slot says what goes there. */
    private List<Component> slotTooltip(int slot) {
        FactorySlots.Role role = FactorySlots.role(slot);
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable(role.getTranslationKey()));
        if (role == FactorySlots.Role.ROTOR) {
            lines.add(Component.translatable("screen.seekerdrones.drone_factory.role.rotor.tooltip").withStyle(ChatFormatting.GRAY));
        }
        Set<String> takes = new LinkedHashSet<>();
        for (RecipeHolder<DroneAssemblyRecipe> holder : recipes()) {
            int count = holder.value().countFor(slot);
            Ingredient ingredient = holder.value().ingredientFor(slot).orElse(null);
            if (ingredient == null || ingredient.getItems().length == 0) {
                continue;
            }
            Component name = ingredient.getItems()[0].getHoverName();
            takes.add(count > 1
                    ? Component.translatable("screen.seekerdrones.drone_factory.takes_count", name, count).getString()
                    : Component.translatable("screen.seekerdrones.drone_factory.takes", name).getString());
        }
        if (takes.isEmpty()) {
            lines.add(Component.translatable("screen.seekerdrones.drone_factory.unused_slot").withStyle(ChatFormatting.GRAY));
        }
        takes.forEach(line -> lines.add(Component.literal(line).withStyle(ChatFormatting.GRAY)));
        return lines;
    }

    // --- Drawing ---

    @Override
    protected void renderContents(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = leftPos + BLUEPRINT_X;
        int y = topPos + BLUEPRINT_Y;
        graphics.blitSprite(BLUEPRINT, x, y, BLUEPRINT_WIDTH, BLUEPRINT_HEIGHT);
        // The inside of the display, where the blueprint's geometry starts.
        int innerX = x + 1;
        int innerY = y + 1;
        // Each arm traces out from the center over a quarter of the build.
        float progress = progress();
        for (int i = 0; i < ROTORS.length; i++) {
            float arm = Mth.clamp(progress * ROTORS.length - i, 0, 1);
            if (arm > 0) {
                line(graphics, innerX + CENTER_X, innerY + CENTER_Y, innerX + ROTORS[i][0], innerY + ROTORS[i][1], arm, ARM_TRACE);
            }
        }
        // Dashed rings around the rotors, always turning, clipped to the display like the rest of the blueprint.
        double turn = Math.toRadians(Util.getMillis() / 40.0 % 360);
        graphics.enableScissor(innerX, innerY, x + BLUEPRINT_WIDTH - 1, y + BLUEPRINT_HEIGHT - 1);
        for (int[] rotor : ROTORS) {
            ring(graphics, innerX + rotor[0], innerY + rotor[1], turn);
        }
        graphics.disableScissor();
    }

    /**
     * A 1-pixel line from the point (x0, y0) toward (x1, y1), drawn up to {@code fraction} of the way. The points are
     * pixel corners (like the slots' centers), and each pixel is taken on the side of the line's direction, so lines in
     * mirrored directions come out as mirror images.
     */
    private static void line(GuiGraphics graphics, int x0, int y0, int x1, int y1, float fraction, int color) {
        int dx = x1 - x0;
        int dy = y1 - y0;
        int steps = Math.max(Math.abs(dx), Math.abs(dy));
        int end = Math.round(steps * fraction);
        for (int i = 0; i < end; i++) {
            float t = (i + 0.5F) / steps;
            int px = pixelAlong(x0, dx, t);
            int py = pixelAlong(y0, dy, t);
            graphics.fill(px, py, px + 1, py + 1, color);
        }
    }

    /** The pixel covering {@code start + delta * t}, mirrored around {@code start} for negative deltas. */
    private static int pixelAlong(int start, int delta, float t) {
        return delta >= 0 ? start + Mth.floor(delta * t) : start - 1 - Mth.floor(-delta * t);
    }

    /** A dashed circle around the point (centerX, centerY), a pixel corner, its dashes rotated by {@code turn} radians. */
    private static void ring(GuiGraphics graphics, int centerX, int centerY, double turn) {
        double circumference = 2 * Math.PI * ROTOR_RING_RADIUS;
        int dashes = 20;
        double period = circumference / dashes;
        for (double along = 0; along < circumference; along += 0.5) {
            if (along % period > period * 0.58) {
                continue;
            }
            double angle = along / ROTOR_RING_RADIUS + turn;
            int px = Mth.floor(centerX + Math.cos(angle) * ROTOR_RING_RADIUS);
            int py = Mth.floor(centerY + Math.sin(angle) * ROTOR_RING_RADIUS);
            graphics.fill(px, py, px + 1, py + 1, ROTOR_RING);
        }
    }

    @Override
    protected void renderSlotBackground(GuiGraphics graphics, Slot slot, int x, int y) {
        if (slot.index == FactorySlots.OUTPUT) {
            graphics.blitSprite(Kit.SLOT_LARGE, x - 4, y - 4, 26, 26);
            if (!slot.hasItem()) {
                Kit.ghost(graphics, new ItemStack(ModItems.DRONE.get()), x + 1, y + 1);
            }
            return;
        }
        super.renderSlotBackground(graphics, slot, x, y);
        if (slot.index < FactorySlots.INPUT_SLOTS && !slot.hasItem() && slot.index < ghosts.size()) {
            List<ItemStack> candidates = ghosts.get(slot.index);
            if (!candidates.isEmpty()) {
                Kit.ghost(graphics, candidates.get((int) (Util.getMillis() / 1000 % candidates.size())), x + 1, y + 1);
            }
        }
    }

    /**
     * The progress arrow, filled from the left. With JEI, clicking it shows the recipes. It has no tooltip of its own:
     * JEI shows one for its click area, and the status strip already shows the status and progress.
     */
    private class Arrow extends KitWidget {
        Arrow(int x, int y) {
            super(x, y, ARROW_WIDTH, ARROW_HEIGHT);
        }

        @Override
        protected void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            graphics.blitSprite(ARROW, getX(), getY(), width, height);
            int filled = Math.round(width * progress());
            if (filled > 0) {
                graphics.blitSprite(ARROW_FILLED, width, height, 0, 0, getX(), getY(), filled, height);
            }
        }
    }

    // --- Operators ---

    private boolean isOwner() {
        FactoryOperatorsPayload operators = menu.getOperators();
        return operators != null && operators.owner();
    }

    private List<FactoryOperatorsPayload.Operator> operators() {
        FactoryOperatorsPayload operators = menu.getOperators();
        return operators != null ? operators.operators() : List.of();
    }

    private String playerName() {
        return minecraft.player != null ? minecraft.player.getGameProfile().getName() : "";
    }

    /** A fresh list from the server, e.g. after an edit. */
    public void onOperatorsUpdated(FactoryOperatorsPayload payload) {
        payload.message().filter(message -> !message.getString().isEmpty()).ifPresent(message -> operatorMessage = message);
        operatorScroll = Mth.clamp(operatorScroll, 0, maxOperatorScroll());
    }

    private int maxOperatorScroll() {
        return Math.max(0, operators().size() - VISIBLE_ROWS);
    }

    private void renderOperatorsPanel(GuiGraphics graphics, int x, int y, int mouseX, int mouseY) {
        EntityPreview.drawFace(graphics, playerName(), x + 8, y + 5, 9);
        graphics.drawString(font, Component.translatable("screen.seekerdrones.drone_factory.operators"), x + 21, y + 6, Kit.LABEL, false);
        Kit.scrollingText(graphics, font, Component.translatable("screen.seekerdrones.drone_factory.operators.owner", playerName()),
                x + 8, y + 17, PANEL_WIDTH - 16, 7, Kit.LABEL, false, true);

        int listX = x + LIST_X;
        int listY = y + LIST_Y;
        Kit.display(graphics, listX, listY, LIST_WIDTH, LIST_HEIGHT);
        List<FactoryOperatorsPayload.Operator> operators = operators();
        for (int row = 0; row < VISIBLE_ROWS && operatorScroll + row < operators.size(); row++) {
            FactoryOperatorsPayload.Operator operator = operators.get(operatorScroll + row);
            int rowY = listY + 3 + row * ROW_HEIGHT;
            EntityPreview.drawFace(graphics, operator.name(), listX + 3, rowY, 9);
            Kit.scrollingText(graphics, font, Component.literal(operator.name()), listX + 15, rowY + 1, REMOVE_X - LIST_X - 17, 8,
                    Kit.DISPLAY_TEXT, false, false);
            graphics.blitSprite(removeRow(mouseX, mouseY, x, y) == row ? REMOVE_HIGHLIGHTED : REMOVE, x + REMOVE_X, rowY, 10, 10);
        }
        if (operators.size() > VISIBLE_ROWS) {
            // A thin scrollbar along the list's right edge.
            int trackY = listY + 3;
            int trackHeight = VISIBLE_ROWS * ROW_HEIGHT - 3;
            graphics.blitSprite(Kit.SCROLLBAR, listX + LIST_WIDTH - 3, trackY, 2, trackHeight);
            int thumbHeight = Math.max(4, trackHeight * VISIBLE_ROWS / operators.size());
            int thumbY = trackY + (trackHeight - thumbHeight) * operatorScroll / maxOperatorScroll();
            graphics.blitSprite(Kit.SCROLLBAR_THUMB, listX + LIST_WIDTH - 3, thumbY, 2, thumbHeight);
        }
        Component footer = operatorMessage != null ? operatorMessage
                : operators.isEmpty() ? Component.translatable("screen.seekerdrones.drone_factory.operators.none")
                : Component.translatable(operators.size() == 1 ? "screen.seekerdrones.drone_factory.operators.count_one"
                        : "screen.seekerdrones.drone_factory.operators.count", operators.size());
        Kit.scrollingText(graphics, font, footer, listX + 3, listY + 44, LIST_WIDTH - 6, 7,
                operatorMessage != null ? Kit.DISPLAY_TEXT : Kit.DISPLAY_TEXT_DIM, false, true);

        nameBox.setPosition(x + FIELD_X, y + FIELD_Y);
        boolean addHovered = isOverAdd(mouseX, mouseY, x, y);
        graphics.blitSprite(addHovered ? Kit.BUTTON_HIGHLIGHTED : Kit.BUTTON, x + ADD_X, y + FIELD_Y, ADD_WIDTH, FIELD_HEIGHT);
        graphics.drawCenteredString(font, "+", x + ADD_X + ADD_WIDTH / 2, y + FIELD_Y + 3, Kit.WHITE);
    }

    /** The row whose remove button is under the mouse, or -1. */
    private int removeRow(double mouseX, double mouseY, int x, int y) {
        int count = Math.min(VISIBLE_ROWS, operators().size() - operatorScroll);
        for (int row = 0; row < count; row++) {
            int rowY = y + LIST_Y + 3 + row * ROW_HEIGHT;
            if (mouseX >= x + REMOVE_X && mouseX < x + REMOVE_X + 10 && mouseY >= rowY && mouseY < rowY + 10) {
                return row;
            }
        }
        return -1;
    }

    private static boolean isOverAdd(double mouseX, double mouseY, int x, int y) {
        return mouseX >= x + ADD_X && mouseX < x + ADD_X + ADD_WIDTH && mouseY >= y + FIELD_Y && mouseY < y + FIELD_Y + FIELD_HEIGHT;
    }

    private static boolean isOverList(double mouseX, double mouseY, int x, int y) {
        return mouseX >= x + LIST_X && mouseX < x + LIST_X + LIST_WIDTH && mouseY >= y + LIST_Y && mouseY < y + LIST_Y + LIST_HEIGHT;
    }

    private boolean clickOperatorsPanel(double mouseX, double mouseY, int x, int y) {
        int row = removeRow(mouseX, mouseY, x, y);
        if (row >= 0) {
            PacketDistributor.sendToServer(EditFactoryOperatorsPayload.remove(menu.containerId, operators().get(operatorScroll + row).id()));
            return true;
        }
        if (isOverAdd(mouseX, mouseY, x, y)) {
            submitName();
            return true;
        }
        if (nameBox.isMouseOver(mouseX, mouseY)) {
            setFocused(nameBox);
            nameBox.mouseClicked(mouseX, mouseY, 0);
        }
        return false;
    }

    private void submitName() {
        String name = nameBox.getValue().trim();
        if (!name.isEmpty()) {
            PacketDistributor.sendToServer(EditFactoryOperatorsPayload.add(menu.containerId, name));
            nameBox.setValue("");
        }
    }

    private List<Component> operatorsTooltip() {
        if (!operatorsTab.isExpanded()) {
            return List.of(Component.translatable("screen.seekerdrones.drone_factory.operators"),
                    Component.translatable("screen.seekerdrones.drone_factory.operators.owner_only").withStyle(ChatFormatting.GRAY),
                    Component.translatable("screen.seekerdrones.drone_factory.operators.open").withStyle(ChatFormatting.GRAY));
        }
        int x = operatorsTab.getX();
        int y = operatorsTab.getY();
        if (mouseY < y + PANEL_HEADER) {
            return List.of(Component.translatable("screen.seekerdrones.drone_factory.operators"),
                    Component.translatable("screen.seekerdrones.drone_factory.operators.close").withStyle(ChatFormatting.GRAY));
        }
        int row = removeRow(mouseX, mouseY, x, y);
        if (row >= 0) {
            return List.of(Component.translatable("screen.seekerdrones.drone_factory.operators.remove_player",
                    operators().get(operatorScroll + row).name()));
        }
        if (isOverAdd(mouseX, mouseY, x, y)) {
            return List.of(Component.translatable("screen.seekerdrones.drone_factory.operators.add"),
                    Component.translatable("screen.seekerdrones.drone_factory.operators.add.tooltip").withStyle(ChatFormatting.GRAY),
                    Component.translatable("screen.seekerdrones.drone_factory.operators.add.enter").withStyle(ChatFormatting.GRAY));
        }
        return List.of();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (getFocused() == nameBox && !nameBox.isMouseOver(mouseX, mouseY)) {
            setFocused(null);
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (operatorsTab.isVisible() && operatorsTab.isExpanded() && isOverList(mouseX, mouseY, operatorsTab.getX(), operatorsTab.getY())) {
            operatorScroll = Mth.clamp(operatorScroll - (int) Math.signum(scrollY), 0, maxOperatorScroll());
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /** While the name field has focus, keys type into it (so the inventory key doesn't close the screen). */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (nameBox.visible && getFocused() == nameBox && keyCode != GLFW.GLFW_KEY_ESCAPE) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                submitName();
                return true;
            }
            nameBox.keyPressed(keyCode, scanCode, modifiers);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
