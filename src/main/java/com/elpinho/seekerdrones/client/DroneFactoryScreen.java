package com.elpinho.seekerdrones.client;

import java.util.List;

import com.elpinho.seekerdrones.factory.DroneFactoryBlockEntity;
import com.elpinho.seekerdrones.factory.DroneFactoryMenu;
import com.elpinho.seekerdrones.network.FactoryOperatorsPayload;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;

/**
 * Drone Factory screen (DESIGN.md section 7.1): input slots, fluid tank, energy bar, progress and output, plus an
 * Operators button for the group owner. Drawn with plain fills until the M8 polish pass adds textures.
 */
public class DroneFactoryScreen extends AbstractContainerScreen<DroneFactoryMenu> {
    private static final int PANEL_COLOR = 0xFFC6C6C6;
    private static final int PANEL_BORDER_COLOR = 0xFF555555;
    private static final int SLOT_COLOR = 0xFF8B8B8B;
    private static final int SLOT_BORDER_COLOR = 0xFF373737;
    private static final int BAR_BACKGROUND_COLOR = 0xFF2A2A38;
    private static final int ENERGY_BAR_COLOR = 0xFFD83A2E;
    private static final int PROGRESS_COLOR = 0xFF3FB950;
    private static final int LABEL_COLOR = 0xFF404040;

    private static final int ENERGY_X = 8;
    private static final int FLUID_X = 24;
    private static final int BAR_Y = 18;
    private static final int BAR_WIDTH = 16;
    private static final int ENERGY_WIDTH = 10;
    private static final int BAR_HEIGHT = 52;
    private static final int PROGRESS_X = 108;
    private static final int PROGRESS_Y = 36;
    private static final int PROGRESS_WIDTH = 22;
    private static final int PROGRESS_HEIGHT = 6;

    private Button operatorsButton;

    public DroneFactoryScreen(DroneFactoryMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = 176;
        this.imageHeight = 166;
        this.inventoryLabelY = DroneFactoryMenu.INVENTORY_Y - 11;
    }

    @Override
    protected void init() {
        super.init();
        operatorsButton = addRenderableWidget(Button.builder(Component.translatable("screen.seekerdrones.drone_factory.operators"),
                        button -> minecraft.setScreen(new FactoryOperatorsScreen(this)))
                .bounds(leftPos + imageWidth - 68, topPos + 60, 62, 16)
                .build());
        updateOperatorsButton();
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        updateOperatorsButton();
    }

