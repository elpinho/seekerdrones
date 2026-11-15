package com.elpinho.seekerdrones.client.radar;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;

import javax.annotation.Nullable;

import com.elpinho.seekerdrones.client.gui.Kit;
import com.elpinho.seekerdrones.client.gui.KitButton;
import com.elpinho.seekerdrones.client.gui.SideTab;
import com.elpinho.seekerdrones.config.ClientConfig;
import com.elpinho.seekerdrones.config.ListSort;
import com.elpinho.seekerdrones.network.RequestRemoteStatusPayload;
import com.elpinho.seekerdrones.radar.RemoteStationMenu;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The screens of the radar machines a remote can be opened through (DESIGN.md sections 7.9 and 7.10, layout B): the
 * energy gauge, the square {@link StationMap} with its zoom buttons, a list panel on the right, a status strip, and
 * the energy unit and Redstone side tabs. Right-drag pans the map and the wheel zooms it (or scrolls the list over the
 * list). Opening a drone's remote asks the server, and the station's menu stays open meanwhile.
 * <p>
 * The map is kept per open menu, not per screen, so coming back from a drone's remote screen or feed shows the map and
 * zoom as they were (the server only sends the terrain when the menu opens and after a rebuild).
 */
public abstract class StationScreen<M extends RemoteStationMenu, P extends StationMap> extends RadarMachineScreen<M> {
    protected static final int LIST_TOP = 11;
    protected static final int ROW_HIGHLIGHT = 0xFF1E3E42;
    protected static final int AMBER = 0xFFE29628;
    private static final int ZOOM_BUTTON = 11;
    private static final int SORT_WIDTH = 15;
    private static final int SORT_HEIGHT = 9;
    private static final ResourceLocation REDSTONE = Kit.sprite("icon/redstone");

    /** The map of the open station menu, kept while the player is in a drone's remote screen or feed. */
    @Nullable
    private static StationMap openMap;
    private static int openMapContainer = -1;

    protected final P map;
    protected final int listX;
    protected final int listWidth;
    /** The list's scroll, in pixels. */
    protected int listScroll;
    /** The right mouse button went down on the map and is dragging it. */
    private boolean panning;
    /** How far (GUI pixels) the map was dragged since the right button went down. */
    private double panDistance;

    protected StationScreen(M menu, Inventory inventory, Component title, int listWidth, Supplier<P> newMap) {
        super(menu, inventory, title, MAP_X + StationMap.SIZE + 4 + listWidth + 8, StationMap.SIZE);
        this.listX = MAP_X + StationMap.SIZE + 4;
        this.listWidth = listWidth;
        this.map = mapFor(menu, newMap);
        String key = redstoneKey();
        sideTabs.add(new SideTab(22, (graphics, x, y, mouseX, mouseY) -> graphics.blitSprite(REDSTONE, x + 8, y + 7, 8, 7))
                .tooltip(() -> List.of(
                        Component.translatable(key),
                        Component.translatable(key + ".alarm").withStyle(ChatFormatting.GRAY),
                        Component.translatable(key + ".placer").withStyle(ChatFormatting.GRAY))));
    }

    /** The translation key of the Redstone tab's tooltip, with {@code .alarm} and {@code .placer} lines. */
    protected abstract String redstoneKey();

    /** The station's radius from the client's copy of the server config, until the map arrives. */
    protected abstract int configRadius();

    /** The sort orders the list's sort button cycles through, the first being the fallback. */
    protected abstract List<ListSort> sorts();

    /** Where the list's sort order is saved (the client config). */
    protected abstract ModConfigSpec.EnumValue<ListSort> sortConfig();

    /** The list's sort order: the saved one if this screen offers it, else the first. */
    protected ListSort sort() {
        ListSort saved = ClientConfig.get(sortConfig());
        return sorts().contains(saved) ? saved : sorts().get(0);
    }

    /**
     * Sorts the list's rows by {@link #sort()}: by distance or name either way, or by state (busiest first, by
     * {@code stateRank}). Ties go by name, then distance, so the order stays put while drones move.
     */
    protected <T> List<T> sorted(List<T> rows, ToDoubleFunction<T> distance, Function<T, String> name, ToIntFunction<T> stateRank) {
        Comparator<T> byName = Comparator.comparing(row -> name.apply(row).toLowerCase(Locale.ROOT));
        Comparator<T> byDistance = Comparator.comparingDouble(distance);
        Comparator<T> order = switch (sort()) {
            case DISTANCE_NEAREST -> byDistance.thenComparing(byName);
            case DISTANCE_FARTHEST -> byDistance.reversed().thenComparing(byName);
            case NAME_A_TO_Z -> byName.thenComparing(byDistance);
            case NAME_Z_TO_A -> byName.reversed().thenComparing(byDistance);
            case STATE -> Comparator.comparingInt(stateRank).thenComparing(byName).thenComparing(byDistance);
        };
        List<T> sorted = new ArrayList<>(rows);
        sorted.sort(order);
        return sorted;
    }

