package com.elpinho.seekerdrones.client.radar;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.joml.Matrix4f;

import com.elpinho.seekerdrones.client.gui.EntityPreview;
import com.elpinho.seekerdrones.client.gui.Kit;
import com.elpinho.seekerdrones.network.UplinkContactsPayload;
import com.elpinho.seekerdrones.network.UplinkContactsPayload.Identified;
import com.elpinho.seekerdrones.network.UplinkContactsPayload.Unidentified;
import com.elpinho.seekerdrones.radar.ColorMap;
import com.elpinho.seekerdrones.radar.RadarMap;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FastColor;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.MapColor;

/**
 * The square map of the Satellite Uplink screen (DESIGN.md section 7.9): the terrain in vanilla map colors, the area's
 * edge with the outside hatched, the Uplink, the Charging Stations, the viewing player, and the drones as pulsing
 * blips with a heading tail and an altitude cue. It zooms in steps (FIT, ×1, ×2, ×4 px per block) anchored on the
 * mouse, and the view is kept inside the area's bounding square. Client-side only: the server sends the map and the
 * snapshots.
 */
public final class UplinkMap implements AutoCloseable {
    /** The display's size, like the Radar Station's scope. */
    public static final int SIZE = 198;
    public static final int BORDER = 1;
    public static final int INNER = SIZE - 2 * BORDER;
    /** Pixels per block for each zoom step; the first is FIT (the whole area). */
    private static final float[] ZOOMS = {0, 1, 2, 4};
    private static final String[] ZOOM_LABELS = {"FIT", "×1", "×2", "×4"};

    private static final int OUTSIDE = 0xFF12161A;
    private static final int OUTSIDE_HATCH = 0xFF1E242A;
    private static final int UNKNOWN = 0xFF161B1F;
    private static final int EDGE = 0xA08FE5D6;
    private static final int STATION_DARK = 0xFF0E1115;
    private static final int STATION_PAD = 0xFF3F7872;
    private static final int UPLINK_WHITE = 0xFFF0F0F0;
    private static final int UPLINK_RED = 0xFFC42620;
    private static final int UNIDENTIFIED = 0xFFD2D2D2;
    private static final int UNIDENTIFIED_CORE = 0xFF5A5A5A;
    private static final int UNIDENTIFIED_TAIL = 0xFFC8C8C8;
    private static final int ALTITUDE = 0xFFAAB4B9;
    private static final int SCALE_BACKGROUND = 0xAA0A0E10;
    private static final int UNPOWERED_SHADE = 0xB0080B0D;
    /** Half the identified blip's size, in GUI pixels. */
    private static final float BLIP = 1.5F;
    private static final float DIAMOND = 2.5F;
    /** How close (GUI pixels) the mouse must be to a blip for its tooltip. */
    private static final float HOVER = 3.5F;
    private static final int TAIL_DOTS = 5;
    /** The length of the target line's dashes (and gaps), in GUI pixels. */
    private static final float TARGET_DASH = 2;
    /** A target without a head to show: a small red square. */
    private static final int TARGET_MARK = 0xFFE8281E;
    /** The size of a target's head (a player's face or a mob's head item), in GUI pixels. */
    private static final int TARGET_HEAD = 8;
    /** The mobs with a vanilla head item. */
    private static final Map<EntityType<?>, Item> MOB_HEADS = Map.of(
            EntityType.ZOMBIE, Items.ZOMBIE_HEAD,
            EntityType.SKELETON, Items.SKELETON_SKULL,
            EntityType.WITHER_SKELETON, Items.WITHER_SKELETON_SKULL,
            EntityType.CREEPER, Items.CREEPER_HEAD,
            EntityType.PIGLIN, Items.PIGLIN_HEAD,
            EntityType.ENDER_DRAGON, Items.DRAGON_HEAD);
    private static final float MIN_PULSE = 0.55F;
    /** Ticks the blips take to fade out once the Uplink loses power. */
    private static final float FADE_TICKS = 20;

    /** What the mouse (or a hovered list row) points at. */
    public record Hover(@Nullable Identified identified, @Nullable Unidentified unidentified) {}

