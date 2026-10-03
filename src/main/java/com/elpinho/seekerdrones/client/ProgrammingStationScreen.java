package com.elpinho.seekerdrones.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import org.lwjgl.glfw.GLFW;

import com.elpinho.seekerdrones.client.gui.DronePreview;
import com.elpinho.seekerdrones.client.gui.EntityPreview;
import com.elpinho.seekerdrones.client.gui.Gauges;
import com.elpinho.seekerdrones.client.gui.Kit;
import com.elpinho.seekerdrones.client.gui.KitButton;
import com.elpinho.seekerdrones.client.gui.KitSlider;
import com.elpinho.seekerdrones.client.gui.KitWidget;
import com.elpinho.seekerdrones.client.gui.MachineScreen;
import com.elpinho.seekerdrones.client.gui.SideTab;
import com.elpinho.seekerdrones.client.gui.StatusStrip;
import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.drone.DroneConfig;
import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.DroneStats;
import com.elpinho.seekerdrones.drone.TargetBlacklist;
import com.elpinho.seekerdrones.drone.TargetEntry;
import com.elpinho.seekerdrones.drone.UpgradeType;
import com.elpinho.seekerdrones.energy.EnergyFormat;
import com.elpinho.seekerdrones.network.EditProgramPayload;
import com.elpinho.seekerdrones.programming.DroneProgram;
import com.elpinho.seekerdrones.programming.ProgramRules;
import com.elpinho.seekerdrones.programming.ProgrammingMode;
import com.elpinho.seekerdrones.programming.ProgrammingStationBlockEntity;
import com.elpinho.seekerdrones.programming.ProgrammingStationMenu;
import com.elpinho.seekerdrones.registry.ModItems;

import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.util.StringUtil;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Drone Programming Station screen (DESIGN.md section 7.2), in the machine GUI kit: a wide console with the standard
 * inventory centered below it. The left column has the energy gauge, the drone bay (a preview of the drone in its
 * color circling its patrol radius, with the install progress as a ring around the drone slot) and the 3x3 upgrade
 * input. The editor has Upgrades, Targets, Behavior and Identity tabs and edits either the drone in the slot (Direct
 * mode) or the template (Template mode). The accent color follows the mode. Every edit is sent to the server, which
 * checks it again.
 */
public class ProgrammingStationScreen extends MachineScreen<ProgrammingStationMenu> {
    private static final String KEY = "screen.seekerdrones.programming_station.";

    // The stepped frame: the console, and the standard 176-wide inventory frame centered under it.
    private static final int WIDTH = 244;
    private static final int HEIGHT = 252;
    private static final int CONSOLE_HEIGHT = 158;
    private static final int INVENTORY_FRAME_X = 34;
    private static final int INVENTORY_FRAME_Y = 150;
    private static final int INVENTORY_FRAME_WIDTH = 176;
    /** The frame sprite's border: the seam between the two frames is covered inside it. */
    private static final int FRAME_BORDER = 3;
    private static final int PANEL_COLOR = 0xFFC6C6C6;

    // Mode accents and display colors.
    private static final int DIRECT_ACCENT = 0xFF47B5EF;
    private static final int TEMPLATE_ACCENT = 0xFFF0A838;
    private static final int OK_COLOR = 0xFF4ADE80;
    private static final int WARN_COLOR = 0xFFFBBF24;
    private static final int BAD_COLOR = 0xFFF45B5B;
    private static final int MISSING_COLOR = 0xFF6E5518;
    private static final int TRACK_COLOR = 0xFF1D3034;
    private static final int PIP_OFF_COLOR = 0xFF22343A;
    private static final int TILE_PROGRESS_TRACK = 0xFF1A2A2E;
    private static final int MINI_BUTTON_COLOR = 0xFF0A1013;
    private static final int SEGMENT_OFF_TEXT = 0xFF99AAAA;
    private static final int SEGMENT_ON_TEXT = 0xFF0B1013;
    private static final int NAMEPLATE_BACKGROUND = 0x73000000;
    private static final int INVALID_TEXT = 0xFF6060;
    /** How long each upgrade shows as the input slots' ghost. */
    private static final long GHOST_CYCLE_MILLIS = 7000;
    /** Laid over an upgrade icon that nothing uses yet. */
    private static final int LOW_KEY_FADE = 0x8C101B1F;

    // The left column.
    private static final int ENERGY_X = 8;
    private static final int ENERGY_Y = 18;
    private static final int ENERGY_HEIGHT = 120;
    private static final int BAY_X = 22;
    private static final int BAY_Y = 18;
    private static final int BAY_WIDTH = 56;
    private static final int BAY_HEIGHT = 62;
    /** The orbit's center height in the bay, and its size: the widest orbit is the largest patrol radius. */
    private static final int ORBIT_Y = 20;
    private static final float ORBIT_MIN = 6;
    private static final float ORBIT_RANGE = 18;
    private static final float ORBIT_TILT = 0.32F;
    private static final float DRONE_SCALE = 14;
    private static final float RING_RADIUS = 10.2F;

    // The editor: tabs over a display.
    private static final int MODE_X = WIDTH - 8 - 92;
    private static final int MODE_Y = 4;
    private static final int MODE_WIDTH = 92;
    private static final int MODE_HEIGHT = 12;
    private static final int TAB_X = 84;
    private static final int TAB_Y = 18;
    private static final int TAB_WIDTH = 24;
    private static final int TAB_HEIGHT = 13;
    private static final int TAB_PITCH = 25;
    private static final int EDITOR_X = 84;
    private static final int EDITOR_Y = 30;
    private static final int EDITOR_WIDTH = 152;
    private static final int EDITOR_HEIGHT = 108;
    private static final int STATUS_Y = 140;

    /** The tab's name at the top of the editor. */
    private static final int HEADER_Y = 5;
    /** Behavior and Identity start their contents this much lower, below the header. */
    private static final int BODY_SHIFT = 2;

    // Upgrades, relative to the editor.
    private static final int TILE_X = 1;
    private static final int TILE_Y = 14;
    private static final int TILE_WIDTH = 27;
    private static final int TILE_HEIGHT = 28;
    private static final int TILE_PITCH_X = 29;
    private static final int TILE_PITCH_Y = 30;
    private static final int TILE_COLUMNS = 5;
    private static final int TILE_ROWS = 3;
    /** Up to this many pips per tile. A larger per-type cap (config) shows as a segmented bar. */
    private static final int MAX_PIPS = 8;
    private static final int SLOT_BAR_RIGHT = 146;
    private static final int SLOT_BAR_Y = 6;
    private static final int SLOT_BAR_HEIGHT = 5;
    /** Up to this many slots show one segment each. More show as one bar of the same width. */
    private static final int MAX_SLOT_SEGMENTS = 24;
    private static final int TILE_SCROLLBAR_X = 146;

    // Targets, relative to the editor.
    private static final int ROW_Y = 14;
    private static final int ROW_PITCH = 16;
    private static final int VISIBLE_ROWS = 5;
    private static final int KIND_X = 1;
    private static final int ROW_FIELD_X = 17;
    private static final int ROW_FIELD_WIDTH = 74;
    private static final int ROW_HEIGHT = 14;
    private static final int REMOVE_X = 93;
    private static final int ROW_SCROLLBAR_X = 104;
    private static final int PREVIEW_X = 107;
    private static final int PREVIEW_Y = 14;
    private static final int PREVIEW_WIDTH = 41;
    private static final int PREVIEW_HEIGHT = 66;

    // Behavior and Identity, relative to the editor.
    private static final int SLIDER_WIDTH = 104;
    private static final int SLIDER_HEIGHT = 6;
    private static final int VALUE_X = 112;
    private static final int VALUE_WIDTH = 36;
    private static final int FIELD_HEIGHT = 13;
    private static final int SWATCH_SIZE = 12;
    private static final int SWATCH_PITCH = 14;

    private static final ResourceLocation TAB = Kit.sprite("programming/tab");
    private static final ResourceLocation TAB_HIGHLIGHTED = Kit.sprite("programming/tab_highlighted");
    private static final ResourceLocation TAB_SELECTED = Kit.sprite("programming/tab_selected");
    private static final ResourceLocation ICON_TARGET = Kit.sprite("icon/target");
    private static final ResourceLocation ICON_ROUTE = Kit.sprite("icon/route");
    private static final ResourceLocation ICON_NAME_TAG = Kit.sprite("icon/name_tag");
    private static final ResourceLocation ICON_EGG = Kit.sprite("icon/egg");
    private static final ResourceLocation ICON_TAG = Kit.sprite("icon/tag");
    private static final ResourceLocation ICON_PIN = Kit.sprite("icon/pin");
    private static final ResourceLocation REMOVE = Kit.sprite("remove_button");
    private static final ResourceLocation REMOVE_HIGHLIGHTED = Kit.sprite("remove_button_highlighted");

    private enum Tab {
        UPGRADES, TARGETS, BEHAVIOR, IDENTITY
    }

    /** What decides which widgets exist. The widgets are rebuilt when it changes. */
    private record Structure(Tab tab, ProgrammingMode mode, boolean hasProgram, int targetRows, int targetScroll, int tileScroll, boolean patrol) {}

