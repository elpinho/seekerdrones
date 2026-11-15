package com.elpinho.seekerdrones.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.annotation.Nullable;

import org.lwjgl.glfw.GLFW;

import com.elpinho.seekerdrones.client.gui.Kit;
import com.elpinho.seekerdrones.client.gui.KitWidget;
import com.elpinho.seekerdrones.client.gui.StatusStrip;
import com.elpinho.seekerdrones.client.radar.CommandCenterMap;
import com.elpinho.seekerdrones.client.radar.CommandCenterMap.Blip;
import com.elpinho.seekerdrones.client.radar.StationMap;
import com.elpinho.seekerdrones.client.radar.StationScreen;
import com.elpinho.seekerdrones.commandcenter.CommandCenterMenu;
import com.elpinho.seekerdrones.config.ClientConfig;
import com.elpinho.seekerdrones.config.ListSort;
import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.drone.DroneItem;
import com.elpinho.seekerdrones.drone.DroneStats;
import com.elpinho.seekerdrones.drone.UpgradeType;
import com.elpinho.seekerdrones.energy.EnergyFormat;
import com.elpinho.seekerdrones.network.CommandCenterContactsPayload;
import com.elpinho.seekerdrones.network.CommandCenterContactsPayload.Contact;
import com.elpinho.seekerdrones.network.CommandCenterContactsPayload.Details;
import com.elpinho.seekerdrones.network.CommandCenterContactsPayload.Kind;
import com.elpinho.seekerdrones.network.CommandCenterOrderPayload;
import com.elpinho.seekerdrones.network.CommandCenterOrderPayload.Mode;
import com.elpinho.seekerdrones.network.CommandCenterTerrainPayload;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Drone Command Center screen (DESIGN.md section 7.10, layout B) on the shared {@link StationScreen}: the map with
 * every drone in the area, a wider My drones list with health and energy bars, box selection of own drones, the mode
 * picker under the selection, aiming Move, Patrol Here and Strike on the map, and the Strike confirm. Clicking an own
 * drone opens its Drone Remote screen on top; Back or Esc there comes back here.
 */
public class CommandCenterScreen extends StationScreen<CommandCenterMenu, CommandCenterMap> {
    private static final String KEY = "screen.seekerdrones.command_center.";
    private static final int LIST_WIDTH = 96;
    private static final int ROW_HEIGHT = 15;
    private static final List<ListSort> SORTS = List.of(ListSort.values());
    /** The bars' offset under the row's top, leaving a small gap under the label. */
    private static final int BAR_Y = 8;
    private static final int BAR_HEIGHT = 3;
    private static final int FOOTER_GAP = 4;
    private static final int LINE = 8;
    private static final int SELECTED_ROW = 0xFF183236;
    private static final int BAR_BACKGROUND = 0xFF0A0E10;
    private static final int OTHERS = Kit.DISPLAY_TEXT_DIM;
    /** How far (GUI pixels) the mouse must move with the button down before it draws a box. */
    private static final int DRAG_THRESHOLD = 3;
    private static final int PICKER_BUTTON = 13;
    private static final int PICKER_WIDTH = 80;
    private static final int PICKER_HEIGHT = 15;
    /** How close (GUI pixels) a click must be to the placed Strike crosshair to confirm it. */
    private static final float CONFIRM_RADIUS = 5;
    private static final Map<Mode, ResourceLocation> MODE_ICONS = Map.of(
            Mode.MOVE, Kit.sprite("icon/route"),
            Mode.PATROL_HERE, Kit.sprite("icon/pin"),
            Mode.STRIKE, Kit.sprite("icon/detonate"),
            Mode.HOLD, Kit.sprite("icon/hold"),
            Mode.RETURN_TO_CHARGE, Kit.ICON_ENERGY,
            Mode.RELEASE, Kit.sprite("icon/stop"));

    private List<Contact> rows = List.of();
    @Nullable
    private Contact rowHover;
    /** Where the left button went down on the map, or null. */
    @Nullable
    private double[] press;
    private boolean pressShift;
    @Nullable
    private Blip pressBlip;
    @Nullable
    private CommandCenterMap.Box box;
    /** The mode being aimed, or null. */
    @Nullable
    private Mode aiming;
    /** The placed Strike crosshair, in blocks from the station, waiting for the confirming click. */
    @Nullable
    private double[] strikeSpot;
    /** The picker's top left in the last frame, or null while hidden. */
    @Nullable
    private int[] picker;

