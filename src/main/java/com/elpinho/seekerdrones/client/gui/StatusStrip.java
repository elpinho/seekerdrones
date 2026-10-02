package com.elpinho.seekerdrones.client.gui;

import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * The kit's status strip: one line on a display with a status light. Text that doesn't fit scrolls. The full text is
 * also the tooltip, so it can be read at once.
 */
public class StatusStrip extends KitWidget {
    public static final int HEIGHT = 11;

    /** What the strip shows: the light, whether it blinks (for problems that need the player), and the text. */
    public record Status(Kit.Light light, boolean blink, Component text) {}

    private final Supplier<Status> status;

    public StatusStrip(int x, int y, int width, Supplier<Status> status) {
        super(x, y, width, HEIGHT);
        this.status = status;
        tooltip(() -> java.util.List.of(this.status.get().text()));
    }

    @Override
    protected void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        Status current = status.get();
        Kit.display(graphics, getX(), getY(), width, height);
        Kit.light(graphics, current.light(), getX() + 3, getY() + 3, current.blink());
        Kit.scrollingText(graphics, Minecraft.getInstance().font, current.text(), getX() + 10, getY() + 2, width - 13, 8,
                Kit.DISPLAY_TEXT, false, false);
    }
}
