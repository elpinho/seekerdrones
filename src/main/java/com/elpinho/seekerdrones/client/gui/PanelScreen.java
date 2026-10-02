package com.elpinho.seekerdrones.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Base of the kit's screens without a container (the Charging Station and drone status screens): a centered frame with
 * side tabs. They don't pause the game.
 */
public abstract class PanelScreen extends Screen {
    protected final SideTabs sideTabs = new SideTabs();
    protected final int imageWidth;
    protected final int imageHeight;
    protected int leftPos;
    protected int topPos;

    protected PanelScreen(Component title, int width, int height) {
        super(title);
        this.imageWidth = width;
        this.imageHeight = height;
    }

    @Override
    protected void init() {
        leftPos = (width - imageWidth) / 2;
        topPos = (height - imageHeight) / 2;
        sideTabs.layout(leftPos + imageWidth, topPos);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        sideTabs.layout(leftPos + imageWidth, topPos);
        // Screen.render draws the background first, then the widgets.
        super.render(graphics, mouseX, mouseY, partialTick);
        renderForeground(graphics, mouseX, mouseY, partialTick);
        sideTabs.renderTooltip(mouseX, mouseY);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        sideTabs.render(graphics, mouseX, mouseY);
        Kit.frame(graphics, leftPos, topPos, imageWidth, imageHeight);
        renderContents(graphics, mouseX, mouseY, partialTick);
    }

    /** Draws the screen's displays and text over the frame, under the widgets. */
    protected abstract void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTick);

    /** Draws anything that goes over the widgets. */
    protected void renderForeground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {}

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return sideTabs.mouseClicked(mouseX, mouseY, button) || super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
