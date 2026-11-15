package com.elpinho.seekerdrones.client.radar;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.joml.Matrix4f;

import com.elpinho.seekerdrones.client.gui.Kit;
import com.elpinho.seekerdrones.network.RadarContactsPayload.Contact;
import com.elpinho.seekerdrones.radar.TerrainMap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * The round radar scope of the Radar Station screen (DESIGN.md section 7.8): the terrain, range rings, crosshair, the
 * sweep with its phosphor trail, the station, the viewing player and the blips. A drone's blip is revealed when the
 * sweep passes its bearing, at its position in the latest snapshot, then fades out over one turn. Client-side only.
 */
public final class RadarScope implements AutoCloseable {
    private static final int RING = 0x966EF08C;
    private static final int SWEEP = 0xFFBEFFC8;
    private static final int TRAIL = 0x6E78FF96;
    private static final int TRAIL_DEGREES = 70;
    private static final int STATION = 0xFF8FE5D6;
    /** Half the blip's size, in GUI pixels. */
    private static final float BLIP = 1.5F;
    /** How close (GUI pixels) the mouse must be to a blip for its tooltip. */
    private static final float HOVER = 3;
    private static final float PING_VOLUME = 0.15F;
    private static final float PING_PITCH = 2.0F;

    private final TerrainTexture terrain = new TerrainTexture();
    private final Map<String, Blip> blips = new HashMap<>();
    private List<Contact> contacts = List.of();
    private int radius;
    private boolean hasTerrain;
    /** The sweep's position (in ticks since the world started) at the last frame, or -1 before the first frame. */
    private double lastSweepTime = -1;
    /** The sweep turn of the last ping, so it pings at most once per turn. */
    private long lastPingTurn = -1;

    /** A revealed drone: where it was when the sweep passed it, and when. */
    private record Blip(Contact contact, double revealedAt) {}

    public void setTerrain(TerrainMap map) {
        terrain.upload(map);
        radius = map.radius();
        hasTerrain = true;
    }

    public void setContacts(List<Contact> contacts) {
        this.contacts = contacts;
    }

    public boolean hasTerrain() {
        return hasTerrain;
    }

    public int contactCount() {
        return contacts.size();
    }

    /**
     * Draws the scope's contents inside a square at (x, y) of {@code size} GUI pixels.
     *
     * @param time    the world time in ticks, with the partial tick
     * @param powered whether the sweep runs; unpowered, the blips fade out and nothing new is revealed
     */
    public void render(GuiGraphics graphics, Font font, int x, int y, int size, double time, int sweepTicks, int configRadius,
            boolean powered, double playerDx, double playerDz, float playerYaw) {
        int mapRadius = hasTerrain ? radius : configRadius;
        float cx = x + size / 2F;
        float cy = y + size / 2F;
        float r = size / 2F;
        float scale = size / (float) TerrainMap.size(mapRadius);
        if (hasTerrain && powered) {
            graphics.blit(terrain.location(), x, y, size, size, 0, 0, TerrainMap.size(radius), TerrainMap.size(radius),
                    TerrainMap.size(radius), TerrainMap.size(radius));
        }
        // The shapes below are built from quads in both windings, so culling is off while they're drawn.
        graphics.flush();
        RenderSystem.disableCull();
        Matrix4f pose = graphics.pose().last().pose();
        VertexConsumer buffer = graphics.bufferSource().getBuffer(RenderType.gui());
        double sweepAngle = sweepAngle(time, sweepTicks);
        if (powered) {
            reveal(time, sweepTicks);
            drawTrail(buffer, pose, cx, cy, r, sweepAngle);
        }
        for (int ring = 1; ring <= 3; ring++) {
            MapShapes.ring(buffer, pose, cx, cy, r * ring / 3F, 0.5F, RING);
        }
        MapShapes.quad(buffer, pose, cx - r, cy - 0.25F, cx + r, cy + 0.25F, RING);
        MapShapes.quad(buffer, pose, cx - 0.25F, cy - r, cx + 0.25F, cy + r, RING);
        if (powered) {
            MapShapes.line(buffer, pose, cx, cy, cx + r * (float) Math.sin(sweepAngle), cy - r * (float) Math.cos(sweepAngle), 0.6F, SWEEP);
        }
        MapShapes.quad(buffer, pose, cx - 1, cy - 1, cx + 1, cy + 1, STATION);
        if (playerDx * playerDx + playerDz * playerDz <= (double) mapRadius * mapRadius) {
            MapShapes.player(buffer, pose, cx + (float) playerDx * scale, cy + (float) playerDz * scale, playerYaw);
        }
        List<Rect2i> labels = new ArrayList<>();
        for (Blip blip : blips.values()) {
            float alpha = alpha(blip, time, sweepTicks);
            if (alpha <= 0) {
                continue;
            }
            float bx = cx + blip.contact().dx() * scale;
            float by = cy + blip.contact().dz() * scale;
            MapShapes.quad(buffer, pose, bx - BLIP - 0.5F, by - BLIP - 0.5F, bx + BLIP + 0.5F, by + BLIP + 0.5F, MapShapes.withAlpha(MapShapes.OUTLINE, alpha));
            MapShapes.quad(buffer, pose, bx - BLIP, by - BLIP, bx + BLIP, by + BLIP, MapShapes.withAlpha(textColor(blip.contact()), alpha));
        }
        graphics.flush();
        RenderSystem.enableCull();
        if (!powered) {
            Kit.scaledTextCentered(graphics, font, Component.translatable("screen.seekerdrones.radar_station.no_power"),
                    Math.round(cx), cy + 6, Kit.DISPLAY_TEXT_DIM, 1);
        }
        drawRingLabels(graphics, font, cx, cy, r, mapRadius);
        for (Blip blip : blips.values()) {
            float alpha = alpha(blip, time, sweepTicks);
            if (alpha <= 0) {
                continue;
            }
            String label = label(blip.contact());
            int lx = Math.round(cx + blip.contact().dx() * scale + BLIP + 2);
            int ly = Math.round(cy + blip.contact().dz() * scale - 3);
            Rect2i area = new Rect2i(lx, ly, Kit.smallWidth(font, label), 6);
            if (labels.stream().anyMatch(other -> MapShapes.intersects(other, area))) {
                continue;
            }
            labels.add(area);
            Kit.smallText(graphics, font, label, lx, ly, MapShapes.withAlpha(textColor(blip.contact()), Math.max(alpha, 0.05F)), true);
        }
    }