    /** Only the group owner sees the Operators button. */
    private void updateOperatorsButton() {
        FactoryOperatorsPayload operators = menu.getOperators();
        operatorsButton.visible = operators != null && operators.owner();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
        if (isHovering(ENERGY_X, BAR_Y, ENERGY_WIDTH, BAR_HEIGHT, mouseX, mouseY)) {
            graphics.renderTooltip(font, Component.translatable("screen.seekerdrones.drone_status.energy_value",
                    menu.getEnergy(), menu.getEnergyCapacity()), mouseX, mouseY);
        } else if (isHovering(FLUID_X, BAR_Y, BAR_WIDTH, BAR_HEIGHT, mouseX, mouseY)) {
            Fluid fluid = menu.getFluid();
            Component name = fluid == Fluids.EMPTY
                    ? Component.translatable("screen.seekerdrones.drone_factory.empty")
                    : fluid.getFluidType().getDescription();
            graphics.renderComponentTooltip(font, List.of(name, Component.translatable("screen.seekerdrones.drone_factory.fluid_value",
                    menu.getFluidAmount(), menu.getTankCapacity())), mouseX, mouseY);
        } else if (isHovering(PROGRESS_X, PROGRESS_Y - 4, PROGRESS_WIDTH, PROGRESS_HEIGHT + 8, mouseX, mouseY) && menu.getTime() > 0) {
            graphics.renderTooltip(font, Component.translatable("screen.seekerdrones.drone_factory.progress_value",
                    menu.getProgress() * 100 / menu.getTime()), mouseX, mouseY);
        }
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        graphics.fill(x - 1, y - 1, x + imageWidth + 1, y + imageHeight + 1, PANEL_BORDER_COLOR);
        graphics.fill(x, y, x + imageWidth, y + imageHeight, PANEL_COLOR);
        for (int i = 0; i < DroneFactoryBlockEntity.SLOT_COUNT + 36; i++) {
            var slot = menu.slots.get(i);
            drawSlot(graphics, x + slot.x, y + slot.y);
        }

        // Energy
        drawBar(graphics, x + ENERGY_X, y + BAR_Y, ENERGY_WIDTH, BAR_HEIGHT);
        int energyHeight = scaled(menu.getEnergy(), menu.getEnergyCapacity(), BAR_HEIGHT);
        graphics.fill(x + ENERGY_X, y + BAR_Y + BAR_HEIGHT - energyHeight, x + ENERGY_X + ENERGY_WIDTH, y + BAR_Y + BAR_HEIGHT, ENERGY_BAR_COLOR);

        // Fluid
        drawBar(graphics, x + FLUID_X, y + BAR_Y, BAR_WIDTH, BAR_HEIGHT);
        int fluidHeight = scaled(menu.getFluidAmount(), menu.getTankCapacity(), BAR_HEIGHT);
        if (fluidHeight > 0 && menu.getFluid() != Fluids.EMPTY) {
            drawFluid(graphics, menu.getFluid(), x + FLUID_X, y + BAR_Y + BAR_HEIGHT - fluidHeight, fluidHeight);
        }

        // Progress
        graphics.fill(x + PROGRESS_X, y + PROGRESS_Y, x + PROGRESS_X + PROGRESS_WIDTH, y + PROGRESS_Y + PROGRESS_HEIGHT, BAR_BACKGROUND_COLOR);
        int progressWidth = scaled(menu.getProgress(), menu.getTime(), PROGRESS_WIDTH);
        graphics.fill(x + PROGRESS_X, y + PROGRESS_Y, x + PROGRESS_X + progressWidth, y + PROGRESS_Y + PROGRESS_HEIGHT, PROGRESS_COLOR);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, titleLabelX, titleLabelY, LABEL_COLOR, false);
        graphics.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, LABEL_COLOR, false);
    }

    private static void drawSlot(GuiGraphics graphics, int x, int y) {
        graphics.fill(x - 1, y - 1, x + 17, y + 17, SLOT_BORDER_COLOR);
        graphics.fill(x, y, x + 16, y + 16, SLOT_COLOR);
    }

    private static void drawBar(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.fill(x - 1, y - 1, x + width + 1, y + height + 1, SLOT_BORDER_COLOR);
        graphics.fill(x, y, x + width, y + height, BAR_BACKGROUND_COLOR);
    }

    /** Tiles the fluid's still texture, tinted, from {@code top} down to the bottom of the tank bar. */
    private void drawFluid(GuiGraphics graphics, Fluid fluid, int x, int top, int height) {
        IClientFluidTypeExtensions extensions = IClientFluidTypeExtensions.of(fluid);
        TextureAtlasSprite sprite = minecraft.getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(extensions.getStillTexture());
        int tint = extensions.getTintColor();
        float r = (tint >> 16 & 0xFF) / 255F;
        float g = (tint >> 8 & 0xFF) / 255F;
        float b = (tint & 0xFF) / 255F;
        graphics.enableScissor(x, top, x + BAR_WIDTH, top + height);
        for (int tileY = top + height - BAR_WIDTH; tileY > top - BAR_WIDTH; tileY -= BAR_WIDTH) {
            graphics.blit(x, tileY, 0, BAR_WIDTH, BAR_WIDTH, sprite, r, g, b, 1F);
        }
        graphics.disableScissor();
    }

    private static int scaled(int value, int max, int size) {
        return max <= 0 ? 0 : (int) Math.min(size, (long) value * size / max);
    }
}
