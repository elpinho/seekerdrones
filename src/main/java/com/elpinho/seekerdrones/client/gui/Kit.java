package com.elpinho.seekerdrones.client.gui;

import java.util.Locale;

import com.elpinho.seekerdrones.SeekerDrones;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.Util;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

/**
 * The machine GUI kit (IMPROVEMENTS.md, "Machine GUI style pass"): the shared sprites, colors and drawing helpers.
 * Every part is a GUI sprite in {@code textures/gui/sprites} whose {@code .mcmeta} sets its scaling, so the art can be
 * repainted without touching the layouts. The placeholder sprites come from {@code scripts/textures/generate_gui_sprites.py}.
 */
public final class Kit {
    public static final ResourceLocation FRAME = sprite("frame");
    public static final ResourceLocation DISPLAY = sprite("display");
    public static final ResourceLocation SLOT = sprite("slot");
    public static final ResourceLocation SLOT_LARGE = sprite("slot_large");
    public static final ResourceLocation CELL = sprite("cell");
    public static final ResourceLocation GAUGE = sprite("gauge");
    public static final ResourceLocation GAUGE_GLASS = sprite("gauge_glass");
    public static final ResourceLocation FILL_ENERGY_VERTICAL = sprite("fill/energy_vertical");
    public static final ResourceLocation FILL_ENERGY_HORIZONTAL = sprite("fill/energy_horizontal");
    public static final ResourceLocation FILL_HEALTH_HORIZONTAL = sprite("fill/health_horizontal");
    public static final ResourceLocation SIDE_TAB = sprite("side_tab");
    public static final ResourceLocation SIDE_TAB_HIGHLIGHTED = sprite("side_tab_highlighted");
    public static final ResourceLocation BUTTON = sprite("button");
    public static final ResourceLocation BUTTON_HIGHLIGHTED = sprite("button_highlighted");
    public static final ResourceLocation BUTTON_DISABLED = sprite("button_disabled");
    public static final ResourceLocation PILL = sprite("pill");
    public static final ResourceLocation CHIP = sprite("chip");
    public static final ResourceLocation TOGGLE = sprite("toggle");
    public static final ResourceLocation TOGGLE_KNOB = sprite("toggle_knob");
    public static final ResourceLocation SCROLLBAR = sprite("scrollbar");
    public static final ResourceLocation SCROLLBAR_THUMB = sprite("scrollbar_thumb");
    public static final ResourceLocation ICON_ENERGY = sprite("icon/energy");
    public static final ResourceLocation ICON_HEALTH = sprite("icon/health");

    // Text colors.
    /** Labels on the gray panel, like vanilla's container titles. */
    public static final int LABEL = 0xFF404040;
    /** Text on a display. */
    public static final int DISPLAY_TEXT = 0xFF8FE5D6;
    public static final int DISPLAY_TEXT_DIM = 0xFF55807B;
    public static final int DISPLAY_BACKGROUND = 0xFF0C1215;
    public static final int WHITE = 0xFFFFFFFF;
    public static final int CHIP_TEXT = 0xFFD8DDE3;
    /** Scale notches on gauges and bars. */
    public static final int NOTCH = 0x8CFFFFFF;

    /** "Small" text is the normal font drawn at this scale (the mockup's smaller labels). */
    public static final float SMALL = 0.75F;

    /** How long scrolling text waits at each end, and its speed in pixels per second. */
    private static final double SCROLL_PAUSE_SECONDS = 1.2;
    private static final double SCROLL_SPEED = 18;

    private Kit() {}

    public static ResourceLocation sprite(String path) {
        return ResourceLocation.fromNamespaceAndPath(SeekerDrones.MODID, path);
    }

    // --- Panels ---

    public static void frame(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.blitSprite(FRAME, x, y, width, height);
    }

    public static void display(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.blitSprite(DISPLAY, x, y, width, height);
    }

