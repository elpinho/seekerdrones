package com.elpinho.seekerdrones.client;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import org.lwjgl.glfw.GLFW;

import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.drone.DroneConfig;
import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.DroneStats;
import com.elpinho.seekerdrones.drone.TargetBlacklist;
import com.elpinho.seekerdrones.drone.TargetEntry;
import com.elpinho.seekerdrones.drone.UpgradeType;
import com.elpinho.seekerdrones.network.EditProgramPayload;
import com.elpinho.seekerdrones.programming.DroneProgram;
import com.elpinho.seekerdrones.programming.ProgramRules;
import com.elpinho.seekerdrones.programming.ProgrammingMode;
import com.elpinho.seekerdrones.programming.ProgrammingStationMenu;
import com.elpinho.seekerdrones.registry.ModItems;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringUtil;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Drone Programming Station screen (DESIGN.md section 7.2). The slots, FE bar and install progress are on the left. The
 * editor on the right has Upgrades, Targets and Settings tabs, and edits either the drone in the slot (Direct mode) or
 * the template (Template mode). Every edit is sent to the server, which checks it again. Drawn with plain fills until
 * the M9 polish pass adds textures.
 */
public class ProgrammingStationScreen extends AbstractContainerScreen<ProgrammingStationMenu> {
    private static final String KEY = "screen.seekerdrones.programming_station.";

    private static final int PANEL_COLOR = 0xFFC6C6C6;
    private static final int PANEL_BORDER_COLOR = 0xFF555555;
    private static final int EDITOR_COLOR = 0xFFB4B4B4;
    private static final int SLOT_COLOR = 0xFF8B8B8B;
    private static final int SLOT_BORDER_COLOR = 0xFF373737;
    private static final int BAR_BACKGROUND_COLOR = 0xFF2A2A38;
    private static final int ENERGY_BAR_COLOR = 0xFFD83A2E;
    private static final int PROGRESS_COLOR = 0xFF3FB950;
    private static final int LABEL_COLOR = 0xFF404040;
    private static final int MUTED_COLOR = 0xFF707070;
    private static final int ERROR_COLOR = 0xFFB02020;
    private static final int WARNING_COLOR = 0xFF8A5A00;
    private static final int OK_COLOR = 0xFF1E7B1E;
    private static final int BOX_TEXT_COLOR = 0xE0E0E0;
    private static final int BOX_ERROR_COLOR = 0xFF6060;

    private static final int ENERGY_X = 8;
    private static final int ENERGY_Y = 20;
    private static final int ENERGY_WIDTH = 10;
    private static final int ENERGY_HEIGHT = 86;
    private static final int PROGRESS_X = ProgrammingStationMenu.DRONE_X;
    private static final int PROGRESS_Y = ProgrammingStationMenu.DRONE_Y + 20;
    private static final int PROGRESS_WIDTH = 16;
    private static final int PROGRESS_HEIGHT = 3;

    private static final int EDITOR_X = 90;
    private static final int EDITOR_WIDTH = 182;
    private static final int TAB_Y = 18;
    private static final int TAB_HEIGHT = 14;
    private static final int CONTENT_Y = 36;
    private static final int CONTENT_BOTTOM = 146;
    private static final int STATUS_Y = 149;
    private static final int STATUS_LINES = 2;
    private static final int STATUS_LINE_HEIGHT = 10;
    private static final int STATUS_X = 8;
    private static final String ELLIPSIS = "…";

    private static final int CELL_WIDTH = 92;
    private static final int CELL_HEIGHT = 19;
    private static final int UPGRADE_ROWS = 5;
    private static final int FOOTER_Y = CONTENT_Y + UPGRADE_ROWS * CELL_HEIGHT + 2;

    private static final int TARGET_ROW_HEIGHT = 20;
    private static final int TARGET_ROWS_Y = CONTENT_Y + 12;
    private static final int VISIBLE_TARGET_ROWS = 4;

    private static final int SETTINGS_ROW_HEIGHT = 21;
    private static final int SETTINGS_CONTROL_X = 62;

    private enum Tab {
        UPGRADES, TARGETS, SETTINGS
    }

    /** What decides which widgets exist. The widgets are rebuilt when it changes. */
    private record Structure(Tab tab, ProgrammingMode mode, boolean hasProgram, int targetRows, int scroll, boolean patrol) {}

    private record UpgradeRow(UpgradeType type, Button remove, Button minus, Button plus) {}

    private Tab tab = Tab.UPGRADES;
    private int targetScroll;
    @Nullable
    private Structure structure;
    /** The settings last written into the edit boxes. */
    @Nullable
    private DroneConfig syncedConfig;
    /** The kind picked for each target row, by row index. */
    private final Map<Integer, TargetEntry.Kind> rowKinds = new HashMap<>();
    /** Feedback on the last rejected edit, shown in the status line until the next edit. */
    @Nullable
    private Component editError;

