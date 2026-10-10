package com.elpinho.seekerdrones.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.elpinho.seekerdrones.client.gui.DronePreview;
import com.elpinho.seekerdrones.client.gui.EditorTab;
import com.elpinho.seekerdrones.client.gui.EntityPreview;
import com.elpinho.seekerdrones.client.gui.FieldBox;
import com.elpinho.seekerdrones.client.gui.Gauges;
import com.elpinho.seekerdrones.client.gui.Kit;
import com.elpinho.seekerdrones.client.gui.KitButton;
import com.elpinho.seekerdrones.client.gui.KitSlider;
import com.elpinho.seekerdrones.client.gui.KitWidget;
import com.elpinho.seekerdrones.client.gui.PanelScreen;
import com.elpinho.seekerdrones.client.gui.SideTab;
import com.elpinho.seekerdrones.client.gui.Swatch;
import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.drone.DroneConfig;
import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.DroneItem;
import com.elpinho.seekerdrones.drone.DroneState;
import com.elpinho.seekerdrones.drone.DroneStats;
import com.elpinho.seekerdrones.drone.TargetEntry;
import com.elpinho.seekerdrones.drone.TargetMatcher;
import com.elpinho.seekerdrones.drone.UpgradeType;
import com.elpinho.seekerdrones.energy.EnergyFormat;
import com.elpinho.seekerdrones.network.DroneStatusPayload;
import com.elpinho.seekerdrones.network.EditRemoteSettingsPayload;
import com.elpinho.seekerdrones.network.RemoteCommandPayload;
import com.elpinho.seekerdrones.network.RemoteStatusPayload;
import com.elpinho.seekerdrones.network.RequestRemoteStatusPayload;
import com.elpinho.seekerdrones.programming.ProgramRules;
import com.elpinho.seekerdrones.registry.ModDataComponents;
import com.elpinho.seekerdrones.registry.ModItems;
import com.elpinho.seekerdrones.remote.DroneRemoteItem;
import com.elpinho.seekerdrones.remote.RemoteCommand;
import com.elpinho.seekerdrones.remote.RemoteLink;
import com.elpinho.seekerdrones.remote.RemoteReach;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.util.StringUtil;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The Drone Remote's screen (DESIGN.md section 2.10): the drone status screen's header, radar, energy and health,
 * laid out so a dark display with Upgrades, Targets, Behavior and Identity tabs and a command bar fit in one frame.
 * Upgrades and Targets are read-only. Behavior and Identity edit the settings the remote may change, and the command
 * bar sends Recall, Hold or Resume, Return to charge and Patrol center here. Everything is checked again by the
 * server. The screen asks for fresh values about once a second, and disables its controls while the drone can't be
 * reached.
 */
public class DroneRemoteScreen extends PanelScreen {
    private static final String KEY = "screen.seekerdrones.drone_remote.";
    /** The Programming Station's keys for the settings the Behavior and Identity tabs share with it. */
    private static final String STATION_KEY = "screen.seekerdrones.programming_station.";

    private static final int REFRESH_INTERVAL_TICKS = 20;
    private static final int WIDTH = 228;
    private static final int HEIGHT = 176;

    private static final int ACCENT = 0xFF47B5EF;

    private static final int HEADER_Y = 8;
    private static final int CHIP_HEIGHT = 10;

    private static final int RADAR_X = 8;
    private static final int RADAR_Y = 21;
    private static final int RADAR_SIZE = 74;
    private static final int RADAR_RANGE = 30;
    private static final float SWEEP_DEGREES_PER_SECOND = 111;
    private static final int RADAR_LINE_Y = 98;
    private static final int SIGHT_Y = 108;
    private static final int FOLLOW_Y = 118;

    private static final int COLUMN_X = 90;
    private static final int COLUMN_WIDTH = WIDTH - 8 - COLUMN_X;
    private static final int ENERGY_ROW_Y = 21;
    private static final int ENERGY_BAR_Y = 31;
    private static final int HEALTH_ROW_Y = 40;
    private static final int HEALTH_BAR_Y = 49;

    // The tabs sit on the display's top edge, like the Programming Station's editor.
    private static final int TAB_Y = 58;
    private static final int TAB_X = COLUMN_X + 2;
    private static final int DISPLAY_Y = 70;
    private static final int DISPLAY_HEIGHT = 76;
    /** Where the display's contents start, from its left edge. */
    private static final int PAD = 4;
    private static final int CONTENT_WIDTH = COLUMN_WIDTH - 2 * PAD;

    private static final int CELL_SIZE = 18;
    private static final int VISIBLE_CELLS = CONTENT_WIDTH / CELL_SIZE;
    private static final int CARD_WIDTH = 39;
    private static final int CARD_HEIGHT = 42;
    private static final int CARD_PITCH = 41;
    private static final int CARD_PREVIEW_HEIGHT = 31;
    private static final float CARD_PREVIEW_SIZE = 0.75F * 1.25F;
    private static final int VISIBLE_CARDS = 3;

    private static final int ROW_PITCH = 22;
    private static final int SLIDER_HEIGHT = 6;
    private static final int VALUE_WIDTH = 30;
    private static final int FIELD_HEIGHT = 12;
    private static final int SLIDER_WIDTH = CONTENT_WIDTH - VALUE_WIDTH - 4;
    private static final double MIN_SPEED_SECONDS = 0.2;
    private static final int MIN_SPEED_HUNDREDTHS = 20;

    private static final int COMMAND_Y = 153;
    private static final int COMMAND_WIDTH = 50;
    private static final int COMMAND_HEIGHT = 18;
    private static final int COMMAND_GAP = 3;
    private static final int SEPARATOR_Y = 149;

    private static final double SPEED_SMOOTHING = 0.15;

    private static final ResourceLocation RADAR = Kit.sprite("drone_status/radar");
    private static final ResourceLocation SWEEP = Kit.sprite("drone_status/radar_sweep");
    private static final ResourceLocation ICON_UPGRADES = Kit.sprite("icon/upgrades");
    private static final ResourceLocation ICON_TARGET = Kit.sprite("icon/target");
    private static final ResourceLocation ICON_NAME_TAG = Kit.sprite("icon/name_tag");
    private static final ResourceLocation ICON_SETTINGS = Kit.sprite("icon/settings");
    private static final ResourceLocation ICON_RECALL = Kit.sprite("icon/recall");
    private static final ResourceLocation ICON_HOLD = Kit.sprite("icon/hold");
    private static final ResourceLocation ICON_RESUME = Kit.sprite("icon/resume");
    private static final ResourceLocation ICON_PIN = Kit.sprite("icon/pin");

