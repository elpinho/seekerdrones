package com.elpinho.seekerdrones.client;

import java.util.ArrayList;
import java.util.List;

import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.DroneItem;
import com.elpinho.seekerdrones.drone.DroneStats;
import com.elpinho.seekerdrones.drone.TargetEntry;
import com.elpinho.seekerdrones.drone.UpgradeType;
import com.elpinho.seekerdrones.network.DroneStatusPayload;
import com.elpinho.seekerdrones.network.RequestDroneStatusPayload;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Read-only drone status screen (DESIGN.md section 2.4). Asks the server for fresh values about once a second.
 */
public class DroneStatusScreen extends Screen {
    private static final int REFRESH_INTERVAL_TICKS = 20;
    /** Same reach buffer the server uses when answering refresh requests. */
    private static final double RANGE_BUFFER = 4.0;
    private static final int PADDING = 8;
    private static final int LINE_HEIGHT = 11;
    private static final int PANEL_COLOR = 0xE0101018;
    private static final int BORDER_COLOR = 0xFF5A5A70;
    private static final int TEXT_COLOR = 0xFFE0E0E0;

    private final int entityId;
    private List<Component> lines = List.of();
    private int ticksOpen;

    public DroneStatusScreen(DroneStatusPayload status) {
        super(Component.translatable("screen.seekerdrones.drone_status"));
        this.entityId = status.entityId();
        update(status);
    }

    public int getEntityId() {
        return entityId;
    }

    public void update(DroneStatusPayload status) {
        this.lines = buildLines(status);
    }

    private static List<Component> buildLines(DroneStatusPayload status) {
        DroneData data = status.data();
        List<Component> lines = new ArrayList<>();
        lines.add(DroneItem.identity(data));
        lines.add(Component.empty());
        lines.add(field("state", status.isDeployed()
                ? Component.translatable(status.state().getTranslationKey())
                : Component.translatable("screen.seekerdrones.drone_status.not_deployed")));
        lines.add(field("energy", Component.translatable("screen.seekerdrones.drone_status.energy_value", data.energy(), status.maxEnergy())));
        lines.add(field("health", Component.translatable("screen.seekerdrones.drone_status.health_value",
                DroneItem.formatHealth(data.health()), DroneItem.formatHealth(status.maxHealth()))));
        lines.add(field("sight_range", Component.translatable("screen.seekerdrones.drone_status.sight_range_value", status.sightRange())));
        if (DroneStats.isPatrolling(data)) {
            lines.add(field("patrol_center", status.patrolCenter()
                    .<Component>map(center -> Component.literal(center.pos().getX() + ", " + center.pos().getY() + ", " + center.pos().getZ()))
                    .orElse(Component.translatable("screen.seekerdrones.drone_status.not_set"))));
            lines.add(field("patrol_radius", Component.translatable("screen.seekerdrones.drone_status.patrol_radius_value",
                    status.patrolRadius(), status.maxPatrolRadius())));        }

        lines.add(field("upgrades", data.upgrades().isEmpty() ? Component.translatable("screen.seekerdrones.drone_status.none") : Component.empty()));
        for (UpgradeType type : UpgradeType.values()) {
            int count = data.upgradeCount(type);
            if (count > 0) {
                lines.add(Component.literal("  ").append(Component.translatable("tooltip.seekerdrones.drone.upgrade_entry",
                        Component.translatable(type.getTranslationKey()), count)));
            }
        }

        List<TargetEntry> targets = data.config().targets();
        lines.add(field("targets", targets.isEmpty() ? Component.translatable("screen.seekerdrones.drone_status.none") : Component.empty()));
        for (TargetEntry target : targets) {
            lines.add(Component.literal("  " + target.displayString()));
        }
        return lines;
    }

    private static Component field(String key, Component value) {
        MutableComponent label = Component.translatable("screen.seekerdrones.drone_status." + key).withStyle(ChatFormatting.GRAY);
        return label.append(Component.literal(" ")).append(value.copy().withStyle(ChatFormatting.WHITE));
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
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        int width = 0;
        for (Component line : lines) {
            width = Math.max(width, font.width(line));
        }
        int panelWidth = width + PADDING * 2;
        int panelHeight = lines.size() * LINE_HEIGHT + PADDING * 2 - 2;
        int left = (this.width - panelWidth) / 2;
        int top = (this.height - panelHeight) / 2;
        graphics.fill(left - 1, top - 1, left + panelWidth + 1, top + panelHeight + 1, BORDER_COLOR);
        graphics.fill(left, top, left + panelWidth, top + panelHeight, PANEL_COLOR);
        int y = top + PADDING;
        for (Component line : lines) {
            graphics.drawString(font, line, left + PADDING, y, TEXT_COLOR, false);
            y += LINE_HEIGHT;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
