package com.elpinho.seekerdrones.client.radar;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import org.joml.Matrix4f;

import com.elpinho.seekerdrones.client.gui.Kit;
import com.elpinho.seekerdrones.network.CommandCenterContactsPayload;
import com.elpinho.seekerdrones.network.CommandCenterContactsPayload.Contact;
import com.elpinho.seekerdrones.network.CommandCenterContactsPayload.Details;
import com.elpinho.seekerdrones.network.CommandCenterContactsPayload.Kind;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.util.FastColor;
import net.minecraft.util.Mth;

/**
 * The square map of the Command Center screen (DESIGN.md section 7.10), on the shared {@link StationMap}: every drone
 * in the area always shown and steady, own drones as dye-colored squares, other players' as dye-colored diamonds and
 * unowned ones as gray diamonds, each with a heading tail and an altitude cue, and every drone's tracking line. Own
 * and other players' blips move smoothly between snapshots; unowned ones jump (they have no ID to match them by).
 * <p>
 * It also keeps the selection (by drone ID) and draws it, the selection box being dragged, and the aim of an order.
 */
public final class CommandCenterMap extends StationMap {
    public static final int TEAL = Kit.DISPLAY_TEXT;
    private static final int TEAL_FILL = 0x308FE5D6;
    public static final int RED = 0xFFE8281E;
    private static final int UNOWNED = 0xFFD2D2D2;
    private static final int UNOWNED_CORE = 0xFF5A5A5A;
    private static final int UNOWNED_TAIL = 0xFFC8C8C8;
    /** Space (GUI pixels) between the selected blips and the selection outline. */
    private static final float SELECTION_MARGIN = 4;

    /** A drone where it is drawn this frame: its latest contact, and its position between snapshots. */
    public record Blip(Contact contact, float dx, float dz) {
        public boolean own() {
            return contact.kind() == Kind.OWN;
        }
    }

    /** The selection box being dragged, in GUI pixels, or null. */
    public record Box(float x0, float y0, float x1, float y1) {}

    /**
     * An order being aimed: lines from each drone that can take it to the spot (in blocks from the station), the
     * marker, red for Strike, and whether the spot can be picked.
     *
     * @param placed for Strike, the crosshair is placed and waits for the confirming click
     */
    public record Aim(List<String> droneIds, double dx, double dz, boolean strike, boolean valid, boolean placed) {}

    private List<Contact> contacts = List.of();
    /** Where own and other players' blips were drawn when the latest snapshot came, to move smoothly from. */
    private final Map<String, float[]> from = new HashMap<>();
    /** The latest details of own and other players' drones, by ID. */
    private final Map<String, Details> details = new HashMap<>();
    private final Set<String> selection = new LinkedHashSet<>();
    private double snapshotTime = Double.NaN;
    private int scanInterval = 5;
    private List<Blip> blips = List.of();
    @Nullable
    private Blip hover;
    /** The drone of a hovered list row, drawn as if its blip were hovered. */
    @Nullable
    private String listHover;
    @Nullable
    private Box box;
    @Nullable
    private Aim aim;
    /** The selection outline's bounds in GUI pixels in the last frame, or null without a selection. */
    @Nullable
    private float[] selectionBounds;

    public CommandCenterMap() {
        super("command_center_terrain");
    }

    // --- Data ---

    /**
     * Takes a new snapshot at {@code time} (world ticks). Blips start from where they are drawn now, so they don't
     * jump. Drones that left the area or stopped being the viewer's drop out of the selection.
     */
    public void setContacts(CommandCenterContactsPayload payload, double time) {
        from.clear();
        for (Blip blip : blips(time)) {
            if (blip.contact().kind() != Kind.UNOWNED) {
                from.put(blip.contact().droneId(), new float[] {blip.dx(), blip.dz()});
            }
        }
        Set<String> present = new java.util.HashSet<>();
        Set<String> own = new java.util.HashSet<>();
        for (Contact contact : payload.contacts()) {
            if (contact.kind() == Kind.UNOWNED) {
                continue;
            }
            present.add(contact.droneId());
            if (contact.kind() == Kind.OWN) {
                own.add(contact.droneId());
            }
            contact.details().ifPresent(value -> details.put(contact.droneId(), value));
        }
        details.keySet().retainAll(present);
        selection.retainAll(own);
        contacts = payload.contacts();
        snapshotTime = time;
        setStations(payload.stations());
    }

