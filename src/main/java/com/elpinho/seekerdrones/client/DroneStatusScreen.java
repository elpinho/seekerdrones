package com.elpinho.seekerdrones.client;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

import com.elpinho.seekerdrones.client.gui.DronePreview;
import com.elpinho.seekerdrones.client.gui.EntityPreview;
import com.elpinho.seekerdrones.client.gui.Gauges;
import com.elpinho.seekerdrones.client.gui.Kit;
import com.elpinho.seekerdrones.client.gui.KitWidget;
import com.elpinho.seekerdrones.client.gui.PanelScreen;
import com.elpinho.seekerdrones.client.gui.SideTab;
import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.DroneItem;
import com.elpinho.seekerdrones.drone.DroneState;
import com.elpinho.seekerdrones.drone.DroneStats;
import com.elpinho.seekerdrones.drone.TargetEntry;
import com.elpinho.seekerdrones.drone.TargetMatcher;
import com.elpinho.seekerdrones.drone.UpgradeType;
import com.elpinho.seekerdrones.energy.EnergyFormat;
import com.elpinho.seekerdrones.network.DroneStatusPayload;
import com.elpinho.seekerdrones.network.RequestDroneStatusPayload;
import com.elpinho.seekerdrones.registry.ModDataComponents;
import com.elpinho.seekerdrones.registry.ModItems;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Read-only drone status screen (DESIGN.md section 2.4): a header in the drone's color with its ID chip and state
 * pill, a radar of the drone circling its patrol radius (a client-side display, not its real position), energy and
 * health bars, the installed upgrades and the usable targets with previews. Upgrades and targets scroll sideways when
 * there are more than fit. Asks the server for fresh values about once a second.
 */
public class DroneStatusScreen extends PanelScreen {
    private static final int REFRESH_INTERVAL_TICKS = 20;
    /** Same reach buffer the server uses when answering refresh requests. */
    private static final double RANGE_BUFFER = 4.0;
    private static final int WIDTH = 228;
    private static final int HEIGHT = 152;

    private static final int RADAR_X = 8;
    private static final int RADAR_Y = 21;
    private static final int RADAR_SIZE = 74;
    /** The radius of the radar's outer ring, which stands for the largest patrol radius the drone allows. */
    private static final int RADAR_RANGE = 30;
    private static final float SWEEP_DEGREES_PER_SECOND = 111;

    // Under the radar: two rows, sight range then follow distance, each value right-aligned.
    private static final int SIGHT_Y = 108;
    private static final int FOLLOW_Y = 118;

    // The right column: energy and health, the upgrades and the targets.
    private static final int COLUMN_X = 90;
    private static final int COLUMN_WIDTH = WIDTH - 8 - COLUMN_X;
    private static final int ENERGY_ROW_Y = 21;
    private static final int ENERGY_BAR_Y = 31;
    private static final int HEALTH_ROW_Y = 40;
    private static final int HEALTH_BAR_Y = 49;
    private static final int UPGRADES_HEADING_Y = 59;
    private static final int CELL_Y = 67;
    private static final int CELL_SIZE = 18;
    private static final int VISIBLE_CELLS = COLUMN_WIDTH / CELL_SIZE;
    private static final int TARGETS_HEADING_Y = 92;
    private static final int CARD_Y = 100;
    private static final int CARD_WIDTH = 42;
    private static final int CARD_HEIGHT = 42;
    /** The entity preview's height in a card; the caption goes under it. */
    private static final int CARD_PREVIEW_HEIGHT = 31;
    /** How big the entity is drawn in its preview box. */
    private static final float CARD_PREVIEW_SIZE = 0.75F * 1.25F;
    private static final int CARD_PITCH = 44;
    /** Targets show as one row of cards. With more, the mouse wheel scrolls it sideways. */
    private static final int VISIBLE_CARDS = 3;

    /** The header row's top: the pill and chip start here. */
    private static final int HEADER_Y = 8;
    private static final int CHIP_HEIGHT = 10;
    private static final double SPEED_SMOOTHING = 0.15;

    private static final ResourceLocation RADAR = Kit.sprite("drone_status/radar");
    private static final ResourceLocation SWEEP = Kit.sprite("drone_status/radar_sweep");