    private Tab tab = Tab.UPGRADES;
    private int targetScroll;
    private int tileScroll;
    /** The target row whose preview is shown. */
    private int selectedRow;
    @Nullable
    private Structure structure;
    /** The settings last written into the fields. */
    @Nullable
    private DroneConfig syncedConfig;
    /** The kind picked for each target row, by row index. */
    private final Map<Integer, TargetEntry.Kind> rowKinds = new HashMap<>();
    /** Feedback on the last rejected edit, shown in the status strip until the next edit. */
    @Nullable
    private Component editError;

    private final Map<Integer, FieldBox> targetBoxes = new HashMap<>();
    private final Map<Integer, KitWidget> removeButtons = new HashMap<>();
    private final List<KitSlider> sliders = new ArrayList<>();
    @Nullable
    private FieldBox followBox;
    @Nullable
    private FieldBox labelBox;
    @Nullable
    private FieldBox centerX;
    @Nullable
    private FieldBox centerY;
    @Nullable
    private FieldBox centerZ;
    @Nullable
    private FieldBox radiusBox;
    /** Auto-complete for the focused target row. Created in {@link #init()}, once the font is set. */
    @Nullable
    private TargetSuggestions suggestions;
    private final DronePreview dronePreview = new DronePreview();
    private final EntityPreview targetPreview = new EntityPreview();