    /** Sets the frame's interpolation length, hovered list row, selection box and aim, before {@link #render}. */
    public void prepare(int scanInterval, @Nullable String listHover, @Nullable Box box, @Nullable Aim aim) {
        this.scanInterval = Math.max(1, scanInterval);
        this.listHover = listHover;
        this.box = box;
        this.aim = aim;
    }

    public List<Contact> contacts() {
        return contacts;
    }

    /** The latest details of an own or other player's drone, or of an unowned contact, if any. */
    @Nullable
    public Details details(Contact contact) {
        return contact.kind() == Kind.UNOWNED ? contact.details().orElse(null) : details.get(contact.droneId());
    }

    public int count(Kind kind) {
        int count = 0;
        for (Contact contact : contacts) {
            if (contact.kind() == kind) {
                count++;
            }
        }
        return count;
    }

    /** The own drone with this ID in the latest snapshot, or null. */
    @Nullable
    public Contact own(String droneId) {
        for (Contact contact : contacts) {
            if (contact.kind() == Kind.OWN && contact.droneId().equals(droneId)) {
                return contact;
            }
        }
        return null;
    }

    /** What the mouse pointed at in the last frame. */
    @Nullable
    public Blip hover() {
        return hover;
    }

    /** The blips as drawn in the last frame. */
    public List<Blip> blips() {
        return blips;
    }

    // --- Selection ---

    public Set<String> selection() {
        return selection;
    }

    public void select(Collection<String> droneIds, boolean add) {
        if (!add) {
            selection.clear();
        }
        selection.addAll(droneIds);
    }

    public void toggle(String droneId) {
        if (!selection.remove(droneId)) {
            selection.add(droneId);
        }
    }

    public void clearSelection() {
        selection.clear();
    }

    /** The own drones whose blips are inside the box (GUI pixels), in the last frame. */
    public List<String> inBox(Box box) {
        float minX = Math.min(box.x0(), box.x1());
        float maxX = Math.max(box.x0(), box.x1());
        float minY = Math.min(box.y0(), box.y1());
        float maxY = Math.max(box.y0(), box.y1());
        List<String> found = new ArrayList<>();
        for (Blip blip : blips) {
            float x = sx(blip.dx());
            float y = sy(blip.dz());
            if (blip.own() && x >= minX && x <= maxX && y >= minY && y <= maxY) {
                found.add(blip.contact().droneId());
            }
        }
        return found;
    }

    /** The selection outline's bounds (minX, minY, maxX, maxY) in GUI pixels in the last frame, or null. */
    @Nullable
    public float[] selectionBounds() {
        return selectionBounds;
    }

    /** Where the blip of an own drone was drawn in the last frame, in blocks from the station, or null. */
    @Nullable
    public Blip blip(String droneId) {
        for (Blip blip : blips) {
            if (blip.contact().kind() != Kind.UNOWNED && blip.contact().droneId().equals(droneId)) {
                return blip;
            }
        }
        return null;
    }

    // --- Rendering ---

    /** The contacts at their positions at {@code time}, between the last snapshot's start and end positions. */
    private List<Blip> blips(double time) {
        float t = Double.isNaN(snapshotTime) ? 1 : (float) Mth.clamp((time - snapshotTime) / scanInterval, 0, 1);
        List<Blip> result = new ArrayList<>(contacts.size());
        for (Contact contact : contacts) {
            float[] start = contact.kind() == Kind.UNOWNED ? null : from.get(contact.droneId());
            if (start == null) {
                result.add(new Blip(contact, contact.dx(), contact.dz()));
            } else {
                result.add(new Blip(contact, Mth.lerp(t, start[0], contact.dx()), Mth.lerp(t, start[1], contact.dz())));
            }
        }
        return result;
    }