    private final int entityId;
    private DroneStatusPayload status;
    private ItemStack droneStack = ItemStack.EMPTY;
    private List<UpgradeType> upgrades = List.of();
    private List<TargetEntry> targets = List.of();
    private int upgradeScroll;
    private int targetScroll;
    private int ticksOpen;
    /** The drone's speed in blocks per tick, smoothed, for the energy rate. */
    private double averageSpeed;
    private final EntityPreview targetPreviews = new EntityPreview();

    public DroneStatusScreen(DroneStatusPayload status) {
        super(Component.translatable("screen.seekerdrones.drone_status"), WIDTH, HEIGHT);
        this.entityId = status.entityId();
        sideTabs.add(SideTab.energyUnit());
        update(status);
    }

    public int getEntityId() {
        return entityId;
    }

    public void update(DroneStatusPayload status) {
        this.status = status;
        DroneData data = status.data();
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
        upgradeScroll = Math.min(upgradeScroll, Math.max(0, upgrades.size() - VISIBLE_CELLS));
        targetScroll = Math.min(targetScroll, maxTargetScroll());
    }

    @Override
    protected void init() {
        super.init();
        addRenderableWidget(new Gauges.Bar(leftPos + COLUMN_X, topPos + ENERGY_BAR_Y, COLUMN_WIDTH, 6, Gauges.Bar.Kind.ENERGY,
                () -> Kit.fraction(status.data().energy(), status.maxEnergy())))
                .tooltip(() -> List.of(
                        Component.translatable("screen.seekerdrones.energy"),
                        Component.literal(EnergyFormat.ratio(status.data().energy(), status.maxEnergy())).withStyle(ChatFormatting.GRAY),
                        energyRateTooltip()));
        addRenderableWidget(new Gauges.Bar(leftPos + COLUMN_X, topPos + HEALTH_BAR_Y, COLUMN_WIDTH, 6, Gauges.Bar.Kind.HEALTH,
                () -> Kit.fraction(status.data().health(), status.maxHealth())))
                .tooltip(() -> List.of(
                        Component.translatable("screen.seekerdrones.drone_status.health"),
                        healthText().copy().withStyle(ChatFormatting.GRAY)));
        addRenderableWidget(new KitWidget.Area(leftPos + RADAR_X, topPos + RADAR_Y, RADAR_SIZE, RADAR_SIZE)).tooltip(this::radarTooltip);
        // The sight and follow rows: the exact meaning, in blocks.
        addRenderableWidget(new KitWidget.Area(leftPos + RADAR_X, topPos + SIGHT_Y - 1, RADAR_SIZE, 8)).tooltip(() -> List.of(
                Component.translatable("screen.seekerdrones.drone_status.sight.tooltip", status.sightRange())));
        addRenderableWidget(new KitWidget.Area(leftPos + RADAR_X, topPos + FOLLOW_Y - 1, RADAR_SIZE, 8)).tooltip(() -> List.of(
                Component.translatable("screen.seekerdrones.drone_status.follow.tooltip", status.data().config().followDistance())));
        for (int i = 0; i < VISIBLE_CELLS; i++) {
            int index = i;
            addRenderableWidget(new KitWidget.Area(leftPos + COLUMN_X + i * CELL_SIZE, topPos + CELL_Y, CELL_SIZE, CELL_SIZE))
                    .tooltip(() -> upgradeTooltip(upgradeScroll + index));
        }
        for (int i = 0; i < VISIBLE_CARDS; i++) {
            int index = i;
            addRenderableWidget(new KitWidget.Area(leftPos + COLUMN_X + i * CARD_PITCH, topPos + CARD_Y, CARD_WIDTH, CARD_HEIGHT))
                    .tooltip(() -> targetTooltip(targetScroll + index));
        }
    }

    private Component healthText() {
        return Component.translatable("screen.seekerdrones.drone_status.health_value", DroneItem.formatHealth(status.data().health()),
                DroneItem.formatHealth(status.maxHealth()));
    }

    private List<Component> radarTooltip() {
        if (!DroneStats.isPatrolling(status.data())) {
            return List.of(Component.translatable("screen.seekerdrones.drone_status.radar"),
                    Component.translatable("screen.seekerdrones.drone_status.radar.no_patrol").withStyle(ChatFormatting.GRAY));
        }
        return List.of(Component.translatable("screen.seekerdrones.drone_status.radar"),
                Component.translatable("screen.seekerdrones.drone_status.patrol_radius", Component.translatable(
                        "screen.seekerdrones.drone_status.patrol_radius_value", status.patrolRadius(), status.maxPatrolRadius()))
                        .withStyle(ChatFormatting.GRAY),
                Component.translatable("screen.seekerdrones.drone_status.patrol_center", patrolCenter()).withStyle(ChatFormatting.GRAY),
                Component.translatable("screen.seekerdrones.drone_status.radar.preview").withStyle(ChatFormatting.DARK_GRAY));
    }

