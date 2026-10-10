package com.elpinho.seekerdrones.client.gui;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * A tab over an editor display (the Programming Station's and the Drone Remote's). The selected one joins the display
 * and has the accent on its top edge. What it shows is drawn by a {@link SideTab.Drawer} at the tab's top-left corner.
 */
public class EditorTab extends KitWidget {
    public static final int WIDTH = 24;
    public static final int HEIGHT = 13;
    /** Distance between the left edges of neighboring tabs. */
    public static final int PITCH = 25;

    private static final ResourceLocation TAB = Kit.sprite("programming/tab");
    private static final ResourceLocation TAB_HIGHLIGHTED = Kit.sprite("programming/tab_highlighted");
    private static final ResourceLocation TAB_SELECTED = Kit.sprite("programming/tab_selected");

    private final BooleanSupplier selected;
    private final SideTab.Drawer icon;
    private final IntSupplier accent;
    private final Runnable onSelect;

    /**
     * @param selected whether this is the tab being shown
     * @param accent   the color of the selected tab's top edge
     * @param onSelect called when an unselected tab is clicked
     */
    public EditorTab(int x, int y, BooleanSupplier selected, SideTab.Drawer icon, Supplier<List<Component>> tooltip, IntSupplier accent,
            Runnable onSelect) {
        super(x, y, WIDTH, HEIGHT);
        this.selected = selected;
        this.icon = icon;
        this.accent = accent;
        this.onSelect = onSelect;
        tooltip(tooltip);
    }

    @Override
    protected void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int x = getX();
        int y = getY();
        if (selected.getAsBoolean()) {
            // One row taller, over the display's top edge, so it joins the display.
            graphics.blitSprite(TAB_SELECTED, x, y, width, height);
            graphics.fill(x + 1, y + 1, x + width - 1, y + 2, accent.getAsInt());
        } else {
            graphics.blitSprite(isHovered() ? TAB_HIGHLIGHTED : TAB, x, y, width, height - 1);
        }
        icon.draw(graphics, x, y, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!visible || button != 0 || !isMouseOver(mouseX, mouseY)) {
            return false;
        }
        if (!selected.getAsBoolean()) {
            playDownSound(Minecraft.getInstance().getSoundManager());
            onSelect.run();
        }
        return true;
    }
}
