package com.elpinho.seekerdrones.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.annotation.Nullable;

import com.elpinho.seekerdrones.client.gui.Kit;
import com.elpinho.seekerdrones.client.gui.KitButton;
import com.elpinho.seekerdrones.client.gui.KitWidget;
import com.elpinho.seekerdrones.client.gui.SideTab;
import com.elpinho.seekerdrones.client.gui.StatusStrip;
import com.elpinho.seekerdrones.client.radar.RadarMachineScreen;
import com.elpinho.seekerdrones.client.radar.UplinkMap;
import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.network.RequestRemoteStatusPayload;
import com.elpinho.seekerdrones.network.UplinkContactsPayload;
import com.elpinho.seekerdrones.network.UplinkContactsPayload.Identified;
import com.elpinho.seekerdrones.network.UplinkTerrainPayload;
import com.elpinho.seekerdrones.uplink.UplinkMenu;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Satellite Uplink screen (DESIGN.md section 7.9, layout B): the energy gauge, the square map, the My drones list, a
 * status strip, and the energy unit and Redstone side tabs. Clicking an identified drone (its blip or its row) asks the
 * server to open its Drone Remote screen on top; the Uplink's menu stays open meanwhile, and Back or Esc there comes
 * back here.
 * <p>
 * The map's data is kept per open menu, not per screen, so coming back from the remote screen or a feed shows the map
 * and zoom as they were (the server only sends the terrain when the menu opens and after a rebuild).
 */
public class UplinkScreen extends RadarMachineScreen<UplinkMenu> {
    private static final int LIST_X = MAP_X + UplinkMap.SIZE + 4;
    private static final int LIST_WIDTH = 70;
    private static final int WIDTH = LIST_X + LIST_WIDTH + 8;
    private static final int LIST_TOP = 11;
    private static final int ROW_HEIGHT = 8;
    private static final int UNIDENTIFIED_GAP = 4;
    private static final int ZOOM_BUTTON = 11;
    private static final int ROW_HIGHLIGHT = 0xFF1E3E42;
    private static final int AMBER = 0xFFE29628;
    private static final ResourceLocation REDSTONE = Kit.sprite("icon/redstone");

    /** The map of the open Uplink menu, kept while the player is in a drone's remote screen or feed. */
    @Nullable
    private static UplinkMap openMap;
    private static int openMapContainer = -1;

    private final UplinkMap map;
    private List<Identified> rows = List.of();
    private int listScroll;
    @Nullable
    private Identified rowHover;
    /** The right mouse button went down on the map and is dragging it. */
    private boolean panning;