    private Component patrolCenter() {
        return status.patrolCenter()
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
                        status.data().upgradeCount(type)),
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

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int step = scrollY > 0 ? -1 : scrollY < 0 ? 1 : 0;
        if (isOver(mouseX, mouseY, COLUMN_X, CELL_Y, COLUMN_WIDTH, CELL_SIZE + 3)) {
            upgradeScroll = Mth.clamp(upgradeScroll + step, 0, Math.max(0, upgrades.size() - VISIBLE_CELLS));
            return true;
        }
        if (isOver(mouseX, mouseY, COLUMN_X, CARD_Y, COLUMN_WIDTH, CARD_HEIGHT + 4)) {
            targetScroll = Mth.clamp(targetScroll + step, 0, maxTargetScroll());
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private int maxTargetScroll() {
        return Math.max(0, targets.size() - VISIBLE_CARDS);
    }

    private boolean isOver(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= leftPos + x && mouseX < leftPos + x + width && mouseY >= topPos + y && mouseY < topPos + y + height;
    }

    @Override
    public void tick() {
        super.tick();
        if (entityId == DroneStatusPayload.NO_ENTITY) {
            // A drone item's data can't change while this screen is open, so there's nothing to track.
            return;
        }
        Entity entity = minecraft.level != null ? minecraft.level.getEntity(entityId) : null;
        if (entity == null || entity.isRemoved() || minecraft.player == null || !minecraft.player.canInteractWithEntity(entity, RANGE_BUFFER)) {
            onClose();
            return;
        }
        if (++ticksOpen % REFRESH_INTERVAL_TICKS == 0) {
            PacketDistributor.sendToServer(new RequestDroneStatusPayload(entityId));
        }
        // How far the drone moved this tick, as the client sees it, smoothed over about a second.
        double moved = Math.sqrt(entity.distanceToSqr(entity.xo, entity.yo, entity.zo));
        averageSpeed += (moved - averageSpeed) * SPEED_SMOOTHING;
    }

    /**
     * The drone's current energy use in FE per tick, estimated on the client from the drain config (DESIGN.md section
     * 5.1): the hover cost plus the distance cost at its current speed, times the upgrade multiplier. Empty for a drone
     * item or a docked drone, which don't drain.
     */
    private OptionalDouble energyRate() {
        if (!status.isDeployed() || status.state() == DroneState.CHARGING) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(DroneStats.energyUsageMultiplier(status.data()) * (ServerConfig.get(ServerConfig.DRONE_HOVER_ENERGY_PER_TICK)
                + averageSpeed * ServerConfig.get(ServerConfig.DRONE_ENERGY_PER_BLOCK)));
    }

    private Component energyRateText(double rate) {
        return Component.translatable("screen.seekerdrones.drone_status.energy_rate", EnergyFormat.rate(Math.round(rate)));
    }

    private Component energyRateTooltip() {
        OptionalDouble rate = energyRate();
        return rate.isPresent()
                ? Component.translatable("screen.seekerdrones.drone_status.energy_rate.tooltip", EnergyFormat.rate(Math.round(rate.getAsDouble())))
                        .withStyle(ChatFormatting.GRAY)
                : Component.translatable("screen.seekerdrones.drone_status.energy_rate.none").withStyle(ChatFormatting.GRAY);
    }

    private Kit.Light stateLight() {
        if (!status.isDeployed()) {
            return Kit.Light.IDLE;
        }
        return switch (status.state()) {
            case IDLE -> Kit.Light.IDLE;
            case PATROLLING, CHASING, FOLLOWING -> Kit.Light.OK;
            case RETURNING, CHARGING -> Kit.Light.WARN;
        };
    }

    private Component stateText() {
        return status.isDeployed()
                ? Component.translatable(status.state().getTranslationKey())
                : Component.translatable("screen.seekerdrones.drone_status.not_deployed");
    }

    @Override
    protected void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        DroneData data = status.data();
        int colorRgb = data.config().color().getTextureDiffuseColor();
        int x = leftPos;
        int y = topPos;
        graphics.fill(x + 3, y + 3, x + imageWidth - 3, y + 5, FastColor.ARGB32.opaque(colorRgb));
        renderHeader(graphics, data);
        renderRadar(graphics, data, x + RADAR_X, y + RADAR_Y);

        Component radarLine = DroneStats.isPatrolling(data)
                ? Component.translatable("screen.seekerdrones.drone_status.radar.line", status.patrolRadius(), patrolCenter())
                : Component.translatable("screen.seekerdrones.drone_status.radar.stationary");
        Kit.scrollingText(graphics, font, radarLine, x + RADAR_X, y + 98, RADAR_SIZE, 7, Kit.LABEL, false, true);
        statRow(graphics, Component.translatable("screen.seekerdrones.drone_status.sight"), status.sightRange(), x + RADAR_X, y + SIGHT_Y);
        statRow(graphics, Component.translatable("screen.seekerdrones.drone_status.follow"), data.config().followDistance(), x + RADAR_X,
                y + FOLLOW_Y);

        renderEnergyRow(graphics, data, x + COLUMN_X, y + ENERGY_ROW_Y);
        graphics.blitSprite(Kit.ICON_HEALTH, x + COLUMN_X, y + HEALTH_ROW_Y + 1, 7, 6);
        Kit.smallText(graphics, font, Component.translatable("screen.seekerdrones.drone_status.health_hp", healthText()), x + COLUMN_X + 9,
                y + HEALTH_ROW_Y + 1, Kit.LABEL, false);
        renderUpgrades(graphics, data, x + COLUMN_X, y);
        renderTargets(graphics, x + COLUMN_X, y);
    }