    private enum Tab {
        UPGRADES, TARGETS, BEHAVIOR, IDENTITY
    }

    /** What decides which widgets exist. The widgets are rebuilt when it changes. */
    private record Structure(Tab tab, boolean patrol, boolean controls) {}

    private final String droneId;
    private final RemoteLink link;
    private RemoteStatusPayload status;
    /** The latest values of the drone, or what is known from the link while none has arrived. */
    private DroneStatusPayload shown;
    /** The settings shown and edited. Replaced by each status, and by an edit as soon as it is sent. */
    private DroneConfig config;
    @Nullable
    private DroneConfig syncedConfig;
    private ItemStack droneStack = ItemStack.EMPTY;
    private List<UpgradeType> upgrades = List.of();
    private List<TargetEntry> targets = List.of();
    private Tab tab = Tab.UPGRADES;
    private int upgradeScroll;
    private int targetScroll;
    private int ticksOpen;
    @Nullable
    private Structure structure;
    private double averageSpeed;
    private final EntityPreview targetPreviews = new EntityPreview();

    private final List<KitSlider> sliders = new ArrayList<>();
    @Nullable
    private FieldBox followBox;
    @Nullable
    private FieldBox radiusBox;
    @Nullable
    private FieldBox speedBox;
    @Nullable
    private FieldBox labelBox;

    public DroneRemoteScreen(RemoteStatusPayload status) {
        super(Component.translatable("screen.seekerdrones.drone_remote"), WIDTH, HEIGHT);
        this.droneId = status.link().droneId();
        this.link = status.link();
        sideTabs.add(SideTab.energyUnit());
        update(status);
    }

    public String getDroneId() {
        return droneId;
    }

    public void update(RemoteStatusPayload status) {
        this.status = status;
        if (status.status().isPresent()) {
            shown = status.status().get();
        } else if (shown == null) {
            DroneConfig placeholderConfig = DroneConfig.createDefault().withLabel(link.label()).withColor(link.color());
            shown = DroneStatusPayload.ofItem(DroneData.createNew().withDroneId(link.droneId()).withConfig(placeholderConfig).withEnergy(0).withHealth(0));
        }
        DroneData data = shown.data();
        config = data.config();
        droneStack = new ItemStack(ModItems.DRONE.get());
        droneStack.set(ModDataComponents.DRONE_DATA, data);
        List<UpgradeType> installed = new ArrayList<>();
        for (UpgradeType type : UpgradeType.values()) {
            if (data.upgradeCount(type) > 0) {
                installed.add(type);
            }
        }
        upgrades = installed;
        targets = TargetMatcher.targetableEntries(data);
        upgradeScroll = Math.min(upgradeScroll, maxUpgradeScroll());
        targetScroll = Math.min(targetScroll, maxTargetScroll());
        syncedConfig = null;
    }

    // --- State ---

    /** Whether the drone can be controlled now. */
    private boolean controls() {
        return status.reach() == RemoteReach.OK;
    }

    private boolean patrol() {
        return DroneStats.isPatrolling(shown.data());
    }

    private DroneState state() {
        return shown.state();
    }

    /** Held or recalled: the hold button resumes instead (section 2.10). */
    private boolean passive() {
        return state() == DroneState.HOLDING || state() == DroneState.RECALLED;
    }

    private Structure currentStructure() {
        return new Structure(tab, patrol(), controls());
    }

    private int maxUpgradeScroll() {
        return Math.max(0, upgrades.size() - VISIBLE_CELLS);
    }

    private int maxTargetScroll() {
        return Math.max(0, targets.size() - VISIBLE_CARDS);
    }

    private int patrolCount() {
        return shown.data().upgradeCount(UpgradeType.PATROL);
    }

    // --- Widgets ---

    @Override
    protected void init() {
        super.init();
        sliders.clear();
        followBox = radiusBox = speedBox = labelBox = null;
        structure = currentStructure();

        addRenderableWidget(new Gauges.Bar(leftPos + COLUMN_X, topPos + ENERGY_BAR_Y, COLUMN_WIDTH, 6, Gauges.Bar.Kind.ENERGY,
                () -> Kit.fraction(shown.data().energy(), shown.maxEnergy())))
                .tooltip(() -> List.of(
                        Component.translatable("screen.seekerdrones.energy"),
                        Component.literal(EnergyFormat.ratio(shown.data().energy(), shown.maxEnergy())).withStyle(ChatFormatting.GRAY),
                        energyRateTooltip()));
        addRenderableWidget(new Gauges.Bar(leftPos + COLUMN_X, topPos + HEALTH_BAR_Y, COLUMN_WIDTH, 6, Gauges.Bar.Kind.HEALTH,
                () -> Kit.fraction(shown.data().health(), shown.maxHealth())))
                .tooltip(() -> List.of(
                        Component.translatable("screen.seekerdrones.drone_status.health"),
                        healthText().copy().withStyle(ChatFormatting.GRAY)));
        addRenderableWidget(new KitWidget.Area(leftPos + RADAR_X, topPos + RADAR_Y, RADAR_SIZE, RADAR_SIZE)).tooltip(this::radarTooltip);
        addRenderableWidget(new KitWidget.Area(leftPos + RADAR_X, topPos + SIGHT_Y - 1, RADAR_SIZE, 8)).tooltip(() -> List.of(
                Component.translatable("screen.seekerdrones.drone_status.sight.tooltip", shown.sightRange())));
        addRenderableWidget(new KitWidget.Area(leftPos + RADAR_X, topPos + FOLLOW_Y - 1, RADAR_SIZE, 8)).tooltip(() -> List.of(
                Component.translatable("screen.seekerdrones.drone_status.follow.tooltip", config.followDistance())));

        for (Tab target : Tab.values()) {
            addRenderableWidget(editorTab(leftPos + TAB_X + target.ordinal() * EditorTab.PITCH, topPos + TAB_Y, target));
        }
        int x = leftPos + COLUMN_X;
        int y = topPos + DISPLAY_Y;
        switch (tab) {
            case UPGRADES -> initUpgrades(x, y);
            case TARGETS -> initTargets(x, y);
            case BEHAVIOR -> initBehavior(x, y);
            case IDENTITY -> initIdentity(x, y);
        }
        initCommands();
    }

