package com.elpinho.seekerdrones.client.radar;

import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import org.joml.Matrix4f;

import com.elpinho.seekerdrones.client.gui.EntityPreview;
import com.elpinho.seekerdrones.client.gui.Kit;
import com.elpinho.seekerdrones.network.MapMarkers;
import com.elpinho.seekerdrones.radar.ColorMap;
import com.elpinho.seekerdrones.radar.RadarMap;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;

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
 * The square map shared by the Satellite Uplink and the Command Center screens (DESIGN.md sections 7.9 and 7.10): the
 * terrain in vanilla map colors, the area's edge with the outside hatched, the station, the Charging Stations, the
 * viewing player, the north marker and the scale bar. It zooms in steps (FIT, ×1, ×2, ×4 px per block) anchored on
 * the mouse, pans, and keeps the view inside the area's bounding square. Subclasses draw the drones. Client-side only.
 * <p>
 * {@link #sx} and {@link #sy} turn block offsets from the station into GUI pixels of the last frame drawn.
 */
public abstract class StationMap implements AutoCloseable {
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
    private static final int STATION_WHITE = 0xFFF0F0F0;
    private static final int STATION_RED = 0xFFC42620;
    protected static final int ALTITUDE = 0xFFAAB4B9;
    private static final int LABEL_BACKING = 0x9A0A0E10;
    private static final int SCALE_BACKGROUND = 0xAA0A0E10;
    private static final int UNPOWERED_SHADE = 0xB0080B0D;
    /** Half the square blip's size, in GUI pixels. */
    protected static final float BLIP = 1.5F;
    protected static final float DIAMOND = 2.5F;
    /** How close (GUI pixels) the mouse must be to a blip for its tooltip. */
    protected static final float HOVER = 3.5F;
    private static final int TAIL_DOTS = 5;
    /** The length of the dashes (and gaps) of target and patrol lines, in GUI pixels. */
    protected static final float DASH = 2;
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
    /** Ticks the blips take to fade out once the station loses power. */
    private static final float FADE_TICKS = 20;

    /** A drone's tracking line: from the blip at (dx, dz) to its target, in a color. */
    protected record TrackingLine(float dx, float dz, MapMarkers.Target target, int color) {}

    private final MapTexture texture;
    private int radius;
    /** The whole terrain map has arrived at least once. */
    private boolean hasTerrain;
    private List<MapMarkers.Station> stations = List.of();
    private int zoom;
    /** The view's center, in blocks from the station's center. */
    private double viewX;
    private double viewZ;
    /** When the station was last seen unpowered while it had been powered, or -1 while powered. */
    private double unpoweredSince = -1;

    /** The last frame's layout: the map's middle in GUI pixels and the pixels per block. */
    protected float midX;
    protected float midY;
    protected float ppb = 1;
    /** The last frame's blip opacity: 1 while powered, fading out after the power is lost. */
    protected float fade = 1;

    protected StationMap(String textureName) {
        this.texture = new MapTexture(textureName);
    }

    // --- Data ---

    /** A whole terrain map in map colors. */
    public void setTerrain(ColorMap map) {
        setTerrainRows(map.radius(), 0, map.colors(), true);
    }

    /**
     * Some rows of the terrain map, starting at {@code firstRow}: the whole map for the Uplink, a slice for the
     * Command Center (section 7.10). They are drawn as soon as they arrive; {@code last} marks the map complete.
     */
    public void setTerrainRows(int radius, int firstRow, byte[] colors, boolean last) {
        int size = RadarMap.size(radius);
        if (radius != this.radius) {
            hasTerrain = false;
        }
        this.radius = radius;
        float center = radius;
        float edge = radius + 0.5F;
        int rows = colors.length / size;
        texture.uploadRows(size, firstRow, firstRow + rows, (column, row) -> {
            float dx = column - center;
            float dz = row - center;
            if (dx * dx + dz * dz > edge * edge) {
                return (column + row) % 6 == 0 ? OUTSIDE_HATCH : OUTSIDE;
            }
            byte packed = colors[(row - firstRow) * size + column];
            return packed == ColorMap.UNKNOWN ? UNKNOWN : argb(packed);
        });
        if (last) {
            hasTerrain = true;
        }
        clampView(radius);
    }

    protected void setStations(List<MapMarkers.Station> stations) {
        this.stations = stations;
    }

    /** Whether the whole terrain map has arrived. */
    public boolean hasTerrain() {
        return hasTerrain;
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
        double mx = x + INNER / 2.0;
        double my = y + INNER / 2.0;
        float before = ppb(r);
        double blockX = viewX + (anchorX - mx) / before;
        double blockZ = viewZ + (anchorY - my) / before;
        zoom += step;
        float after = ppb(r);
        viewX = blockX - (anchorX - mx) / after;
        viewZ = blockZ - (anchorY - my) / after;
        clampView(r);
    }

    /** Moves the view with the mouse, by a drag of ({@code dragX}, {@code dragY}) GUI pixels, kept inside the area. */
    public void pan(double dragX, double dragY, int configRadius) {
        int r = mapRadius(configRadius);
        float scale = ppb(r);
        viewX -= dragX / scale;
        viewZ -= dragY / scale;
        clampView(r);
    }

    private float ppb(int r) {
        return zoom == 0 ? INNER / (float) RadarMap.size(r) : ZOOMS[zoom];
    }

    protected int mapRadius(int configRadius) {
        return texture.isReady() ? radius : configRadius;
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
     * Draws the map inside its inner area at ({@code x}, {@code y}), and lets the subclass work out what the mouse
     * points at.
     *
     * @param time   the world time in ticks, with the partial tick
     * @param player the viewing player's position relative to the station's center, in blocks (x, z), and yaw
     */
    public void render(GuiGraphics graphics, Font font, int x, int y, double time, int configRadius, boolean powered,
            double playerX, double playerZ, float playerYaw, int mouseX, int mouseY) {
        int r = mapRadius(configRadius);
        clampView(r);
        ppb = ppb(r);
        midX = x + INNER / 2F;
        midY = y + INNER / 2F;
        if (powered) {
            unpoweredSince = -1;
        } else if (unpoweredSince < 0) {
            unpoweredSince = time;
        }
        fade = powered ? 1 : (float) Mth.clamp(1 - (time - unpoweredSince) / FADE_TICKS, 0, 1);

        graphics.enableScissor(x, y, x + INNER, y + INNER);
        graphics.fill(x, y, x + INNER, y + INNER, OUTSIDE);
        if (texture.isReady()) {
            var pose = graphics.pose();
            pose.pushPose();
            pose.translate(sx(-radius - 0.5), sy(-radius - 0.5), 0);
            pose.scale(ppb, ppb, 1);
            int size = texture.size();
            graphics.blit(texture.location(), 0, 0, size, size, 0, 0, size, size, size, size);
            pose.popPose();
        }

        boolean inside = mouseX >= x && mouseX < x + INNER && mouseY >= y && mouseY < y + INNER;
        updateHover(fade > 0 && inside, mouseX, mouseY, time);

        // The shapes are built from quads in both windings, so culling is off while they're drawn.
        graphics.flush();
        RenderSystem.disableCull();
        Matrix4f pose = graphics.pose().last().pose();
        VertexConsumer buffer = graphics.bufferSource().getBuffer(RenderType.gui());
        MapShapes.ring(buffer, pose, sx(0), sy(0), r * ppb, 0.5F, EDGE);
        drawUnderlay(buffer, pose);
        for (MapMarkers.Station station : stations) {
            float px = sx(station.dx());
            float py = sy(station.dz());
            MapShapes.quad(buffer, pose, px - 3, py - 1.5F, px + 3, py + 1.5F, STATION_DARK);
            MapShapes.quad(buffer, pose, px - 2.5F, py - 1, px + 2.5F, py + 1, STATION_PAD);
            MapShapes.square(buffer, pose, px, py, 1, Kit.DISPLAY_TEXT);
        }
        float ux = sx(0);
        float uy = sy(0);
        MapShapes.square(buffer, pose, ux, uy, 2, STATION_DARK);
        MapShapes.square(buffer, pose, ux, uy, 1.5F, STATION_WHITE);
        MapShapes.square(buffer, pose, ux, uy, 0.5F, STATION_RED);
        if (playerX * playerX + playerZ * playerZ <= (double) r * r) {
            MapShapes.player(buffer, pose, sx(playerX), sy(playerZ), playerYaw);
        }
        List<TrackingLine> lines = fade > 0 ? trackingLines() : List.of();
        for (TrackingLine line : lines) {
            drawTargetLine(buffer, pose, line);
        }
        drawContacts(buffer, pose, time);
        graphics.flush();
        RenderSystem.enableCull();

        drawTargetHeads(graphics, lines);
        drawOverlay(graphics, font, time);
        drawNorth(graphics, font, x, y);
        drawScale(graphics, font, x, y + INNER);
        if (!powered) {
            graphics.fill(x, y, x + INNER, y + INNER, UNPOWERED_SHADE);
            Kit.scaledTextCentered(graphics, font, Component.translatable("screen.seekerdrones.radar_station.no_power"),
                    Math.round(midX), midY - 4, Kit.DISPLAY_TEXT_DIM, 1);
        }
        drawTop(graphics, font, time);
        graphics.disableScissor();
    }

    /** Works out what the mouse points at, before anything is drawn. */
    protected abstract void updateHover(boolean active, int mouseX, int mouseY, double time);

    /** Shapes under the markers, e.g. a hovered drone's patrol ring. */
    protected void drawUnderlay(VertexConsumer buffer, Matrix4f pose) {}

    /** The tracking lines to draw this frame. */
    protected abstract List<TrackingLine> trackingLines();

    /** The drones' blips and tails, as shapes. */
    protected abstract void drawContacts(VertexConsumer buffer, Matrix4f pose, double time);

    /** Text over the shapes, e.g. labels. */
    protected abstract void drawOverlay(GuiGraphics graphics, Font font, double time);

    /** Drawn last, over the unpowered shade, still inside the map. Nothing by default. */
    protected void drawTop(GuiGraphics graphics, Font font, double time) {}

    /** Draws map shapes (quads in both windings) outside the main shape pass. */
    protected static void drawShapes(GuiGraphics graphics, BiConsumer<VertexConsumer, Matrix4f> draw) {
        graphics.flush();
        RenderSystem.disableCull();
        draw.accept(graphics.bufferSource().getBuffer(RenderType.gui()), graphics.pose().last().pose());
        graphics.flush();
        RenderSystem.enableCull();
    }

    // --- Shared drawing ---

    /** A dashed patrol ring in the drone's color, with a dot at its center. */
    protected void drawPatrolRing(VertexConsumer buffer, Matrix4f pose, MapMarkers.Patrol patrol, int color) {
        int ringColor = MapShapes.withAlpha(color, 0.9F * fade);
        float px = sx(patrol.dx());
        float py = sy(patrol.dz());
        float ringRadius = patrol.radius() * ppb;
        int segments = Mth.clamp(Math.round(ringRadius * Mth.TWO_PI / 3), 12, 240);
        MapShapes.dashedRing(buffer, pose, px, py, ringRadius, 0.75F, ringColor, segments - segments % 2, 2);
        MapShapes.square(buffer, pose, px, py, 0.75F, ringColor);
    }

    /** A dye-colored square blip with a dark outline. */
    protected static void drawSquareBlip(VertexConsumer buffer, Matrix4f pose, float x, float y, int color, float alpha) {
        MapShapes.square(buffer, pose, x, y, BLIP + 0.5F, MapShapes.withAlpha(MapShapes.OUTLINE, alpha));
        MapShapes.square(buffer, pose, x, y, BLIP, MapShapes.withAlpha(color, alpha));
    }

    /** A diamond blip with a dark outline and a darker core. */
    protected static void drawDiamondBlip(VertexConsumer buffer, Matrix4f pose, float x, float y, int color, int core, float alpha) {
        MapShapes.diamond(buffer, pose, x, y, DIAMOND + 1, MapShapes.withAlpha(MapShapes.OUTLINE, alpha));
        MapShapes.diamond(buffer, pose, x, y, DIAMOND, MapShapes.withAlpha(color, alpha));
        MapShapes.diamond(buffer, pose, x, y, DIAMOND - 1, MapShapes.withAlpha(core, alpha));
    }

    /** A dashed line between two map points, in GUI pixels, starting {@code skip} px in and stopping {@code stopShort} short. */
    protected static void dashedLine(VertexConsumer buffer, Matrix4f pose, float x0, float y0, float x1, float y1, float skip, float stopShort,
            float width, int color) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float length = Mth.sqrt(dx * dx + dy * dy);
        if (length < 1.0E-3F) {
            return;
        }
        float stop = length - stopShort;
        for (float at = skip; at < stop; at += DASH * 2) {
            float end = Math.min(at + DASH, stop);
            MapShapes.line(buffer, pose, x0 + dx * at / length, y0 + dy * at / length, x0 + dx * end / length, y0 + dy * end / length, width, color);
        }
    }

    /**
     * A dashed line in the drone's color from the blip to the entity it tracks, ending at its head (or square). It
     * runs between map positions, so it follows the zoom.
     */
    private void drawTargetLine(VertexConsumer buffer, Matrix4f pose, TrackingLine line) {
        float x1 = sx(line.target().dx());
        float y1 = sy(line.target().dz());
        boolean head = hasHead(line.target().type());
        dashedLine(buffer, pose, sx(line.dx()), sy(line.dz()), x1, y1, BLIP + 2, head ? TARGET_HEAD / 2F + 1 : 2, 0.75F,
                MapShapes.withAlpha(line.color(), 0.85F * fade));
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
    private void drawTargetHeads(GuiGraphics graphics, List<TrackingLine> lines) {
        for (TrackingLine line : lines) {
            MapMarkers.Target target = line.target();
            if (!hasHead(target.type())) {
                continue;
            }
            int x = Math.round(sx(target.dx())) - TARGET_HEAD / 2;
            int y = Math.round(sy(target.dz())) - TARGET_HEAD / 2;
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
    protected void drawTail(VertexConsumer buffer, Matrix4f pose, float x, float y, float yaw, int color) {
        // Minecraft's heading is (-sin yaw, cos yaw) in X and Z, and the map's X and Y are the world's X and Z.
        float dirX = -Mth.sin(yaw * Mth.DEG_TO_RAD);
        float dirY = Mth.cos(yaw * Mth.DEG_TO_RAD);
        for (int i = 1; i <= TAIL_DOTS; i++) {
            float back = 2 + i * 1.6F;
            float alpha = 0.8F * (1 - i / (TAIL_DOTS + 1F)) * fade;
            MapShapes.square(buffer, pose, x - dirX * back, y - dirY * back, 0.5F, MapShapes.withAlpha(color, alpha));
        }
    }

    /**
     * A label (may be empty) and its altitude cue after it, unless they would overlap one already drawn: the later one
     * is hidden.
     */
    protected static void drawLabel(GuiGraphics graphics, Font font, List<Rect2i> taken, String label, int color, float dy, float x, float y,
            float alpha) {
        String altitude = altitude(dy);
        if (label.isEmpty() && altitude.isEmpty()) {
            return;
        }
        int labelWidth = label.isEmpty() ? 0 : Kit.smallWidth(font, label);
        int width = labelWidth + (altitude.isEmpty() ? 0 : (label.isEmpty() ? 0 : 2) + Kit.smallWidth(font, altitude));
        Rect2i area = new Rect2i(Math.round(x), Math.round(y), width, 6);
        if (taken.stream().anyMatch(other -> MapShapes.intersects(other, area))) {
            return;
        }
        taken.add(area);
        // A dark backing, so a label stays readable over any terrain (a blue label over water, a white one over snow).
        graphics.fill(Math.round(x) - 1, Math.round(y) - 1, Math.round(x) + width + 1, Math.round(y) + 6,
                MapShapes.withAlpha(LABEL_BACKING, alpha));
        if (!label.isEmpty()) {
            Kit.smallText(graphics, font, label, x, y, MapShapes.withAlpha(color, alpha), true);
        }
        if (!altitude.isEmpty()) {
            Kit.smallText(graphics, font, altitude, x + labelWidth + (label.isEmpty() ? 0 : 2), y, MapShapes.withAlpha(ALTITUDE, alpha), true);
        }
    }

    /** {@code ▲12} or {@code ▼4}: the drone's Y minus the station's, hidden within ±1. */
    private static String altitude(float dy) {
        int blocks = Math.round(dy);
        if (Math.abs(blocks) <= 1) {
            return "";
        }
        return (blocks > 0 ? "▲" : "▼") + Math.abs(blocks);
    }

    /** "N" with a small arrowhead, at the top left. */
    private static void drawNorth(GuiGraphics graphics, Font font, int x, int y) {
        Kit.smallText(graphics, font, "N", x + 4, y + 4, Kit.DISPLAY_TEXT, true);
        graphics.fill(x + 5, y + 2, x + 6, y + 3, Kit.DISPLAY_TEXT);
        graphics.fill(x + 4, y + 3, x + 7, y + 4, Kit.DISPLAY_TEXT);
    }

    /** A bar of a round number of meters, and the zoom level, at the bottom left. */
    private void drawScale(GuiGraphics graphics, Font font, int x, int bottom) {
        int blocks = ppb < 0.6F ? 64 : ppb < 2 ? 32 : ppb < 4 ? 16 : 8;
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

    /** The GUI x of a block offset from the station, in the last frame. */
    public float sx(double dx) {
        return midX + (float) ((dx - viewX) * ppb);
    }

    /** The GUI y of a block offset from the station, in the last frame. */
    public float sy(double dz) {
        return midY + (float) ((dz - viewZ) * ppb);
    }

    /** The block offset from the station under a GUI x, in the last frame. */
    public double blockX(double guiX) {
        return viewX + (guiX - midX) / ppb;
    }

    /** The block offset from the station under a GUI y, in the last frame. */
    public double blockZ(double guiY) {
        return viewZ + (guiY - midY) / ppb;
    }

    protected static double distanceSqr(float x, float y, double mouseX, double mouseY) {
        double dx = x - mouseX;
        double dy = y - mouseY;
        return dx * dx + dy * dy;
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
