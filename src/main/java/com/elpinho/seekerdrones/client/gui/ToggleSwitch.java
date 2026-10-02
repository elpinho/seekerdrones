package com.elpinho.seekerdrones.client.gui;

import java.util.function.BooleanSupplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * A two-position switch: the knob sits right when on, with the "on" word on the left, and left when off, with the
 * "off" word on the right. It looks different from a button, so it doesn't read as a second action.
 */
public class ToggleSwitch extends KitWidget {
    private static final int KNOB_WIDTH = 20;

    private final BooleanSupplier on;
    private final Component onLabel;
    private final Component offLabel;
    private final Runnable onPress;

    public ToggleSwitch(int x, int y, int width, int height, BooleanSupplier on, Component onLabel, Component offLabel, Runnable onPress) {
        super(x, y, width, height);
        this.on = on;
        this.onLabel = onLabel;
        this.offLabel = offLabel;
        this.onPress = onPress;
    }

    @Override
    protected void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        boolean isOn = on.getAsBoolean();
        graphics.blitSprite(Kit.TOGGLE, getX(), getY(), width, height);
        int knobX = isOn ? getX() + width - 2 - KNOB_WIDTH : getX() + 2;
        graphics.blitSprite(Kit.TOGGLE_KNOB, knobX, getY() + 2, KNOB_WIDTH, height - 4);
        var font = Minecraft.getInstance().font;
        String label = Kit.upper(isOn ? onLabel : offLabel);
        int labelLeft = isOn ? getX() + 2 : knobX + KNOB_WIDTH;
        int labelRight = isOn ? knobX : getX() + width - 2;
        float textX = (labelLeft + labelRight - Kit.smallWidth(font, label)) / 2F;
        Kit.smallText(graphics, font, label, textX, getY() + (height - 6) / 2F, Kit.WHITE, true);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (active && visible && button == 0 && isMouseOver(mouseX, mouseY)) {
            playDownSound(Minecraft.getInstance().getSoundManager());
            onPress.run();
            return true;
        }
        return false;
    }
}