    private EditorTab editorTab(int x, int y, Tab target) {
        SideTab.Drawer icon = (graphics, tx, ty, mouseX, mouseY) -> {
            switch (target) {
                case UPGRADES -> graphics.blitSprite(ICON_UPGRADES, tx + 7, ty + 3, 9, 8);
                case TARGETS -> graphics.blitSprite(ICON_TARGET, tx + 8, ty + 3, 9, 9);
                case BEHAVIOR -> graphics.blitSprite(ICON_SETTINGS, tx + 8, ty + 3, 9, 8);
                case IDENTITY -> graphics.blitSprite(ICON_NAME_TAG, tx + 8, ty + 4, 9, 6);
            }
        };
        return new EditorTab(x, y, () -> tab == target, icon,
                () -> List.of(Component.translatable(STATION_KEY + "tab." + target.name().toLowerCase(Locale.ROOT))), () -> ACCENT, () -> {
                    tab = target;
                    rebuildWidgets();
                });
    }

    private void initUpgrades(int x, int y) {
        for (int i = 0; i < VISIBLE_CELLS; i++) {
            int index = i;
            addRenderableWidget(new KitWidget.Area(x + PAD + i * CELL_SIZE, y + 5, CELL_SIZE, CELL_SIZE))
                    .tooltip(() -> upgradeTooltip(upgradeScroll + index));
        }
    }

    private void initTargets(int x, int y) {
        for (int i = 0; i < VISIBLE_CARDS; i++) {
            int index = i;
            addRenderableWidget(new KitWidget.Area(x + PAD + i * CARD_PITCH, y + 5, CARD_WIDTH, CARD_HEIGHT))
                    .tooltip(() -> targetTooltip(targetScroll + index));
        }
    }

    private void initBehavior(int x, int y) {
        boolean patrol = patrol();
        int row = 0;
        if (patrol) {
            int rowY = y + 4 + row++ * ROW_PITCH;
            sliders.add(addRenderableWidget(new KitSlider(x + PAD, rowY + 13, SLIDER_WIDTH, SLIDER_HEIGHT, () -> 1, this::maxRadius,
                    this::currentRadius, () -> ACCENT, value -> showDragged(radiusBox, value), this::setRadius)));
            sliders.getLast().tooltip(() -> List.of(Component.translatable(STATION_KEY + "patrol_radius"),
                    Component.translatable(STATION_KEY + "patrol_radius.slider", maxRadius()).withStyle(ChatFormatting.GRAY)));
            radiusBox = valueBox(x + PAD + CONTENT_WIDTH - VALUE_WIDTH, rowY + 10, Component.translatable(STATION_KEY + "patrol_radius"),
                    self -> commitRadius());
            radiusBox.setMaxLength(6);
            radiusBox.setFilter(text -> text.chars().allMatch(Character::isDigit));
            radiusBox.tooltipLines = () -> List.of(Component.translatable(STATION_KEY + "patrol_radius"),
                    Component.translatable(STATION_KEY + "patrol_radius.tooltip", maxRadius()).withStyle(ChatFormatting.GRAY));

            rowY = y + 4 + row++ * ROW_PITCH;
            KitSlider speedSlider = addRenderableWidget(new KitSlider(x + PAD, rowY + 13, SLIDER_WIDTH, SLIDER_HEIGHT, () -> MIN_SPEED_HUNDREDTHS,
                    this::maxSpeedHundredths, this::currentSpeedHundredths, () -> ACCENT, this::showDraggedSpeed, this::setSpeed));
            sliders.add(speedSlider);
            speedSlider.tooltip(() -> List.of(Component.translatable(STATION_KEY + "patrol_speed"),
                    Component.translatable(STATION_KEY + "patrol_speed.slider", formatSpeed(toSeconds(maxSpeed()))).withStyle(ChatFormatting.GRAY)));
            speedBox = valueBox(x + PAD + CONTENT_WIDTH - VALUE_WIDTH, rowY + 10, Component.translatable(STATION_KEY + "patrol_speed"),
                    self -> commitSpeed());
            speedBox.setMaxLength(5);
            speedBox.setFilter(text -> text.matches("\\d*\\.?\\d*"));
            speedBox.tooltipLines = () -> List.of(Component.translatable(STATION_KEY + "patrol_speed"),
                    Component.translatable(STATION_KEY + "patrol_speed.tooltip", formatSpeed(toSeconds(DroneStats.basePatrolSpeed())))
                            .withStyle(ChatFormatting.GRAY));
        }
        int rowY = y + 4 + row * ROW_PITCH;
        sliders.add(addRenderableWidget(new KitSlider(x + PAD, rowY + 13, SLIDER_WIDTH, SLIDER_HEIGHT, () -> 1, ProgramRules::maxFollowDistance,
                () -> config.followDistance(), () -> ACCENT, value -> showDragged(followBox, value), this::setFollowDistance)));
        sliders.getLast().tooltip(() -> List.of(Component.translatable(STATION_KEY + "follow_distance"),
                Component.translatable(STATION_KEY + "follow_distance.tooltip", 1, ProgramRules.maxFollowDistance()).withStyle(ChatFormatting.GRAY)));
        followBox = valueBox(x + PAD + CONTENT_WIDTH - VALUE_WIDTH, rowY + 10, Component.translatable(STATION_KEY + "follow_distance"),
                self -> commitFollowDistance());
        followBox.setMaxLength(3);
        followBox.setFilter(text -> text.chars().allMatch(Character::isDigit));
        followBox.tooltipLines = () -> List.of(Component.translatable(STATION_KEY + "follow_distance"),
                Component.translatable(STATION_KEY + "follow_distance.tooltip", 1, ProgramRules.maxFollowDistance()).withStyle(ChatFormatting.GRAY));
    }

