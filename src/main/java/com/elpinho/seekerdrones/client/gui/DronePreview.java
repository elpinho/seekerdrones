package com.elpinho.seekerdrones.client.gui;

import javax.annotation.Nullable;

import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.registry.ModEntityTypes;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.world.item.DyeColor;

/**
 * A client-only 3D drone for GUI previews, in its color with spinning rotors. The entity is never added to the level
 * and shows no real position: it hovers in place or circles an orbit standing in for its patrol radius.
 */
public class DronePreview {
    /** One orbit takes this long. */
    private static final double ORBIT_MILLIS = 900 * Mth.TWO_PI;
    private static final float PITCH = 25;

    @Nullable
    private DroneEntity drone;
    private boolean failed;

    @Nullable
    private DroneEntity drone(DyeColor color) {
        if (drone == null && !failed) {
            var level = Minecraft.getInstance().level;
            drone = level != null ? ModEntityTypes.DRONE.get().create(level) : null;
            failed = drone == null;
            if (drone != null) {
                EntityRendering.preparePreview(drone);
            }
        }
        if (drone != null) {
            drone.setPreviewColor(color);
            // The model spins its rotors with the entity's age.
            drone.tickCount = (int) (Util.getMillis() / 50);
        }
        return drone;
    }

    /** Draws the drone hovering at (x, y) with a gentle bob, turning slowly. */
    public void renderHovering(GuiGraphics graphics, DyeColor color, float x, float y, float scale) {
        DroneEntity entity = drone(color);
        if (entity == null) {
            return;
        }
        long millis = Util.getMillis();
        float bob = Mth.sin(millis / 500F) * 0.8F;
        float yaw = millis / 40F % 360;
        EntityRendering.render(graphics, entity, x, y + bob, scale, yaw, PITCH, true);
    }

    /**
     * Draws the drone circling an ellipse around (centerX, centerY), facing the way it flies. It shrinks slightly on
     * the far side of the orbit for depth.
     */
    public void renderOrbiting(GuiGraphics graphics, DyeColor color, float centerX, float centerY, float radiusX, float radiusY, float scale) {
        DroneEntity entity = drone(color);
        if (entity == null) {
            return;
        }
        double angle = Util.getMillis() / ORBIT_MILLIS * Mth.TWO_PI;
        float x = centerX + radiusX * (float) Math.cos(angle);
        float y = centerY + radiusY * (float) Math.sin(angle) + Mth.sin(Util.getMillis() / 300F) * 0.6F;
        float depth = 0.85F + 0.15F * (float) Math.sin(angle);
        // Moving counterclockwise as seen from above: the heading is the orbit's tangent.
        float yaw = (float) Math.toDegrees(angle) + 90;
        EntityRendering.render(graphics, entity, x, y, scale * depth, yaw, PITCH, true);
    }

    /** Draws an orbit as a dashed ellipse of single pixels. */
    public static void drawOrbit(GuiGraphics graphics, float centerX, float centerY, float radiusX, float radiusY, int color) {
        int dots = Math.max(12, Math.round((radiusX + radiusY) * 1.6F));
        for (int i = 0; i < dots; i++) {
            if (i % 3 == 2) {
                continue;
            }
            double angle = Mth.TWO_PI * i / dots;
            int x = Math.round(centerX + radiusX * (float) Math.cos(angle));
            int y = Math.round(centerY + radiusY * (float) Math.sin(angle));
            graphics.fill(x, y, x + 1, y + 1, color);
        }
    }
}
