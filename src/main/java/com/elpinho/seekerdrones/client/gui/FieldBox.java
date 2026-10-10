package com.elpinho.seekerdrones.client.gui;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * A text field on a display: a dark box with the text inset, an accent border while focused or selected, and a
 * tooltip built when hovered. It commits its value on Enter and when it loses focus.
 */
public class FieldBox extends EditBox {
    /** Text color of an input the screen rejected (RGB). */
    public static final int INVALID_TEXT = 0xFF6060;
    /** The color of the "!" that marks a warning. */
    public static final int WARN_COLOR = 0xFFFBBF24;

    private static final int PADDING_X = 3;

    private final Font font;
    private final IntSupplier accent;
    private final Consumer<FieldBox> onCommit;
    /** Drawn with a dashed border while empty: an empty target row. */
    public boolean dashed;
    public BooleanSupplier selected = () -> false;
    /** Shows an amber "!" at the right end: an ignored target entry. */
    public BooleanSupplier warning = () -> false;
    public Supplier<List<Component>> tooltipLines = List::of;

    /**
     * @param accent   the color of the border while focused or selected
     * @param onCommit called on Enter and when the field loses focus
     */
    public FieldBox(Font font, int x, int y, int width, int height, Component message, IntSupplier accent, Consumer<FieldBox> onCommit) {
        super(font, x, y, width, height, message);
        this.font = font;
        this.accent = accent;
        this.onCommit = onCommit;
        setBordered(false);
        setTextColor(Kit.DISPLAY_TEXT & 0xFFFFFF);
    }

    private int paddingY() {
        return (height - 7) / 2;
    }

    public void showValue(String value) {
        if (!getValue().equals(value)) {
            setValue(value);
        }
    }

    public void setInvalid(boolean invalid) {
        setTextColor(invalid ? INVALID_TEXT : Kit.DISPLAY_TEXT & 0xFFFFFF);
    }

    @Override
    public int getInnerWidth() {
        // EditBox can ask before this box's fields are set.
        return width - 2 * PADDING_X - (warning != null && warning.getAsBoolean() ? 6 : 0);
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!isVisible()) {
            return;
        }
        boolean empty = dashed && getValue().isEmpty() && !isFocused();
        graphics.blitSprite(empty ? Kit.FIELD_EMPTY : Kit.FIELD, getX(), getY(), width, height);
        if (isFocused() || selected.getAsBoolean()) {
            Kit.outline(graphics, getX(), getY(), width, height, accent.getAsInt());
        }
        // EditBox draws its text at its own corner without a border, so shift it inside the box.
        graphics.pose().pushPose();
        graphics.pose().translate(PADDING_X, paddingY(), 0);
        super.renderWidget(graphics, mouseX - PADDING_X, mouseY - paddingY(), partialTick);
        graphics.pose().popPose();
        if (warning.getAsBoolean()) {
            graphics.drawString(font, "!", getX() + width - 6, getY() + paddingY(), WARN_COLOR, false);
        }
        if (isHovered() && !isFocused()) {
            KitWidget.showTooltip(tooltipLines.get());
        }
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        super.onClick(mouseX - PADDING_X, mouseY);
    }

    @Override
    public void setFocused(boolean focused) {
        boolean wasFocused = isFocused();
        super.setFocused(focused);
        if (wasFocused && !focused) {
            onCommit.accept(this);
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (isFocused() && (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)) {
            onCommit.accept(this);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