    /** An 18x18 slot box whose item sits at ({@code x + 1}, {@code y + 1}). */
    public static void slot(GuiGraphics graphics, int x, int y) {
        graphics.blitSprite(SLOT, x, y, 18, 18);
    }

    /**
     * Draws a sprite with partly transparent pixels (glass, glows, sweeps). Sprite blits don't turn blending on by
     * themselves, so without this those pixels come out opaque.
     */
    public static void blitTranslucent(GuiGraphics graphics, ResourceLocation sprite, int x, int y, int width, int height) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        graphics.blitSprite(sprite, x, y, width, height);
        RenderSystem.disableBlend();
    }

    // --- Status lights ---

    /** The status light colors: green working or ready, amber waiting, red needs the player, gray idle. */
    public enum Light {
        OK("ok"),
        WARN("warn"),
        BAD("bad"),
        IDLE("idle");

        private final ResourceLocation sprite;

        Light(String name) {
            this.sprite = Kit.sprite("light/" + name);
        }
    }

    /** A 4x4 lamp at (x, y), with its glow drawn one pixel around it. Blinking lights flash once a second. */
    public static void light(GuiGraphics graphics, Light light, int x, int y, boolean blink) {
        if (blink && Util.getMillis() / 500 % 2 == 1) {
            graphics.setColor(1, 1, 1, 0.25F);
            blitTranslucent(graphics, light.sprite, x - 1, y - 1, 6, 6);
            graphics.setColor(1, 1, 1, 1);
            return;
        }
        blitTranslucent(graphics, light.sprite, x - 1, y - 1, 6, 6);
    }

    /** A state pill: a dark rounded box with a light and the text in small capitals. Returns its width. */
    public static int pillWidth(Font font, Component text) {
        return 10 + smallWidth(font, upper(text)) + 4;
    }

    public static void pill(GuiGraphics graphics, Font font, int x, int y, Light light, Component text) {
        String label = upper(text);
        int width = pillWidth(font, text);
        graphics.blitSprite(PILL, x, y, width, 11);
        light(graphics, light, x + 3, y + 3, false);
        smallText(graphics, font, label, x + 10, y + 3, WHITE, true);
    }

    /** Pills, strips and the toggle show their words in capitals, as in the mockup. */
    public static String upper(Component text) {
        return text.getString().toUpperCase(Locale.ROOT);
    }

    // --- Text ---

    public static int smallWidth(Font font, String text) {
        return Mth.ceil(font.width(text) * SMALL);
    }

    public static int smallWidth(Font font, Component text) {
        return Mth.ceil(font.width(text) * SMALL);
    }

    public static void smallText(GuiGraphics graphics, Font font, String text, float x, float y, int color, boolean shadow) {
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(x, y, 0);
        pose.scale(SMALL, SMALL, 1);
        graphics.drawString(font, text, 0, 0, color, shadow);
        pose.popPose();
    }

    public static void smallText(GuiGraphics graphics, Font font, Component text, float x, float y, int color, boolean shadow) {
        smallText(graphics, font, text.getVisualOrderText(), x, y, color, shadow);
    }

    public static void smallText(GuiGraphics graphics, Font font, FormattedCharSequence text, float x, float y, int color, boolean shadow) {
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(x, y, 0);
        pose.scale(SMALL, SMALL, 1);
        graphics.drawString(font, text, 0, 0, color, shadow);
        pose.popPose();
    }

    public static void smallTextCentered(GuiGraphics graphics, Font font, Component text, int centerX, float y, int color) {
        scaledTextCentered(graphics, font, text, centerX, y, color, SMALL);
    }

    public static void scaledTextCentered(GuiGraphics graphics, Font font, Component text, int centerX, float y, int color, float scale) {
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(centerX - font.width(text) * scale / 2, y, 0);
        pose.scale(scale, scale, 1);
        graphics.drawString(font, text, 0, 0, color, false);
        pose.popPose();
    }

    /**
     * Text in a box {@code width} wide starting at (x, y). Text that doesn't fit scrolls back and forth instead of
     * being cut off (clipped to the box, {@code height} pixels tall).
     */
    public static void scrollingText(GuiGraphics graphics, Font font, Component text, int x, int y, int width, int height, int color,
            boolean shadow, boolean small) {
        scrollingText(graphics, font, text, x, y, width, height, color, shadow, small ? SMALL : 1);
    }

    /** {@link #scrollingText} at any text scale. */
    public static void scrollingText(GuiGraphics graphics, Font font, Component text, int x, int y, int width, int height, int color,
            boolean shadow, float scale) {
        float textWidth = font.width(text) * scale;
        if (textWidth <= width) {
            draw(graphics, font, text, x, y, color, shadow, scale);
            return;
        }
        float overflow = textWidth - width + 2;
        double travel = overflow / SCROLL_SPEED;
        double cycle = 2 * (travel + SCROLL_PAUSE_SECONDS);
        double t = Util.getMillis() / 1000.0 % cycle;
        double progress;
        if (t < SCROLL_PAUSE_SECONDS) {
            progress = 0;
        } else if (t < SCROLL_PAUSE_SECONDS + travel) {
            progress = (t - SCROLL_PAUSE_SECONDS) / travel;
        } else if (t < 2 * SCROLL_PAUSE_SECONDS + travel) {
            progress = 1;
        } else {
            progress = 1 - (t - 2 * SCROLL_PAUSE_SECONDS - travel) / travel;
        }
        graphics.enableScissor(x, y - 1, x + width, y + height);
        draw(graphics, font, text, x - (float) (progress * overflow), y, color, shadow, scale);
        graphics.disableScissor();
    }

    private static void draw(GuiGraphics graphics, Font font, Component text, float x, float y, int color, boolean shadow, float scale) {
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(x, y, 0);
        pose.scale(scale, scale, 1);
        graphics.drawString(font, text, 0, 0, color, shadow);
        pose.popPose();
    }

    // --- Gauges ---

    /** Scale notches along the right edge of a vertical gauge: short ones at eighths, long ones at quarters. */
    public static void verticalNotches(GuiGraphics graphics, int x, int y, int width, int height) {
        int innerHeight = height - 2;
        for (int i = 1; i < 8; i++) {
            int length = i % 2 == 0 ? 4 : 2;
            int notchY = y + 1 + innerHeight - Math.round(innerHeight * i / 8F);
            graphics.fill(x + width - 1 - length, notchY, x + width - 1, notchY + 1, NOTCH);
        }
    }

    /** Scale notches along the bottom edge of a horizontal bar. */
    public static void horizontalNotches(GuiGraphics graphics, int x, int y, int width, int height) {
        int innerWidth = width - 2;
        for (int i = 1; i < 8; i++) {
            int length = i % 2 == 0 ? 2 : 1;
            int notchX = x + 1 + Math.round(innerWidth * i / 8F);
            graphics.fill(notchX, y + height - 1 - length, notchX + 1, y + height - 1, NOTCH);
        }
    }

    /** A thin horizontal scrollbar for a row of {@code total} items with {@code visible} shown from {@code first}. */
    public static void horizontalScrollbar(GuiGraphics graphics, int x, int y, int width, int height, int first, int visible, int total) {
        if (total <= visible) {
            return;
        }
        graphics.blitSprite(SCROLLBAR, x, y, width, height);
        int thumbWidth = Math.max(4, width * visible / total);
        int thumbX = x + (width - thumbWidth) * first / (total - visible);
        graphics.blitSprite(SCROLLBAR_THUMB, thumbX, y, thumbWidth, height);
    }

    public static float fraction(double value, double max) {
        return max <= 0 ? 0 : (float) Mth.clamp(value / max, 0, 1);
    }
}