    /** The tooltip of the blip under the mouse, if any. */
    @Nullable
    public Component hovered(int x, int y, int size, double time, int sweepTicks, int configRadius, double mouseX, double mouseY) {
        int mapRadius = hasTerrain ? radius : configRadius;
        float scale = size / (float) TerrainMap.size(mapRadius);
        float cx = x + size / 2F;
        float cy = y + size / 2F;
        Blip best = null;
        double bestDistance = HOVER * HOVER;
        for (Blip blip : blips.values()) {
            double dx = cx + blip.contact().dx() * scale - mouseX;
            double dy = cy + blip.contact().dz() * scale - mouseY;
            double distance = dx * dx + dy * dy;
            if (alpha(blip, time, sweepTicks) > 0 && distance <= bestDistance) {
                best = blip;
                bestDistance = distance;
            }
        }
        if (best == null) {
            return null;
        }
        Contact contact = best.contact();
        String text = contact.label().isEmpty() ? contact.droneId() : contact.label() + " - " + contact.droneId();
        return Component.literal(text).withColor(contact.color().getTextColor());
    }

    /** Reveals the contacts whose bearing the sweep passed since the last frame. */
    private void reveal(double time, int sweepTicks) {
        double from = lastSweepTime < 0 || time < lastSweepTime ? time : lastSweepTime;
        lastSweepTime = time;
        boolean revealed = false;
        for (Contact contact : contacts) {
            double bearing = bearing(contact) / (2 * Math.PI) * sweepTicks;
            // The first time at or after `from` when the sweep points at the contact.
            double passes = Math.floor((from - bearing) / sweepTicks) * sweepTicks + bearing;
            if (passes <= from) {
                passes += sweepTicks;
            }
            if (passes <= time || time - from >= sweepTicks) {
                blips.put(contact.droneId(), new Blip(contact, Math.min(passes, time)));
                revealed = true;
            }
        }
        long turn = (long) Math.floor(time / sweepTicks);
        if (revealed && turn != lastPingTurn) {
            lastPingTurn = turn;
            Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BELL.value(), PING_PITCH, PING_VOLUME));
        }
        blips.values().removeIf(blip -> time - blip.revealedAt() > sweepTicks);
    }

    private static float alpha(Blip blip, double time, int sweepTicks) {
        return (float) Mth.clamp(1 - (time - blip.revealedAt()) / sweepTicks, 0, 1);
    }

    /** Clockwise from north, in radians. North is negative Z, the top of the map. */
    private static double bearing(Contact contact) {
        double angle = Math.atan2(contact.dx(), -contact.dz());
        return angle < 0 ? angle + 2 * Math.PI : angle;
    }

    private static double sweepAngle(double time, int sweepTicks) {
        return (time % sweepTicks) / sweepTicks * 2 * Math.PI;
    }

    private static String label(Contact contact) {
        return contact.label().isEmpty() ? contact.droneId() : contact.label();
    }

    private static int textColor(Contact contact) {
        return 0xFF000000 | contact.color().getTextColor();
    }

    private static void drawRingLabels(GuiGraphics graphics, Font font, float cx, float cy, float r, int mapRadius) {
        for (int ring = 1; ring <= 3; ring++) {
            float distance = r * ring / 3F * 0.7071F;
            String text = String.valueOf(Math.round(mapRadius * ring / 3F));
            Kit.smallText(graphics, font, text, cx + distance + 1, cy + distance - 6, Kit.DISPLAY_TEXT_DIM, false);
        }
    }

    // --- Shapes, drawn as quads in GUI pixels so they stay smooth at any GUI scale ---

    /** The fading phosphor trail behind the sweep: thin wedges whose alpha drops off with the angle. */
    private static void drawTrail(VertexConsumer buffer, Matrix4f pose, float cx, float cy, float r, double sweepAngle) {
        int steps = TRAIL_DEGREES;
        for (int i = 0; i < steps; i++) {
            double a0 = sweepAngle - Math.toRadians(i);
            double a1 = sweepAngle - Math.toRadians(i + 1);
            float fade = 1 - (float) i / steps;
            int color = MapShapes.withAlpha(TRAIL, fade * fade);
            int colorEnd = MapShapes.withAlpha(TRAIL, (1 - (float) (i + 1) / steps) * (1 - (float) (i + 1) / steps));
            MapShapes.vertex(buffer, pose, cx, cy, color);
            MapShapes.vertex(buffer, pose, cx + r * (float) Math.sin(a0), cy - r * (float) Math.cos(a0), color);
            MapShapes.vertex(buffer, pose, cx + r * (float) Math.sin(a1), cy - r * (float) Math.cos(a1), colorEnd);
            MapShapes.vertex(buffer, pose, cx, cy, colorEnd);
        }
    }

    @Override
    public void close() {
        terrain.close();
    }
}
