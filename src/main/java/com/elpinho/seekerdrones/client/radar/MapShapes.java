package com.elpinho.seekerdrones.client.radar;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.Rect2i;
import net.minecraft.util.FastColor;
import net.minecraft.util.Mth;

/**
 * Shapes for the radar machines' maps (DESIGN.md sections 7.8 and 7.9), drawn as quads in GUI pixels so they stay
 * smooth at any GUI scale. Some are built in both windings, so callers draw them with culling off.
 */
public final class MapShapes {
    public static final int PLAYER = 0xFFFFFFFF;
    public static final int OUTLINE = 0xFF0A0E10;
    private static final int PLAYER_OUTLINE = 0xFF141414;
    private static final int SEGMENTS = 128;

    private MapShapes() {}

    public static void ring(VertexConsumer buffer, Matrix4f pose, float cx, float cy, float r, float width, int color) {
        dashedRing(buffer, pose, cx, cy, r, width, color, SEGMENTS, 1);
    }

    /**
     * A ring of {@code segments} pieces, of which every {@code period}-th starts a dash and only the first of each
     * period is drawn. A period of 1 draws a full ring.
     */
    public static void dashedRing(VertexConsumer buffer, Matrix4f pose, float cx, float cy, float r, float width, int color, int segments, int period) {
        float inner = r - width / 2;
        float outer = r + width / 2;
        for (int i = 0; i < segments; i++) {
            if (i % period != 0) {
                continue;
            }
            double a0 = 2 * Math.PI * i / segments;
            double a1 = 2 * Math.PI * (i + 1) / segments;
            float s0 = (float) Math.sin(a0);
            float c0 = (float) Math.cos(a0);
            float s1 = (float) Math.sin(a1);
            float c1 = (float) Math.cos(a1);
            vertex(buffer, pose, cx + outer * s0, cy - outer * c0, color);
            vertex(buffer, pose, cx + inner * s0, cy - inner * c0, color);
            vertex(buffer, pose, cx + inner * s1, cy - inner * c1, color);
            vertex(buffer, pose, cx + outer * s1, cy - outer * c1, color);
        }
    }

    public static void line(VertexConsumer buffer, Matrix4f pose, float x0, float y0, float x1, float y1, float width, int color) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float length = Mth.sqrt(dx * dx + dy * dy);
        if (length == 0) {
            return;
        }
        float nx = -dy / length * width / 2;
        float ny = dx / length * width / 2;
        vertex(buffer, pose, x0 + nx, y0 + ny, color);
        vertex(buffer, pose, x0 - nx, y0 - ny, color);
        vertex(buffer, pose, x1 - nx, y1 - ny, color);
        vertex(buffer, pose, x1 + nx, y1 + ny, color);
    }

    public static void quad(VertexConsumer buffer, Matrix4f pose, float x0, float y0, float x1, float y1, int color) {
        vertex(buffer, pose, x0, y0, color);
        vertex(buffer, pose, x0, y1, color);
        vertex(buffer, pose, x1, y1, color);
        vertex(buffer, pose, x1, y0, color);
    }

    /** A square of half-size {@code half} centered on (x, y). */
    public static void square(VertexConsumer buffer, Matrix4f pose, float x, float y, float half, int color) {
        quad(buffer, pose, x - half, y - half, x + half, y + half, color);
    }

    /** A diamond (a square standing on a corner) with corners {@code r} from (x, y). */
    public static void diamond(VertexConsumer buffer, Matrix4f pose, float x, float y, float r, int color) {
        vertex(buffer, pose, x, y - r, color);
        vertex(buffer, pose, x - r, y, color);
        vertex(buffer, pose, x, y + r, color);
        vertex(buffer, pose, x + r, y, color);
    }

    /** A square outline of half-size {@code half} and the given line width. */
    public static void outline(VertexConsumer buffer, Matrix4f pose, float x, float y, float half, float width, int color) {
        quad(buffer, pose, x - half, y - half, x + half, y - half + width, color);
        quad(buffer, pose, x - half, y + half - width, x + half, y + half, color);
        quad(buffer, pose, x - half, y - half + width, x - half + width, y + half - width, color);
        quad(buffer, pose, x + half - width, y - half + width, x + half, y + half - width, color);
    }

    /** The viewing player: an arrow pointing where they look, with a dark outline. */
    public static void player(VertexConsumer buffer, Matrix4f pose, float x, float y, float yaw) {
        // Minecraft's look direction is (-sin yaw, cos yaw) in X and Z, and the map's X and Y are the world's X and Z.
        float dirX = -Mth.sin(yaw * Mth.DEG_TO_RAD);
        float dirY = Mth.cos(yaw * Mth.DEG_TO_RAD);
        arrow(buffer, pose, x, y, dirX, dirY, 3.5F, PLAYER_OUTLINE);
        arrow(buffer, pose, x, y, dirX, dirY, 2.5F, PLAYER);
    }

    /** An arrowhead: the tip, two back corners and a notch pulled toward the middle. */
    public static void arrow(VertexConsumer buffer, Matrix4f pose, float x, float y, float dirX, float dirY, float size, int color) {
        float sideX = -dirY;
        float sideY = dirX;
        vertex(buffer, pose, x + dirX * size, y + dirY * size, color);
        vertex(buffer, pose, x - dirX * size * 0.7F + sideX * size * 0.75F, y - dirY * size * 0.7F + sideY * size * 0.75F, color);
        vertex(buffer, pose, x - dirX * size * 0.3F, y - dirY * size * 0.3F, color);
        vertex(buffer, pose, x - dirX * size * 0.7F - sideX * size * 0.75F, y - dirY * size * 0.7F - sideY * size * 0.75F, color);
    }

    public static void vertex(VertexConsumer buffer, Matrix4f pose, float x, float y, int color) {
        buffer.addVertex(pose, x, y, 0).setColor(color);
    }

    public static int withAlpha(int color, float alpha) {
        return FastColor.ARGB32.color(Math.round(FastColor.ARGB32.alpha(color) * alpha), color);
    }

    public static boolean intersects(Rect2i a, Rect2i b) {
        return a.getX() < b.getX() + b.getWidth() && b.getX() < a.getX() + a.getWidth()
                && a.getY() < b.getY() + b.getHeight() && b.getY() < a.getY() + a.getHeight();
    }
}