    @Override
    protected void updateHover(boolean active, int mouseX, int mouseY, double time) {
        blips = blips(time);
        hover = null;
        if (!active) {
            return;
        }
        double best = HOVER * HOVER;
        for (Blip blip : blips) {
            double d = distanceSqr(sx(blip.dx()), sy(blip.dz()), mouseX, mouseY);
            // Own drones win ties, so they stay clickable under someone else's.
            if (d < best || d == best && blip.own()) {
                best = d;
                hover = blip;
            }
        }
    }

    /** The highlighted drone: a hovered list row, else the hovered blip. */
    @Nullable
    private Blip highlighted() {
        if (listHover != null) {
            Blip row = blip(listHover);
            if (row != null) {
                return row;
            }
        }
        return hover;
    }

    public static int color(Contact contact) {
        return contact.kind() == Kind.UNOWNED ? UNOWNED : 0xFF000000 | contact.color().getTextColor();
    }

    @Override
    protected void drawUnderlay(VertexConsumer buffer, Matrix4f pose) {
        Blip highlighted = highlighted();
        if (highlighted == null || fade <= 0) {
            return;
        }
        Details detail = details(highlighted.contact());
        if (detail != null && detail.patrol().isPresent()) {
            drawPatrolRing(buffer, pose, detail.patrol().get(), color(highlighted.contact()));
        }
    }

    @Override
    protected List<TrackingLine> trackingLines() {
        List<TrackingLine> lines = new ArrayList<>();
        for (Blip blip : blips) {
            Contact contact = blip.contact();
            contact.target().ifPresent(target -> lines.add(new TrackingLine(blip.dx(), blip.dz(), target, color(contact))));
        }
        return lines;
    }

    @Override
    protected void drawContacts(VertexConsumer buffer, Matrix4f pose, double time) {
        if (fade <= 0) {
            selectionBounds = null;
            return;
        }
        Blip highlighted = highlighted();
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        // Unowned under other players' under own, so the viewer's drones stay on top.
        for (Kind kind : new Kind[] {Kind.UNOWNED, Kind.OTHER, Kind.OWN}) {
            for (Blip blip : blips) {
                Contact contact = blip.contact();
                if (contact.kind() != kind) {
                    continue;
                }
                float px = sx(blip.dx());
                float py = sy(blip.dz());
                int color = color(contact);
                switch (kind) {
                    case UNOWNED -> {
                        drawTail(buffer, pose, px, py, contact.yaw(), UNOWNED_TAIL);
                        drawDiamondBlip(buffer, pose, px, py, UNOWNED, UNOWNED_CORE, fade);
                    }
                    case OTHER -> {
                        drawTail(buffer, pose, px, py, contact.yaw(), color);
                        drawDiamondBlip(buffer, pose, px, py, color, darker(color), fade);
                    }
                    case OWN -> {
                        drawTail(buffer, pose, px, py, contact.yaw(), color);
                        boolean selected = selection.contains(contact.droneId());
                        if (selected) {
                            MapShapes.outline(buffer, pose, px, py, BLIP + 2, 1, MapShapes.withAlpha(MapShapes.OUTLINE, fade));
                            MapShapes.outline(buffer, pose, px, py, BLIP + 1.5F, 0.5F, MapShapes.withAlpha(MapShapes.PLAYER, fade));
                            minX = Math.min(minX, px);
                            minY = Math.min(minY, py);
                            maxX = Math.max(maxX, px);
                            maxY = Math.max(maxY, py);
                        }
                        drawSquareBlip(buffer, pose, px, py, color, fade);
                    }
                }
                if (highlighted != null && blip.contact() == highlighted.contact()) {
                    float half = kind == Kind.OWN ? BLIP + 1.5F : DIAMOND + 2;
                    MapShapes.outline(buffer, pose, px, py, half, 0.5F, MapShapes.withAlpha(MapShapes.PLAYER, fade));
                }
            }
        }
        selectionBounds = minX <= maxX
                ? new float[] {minX - SELECTION_MARGIN, minY - SELECTION_MARGIN, maxX + SELECTION_MARGIN, maxY + SELECTION_MARGIN}
                : null;
        if (selectionBounds != null) {
            dashedRect(buffer, pose, selectionBounds[0], selectionBounds[1], selectionBounds[2], selectionBounds[3], MapShapes.withAlpha(TEAL, fade));
        }
    }