    /** A label on the left and its number right-aligned at the radar's edge. */
    private void statRow(GuiGraphics graphics, Component label, int value, int x, int y) {
        Kit.smallText(graphics, font, label, x, y, Kit.LABEL, false);
        String number = String.valueOf(value);
        Kit.smallText(graphics, font, number, x + RADAR_SIZE - Kit.smallWidth(font, number), y, Kit.LABEL, false);
    }

    /** The stored and max energy, with the current energy use right-aligned on the same row. */
    private void renderEnergyRow(GuiGraphics graphics, DroneData data, int x, int y) {
        graphics.blitSprite(Kit.ICON_ENERGY, x, y, 6, 8);
        int valueEnd = x + COLUMN_WIDTH;
        OptionalDouble rate = energyRate();
        if (rate.isPresent()) {
            Component rateText = energyRateText(rate.getAsDouble());
            int rateWidth = Kit.smallWidth(font, rateText);
            Kit.smallText(graphics, font, rateText, x + COLUMN_WIDTH - rateWidth, y + 2, Kit.LABEL, false);
            valueEnd -= rateWidth + 4;
        }
        Kit.scrollingText(graphics, font, Component.literal(EnergyFormat.ratio(data.energy(), status.maxEnergy())), x + 9, y + 2,
                valueEnd - x - 9, 7, Kit.LABEL, false, true);
    }

    /**
     * The label in the drone's color (the drone's item name without a label), the ID chip right after it, and the
     * state pill on the right. Kept clear of the frame's shaded edge.
     */
    private void renderHeader(GuiGraphics graphics, DroneData data) {
        Component state = stateText();
        int pillWidth = Kit.pillWidth(font, state);
        int pillX = leftPos + imageWidth - 8 - pillWidth;
        Kit.pill(graphics, font, pillX, topPos + HEADER_Y, stateLight(), state);

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

    /**
     * The radar: the drone circles its patrol radius (scaled to its largest allowed radius) while the sweep turns. A
     * drone without Patrol hovers in the middle.
     */
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
        if (DroneStats.isPatrolling(data)) {
            float radius = Math.max(6, RADAR_RANGE * Kit.fraction(status.patrolRadius(), Math.max(1, status.maxPatrolRadius())));
            DronePreview.drawOrbit(graphics, centerX - 0.5F, centerY - 0.5F, radius, radius, Kit.DISPLAY_TEXT);
            double angle = millis / 2600.0;
            droneX += radius * (float) Math.cos(angle);
            droneY += radius * (float) Math.sin(angle);
        }
        graphics.fill(Mth.floor(centerX) - 1, Mth.floor(centerY) - 1, Mth.floor(centerX) + 2, Mth.floor(centerY) + 2, Kit.DISPLAY_TEXT);
        // The drone item in its color, at 10x10.
        pose.pushPose();
        pose.translate(droneX - 5, droneY - 5, 0);
        pose.scale(10 / 16F, 10 / 16F, 1);
        graphics.renderItem(droneStack, 0, 0);
        pose.popPose();
    }