    private final MapTexture texture = new MapTexture("uplink_terrain");
    private int radius;
    private boolean hasTerrain;
    private List<Identified> identified = List.of();
    private List<Unidentified> unidentified = List.of();
    private List<UplinkContactsPayload.Station> stations = List.of();
    private int zoom;
    /** The view's center, in blocks from the Uplink's center. */
    private double viewX;
    private double viewZ;
    @Nullable
    private Hover hover;
    /** When the Uplink was last seen unpowered while it had been powered, or -1 while powered. */
    private double unpoweredSince = -1;

    // --- Data ---

    public void setTerrain(ColorMap map) {
        radius = map.radius();
        hasTerrain = true;
        int size = map.size();
        float center = map.radius();
        float edge = map.radius() + 0.5F;
        texture.upload(size, (column, row) -> {
            float dx = column - center;
            float dz = row - center;
            if (dx * dx + dz * dz > edge * edge) {
                return (column + row) % 6 == 0 ? OUTSIDE_HATCH : OUTSIDE;
            }
            byte packed = map.color(column, row);
            return packed == ColorMap.UNKNOWN ? UNKNOWN : argb(packed);
        });
        clampView(radius);
    }

    public void setContacts(UplinkContactsPayload payload) {
        identified = payload.identified();
        unidentified = payload.unidentified();
        stations = payload.stations();
    }

