package com.elpinho.seekerdrones.client.gui;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.elpinho.seekerdrones.energy.EnergyFormat;
import com.elpinho.seekerdrones.energy.EnergyUnit;
import com.elpinho.seekerdrones.energy.MekanismEnergy;
import com.elpinho.seekerdrones.network.EnergyUnitPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * A tab on the right edge of a machine screen, Mekanism-style (see {@link SideTabs}). The tab tucks
 * {@link SideTabs#OVERLAP} pixels under the frame. A tab with a panel unfolds into it when clicked.
 */
public class SideTab {
    public static final int WIDTH = 24;
    /** The unit under the energy tab's bolt is smaller than the kit's small text, so "AUTO" sits well inside the tab. */
    private static final float UNIT_LABEL_SCALE = Kit.SMALL * 0.75F;

    /** Draws something at the given top-left position. */
    @FunctionalInterface
    public interface Drawer {
        void draw(GuiGraphics graphics, int x, int y, int mouseX, int mouseY);
    }

    final int height;
    private final Drawer icon;
    private Supplier<List<Component>> tooltip = List::of;
    private BooleanSupplier visible = () -> true;
    private Runnable onClick = () -> {};

    /** Handles a click on an unfolded panel's body, at the panel's top-left position. Returns whether it was used. */
    @FunctionalInterface
    public interface PanelClick {
        boolean click(double mouseX, double mouseY, int x, int y);
    }

    // An optional panel the tab unfolds into.
    int panelWidth;
    int panelHeight;
    private Drawer panel;
    boolean expanded;
    @Nullable
    private PanelClick panelClick;
    private int headerHeight;
    private boolean passUnusedClicks;

    // Laid out by SideTabs.
    int x;
    int y;

    public SideTab(int height, Drawer icon) {
        this.height = height;
        this.icon = icon;
    }

    public SideTab tooltip(Supplier<List<Component>> tooltip) {
        this.tooltip = tooltip;
        return this;
    }

    public SideTab visibleWhen(BooleanSupplier visible) {
        this.visible = visible;
        return this;
    }

    public SideTab onClick(Runnable onClick) {
        this.onClick = onClick;
        return this;
    }

    /** Clicking the tab unfolds it into a framed panel of this size. Clicking the panel folds it again. */
    public SideTab panel(int width, int height, Drawer panel) {
        this.panelWidth = width;
        this.panelHeight = height;
        this.panel = panel;
        return this;
    }

    /**
     * Gives the panel controls: clicks below the top {@code headerHeight} pixels go to {@code click} instead of folding
     * the panel. Only the header folds it.
     */
    public SideTab panelClick(int headerHeight, PanelClick click) {
        this.headerHeight = headerHeight;
        this.panelClick = click;
        return this;
    }

    /**
     * Lets clicks on the panel's body that {@link #panelClick} doesn't use go on to the screen, so slots placed in the
     * panel (the Upgrades tab) take them.
     */
    public SideTab passUnusedClicks() {
        this.passUnusedClicks = true;
        return this;
    }

    boolean passesUnusedClicks() {
        return passUnusedClicks && isExpanded();
    }

    /** The tab's (or its unfolded panel's) left edge, as laid out for this frame. */
    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public boolean isVisible() {
        return visible.getAsBoolean();
    }

    public boolean isExpanded() {
        return expanded && panel != null;
    }

    public void setExpanded(boolean expanded) {
        this.expanded = expanded;
    }

    int currentWidth() {
        return isExpanded() ? panelWidth : WIDTH;
    }

    int currentHeight() {
        return isExpanded() ? panelHeight : height;
    }

    boolean isMouseOver(double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + currentWidth() && mouseY >= y && mouseY < y + currentHeight();
    }

    void render(GuiGraphics graphics, int mouseX, int mouseY) {
        if (isExpanded()) {
            Kit.frame(graphics, x, y, panelWidth, panelHeight);
            panel.draw(graphics, x, y, mouseX, mouseY);
            return;
        }
        graphics.blitSprite(isMouseOver(mouseX, mouseY) ? Kit.SIDE_TAB_HIGHLIGHTED : Kit.SIDE_TAB, x, y, WIDTH, height);
        icon.draw(graphics, x, y, mouseX, mouseY);
    }

    List<Component> tooltipLines() {
        return tooltip.get();
    }

    /** Returns whether the click did something (and should play the click sound). */
    boolean click(double mouseX, double mouseY) {
        if (isExpanded() && panelClick != null && mouseY >= y + headerHeight) {
            return panelClick.click(mouseX, mouseY, x, y);
        }
        if (panel != null) {
            expanded = !expanded;
        }
        onClick.run();
        return true;
    }

    /**
     * The energy unit tab (DESIGN.md section 5.4): a bolt over the current unit. Clicking cycles Auto, FE and Joules,
     * and the server saves the choice for the player. Hidden without Mekanism, since FE is then the only unit.
     */
    public static SideTab energyUnit() {
        return new SideTab(25, (graphics, x, y, mouseX, mouseY) -> {
            graphics.blitSprite(Kit.ICON_ENERGY, x + 10, y + 4, 6, 8);
            Component label = Component.translatable(EnergyFormat.clientUnit().getTranslationKey());
            Kit.scaledTextCentered(graphics, Minecraft.getInstance().font, Component.literal(Kit.upper(label)), x + 13, y + 15, Kit.LABEL,
                    UNIT_LABEL_SCALE);
        })
                .visibleWhen(MekanismEnergy::isLoaded)
                .tooltip(() -> {
                    EnergyUnit unit = EnergyFormat.clientUnit();
                    return List.of(Component.translatable("screen.seekerdrones.energy_unit.tooltip",
                            Component.translatable(unit.getTranslationKey() + ".name")));
                })
                .onClick(() -> {
                    EnergyUnit next = EnergyFormat.clientUnit().next();
                    EnergyFormat.setClientUnit(next);
                    PacketDistributor.sendToServer(new EnergyUnitPayload(next));
                });
    }
}
