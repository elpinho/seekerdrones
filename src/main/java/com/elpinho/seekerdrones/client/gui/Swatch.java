package com.elpinho.seekerdrones.client.gui;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FastColor;
import net.minecraft.world.item.DyeColor;

/**
 * One of the 16 dye colors of a drone's color palette (the Programming Station's and the Drone Remote's Identity
 * tab). The selected one has a white outline and the hovered one an accent outline.
 */
public class Swatch extends KitWidget {
    public static final int SIZE = 12;
    /** Distance between the left edges of neighboring swatches. */
    public static final int PITCH = 14;

    private final DyeColor color;
    private final Supplier<DyeColor> selected;
    private final IntSupplier accent;
    private final Consumer<DyeColor> onPick;

    /**
     * @param selected the color currently set, or null while there is nothing to edit (the swatches then ignore clicks)
     * @param onPick   called with this swatch's color when it is clicked
     */
    public Swatch(int x, int y, DyeColor color, Supplier<DyeColor> selected, IntSupplier accent, Consumer<DyeColor> onPick) {
        super(x, y, SIZE, SIZE);
        this.color = color;
        this.selected = selected;
        this.accent = accent;
        this.onPick = onPick;
        tooltip(() -> List.of(Component.translatable("color.minecraft." + color.getName())));
    }

    @Nullable
    private DyeColor current() {
        return selected.get();
    }

    @Override
    protected void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (current() == color) {
            Kit.outline(graphics, getX() - 2, getY() - 2, width + 4, height + 4, Kit.WHITE);
        }
        graphics.fill(getX(), getY(), getX() + width, getY() + height, 0xFF000000);
        graphics.fill(getX() + 1, getY() + 1, getX() + width - 1, getY() + height - 1, FastColor.ARGB32.opaque(color.getTextureDiffuseColor()));
        if (isHovered()) {
            Kit.outline(graphics, getX(), getY(), width, height, accent.getAsInt());
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        DyeColor current = current();
        if (visible && active && button == 0 && isMouseOver(mouseX, mouseY) && current != null) {
            playDownSound(Minecraft.getInstance().getSoundManager());
            if (current != color) {
                onPick.accept(color);
            }
            return true;
        }
        return false;
    }
}
