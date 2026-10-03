package com.elpinho.seekerdrones.client.gui;

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

import javax.annotation.Nullable;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

/**
 * A slider for a whole number, on a display: a dark track filled in the accent color up to a knob. Dragging only moves
 * the knob and reports each value to {@code onMove}. The value is set once, through {@code onSet}, when the mouse is
 * released, so a drag sends one edit. The screen forwards drags and releases with {@link #drag} and {@link #release}.
 */
public class KitSlider extends KitWidget {
    private final IntSupplier min;
    private final IntSupplier max;
    private final IntSupplier value;
    private final IntSupplier color;
    private final IntConsumer onMove;
    private final IntConsumer onSet;
    @Nullable
    private Integer dragValue;

    public KitSlider(int x, int y, int width, int height, IntSupplier min, IntSupplier max, IntSupplier value, IntSupplier color,
            IntConsumer onMove, IntConsumer onSet) {
        super(x, y, width, height);
        this.min = min;
        this.max = max;
        this.value = value;
        this.color = color;
        this.onMove = onMove;
        this.onSet = onSet;
    }

    /** The value shown: the one being dragged to, or the current one. */
    public int shownValue() {
        return dragValue != null ? dragValue : Mth.clamp(value.getAsInt(), min.getAsInt(), Math.max(min.getAsInt(), max.getAsInt()));
    }

    public boolean isDragging() {
        return dragValue != null;
    }

    @Override
    protected void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.blitSprite(Kit.FIELD, getX(), getY(), width, height);
        int low = min.getAsInt();
        int high = max.getAsInt();
        float fraction = high > low ? (shownValue() - low) / (float) (high - low) : 1;
        int inner = width - 2;
        int filled = Math.round(inner * fraction);
        int accent = color.getAsInt();
        if (filled > 0) {
            graphics.fill(getX() + 1, getY() + 1, getX() + 1 + filled, getY() + height - 1, accent);
            // The lower half a little darker, like the mockup's gradient.
            graphics.fill(getX() + 1, getY() + height / 2, getX() + 1 + filled, getY() + height - 1, 0x40000000);
        }
        graphics.blitSprite(Kit.SLIDER_KNOB, getX() + 1 + filled - 2, getY() - 2, 4, height + 4);
    }

    private int valueAt(double mouseX) {
        int low = min.getAsInt();
        int high = Math.max(low, max.getAsInt());
        double fraction = Mth.clamp((mouseX - getX() - 1) / (width - 2), 0, 1);
        return low + (int) Math.round(fraction * (high - low));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (active && visible && button == 0 && isMouseOver(mouseX, mouseY)) {
            dragValue = valueAt(mouseX);
            onMove.accept(dragValue);
            return true;
        }
        return false;
    }

    public void drag(double mouseX) {
        if (dragValue != null) {
            int next = valueAt(mouseX);
            if (next != dragValue) {
                dragValue = next;
                onMove.accept(next);
            }
        }
    }

    public void release() {
        if (dragValue != null) {
            int set = dragValue;
            dragValue = null;
            onSet.accept(set);
        }
    }
}