    @SuppressWarnings("unchecked")
    private static <P extends StationMap> P mapFor(RemoteStationMenu menu, Supplier<P> newMap) {
        if (openMap == null || openMapContainer != menu.containerId) {
            if (openMap != null) {
                openMap.close();
            }
            openMap = newMap.get();
            openMapContainer = menu.containerId;
        }
        return (P) openMap;
    }

    /** The map of the player's open station menu at {@code pos}, if any and of this type, whichever screen is showing. */
    @Nullable
    protected static <P extends StationMap> P openMap(BlockPos pos, Class<P> type) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && player.containerMenu instanceof RemoteStationMenu menu && menu.getPos().equals(pos)
                && openMapContainer == menu.containerId && type.isInstance(openMap)) {
            return type.cast(openMap);
        }
        return null;
    }

    /** Whether the player has a station's menu open, so a remote screen opened from it is still valid. */
    public static boolean isOpen() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null && player.containerMenu instanceof RemoteStationMenu;
    }

    // --- Widgets ---

    @Override
    protected void init() {
        super.init();
        int buttonX = leftPos + MAP_X + StationMap.SIZE - ZOOM_BUTTON - 4;
        int buttonY = topPos + MAP_Y + 4;
        addRenderableWidget(zoomButton(buttonX, buttonY, "+", 1));
        addRenderableWidget(zoomButton(buttonX, buttonY + ZOOM_BUTTON + 2, "-", -1));
        addRenderableWidget(sortButton(leftPos + listX + listWidth - 2 - SORT_WIDTH, topPos + MAP_Y + 1));
    }

    /** Pinned right in the list's header: what the list is sorted by, and the direction. A click cycles the orders. */
    private KitButton sortButton(int x, int y) {
        KitButton button = new KitButton(x, y, SORT_WIDTH, SORT_HEIGHT, KitButton.Style.DISPLAY, (graphics, bx, by, mouseX, mouseY) -> {
            ListSort sort = sort();
            graphics.blitSprite(Kit.sprite("icon/" + sort.icon()), bx + 2, by + 2, 5, 5);
            graphics.blitSprite(Kit.sprite(sort.down() ? "icon/sort_down" : "icon/sort_up"), bx + 8, by + 3, 5, 3);
        }, () -> {
            List<ListSort> sorts = sorts();
            ClientConfig.set(sortConfig(), sorts.get((sorts.indexOf(sort()) + 1) % sorts.size()));
            listScroll = 0;
        });
        button.tooltip(() -> List.of(Component.translatable("screen.seekerdrones.uplink.sort", Component.translatable(sort().getTranslationKey()))));
        return button;
    }

    private KitButton zoomButton(int x, int y, String symbol, int step) {
        KitButton button = new KitButton(x, y, ZOOM_BUTTON, ZOOM_BUTTON, KitButton.Style.DISPLAY,
                (graphics, bx, by, mouseX, mouseY) -> graphics.drawCenteredString(font, symbol, bx + ZOOM_BUTTON / 2 + 1, by + 2, Kit.DISPLAY_TEXT),
                () -> map.zoom(step, mapX() + StationMap.INNER / 2.0, mapY() + StationMap.INNER / 2.0, mapX(), mapY(), configRadius()));
        button.enabledWhen(() -> map.canZoom(step));
        button.tooltip(() -> List.of(Component.translatable(step > 0 ? "screen.seekerdrones.uplink.zoom_in" : "screen.seekerdrones.uplink.zoom_out"),
                Component.translatable("screen.seekerdrones.uplink.zoom_hint").withStyle(ChatFormatting.GRAY)));
        return button;
    }

    // --- Rendering ---

    /** Draws the map (after {@link #prepareMap}) and the list panel. */
    @Override
    protected void renderContents(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        prepareMap(mouseX, mouseY, partialTick);
        Kit.display(graphics, leftPos + MAP_X, topPos + MAP_Y, StationMap.SIZE, StationMap.SIZE);
        Vec3 center = menu.getPos().getCenter();
        LocalPlayer player = minecraft.player;
        map.render(graphics, font, mapX(), mapY(), time(partialTick), configRadius(), menu.isPowered(),
                player.getX() - center.x, player.getZ() - center.z, player.getViewYRot(partialTick), mouseX, mouseY);
        int x = leftPos + listX;
        int y = topPos + MAP_Y;
        Kit.display(graphics, x, y, listWidth, StationMap.SIZE);
        Kit.smallText(graphics, font, Component.translatable("screen.seekerdrones.uplink.my_drones"), x + 3, y + 3, Kit.DISPLAY_TEXT_DIM, false);
        listScroll = Mth.clamp(listScroll, 0, maxScroll());
        graphics.enableScissor(x + 1, y + LIST_TOP - 1, x + listWidth - 1, y + StationMap.SIZE - 1);
        renderList(graphics, x, y + LIST_TOP - listScroll, mouseX, mouseY);
        graphics.disableScissor();
        renderListFooter(graphics, x, y);
    }

    /** Updates the list's rows and hover, and tells the map, before anything is drawn. */
    protected abstract void prepareMap(int mouseX, int mouseY, float partialTick);

    /** The list's contents from {@code top} (already scrolled), clipped to the panel. */
    protected abstract void renderList(GuiGraphics graphics, int x, int top, int mouseX, int mouseY);

    /** Drawn under the list's clip, e.g. a pinned last line. Nothing by default. */
    protected void renderListFooter(GuiGraphics graphics, int x, int y) {}

    /** The list's content height in pixels, for scrolling. */
    protected abstract int listContentHeight();

    /** The visible height of the list's contents. */
    protected int listViewHeight() {
        return StationMap.SIZE - LIST_TOP - 2;
    }

    private int maxScroll() {
        return Math.max(0, listContentHeight() - listViewHeight());
    }

    /** Clips the text with "..." to fit in {@code maxWidth} small-text pixels. */
    protected String clip(String text, int maxWidth) {
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

    // --- Input ---

    /** Asks the server to open the drone's remote screen on top of this one. */
    protected static void openRemote(String droneId) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1));
        PacketDistributor.sendToServer(new RequestRemoteStatusPayload(droneId, true));
    }

    /** Whether the mouse is over one of the screen's widgets (zoom buttons, side tabs), which come first. */
    protected boolean overWidget(double mouseX, double mouseY) {
        return children().stream().anyMatch(child -> child.isMouseOver(mouseX, mouseY));
    }

    /** Right-click on the map starts panning. */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 1 && !overWidget(mouseX, mouseY) && overMap(mouseX, mouseY)) {
            panning = true;
            panDistance = 0;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** Right-drag pans the map (left-drag is kept for the Command Center's box selection). */
    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (panning && button == 1) {
            map.pan(dragX, dragY, configRadius());
            panDistance += Math.abs(dragX) + Math.abs(dragY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (panning && button == 1) {
            panning = false;
            if (panDistance < 3) {
                onRightClick(mouseX, mouseY);
            }
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /** A right-click on the map that didn't pan it. Nothing by default. */
    protected void onRightClick(double mouseX, double mouseY) {}

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int step = scrollY > 0 ? 1 : scrollY < 0 ? -1 : 0;
        if (step != 0 && overMap(mouseX, mouseY)) {
            map.zoom(step, mouseX, mouseY, mapX(), mapY(), configRadius());
            return true;
        }
        if (step != 0 && overList(mouseX, mouseY)) {
            listScroll = Mth.clamp(listScroll - step * scrollStep(), 0, maxScroll());
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /** Pixels the list scrolls per wheel step: one row. */
    protected abstract int scrollStep();

    // --- Layout helpers ---

    protected int mapX() {
        return leftPos + MAP_X + StationMap.BORDER;
    }

    protected int mapY() {
        return topPos + MAP_Y + StationMap.BORDER;
    }

    protected boolean overMap(double mouseX, double mouseY) {
        return mouseX >= mapX() && mouseX < mapX() + StationMap.INNER && mouseY >= mapY() && mouseY < mapY() + StationMap.INNER;
    }

    protected boolean overList(double mouseX, double mouseY) {
        return mouseX >= leftPos + listX && mouseX < leftPos + listX + listWidth
                && mouseY >= topPos + MAP_Y && mouseY < topPos + MAP_Y + StationMap.SIZE;
    }

    /** The y offset of the mouse inside the list's scrolled contents, or -1 outside them. */
    protected int listOffset(double mouseX, double mouseY) {
        if (!overList(mouseX, mouseY)) {
            return -1;
        }
        int offset = (int) Math.floor(mouseY - (topPos + MAP_Y + LIST_TOP - 1));
        return offset < 0 ? -1 : offset + listScroll;
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