    public UplinkScreen(UplinkMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, WIDTH, UplinkMap.SIZE);
        this.map = mapFor(menu);
        sideTabs.add(new SideTab(22, (graphics, x, y, mouseX, mouseY) -> graphics.blitSprite(REDSTONE, x + 8, y + 7, 8, 7))
                .tooltip(() -> List.of(
                        Component.translatable("screen.seekerdrones.uplink.redstone"),
                        Component.translatable("screen.seekerdrones.uplink.redstone.alarm").withStyle(ChatFormatting.GRAY),
                        Component.translatable("screen.seekerdrones.uplink.redstone.placer").withStyle(ChatFormatting.GRAY))));
    }

    private static UplinkMap mapFor(UplinkMenu menu) {
        if (openMap == null || openMapContainer != menu.containerId) {
            if (openMap != null) {
                openMap.close();
            }
            openMap = new UplinkMap();
            openMapContainer = menu.containerId;
        }
        return openMap;
    }

    /** The map of the player's open Uplink menu at {@code pos}, if any, whichever screen is showing. */
    @Nullable
    private static UplinkMap openMap(net.minecraft.core.BlockPos pos) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && player.containerMenu instanceof UplinkMenu menu && menu.getPos().equals(pos)) {
            return mapFor(menu);
        }
        return null;
    }

    public static void onTerrain(UplinkTerrainPayload payload) {
        UplinkMap target = openMap(payload.pos());
        if (target != null) {
            target.setTerrain(payload.map());
        }
    }

    public static void onContacts(UplinkContactsPayload payload) {
        UplinkMap target = openMap(payload.pos());
        if (target != null) {
            target.setContacts(payload);
        }
    }

    /**
     * Shows the Uplink screen again for the player's open Uplink menu, e.g. coming back from a drone's remote screen.
     * Returns false if the menu has been closed.
     */
    public static boolean reopen() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null && minecraft.player.containerMenu instanceof UplinkMenu menu) {
            minecraft.setScreen(new UplinkScreen(menu, minecraft.player.getInventory(), Component.translatable("screen.seekerdrones.uplink")));
            return true;
        }
        return false;
    }

    /** Whether the player has an Uplink's menu open, so a remote screen opened from it is still valid. */
    public static boolean isOpen() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null && player.containerMenu instanceof UplinkMenu;
    }

    // --- Widgets ---

    @Override
    protected void init() {
        super.init();
        int buttonX = leftPos + MAP_X + UplinkMap.SIZE - ZOOM_BUTTON - 4;
        int buttonY = topPos + MAP_Y + 4;
        addRenderableWidget(zoomButton(buttonX, buttonY, "+", 1));
        addRenderableWidget(zoomButton(buttonX, buttonY + ZOOM_BUTTON + 2, "-", -1));
    }

    private KitButton zoomButton(int x, int y, String symbol, int step) {
        KitButton button = new KitButton(x, y, ZOOM_BUTTON, ZOOM_BUTTON, KitButton.Style.DISPLAY,
                (graphics, bx, by, mouseX, mouseY) -> graphics.drawCenteredString(font, symbol, bx + ZOOM_BUTTON / 2 + 1, by + 2, Kit.DISPLAY_TEXT),
                () -> map.zoom(step, mapX() + UplinkMap.INNER / 2.0, mapY() + UplinkMap.INNER / 2.0, mapX(), mapY(), configRadius()));
        button.enabledWhen(() -> map.canZoom(step));
        button.tooltip(() -> List.of(Component.translatable(step > 0 ? "screen.seekerdrones.uplink.zoom_in" : "screen.seekerdrones.uplink.zoom_out"),
                Component.translatable("screen.seekerdrones.uplink.zoom_hint").withStyle(ChatFormatting.GRAY)));
        return button;
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
    protected void renderContents(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        rows = sortedRows();
        listScroll = Mth.clamp(listScroll, 0, maxScroll());
        rowHover = rowAt(mouseX, mouseY);

        Kit.display(graphics, leftPos + MAP_X, topPos + MAP_Y, UplinkMap.SIZE, UplinkMap.SIZE);
        Vec3 center = menu.getPos().getCenter();
        LocalPlayer player = minecraft.player;
        map.render(graphics, font, mapX(), mapY(), time(partialTick), ServerConfig.get(ServerConfig.UPLINK_PULSE_TICKS), configRadius(),
                menu.isPowered(), player.getX() - center.x, player.getZ() - center.z, player.getViewYRot(partialTick), mouseX, mouseY, rowHover);
        renderList(graphics);
    }

    private void renderList(GuiGraphics graphics) {
        int x = leftPos + LIST_X;
        int y = topPos + MAP_Y;
        Kit.display(graphics, x, y, LIST_WIDTH, UplinkMap.SIZE);
        Kit.smallText(graphics, font, Component.translatable("screen.seekerdrones.uplink.my_drones"), x + 3, y + 3, Kit.DISPLAY_TEXT_DIM, false);
        graphics.enableScissor(x + 1, y + LIST_TOP - 1, x + LIST_WIDTH - 1, y + UplinkMap.SIZE - 1);
        int rowY = y + LIST_TOP - listScroll * ROW_HEIGHT;
        Vec3 center = menu.getPos().getCenter();
        for (Identified drone : rows) {
            if (drone == rowHover) {
                graphics.fill(x + 1, rowY - 1, x + LIST_WIDTH - 1, rowY + ROW_HEIGHT - 1, ROW_HIGHLIGHT);
            }
            int color = 0xFF000000 | drone.color().getTextColor();
            graphics.fill(x + 3, rowY + 1, x + 6, rowY + 4, color);
            String distance = Component.translatable("screen.seekerdrones.uplink.meters_short", Math.round(Mth.length(drone.dx(), drone.dz()))).getString();
            int distanceWidth = Kit.smallWidth(font, distance);
            // With a shadow like the label, so both sit on the same line.
            Kit.smallText(graphics, font, distance, x + LIST_WIDTH - 3 - distanceWidth, rowY, Kit.DISPLAY_TEXT_DIM, true);
            String label = drone.label().isEmpty() ? drone.droneId() : drone.label();
            int maxWidth = LIST_WIDTH - 8 - 3 - distanceWidth - 3;
            Kit.smallText(graphics, font, clip(label, maxWidth), x + 8, rowY, color, true);
            rowY += ROW_HEIGHT;
        }
        int unidentified = map.unidentifiedCount();
        Kit.smallText(graphics, font, Component.translatable("screen.seekerdrones.uplink.unidentified", unidentified).getString(),
                x + 3, rowY + UNIDENTIFIED_GAP, unidentified > 0 ? AMBER : Kit.DISPLAY_TEXT_DIM, false);
        graphics.disableScissor();
    }

    private String clip(String text, int maxWidth) {
        if (Kit.smallWidth(font, text) <= maxWidth) {
            return text;
        }
        String ellipsis = "...";
        String clipped = text;
        while (!clipped.isEmpty() && Kit.smallWidth(font, clipped + ellipsis) > maxWidth) {
            clipped = clipped.substring(0, clipped.length() - 1);
        }
        return clipped + ellipsis;
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
        boolean overWidget = children().stream().anyMatch(child -> child.isMouseOver(mouseX, mouseY));
        if (!overWidget && button == 1 && overMap(mouseX, mouseY)) {
            panning = true;
            return true;
        }
        if (!overWidget && button == 0) {
            Identified clicked = rowAt(mouseX, mouseY);
            if (clicked == null && overMap(mouseX, mouseY) && map.hover() != null) {
                clicked = map.hover().identified();
            }
            if (clicked != null) {
                Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1));
                PacketDistributor.sendToServer(new RequestRemoteStatusPayload(clicked.droneId(), true));
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** Right-drag pans the map (left-drag stays free for the Command Center's box selection). */
    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (panning && button == 1) {
            map.pan(dragX, dragY, configRadius());
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (panning && button == 1) {
            panning = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int step = scrollY > 0 ? 1 : scrollY < 0 ? -1 : 0;
        if (step != 0 && overMap(mouseX, mouseY)) {
            map.zoom(step, mouseX, mouseY, mapX(), mapY(), configRadius());
            return true;
        }
        if (step != 0 && overList(mouseX, mouseY)) {
            listScroll = Mth.clamp(listScroll - step, 0, maxScroll());
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    // --- Layout helpers ---

    private int mapX() {
        return leftPos + MAP_X + UplinkMap.BORDER;
    }

    private int mapY() {
        return topPos + MAP_Y + UplinkMap.BORDER;
    }

    private boolean overMap(double mouseX, double mouseY) {
        return mouseX >= mapX() && mouseX < mapX() + UplinkMap.INNER && mouseY >= mapY() && mouseY < mapY() + UplinkMap.INNER;
    }

    private boolean overList(double mouseX, double mouseY) {
        return mouseX >= leftPos + LIST_X && mouseX < leftPos + LIST_X + LIST_WIDTH
                && mouseY >= topPos + MAP_Y && mouseY < topPos + MAP_Y + UplinkMap.SIZE;
    }

    @Nullable
    private Identified rowAt(double mouseX, double mouseY) {
        if (!overList(mouseX, mouseY)) {
            return null;
        }
        int offset = (int) Math.floor(mouseY - (topPos + MAP_Y + LIST_TOP - 1));
        if (offset < 0) {
            return null;
        }
        int index = offset / ROW_HEIGHT + listScroll;
        return index < rows.size() ? rows.get(index) : null;
    }

    private int visibleRows() {
        return (UplinkMap.SIZE - LIST_TOP - 2) / ROW_HEIGHT;
    }

    /** The rows plus the Unidentified line (about one and a half rows with its gap) may scroll. */
    private int maxScroll() {
        return Math.max(0, rows.size() + 2 - visibleRows());
    }

    /** The viewer's identified drones in the latest snapshot, nearest to the Uplink first. */
    private List<Identified> sortedRows() {
        List<Identified> sorted = new ArrayList<>(map.identified());
        sorted.sort(Comparator.comparingDouble(drone -> drone.dx() * drone.dx() + drone.dz() * drone.dz()));
        return sorted;
    }

    private static int configRadius() {
        return ServerConfig.get(ServerConfig.UPLINK_RADIUS);
    }

    @Override
    public void removed() {
        super.removed();
        // Going to a drone's remote screen keeps the menu, and the map with it, for coming back.
        if (!isOpen() && openMap != null) {
            openMap.close();
            openMap = null;
            openMapContainer = -1;
        }
    }
}