    private void initIdentity(int x, int y) {
        labelBox = valueBox(x + PAD, y + 12, Component.translatable(STATION_KEY + "label"), self -> commitLabel());
        labelBox.setWidth(CONTENT_WIDTH);
        labelBox.setMaxLength(ProgramRules.MAX_LABEL_LENGTH);
        labelBox.setHint(Component.translatable(STATION_KEY + "label.hint").withColor(Kit.DISPLAY_TEXT_DIM & 0xFFFFFF));
        DyeColor[] colors = DyeColor.values();
        for (int i = 0; i < colors.length; i++) {
            Swatch swatch = addRenderableWidget(new Swatch(x + PAD + (i % 8) * Swatch.PITCH, y + 40 + (i / 8) * Swatch.PITCH, colors[i],
                    () -> controls() ? config.color() : null, () -> ACCENT, this::setColor));
            swatch.active = controls();
        }
    }

    /** A value box at the right end of a settings row, disabled while the drone can't be reached. */
    private FieldBox valueBox(int x, int y, Component message, Consumer<FieldBox> onCommit) {
        FieldBox box = addRenderableWidget(new FieldBox(font, x, y, VALUE_WIDTH, FIELD_HEIGHT, message, () -> ACCENT, onCommit));
        box.setEditable(controls());
        box.active = controls();
        return box;
    }

    private void initCommands() {
        int total = 4 * COMMAND_WIDTH + 3 * COMMAND_GAP;
        int x = leftPos + (WIDTH - total) / 2;
        int y = topPos + COMMAND_Y;
        addCommand(x, y, () -> ICON_RECALL, 9, 9, () -> Component.translatable(KEY + "recall"), () -> controls(),
                () -> send(RemoteCommand.RECALL), () -> List.of(Component.translatable(KEY + "recall"),
                        Component.translatable(KEY + "recall.tooltip").withStyle(ChatFormatting.GRAY)));
        addCommand(x + (COMMAND_WIDTH + COMMAND_GAP), y, () -> passive() ? ICON_RESUME : ICON_HOLD, 6, 7,
                () -> Component.translatable(KEY + (passive() ? "resume" : "hold")), () -> controls(),
                () -> send(passive() ? RemoteCommand.RESUME : RemoteCommand.HOLD),
                () -> passive()
                        ? List.of(Component.translatable(KEY + "resume"), Component.translatable(KEY + "resume.tooltip").withStyle(ChatFormatting.GRAY))
                        : List.of(Component.translatable(KEY + "hold"), Component.translatable(KEY + "hold.tooltip").withStyle(ChatFormatting.GRAY)));
        addCommand(x + 2 * (COMMAND_WIDTH + COMMAND_GAP), y, () -> Kit.ICON_ENERGY, 6, 8, () -> Component.translatable(KEY + "charge"),
                () -> controls() && !returning(), () -> send(RemoteCommand.CHARGE), () -> {
                    List<Component> lines = new ArrayList<>();
                    lines.add(Component.translatable(KEY + "charge"));
                    lines.add(Component.translatable(KEY + "charge.tooltip").withStyle(ChatFormatting.GRAY));
                    if (controls() && returning()) {
                        lines.add(Component.translatable(KEY + "charge.disabled").withStyle(ChatFormatting.RED));
                    }
                    return lines;
                });
        addCommand(x + 3 * (COMMAND_WIDTH + COMMAND_GAP), y, () -> ICON_PIN, 7, 7, () -> Component.translatable(KEY + "center"),
                () -> controls() && patrol(), () -> send(RemoteCommand.CENTER), () -> {
                    List<Component> lines = new ArrayList<>();
                    lines.add(Component.translatable(KEY + "center"));
                    lines.add(Component.translatable(KEY + "center.tooltip").withStyle(ChatFormatting.GRAY));
                    if (controls() && !patrol()) {
                        lines.add(Component.translatable(KEY + "center.disabled").withStyle(ChatFormatting.RED));
                    }
                    return lines;
                });
    }

    private boolean returning() {
        return state() == DroneState.RETURNING || state() == DroneState.CHARGING;
    }

    /** One button of the command bar: an icon and a small label, centered together. */
    private void addCommand(int x, int y, Supplier<ResourceLocation> icon, int iconWidth, int iconHeight,
            Supplier<Component> label, BooleanSupplier enabled, Runnable onPress,
            Supplier<List<Component>> tooltip) {
        SideTab.Drawer content = (graphics, bx, by, mouseX, mouseY) -> {
            String text = Kit.upper(label.get());
            int textWidth = Kit.smallWidth(font, text);
            int start = bx + (COMMAND_WIDTH - (iconWidth + 3 + textWidth)) / 2;
            graphics.blitSprite(icon.get(), start, by + (COMMAND_HEIGHT - iconHeight) / 2, iconWidth, iconHeight);
            Kit.smallText(graphics, font, text, start + iconWidth + 3, by + (COMMAND_HEIGHT - 6) / 2F, enabled.getAsBoolean() ? Kit.WHITE : Kit.CHIP_TEXT, true);
        };
        addRenderableWidget(new KitButton(x, y, COMMAND_WIDTH, COMMAND_HEIGHT, KitButton.Style.PANEL, content, onPress)
                .enabledWhen(enabled)).tooltip(tooltip);
    }

    private void send(RemoteCommand command) {
        PacketDistributor.sendToServer(new RemoteCommandPayload(droneId, command));
    }

    // --- Tooltips ---

    private Component healthText() {
        return Component.translatable("screen.seekerdrones.drone_status.health_value", DroneItem.formatHealth(shown.data().health()),
                DroneItem.formatHealth(shown.maxHealth()));
    }

    private List<Component> radarTooltip() {
        if (!patrol()) {
            return List.of(Component.translatable("screen.seekerdrones.drone_status.radar"),
                    Component.translatable("screen.seekerdrones.drone_status.radar.no_patrol").withStyle(ChatFormatting.GRAY));
        }
        return List.of(Component.translatable("screen.seekerdrones.drone_status.radar"),
                Component.translatable("screen.seekerdrones.drone_status.patrol_radius", Component.translatable(
                        "screen.seekerdrones.drone_status.patrol_radius_value", shown.patrolRadius(), shown.maxPatrolRadius()))
                        .withStyle(ChatFormatting.GRAY),
                Component.translatable("screen.seekerdrones.drone_status.patrol_center", patrolCenter()).withStyle(ChatFormatting.GRAY),
                Component.translatable("screen.seekerdrones.drone_status.radar.preview").withStyle(ChatFormatting.DARK_GRAY));
    }