    private final List<Button> tabButtons = new ArrayList<>();
    private final Map<UpgradeType, UpgradeRow> upgradeRows = new EnumMap<>(UpgradeType.class);
    private final Map<Integer, CommitBox> targetBoxes = new HashMap<>();
    private final Map<Integer, Button> kindButtons = new HashMap<>();
    @Nullable
    private Button copyButton;
    @Nullable
    private CommitBox followBox;
    @Nullable
    private CommitBox labelBox;
    @Nullable
    private Button colorButton;
    @Nullable
    private CommitBox centerX;
    @Nullable
    private CommitBox centerY;
    @Nullable
    private CommitBox centerZ;
    @Nullable
    private CommitBox radiusBox;

    public ProgrammingStationScreen(ProgrammingStationMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = 280;
        this.imageHeight = 262;
        this.inventoryLabelX = ProgrammingStationMenu.INVENTORY_X;
        this.inventoryLabelY = ProgrammingStationMenu.INVENTORY_Y - 11;
    }

    // --- State ---

    private ProgrammingMode mode() {
        return menu.getMode();
    }

    /** What the editor shows: the drone's own values in Direct mode, the template in Template mode. */
    @Nullable
    private DroneProgram program() {
        if (mode() == ProgrammingMode.DIRECT) {
            DroneData drone = menu.getDrone();
            return drone != null ? DroneProgram.of(drone) : null;
        }
        return menu.getTemplate();
    }

    private Structure currentStructure() {
        DroneProgram program = program();
        int targetRows = program != null ? Math.max(program.allowedTargetCount(), program.config().targets().size()) : 0;
        boolean patrol = program != null && program.upgradeCount(UpgradeType.PATROL) > 0;
        return new Structure(tab, mode(), program != null, targetRows, targetScroll, patrol);
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
        tabButtons.clear();
        upgradeRows.clear();
        targetBoxes.clear();
        kindButtons.clear();
        copyButton = null;
        followBox = labelBox = centerX = centerY = centerZ = radiusBox = null;
        colorButton = null;

        addRenderableWidget(Button.builder(Component.translatable(mode().getTranslationKey()), button -> send(EditProgramPayload.setMode(menu.containerId,
                        mode() == ProgrammingMode.DIRECT ? ProgrammingMode.TEMPLATE : ProgrammingMode.DIRECT)))
                .bounds(leftPos + imageWidth - 88, topPos + 3, 80, 14)
                .tooltip(Tooltip.create(Component.translatable(KEY + "mode.tooltip")))
                .build());
        Tab[] tabs = Tab.values();
        int tabWidth = (EDITOR_WIDTH - (tabs.length - 1) * 2) / tabs.length;
        for (int i = 0; i < tabs.length; i++) {
            Tab target = tabs[i];
            Button button = addRenderableWidget(Button.builder(Component.translatable(KEY + "tab." + target.name().toLowerCase()), b -> {
                        tab = target;
                        rebuildWidgets();
                    })
                    .bounds(leftPos + EDITOR_X + i * (tabWidth + 2), topPos + TAB_Y, tabWidth, TAB_HEIGHT)
                    .build());
            button.active = target != tab;
            tabButtons.add(button);
        }

        DroneProgram program = program();
        if (program != null) {
            switch (tab) {
                case UPGRADES -> initUpgrades();
                case TARGETS -> initTargets(program);
                case SETTINGS -> initSettings(program);
            }
        }
        structure = currentStructure();
        syncedConfig = null;
        syncWidgets();
    }

    private void initUpgrades() {
        UpgradeType[] types = UpgradeType.values();
        for (int i = 0; i < types.length; i++) {
            UpgradeType type = types[i];
            int x = leftPos + EDITOR_X + (i / UPGRADE_ROWS) * CELL_WIDTH;
            int y = topPos + CONTENT_Y + (i % UPGRADE_ROWS) * CELL_HEIGHT + 3;
            Button remove = addRenderableWidget(Button.builder(Component.literal("x"), b -> send(EditProgramPayload.remove(menu.containerId, type)))
                    .bounds(x + 48, y, 12, 12)
                    .tooltip(Tooltip.create(Component.translatable(KEY + "upgrade.remove_extra")))
                    .build());
            Button minus = addRenderableWidget(Button.builder(Component.literal("-"), b -> changeUpgrade(type, -1))
                    .bounds(x + 62, y, 12, 12)
                    .build());
            Button plus = addRenderableWidget(Button.builder(Component.literal("+"), b -> changeUpgrade(type, 1))
                    .bounds(x + 76, y, 12, 12)
                    .build());
            if (mode() == ProgrammingMode.DIRECT) {
                remove.visible = false;
                minus.setTooltip(Tooltip.create(Component.translatable(KEY + "upgrade.remove")));
                plus.setTooltip(Tooltip.create(Component.translatable(KEY + "upgrade.install")));
            }
            upgradeRows.put(type, new UpgradeRow(type, remove, minus, plus));
        }
        if (mode() == ProgrammingMode.TEMPLATE) {
            copyButton = addRenderableWidget(Button.builder(Component.translatable(KEY + "copy_from_drone"),
                            b -> send(EditProgramPayload.copyFromDrone(menu.containerId)))
                    .bounds(leftPos + EDITOR_X + EDITOR_WIDTH - 84, topPos + FOOTER_Y, 84, 14)
                    .tooltip(Tooltip.create(Component.translatable(KEY + "copy_from_drone.tooltip")))
                    .build());
        }
    }

