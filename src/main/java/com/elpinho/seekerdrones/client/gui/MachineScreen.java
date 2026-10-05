package com.elpinho.seekerdrones.client.gui;

import java.util.List;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;

/**
 * Base of the kit's machine screens with a container: the frame, a slot box under every slot, the side tabs and the
 * vanilla-colored title and inventory labels. Subclasses add their displays in {@link #renderContents} and their
 * gauges, strips and controls as widgets.
 */
public abstract class MachineScreen<M extends AbstractContainerMenu> extends AbstractContainerScreen<M> {
    protected final SideTabs sideTabs = new SideTabs();

    protected MachineScreen(M menu, Inventory inventory, Component title, int width, int height, int inventoryY) {
        super(menu, inventory, title);
        this.imageWidth = width;
        this.imageHeight = height;
        this.inventoryLabelY = inventoryY - 11;
    }

    @Override
    protected void init() {
        super.init();
        sideTabs.layout(leftPos + imageWidth, topPos);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Tabs can unfold, so they are laid out every frame.
        sideTabs.layout(leftPos + imageWidth, topPos);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
        sideTabs.renderTooltip(mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        sideTabs.render(graphics, mouseX, mouseY);
        renderFrame(graphics);
        renderContents(graphics, partialTick, mouseX, mouseY);
        for (Slot slot : menu.slots) {
            // Inactive slots (e.g. in a folded Upgrades tab) aren't drawn.
            if (slot.isActive()) {
                renderSlotBackground(graphics, slot, leftPos + slot.x - 1, topPos + slot.y - 1);
            }
        }
    }

    /** Draws the frame. Screens that aren't a plain rectangle override this. */
    protected void renderFrame(GuiGraphics graphics) {
        Kit.frame(graphics, leftPos, topPos, imageWidth, imageHeight);
    }

    /** Draws the screen's displays and decorations over the frame, under the slots and widgets. */
    protected abstract void renderContents(GuiGraphics graphics, float partialTick, int mouseX, int mouseY);

    /** Draws a slot's box at its top-left corner. Subclasses can add ghosts. */
    protected void renderSlotBackground(GuiGraphics graphics, Slot slot, int x, int y) {
        Kit.slot(graphics, x, y);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, titleLabelX, titleLabelY, Kit.LABEL, false);
        graphics.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, Kit.LABEL, false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return sideTabs.mouseClicked(mouseX, mouseY, button) || super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    protected boolean hasClickedOutside(double mouseX, double mouseY, int guiLeft, int guiTop, int button) {
        return super.hasClickedOutside(mouseX, mouseY, guiLeft, guiTop, button) && !sideTabs.isMouseOver(mouseX, mouseY);
    }

    /** The side tabs' areas, for JEI's exclusion zones. */
    public List<Rect2i> getSideTabAreas() {
        return sideTabs.areas();
    }
}