    public ProgrammingStationScreen(ProgrammingStationMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, WIDTH, HEIGHT, ProgrammingStationMenu.INVENTORY_Y);
        this.inventoryLabelX = ProgrammingStationMenu.INVENTORY_X - 1;
        sideTabs.add(SideTab.energyUnit());
        sideTabs.add(new SideTab(22, (graphics, x, y, mouseX, mouseY) -> graphics.blitSprite(Kit.ICON_INFO, x + 9, y + 7, 7, 8))
                .tooltip(() -> List.of(
                        Component.translatable(KEY + "help"),
                        Component.translatable(KEY + "help.direct").withStyle(ChatFormatting.GRAY),
                        Component.translatable(KEY + "help.template").withStyle(ChatFormatting.GRAY))));
    }

    // --- State ---

    private ProgrammingMode mode() {
        return menu.getMode();
    }

    private boolean direct() {
        return mode() == ProgrammingMode.DIRECT;
    }

    private int accent() {
        return direct() ? DIRECT_ACCENT : TEMPLATE_ACCENT;
    }

    /** What the editor shows: the drone's own values in Direct mode, the template in Template mode. */
    @Nullable
    private DroneProgram program() {
        if (direct()) {
            DroneData drone = menu.getDrone();
            return drone != null ? DroneProgram.of(drone) : null;
        }
        return menu.getTemplate();
    }

    private static int targetRows(DroneProgram program) {
        return Math.max(program.allowedTargetCount(), program.config().targets().size());
    }

    private int maxTileScroll() {
        int rows = Mth.positiveCeilDiv(UpgradeType.values().length, TILE_COLUMNS);
        return Math.max(0, rows - TILE_ROWS);
    }

    private Structure currentStructure() {
        DroneProgram program = program();
        int targetRows = program != null ? targetRows(program) : 0;
        boolean patrol = program != null && program.upgradeCount(UpgradeType.PATROL) > 0;
        return new Structure(tab, mode(), program != null, targetRows, targetScroll, tileScroll, patrol);
    }

    private void send(EditProgramPayload payload) {
        editError = null;
        PacketDistributor.sendToServer(payload);
    }

    private void sendConfig(DroneConfig config) {
        send(EditProgramPayload.setConfig(menu.containerId, config));
    }

    // --- Widgets ---

    @Override
    protected void init() {
        super.init();
        targetBoxes.clear();
        removeButtons.clear();
        sliders.clear();
        followBox = labelBox = centerX = centerY = centerZ = radiusBox = null;
        suggestions = new TargetSuggestions(font, this::acceptSuggestion);

        addRenderableWidget(new Gauges.Energy(leftPos + ENERGY_X, topPos + ENERGY_Y, 12, ENERGY_HEIGHT,
                () -> Kit.fraction(menu.getEnergy(), menu.getEnergyCapacity())))
                .tooltip(() -> List.of(Component.translatable("screen.seekerdrones.energy"),
                        Component.literal(EnergyFormat.ratio(menu.getEnergy(), menu.getEnergyCapacity())).withStyle(ChatFormatting.GRAY)));
        // The bay's preview, above the drone slot.
        addRenderableWidget(new KitWidget.Area(leftPos + BAY_X, topPos + BAY_Y, BAY_WIDTH, ProgrammingStationMenu.DRONE_Y - 2 - BAY_Y))
                .tooltip(this::bayTooltip);
        addRenderableWidget(new ModeSwitch(leftPos + MODE_X, topPos + MODE_Y));
        Tab[] tabs = Tab.values();
        for (int i = 0; i < tabs.length; i++) {
            addRenderableWidget(new EditorTab(leftPos + TAB_X + i * TAB_PITCH, topPos + TAB_Y, tabs[i]));
        }
        addRenderableWidget(new StatusStrip(leftPos + 8, topPos + STATUS_Y, WIDTH - 16, this::status));

        DroneProgram program = program();
        if (program != null) {
            int x = leftPos + EDITOR_X;
            int y = topPos + EDITOR_Y;
            switch (tab) {
                case UPGRADES -> initUpgrades(x, y);
                case TARGETS -> initTargets(program, x, y);
                case BEHAVIOR -> initBehavior(program, x, y);
                case IDENTITY -> initIdentity(x, y);
            }
        }
        structure = currentStructure();
        syncedConfig = null;
        syncWidgets();
    }

    private void initUpgrades(int x, int y) {
        tileScroll = Math.clamp(tileScroll, 0, maxTileScroll());
        UpgradeType[] types = UpgradeType.values();
        int first = tileScroll * TILE_COLUMNS;
        for (int i = 0; i < TILE_COLUMNS * TILE_ROWS && first + i < types.length; i++) {
            addRenderableWidget(new Tile(x + TILE_X + (i % TILE_COLUMNS) * TILE_PITCH_X, y + TILE_Y + (i / TILE_COLUMNS) * TILE_PITCH_Y,
                    types[first + i]));
        }
        int slots = ServerConfig.get(ServerConfig.UPGRADES_TOTAL_SLOTS);
        int barWidth = Math.min(slots, MAX_SLOT_SEGMENTS) * 2;
        addRenderableWidget(new KitWidget.Area(x + SLOT_BAR_RIGHT - barWidth, y + SLOT_BAR_Y, barWidth, SLOT_BAR_HEIGHT))
                .tooltip(() -> {
                    DroneProgram program = program();
                    int used = program != null ? DroneStats.totalUpgrades(program.upgrades()) : 0;
                    return List.of(Component.translatable(KEY + "slots"),
                            Component.translatable(KEY + "slots.used", used, ServerConfig.get(ServerConfig.UPGRADES_TOTAL_SLOTS))
                                    .withStyle(ChatFormatting.GRAY));
                });
    }

    private void initTargets(DroneProgram program, int x, int y) {
        int rows = targetRows(program);
        targetScroll = Math.clamp(targetScroll, 0, Math.max(0, rows - VISIBLE_ROWS));
        selectedRow = Math.clamp(selectedRow, 0, Math.max(0, rows - 1));
        for (int visible = 0; visible < VISIBLE_ROWS && targetScroll + visible < rows; visible++) {
            int index = targetScroll + visible;
            int rowY = y + ROW_Y + visible * ROW_PITCH;
            addRenderableWidget(new KitButton(x + KIND_X, rowY, ROW_HEIGHT, ROW_HEIGHT, KitButton.Style.DISPLAY,
                    (graphics, bx, by, mouseX, mouseY) -> drawKindIcon(graphics, index, bx, by), () -> cycleKind(index)))
                    .tooltip(() -> List.of(
                            Component.translatable(KEY + "target.kind",
                                    Component.translatable("screen.seekerdrones.target.kind." + rowKind(index).getSerializedName())),
                            Component.translatable(KEY + "target.kind.tooltip").withStyle(ChatFormatting.GRAY)));
            FieldBox box = addRenderableWidget(new FieldBox(x + ROW_FIELD_X, rowY, ROW_FIELD_WIDTH, ROW_HEIGHT,
                    Component.translatable(KEY + "target.value"), self -> commitTarget(index)));
            box.setMaxLength(64);
            box.setResponder(text -> updateSuggestions());
            box.dashed = true;
            box.setHint(Component.translatable(KEY + "target.add").withColor(Kit.DISPLAY_TEXT_DIM & 0xFFFFFF));
            box.selected = () -> selectedRow == index;
            box.warning = () -> ignoredReason(index) != null;
            box.tooltipLines = () -> targetTooltip(index);
            targetBoxes.put(index, box);
            KitWidget remove = addRenderableWidget(new RemoveButton(x + REMOVE_X, rowY + 2, () -> removeTarget(index)));
            remove.tooltip(() -> List.of(Component.translatable(KEY + "target.remove")));
            removeButtons.put(index, remove);
        }
        addRenderableWidget(new KitWidget.Area(x + PREVIEW_X, y + PREVIEW_Y, PREVIEW_WIDTH, PREVIEW_HEIGHT)).tooltip(this::previewTooltip);
    }

    private void initBehavior(DroneProgram program, int x, int y) {
        y += BODY_SHIFT;
        int maxFollow = ProgramRules.maxFollowDistance();
        sliders.add(addRenderableWidget(new KitSlider(x + 3, y + 25, SLIDER_WIDTH, SLIDER_HEIGHT, () -> 1, ProgramRules::maxFollowDistance,
                () -> {
                    DroneProgram current = program();
                    return current != null ? current.config().followDistance() : 1;
                }, this::accent, value -> showDragged(followBox, value), this::setFollowDistance)));
        sliders.getLast().tooltip(() -> List.of(Component.translatable(KEY + "follow_distance"),
                Component.translatable(KEY + "follow_distance.tooltip", 1, ProgramRules.maxFollowDistance()).withStyle(ChatFormatting.GRAY)));
        followBox = addRenderableWidget(new FieldBox(x + VALUE_X, y + 21, VALUE_WIDTH, FIELD_HEIGHT,
                Component.translatable(KEY + "follow_distance"), self -> commitFollowDistance()));
        followBox.setMaxLength(3);
        followBox.setFilter(text -> text.chars().allMatch(Character::isDigit));
        followBox.tooltipLines = () -> List.of(Component.translatable(KEY + "follow_distance"),
                Component.translatable(KEY + "follow_distance.tooltip", 1, maxFollow).withStyle(ChatFormatting.GRAY));

        if (program.upgradeCount(UpgradeType.PATROL) <= 0) {
            return;
        }
        String[] axes = {"x", "y", "z"};
        FieldBox[] boxes = new FieldBox[3];
        for (int i = 0; i < 3; i++) {
            String axis = axes[i];
            FieldBox box = addRenderableWidget(new FieldBox(x + 3 + i * 32, y + 49, 30, FIELD_HEIGHT, Component.literal(axis), self -> commitCenter()));
            box.setMaxLength(9);
            box.setFilter(text -> text.isEmpty() || text.matches("-?\\d*"));
            box.setHint(Component.literal(axis.toUpperCase()).withColor(Kit.DISPLAY_TEXT_DIM & 0xFFFFFF));
            box.tooltipLines = () -> List.of(Component.translatable(KEY + "patrol_center"),
                    Component.literal(axis.toUpperCase()).withStyle(ChatFormatting.GRAY));
            boxes[i] = box;
        }
        centerX = boxes[0];
        centerY = boxes[1];
        centerZ = boxes[2];
        addRenderableWidget(new KitButton(x + 100, y + 49, 22, FIELD_HEIGHT, KitButton.Style.DISPLAY,
                (graphics, bx, by, mouseX, mouseY) -> graphics.blitSprite(ICON_PIN, bx + 7, by + 3, 7, 7),
                () -> setCenter(Optional.of(menu.getPos().above()))))
                .tooltip(() -> List.of(Component.translatable(KEY + "patrol_center.here"),
                        Component.translatable(KEY + "patrol_center.here.tooltip").withStyle(ChatFormatting.GRAY)));
        addRenderableWidget(new KitButton(x + 125, y + 49, 22, FIELD_HEIGHT, KitButton.Style.DISPLAY,
                (graphics, bx, by, mouseX, mouseY) -> graphics.drawCenteredString(font, "×", bx + 11, by + 3, Kit.DISPLAY_TEXT),
                () -> setCenter(Optional.empty())))
                .tooltip(() -> List.of(Component.translatable(KEY + "patrol_center.clear"),
                        Component.translatable(KEY + "patrol_center.clear.tooltip").withStyle(ChatFormatting.GRAY)));

        sliders.add(addRenderableWidget(new KitSlider(x + 3, y + 80, SLIDER_WIDTH, SLIDER_HEIGHT, () -> 1, this::maxRadius,
                this::currentRadius, this::accent, value -> showDragged(radiusBox, value), this::setRadius)));
        sliders.getLast().tooltip(() -> List.of(Component.translatable(KEY + "patrol_radius"),
                Component.translatable(KEY + "patrol_radius.slider", maxRadius()).withStyle(ChatFormatting.GRAY)));
        radiusBox = addRenderableWidget(new FieldBox(x + VALUE_X, y + 76, VALUE_WIDTH, FIELD_HEIGHT,
                Component.translatable(KEY + "patrol_radius"), self -> commitRadius()));
        radiusBox.setMaxLength(6);
        radiusBox.setFilter(text -> text.chars().allMatch(Character::isDigit));
        radiusBox.tooltipLines = () -> List.of(Component.translatable(KEY + "patrol_radius"),
                Component.translatable(KEY + "patrol_radius.tooltip", maxRadius()).withStyle(ChatFormatting.GRAY));
    }

    private void initIdentity(int x, int y) {
        y += BODY_SHIFT;
        labelBox = addRenderableWidget(new FieldBox(x + 3, y + 22, EDITOR_WIDTH - 7, FIELD_HEIGHT, Component.translatable(KEY + "label"),
                self -> commitLabel()));
        labelBox.setMaxLength(ProgramRules.MAX_LABEL_LENGTH);
        labelBox.setHint(Component.translatable(KEY + "label.hint").withColor(Kit.DISPLAY_TEXT_DIM & 0xFFFFFF));
        DyeColor[] colors = DyeColor.values();
        for (int i = 0; i < colors.length; i++) {
            addRenderableWidget(new Swatch(x + 3 + (i % 8) * SWATCH_PITCH, y + 50 + (i / 8) * SWATCH_PITCH, colors[i]));
        }
    }

    /** While a slider is dragged, its value field shows the value it would set. */
    private static void showDragged(@Nullable FieldBox box, int value) {
        if (box != null) {
            box.showValue(String.valueOf(value));
            box.setInvalid(false);
        }
    }

    // --- Upgrades ---

    /** Direct mode: whether one more of the type can be installed from the input now. */
    private boolean canInstall(DroneProgram program, UpgradeType type) {
        return menu.getInstalling() == null && menu.inputCount(type) > 0 && ProgramRules.canAdd(program.upgrades(), type);
    }

    /** Template mode: how many of the type the drone has beyond the program. */
    private int extra(DroneProgram program, UpgradeType type) {
        DroneData drone = menu.getDrone();
        return drone != null ? Math.max(0, drone.upgradeCount(type) - program.upgradeCount(type)) : 0;
    }

    /**
     * A click on a tile (section 7.2). Direct: left-click installs one from the input, right-click removes one.
     * Template: left/right-click change the programmed count, and shift+right-click removes an extra upgrade from the
     * drone. Returns whether it did something.
     */
    private boolean clickTile(UpgradeType type, int button) {
        DroneProgram program = program();
        if (program == null) {
            return false;
        }
        int count = program.upgradeCount(type);
        if (direct()) {
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && canInstall(program, type)) {
                send(EditProgramPayload.install(menu.containerId, type));
                return true;
            }
            if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && count > 0) {
                send(EditProgramPayload.remove(menu.containerId, type));
                return true;
            }
            return false;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && Screen.hasShiftDown() && extra(program, type) > 0) {
            send(EditProgramPayload.remove(menu.containerId, type));
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && ProgramRules.canAdd(program.upgrades(), type)) {
            send(EditProgramPayload.setCount(menu.containerId, type, count + 1));
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && count > 0) {
            send(EditProgramPayload.setCount(menu.containerId, type, count - 1));
            return true;
        }
        return false;
    }

    /** Whether a left (add) or right (remove) click on the tile would do something, for the hover hints. */
    private boolean canClickTile(DroneProgram program, UpgradeType type, boolean add) {
        if (direct()) {
            return add ? canInstall(program, type) : program.upgradeCount(type) > 0;
        }
        return add ? ProgramRules.canAdd(program.upgrades(), type) : program.upgradeCount(type) > 0 || extra(program, type) > 0;
    }

    private List<Component> tileTooltip(UpgradeType type) {
        DroneProgram program = program();
        if (program == null) {
            return List.of();
        }
        DroneData drone = menu.getDrone();
        List<Component> lines = new ArrayList<>();
        lines.add(ModItems.upgrade(type).get().getDescription());
        lines.add(Component.translatable(type.getTranslationKey() + ".description").withStyle(ChatFormatting.GRAY));
        if (drone != null) {
            lines.add(Component.translatable(KEY + "upgrade.installed", drone.upgradeCount(type), type.maxCount()).withStyle(ChatFormatting.GRAY));
        }
        if (!direct()) {
            lines.add(Component.translatable(KEY + "upgrade.programmed", program.upgradeCount(type)).withStyle(ChatFormatting.GRAY));
        }
        lines.add(Component.translatable(KEY + "upgrade.in_input", menu.inputCount(type)).withStyle(ChatFormatting.GRAY));
        if (type == menu.getInstalling()) {
            lines.add(Component.translatable(KEY + "upgrade.installing", installPercent()).withStyle(ChatFormatting.GRAY));
        } else if (drone != null && drone.upgradeCount(type) < type.maxCount()) {
            lines.add(Component.translatable(KEY + "upgrade.cost", EnergyFormat.amount(ProgramRules.installCost(type, drone.upgradeCount(type) + 1)))
                    .withStyle(ChatFormatting.GRAY));
        }
        if (!direct() && extra(program, type) > 0) {
            lines.add(Component.translatable(KEY + "upgrade.extra", extra(program, type)).withStyle(ChatFormatting.RED));
        }
        lines.add(Component.translatable(direct() ? KEY + "upgrade.hint.direct" : KEY + "upgrade.hint.template").withStyle(ChatFormatting.GREEN));
        return lines;
    }

    private int installPercent() {
        return menu.getTime() > 0 ? menu.getProgress() * 100 / menu.getTime() : 0;
    }

    /** One upgrade type: its icon, pips for the counts, and the install progress. */
    private class Tile extends KitWidget {
        private final UpgradeType type;

        Tile(int x, int y, UpgradeType type) {
            super(x, y, TILE_WIDTH, TILE_HEIGHT);
            this.type = type;
            tooltip(() -> tileTooltip(type));
        }

        @Override
        protected void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            DroneProgram program = program();
            if (program == null) {
                return;
            }
            int x = getX();
            int y = getY();
            graphics.blitSprite(Kit.CELL, x, y, width, height);
            if (isHovered()) {
                Kit.outline(graphics, x, y, width, height, accent());
            }
            graphics.renderItem(new ItemStack(ModItems.upgrade(type).get()), x + 5, y + 2);

            DroneData drone = menu.getDrone();
            int installed = drone != null ? drone.upgradeCount(type) : 0;
            int programmed = program.upgradeCount(type);
            int input = menu.inputCount(type);
            graphics.pose().pushPose();
            graphics.pose().translate(0, 0, 200);
            if (installed == 0 && programmed == 0 && input == 0) {
                graphics.fill(x + 5, y + 2, x + 21, y + 18, LOW_KEY_FADE);
            }
            pips(graphics, x, y + 21, pipColors(drone, installed, programmed));
            if (type == menu.getInstalling()) {
                graphics.fill(x + 2, y + 25, x + 25, y + 26, TILE_PROGRESS_TRACK);
                graphics.fill(x + 2, y + 25, x + 2 + Math.round(23 * Kit.fraction(menu.getProgress(), menu.getTime())), y + 26, OK_COLOR);
            }
            if (isHovered()) {
                if (canClickTile(program, type, false)) {
                    miniButton(graphics, x, y + 5, "-");
                }
                if (canClickTile(program, type, true)) {
                    miniButton(graphics, x + width - 7, y + 5, "+");
                }
            }
            graphics.pose().popPose();
        }

        /**
         * Direct: lit pips are installed. Template with a drone: green is installed, amber is missing and red is extra.
         * Template without a drone: lit pips are programmed.
         */
        private int[] pipColors(@Nullable DroneData drone, int installed, int programmed) {
            int count = Math.max(type.maxCount(), Math.max(installed, programmed));
            int[] colors = new int[count];
            for (int i = 0; i < count; i++) {
                if (direct()) {
                    colors[i] = i < installed ? accent() : PIP_OFF_COLOR;
                } else if (drone == null) {
                    colors[i] = i < programmed ? accent() : PIP_OFF_COLOR;
                } else if (i < Math.min(installed, programmed)) {
                    colors[i] = OK_COLOR;
                } else if (i < programmed) {
                    colors[i] = MISSING_COLOR;
                } else if (i < installed) {
                    colors[i] = BAD_COLOR;
                } else {
                    colors[i] = PIP_OFF_COLOR;
                }
            }
            return colors;
        }

        private void pips(GuiGraphics graphics, int x, int y, int[] colors) {
            if (colors.length <= MAX_PIPS) {
                int total = colors.length * 3 - 1;
                int startX = x + (TILE_WIDTH - total) / 2;
                for (int i = 0; i < colors.length; i++) {
                    graphics.fill(startX + i * 3, y, startX + i * 3 + 2, y + 3, colors[i]);
                }
                return;
            }
            int barWidth = TILE_WIDTH - 4;
            for (int px = 0; px < barWidth; px++) {
                graphics.fill(x + 2 + px, y, x + 3 + px, y + 3, colors[px * colors.length / barWidth]);
            }
        }

        private void miniButton(GuiGraphics graphics, int x, int y, String text) {
            graphics.fill(x, y, x + 7, y + 8, MINI_BUTTON_COLOR);
            Kit.outline(graphics, x, y, 7, 8, accent());
            graphics.drawString(font, text, x + 2, y, accent(), false);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (visible && isMouseOver(mouseX, mouseY) && (button == GLFW.GLFW_MOUSE_BUTTON_LEFT || button == GLFW.GLFW_MOUSE_BUTTON_RIGHT)) {
                if (clickTile(type, button)) {
                    playDownSound(minecraft.getSoundManager());
                }
                return true;
            }
            return false;
        }
    }

    // --- Targets ---

    private TargetEntry.Kind rowKind(int index) {
        return rowKinds.getOrDefault(index, TargetEntry.Kind.ENTITY_TYPE);
    }

    private void drawKindIcon(GuiGraphics graphics, int index, int x, int y) {
        switch (rowKind(index)) {
            case ENTITY_TYPE -> graphics.blitSprite(ICON_EGG, x + 4, y + 3, 7, 8);
            case TAG -> graphics.blitSprite(ICON_TAG, x + 4, y + 4, 7, 6);
            case PLAYER_NAME -> {
                FieldBox box = targetBoxes.get(index);
                EntityPreview.drawFace(graphics, box != null ? box.getValue().trim() : "", x + 3, y + 3, 8);
            }
        }
    }

    private void cycleKind(int index) {
        TargetEntry.Kind[] kinds = TargetEntry.Kind.values();
        rowKinds.put(index, kinds[(rowKind(index).ordinal() + 1) % kinds.length]);
        FieldBox box = targetBoxes.get(index);
        if (box != null && !box.getValue().isBlank()) {
            commitTarget(index);
        }
    }

    private void commitTarget(int index) {
        DroneProgram program = program();
        FieldBox box = targetBoxes.get(index);
        if (program == null || box == null) {
            return;
        }
        List<TargetEntry> previous = program.config().targets();
        List<TargetEntry> targets = new ArrayList<>(previous);
        String text = box.getValue().trim();
        if (text.isEmpty()) {
            box.setInvalid(false);
            if (index < targets.size()) {
                removeTarget(index);
            }
            return;
        }
        Optional<TargetEntry> parsed = TargetEntry.parse(rowKind(index), text);
        if (parsed.isEmpty()) {
            reject(box, Component.translatable(KEY + "target.unknown." + rowKind(index).getSerializedName(), text));
            return;
        }
        int at = Math.min(index, targets.size());
        if (at < targets.size()) {
            targets.set(at, parsed.get());
        } else {
            targets.add(parsed.get());
        }
        String problem = ProgramRules.targetProblem(targets, at, previous, program.upgrades());
        if (problem != null) {
            reject(box, Component.translatable(problem, parsed.get().displayString()));
            return;
        }
        box.setInvalid(false);
        if (!targets.equals(previous)) {
            sendConfig(program.config().withTargets(targets));
        }
    }

    /** The index of the target row being typed in, or -1. */
    private int focusedTargetRow() {
        for (Map.Entry<Integer, FieldBox> entry : targetBoxes.entrySet()) {
            if (entry.getValue() == getFocused()) {
                return entry.getKey();
            }
        }
        return -1;
    }

    /** Shows the suggestions for the target row being typed in, or hides them if there is none. */
    private void updateSuggestions() {
        if (suggestions == null) {
            return;
        }
        int index = focusedTargetRow();
        DroneProgram program = program();
        if (index < 0 || program == null) {
            suggestions.hide();
            return;
        }
        // Entries in the other rows would be rejected as duplicates.
        List<TargetEntry> targets = program.config().targets();
        suggestions.show(targetBoxes.get(index), rowKind(index), entry -> {
            for (int i = 0; i < targets.size(); i++) {
                if (i != index && targets.get(i).sameAs(entry)) {
                    return true;
                }
            }
            return false;
        }, width);
    }

    private void acceptSuggestion(String value) {
        int index = focusedTargetRow();
        if (index >= 0) {
            targetBoxes.get(index).setValue(value);
            commitTarget(index);
        }
    }

    private void removeTarget(int index) {
        DroneProgram program = program();
        if (program == null) {
            return;
        }
        List<TargetEntry> targets = new ArrayList<>(program.config().targets());
        if (index < targets.size()) {
            targets.remove(index);
            // Later rows move up, so their picked kinds move with them.
            Map<Integer, TargetEntry.Kind> shifted = new HashMap<>();
            rowKinds.forEach((row, kind) -> {
                if (row != index) {
                    shifted.put(row > index ? row - 1 : row, kind);
                }
            });
            rowKinds.clear();
            rowKinds.putAll(shifted);
            sendConfig(program.config().withTargets(targets));
        } else {
            FieldBox box = targetBoxes.get(index);
            if (box != null) {
                box.setValue("");
                box.setInvalid(false);
            }
        }
    }

    /** The stored entry of a row, or null for an empty row. */
    @Nullable
    private TargetEntry storedTarget(int index) {
        DroneProgram program = program();
        return program != null && index < program.config().targets().size() ? program.config().targets().get(index) : null;
    }

    private List<Component> targetTooltip(int index) {
        TargetEntry entry = storedTarget(index);
        List<Component> lines = new ArrayList<>();
        if (entry != null) {
            lines.add(Component.literal(entry.displayString()));
            lines.add(Component.translatable("screen.seekerdrones.target.kind." + entry.kind().getSerializedName()).withStyle(ChatFormatting.GRAY));
            Component ignored = ignoredReason(index);
            if (ignored != null) {
                lines.add(ignored.copy().withStyle(ChatFormatting.RED));
            }
        } else {
            lines.add(Component.translatable(KEY + "target.empty"));
        }
        lines.add(Component.translatable(KEY + "target.select").withStyle(ChatFormatting.GRAY));
        return lines;
    }

    private List<Component> previewTooltip() {
        TargetEntry entry = storedTarget(selectedRow);
        if (entry == null) {
            return List.of();
        }
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(entry.displayString()));
        lines.add(Component.translatable(KEY + "target.preview").withStyle(ChatFormatting.GRAY));
        if (entry.kind() == TargetEntry.Kind.TAG) {
            lines.add(Component.translatable("screen.seekerdrones.target.tag_cycles").withStyle(ChatFormatting.GRAY));
        }
        return lines;
    }

    /** Why a stored target entry is ignored at runtime (section 2.7), or null. */
    @Nullable
    private Component ignoredReason(int index) {
        DroneProgram program = program();
        TargetEntry entry = storedTarget(index);
        if (program == null || entry == null) {
            return null;
        }
        if (index >= program.allowedTargetCount()) {
            return Component.translatable(KEY + "target.ignored.no_slot");
        }
        if (entry.kind() == TargetEntry.Kind.PLAYER_NAME && program.upgradeCount(UpgradeType.PLAYER_SEEK) <= 0) {
            return Component.translatable(KEY + "target.ignored.player_seek");
        }
        if (TargetBlacklist.blocks(entry)) {
            return Component.translatable(KEY + "target.ignored.blacklisted");
        }
        return null;
    }

    /** The small red × that removes a target row. */
    private static class RemoveButton extends KitWidget {
        private final Runnable onPress;

        RemoveButton(int x, int y, Runnable onPress) {
            super(x, y, 10, 10);
            this.onPress = onPress;
        }

        @Override
        protected void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            graphics.blitSprite(isHovered() ? REMOVE_HIGHLIGHTED : REMOVE, getX(), getY(), width, height);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (visible && button == 0 && isMouseOver(mouseX, mouseY)) {
                playDownSound(Minecraft.getInstance().getSoundManager());
                onPress.run();
                return true;
            }
            return false;
        }
    }

    // --- Behavior ---

    private int maxRadius() {
        DroneProgram program = program();
        return program != null ? Math.max(1, DroneStats.maxPatrolRadius(program.upgradeCount(UpgradeType.PATROL))) : 1;
    }

    /** The radius the slider shows: the set one, capped at the max, or the max if none is set. */
    private int currentRadius() {
        DroneProgram program = program();
        int max = maxRadius();
        return program != null ? program.config().patrolRadius().map(radius -> Math.min(radius, max)).orElse(max) : max;
    }

    private void setFollowDistance(int distance) {
        DroneProgram program = program();
        // Force the field to show the synced value again.
        syncedConfig = null;
        if (program != null && distance != program.config().followDistance()) {
            sendConfig(program.config().withFollowDistance(distance));
        }
    }

    private void setRadius(int radius) {
        DroneProgram program = program();
        syncedConfig = null;
        if (program != null && !Optional.of(radius).equals(program.config().patrolRadius())) {
            sendConfig(program.config().withPatrolRadius(Optional.of(radius)));
        }
    }

    private void commitFollowDistance() {
        DroneProgram program = program();
        if (program == null || followBox == null) {
            return;
        }
        int max = ProgramRules.maxFollowDistance();
        int distance = parseInt(followBox.getValue(), -1);
        if (distance < 1 || distance > max) {
            reject(followBox, Component.translatable(KEY + "follow_distance.invalid", 1, max));
            return;
        }
        followBox.setInvalid(false);
        if (distance != program.config().followDistance()) {
            sendConfig(program.config().withFollowDistance(distance));
        }
    }

    private void commitCenter() {
        if (centerX == null || centerY == null || centerZ == null) {
            return;
        }
        String x = centerX.getValue().trim();
        String y = centerY.getValue().trim();
        String z = centerZ.getValue().trim();
        if (x.isEmpty() && y.isEmpty() && z.isEmpty()) {
            setCenter(Optional.empty());
            return;
        }
        Integer px = parseCoordinate(x);
        Integer py = parseCoordinate(y);
        Integer pz = parseCoordinate(z);
        if (px == null || py == null || pz == null) {
            // Not complete yet: wait until all three are filled in.
            return;
        }
        setCenter(Optional.of(new BlockPos(px, py, pz)));
    }

    private void setCenter(Optional<BlockPos> pos) {
        DroneProgram program = program();
        if (program == null || minecraft.level == null) {
            return;
        }
        Optional<GlobalPos> center = pos.map(p -> GlobalPos.of(minecraft.level.dimension(), p));
        // Force the fields to show the new value even if they are focused.
        syncedConfig = null;
        if (!center.equals(program.config().patrolCenter())) {
            sendConfig(program.config().withPatrolCenter(center));
        }
    }

    private void commitRadius() {
        DroneProgram program = program();
        if (program == null || radiusBox == null) {
            return;
        }
        String text = radiusBox.getValue().trim();
        Optional<Integer> radius = Optional.empty();
        if (!text.isEmpty()) {
            int value = parseInt(text, 0);
            if (value < 1) {
                reject(radiusBox, Component.translatable(KEY + "patrol_radius.invalid"));
                return;
            }
            radius = Optional.of(value);
        }
        radiusBox.setInvalid(false);
        if (!radius.equals(program.config().patrolRadius())) {
            sendConfig(program.config().withPatrolRadius(radius));
        }
    }

    // --- Identity ---

    private void commitLabel() {
        DroneProgram program = program();
        if (program == null || labelBox == null) {
            return;
        }
        String label = StringUtil.filterText(labelBox.getValue().trim());
        if (!label.equals(program.config().label())) {
            sendConfig(program.config().withLabel(label));
        }
    }

    /** One of the 16 dye colors. The selected one has a white outline. */
    private class Swatch extends KitWidget {
        private final DyeColor color;

        Swatch(int x, int y, DyeColor color) {
            super(x, y, SWATCH_SIZE, SWATCH_SIZE);
            this.color = color;
            tooltip(() -> List.of(Component.translatable("color.minecraft." + color.getName())));
        }

        @Override
        protected void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            DroneProgram program = program();
            if (program != null && program.config().color() == color) {
                Kit.outline(graphics, getX() - 2, getY() - 2, width + 4, height + 4, Kit.WHITE);
            }
            graphics.fill(getX(), getY(), getX() + width, getY() + height, 0xFF000000);
            graphics.fill(getX() + 1, getY() + 1, getX() + width - 1, getY() + height - 1, FastColor.ARGB32.opaque(color.getTextureDiffuseColor()));
            if (isHovered()) {
                Kit.outline(graphics, getX(), getY(), width, height, accent());
            }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            DroneProgram program = program();
            if (visible && button == 0 && isMouseOver(mouseX, mouseY) && program != null) {
                playDownSound(minecraft.getSoundManager());
                if (program.config().color() != color) {
                    sendConfig(program.config().withColor(color));
                }
                return true;
            }
            return false;
        }
    }

    private void reject(FieldBox box, Component message) {
        box.setInvalid(true);
        editError = message;
    }

    private static int parseInt(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Nullable
    private static Integer parseCoordinate(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // --- Ticking: keep widgets in step with the synced state ---

    @Override
    protected void containerTick() {
        super.containerTick();
        if (!currentStructure().equals(structure)) {
            rebuildWidgets();
            return;
        }
        syncWidgets();
    }

    private void syncWidgets() {
        removeButtons.forEach((index, button) -> {
            FieldBox box = targetBoxes.get(index);
            button.visible = storedTarget(index) != null || box != null && !box.getValue().isEmpty();
        });
        DroneProgram program = program();
        if (program == null || program.config().equals(syncedConfig)) {
            return;
        }
        DroneConfig config = program.config();
        syncedConfig = config;
        List<TargetEntry> targets = config.targets();
        for (Map.Entry<Integer, FieldBox> entry : targetBoxes.entrySet()) {
            int index = entry.getKey();
            if (index < targets.size()) {
                rowKinds.put(index, targets.get(index).kind());
                setIfIdle(entry.getValue(), targets.get(index).displayString());
            } else {
                setIfIdle(entry.getValue(), "");
            }
        }
        if (sliders.stream().noneMatch(KitSlider::isDragging)) {
            setIfIdle(followBox, String.valueOf(config.followDistance()));
            setIfIdle(radiusBox, config.patrolRadius().map(String::valueOf).orElse(""));
        }
        setIfIdle(labelBox, config.label());
        Optional<BlockPos> center = config.patrolCenter().map(GlobalPos::pos);
        setIfIdle(centerX, center.map(p -> String.valueOf(p.getX())).orElse(""));
        setIfIdle(centerY, center.map(p -> String.valueOf(p.getY())).orElse(""));
        setIfIdle(centerZ, center.map(p -> String.valueOf(p.getZ())).orElse(""));
        if (radiusBox != null) {
            radiusBox.setHint(Component.literal(String.valueOf(maxRadius())).withColor(Kit.DISPLAY_TEXT_DIM & 0xFFFFFF));
        }
    }

    /** Shows the synced value, unless the player is typing in the field. */
    private static void setIfIdle(@Nullable FieldBox box, String value) {
        if (box != null && !box.isFocused()) {
            box.showValue(value);
            box.setInvalid(false);
        }
    }

    // --- Input ---

    @Override
    public void setFocused(@Nullable GuiEventListener listener) {
        super.setFocused(listener);
        int row = focusedTargetRow();
        if (row >= 0) {
            selectedRow = row;
        }
        updateSuggestions();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (suggestions != null && suggestions.keyPressed(keyCode)) {
            return true;
        }
        // Typed keys go to the focused field, so e.g. "E" doesn't close the screen.
        if (keyCode != GLFW.GLFW_KEY_ESCAPE && getFocused() instanceof EditBox box && box.canConsumeInput()) {
            box.keyPressed(keyCode, scanCode, modifiers);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (suggestions != null && suggestions.mouseClicked(mouseX, mouseY)) {
            return true;
        }
        // Clicking anywhere else commits the focused field.
        if (getFocused() instanceof FieldBox box && !box.isMouseOver(mouseX, mouseY)) {
            setFocused(null);
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        for (KitSlider slider : sliders) {
            if (slider.isDragging()) {
                slider.drag(mouseX);
                return true;
            }
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        for (KitSlider slider : sliders) {
            if (slider.isDragging()) {
                slider.release();
            }
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /** The stepped frame: clicks beside the inventory frame are outside, so held items drop there. */
    @Override
    protected boolean hasClickedOutside(double mouseX, double mouseY, int guiLeft, int guiTop, int button) {
        boolean inConsole = mouseX >= guiLeft && mouseX < guiLeft + WIDTH && mouseY >= guiTop && mouseY < guiTop + CONSOLE_HEIGHT;
        boolean inInventory = mouseX >= guiLeft + INVENTORY_FRAME_X && mouseX < guiLeft + INVENTORY_FRAME_X + INVENTORY_FRAME_WIDTH
                && mouseY >= guiTop + INVENTORY_FRAME_Y && mouseY < guiTop + HEIGHT;
        return !inConsole && !inInventory && !sideTabs.isMouseOver(mouseX, mouseY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (suggestions != null && suggestions.mouseScrolled(mouseX, mouseY, scrollY)) {
            return true;
        }
        if (isHovering(EDITOR_X, EDITOR_Y, EDITOR_WIDTH, EDITOR_HEIGHT, mouseX, mouseY)) {
            int step = -(int) Math.signum(scrollY);
            DroneProgram program = program();
            if (tab == Tab.UPGRADES) {
                tileScroll = Math.clamp(tileScroll + step, 0, maxTileScroll());
            } else if (tab == Tab.TARGETS && program != null) {
                targetScroll = Math.clamp(targetScroll + step, 0, Math.max(0, targetRows(program) - VISIBLE_ROWS));
            }
            if (!currentStructure().equals(structure)) {
                rebuildWidgets();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        if (suggestions != null) {
            suggestions.mouseMoved(mouseX, mouseY);
        }
        super.mouseMoved(mouseX, mouseY);
    }

    /** Commits the field being typed in before the menu closes. */
    @Override
    public void onClose() {
        if (getFocused() instanceof FieldBox) {
            setFocused(null);
        }
        super.onClose();
    }

    // --- Rendering ---

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Nothing under the suggestions is hovered, so no tooltip shows through them.
        boolean overSuggestions = suggestions != null && suggestions.isMouseOver(mouseX, mouseY);
        super.render(graphics, overSuggestions ? -1 : mouseX, overSuggestions ? -1 : mouseY, partialTick);
        if (hoveredSlot != null && !hoveredSlot.hasItem() && hoveredSlot.index < ProgrammingStationBlockEntity.SLOT_COUNT && menu.getCarried().isEmpty()) {
            String slot = hoveredSlot.index == ProgrammingStationBlockEntity.DRONE_SLOT ? "slot.drone" : "slot.input";
            KitWidget.showTooltip(List.of(Component.translatable(KEY + slot),
                    Component.translatable(KEY + slot + ".tooltip").withStyle(ChatFormatting.GRAY)));
        }
        if (suggestions != null && suggestions.isVisible()) {
            suggestions.render(graphics);
        }
    }

    @Override
    protected void renderFrame(GuiGraphics graphics) {
        int x = leftPos;
        int y = topPos;
        Kit.frame(graphics, x + INVENTORY_FRAME_X, y + INVENTORY_FRAME_Y, INVENTORY_FRAME_WIDTH, HEIGHT - INVENTORY_FRAME_Y);
        Kit.frame(graphics, x, y, WIDTH, CONSOLE_HEIGHT);
        // Join the two frames: cover the console's bottom edge and the inventory frame's top edge between them.
        graphics.fill(x + INVENTORY_FRAME_X + FRAME_BORDER, y + INVENTORY_FRAME_Y, x + INVENTORY_FRAME_X + INVENTORY_FRAME_WIDTH - FRAME_BORDER,
                y + CONSOLE_HEIGHT, PANEL_COLOR);
    }

    @Override
    protected void renderSlotBackground(GuiGraphics graphics, Slot slot, int x, int y) {
        super.renderSlotBackground(graphics, slot, x, y);
        if (slot.hasItem() || slot.index >= ProgrammingStationBlockEntity.SLOT_COUNT) {
            return;
        }
        if (slot.index == ProgrammingStationBlockEntity.DRONE_SLOT) {
            Kit.ghost(graphics, new ItemStack(ModItems.DRONE.get()), x + 1, y + 1);
        } else {
            UpgradeType[] types = UpgradeType.values();
            Kit.ghost(graphics, new ItemStack(ModItems.upgrade(types[(int) (Util.getMillis() / GHOST_CYCLE_MILLIS % types.length)]).get()), x + 1, y + 1);
        }
    }

    @Override
    protected void renderContents(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        renderBay(graphics);
        int x = leftPos + EDITOR_X;
        int y = topPos + EDITOR_Y;
        Kit.display(graphics, x, y, EDITOR_WIDTH, EDITOR_HEIGHT);
        graphics.fill(x + 1, y, x + EDITOR_WIDTH - 1, y + 1, accent());
        DroneProgram program = program();
        if (program == null) {
            Component empty = Component.translatable(direct() ? KEY + "no_drone" : KEY + "loading");
            graphics.drawWordWrap(font, empty, x + 4, y + 5, EDITOR_WIDTH - 8, Kit.DISPLAY_TEXT_DIM);
            return;
        }
        switch (tab) {
            case UPGRADES -> renderUpgrades(graphics, program, x, y);
            case TARGETS -> renderTargets(graphics, program, x, y);
            case BEHAVIOR -> renderBehavior(graphics, program, x, y);
            case IDENTITY -> renderIdentity(graphics, program, x, y);
        }
    }

    private void header(GuiGraphics graphics, Tab shown, int x, int y) {
        graphics.drawString(font, Kit.upper(Component.translatable(KEY + "tab." + shown.name().toLowerCase())), x + 3, y + HEADER_Y, Kit.DISPLAY_TEXT,
                false);
    }

    private void label(GuiGraphics graphics, Component text, int x, int y) {
        Kit.smallText(graphics, font, Kit.upper(text), x, y, Kit.DISPLAY_TEXT_DIM, false);
    }

    /**
     * The drone bay: the drone (the program's color) circling its patrol radius, scaled to the largest radius its
     * Patrol upgrades allow, or hovering without Patrol. A ring around the drone slot shows the install progress.
     */
    private void renderBay(GuiGraphics graphics) {
        int x = leftPos + BAY_X;
        int y = topPos + BAY_Y;
        Kit.display(graphics, x, y, BAY_WIDTH, BAY_HEIGHT);
        DroneProgram program = program();
        if (program != null) {
            float centerX = x + BAY_WIDTH / 2F;
            float centerY = y + ORBIT_Y;
            DyeColor color = program.config().color();
            graphics.enableScissor(x + 1, y + 1, x + BAY_WIDTH - 1, y + BAY_HEIGHT - 1);
            if (program.upgradeCount(UpgradeType.PATROL) > 0) {
                float radiusX = ORBIT_MIN + ORBIT_RANGE * Kit.fraction(currentRadius(), maxRadius());
                float radiusY = radiusX * ORBIT_TILT;
                DronePreview.drawOrbit(graphics, centerX, centerY, radiusX, radiusY, accent());
                graphics.fill(Mth.floor(centerX) - 1, Mth.floor(centerY) - 1, Mth.floor(centerX) + 1, Mth.floor(centerY) + 1, accent());
                dronePreview.renderOrbiting(graphics, color, centerX, centerY, radiusX, radiusY, DRONE_SCALE);
            } else {
                dronePreview.renderHovering(graphics, color, centerX, centerY, DRONE_SCALE);
            }
            graphics.disableScissor();
        }
        if (menu.getDrone() != null) {
            renderRing(graphics);
        }
    }

    /** A ring around the drone slot, filling clockwise from the top as an install step runs. */
    private void renderRing(GuiGraphics graphics) {
        // The slot box's center, a pixel corner.
        int centerX = leftPos + ProgrammingStationMenu.DRONE_X + 8;
        int centerY = topPos + ProgrammingStationMenu.DRONE_Y + 8;
        float progress = menu.getInstalling() != null ? Kit.fraction(menu.getProgress(), menu.getTime()) : 0;
        double circumference = Mth.TWO_PI * RING_RADIUS;
        for (double along = 0; along < circumference; along += 0.5) {
            double angle = along / RING_RADIUS - Math.PI / 2;
            int px = Mth.floor(centerX + Math.cos(angle) * RING_RADIUS);
            int py = Mth.floor(centerY + Math.sin(angle) * RING_RADIUS);
            graphics.fill(px, py, px + 1, py + 1, along / circumference < progress ? OK_COLOR : TRACK_COLOR);
        }
    }

    private void renderUpgrades(GuiGraphics graphics, DroneProgram program, int x, int y) {
        header(graphics, Tab.UPGRADES, x, y);
        // The slot bar: one segment per upgrade slot, lit when used. Numbers only in the tooltip.
        int used = DroneStats.totalUpgrades(program.upgrades());
        int slots = ServerConfig.get(ServerConfig.UPGRADES_TOTAL_SLOTS);
        int right = x + SLOT_BAR_RIGHT;
        if (slots <= MAX_SLOT_SEGMENTS) {
            for (int i = 0; i < slots; i++) {
                int segmentX = right - (slots - i) * 2;
                graphics.fill(segmentX, y + SLOT_BAR_Y, segmentX + 1, y + SLOT_BAR_Y + SLOT_BAR_HEIGHT, i < used ? accent() : TRACK_COLOR);
            }
        } else {
            int barWidth = MAX_SLOT_SEGMENTS * 2;
            graphics.fill(right - barWidth, y + SLOT_BAR_Y, right, y + SLOT_BAR_Y + SLOT_BAR_HEIGHT, TRACK_COLOR);
            graphics.fill(right - barWidth, y + SLOT_BAR_Y, right - barWidth + Math.round(barWidth * Kit.fraction(used, slots)),
                    y + SLOT_BAR_Y + SLOT_BAR_HEIGHT, accent());
        }
        int maxScroll = maxTileScroll();
        if (maxScroll > 0) {
            int rows = maxScroll + TILE_ROWS;
            int trackHeight = TILE_ROWS * TILE_PITCH_Y - 2;
            int trackY = y + TILE_Y;
            graphics.fill(x + TILE_SCROLLBAR_X, trackY, x + TILE_SCROLLBAR_X + 3, trackY + trackHeight, TRACK_COLOR);
            int thumbHeight = trackHeight * TILE_ROWS / rows;
            int thumbY = trackY + (trackHeight - thumbHeight) * tileScroll / maxScroll;
            graphics.fill(x + TILE_SCROLLBAR_X, thumbY, x + TILE_SCROLLBAR_X + 3, thumbY + thumbHeight, accent());
        }
    }

    private void renderTargets(GuiGraphics graphics, DroneProgram program, int x, int y) {
        header(graphics, Tab.TARGETS, x, y);
        Component slots = Component.translatable(KEY + "target.slots", program.config().targets().size(), program.allowedTargetCount());
        Kit.smallText(graphics, font, slots, x + EDITOR_WIDTH - 4 - Kit.smallWidth(font, slots), y + HEADER_Y + 1, Kit.DISPLAY_TEXT, false);
        int rows = targetRows(program);
        if (rows > VISIBLE_ROWS) {
            int trackY = y + ROW_Y;
            int trackHeight = VISIBLE_ROWS * ROW_PITCH - 2;
            graphics.fill(x + ROW_SCROLLBAR_X, trackY, x + ROW_SCROLLBAR_X + 2, trackY + trackHeight, TRACK_COLOR);
            int thumbHeight = Math.max(4, trackHeight * VISIBLE_ROWS / rows);
            int thumbY = trackY + (trackHeight - thumbHeight) * targetScroll / (rows - VISIBLE_ROWS);
            graphics.fill(x + ROW_SCROLLBAR_X, thumbY, x + ROW_SCROLLBAR_X + 2, thumbY + thumbHeight, accent());
        }

        // The selected row's preview, with what it shows right now under it.
        int previewX = x + PREVIEW_X;
        int previewY = y + PREVIEW_Y;
        graphics.blitSprite(Kit.CELL, previewX, previewY, PREVIEW_WIDTH, PREVIEW_HEIGHT);
        TargetEntry entry = storedTarget(selectedRow);
        Component caption;
        if (entry != null) {
            targetPreview.render(graphics, entry, previewX + 1, previewY + 1, PREVIEW_WIDTH - 2, PREVIEW_HEIGHT - 2, selectedRow);
            caption = targetPreview.caption(entry, selectedRow);
        } else {
            caption = Component.translatable(KEY + "target.none");
        }
        int captionWidth = Kit.smallWidth(font, caption);
        int captionX = captionWidth <= PREVIEW_WIDTH ? previewX + (PREVIEW_WIDTH - captionWidth) / 2 : previewX;
        Kit.scrollingText(graphics, font, caption, captionX, y + PREVIEW_Y + PREVIEW_HEIGHT + 3, PREVIEW_WIDTH, 7, entry != null ? Kit.DISPLAY_TEXT : Kit.DISPLAY_TEXT_DIM,
                false, true);
        Kit.scrollingText(graphics, font, Component.translatable(KEY + "target.hint"), x + 3, y + 96, EDITOR_WIDTH - 7, 7,
                Kit.DISPLAY_TEXT_DIM, false, true);
    }

    private void renderBehavior(GuiGraphics graphics, DroneProgram program, int x, int y) {
        header(graphics, Tab.BEHAVIOR, x, y);
        y += BODY_SHIFT;
        label(graphics, Component.translatable(KEY + "follow_distance.header"), x + 3, y + 15);
        label(graphics, Component.translatable(KEY + "patrol_center"), x + 3, y + 41);
        if (program.upgradeCount(UpgradeType.PATROL) <= 0) {
            graphics.drawWordWrap(font, Component.translatable(KEY + "patrol.none"), x + 3, y + 51, EDITOR_WIDTH - 7, Kit.DISPLAY_TEXT_DIM);
            return;
        }
        label(graphics, Component.translatable(KEY + "patrol_radius.header", maxRadius()), x + 3, y + 69);
    }

    private void renderIdentity(GuiGraphics graphics, DroneProgram program, int x, int y) {
        header(graphics, Tab.IDENTITY, x, y);
        y += BODY_SHIFT;
        label(graphics, Component.translatable(KEY + "label"), x + 3, y + 15);
        label(graphics, Component.translatable(KEY + "color"), x + 3, y + 42);
        DyeColor color = program.config().color();
        // The color's name beside the palette, on up to two lines.
        int nameX = x + 3 + 8 * SWATCH_PITCH + 2;
        int nameWidth = x + EDITOR_WIDTH - 3 - nameX;
        List<FormattedCharSequence> lines = font.split(Component.translatable("color.minecraft." + color.getName()), (int) (nameWidth / Kit.SMALL));
        for (int i = 0; i < Math.min(2, lines.size()); i++) {
            Kit.smallText(graphics, font, lines.get(i), nameX, y + 54 + i * 7, Kit.DISPLAY_TEXT, false);
        }

        // The nameplate: the label in the drone's color, then its ID.
        label(graphics, Component.translatable(KEY + "nameplate"), x + 3, y + 81);
        int plateX = x + 3;
        int plateY = y + 89;
        int plateWidth = EDITOR_WIDTH - 7;
        graphics.fill(plateX, plateY, plateX + plateWidth, plateY + 14, NAMEPLATE_BACKGROUND);
        String label = program.config().label();
        Component name = label.isEmpty()
                ? Component.translatable(KEY + "nameplate.no_label").withStyle(ChatFormatting.GRAY)
                : Component.literal(label).withColor(color.getTextColor());
        DroneData drone = menu.getDrone();
        if (drone != null && drone.hasDroneId()) {
            name = name.copy().append(Component.literal(" #" + drone.droneId()).withStyle(ChatFormatting.GRAY));
        }
        int nameWidthPx = font.width(name);
        int textX = nameWidthPx <= plateWidth - 4 ? plateX + (plateWidth - nameWidthPx) / 2 : plateX + 2;
        Kit.scrollingText(graphics, font, name, textX, plateY + 3, plateWidth - 4, 9, Kit.WHITE, true, false);
    }

    private List<Component> bayTooltip() {
        DroneProgram program = program();
        if (program == null) {
            return List.of(Component.translatable(KEY + "bay"), Component.translatable(KEY + "slot.drone.tooltip").withStyle(ChatFormatting.GRAY));
        }
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable(KEY + "bay"));
        lines.add(program.upgradeCount(UpgradeType.PATROL) > 0
                ? Component.translatable(KEY + "bay.patrol", currentRadius(), maxRadius()).withStyle(ChatFormatting.GRAY)
                : Component.translatable(KEY + "bay.hover").withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable(KEY + "bay.preview").withStyle(ChatFormatting.DARK_GRAY));
        return lines;
    }

    // --- Status ---

    private StatusStrip.Status status(Kit.Light light, boolean blink, Component text) {
        return new StatusStrip.Status(light, blink, text);
    }

    /** The most important problem, or what the station is doing. */
    private StatusStrip.Status status() {
        if (editError != null) {
            return status(Kit.Light.BAD, false, editError);
        }
        DroneData drone = menu.getDrone();
        UpgradeType installing = menu.getInstalling();
        if (direct()) {
            if (drone == null) {
                return status(Kit.Light.IDLE, false, Component.translatable(KEY + "status.insert_drone"));
            }
            if (installing != null) {
                return installingStatus(installing);
            }
            return status(Kit.Light.IDLE, false, Component.translatable(KEY + "status.direct_hint"));
        }
        DroneProgram template = menu.getTemplate();
        if (template == null) {
            return status(Kit.Light.IDLE, false, Component.empty());
        }
        if (!DroneStats.withinUpgradeLimits(template.upgrades())) {
            return status(Kit.Light.BAD, true, Component.translatable(KEY + "status.template_invalid"));
        }
        if (drone == null) {
            return status(Kit.Light.IDLE, false, Component.translatable(KEY + "status.insert_drone_template"));
        }
        if (!menu.isAccepted()) {
            return status(Kit.Light.WARN, false, Component.translatable(KEY + "status.reinsert_drone"));
        }
        List<UpgradeType> extra = new ArrayList<>();
        List<UpgradeType> missing = new ArrayList<>();
        for (UpgradeType type : UpgradeType.values()) {
            if (drone.upgradeCount(type) > template.upgradeCount(type)) {
                extra.add(type);
            } else if (drone.upgradeCount(type) < template.upgradeCount(type) && menu.inputCount(type) == 0 && type != installing) {
                missing.add(type);
            }
        }
        if (!extra.isEmpty()) {
            return status(Kit.Light.BAD, true, Component.translatable(KEY + "status.extra_upgrades", names(extra)));
        }
        if (installing != null) {
            return installingStatus(installing);
        }
        if (!missing.isEmpty()) {
            return status(Kit.Light.WARN, false, Component.translatable(KEY + "status.missing_upgrades", names(missing)));
        }
        if (template.matches(drone)) {
            return status(Kit.Light.OK, false, Component.translatable(KEY + "status.complete"));
        }
        return status(Kit.Light.OK, false, Component.translatable(KEY + "status.working"));
    }

    private StatusStrip.Status installingStatus(UpgradeType installing) {
        int perTick = menu.getTime() > 0 ? menu.getStepCost() / menu.getTime() : 0;
        Component name = Component.translatable(installing.getTranslationKey());
        if (menu.getEnergy() < perTick) {
            return status(Kit.Light.WARN, false, Component.translatable(KEY + "status.no_power", name));
        }
        return status(Kit.Light.OK, false, Component.translatable(KEY + "status.installing", name, installPercent(),
                EnergyFormat.amount(menu.getStepCost())));
    }

    private static Component names(List<UpgradeType> types) {
        Component result = Component.empty();
        for (int i = 0; i < types.size(); i++) {
            if (i > 0) {
                result = result.copy().append(", ");
            }
            result = result.copy().append(Component.translatable(types.get(i).getTranslationKey()));
        }
        return result;
    }

    // --- Mode switch and tabs ---

    /** The Direct/Template switch: the active half is filled with the mode's accent. */
    private class ModeSwitch extends KitWidget {
        ModeSwitch(int x, int y) {
            super(x, y, MODE_WIDTH, MODE_HEIGHT);
            tooltip(() -> List.of(Component.translatable(KEY + "mode"),
                    Component.translatable(KEY + "mode.tooltip").withStyle(ChatFormatting.GRAY)));
        }

        @Override
        protected void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            graphics.blitSprite(Kit.TOGGLE, getX(), getY(), width, height);
            int half = (width - 2) / 2;
            ProgrammingMode[] modes = ProgrammingMode.values();
            for (int i = 0; i < modes.length; i++) {
                int halfX = getX() + 1 + i * half;
                boolean on = modes[i] == mode();
                if (on) {
                    graphics.fill(halfX, getY() + 1, halfX + half, getY() + height - 1, accent());
                }
                Component text = Component.translatable(modes[i].getTranslationKey());
                Kit.smallText(graphics, font, text, halfX + (half - Kit.smallWidth(font, text)) / 2F, getY() + 3.5F,
                        on ? SEGMENT_ON_TEXT : SEGMENT_OFF_TEXT, false);
            }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (!visible || button != 0 || !isMouseOver(mouseX, mouseY)) {
                return false;
            }
            ProgrammingMode clicked = mouseX < getX() + width / 2.0 ? ProgrammingMode.DIRECT : ProgrammingMode.TEMPLATE;
            if (clicked != mode()) {
                playDownSound(minecraft.getSoundManager());
                send(EditProgramPayload.setMode(menu.containerId, clicked));
            }
            return true;
        }
    }

    /** A tab over the editor. The selected one joins the editor and has the accent on its top edge. */
    private class EditorTab extends KitWidget {
        private final Tab target;

        EditorTab(int x, int y, Tab target) {
            super(x, y, TAB_WIDTH, TAB_HEIGHT);
            this.target = target;
            tooltip(() -> List.of(Component.translatable(KEY + "tab." + target.name().toLowerCase())));
        }

        @Override
        protected void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int x = getX();
            int y = getY();
            boolean on = tab == target;
            if (on) {
                // One row taller, over the editor's top edge, so it joins the editor.
                graphics.blitSprite(TAB_SELECTED, x, y, width, height);
                graphics.fill(x + 1, y + 1, x + width - 1, y + 2, accent());
            } else {
                graphics.blitSprite(isHovered() ? TAB_HIGHLIGHTED : TAB, x, y, width, height - 1);
            }
            switch (target) {
                case UPGRADES -> Kit.item(graphics, new ItemStack(ModItems.upgrade(UpgradeType.SIGHT).get()), x + 7, y + 2, 10);
                case TARGETS -> graphics.blitSprite(ICON_TARGET, x + 8, y + 3, 9, 9);
                case BEHAVIOR -> graphics.blitSprite(ICON_ROUTE, x + 8, y + 3, 9, 8);
                case IDENTITY -> graphics.blitSprite(ICON_NAME_TAG, x + 8, y + 4, 9, 6);
            }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (!visible || button != 0 || !isMouseOver(mouseX, mouseY)) {
                return false;
            }
            if (tab != target) {
                playDownSound(minecraft.getSoundManager());
                tab = target;
                rebuildWidgets();
            }
            return true;
        }
    }

    // --- Fields ---

    /**
     * A text field on a display: a dark box with the text inset, an accent border while focused or selected, and a
     * tooltip built when hovered. It commits its value on Enter and when it loses focus.
     */
    private class FieldBox extends EditBox {
        private static final int PADDING_X = 3;
        private final Consumer<FieldBox> onCommit;
        /** Drawn with a dashed border while empty: an empty target row. */
        boolean dashed;
        BooleanSupplier selected = () -> false;
        /** Shows an amber "!" at the right end: an ignored target entry. */
        BooleanSupplier warning = () -> false;
        Supplier<List<Component>> tooltipLines = List::of;

        FieldBox(int x, int y, int width, int height, Component message, Consumer<FieldBox> onCommit) {
            super(ProgrammingStationScreen.this.font, x, y, width, height, message);
            this.onCommit = onCommit;
            setBordered(false);
            setTextColor(Kit.DISPLAY_TEXT & 0xFFFFFF);
        }

        private int paddingY() {
            return (height - 7) / 2;
        }

        void showValue(String value) {
            if (!getValue().equals(value)) {
                setValue(value);
            }
        }

        void setInvalid(boolean invalid) {
            setTextColor(invalid ? INVALID_TEXT : Kit.DISPLAY_TEXT & 0xFFFFFF);
        }

        @Override
        public int getInnerWidth() {
            // EditBox can ask before this box's fields are set.
            return width - 2 * PADDING_X - (warning != null && warning.getAsBoolean() ? 6 : 0);
        }

        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            if (!isVisible()) {
                return;
            }
            boolean empty = dashed && getValue().isEmpty() && !isFocused();
            graphics.blitSprite(empty ? Kit.FIELD_EMPTY : Kit.FIELD, getX(), getY(), width, height);
            if (isFocused() || selected.getAsBoolean()) {
                Kit.outline(graphics, getX(), getY(), width, height, accent());
            }
            // EditBox draws its text at its own corner without a border, so shift it inside the box.
            graphics.pose().pushPose();
            graphics.pose().translate(PADDING_X, paddingY(), 0);
            super.renderWidget(graphics, mouseX - PADDING_X, mouseY - paddingY(), partialTick);
            graphics.pose().popPose();
            if (warning.getAsBoolean()) {
                graphics.drawString(font, "!", getX() + width - 6, getY() + paddingY(), WARN_COLOR, false);
            }
            if (isHovered() && !isFocused()) {
                KitWidget.showTooltip(tooltipLines.get());
            }
        }

        @Override
        public void onClick(double mouseX, double mouseY) {
            super.onClick(mouseX - PADDING_X, mouseY);
        }

        @Override
        public void setFocused(boolean focused) {
            boolean wasFocused = isFocused();
            super.setFocused(focused);
            if (wasFocused && !focused) {
                onCommit.accept(this);
            }
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (isFocused() && (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)) {
                onCommit.accept(this);
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
    }
}