    private void changeUpgrade(UpgradeType type, int delta) {
        if (mode() == ProgrammingMode.DIRECT) {
            send(delta > 0 ? EditProgramPayload.install(menu.containerId, type) : EditProgramPayload.remove(menu.containerId, type));
        } else {
            DroneProgram template = menu.getTemplate();
            if (template != null) {
                send(EditProgramPayload.setCount(menu.containerId, type, template.upgradeCount(type) + delta));
            }
        }
    }

    private void initTargets(DroneProgram program) {
        int rows = Math.max(program.allowedTargetCount(), program.config().targets().size());
        targetScroll = Math.clamp(targetScroll, 0, Math.max(0, rows - VISIBLE_TARGET_ROWS));
        for (int visible = 0; visible < VISIBLE_TARGET_ROWS && targetScroll + visible < rows; visible++) {
            int index = targetScroll + visible;
            int x = leftPos + EDITOR_X;
            int y = topPos + TARGET_ROWS_Y + visible * TARGET_ROW_HEIGHT;
            Button kind = addRenderableWidget(Button.builder(Component.empty(), b -> cycleKind(index))
                    .bounds(x, y, 44, 18)
                    .tooltip(Tooltip.create(Component.translatable(KEY + "target.kind.tooltip")))
                    .build());
            CommitBox box = addRenderableWidget(new CommitBox(font, x + 46, y, 116, 18, Component.translatable(KEY + "target.value"),
                    self -> commitTarget(index)));
            box.setMaxLength(64);
            addRenderableWidget(Button.builder(Component.literal("x"), b -> removeTarget(index))
                    .bounds(x + 164, y + 2, 14, 14)
                    .tooltip(Tooltip.create(Component.translatable(KEY + "target.remove")))
                    .build());
            kindButtons.put(index, kind);
            targetBoxes.put(index, box);
        }
    }

    private TargetEntry.Kind rowKind(int index) {
        return rowKinds.getOrDefault(index, TargetEntry.Kind.ENTITY_TYPE);
    }

    private void cycleKind(int index) {
        TargetEntry.Kind[] kinds = TargetEntry.Kind.values();
        rowKinds.put(index, kinds[(rowKind(index).ordinal() + 1) % kinds.length]);
        updateKindButtons();
        CommitBox box = targetBoxes.get(index);
        if (box != null && !box.getValue().isBlank()) {
            commitTarget(index);
        }
    }