    public boolean hasTerrain() {
        return hasTerrain;
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

    // --- Zoom ---

    public String zoomLabel() {
        return ZOOM_LABELS[zoom];
    }

    public boolean canZoom(int step) {
        int next = zoom + step;
        return next >= 0 && next < ZOOMS.length;
    }

    /**
     * Steps the zoom in ({@code step} 1) or out (-1), keeping the block under ({@code anchorX}, {@code anchorY}) where
     * it is. The anchor is in GUI pixels, the map's inner area at ({@code x}, {@code y}).
     */
    public void zoom(int step, double anchorX, double anchorY, int x, int y, int configRadius) {
        if (!canZoom(step)) {
            return;
        }
        int r = mapRadius(configRadius);
        double midX = x + INNER / 2.0;
        double midY = y + INNER / 2.0;
        float before = ppb(r);
        double blockX = viewX + (anchorX - midX) / before;
        double blockZ = viewZ + (anchorY - midY) / before;
        zoom += step;
        float after = ppb(r);
        viewX = blockX - (anchorX - midX) / after;
        viewZ = blockZ - (anchorY - midY) / after;
        clampView(r);
    }

    /** Moves the view with the mouse, by a drag of ({@code dragX}, {@code dragY}) GUI pixels, kept inside the area. */
    public void pan(double dragX, double dragY, int configRadius) {
        int r = mapRadius(configRadius);
        float ppb = ppb(r);
        viewX -= dragX / ppb;
        viewZ -= dragY / ppb;
        clampView(r);
    }

    private float ppb(int r) {
        return zoom == 0 ? INNER / (float) RadarMap.size(r) : ZOOMS[zoom];
    }

    private int mapRadius(int configRadius) {
        return hasTerrain ? radius : configRadius;
    }

    /** Keeps the view inside the area's bounding square (the whole square at FIT). */
    private void clampView(int r) {
        double half = INNER / 2.0 / ppb(r);
        double limit = Math.max(0, r + 0.5 - half);
        viewX = Mth.clamp(viewX, -limit, limit);
        viewZ = Mth.clamp(viewZ, -limit, limit);
    }

    // --- Rendering ---

    /**
     * Draws the map inside its inner area at ({@code x}, {@code y}), and works out what the mouse points at.
     *
     * @param time        the world time in ticks, with the partial tick
     * @param listHover   the drone of a hovered list row, drawn as if its blip were hovered
     * @param player      the viewing player's position relative to the Uplink's center, in blocks (x, z), and yaw
     */
    public void render(GuiGraphics graphics, Font font, int x, int y, double time, int pulseTicks, int configRadius, boolean powered,
            double playerX, double playerZ, float playerYaw, int mouseX, int mouseY, @Nullable Identified listHover) {
        int r = mapRadius(configRadius);
        clampView(r);
        float ppb = ppb(r);
        float midX = x + INNER / 2F;
        float midY = y + INNER / 2F;
        if (powered) {
            unpoweredSince = -1;
        } else if (unpoweredSince < 0) {
            unpoweredSince = time;
        }
        float fade = powered ? 1 : (float) Mth.clamp(1 - (time - unpoweredSince) / FADE_TICKS, 0, 1);
        float pulse = MIN_PULSE + (1 - MIN_PULSE) * (0.5F + 0.5F * Mth.cos((float) (time / pulseTicks * Mth.TWO_PI)));
        float alpha = pulse * fade;

        graphics.enableScissor(x, y, x + INNER, y + INNER);
        graphics.fill(x, y, x + INNER, y + INNER, OUTSIDE);
        if (hasTerrain) {
            var pose = graphics.pose();
            pose.pushPose();
            pose.translate(sx(-radius - 0.5, midX, ppb), sy(-radius - 0.5, midY, ppb), 0);
            pose.scale(ppb, ppb, 1);
            int size = texture.size();
            graphics.blit(texture.location(), 0, 0, size, size, 0, 0, size, size, size, size);
            pose.popPose();
        }

        boolean inside = mouseX >= x && mouseX < x + INNER && mouseY >= y && mouseY < y + INNER;
        hover = fade > 0 && inside ? findHover(midX, midY, ppb, mouseX, mouseY) : null;
        Identified highlighted = listHover != null ? listHover : hover != null ? hover.identified() : null;

        // The shapes are built from quads in both windings, so culling is off while they're drawn.
        graphics.flush();
        RenderSystem.disableCull();
        Matrix4f pose = graphics.pose().last().pose();
        VertexConsumer buffer = graphics.bufferSource().getBuffer(RenderType.gui());
        MapShapes.ring(buffer, pose, sx(0, midX, ppb), sy(0, midY, ppb), r * ppb, 0.5F, EDGE);
        if (highlighted != null && highlighted.patrol().isPresent() && fade > 0) {
            UplinkContactsPayload.Patrol patrol = highlighted.patrol().get();
            int color = MapShapes.withAlpha(textColor(highlighted), 0.9F * fade);
            float px = sx(patrol.dx(), midX, ppb);
            float py = sy(patrol.dz(), midY, ppb);
            float ringRadius = patrol.radius() * ppb;
            int segments = Mth.clamp(Math.round(ringRadius * Mth.TWO_PI / 3), 12, 240);
            MapShapes.dashedRing(buffer, pose, px, py, ringRadius, 0.75F, color, segments - segments % 2, 2);
            MapShapes.square(buffer, pose, px, py, 0.75F, color);
        }
        for (UplinkContactsPayload.Station station : stations) {
            float px = sx(station.dx(), midX, ppb);
            float py = sy(station.dz(), midY, ppb);
            MapShapes.quad(buffer, pose, px - 3, py - 1.5F, px + 3, py + 1.5F, STATION_DARK);
            MapShapes.quad(buffer, pose, px - 2.5F, py - 1, px + 2.5F, py + 1, STATION_PAD);
            MapShapes.square(buffer, pose, px, py, 1, Kit.DISPLAY_TEXT);
        }
        float ux = sx(0, midX, ppb);
        float uy = sy(0, midY, ppb);
        MapShapes.square(buffer, pose, ux, uy, 2, STATION_DARK);
        MapShapes.square(buffer, pose, ux, uy, 1.5F, UPLINK_WHITE);
        MapShapes.square(buffer, pose, ux, uy, 0.5F, UPLINK_RED);
        if (playerX * playerX + playerZ * playerZ <= (double) r * r) {
            MapShapes.player(buffer, pose, sx(playerX, midX, ppb), sy(playerZ, midY, ppb), playerYaw);
        }
        if (fade > 0) {
            for (Identified drone : identified) {
                drone.target().ifPresent(target -> drawTargetLine(buffer, pose, sx(drone.dx(), midX, ppb), sy(drone.dz(), midY, ppb),
                        sx(target.dx(), midX, ppb), sy(target.dz(), midY, ppb), textColor(drone), fade, target.type()));
            }
        }
        if (alpha > 0) {
            for (Unidentified drone : unidentified) {
                float px = sx(drone.dx(), midX, ppb);
                float py = sy(drone.dz(), midY, ppb);
                drawTail(buffer, pose, px, py, drone.yaw(), UNIDENTIFIED_TAIL, fade);
                MapShapes.diamond(buffer, pose, px, py, DIAMOND + 1, MapShapes.withAlpha(MapShapes.OUTLINE, alpha));
                MapShapes.diamond(buffer, pose, px, py, DIAMOND, MapShapes.withAlpha(UNIDENTIFIED, alpha));
                MapShapes.diamond(buffer, pose, px, py, DIAMOND - 1, MapShapes.withAlpha(UNIDENTIFIED_CORE, alpha));
            }
            for (Identified drone : identified) {
                float px = sx(drone.dx(), midX, ppb);
                float py = sy(drone.dz(), midY, ppb);
                drawTail(buffer, pose, px, py, drone.yaw(), textColor(drone), fade);
                MapShapes.square(buffer, pose, px, py, BLIP + 0.5F, MapShapes.withAlpha(MapShapes.OUTLINE, alpha));
                MapShapes.square(buffer, pose, px, py, BLIP, MapShapes.withAlpha(textColor(drone), alpha));
                if (drone == highlighted) {
                    MapShapes.outline(buffer, pose, px, py, BLIP + 1.5F, 0.5F, MapShapes.withAlpha(MapShapes.PLAYER, fade));
                }
            }
        }
        graphics.flush();
        RenderSystem.enableCull();

        drawTargetHeads(graphics, midX, midY, ppb, fade);
        if (alpha > 0) {
            drawLabels(graphics, font, midX, midY, ppb, Math.max(alpha, 0.05F));
        }
        drawNorth(graphics, font, x, y);
        drawScale(graphics, font, x, y + INNER, ppb);
        if (!powered) {
            graphics.fill(x, y, x + INNER, y + INNER, UNPOWERED_SHADE);
            Kit.scaledTextCentered(graphics, font, Component.translatable("screen.seekerdrones.radar_station.no_power"),
                    Math.round(midX), midY - 4, Kit.DISPLAY_TEXT_DIM, 1);
        }
        graphics.disableScissor();
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

    private Hover findHover(float midX, float midY, float ppb, int mouseX, int mouseY) {
        double best = HOVER * HOVER;
        Hover found = null;
        for (Identified drone : identified) {
            double d = distanceSqr(sx(drone.dx(), midX, ppb), sy(drone.dz(), midY, ppb), mouseX, mouseY);
            if (d <= best) {
                best = d;
                found = new Hover(drone, null);
            }
        }
        for (Unidentified drone : unidentified) {
            double d = distanceSqr(sx(drone.dx(), midX, ppb), sy(drone.dz(), midY, ppb), mouseX, mouseY);
            // Identified drones win ties, so an operator's own drone stays clickable under a stranger's.
            if (d < best) {
                best = d;
                found = new Hover(null, drone);
            }
        }
        return found;
    }

    /** Labels (or IDs) and altitude cues. When two would overlap, the later one is hidden. */
    private void drawLabels(GuiGraphics graphics, Font font, float midX, float midY, float ppb, float alpha) {
        List<Rect2i> taken = new ArrayList<>();
        for (Unidentified drone : unidentified) {
            String altitude = altitude(drone.dy());
            if (!altitude.isEmpty()) {
                drawText(graphics, font, taken, altitude, sx(drone.dx(), midX, ppb) + DIAMOND + 2, sy(drone.dz(), midY, ppb) - 3,
                        MapShapes.withAlpha(ALTITUDE, alpha));
            }
        }
        for (Identified drone : identified) {
            float lx = sx(drone.dx(), midX, ppb) + BLIP + 2;
            float ly = sy(drone.dz(), midY, ppb) - 3;
            String label = drone.label().isEmpty() ? drone.droneId() : drone.label();
            String altitude = altitude(drone.dy());
            int labelWidth = Kit.smallWidth(font, label);
            int width = labelWidth + (altitude.isEmpty() ? 0 : 2 + Kit.smallWidth(font, altitude));
            Rect2i area = new Rect2i(Math.round(lx), Math.round(ly), width, 6);
            if (taken.stream().anyMatch(other -> MapShapes.intersects(other, area))) {
                continue;
            }
            taken.add(area);
            Kit.smallText(graphics, font, label, lx, ly, MapShapes.withAlpha(textColor(drone), alpha), true);
            if (!altitude.isEmpty()) {
                Kit.smallText(graphics, font, altitude, lx + labelWidth + 2, ly, MapShapes.withAlpha(ALTITUDE, alpha), true);
            }
        }
    }

    private static void drawText(GuiGraphics graphics, Font font, List<Rect2i> taken, String text, float x, float y, int color) {
        Rect2i area = new Rect2i(Math.round(x), Math.round(y), Kit.smallWidth(font, text), 6);
        if (taken.stream().anyMatch(other -> MapShapes.intersects(other, area))) {
            return;
        }
        taken.add(area);
        Kit.smallText(graphics, font, text, x, y, color, true);
    }

    /** {@code ▲12} or {@code ▼4}: the drone's Y minus the Uplink's, hidden within ±1. */
    private static String altitude(float dy) {
        int blocks = Math.round(dy);
        if (Math.abs(blocks) <= 1) {
            return "";
        }
        return (blocks > 0 ? "▲" : "▼") + Math.abs(blocks);
    }

    /**
     * A dashed line in the drone's color from the blip to the entity it tracks, ending at its head (or square). It
     * runs between map positions, so it follows the zoom.
     */
    private static void drawTargetLine(VertexConsumer buffer, Matrix4f pose, float x0, float y0, float x1, float y1, int color, float fade,
            EntityType<?> type) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float length = Mth.sqrt(dx * dx + dy * dy);
        int line = MapShapes.withAlpha(color, 0.85F * fade);
        boolean head = hasHead(type);
        // Start past the blip and stop at the target's head or square, with 2 px dashes and 2 px gaps.
        float stop = length - (head ? TARGET_HEAD / 2F + 1 : 2);
        for (float at = BLIP + 2; at < stop; at += TARGET_DASH * 2) {
            float end = Math.min(at + TARGET_DASH, stop);
            MapShapes.line(buffer, pose, x0 + dx * at / length, y0 + dy * at / length, x0 + dx * end / length, y0 + dy * end / length, 0.75F, line);
        }
        if (!head) {
            // No head to show: a small square in the target color (heads are drawn later, over the shapes).
            MapShapes.square(buffer, pose, x1, y1, 1.5F, MapShapes.withAlpha(MapShapes.OUTLINE, fade));
            MapShapes.square(buffer, pose, x1, y1, 1, MapShapes.withAlpha(TARGET_MARK, fade));
        }
    }

    /** Whether the target is drawn as a head: a player's face, or a mob with a vanilla head item. */
    private static boolean hasHead(EntityType<?> type) {
        return type == EntityType.PLAYER || MOB_HEADS.containsKey(type);
    }

    /** The heads of the tracked targets, over the lines' ends. */
    private void drawTargetHeads(GuiGraphics graphics, float midX, float midY, float ppb, float fade) {
        if (fade <= 0) {
            return;
        }
        for (Identified drone : identified) {
            if (drone.target().isEmpty() || !hasHead(drone.target().get().type())) {
                continue;
            }
            UplinkContactsPayload.Target target = drone.target().get();
            int x = Math.round(sx(target.dx(), midX, ppb)) - TARGET_HEAD / 2;
            int y = Math.round(sy(target.dz(), midY, ppb)) - TARGET_HEAD / 2;
            graphics.fill(x - 1, y - 1, x + TARGET_HEAD + 1, y + TARGET_HEAD + 1, MapShapes.OUTLINE);
            if (target.type() == EntityType.PLAYER) {
                EntityPreview.drawFace(graphics, target.playerName(), x, y, TARGET_HEAD);
            } else {
                var pose = graphics.pose();
                pose.pushPose();
                // A head item fills about 3/4 of its 16 px icon, so scale it a little past the box.
                pose.translate(x - 1.5F, y - 1.5F, 0);
                pose.scale((TARGET_HEAD + 3) / 16F, (TARGET_HEAD + 3) / 16F, 1);
                graphics.renderItem(new ItemStack(MOB_HEADS.get(target.type())), 0, 0);
                pose.popPose();
            }
        }
    }

    /** Five fading dots behind the blip, opposite its heading. */
    private static void drawTail(VertexConsumer buffer, Matrix4f pose, float x, float y, float yaw, int color, float fade) {
        // Minecraft's heading is (-sin yaw, cos yaw) in X and Z, and the map's X and Y are the world's X and Z.
        float dirX = -Mth.sin(yaw * Mth.DEG_TO_RAD);
        float dirY = Mth.cos(yaw * Mth.DEG_TO_RAD);
        for (int i = 1; i <= TAIL_DOTS; i++) {
            float back = 2 + i * 1.6F;
            float alpha = 0.8F * (1 - i / (TAIL_DOTS + 1F)) * fade;
            MapShapes.square(buffer, pose, x - dirX * back, y - dirY * back, 0.5F, MapShapes.withAlpha(color, alpha));
        }
    }

    /** "N" with a small arrowhead, at the top left. */
    private static void drawNorth(GuiGraphics graphics, Font font, int x, int y) {
        Kit.smallText(graphics, font, "N", x + 4, y + 4, Kit.DISPLAY_TEXT, true);
        graphics.fill(x + 5, y + 2, x + 6, y + 3, Kit.DISPLAY_TEXT);
        graphics.fill(x + 4, y + 3, x + 7, y + 4, Kit.DISPLAY_TEXT);
    }

    /** A bar of a round number of meters, and the zoom level, at the bottom left. */
    private void drawScale(GuiGraphics graphics, Font font, int x, int bottom, float ppb) {
        int blocks = ppb < 2 ? 32 : ppb < 4 ? 16 : 8;
        int length = Math.round(blocks * ppb);
        int x0 = x + 4;
        int y0 = bottom - 6;
        String meters = Component.translatable("screen.seekerdrones.uplink.meters", blocks).getString();
        String level = zoomLabel();
        int width = Math.max(length, Kit.smallWidth(font, meters)) + 4 + Kit.smallWidth(font, level);
        graphics.fill(x0 - 1, y0 - 8, x0 + width + 2, y0 + 3, SCALE_BACKGROUND);
        graphics.fill(x0, y0, x0 + length, y0 + 1, Kit.DISPLAY_TEXT);
        graphics.fill(x0, y0 - 2, x0 + 1, y0 + 1, Kit.DISPLAY_TEXT);
        graphics.fill(x0 + length - 1, y0 - 2, x0 + length, y0 + 1, Kit.DISPLAY_TEXT);
        Kit.smallText(graphics, font, meters, x0, y0 - 7, Kit.DISPLAY_TEXT, false);
        Kit.smallText(graphics, font, level, x0 + Math.max(length, Kit.smallWidth(font, meters)) + 4, y0 - 4, Kit.DISPLAY_TEXT_DIM, false);
    }

    // --- Helpers ---

    private float sx(double dx, float midX, float ppb) {
        return midX + (float) ((dx - viewX) * ppb);
    }

    private float sy(double dz, float midY, float ppb) {
        return midY + (float) ((dz - viewZ) * ppb);
    }

    private static double distanceSqr(float x, float y, int mouseX, int mouseY) {
        double dx = x - mouseX;
        double dy = y - mouseY;
        return dx * dx + dy * dy;
    }

    private static int textColor(Identified drone) {
        return 0xFF000000 | drone.color().getTextColor();
    }

    /** A vanilla map pixel (color id × 4 + brightness) in ARGB. */
    private static int argb(byte packed) {
        int value = packed & 0xFF;
        MapColor color = MapColor.byId(value >> 2);
        int modifier = MapColor.Brightness.byId(value & 3).modifier;
        int rgb = color.col;
        return FastColor.ARGB32.color(255, (rgb >> 16 & 0xFF) * modifier / 255, (rgb >> 8 & 0xFF) * modifier / 255, (rgb & 0xFF) * modifier / 255);
    }

    @Override
    public void close() {
        texture.close();
    }
}
