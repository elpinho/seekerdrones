package com.elpinho.seekerdrones.client.gui;

import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;

/**
 * The kit's gauges: a vertical energy gauge (green), a vertical fluid gauge drawn with the fluid's own texture, and
 * horizontal energy and health bars. All share one scale of edge notches and show exact values in their tooltips.
 */
public final class Gauges {
    private Gauges() {}

    /** A vertical gauge filled from the bottom with a stretched fill sprite. */
    public static class Energy extends KitWidget {
        private final DoubleSupplier fraction;

        public Energy(int x, int y, int width, int height, DoubleSupplier fraction) {
            super(x, y, width, height);
            this.fraction = fraction;
        }

        @Override
        protected void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            graphics.blitSprite(Kit.GAUGE, getX(), getY(), width, height);
            int inner = height - 2;
            int filled = Math.round(inner * (float) fraction.getAsDouble());
            if (filled > 0) {
                graphics.blitSprite(Kit.FILL_ENERGY_VERTICAL, getX() + 1, getY() + 1 + inner - filled, width - 2, filled);
            }
            Kit.verticalNotches(graphics, getX(), getY(), width, height);
        }
    }

    /** A vertical fluid gauge. The fluid's still texture is tiled from the bottom and tinted like in the world. */
    public static class FluidTank extends KitWidget {
        private final Supplier<Fluid> fluid;
        private final IntSupplier amount;
        private final IntSupplier capacity;

        public FluidTank(int x, int y, int width, int height, Supplier<Fluid> fluid, IntSupplier amount, IntSupplier capacity) {
            super(x, y, width, height);
            this.fluid = fluid;
            this.amount = amount;
            this.capacity = capacity;
        }

        @Override
        protected void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            graphics.blitSprite(Kit.GAUGE, getX(), getY(), width, height);
            int inner = height - 2;
            int filled = Math.round(inner * Kit.fraction(amount.getAsInt(), capacity.getAsInt()));
            Fluid shown = fluid.get();
            if (filled > 0 && shown != Fluids.EMPTY) {
                drawFluid(graphics, shown, getX() + 1, getY() + 1 + inner - filled, width - 2, filled);
                Kit.blitTranslucent(graphics, Kit.GAUGE_GLASS, getX() + 1, getY() + 1 + inner - filled, width - 2, filled);
            }
            Kit.verticalNotches(graphics, getX(), getY(), width, height);
        }
    }

    /** Tiles a fluid's still texture over the area, clipped to it. */
    public static void drawFluid(GuiGraphics graphics, Fluid fluid, int x, int y, int width, int height) {
        IClientFluidTypeExtensions extensions = IClientFluidTypeExtensions.of(fluid);
        ResourceLocation texture = extensions.getStillTexture();
        if (texture == null) {
            return;
        }
        TextureAtlasSprite sprite = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(texture);
        int tint = extensions.getTintColor();
        float red = FastColor.ARGB32.red(tint) / 255F;
        float green = FastColor.ARGB32.green(tint) / 255F;
        float blue = FastColor.ARGB32.blue(tint) / 255F;
        graphics.enableScissor(x, y, x + width, y + height);
        // Tiles are laid from the bottom up, so the surface moves while the bottom stays put.
        int bottom = y + height;
        for (int tileY = bottom - 16; tileY > y - 16; tileY -= 16) {
            for (int tileX = x; tileX < x + width; tileX += 16) {
                graphics.blit(tileX, tileY, 0, 16, 16, sprite, red, green, blue, 1);
            }
        }
        graphics.disableScissor();
    }

    /** A horizontal bar filled from the left, for drone energy and health. */
    public static class Bar extends KitWidget {
        public enum Kind {
            ENERGY(Kit.FILL_ENERGY_HORIZONTAL),
            HEALTH(Kit.FILL_HEALTH_HORIZONTAL);

            private final ResourceLocation fill;

            Kind(ResourceLocation fill) {
                this.fill = fill;
            }
        }

        private final Kind kind;
        private final DoubleSupplier fraction;

        public Bar(int x, int y, int width, int height, Kind kind, DoubleSupplier fraction) {
            super(x, y, width, height);
            this.kind = kind;
            this.fraction = fraction;
        }

        @Override
        protected void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            graphics.blitSprite(Kit.GAUGE, getX(), getY(), width, height);
            int inner = width - 2;
            int filled = Math.round(inner * (float) fraction.getAsDouble());
            if (filled > 0) {
                graphics.blitSprite(kind.fill, getX() + 1, getY() + 1, filled, height - 2);
            }
            Kit.horizontalNotches(graphics, getX(), getY(), width, height);
        }
    }
}
