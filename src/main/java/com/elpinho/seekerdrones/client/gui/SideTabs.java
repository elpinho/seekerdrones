package com.elpinho.seekerdrones.client.gui;

import java.util.ArrayList;
import java.util.List;

import com.elpinho.seekerdrones.machine.UpgradeSlots;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

/**
 * The column of {@link SideTab}s on the right edge of a screen's frame. Visible tabs stack from the top. They are drawn
 * before the frame, so the frame's edge stays in front of them.
 */
public class SideTabs {
    /** How far a tab tucks under the frame. {@link UpgradeSlots} places the Upgrades tab's slots from this and FIRST_Y. */
    public static final int OVERLAP = UpgradeSlots.TAB_OVERLAP;
    private static final int FIRST_Y = UpgradeSlots.TAB_FIRST_Y;
    private static final int GAP = 2;

    private final List<SideTab> tabs = new ArrayList<>();

    public SideTab add(SideTab tab) {
        tabs.add(tab);
        return tab;
    }

    /** Positions the visible tabs along the frame's right edge ({@code frameRight} is the frame's x + width). */
    public void layout(int frameRight, int frameTop) {
        int y = frameTop + FIRST_Y;
        for (SideTab tab : tabs) {
            if (!tab.isVisible()) {
                continue;
            }
            tab.x = frameRight - OVERLAP;
            tab.y = y;
            y += tab.currentHeight() + GAP;
        }
    }

    /** Draws the tabs. Call before drawing the frame. */
    public void render(GuiGraphics graphics, int mouseX, int mouseY) {
        for (SideTab tab : tabs) {
            if (tab.isVisible()) {
                tab.render(graphics, mouseX, mouseY);
            }
        }
    }

    /** Shows the hovered tab's tooltip. Call after everything else is drawn. */
    public void renderTooltip(int mouseX, int mouseY) {
        for (SideTab tab : tabs) {
            if (tab.isVisible() && tab.isMouseOver(mouseX, mouseY)) {
                KitWidget.showTooltip(tab.tooltipLines());
                return;
            }
        }
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return false;
        }
        for (SideTab tab : tabs) {
            if (tab.isVisible() && tab.isMouseOver(mouseX, mouseY)) {
                if (tab.click(mouseX, mouseY)) {
                    Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
                    return true;
                }
                // An unused click on a panel's body goes on to the screen, e.g. to slots in the panel.
                return !tab.passesUnusedClicks();
            }
        }
        return false;
    }

    public boolean isMouseOver(double mouseX, double mouseY) {
        return tabs.stream().anyMatch(tab -> tab.isVisible() && tab.isMouseOver(mouseX, mouseY));
    }

    /** The areas the tabs cover outside the frame, so JEI keeps its overlays clear of them. */
    public List<Rect2i> areas() {
        List<Rect2i> areas = new ArrayList<>();
        for (SideTab tab : tabs) {
            if (tab.isVisible()) {
                areas.add(new Rect2i(tab.x, tab.y, tab.currentWidth(), tab.currentHeight()));
            }
        }
        return areas;
    }
}