    private void renderUpgrades(GuiGraphics graphics, DroneData data, int x, int y) {
        heading(graphics, Component.translatable("screen.seekerdrones.drone_status.upgrades"), x, y + UPGRADES_HEADING_Y);
        int total = upgrades.stream().mapToInt(data::upgradeCount).sum();
        String totalText = String.valueOf(total);
        Kit.smallText(graphics, font, totalText, x + COLUMN_WIDTH - Kit.smallWidth(font, totalText), y + UPGRADES_HEADING_Y, Kit.LABEL, false);
        if (upgrades.isEmpty()) {
            Kit.smallText(graphics, font, Component.translatable("screen.seekerdrones.drone_status.none"), x, y + CELL_Y + 5, Kit.LABEL, false);
            return;
        }
        for (int i = 0; i < VISIBLE_CELLS && upgradeScroll + i < upgrades.size(); i++) {
            UpgradeType type = upgrades.get(upgradeScroll + i);
            int cellX = x + i * CELL_SIZE;
            graphics.blitSprite(Kit.CELL, cellX, y + CELL_Y, CELL_SIZE, CELL_SIZE);
            ItemStack stack = new ItemStack(ModItems.upgrade(type).get(), data.upgradeCount(type));
            graphics.renderItem(stack, cellX + 1, y + CELL_Y + 1);
            graphics.renderItemDecorations(font, stack, cellX + 1, y + CELL_Y + 1);
        }
        Kit.horizontalScrollbar(graphics, x, y + CELL_Y + CELL_SIZE + 1, VISIBLE_CELLS * CELL_SIZE, 2, upgradeScroll, VISIBLE_CELLS,
                upgrades.size());
    }

    /** Section headings, in small text like the values. */
    private void heading(GuiGraphics graphics, Component text, int x, int y) {
        Kit.smallText(graphics, font, text, x, y, Kit.LABEL, false);
    }

    private void renderTargets(GuiGraphics graphics, int x, int y) {
        heading(graphics, Component.translatable("screen.seekerdrones.drone_status.targets"), x, y + TARGETS_HEADING_Y);
        if (targets.isEmpty()) {
            Kit.smallText(graphics, font, Component.translatable("screen.seekerdrones.drone_status.none"), x, y + CARD_Y + 2, Kit.LABEL, false);
            return;
        }
        for (int i = 0; i < VISIBLE_CARDS && targetScroll + i < targets.size(); i++) {
            int index = targetScroll + i;
            TargetEntry target = targets.get(index);
            int cardX = x + i * CARD_PITCH;
            int cardY = y + CARD_Y;
            graphics.blitSprite(Kit.CELL, cardX, cardY, CARD_WIDTH, CARD_HEIGHT);
            targetPreviews.render(graphics, target, cardX + 1, cardY + 1, CARD_WIDTH - 2, CARD_PREVIEW_HEIGHT, index, CARD_PREVIEW_SIZE);
            Component caption = target.kind() == TargetEntry.Kind.TAG
                    ? Component.literal("#" + tagPath(target))
                    : targetPreviews.caption(target, index);
            int captionWidth = Kit.smallWidth(font, caption);
            int captionX = captionWidth <= CARD_WIDTH - 4 ? cardX + (CARD_WIDTH - captionWidth) / 2 : cardX + 2;
            Kit.scrollingText(graphics, font, caption, captionX, cardY + CARD_PREVIEW_HEIGHT + 2, CARD_WIDTH - 4, 7, Kit.CHIP_TEXT, false, true);
        }
        Kit.horizontalScrollbar(graphics, x, y + CARD_Y + CARD_HEIGHT + 1, COLUMN_WIDTH, 2, targetScroll, VISIBLE_CARDS, targets.size());
    }

    /** A tag's path without its namespace: "#skeletons" for minecraft:skeletons. */
    private static String tagPath(TargetEntry target) {
        int colon = target.value().indexOf(':');
        return colon < 0 ? target.value() : target.value().substring(colon + 1);
    }
}