    private Component patrolCenter() {
        return shown.patrolCenter()
                .<Component>map(center -> Component.literal(center.pos().getX() + ", " + center.pos().getY() + ", " + center.pos().getZ()))
                .orElse(Component.translatable("screen.seekerdrones.drone_status.not_set"));
    }

    private List<Component> upgradeTooltip(int index) {
        if (index >= upgrades.size()) {
            return List.of();
        }
        UpgradeType type = upgrades.get(index);
        return List.of(
                Component.translatable("screen.seekerdrones.drone_status.upgrade", Component.translatable(type.getTranslationKey()),
                        shown.data().upgradeCount(type)),
                Component.translatable(type.getTranslationKey() + ".description").withStyle(ChatFormatting.GRAY));
    }

    private List<Component> targetTooltip(int index) {
        if (index >= targets.size()) {
            return List.of();
        }
        TargetEntry target = targets.get(index);
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(target.displayString()));
        lines.add(Component.translatable("screen.seekerdrones.target.kind." + target.kind().getSerializedName()).withStyle(ChatFormatting.GRAY));
        if (target.kind() == TargetEntry.Kind.TAG) {
            lines.add(Component.translatable("screen.seekerdrones.target.tag_cycles").withStyle(ChatFormatting.GRAY));
        }
        return lines;
    }

    // --- Settings (section 2.10: the Programming Station's rules, checked again by the server) ---

    private void sendSettings(DroneConfig edited) {
        config = edited;
        // Force the fields to show the synced value again.
        syncedConfig = null;
        PacketDistributor.sendToServer(new EditRemoteSettingsPayload(droneId, edited.followDistance(), edited.patrolRadius(), edited.patrolSpeed(),
                edited.label(), edited.color()));
    }

    private void reject(FieldBox box, Component message) {
        box.setInvalid(true);
        if (minecraft != null && minecraft.player != null) {
            minecraft.player.displayClientMessage(message, true);
        }
    }

    private static void showDragged(@Nullable FieldBox box, int value) {
        if (box != null) {
            box.showValue(String.valueOf(value));
            box.setInvalid(false);
        }
    }

    private void setFollowDistance(int distance) {
        syncedConfig = null;
        if (distance != config.followDistance()) {
            sendSettings(config.withFollowDistance(distance));
        }
    }

    private void commitFollowDistance() {
        if (followBox == null) {
            return;
        }
        int max = ProgramRules.maxFollowDistance();
        int distance = parseInt(followBox.getValue(), -1);
        if (distance < 1 || distance > max) {
            reject(followBox, Component.translatable(STATION_KEY + "follow_distance.invalid", 1, max));
            return;
        }
        followBox.setInvalid(false);
        if (distance != config.followDistance()) {
            sendSettings(config.withFollowDistance(distance));
        }
    }

    private int maxRadius() {
        return Math.max(1, DroneStats.maxPatrolRadius(patrolCount()));
    }

    /** The radius the slider shows: the set one, capped at the max, or the max if none is set. */
    private int currentRadius() {
        int max = maxRadius();
        return config.patrolRadius().map(radius -> Math.min(radius, max)).orElse(max);
    }

    private void setRadius(int radius) {
        syncedConfig = null;
        if (!Optional.of(radius).equals(config.patrolRadius())) {
            sendSettings(config.withPatrolRadius(Optional.of(radius)));
        }
    }

    private void commitRadius() {
        if (radiusBox == null) {
            return;
        }
        String text = radiusBox.getValue().trim();
        Optional<Integer> radius = Optional.empty();
        if (!text.isEmpty()) {
            int value = parseInt(text, 0);
            if (value < 1) {
                reject(radiusBox, Component.translatable(STATION_KEY + "patrol_radius.invalid"));
                return;
            }
            radius = Optional.of(value);
        }
        radiusBox.setInvalid(false);
        if (!radius.equals(config.patrolRadius())) {
            sendSettings(config.withPatrolRadius(radius));
        }
    }

    /** The fastest the Patrol upgrades allow, in blocks/tick. */
    private double maxSpeed() {
        return DroneStats.maxPatrolSpeed(patrolCount());
    }

    private int maxSpeedHundredths() {
        return Math.max(MIN_SPEED_HUNDREDTHS, (int) Math.round(toSeconds(maxSpeed()) * 100));
    }

    /** The speed the slider shows: the set one, capped at the max, or the base speed if none is set. */
    private int currentSpeedHundredths() {
        double max = maxSpeed();
        double speed = config.patrolSpeed().map(s -> Math.min(s, max)).orElse(DroneStats.basePatrolSpeed());
        return (int) Math.round(toSeconds(speed) * 100);
    }

    private void setSpeed(int hundredths) {
        syncedConfig = null;
        Optional<Double> speed = Optional.of(toTicks(hundredths / 100.0));
        if (!speed.equals(config.patrolSpeed())) {
            sendSettings(config.withPatrolSpeed(speed));
        }
    }

    private void showDraggedSpeed(int hundredths) {
        if (speedBox != null) {
            speedBox.showValue(formatSpeed(hundredths / 100.0));
            speedBox.setInvalid(false);
        }
    }

    private void commitSpeed() {
        if (speedBox == null) {
            return;
        }
        String text = speedBox.getValue().trim();
        Optional<Double> speed = Optional.empty();
        if (!text.isEmpty()) {
            double seconds = parseDouble(text, -1);
            if (!(seconds >= MIN_SPEED_SECONDS)) {
                reject(speedBox, Component.translatable(STATION_KEY + "patrol_speed.invalid"));
                return;
            }
            speed = Optional.of(toTicks(seconds));
        }
        speedBox.setInvalid(false);
        if (!speed.equals(config.patrolSpeed())) {
            sendSettings(config.withPatrolSpeed(speed));
        }
    }

    private void commitLabel() {
        if (labelBox == null) {
            return;
        }
        String label = StringUtil.filterText(labelBox.getValue().trim());
        if (!label.equals(config.label())) {
            sendSettings(config.withLabel(label));
        }
    }

    private void setColor(DyeColor color) {
        sendSettings(config.withColor(color));
    }

    /** Shows a speed in blocks/second, to two decimals. */
    private static String formatSpeed(double blocksPerSecond) {
        return String.format(Locale.ROOT, "%.2f", blocksPerSecond);
    }

    private static double toSeconds(double blocksPerTick) {
        return blocksPerTick * DroneStats.TICKS_PER_SECOND;
    }

    private static double toTicks(double blocksPerSecond) {
        return blocksPerSecond / DroneStats.TICKS_PER_SECOND;
    }

    private static int parseInt(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double parseDouble(String text, double fallback) {
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // --- Ticking ---

    @Override
    public void tick() {
        super.tick();
        if (minecraft == null || minecraft.player == null || !holdsLink()) {
            onClose();
            return;
        }
        if (++ticksOpen % REFRESH_INTERVAL_TICKS == 0) {
            PacketDistributor.sendToServer(new RequestRemoteStatusPayload(droneId));
        }
        Entity entity = controls() && minecraft.level != null ? minecraft.level.getEntity(shown.entityId()) : null;
        if (entity != null) {
            double moved = Math.sqrt(entity.distanceToSqr(entity.xo, entity.yo, entity.zo));
            averageSpeed += (moved - averageSpeed) * SPEED_SMOOTHING;
        } else {
            averageSpeed = 0;
        }
        if (!currentStructure().equals(structure)) {
            rebuildWidgets();
            return;
        }
        syncWidgets();
    }

    /** Whether the player still holds a remote linked to this drone. */
    private boolean holdsLink() {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = minecraft.player.getItemInHand(hand);
            if (stack.getItem() instanceof DroneRemoteItem) {
                RemoteLink held = DroneRemoteItem.getLink(stack);
                if (held != null && held.droneId().equals(droneId)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Writes the synced settings into the fields, unless the player is typing in one or dragging a slider. */
    private void syncWidgets() {
        if (config.equals(syncedConfig) || sliders.stream().anyMatch(KitSlider::isDragging)) {
            return;
        }
        syncedConfig = config;
        setIfIdle(followBox, String.valueOf(config.followDistance()));
        setIfIdle(radiusBox, config.patrolRadius().map(String::valueOf).orElse(""));
        setIfIdle(speedBox, config.patrolSpeed().map(speed -> formatSpeed(toSeconds(speed))).orElse(""));
        setIfIdle(labelBox, config.label());
        if (radiusBox != null) {
            radiusBox.setHint(Component.literal(String.valueOf(maxRadius())).withColor(Kit.DISPLAY_TEXT_DIM & 0xFFFFFF));
        }
        if (speedBox != null) {
            speedBox.setHint(Component.literal(formatSpeed(toSeconds(DroneStats.basePatrolSpeed()))).withColor(Kit.DISPLAY_TEXT_DIM & 0xFFFFFF));
        }
    }

    private static void setIfIdle(@Nullable FieldBox box, String value) {
        if (box != null && !box.isFocused()) {
            box.showValue(value);
            box.setInvalid(false);
        }
    }

    // --- Input ---

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
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

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int step = scrollY > 0 ? -1 : scrollY < 0 ? 1 : 0;
        if (isOver(mouseX, mouseY, COLUMN_X, DISPLAY_Y, COLUMN_WIDTH, DISPLAY_HEIGHT)) {
            if (tab == Tab.UPGRADES) {
                upgradeScroll = Mth.clamp(upgradeScroll + step, 0, maxUpgradeScroll());
                return true;
            }
            if (tab == Tab.TARGETS) {
                targetScroll = Mth.clamp(targetScroll + step, 0, maxTargetScroll());
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private boolean isOver(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= leftPos + x && mouseX < leftPos + x + width && mouseY >= topPos + y && mouseY < topPos + y + height;
    }

    /** Commits the field being typed in before the screen closes. */
    @Override
    public void onClose() {
        if (getFocused() instanceof FieldBox) {
            setFocused(null);
        }
        super.onClose();
    }

    // --- Rendering ---

    private OptionalDouble energyRate() {
        if (!controls() || state() == DroneState.CHARGING) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(DroneStats.energyUsageMultiplier(shown.data()) * (ServerConfig.get(ServerConfig.DRONE_HOVER_ENERGY_PER_TICK)
                + averageSpeed * ServerConfig.get(ServerConfig.DRONE_ENERGY_PER_BLOCK)) + shown.selfHealRate());
    }

    private Component energyRateTooltip() {
        OptionalDouble rate = energyRate();
        return rate.isPresent()
                ? Component.translatable("screen.seekerdrones.drone_status.energy_rate.tooltip", EnergyFormat.rate(Math.round(rate.getAsDouble())))
                        .withStyle(ChatFormatting.GRAY)
                : Component.translatable("screen.seekerdrones.drone_status.energy_rate.none").withStyle(ChatFormatting.GRAY);
    }

    private Component pillText() {
        return controls() ? Component.translatable(state().getTranslationKey()) : Component.translatable(status.reach().getTranslationKey());
    }

    private Kit.Light pillLight() {
        if (controls()) {
            return DroneStatusScreen.stateLight(state());
        }
        return status.reach() == RemoteReach.NOT_DEPLOYED ? Kit.Light.IDLE : Kit.Light.BAD;
    }

    @Override
    protected void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        DroneData data = shown.data();
        int x = leftPos;
        int y = topPos;
        int colorRgb = data.config().color().getTextureDiffuseColor();
        graphics.fill(x + 3, y + 3, x + imageWidth - 3, y + 5, FastColor.ARGB32.opaque(colorRgb));
        renderHeader(graphics, data);
        renderRadar(graphics, data, x + RADAR_X, y + RADAR_Y);
        if (!controls()) {
            renderReason(graphics, x + RADAR_X, y + RADAR_Y);
        }

        Component radarLine = patrol()
                ? Component.translatable("screen.seekerdrones.drone_status.radar.line", shown.patrolRadius(), patrolCenter())
                : Component.translatable("screen.seekerdrones.drone_status.radar.stationary");
        Kit.scrollingText(graphics, font, radarLine, x + RADAR_X, y + RADAR_LINE_Y, RADAR_SIZE, 7, Kit.LABEL, false, true);
        statRow(graphics, Component.translatable("screen.seekerdrones.drone_status.sight"), shown.sightRange(), x + RADAR_X, y + SIGHT_Y);
        statRow(graphics, Component.translatable("screen.seekerdrones.drone_status.follow"), data.config().followDistance(), x + RADAR_X, y + FOLLOW_Y);

        renderEnergyRow(graphics, data, x + COLUMN_X, y + ENERGY_ROW_Y);
        graphics.blitSprite(Kit.ICON_HEALTH, x + COLUMN_X, y + HEALTH_ROW_Y + 1, 7, 6);
        Kit.smallText(graphics, font, Component.translatable("screen.seekerdrones.drone_status.health_hp", healthText()), x + COLUMN_X + 9,
                y + HEALTH_ROW_Y + 1, Kit.LABEL, false);

        // The display with the accent on its top edge, then what the selected tab shows.
        int displayX = x + COLUMN_X;
        int displayY = y + DISPLAY_Y;
        Kit.display(graphics, displayX, displayY, COLUMN_WIDTH, DISPLAY_HEIGHT);
        graphics.fill(displayX + 1, displayY, displayX + COLUMN_WIDTH - 1, displayY + 1, ACCENT);
        switch (tab) {
            case UPGRADES -> renderUpgrades(graphics, data, displayX, displayY);
            case TARGETS -> renderTargets(graphics, displayX, displayY);
            case BEHAVIOR -> renderBehavior(graphics, displayX, displayY);
            case IDENTITY -> renderIdentity(graphics, displayX, displayY);
        }

        // The command bar's rule: a dark line over a light one, like the frame's own bevel.
        graphics.fill(x + 8, y + SEPARATOR_Y, x + WIDTH - 8, y + SEPARATOR_Y + 1, 0x30000000);
        graphics.fill(x + 8, y + SEPARATOR_Y + 1, x + WIDTH - 8, y + SEPARATOR_Y + 2, 0x90FFFFFF);
    }

    private void statRow(GuiGraphics graphics, Component label, int value, int x, int y) {
        Kit.smallText(graphics, font, label, x, y, Kit.LABEL, false);
        String number = String.valueOf(value);
        Kit.smallText(graphics, font, number, x + RADAR_SIZE - Kit.smallWidth(font, number), y, Kit.LABEL, false);
    }

    private void renderEnergyRow(GuiGraphics graphics, DroneData data, int x, int y) {
        graphics.blitSprite(Kit.ICON_ENERGY, x, y, 6, 8);
        int valueEnd = x + COLUMN_WIDTH;
        OptionalDouble rate = energyRate();
        if (rate.isPresent()) {
            Component rateText = Component.translatable("screen.seekerdrones.drone_status.energy_rate", EnergyFormat.rate(Math.round(rate.getAsDouble())));
            int rateWidth = Kit.smallWidth(font, rateText);
            Kit.smallText(graphics, font, rateText, x + COLUMN_WIDTH - rateWidth, y + 2, Kit.LABEL, false);
            valueEnd -= rateWidth + 4;
        }
        Kit.scrollingText(graphics, font, Component.literal(EnergyFormat.ratio(data.energy(), shown.maxEnergy())), x + 9, y + 2,
                valueEnd - x - 9, 7, Kit.LABEL, false, true);
    }

    private void renderHeader(GuiGraphics graphics, DroneData data) {
        Component state = pillText();
        int pillWidth = Kit.pillWidth(font, state);
        int pillX = leftPos + imageWidth - 8 - pillWidth;
        Kit.pill(graphics, font, pillX, topPos + HEADER_Y, pillLight(), state);

        String id = data.hasDroneId() ? "#" + data.droneId() : Kit.upper(Component.translatable("tooltip.seekerdrones.drone.unassigned"));
        int chipWidth = Kit.smallWidth(font, id) + 6;
        String label = data.config().label();
        Component labelText = label.isEmpty() ? ModItems.DRONE.get().getDescription() : Component.literal(label);
        int labelX = leftPos + 8;
        int maxLabelWidth = pillX - 4 - chipWidth - 4 - labelX;
        int labelWidth = Math.min(font.width(labelText), maxLabelWidth);
        Kit.scrollingText(graphics, font, labelText, labelX, topPos + HEADER_Y + 1, maxLabelWidth, 9,
                FastColor.ARGB32.opaque(data.config().color().getTextColor()), true, false);
        int chipX = labelX + labelWidth + 4;
        graphics.blitSprite(Kit.CHIP, chipX, topPos + HEADER_Y, chipWidth, CHIP_HEIGHT);
        Kit.smallText(graphics, font, id, chipX + 3, topPos + HEADER_Y + 2.5F, Kit.CHIP_TEXT, false);
    }

    /** The radar: the drone circles its patrol radius (scaled to its largest allowed radius) while the sweep turns. */
    private void renderRadar(GuiGraphics graphics, DroneData data, int x, int y) {
        graphics.blitSprite(RADAR, x, y, RADAR_SIZE, RADAR_SIZE);
        float centerX = x + RADAR_SIZE / 2F;
        float centerY = y + RADAR_SIZE / 2F;
        long millis = Util.getMillis();

        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(centerX, centerY, 0);
        pose.mulPose(Axis.ZP.rotationDegrees(millis / 1000F * SWEEP_DEGREES_PER_SECOND % 360));
        Kit.blitTranslucent(graphics, SWEEP, -35, -35, 69, 69);
        pose.popPose();

        float droneX = centerX;
        float droneY = centerY;
        if (patrol()) {
            float radius = Math.max(6, RADAR_RANGE * Kit.fraction(shown.patrolRadius(), Math.max(1, shown.maxPatrolRadius())));
            DronePreview.drawOrbit(graphics, centerX - 0.5F, centerY - 0.5F, radius, radius, Kit.DISPLAY_TEXT);
            double angle = millis / 2600.0;
            droneX += radius * (float) Math.cos(angle);
            droneY += radius * (float) Math.sin(angle);
        }
        graphics.fill(Mth.floor(centerX) - 1, Mth.floor(centerY) - 1, Mth.floor(centerX) + 2, Mth.floor(centerY) + 2, Kit.DISPLAY_TEXT);
        pose.pushPose();
        pose.translate(droneX - 5, droneY - 5, 0);
        pose.scale(10 / 16F, 10 / 16F, 1);
        graphics.renderItem(droneStack, 0, 0);
        pose.popPose();
    }

    /** Dims the radar and says why the drone can't be controlled. */
    private void renderReason(GuiGraphics graphics, int x, int y) {
        graphics.fill(x, y, x + RADAR_SIZE, y + RADAR_SIZE, 0xC00C1215);
        Component reason = status.reach() == RemoteReach.OUT_OF_RANGE
                ? Component.translatable(status.reach().getTranslationKey() + ".detail", status.distance())
                : Component.translatable(status.reach().getTranslationKey() + ".detail");
        List<FormattedCharSequence> lines = font.split(reason, (int) ((RADAR_SIZE - 8) / Kit.SMALL));
        int top = y + (RADAR_SIZE - lines.size() * 7) / 2;
        for (int i = 0; i < lines.size(); i++) {
            int width = Mth.ceil(font.width(lines.get(i)) * Kit.SMALL);
            Kit.smallText(graphics, font, lines.get(i), x + (RADAR_SIZE - width) / 2F, top + i * 7, Kit.DISPLAY_TEXT, false);
        }
    }

    private void renderUpgrades(GuiGraphics graphics, DroneData data, int x, int y) {
        for (int i = 0; i < VISIBLE_CELLS && upgradeScroll + i < upgrades.size(); i++) {
            UpgradeType type = upgrades.get(upgradeScroll + i);
            int cellX = x + PAD + i * CELL_SIZE;
            graphics.blitSprite(Kit.CELL, cellX, y + 5, CELL_SIZE, CELL_SIZE);
            ItemStack stack = new ItemStack(ModItems.upgrade(type).get(), data.upgradeCount(type));
            graphics.renderItem(stack, cellX + 1, y + 6);
            graphics.renderItemDecorations(font, stack, cellX + 1, y + 6);
        }
        Kit.horizontalScrollbar(graphics, x + PAD, y + 5 + CELL_SIZE + 1, VISIBLE_CELLS * CELL_SIZE, 2, upgradeScroll, VISIBLE_CELLS, upgrades.size());
        int total = DroneStats.totalUpgrades(data.upgrades());
        Component slots = upgrades.isEmpty()
                ? Component.translatable("screen.seekerdrones.drone_status.none")
                : Component.translatable(KEY + "slots", total, ServerConfig.get(ServerConfig.UPGRADES_TOTAL_SLOTS));
        Kit.smallText(graphics, font, slots, x + PAD, y + 30, Kit.DISPLAY_TEXT_DIM, false);
        renderHint(graphics, Component.translatable(KEY + "upgrades.hint"), x, y);
    }

    private void renderTargets(GuiGraphics graphics, int x, int y) {
        if (targets.isEmpty()) {
            Kit.smallText(graphics, font, Component.translatable("screen.seekerdrones.drone_status.none"), x + PAD, y + 7, Kit.DISPLAY_TEXT_DIM, false);
        }
        for (int i = 0; i < VISIBLE_CARDS && targetScroll + i < targets.size(); i++) {
            int index = targetScroll + i;
            TargetEntry target = targets.get(index);
            int cardX = x + PAD + i * CARD_PITCH;
            int cardY = y + 5;
            graphics.blitSprite(Kit.CELL, cardX, cardY, CARD_WIDTH, CARD_HEIGHT);
            targetPreviews.render(graphics, target, cardX + 1, cardY + 1, CARD_WIDTH - 2, CARD_PREVIEW_HEIGHT, index, CARD_PREVIEW_SIZE);
            Component caption = target.kind() == TargetEntry.Kind.TAG
                    ? Component.literal("#" + tagPath(target))
                    : targetPreviews.caption(target, index);
            int captionWidth = Kit.smallWidth(font, caption);
            int captionX = captionWidth <= CARD_WIDTH - 4 ? cardX + (CARD_WIDTH - captionWidth) / 2 : cardX + 2;
            Kit.scrollingText(graphics, font, caption, captionX, cardY + CARD_PREVIEW_HEIGHT + 2, CARD_WIDTH - 4, 7, Kit.CHIP_TEXT, false, true);
        }
        Kit.horizontalScrollbar(graphics, x + PAD, y + 5 + CARD_HEIGHT + 1, CONTENT_WIDTH, 2, targetScroll, VISIBLE_CARDS, targets.size());
        renderHint(graphics, Component.translatable(KEY + "targets.hint"), x, y);
    }

    /** A dim line at the bottom of a read-only tab: where to change what it shows. */
    private void renderHint(GuiGraphics graphics, Component hint, int x, int y) {
        Kit.scrollingText(graphics, font, hint, x + PAD, y + DISPLAY_HEIGHT - 10, CONTENT_WIDTH, 7, Kit.DISPLAY_TEXT_DIM, false, true);
    }

    private void renderBehavior(GuiGraphics graphics, int x, int y) {
        int row = 0;
        if (patrol()) {
            // No max in the headers, unlike the station: the display is narrower. The sliders' tooltips still give it.
            row(graphics, Component.translatable(STATION_KEY + "patrol_radius"), x, y, row++);
            row(graphics, Component.translatable(STATION_KEY + "patrol_speed"), x, y, row++);
        }
        row(graphics, Component.translatable(STATION_KEY + "follow_distance.header"), x, y, row++);
        if (!patrol()) {
            graphics.drawWordWrap(font, Component.translatable(STATION_KEY + "patrol.none"), x + PAD, y + 4 + row * ROW_PITCH, CONTENT_WIDTH,
                    Kit.DISPLAY_TEXT_DIM);
        }
    }

    private void row(GuiGraphics graphics, Component label, int x, int y, int row) {
        Kit.scrollingText(graphics, font, label, x + PAD, y + 4 + row * ROW_PITCH, CONTENT_WIDTH, 7, Kit.DISPLAY_TEXT_DIM, false, true);
    }

    private void renderIdentity(GuiGraphics graphics, int x, int y) {
        Kit.smallText(graphics, font, Component.translatable(STATION_KEY + "label"), x + PAD, y + 4, Kit.DISPLAY_TEXT_DIM, false);
        Kit.smallText(graphics, font, Component.translatable(STATION_KEY + "color"), x + PAD, y + 29, Kit.DISPLAY_TEXT_DIM, false);
        Component name = Component.translatable("color.minecraft." + config.color().getName());
        Kit.smallText(graphics, font, name, x + PAD + CONTENT_WIDTH - Kit.smallWidth(font, name), y + 29, Kit.DISPLAY_TEXT, false);
    }

    /** A tag's path without its namespace: "#skeletons" for minecraft:skeletons. */
    private static String tagPath(TargetEntry target) {
        int colon = target.value().indexOf(':');
        return colon < 0 ? target.value() : target.value().substring(colon + 1);
    }
}
