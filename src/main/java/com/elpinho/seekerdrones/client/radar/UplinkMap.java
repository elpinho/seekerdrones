package com.elpinho.seekerdrones.client.radar;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import org.joml.Matrix4f;

import com.elpinho.seekerdrones.network.UplinkContactsPayload;
import com.elpinho.seekerdrones.network.UplinkContactsPayload.Identified;
import com.elpinho.seekerdrones.network.UplinkContactsPayload.Unidentified;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * The square map of the Satellite Uplink screen (DESIGN.md section 7.9), on the shared {@link StationMap}: the drones
 * as pulsing blips with a heading tail and an altitude cue, the viewer's identified and everyone else's unidentified.
 * Only identified drones show their tracking line.
 */
public final class UplinkMap extends StationMap {
    private static final int UNIDENTIFIED = 0xFFD2D2D2;
    private static final int UNIDENTIFIED_CORE = 0xFF5A5A5A;
    private static final int UNIDENTIFIED_TAIL = 0xFFC8C8C8;
    private static final float MIN_PULSE = 0.55F;

    /** What the mouse (or a hovered list row) points at. */
    public record Hover(@Nullable Identified identified, @Nullable Unidentified unidentified) {}

    private List<Identified> identified = List.of();
    private List<Unidentified> unidentified = List.of();
    @Nullable
    private Hover hover;
    /** The drone of a hovered list row, drawn as if its blip were hovered. */
    @Nullable
    private Identified listHover;
    private int pulseTicks = 40;

    public UplinkMap() {
        super("uplink_terrain");
    }

    // --- Data ---

    public void setContacts(UplinkContactsPayload payload) {
        identified = payload.identified();
        unidentified = payload.unidentified();
        setStations(payload.stations());
    }

    public List<Identified> identified() {
        return identified;
    }

    public int unidentifiedCount() {
        return unidentified.size();
    }

    /** What the mouse pointed at in the last frame. */
    @Nullable
    public Hover hover() {
        return hover;
    }

    /** Sets the frame's hovered list row and pulse length, before {@link #render}. */
    public void prepare(@Nullable Identified listHover, int pulseTicks) {
        this.listHover = listHover;
        this.pulseTicks = Math.max(1, pulseTicks);
    }

    // --- Rendering ---

    private float alpha(double time) {
        float pulse = MIN_PULSE + (1 - MIN_PULSE) * (0.5F + 0.5F * Mth.cos((float) (time / pulseTicks * Mth.TWO_PI)));
        return pulse * fade;
    }

    @Nullable
    private Identified highlighted() {
        return listHover != null ? listHover : hover != null ? hover.identified() : null;
    }

    @Override
    protected void updateHover(boolean active, int mouseX, int mouseY, double time) {
        hover = active ? findHover(mouseX, mouseY) : null;
    }

    @Override
    protected void drawUnderlay(VertexConsumer buffer, Matrix4f pose) {
        Identified highlighted = highlighted();
        if (highlighted != null && highlighted.patrol().isPresent() && fade > 0) {
            drawPatrolRing(buffer, pose, highlighted.patrol().get(), textColor(highlighted));
        }
    }

    @Override
    protected List<TrackingLine> trackingLines() {
        List<TrackingLine> lines = new ArrayList<>();
        for (Identified drone : identified) {
            drone.target().ifPresent(target -> lines.add(new TrackingLine(drone.dx(), drone.dz(), target, textColor(drone))));
        }
        return lines;
    }

    @Override
    protected void drawContacts(VertexConsumer buffer, Matrix4f pose, double time) {
        float alpha = alpha(time);
        if (alpha <= 0) {
            return;
        }
        Identified highlighted = highlighted();
        for (Unidentified drone : unidentified) {
            float px = sx(drone.dx());
            float py = sy(drone.dz());
            drawTail(buffer, pose, px, py, drone.yaw(), UNIDENTIFIED_TAIL);
            drawDiamondBlip(buffer, pose, px, py, UNIDENTIFIED, UNIDENTIFIED_CORE, alpha);
        }
        for (Identified drone : identified) {
            float px = sx(drone.dx());
            float py = sy(drone.dz());
            drawTail(buffer, pose, px, py, drone.yaw(), textColor(drone));
            drawSquareBlip(buffer, pose, px, py, textColor(drone), alpha);
            if (drone == highlighted) {
                MapShapes.outline(buffer, pose, px, py, BLIP + 1.5F, 0.5F, MapShapes.withAlpha(MapShapes.PLAYER, fade));
            }
        }
    }

    /** Labels (or IDs) and altitude cues. When two would overlap, the later one is hidden. */
    @Override
    protected void drawOverlay(GuiGraphics graphics, Font font, double time) {
        float alpha = alpha(time);
        if (alpha <= 0) {
            return;
        }
        alpha = Math.max(alpha, 0.05F);
        List<Rect2i> taken = new ArrayList<>();
        for (Unidentified drone : unidentified) {
            drawLabel(graphics, font, taken, "", 0, drone.dy(), sx(drone.dx()) + DIAMOND + 2, sy(drone.dz()) - 3, alpha);
        }
        for (Identified drone : identified) {
            String label = drone.label().isEmpty() ? drone.droneId() : drone.label();
            drawLabel(graphics, font, taken, label, textColor(drone), drone.dy(), sx(drone.dx()) + BLIP + 2, sy(drone.dz()) - 3, alpha);
        }
    }

    /** The tooltip for what the mouse points at, if anything. */
    public List<Component> tooltip() {
        if (hover == null) {
            return List.of();
        }
        if (hover.identified() != null) {
            return identifiedTooltip(hover.identified());
        }
        return List.of(Component.translatable("screen.seekerdrones.uplink.unidentified_drone"));
    }

    /** {@code <label> - <ID>} in the drone's color, what it is tracking, and the click hint. */
    public static List<Component> identifiedTooltip(Identified drone) {
        String text = drone.label().isEmpty() ? drone.droneId() : drone.label() + " - " + drone.droneId();
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(text).withColor(drone.color().getTextColor()));
        drone.target().ifPresent(target -> lines.add(Component.translatable("screen.seekerdrones.uplink.tracking", target.name())
                .withStyle(ChatFormatting.GRAY)));
        lines.add(Component.translatable("screen.seekerdrones.uplink.open_remote").withStyle(ChatFormatting.GRAY));
        return lines;
    }

    private Hover findHover(int mouseX, int mouseY) {
        double best = HOVER * HOVER;
        Hover found = null;
        for (Identified drone : identified) {
            double d = distanceSqr(sx(drone.dx()), sy(drone.dz()), mouseX, mouseY);
            if (d <= best) {
                best = d;
                found = new Hover(drone, null);
            }
        }
        for (Unidentified drone : unidentified) {
            double d = distanceSqr(sx(drone.dx()), sy(drone.dz()), mouseX, mouseY);
            // Identified drones win ties, so an operator's own drone stays clickable under a stranger's.
            if (d < best) {
                best = d;
                found = new Hover(null, drone);
            }
        }
        return found;
    }

    private static int textColor(Identified drone) {
        return 0xFF000000 | drone.color().getTextColor();
    }
}
