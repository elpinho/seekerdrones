package com.elpinho.seekerdrones.client;

import java.util.ArrayList;
import java.util.List;

import com.elpinho.seekerdrones.drone.DroneItem;
import com.elpinho.seekerdrones.network.RequestStationStatusPayload;
import com.elpinho.seekerdrones.network.StationStatusPayload;
import com.elpinho.seekerdrones.network.StationStatusPayload.DockedDrone;
import com.elpinho.seekerdrones.registry.ModBlocks;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Read-only Charging Station screen (DESIGN.md section 7.4): status, stored FE, charge rate, owner and the drone
 * holding the station. Asks the server for fresh values about once a second.
 */
public class ChargingStationScreen extends Screen {
    private static final int REFRESH_INTERVAL_TICKS = 20;
    /** Same reach buffer the server uses when answering refresh requests. */
    private static final double RANGE_BUFFER = 4.0;
    private static final int PADDING = 8;
    private static final int LINE_HEIGHT = 11;
    private static final int BAR_HEIGHT = 4;
    private static final int BAR_GAP = 3;
    private static final int MIN_CONTENT_WIDTH = 140;
    private static final int PANEL_COLOR = 0xE0101018;
    private static final int BORDER_COLOR = 0xFF5A5A70;
    private static final int TEXT_COLOR = 0xFFE0E0E0;
    private static final int BAR_BACKGROUND_COLOR = 0xFF2A2A38;
    private static final int ENERGY_BAR_COLOR = 0xFFD83A2E;
    private static final int HEALTH_BAR_COLOR = 0xFF3FB950;
    private static final int FLUID_BAR_COLOR = 0xFFE07A1A;

    /** A line of text, optionally with a fill bar under it ({@code fill} below 0 means no bar). */
    private record Row(Component text, float fill, int barColor) {
        static Row text(Component text) {
            return new Row(text, -1, 0);
        }

        int height() {
            return fill < 0 ? LINE_HEIGHT : LINE_HEIGHT + BAR_HEIGHT + BAR_GAP;
        }
    }

    private final BlockPos pos;
    private List<Row> rows = List.of();
    private int ticksOpen;

    public ChargingStationScreen(StationStatusPayload status) {
        super(Component.translatable("screen.seekerdrones.charging_station"));
        this.pos = status.pos();
        update(status);
    }

    public BlockPos getPos() {
        return pos;
    }

    public void update(StationStatusPayload status) {
        this.rows = buildRows(status);
    }

    private List<Row> buildRows(StationStatusPayload status) {
        List<Row> rows = new ArrayList<>();
        rows.add(Row.text(title.copy().withStyle(ChatFormatting.BOLD)));
        rows.add(Row.text(Component.empty()));
        rows.add(Row.text(field("status", Component.translatable(status.status().getTranslationKey()))));
        rows.add(new Row(field("energy", Component.translatable("screen.seekerdrones.drone_status.energy_value", status.energy(), status.capacity())),
                fraction(status.energy(), status.capacity()), ENERGY_BAR_COLOR));
        rows.add(Row.text(field("charge_rate", Component.translatable("screen.seekerdrones.charging_station.charge_rate_value", status.chargeRate()))));
        rows.add(new Row(field("repair_fluid", Component.translatable("screen.seekerdrones.charging_station.repair_fluid_value",
                status.fluid().getFluidType().getDescription(), status.fluidAmount(), status.tankCapacity())),
                fraction(status.fluidAmount(), status.tankCapacity()), FLUID_BAR_COLOR));
        rows.add(Row.text(field("owner", status.ownerName().isEmpty()
                ? Component.translatable("screen.seekerdrones.drone_status.none")
                : Component.literal(status.ownerName()))));
        rows.add(Row.text(Component.empty()));

        if (status.drone().isEmpty()) {
            rows.add(Row.text(field("drone", Component.translatable("screen.seekerdrones.drone_status.none"))));
            return rows;
        }
        DockedDrone drone = status.drone().get();
        rows.add(Row.text(field("drone", DroneItem.identity(drone.data()))));
        rows.add(new Row(indented(field("drone_energy", Component.translatable("screen.seekerdrones.drone_status.energy_value",
                drone.data().energy(), drone.maxEnergy()))), fraction(drone.data().energy(), drone.maxEnergy()), ENERGY_BAR_COLOR));
        rows.add(new Row(indented(field("drone_health", Component.translatable("screen.seekerdrones.drone_status.health_value",
                DroneItem.formatHealth(drone.data().health()), DroneItem.formatHealth(drone.maxHealth())))),
                fraction(drone.data().health(), drone.maxHealth()), HEALTH_BAR_COLOR));
        return rows;
    }

    private static Component field(String key, Component value) {
        MutableComponent label = Component.translatable("screen.seekerdrones.charging_station." + key).withStyle(ChatFormatting.GRAY);
        // Keep a value's own color (the drone identity is drawn in the drone's color).
        MutableComponent styled = value.getStyle().getColor() == null ? value.copy().withStyle(ChatFormatting.WHITE) : value.copy();
        return label.append(Component.literal(" ")).append(styled);
    }

    private static Component indented(Component line) {
        return Component.literal("  ").append(line);
    }

    private static float fraction(float value, float max) {
        return max <= 0 ? 0 : Math.clamp(value / max, 0, 1);
    }

    @Override
    public void tick() {
        super.tick();
        if (minecraft.level == null || minecraft.player == null || !minecraft.level.getBlockState(pos).is(ModBlocks.CHARGING_STATION.get())
                || !minecraft.player.canInteractWithBlock(pos, RANGE_BUFFER)) {
            onClose();
            return;
        }
        if (++ticksOpen % REFRESH_INTERVAL_TICKS == 0) {
            PacketDistributor.sendToServer(new RequestStationStatusPayload(pos));
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        int contentWidth = MIN_CONTENT_WIDTH;
        int contentHeight = 0;
        for (Row row : rows) {
            contentWidth = Math.max(contentWidth, font.width(row.text()));
            contentHeight += row.height();
        }
        int panelWidth = contentWidth + PADDING * 2;
        int panelHeight = contentHeight + PADDING * 2 - 2;
        int left = (this.width - panelWidth) / 2;
        int top = (this.height - panelHeight) / 2;
        graphics.fill(left - 1, top - 1, left + panelWidth + 1, top + panelHeight + 1, BORDER_COLOR);
        graphics.fill(left, top, left + panelWidth, top + panelHeight, PANEL_COLOR);
        int x = left + PADDING;
        int y = top + PADDING;
        for (Row row : rows) {
            graphics.drawString(font, row.text(), x, y, TEXT_COLOR, false);
            if (row.fill() >= 0) {
                int barTop = y + LINE_HEIGHT - 1;
                graphics.fill(x, barTop, x + contentWidth, barTop + BAR_HEIGHT, BAR_BACKGROUND_COLOR);
                graphics.fill(x, barTop, x + Math.round(contentWidth * row.fill()), barTop + BAR_HEIGHT, row.barColor());
            }
            y += row.height();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
