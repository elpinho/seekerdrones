package com.elpinho.seekerdrones.client.gui;

import java.util.function.BooleanSupplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * A kit button: the gray panel button, or a dark one for use on a display. What it shows (text, an icon) is drawn by
 * a {@link SideTab.Drawer} at the button's top-left corner.
 */
public class KitButton extends KitWidget {
    public enum Style {
        PANEL(Kit.BUTTON, Kit.BUTTON_HIGHLIGHTED, Kit.BUTTON_DISABLED),
        DISPLAY(Kit.DISPLAY_BUTTON, Kit.DISPLAY_BUTTON_HIGHLIGHTED, Kit.DISPLAY_BUTTON);

        private final ResourceLocation sprite;
        private final ResourceLocation highlighted;
        private final ResourceLocation disabled;

        Style(ResourceLocation sprite, ResourceLocation highlighted, ResourceLocation disabled) {
            this.sprite = sprite;
            this.highlighted = highlighted;
            this.disabled = disabled;
        }
    }

    private final Style style;
    private final SideTab.Drawer content;
    private final Runnable onPress;
    private BooleanSupplier enabled = () -> true;

    public KitButton(int x, int y, int width, int height, Style style, SideTab.Drawer content, Runnable onPress) {
        super(x, y, width, height);
        this.style = style;
        this.content = content;
        this.onPress = onPress;
    }

    public KitButton enabledWhen(BooleanSupplier enabled) {
        this.enabled = enabled;
        return this;
    }

    public boolean isEnabled() {
        return enabled.getAsBoolean();
    }

    @Override
    protected void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        boolean on = isEnabled();
        graphics.blitSprite(!on ? style.disabled : isHovered() ? style.highlighted : style.sprite, getX(), getY(), width, height);
        if (!on) {
            graphics.setColor(1, 1, 1, 0.45F);
        }
        content.draw(graphics, getX(), getY(), mouseX, mouseY);
        graphics.setColor(1, 1, 1, 1);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (visible && button == 0 && isMouseOver(mouseX, mouseY) && isEnabled()) {
            playDownSound(Minecraft.getInstance().getSoundManager());
            onPress.run();
            return true;
        }
        return false;
    }
}
