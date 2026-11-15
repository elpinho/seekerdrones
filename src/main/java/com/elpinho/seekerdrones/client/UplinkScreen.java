package com.elpinho.seekerdrones.client;

import java.util.List;

import javax.annotation.Nullable;

import com.elpinho.seekerdrones.client.gui.Kit;
import com.elpinho.seekerdrones.client.gui.KitWidget;
import com.elpinho.seekerdrones.client.gui.StatusStrip;
import com.elpinho.seekerdrones.client.radar.StationScreen;
import com.elpinho.seekerdrones.client.radar.UplinkMap;
import com.elpinho.seekerdrones.config.ClientConfig;
import com.elpinho.seekerdrones.config.ListSort;
import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.network.UplinkContactsPayload;
import com.elpinho.seekerdrones.network.UplinkContactsPayload.Identified;
import com.elpinho.seekerdrones.network.UplinkTerrainPayload;
import com.elpinho.seekerdrones.uplink.UplinkMenu;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Satellite Uplink screen (DESIGN.md section 7.9, layout B) on the shared {@link StationScreen}: the My drones list
 * has one line per identified drone and an "Unidentified: N" line. Clicking an identified drone (its blip or its row)
 * opens its Drone Remote screen on top; Back or Esc there comes back here.
 */
public class UplinkScreen extends StationScreen<UplinkMenu, UplinkMap> {
    private static final int LIST_WIDTH = 70;
    private static final int ROW_HEIGHT = 8;
    private static final int UNIDENTIFIED_GAP = 4;
    /** The Uplink gets no states, so it sorts by distance or name only. */
    private static final List<ListSort> SORTS = List.of(ListSort.DISTANCE_NEAREST, ListSort.DISTANCE_FARTHEST, ListSort.NAME_A_TO_Z,
            ListSort.NAME_Z_TO_A);

    private List<Identified> rows = List.of();
    @Nullable
    private Identified rowHover;

    public UplinkScreen(UplinkMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, LIST_WIDTH, UplinkMap::new);
    }

    public static void onTerrain(UplinkTerrainPayload payload) {
        UplinkMap target = openMap(payload.pos(), UplinkMap.class);
        if (target != null) {
            target.setTerrain(payload.map());
        }
    }

    public static void onContacts(UplinkContactsPayload payload) {
        UplinkMap target = openMap(payload.pos(), UplinkMap.class);
        if (target != null) {
            target.setContacts(payload);
        }
    }

    @Override
    protected String redstoneKey() {
        return "screen.seekerdrones.uplink.redstone";
    }

    @Override
    protected int configRadius() {
        return ServerConfig.get(ServerConfig.UPLINK_RADIUS);
    }

    @Override
    protected StatusStrip.Status status() {
        if (!menu.isPowered()) {
            return new StatusStrip.Status(Kit.Light.BAD, true, Component.translatable("screen.seekerdrones.radar_station.status.no_power"));
        }
        if (!map.hasTerrain()) {
            return new StatusStrip.Status(Kit.Light.WARN, false, Component.translatable("screen.seekerdrones.uplink.status.downloading"));
        }
        return new StatusStrip.Status(Kit.Light.OK, false,
                Component.translatable("screen.seekerdrones.uplink.status.linked", map.identified().size(), map.unidentifiedCount()));
    }

    // --- Rendering ---

    @Override
    protected void prepareMap(int mouseX, int mouseY, float partialTick) {
        rows = sortedRows();
        rowHover = rowAt(mouseX, mouseY);
        map.prepare(rowHover, ServerConfig.get(ServerConfig.UPLINK_PULSE_TICKS));
    }

    @Override
    protected void renderList(GuiGraphics graphics, int x, int top, int mouseX, int mouseY) {
        int rowY = top;
        for (Identified drone : rows) {
            if (drone == rowHover) {
                graphics.fill(x + 1, rowY - 1, x + listWidth - 1, rowY + ROW_HEIGHT - 1, ROW_HIGHLIGHT);
            }
            int color = 0xFF000000 | drone.color().getTextColor();
            graphics.fill(x + 3, rowY + 1, x + 6, rowY + 4, color);
            String distance = Component.translatable("screen.seekerdrones.uplink.meters_short", Math.round(Mth.length(drone.dx(), drone.dz()))).getString();
            int distanceWidth = Kit.smallWidth(font, distance);
            // With a shadow like the label, so both sit on the same line.
            Kit.smallText(graphics, font, distance, x + listWidth - 3 - distanceWidth, rowY, Kit.DISPLAY_TEXT_DIM, true);
            String label = drone.label().isEmpty() ? drone.droneId() : drone.label();
            int maxWidth = listWidth - 8 - 3 - distanceWidth - 3;
            Kit.smallText(graphics, font, clip(label, maxWidth), x + 8, rowY, color, true);
            rowY += ROW_HEIGHT;
        }
        int unidentified = map.unidentifiedCount();
        Kit.smallText(graphics, font, Component.translatable("screen.seekerdrones.uplink.unidentified", unidentified).getString(),
                x + 3, rowY + UNIDENTIFIED_GAP, unidentified > 0 ? AMBER : Kit.DISPLAY_TEXT_DIM, false);
    }

    /** The rows plus the Unidentified line (about one and a half rows with its gap). */
    @Override
    protected int listContentHeight() {
        return (rows.size() + 2) * ROW_HEIGHT;
    }

    @Override
    protected int scrollStep() {
        return ROW_HEIGHT;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        if (rowHover != null) {
            KitWidget.showTooltip(UplinkMap.identifiedTooltip(rowHover));
        } else if (!map.tooltip().isEmpty()) {
            KitWidget.showTooltip(map.tooltip());
        }
    }

    // --- Input ---

    /**
     * The map and list are handled before the container screen, which takes every click inside its frame. The zoom
     * buttons and side tabs still come first.
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && !overWidget(mouseX, mouseY)) {
            Identified clicked = rowAt(mouseX, mouseY);
            if (clicked == null && overMap(mouseX, mouseY) && map.hover() != null) {
                clicked = map.hover().identified();
            }
            if (clicked != null) {
                openRemote(clicked.droneId());
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Nullable
    private Identified rowAt(double mouseX, double mouseY) {
        int offset = listOffset(mouseX, mouseY);
        if (offset < 0) {
            return null;
        }
        int index = offset / ROW_HEIGHT;
        return index < rows.size() ? rows.get(index) : null;
    }

    /** The viewer's identified drones in the latest snapshot, in the chosen order. */
    private List<Identified> sortedRows() {
        return sorted(map.identified(), drone -> drone.dx() * drone.dx() + drone.dz() * drone.dz(),
                drone -> drone.label().isEmpty() ? drone.droneId() : drone.label(), drone -> 0);
    }

    @Override
    protected List<ListSort> sorts() {
        return SORTS;
    }

    @Override
    protected ModConfigSpec.EnumValue<ListSort> sortConfig() {
        return ClientConfig.UPLINK_LIST_SORT;
    }
}