    /** Labels (own and other players' drones) and altitude cues. When two would overlap, the later one is hidden. */
    @Override
    protected void drawOverlay(GuiGraphics graphics, Font font, double time) {
        if (fade <= 0) {
            return;
        }
        List<Rect2i> taken = new ArrayList<>();
        // Own labels first, so they win overlaps.
        for (Kind kind : new Kind[] {Kind.OWN, Kind.OTHER, Kind.UNOWNED}) {
            for (Blip blip : blips) {
                Contact contact = blip.contact();
                if (contact.kind() != kind) {
                    continue;
                }
                float offset = kind == Kind.OWN ? BLIP + 2 : DIAMOND + 2;
                drawLabel(graphics, font, taken, contact.name(), color(contact), contact.dy(), sx(blip.dx()) + offset, sy(blip.dz()) - 3,
                        Math.max(fade, 0.05F));
            }
        }
    }

    /** The selection box and the aim of an order, over everything else. */
    @Override
    protected void drawTop(GuiGraphics graphics, Font font, double time) {
        if (box == null && aim == null) {
            return;
        }
        drawShapes(graphics, (buffer, pose) -> {
            if (box != null) {
                float x0 = Math.min(box.x0(), box.x1());
                float y0 = Math.min(box.y0(), box.y1());
                float x1 = Math.max(box.x0(), box.x1());
                float y1 = Math.max(box.y0(), box.y1());
                MapShapes.quad(buffer, pose, x0, y0, x1, y1, TEAL_FILL);
                dashedRect(buffer, pose, x0, y0, x1, y1, TEAL);
            }
            if (aim != null) {
                float tx = sx(aim.dx());
                float ty = sy(aim.dz());
                int color = !aim.valid() || aim.strike() ? RED : TEAL;
                for (String id : aim.droneIds()) {
                    Blip blip = blip(id);
                    if (blip != null) {
                        dashedLine(buffer, pose, sx(blip.dx()), sy(blip.dz()), tx, ty, BLIP + 2, 3, 0.5F, MapShapes.withAlpha(color, 0.8F));
                    }
                }
                if (aim.strike()) {
                    float r = aim.placed() ? 4 : 3;
                    MapShapes.ring(buffer, pose, tx, ty, r, 0.75F, color);
                    MapShapes.line(buffer, pose, tx - r - 2, ty, tx + r + 2, ty, 0.75F, color);
                    MapShapes.line(buffer, pose, tx, ty - r - 2, tx, ty + r + 2, 0.75F, color);
                } else {
                    MapShapes.ring(buffer, pose, tx, ty, 2.5F, 0.75F, color);
                }
            }
        });
    }

    /** A rectangle of dashed lines. */
    private static void dashedRect(VertexConsumer buffer, Matrix4f pose, float x0, float y0, float x1, float y1, int color) {
        dashedLine(buffer, pose, x0, y0, x1, y0, 0, 0, 0.5F, color);
        dashedLine(buffer, pose, x1, y0, x1, y1, 0, 0, 0.5F, color);
        dashedLine(buffer, pose, x1, y1, x0, y1, 0, 0, 0.5F, color);
        dashedLine(buffer, pose, x0, y1, x0, y0, 0, 0, 0.5F, color);
    }

    private static int darker(int color) {
        return FastColor.ARGB32.color(255, FastColor.ARGB32.red(color) / 2, FastColor.ARGB32.green(color) / 2, FastColor.ARGB32.blue(color) / 2);
    }
}