    public CommandCenterScreen(CommandCenterMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, LIST_WIDTH, CommandCenterMap::new);
    }

    public static void onTerrain(CommandCenterTerrainPayload payload) {
        CommandCenterMap target = openMap(payload.pos(), CommandCenterMap.class);
        if (target != null) {
            target.setTerrainRows(payload.radius(), payload.firstRow(), payload.colors(), payload.last());
        }
    }

    public static void onContacts(CommandCenterContactsPayload payload) {
        CommandCenterMap target = openMap(payload.pos(), CommandCenterMap.class);
        Minecraft minecraft = Minecraft.getInstance();
        if (target != null && minecraft.level != null) {
            target.setContacts(payload, minecraft.level.getGameTime() + minecraft.getTimer().getGameTimeDeltaPartialTick(false));
        }
    }

    @Override
    protected String redstoneKey() {
        return KEY + "redstone";
    }

    @Override
    protected int configRadius() {
        return ServerConfig.get(ServerConfig.COMMAND_CENTER_RADIUS);
    }

    // --- Status ---

    @Override
    protected StatusStrip.Status status() {
        if (!menu.isPowered()) {
            return new StatusStrip.Status(Kit.Light.BAD, true, Component.translatable("screen.seekerdrones.radar_station.status.no_power"));
        }
        if (!map.hasTerrain()) {
            return new StatusStrip.Status(Kit.Light.WARN, false, Component.translatable("screen.seekerdrones.uplink.status.downloading"));
        }
        MutableComponent text = Component.translatable(KEY + "status.online", map.count(Kind.OWN));
        int others = map.count(Kind.OTHER);
        int unowned = map.count(Kind.UNOWNED);
        if (others > 0) {
            text.append(Component.translatable(KEY + "status.others", others));
        }
        if (unowned > 0) {
            text.append(Component.translatable(KEY + "status.unowned", unowned));
        }
        if (!map.selection().isEmpty()) {
            text.append(Component.translatable(KEY + "status.selected", map.selection().size()));
        }
        return new StatusStrip.Status(Kit.Light.OK, false, text);
    }

    // --- Rendering ---

    @Override
    protected void prepareMap(int mouseX, int mouseY, float partialTick) {
        rows = sortedRows();
        rowHover = rowAt(mouseX, mouseY);
        map.prepare(ServerConfig.get(ServerConfig.COMMAND_CENTER_SCAN_INTERVAL), rowHover != null ? rowHover.droneId() : null, box,
                currentAim(mouseX, mouseY));
    }

    @Nullable
    private CommandCenterMap.Aim currentAim(int mouseX, int mouseY) {
        if (aiming == null) {
            return null;
        }
        List<String> ids = capable(aiming);
        boolean strike = aiming == Mode.STRIKE;
        if (strikeSpot != null) {
            return new CommandCenterMap.Aim(ids, strikeSpot[0], strikeSpot[1], true, true, true);
        }
        if (!overMap(mouseX, mouseY)) {
            return null;
        }
        double dx = map.blockX(mouseX);
        double dz = map.blockZ(mouseY);
        return new CommandCenterMap.Aim(ids, dx, dz, strike, inArea(dx, dz), false);
    }

    @Override
    protected void renderContents(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        super.renderContents(graphics, partialTick, mouseX, mouseY);
        renderPicker(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderList(GuiGraphics graphics, int x, int top, int mouseX, int mouseY) {
        int rowY = top;
        for (Contact drone : rows) {
            boolean selected = map.selection().contains(drone.droneId());
            if (drone == rowHover || selected) {
                graphics.fill(x + 1, rowY - 1, x + listWidth - 1, rowY + ROW_HEIGHT - 2, drone == rowHover ? ROW_HIGHLIGHT : SELECTED_ROW);
            }
            if (selected) {
                graphics.fill(x + 1, rowY - 1, x + 2, rowY + ROW_HEIGHT - 2, Kit.WHITE);
            }
            int color = CommandCenterMap.color(drone);
            graphics.fill(x + 4, rowY + 1, x + 7, rowY + 4, color);
            String distance = Component.translatable("screen.seekerdrones.uplink.meters_short", Math.round(Mth.length(drone.dx(), drone.dz()))).getString();
            int distanceWidth = Kit.smallWidth(font, distance);
            Kit.smallText(graphics, font, distance, x + listWidth - 3 - distanceWidth, rowY, Kit.DISPLAY_TEXT_DIM, true);
            Kit.smallText(graphics, font, clip(drone.name(), listWidth - 9 - 3 - distanceWidth - 3), x + 9, rowY, color, true);
            Details details = map.details(drone);
            int barWidth = (listWidth - 9 - 3 - 2) / 2;
            float health = details != null ? Kit.fraction(details.health(), details.maxHealth()) : 0;
            float energy = details != null && details.own().isPresent()
                    ? Kit.fraction(details.own().get().energy(), details.own().get().maxEnergy())
                    : 0;
            bar(graphics, Kit.FILL_HEALTH_HORIZONTAL, x + 9, rowY + BAR_Y, barWidth, health);
            bar(graphics, Kit.FILL_ENERGY_HORIZONTAL, x + 9 + barWidth + 2, rowY + BAR_Y, barWidth, energy);
            rowY += ROW_HEIGHT;
        }
        rowY += FOOTER_GAP;
        int others = map.count(Kind.OTHER);
        int unowned = map.count(Kind.UNOWNED);
        // Always shown, like the Uplink's Unidentified line; Unowned is amber while there are any.
        Kit.smallText(graphics, font, Component.translatable(KEY + "others", others).getString(), x + 3, rowY, OTHERS, false);
        Kit.smallText(graphics, font, Component.translatable(KEY + "unowned", unowned).getString(), x + 3, rowY + LINE,
                unowned > 0 ? AMBER : Kit.DISPLAY_TEXT_DIM, false);
    }

    private static void bar(GuiGraphics graphics, ResourceLocation fill, int x, int y, int width, float fraction) {
        graphics.fill(x, y, x + width, y + BAR_HEIGHT, BAR_BACKGROUND);
        int filled = Math.round(width * Mth.clamp(fraction, 0, 1));
        if (filled > 0) {
            graphics.blitSprite(fill, x, y, filled, BAR_HEIGHT);
        }
    }

    /** "N selected" pinned at the bottom of the list. */
    @Override
    protected void renderListFooter(GuiGraphics graphics, int x, int y) {
        if (!map.selection().isEmpty()) {
            int lineY = y + StationMap.SIZE - LINE;
            graphics.fill(x + 1, lineY - 2, x + listWidth - 1, y + StationMap.SIZE - 1, Kit.DISPLAY_BACKGROUND);
            Kit.smallText(graphics, font, Component.translatable(KEY + "selected", map.selection().size()).getString(), x + 3, lineY, Kit.WHITE, false);
        }
    }

    @Override
    protected int listContentHeight() {
        return rows.size() * ROW_HEIGHT + FOOTER_GAP + 2 * LINE;
    }

    @Override
    protected int listViewHeight() {
        return super.listViewHeight() - (map.selection().isEmpty() ? 0 : LINE + 2);
    }

    @Override
    protected int scrollStep() {
        return ROW_HEIGHT;
    }

    // --- Mode picker ---

    /** Places the picker under the selection outline's bottom-right corner, flipped to stay inside the map. */
    private void renderPicker(GuiGraphics graphics, int mouseX, int mouseY) {
        picker = null;
        float[] bounds = map.selectionBounds();
        if (bounds == null) {
            return;
        }
        int left = mapX();
        int topEdge = mapY();
        int right = left + StationMap.INNER;
        int bottom = topEdge + StationMap.INNER;
        float cornerX = bounds[2];
        float cornerY = bounds[3];
        if (cornerX < left || cornerX > right || cornerY < topEdge || cornerY > bottom) {
            // The corner is outside the zoomed view.
            return;
        }
        int x = Math.round(cornerX) - PICKER_WIDTH;
        if (x < left) {
            x = Math.round(cornerX);
        }
        if (x + PICKER_WIDTH > right) {
            x = Math.round(bounds[0]) - PICKER_WIDTH;
        }
        x = Mth.clamp(x, left, right - PICKER_WIDTH);
        int y = Math.round(cornerY) + 2;
        if (y + PICKER_HEIGHT > bottom) {
            y = Math.round(bounds[1]) - 2 - PICKER_HEIGHT;
        }
        y = Mth.clamp(y, topEdge, bottom - PICKER_HEIGHT);
        picker = new int[] {x, y};
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 200);
        Kit.display(graphics, x, y, PICKER_WIDTH, PICKER_HEIGHT);
        Mode hovered = pickerMode(mouseX, mouseY);
        for (Mode mode : Mode.values()) {
            int bx = x + 1 + mode.ordinal() * PICKER_BUTTON;
            int by = y + 1;
            boolean enabled = canPick(mode);
            boolean hot = enabled && (mode == hovered || mode == aiming);
            graphics.blitSprite(hot ? Kit.DISPLAY_BUTTON_HIGHLIGHTED : Kit.DISPLAY_BUTTON, bx, by, PICKER_BUTTON, PICKER_BUTTON);
            ResourceLocation icon = MODE_ICONS.get(mode);
            int[] size = iconSize(mode);
            if (!enabled) {
                graphics.setColor(1, 1, 1, 0.4F);
            }
            com.mojang.blaze3d.systems.RenderSystem.enableBlend();
            graphics.blitSprite(icon, bx + (PICKER_BUTTON - size[0]) / 2, by + (PICKER_BUTTON - size[1]) / 2, size[0], size[1]);
            graphics.setColor(1, 1, 1, 1);
        }
        graphics.pose().popPose();
    }

    private static int[] iconSize(Mode mode) {
        return switch (mode) {
            case MOVE -> new int[] {9, 8};
            case PATROL_HERE -> new int[] {7, 7};
            case STRIKE -> new int[] {7, 8};
            case HOLD -> new int[] {6, 7};
            case RETURN_TO_CHARGE -> new int[] {6, 8};
            case RELEASE -> new int[] {5, 5};
        };
    }

    @Nullable
    private Mode pickerMode(double mouseX, double mouseY) {
        if (picker == null || mouseY < picker[1] + 1 || mouseY >= picker[1] + 1 + PICKER_BUTTON) {
            return null;
        }
        int index = (int) Math.floor((mouseX - picker[0] - 1) / PICKER_BUTTON);
        return index >= 0 && index < Mode.values().length ? Mode.values()[index] : null;
    }

    private boolean overPicker(double mouseX, double mouseY) {
        return picker != null && mouseX >= picker[0] && mouseX < picker[0] + PICKER_WIDTH && mouseY >= picker[1] && mouseY < picker[1] + PICKER_HEIGHT;
    }

    /** The selected own drones that can take the mode: Strike needs Explosive, Patrol Here needs Patrol. */
    private List<String> capable(Mode mode) {
        List<String> ids = new ArrayList<>();
        for (String id : map.selection()) {
            Contact contact = map.own(id);
            if (contact != null && canTake(contact, mode)) {
                ids.add(id);
            }
        }
        return ids;
    }

    private boolean canTake(Contact contact, Mode mode) {
        if (mode != Mode.STRIKE && mode != Mode.PATROL_HERE) {
            return true;
        }
        Details details = map.details(contact);
        if (details == null || details.own().isEmpty()) {
            return false;
        }
        UpgradeType needed = mode == Mode.STRIKE ? UpgradeType.EXPLOSIVE : UpgradeType.PATROL;
        return details.own().get().upgrades().getOrDefault(needed, 0) > 0;
    }

    private boolean canPick(Mode mode) {
        return menu.isPowered() && !capable(mode).isEmpty();
    }

    private List<Component> modeTooltip(Mode mode) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable(KEY + "mode." + mode.name().toLowerCase(java.util.Locale.ROOT)));
        int can = capable(mode).size();
        int selected = map.selection().size();
        if ((mode == Mode.STRIKE || mode == Mode.PATROL_HERE) && can < selected) {
            String key = mode == Mode.STRIKE ? "can_strike" : "can_patrol";
            lines.add(Component.translatable(KEY + key, can, selected).withStyle(ChatFormatting.GRAY));
        }
        return lines;
    }

    // --- Tooltips ---

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        Mode pickerHover = pickerMode(mouseX, mouseY);
        if (pickerHover != null && overPicker(mouseX, mouseY)) {
            KitWidget.showTooltip(modeTooltip(pickerHover));
        } else if (aiming != null && overMap(mouseX, mouseY)) {
            KitWidget.showTooltip(aimTooltip(mouseX, mouseY));
        } else if (rowHover != null) {
            KitWidget.showTooltip(tooltip(rowHover));
        } else if (map.hover() != null && box == null) {
            KitWidget.showTooltip(tooltip(map.hover().contact()));
        }
    }

    private List<Component> aimTooltip(int mouseX, int mouseY) {
        if (strikeSpot != null) {
            if (nearStrikeSpot(mouseX, mouseY)) {
                return List.of(Component.translatable(KEY + "aim.strike_count", capable(Mode.STRIKE).size()).withStyle(ChatFormatting.RED),
                        Component.translatable(KEY + "aim.confirm").withStyle(ChatFormatting.WHITE));
            }
            return List.of(Component.translatable(KEY + "aim.strike_move").withStyle(ChatFormatting.GRAY),
                    Component.translatable(KEY + "aim.cancel").withStyle(ChatFormatting.GRAY));
        }
        if (!inArea(map.blockX(mouseX), map.blockZ(mouseY))) {
            return List.of(Component.translatable(KEY + "aim.out_of_range").withStyle(ChatFormatting.RED));
        }
        MutableComponent title = Component.translatable(KEY + "aim." + aiming.name().toLowerCase(java.util.Locale.ROOT));
        if (aiming == Mode.STRIKE) {
            title.withStyle(ChatFormatting.RED);
        }
        return List.of(title, Component.translatable(KEY + "aim.cancel").withStyle(ChatFormatting.GRAY));
    }

    /**
     * The tooltip of a drone on the map or in the list (section 7.10), laid out like the drone item's: the identity in
     * the drone's color, then gray detail lines, the upgrades listed under their heading, and the click hints last.
     */
    private List<Component> tooltip(Contact contact) {
        List<Component> lines = new ArrayList<>();
        Details details = map.details(contact);
        switch (contact.kind()) {
            case OWN -> {
                lines.add(DroneItem.identity(contact.droneId(), contact.label(), contact.color()));
                CommandCenterContactsPayload.OwnDetails own = details != null ? details.own().orElse(null) : null;
                if (own != null) {
                    lines.add(Component.translatable(KEY + "state", Component.translatable(own.state().getTranslationKey()))
                            .withStyle(ChatFormatting.GRAY));
                    own.order().ifPresent(order -> lines.add(order(order)));
                    lines.add(Component.translatable("tooltip.seekerdrones.drone.energy", EnergyFormat.ratio(own.energy(), own.maxEnergy()))
                            .withStyle(ChatFormatting.GRAY));
                }
                health(details).ifPresent(lines::add);
                tracking(contact).ifPresent(lines::add);
                if (own != null) {
                    upgrades(lines, own.upgrades());
                }
                lines.add(Component.translatable(KEY + "hint.open").withStyle(ChatFormatting.DARK_GRAY));
                lines.add(Component.translatable(KEY + "hint.orders").withStyle(ChatFormatting.DARK_GRAY));
                lines.add(Component.translatable(KEY + "hint.select").withStyle(ChatFormatting.DARK_GRAY));
            }
            case OTHER -> {
                lines.add(Component.literal(contact.name()).withColor(contact.color().getTextColor()));
                lines.add(Component.translatable(KEY + "other_drone").withStyle(ChatFormatting.GRAY));
                health(details).ifPresent(lines::add);
                tracking(contact).ifPresent(lines::add);
            }
            case UNOWNED -> {
                lines.add(Component.translatable(KEY + "unowned_drone"));
                health(details).ifPresent(lines::add);
                tracking(contact).ifPresent(lines::add);
            }
        }
        return lines;
    }

    private static Optional<Component> health(@Nullable Details details) {
        return Optional.ofNullable(details).map(value -> Component.translatable("tooltip.seekerdrones.drone.health",
                DroneItem.formatHealth(value.health()), DroneItem.formatHealth(value.maxHealth())).withStyle(ChatFormatting.GRAY));
    }

    private static Optional<Component> tracking(Contact contact) {
        return contact.target().map(target -> Component.translatable("screen.seekerdrones.uplink.tracking", target.name()).withStyle(ChatFormatting.GRAY));
    }

    /** The upgrades under their heading, one per line with its count, like the drone item's tooltip. */
    private static void upgrades(List<Component> lines, Map<UpgradeType, Integer> upgrades) {
        if (upgrades.values().stream().noneMatch(count -> count > 0)) {
            return;
        }
        lines.add(Component.translatable("tooltip.seekerdrones.drone.upgrades").withStyle(ChatFormatting.GRAY));
        for (UpgradeType type : UpgradeType.values()) {
            int count = upgrades.getOrDefault(type, 0);
            if (count > 0) {
                lines.add(Component.literal("  ")
                        .append(Component.translatable("tooltip.seekerdrones.drone.upgrade_entry", Component.translatable(type.getTranslationKey()), count))
                        .withStyle(ChatFormatting.DARK_GRAY));
            }
        }
    }

    private static Component order(CommandCenterContactsPayload.Order order) {
        int seconds = Mth.ceil(order.ticksLeft() / (float) DroneStats.TICKS_PER_SECOND);
        Component name = Component.translatable(order.order().getTranslationKey());
        return (order.order() == com.elpinho.seekerdrones.drone.DroneOrder.STRIKE
                ? Component.translatable(KEY + "order", name)
                : Component.translatable(KEY + "order_timed", name, seconds)).withColor(AMBER & 0xFFFFFF);
    }

    // --- Input ---

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (overWidget(mouseX, mouseY)) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        if (button == 1 && aiming != null) {
            cancelAim();
            return true;
        }
        if (button == 1 && rowAt(mouseX, mouseY) != null) {
            selectOnly(rowAt(mouseX, mouseY).droneId());
            return true;
        }
        if (button != 0) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        if (overPicker(mouseX, mouseY)) {
            Mode mode = pickerMode(mouseX, mouseY);
            if (mode != null && canPick(mode)) {
                click();
                if (mode.aims()) {
                    aiming = mode;
                    strikeSpot = null;
                } else {
                    cancelAim();
                    send(mode, new ArrayList<>(map.selection()), Optional.empty());
                }
            }
            return true;
        }
        if (aiming != null && overMap(mouseX, mouseY)) {
            pickSpot(mouseX, mouseY);
            return true;
        }
        Contact row = rowAt(mouseX, mouseY);
        if (row != null) {
            if (Screen.hasShiftDown()) {
                click();
                map.toggle(row.droneId());
            } else {
                openRemote(row.droneId());
            }
            return true;
        }
        if (overMap(mouseX, mouseY)) {
            press = new double[] {mouseX, mouseY};
            pressShift = Screen.hasShiftDown();
            pressBlip = map.hover();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** A click on the map while aiming: picks the spot, or for Strike places and then confirms the crosshair. */
    private void pickSpot(double mouseX, double mouseY) {
        if (aiming == Mode.STRIKE && strikeSpot != null && nearStrikeSpot(mouseX, mouseY)) {
            click();
            send(Mode.STRIKE, capable(Mode.STRIKE), Optional.of(strikeSpot));
            cancelAim();
            return;
        }
        double dx = map.blockX(mouseX);
        double dz = map.blockZ(mouseY);
        if (!inArea(dx, dz)) {
            return;
        }
        click();
        if (aiming == Mode.STRIKE) {
            strikeSpot = new double[] {dx, dz};
            return;
        }
        send(aiming, capable(aiming), Optional.of(new double[] {dx, dz}));
        cancelAim();
    }

    private boolean nearStrikeSpot(double mouseX, double mouseY) {
        if (strikeSpot == null) {
            return false;
        }
        double x = map.sx(strikeSpot[0]) - mouseX;
        double y = map.sy(strikeSpot[1]) - mouseY;
        return x * x + y * y <= CONFIRM_RADIUS * CONFIRM_RADIUS;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0 && press != null) {
            if (box != null || Math.abs(mouseX - press[0]) >= DRAG_THRESHOLD || Math.abs(mouseY - press[1]) >= DRAG_THRESHOLD) {
                float x = Mth.clamp((float) mouseX, mapX(), mapX() + StationMap.INNER);
                float y = Mth.clamp((float) mouseY, mapY(), mapY() + StationMap.INNER);
                box = new CommandCenterMap.Box((float) press[0], (float) press[1], x, y);
            }
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && press != null) {
            if (box != null) {
                map.select(map.inBox(box), pressShift);
            } else if (pressBlip != null && pressBlip.own()) {
                if (pressShift) {
                    click();
                    map.toggle(pressBlip.contact().droneId());
                } else {
                    openRemote(pressBlip.contact().droneId());
                }
            } else if (pressBlip == null && !pressShift) {
                // A click on an empty spot clears the selection.
                map.clearSelection();
            }
            press = null;
            pressBlip = null;
            box = null;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /** Esc cancels an aim first, then clears the selection, then closes the screen. */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (aiming != null) {
                cancelAim();
                return true;
            }
            if (!map.selection().isEmpty()) {
                map.clearSelection();
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** A right-click (no drag) on the map: on an own drone, selects just that one, so the picker opens for it. */
    @Override
    protected void onRightClick(double mouseX, double mouseY) {
        Blip blip = map.hover();
        if (blip != null && blip.own()) {
            selectOnly(blip.contact().droneId());
        }
    }

    private void selectOnly(String droneId) {
        click();
        cancelAim();
        map.select(List.of(droneId), false);
    }

    private void cancelAim() {
        aiming = null;
        strikeSpot = null;
    }

    private void send(Mode mode, List<String> ids, Optional<double[]> spot) {
        if (ids.isEmpty()) {
            return;
        }
        Vec3 center = menu.getPos().getCenter();
        PacketDistributor.sendToServer(new CommandCenterOrderPayload(ids, mode,
                spot.map(at -> new CommandCenterOrderPayload.Spot(center.x + at[0], center.z + at[1]))));
    }

    private static void click() {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1));
    }

    // --- Helpers ---

    private boolean inArea(double dx, double dz) {
        double radius = configRadius();
        return dx * dx + dz * dz <= radius * radius;
    }

    @Nullable
    private Contact rowAt(double mouseX, double mouseY) {
        int offset = listOffset(mouseX, mouseY);
        if (offset < 0 || offset > listScroll + listViewHeight()) {
            return null;
        }
        int index = offset / ROW_HEIGHT;
        return index < rows.size() ? rows.get(index) : null;
    }

    /** The viewer's own drones in the latest snapshot, in the chosen order. */
    private List<Contact> sortedRows() {
        List<Contact> own = new ArrayList<>();
        for (Contact contact : map.contacts()) {
            if (contact.kind() == Kind.OWN) {
                own.add(contact);
            }
        }
        return sorted(own, drone -> drone.dx() * drone.dx() + drone.dz() * drone.dz(), Contact::name, this::stateRank);
    }

    /** Busy drones first (section 7.10): striking, chasing, on an order or piloted, held, charging, patrolling, idle. */
    private int stateRank(Contact contact) {
        Details details = map.details(contact);
        if (details == null || details.own().isEmpty()) {
            return Integer.MAX_VALUE;
        }
        return switch (details.own().get().state()) {
            case STRIKING -> 0;
            case CHASING, FOLLOWING -> 1;
            case MOVING, GUARDING, PILOTED -> 2;
            case RECALLED, HOLDING -> 3;
            case RETURNING, CHARGING -> 4;
            case PATROLLING -> 5;
            case IDLE -> 6;
        };
    }

    @Override
    protected List<ListSort> sorts() {
        return SORTS;
    }

    @Override
    protected ModConfigSpec.EnumValue<ListSort> sortConfig() {
        return ClientConfig.COMMAND_CENTER_LIST_SORT;
    }
}
