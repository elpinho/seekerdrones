package com.elpinho.seekerdrones.client.gui;

import java.util.List;
import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * A kit widget whose tooltip is built every frame while hovered, so it always shows current values. Display-only
 * widgets (gauges, bars, strips) ignore clicks.
 */
public abstract class KitWidget extends AbstractWidget {
    /** Tooltip lines wider than this wrap. */
    private static final int TOOLTIP_WIDTH = 200;

    private Supplier<List<Component>> tooltip = List::of;

    protected KitWidget(int x, int y, int width, int height) {
        super(x, y, width, height, Component.empty());
    }

    public KitWidget tooltip(Supplier<List<Component>> tooltip) {
        this.tooltip = tooltip;
        return this;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        draw(graphics, mouseX, mouseY, partialTick);
        if (isHovered()) {
            showTooltip(tooltip.get());
        }
    }

    protected abstract void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick);

    /** Shows the lines as the screen's tooltip this frame, wrapping long ones. */
    public static void showTooltip(List<Component> lines) {
        Screen screen = Minecraft.getInstance().screen;
        if (lines.isEmpty() || screen == null) {
            return;
        }
        var font = Minecraft.getInstance().font;
        List<FormattedCharSequence> wrapped = lines.stream()
                .flatMap(line -> font.split(line, TOOLTIP_WIDTH).stream())
                .toList();
        screen.setTooltipForNextRenderPass(wrapped);
    }

    /** Display-only by default. Interactive widgets override this. */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return false;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