    private void commitTarget(int index) {
        DroneProgram program = program();
        CommitBox box = targetBoxes.get(index);
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
            CommitBox box = targetBoxes.get(index);
            if (box != null) {
                box.setValue("");
                box.setInvalid(false);
            }
        }
    }

    private void initSettings(DroneProgram program) {
        int x = leftPos + EDITOR_X;
        int y = topPos + CONTENT_Y;
        int maxFollow = ProgramRules.maxFollowDistance();
        addRenderableWidget(Button.builder(Component.literal("-"), b -> stepFollowDistance(-1))
                .bounds(x + SETTINGS_CONTROL_X + 22, y + 3, 12, 12).build());
        followBox = addRenderableWidget(new CommitBox(font, x + SETTINGS_CONTROL_X + 36, y, 30, 18,
                Component.translatable(KEY + "follow_distance"), self -> commitFollowDistance()));
        followBox.setMaxLength(3);
        followBox.setFilter(text -> text.chars().allMatch(Character::isDigit));
        followBox.setTooltip(Tooltip.create(Component.translatable(KEY + "follow_distance.tooltip", 1, maxFollow)));
        addRenderableWidget(Button.builder(Component.literal("+"), b -> stepFollowDistance(1))
                .bounds(x + SETTINGS_CONTROL_X + 68, y + 3, 12, 12).build());

        y += SETTINGS_ROW_HEIGHT;
        labelBox = addRenderableWidget(new CommitBox(font, x + SETTINGS_CONTROL_X, y, EDITOR_WIDTH - SETTINGS_CONTROL_X, 18,
                Component.translatable(KEY + "label"), self -> commitLabel()));
        labelBox.setMaxLength(ProgramRules.MAX_LABEL_LENGTH);
        labelBox.setHint(Component.translatable(KEY + "label.hint").withStyle(ChatFormatting.DARK_GRAY));

        y += SETTINGS_ROW_HEIGHT;
        colorButton = addRenderableWidget(Button.builder(Component.empty(), b -> cycleColor(1))
                .bounds(x + SETTINGS_CONTROL_X + 16, y, EDITOR_WIDTH - SETTINGS_CONTROL_X - 16, 18)
                .tooltip(Tooltip.create(Component.translatable(KEY + "color.tooltip")))
                .build());

        if (program.upgradeCount(UpgradeType.PATROL) > 0) {
            y += SETTINGS_ROW_HEIGHT;
            int boxWidth = (EDITOR_WIDTH - SETTINGS_CONTROL_X - 4) / 3;
            centerX = addRenderableWidget(coordinateBox(x + SETTINGS_CONTROL_X, y, boxWidth, "x"));
            centerY = addRenderableWidget(coordinateBox(x + SETTINGS_CONTROL_X + boxWidth + 2, y, boxWidth, "y"));
            centerZ = addRenderableWidget(coordinateBox(x + SETTINGS_CONTROL_X + (boxWidth + 2) * 2, y, boxWidth, "z"));

            y += SETTINGS_ROW_HEIGHT;
            int max = DroneStats.maxPatrolRadius(program.upgradeCount(UpgradeType.PATROL));
            radiusBox = addRenderableWidget(new CommitBox(font, x + SETTINGS_CONTROL_X, y, 36, 18,
                    Component.translatable(KEY + "patrol_radius"), self -> commitRadius()));
            radiusBox.setMaxLength(6);
            radiusBox.setFilter(text -> text.chars().allMatch(Character::isDigit));
            radiusBox.setHint(Component.literal(String.valueOf(max)).withStyle(ChatFormatting.DARK_GRAY));
            radiusBox.setTooltip(Tooltip.create(Component.translatable(KEY + "patrol_radius.tooltip", max)));
            addRenderableWidget(Button.builder(Component.translatable(KEY + "patrol_center.here"), b -> {
                        BlockPos above = menu.getPos().above();
                        setCenter(Optional.of(above));
                    })
                    .bounds(x + SETTINGS_CONTROL_X + 40, y, 38, 18)
                    .tooltip(Tooltip.create(Component.translatable(KEY + "patrol_center.here.tooltip")))
                    .build());
            addRenderableWidget(Button.builder(Component.translatable(KEY + "patrol_center.clear"), b -> setCenter(Optional.empty()))
                    .bounds(x + SETTINGS_CONTROL_X + 80, y, 40, 18)
                    .tooltip(Tooltip.create(Component.translatable(KEY + "patrol_center.clear.tooltip")))
                    .build());
        }
    }

    private CommitBox coordinateBox(int x, int y, int width, String axis) {
        CommitBox box = new CommitBox(font, x, y, width, 18, Component.literal(axis), self -> commitCenter());
        box.setMaxLength(9);
        box.setFilter(text -> text.isEmpty() || text.matches("-?\\d*"));
        box.setHint(Component.literal(axis.toUpperCase()).withStyle(ChatFormatting.DARK_GRAY));
        return box;
    }

    private void stepFollowDistance(int delta) {
        DroneProgram program = program();
        if (program != null) {
            int distance = Math.clamp(program.config().followDistance() + delta, 1, ProgramRules.maxFollowDistance());
            if (distance != program.config().followDistance()) {
                sendConfig(program.config().withFollowDistance(distance));
            }
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

    private void cycleColor(int delta) {
        DroneProgram program = program();
        if (program != null) {
            DyeColor[] colors = DyeColor.values();
            DyeColor next = colors[Math.floorMod(program.config().color().ordinal() + delta, colors.length)];
            sendConfig(program.config().withColor(next));
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
        // Force the boxes to show the new value even if they are focused.
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

    private void reject(CommitBox box, Component message) {
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
        DroneProgram program = program();
        DroneData drone = menu.getDrone();
        boolean direct = mode() == ProgrammingMode.DIRECT;
        UpgradeType installing = menu.getInstalling();
        for (UpgradeRow row : upgradeRows.values()) {
            UpgradeType type = row.type();
            if (program == null) {
                continue;
            }
            if (direct) {
                row.minus().active = program.upgradeCount(type) > 0;
                row.plus().active = installing == null && menu.inputCount(type) > 0 && ProgramRules.canAdd(program.upgrades(), type);
            } else {
                row.minus().active = program.upgradeCount(type) > 0;
                row.plus().active = ProgramRules.canAdd(program.upgrades(), type);
                row.remove().visible = drone != null && drone.upgradeCount(type) > program.upgradeCount(type);
            }
        }
        if (copyButton != null) {
            copyButton.active = drone != null && DroneStats.withinUpgradeLimits(drone.upgrades());
        }
        if (colorButton != null && program != null) {
            DyeColor color = program.config().color();
            colorButton.setMessage(Component.translatable("color.minecraft." + color.getName()));
        }
        updateKindButtons();
        if (program == null || program.config().equals(syncedConfig)) {
            return;
        }
        DroneConfig config = program.config();
        syncedConfig = config;
        List<TargetEntry> targets = config.targets();
        for (Map.Entry<Integer, CommitBox> entry : targetBoxes.entrySet()) {
            int index = entry.getKey();
            if (index < targets.size()) {
                rowKinds.put(index, targets.get(index).kind());
                setIfIdle(entry.getValue(), targets.get(index).displayString());
            } else {
                setIfIdle(entry.getValue(), "");
            }
        }
        updateKindButtons();
        setIfIdle(followBox, String.valueOf(config.followDistance()));
        setIfIdle(labelBox, config.label());
        Optional<BlockPos> center = config.patrolCenter().map(GlobalPos::pos);
        setIfIdle(centerX, center.map(p -> String.valueOf(p.getX())).orElse(""));
        setIfIdle(centerY, center.map(p -> String.valueOf(p.getY())).orElse(""));
        setIfIdle(centerZ, center.map(p -> String.valueOf(p.getZ())).orElse(""));
        setIfIdle(radiusBox, config.patrolRadius().map(String::valueOf).orElse(""));
    }

    private void updateKindButtons() {
        kindButtons.forEach((index, button) -> button.setMessage(
                Component.translatable("commands.seekerdrones.config.target.kind." + rowKind(index).getSerializedName())));
    }

    /** Shows the synced value, unless the player is typing in the box. */
    private static void setIfIdle(@Nullable CommitBox box, String value) {
        if (box != null && !box.isFocused()) {
            box.showValue(value);
            box.setInvalid(false);
        }
    }

    // --- Input ---

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Typed keys go to the focused box, so e.g. "E" doesn't close the screen.
        if (keyCode != GLFW.GLFW_KEY_ESCAPE && getFocused() instanceof EditBox box && box.canConsumeInput()) {
            box.keyPressed(keyCode, scanCode, modifiers);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Clicking anywhere else commits the focused box.
        if (getFocused() instanceof CommitBox box && !box.isMouseOver(mouseX, mouseY)) {
            setFocused(null);
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && colorButton != null && colorButton.active && colorButton.isMouseOver(mouseX, mouseY)) {
            colorButton.playDownSound(minecraft.getSoundManager());
            cycleColor(-1);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (tab == Tab.TARGETS && isHovering(EDITOR_X, CONTENT_Y, EDITOR_WIDTH, CONTENT_BOTTOM - CONTENT_Y, mouseX, mouseY)) {
            DroneProgram program = program();
            int rows = program != null ? Math.max(program.allowedTargetCount(), program.config().targets().size()) : 0;
            int next = Math.clamp(targetScroll - (int) Math.signum(scrollY), 0, Math.max(0, rows - VISIBLE_TARGET_ROWS));
            if (next != targetScroll) {
                targetScroll = next;
                rebuildWidgets();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /** Commits the box being typed in before the menu closes. */
    @Override
    public void onClose() {
        if (getFocused() instanceof CommitBox) {
            setFocused(null);
        }
        super.onClose();
    }

    // --- Rendering ---

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
        if (isHovering(ENERGY_X, ENERGY_Y, ENERGY_WIDTH, ENERGY_HEIGHT, mouseX, mouseY)) {
            graphics.renderTooltip(font, Component.translatable("screen.seekerdrones.drone_status.energy_value",
                    menu.getEnergy(), menu.getEnergyCapacity()), mouseX, mouseY);
        } else if (isHovering(PROGRESS_X, PROGRESS_Y - 1, PROGRESS_WIDTH, PROGRESS_HEIGHT + 2, mouseX, mouseY) && menu.getInstalling() != null) {
            graphics.renderTooltip(font, installingText(), mouseX, mouseY);
        } else if (isHovering(STATUS_X, STATUS_Y, statusWidth(), STATUS_LINES * STATUS_LINE_HEIGHT, mouseX, mouseY)) {
            renderStatusTooltip(graphics, mouseX, mouseY);
        } else if (tab == Tab.UPGRADES) {
            renderUpgradeTooltip(graphics, mouseX, mouseY);
        } else if (tab == Tab.TARGETS) {
            renderTargetTooltip(graphics, mouseX, mouseY);
        }
    }

    private void renderUpgradeTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        DroneProgram program = program();
        if (program == null) {
            return;
        }
        UpgradeType[] types = UpgradeType.values();
        for (int i = 0; i < types.length; i++) {
            int x = EDITOR_X + (i / UPGRADE_ROWS) * CELL_WIDTH;
            int y = CONTENT_Y + (i % UPGRADE_ROWS) * CELL_HEIGHT;
            if (!isHovering(x, y, 46, CELL_HEIGHT, mouseX, mouseY)) {
                continue;
            }
            UpgradeType type = types[i];
            DroneData drone = menu.getDrone();
            int installed = drone != null ? drone.upgradeCount(type) : 0;
            List<Component> lines = new ArrayList<>();
            lines.add(Component.translatable(type.getTranslationKey()));
            lines.add(Component.translatable(type.getTranslationKey() + ".description").withStyle(ChatFormatting.GRAY));
            if (drone != null) {
                lines.add(Component.translatable(KEY + "upgrade.installed", installed, type.maxCount()).withStyle(ChatFormatting.GRAY));
            }
            if (mode() == ProgrammingMode.TEMPLATE) {
                lines.add(Component.translatable(KEY + "upgrade.programmed", program.upgradeCount(type)).withStyle(ChatFormatting.GRAY));
            }
            lines.add(Component.translatable(KEY + "upgrade.in_input", menu.inputCount(type)).withStyle(ChatFormatting.GRAY));
            if (installed < type.maxCount()) {
                lines.add(Component.translatable(KEY + "upgrade.cost", ProgramRules.installCost(type, installed + 1)).withStyle(ChatFormatting.GRAY));
            }
            graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
            return;
        }
    }

    private void renderTargetTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        for (int index : targetBoxes.keySet()) {
            int y = TARGET_ROWS_Y + (index - targetScroll) * TARGET_ROW_HEIGHT;
            Component ignored = ignoredReason(index);
            if (ignored != null && isHovering(EDITOR_X + 46 - 8, y, 7, 18, mouseX, mouseY)) {
                graphics.renderTooltip(font, ignored, mouseX, mouseY);
            }
        }
    }

    /** Why a stored target entry is ignored at runtime (section 2.7), or null. */
    @Nullable
    private Component ignoredReason(int index) {
        DroneProgram program = program();
        if (program == null || index >= program.config().targets().size()) {
            return null;
        }
        if (index >= program.allowedTargetCount()) {
            return Component.translatable(KEY + "target.ignored.no_slot");
        }
        TargetEntry entry = program.config().targets().get(index);
        if (entry.kind() == TargetEntry.Kind.PLAYER_NAME && program.upgradeCount(UpgradeType.PLAYER_SEEK) <= 0) {
            return Component.translatable(KEY + "target.ignored.player_seek");
        }
        if (TargetBlacklist.blocks(entry)) {
            return Component.translatable(KEY + "target.ignored.blacklisted");
        }
        return null;
    }

    private Component installingText() {
        UpgradeType installing = menu.getInstalling();
        if (installing == null) {
            return Component.empty();
        }
        int percent = menu.getTime() > 0 ? menu.getProgress() * 100 / menu.getTime() : 0;
        return Component.translatable(KEY + "status.installing", Component.translatable(installing.getTranslationKey()), percent,
                menu.getStepCost());
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        graphics.fill(x - 1, y - 1, x + imageWidth + 1, y + imageHeight + 1, PANEL_BORDER_COLOR);
        graphics.fill(x, y, x + imageWidth, y + imageHeight, PANEL_COLOR);
        graphics.fill(x + EDITOR_X - 2, y + CONTENT_Y - 2, x + EDITOR_X + EDITOR_WIDTH + 2, y + CONTENT_BOTTOM, EDITOR_COLOR);
        for (int i = 0; i < ProgrammingStationMenu.SLOT_TOTAL; i++) {
            var slot = menu.slots.get(i);
            drawSlot(graphics, x + slot.x, y + slot.y);
        }

        graphics.fill(x + ENERGY_X - 1, y + ENERGY_Y - 1, x + ENERGY_X + ENERGY_WIDTH + 1, y + ENERGY_Y + ENERGY_HEIGHT + 1, SLOT_BORDER_COLOR);
        graphics.fill(x + ENERGY_X, y + ENERGY_Y, x + ENERGY_X + ENERGY_WIDTH, y + ENERGY_Y + ENERGY_HEIGHT, BAR_BACKGROUND_COLOR);
        int energyHeight = scaled(menu.getEnergy(), menu.getEnergyCapacity(), ENERGY_HEIGHT);
        graphics.fill(x + ENERGY_X, y + ENERGY_Y + ENERGY_HEIGHT - energyHeight, x + ENERGY_X + ENERGY_WIDTH, y + ENERGY_Y + ENERGY_HEIGHT,
                ENERGY_BAR_COLOR);

        graphics.fill(x + PROGRESS_X, y + PROGRESS_Y, x + PROGRESS_X + PROGRESS_WIDTH, y + PROGRESS_Y + PROGRESS_HEIGHT, BAR_BACKGROUND_COLOR);
        if (menu.getInstalling() != null) {
            int progressWidth = scaled(menu.getProgress(), menu.getTime(), PROGRESS_WIDTH);
            graphics.fill(x + PROGRESS_X, y + PROGRESS_Y, x + PROGRESS_X + progressWidth, y + PROGRESS_Y + PROGRESS_HEIGHT, PROGRESS_COLOR);
        }

        if (colorButton != null) {
            DroneProgram program = program();
            if (program != null) {
                int swatchX = colorButton.getX() - 16;
                int swatchY = colorButton.getY() + 2;
                graphics.fill(swatchX, swatchY, swatchX + 14, swatchY + 14, SLOT_BORDER_COLOR);
                graphics.fill(swatchX + 1, swatchY + 1, swatchX + 13, swatchY + 13, 0xFF000000 | program.config().color().getTextureDiffuseColor());
            }
        }
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, titleLabelX, titleLabelY, LABEL_COLOR, false);
        graphics.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, LABEL_COLOR, false);
        DroneProgram program = program();
        if (program == null) {
            Component empty = Component.translatable(mode() == ProgrammingMode.DIRECT ? KEY + "no_drone" : KEY + "loading");
            graphics.drawWordWrap(font, empty, EDITOR_X + 2, CONTENT_Y + 4, EDITOR_WIDTH - 4, MUTED_COLOR);
        } else {
            switch (tab) {
                case UPGRADES -> renderUpgradeLabels(graphics, program);
                case TARGETS -> renderTargetLabels(graphics, program);
                case SETTINGS -> renderSettingsLabels(graphics, program);
            }
        }
        Status status = status();
        List<String> lines = statusLines(status.text());
        for (int i = 0; i < lines.size(); i++) {
            graphics.drawString(font, lines.get(i), STATUS_X, STATUS_Y + i * STATUS_LINE_HEIGHT, status.color(), false);
        }
    }

    private int statusWidth() {
        return imageWidth - 2 * STATUS_X;
    }

    /**
     * The status wrapped into at most {@link #STATUS_LINES} lines. Text that still doesn't fit ends the last line
     * with an ellipsis, and the full text shows as a tooltip (see {@link #renderStatusTooltip}).
     */
    private List<String> statusLines(Component text) {
        int width = statusWidth();
        List<String> lines = new ArrayList<>();
        font.getSplitter().splitLines(text, width, text.getStyle()).forEach(line -> lines.add(line.getString()));
        if (lines.size() <= STATUS_LINES) {
            return lines;
        }
        List<String> shown = new ArrayList<>(lines.subList(0, STATUS_LINES));
        String last = font.plainSubstrByWidth(shown.getLast(), width - font.width(ELLIPSIS)).stripTrailing();
        shown.set(STATUS_LINES - 1, last + ELLIPSIS);
        return shown;
    }

    /** The full status text, only when it didn't fit in the status area. */
    private void renderStatusTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        Component text = status().text();
        if (font.getSplitter().splitLines(text, statusWidth(), text.getStyle()).size() > STATUS_LINES) {
            graphics.renderTooltip(font, font.split(text, 200), mouseX, mouseY);
        }
    }

    private void renderUpgradeLabels(GuiGraphics graphics, DroneProgram program) {
        DroneData drone = menu.getDrone();
        UpgradeType installing = menu.getInstalling();
        UpgradeType[] types = UpgradeType.values();
        for (int i = 0; i < types.length; i++) {
            UpgradeType type = types[i];
            int x = EDITOR_X + (i / UPGRADE_ROWS) * CELL_WIDTH;
            int y = CONTENT_Y + (i % UPGRADE_ROWS) * CELL_HEIGHT;
            graphics.renderItem(new ItemStack(ModItems.upgrade(type).get()), x, y + 1);
            String text;
            int color;
            if (mode() == ProgrammingMode.DIRECT) {
                text = program.upgradeCount(type) + "/" + type.maxCount();
                color = LABEL_COLOR;
            } else if (drone == null) {
                text = String.valueOf(program.upgradeCount(type));
                color = LABEL_COLOR;
            } else {
                int installed = drone.upgradeCount(type);
                int wanted = program.upgradeCount(type);
                text = installed + "/" + wanted;
                color = installed == wanted ? (wanted > 0 ? OK_COLOR : MUTED_COLOR) : installed < wanted ? WARNING_COLOR : ERROR_COLOR;
            }
            if (type == installing) {
                color = PROGRESS_COLOR;
            }
            graphics.drawString(font, text, x + 18, y + 6, color, false);
        }
        String slots = Component.translatable(KEY + "slots", DroneStats.totalUpgrades(program.upgrades()),
                ServerConfig.get(ServerConfig.UPGRADES_TOTAL_SLOTS)).getString();
        graphics.drawString(font, slots, EDITOR_X + 2, FOOTER_Y + 3, LABEL_COLOR, false);
    }

    private void renderTargetLabels(GuiGraphics graphics, DroneProgram program) {
        int rows = Math.max(program.allowedTargetCount(), program.config().targets().size());
        Component header = Component.translatable(KEY + "target.header", program.config().targets().size(), program.allowedTargetCount());
        graphics.drawString(font, header, EDITOR_X + 2, CONTENT_Y + 1, LABEL_COLOR, false);
        if (rows > VISIBLE_TARGET_ROWS) {
            String range = (targetScroll + 1) + "-" + Math.min(rows, targetScroll + VISIBLE_TARGET_ROWS) + " / " + rows;
            graphics.drawString(font, range, EDITOR_X + EDITOR_WIDTH - font.width(range) - 2, CONTENT_Y + 1, MUTED_COLOR, false);
        }
        for (int index : targetBoxes.keySet()) {
            if (ignoredReason(index) != null) {
                int y = TARGET_ROWS_Y + (index - targetScroll) * TARGET_ROW_HEIGHT;
                graphics.drawString(font, "!", EDITOR_X + 46 - 6, y + 5, ERROR_COLOR, false);
            }
        }
    }

    private void renderSettingsLabels(GuiGraphics graphics, DroneProgram program) {
        int y = CONTENT_Y + 5;
        graphics.drawString(font, Component.translatable(KEY + "follow_distance"), EDITOR_X + 2, y, LABEL_COLOR, false);
        y += SETTINGS_ROW_HEIGHT;
        graphics.drawString(font, Component.translatable(KEY + "label"), EDITOR_X + 2, y, LABEL_COLOR, false);
        y += SETTINGS_ROW_HEIGHT;
        graphics.drawString(font, Component.translatable(KEY + "color"), EDITOR_X + 2, y, LABEL_COLOR, false);
        if (program.upgradeCount(UpgradeType.PATROL) > 0) {
            y += SETTINGS_ROW_HEIGHT;
            graphics.drawString(font, Component.translatable(KEY + "patrol_center"), EDITOR_X + 2, y, LABEL_COLOR, false);
            y += SETTINGS_ROW_HEIGHT;
            graphics.drawString(font, Component.translatable(KEY + "patrol_radius"), EDITOR_X + 2, y, LABEL_COLOR, false);
        }
    }

    private record Status(Component text, int color) {}

    /** The status under the editor: the most important problem or what the station is doing. */
    private Status status() {
        if (editError != null) {
            return new Status(editError, ERROR_COLOR);
        }
        DroneData drone = menu.getDrone();
        UpgradeType installing = menu.getInstalling();
        if (mode() == ProgrammingMode.DIRECT) {
            if (drone == null) {
                return new Status(Component.translatable(KEY + "status.insert_drone"), MUTED_COLOR);
            }
            if (installing != null) {
                return installingStatus();
            }
            return new Status(Component.translatable(KEY + "status.direct_hint"), MUTED_COLOR);
        }
        DroneProgram template = menu.getTemplate();
        if (template == null) {
            return new Status(Component.empty(), MUTED_COLOR);
        }
        if (!DroneStats.withinUpgradeLimits(template.upgrades())) {
            return new Status(Component.translatable(KEY + "status.template_invalid"), ERROR_COLOR);
        }
        if (drone == null) {
            return new Status(Component.translatable(KEY + "status.insert_drone_template"), MUTED_COLOR);
        }
        if (!menu.isAccepted()) {
            return new Status(Component.translatable(KEY + "status.reinsert_drone"), WARNING_COLOR);
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
            return new Status(Component.translatable(KEY + "status.extra_upgrades", names(extra)), ERROR_COLOR);
        }
        if (installing != null) {
            return installingStatus();
        }
        if (!missing.isEmpty()) {
            return new Status(Component.translatable(KEY + "status.missing_upgrades", names(missing)), WARNING_COLOR);
        }
        if (template.matches(drone)) {
            return new Status(Component.translatable(KEY + "status.complete"), OK_COLOR);
        }
        return new Status(Component.translatable(KEY + "status.working"), MUTED_COLOR);
    }

    private Status installingStatus() {
        int perTick = menu.getTime() > 0 ? menu.getStepCost() / menu.getTime() : 0;
        if (menu.getEnergy() < perTick) {
            return new Status(Component.translatable(KEY + "status.no_power", Component.translatable(menu.getInstalling().getTranslationKey())),
                    WARNING_COLOR);
        }
        return new Status(installingText(), OK_COLOR);
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

    private static void drawSlot(GuiGraphics graphics, int x, int y) {
        graphics.fill(x - 1, y - 1, x + 17, y + 17, SLOT_BORDER_COLOR);
        graphics.fill(x, y, x + 16, y + 16, SLOT_COLOR);
    }

    private static int scaled(int value, int max, int size) {
        return max <= 0 ? 0 : (int) Math.min(size, (long) value * size / max);
    }

    /** An edit box that commits its value on Enter and when it loses focus. */
    private static class CommitBox extends EditBox {
        private final Consumer<CommitBox> onCommit;

        CommitBox(Font font, int x, int y, int width, int height, Component message, Consumer<CommitBox> onCommit) {
            super(font, x, y, width, height, message);
            this.onCommit = onCommit;
        }

        void showValue(String value) {
            if (!getValue().equals(value)) {
                setValue(value);
            }
        }

        void setInvalid(boolean invalid) {
            setTextColor(invalid ? BOX_ERROR_COLOR : BOX_TEXT_COLOR);
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
