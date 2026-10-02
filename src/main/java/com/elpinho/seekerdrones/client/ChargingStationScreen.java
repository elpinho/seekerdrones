package com.elpinho.seekerdrones.client;

import java.util.List;
import java.util.Optional;

import com.elpinho.seekerdrones.client.gui.DronePreview;
import com.elpinho.seekerdrones.client.gui.EntityPreview;
import com.elpinho.seekerdrones.client.gui.Gauges;
import com.elpinho.seekerdrones.client.gui.Kit;
import com.elpinho.seekerdrones.client.gui.PanelScreen;
import com.elpinho.seekerdrones.client.gui.SideTab;
import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.DroneItem;
import com.elpinho.seekerdrones.energy.EnergyFormat;
import com.elpinho.seekerdrones.network.RequestStationStatusPayload;
import com.elpinho.seekerdrones.network.StationStatusPayload;
import com.elpinho.seekerdrones.network.StationStatusPayload.DockedDrone;
import com.elpinho.seekerdrones.network.StationStatusPayload.Status;
import com.elpinho.seekerdrones.registry.ModBlocks;

import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Read-only Charging Station screen (DESIGN.md section 7.4): station energy on the left, repair fluid on the right and
 * the drone holding the station in the middle, in its color, with its energy and health bars. The status is a pill in
 * the title row and the owner is a side tab. Asks the server for fresh values about once a second.
 */
public class ChargingStationScreen extends PanelScreen {
    private static final int REFRESH_INTERVAL_TICKS = 20;
    /** Same reach buffer the server uses when answering refresh requests. */
    private static final double RANGE_BUFFER = 4.0;
    private static final int WIDTH = 176;
    private static final int HEIGHT = 108;

    private static final int DISPLAY_X = 24;
    private static final int DISPLAY_Y = 16;
    private static final int DISPLAY_WIDTH = 128;
    private static final int DISPLAY_HEIGHT = 86;
    /** The dock pad, relative to the display. */
    private static final int DOCK_X = 43;
    private static final int DOCK_Y = 44;
    /** Pixels per block for the drone preview. */
    private static final float DRONE_SCALE = 18;
    private static final int FLOW_Y = 30;
    private static final int ENERGY_FLOW = 0xFF4ADE80;
    private static final int FLUID_FLOW = 0xFFF08A1A;
    private static final int SPARK = 0xFFFFF6A0;
    private static final int OWNER_PANEL_WIDTH = 86;
    private static final int OWNER_PANEL_HEIGHT = 30;

    private static final ResourceLocation DOCK = Kit.sprite("charging/dock");

    private final BlockPos pos;
    private StationStatusPayload status;
    private int ticksOpen;
    private final DronePreview dronePreview = new DronePreview();

    public ChargingStationScreen(StationStatusPayload status) {
        super(Component.translatable("screen.seekerdrones.charging_station"), WIDTH, HEIGHT);
        this.pos = status.pos();
        this.status = status;
        sideTabs.add(SideTab.energyUnit());
        sideTabs.add(new SideTab(22, (graphics, x, y, mouseX, mouseY) -> EntityPreview.drawFace(graphics, ownerName(), x + 8, y + 6, 9))
                .tooltip(this::ownerTooltip)
                .panel(OWNER_PANEL_WIDTH, OWNER_PANEL_HEIGHT, (graphics, x, y, mouseX, mouseY) -> {
                    EntityPreview.drawFace(graphics, ownerName(), x + 8, y + 5, 9);
                    graphics.drawString(font, Component.translatable("screen.seekerdrones.charging_station.owner"), x + 21, y + 6, Kit.LABEL, false);
                    Kit.scrollingText(graphics, font, ownerLabel(), x + 8, y + 18, OWNER_PANEL_WIDTH - 14, 7, Kit.LABEL, false, true);
                }));
    }

    public BlockPos getPos() {
        return pos;
    }

    public void update(StationStatusPayload status) {
        this.status = status;
    }

    private String ownerName() {
        return status.ownerName();
    }

    private Component ownerLabel() {
        return status.ownerName().isEmpty() ? Component.translatable("screen.seekerdrones.drone_status.none") : Component.literal(status.ownerName());
    }

    private List<Component> ownerTooltip() {
        return List.of(
                Component.translatable("screen.seekerdrones.charging_station.owner.value", ownerLabel()),
                Component.translatable("screen.seekerdrones.charging_station.owner.tooltip").withStyle(ChatFormatting.GRAY));
    }

    @Override
    protected void init() {
        super.init();
        addRenderableWidget(new Gauges.Energy(leftPos + 8, topPos + 16, 12, 86, () -> Kit.fraction(status.energy(), status.capacity())))
                .tooltip(() -> List.of(
                        Component.translatable("screen.seekerdrones.charging_station.energy"),
                        Component.literal(EnergyFormat.ratio(status.energy(), status.capacity())).withStyle(ChatFormatting.GRAY),
                        Component.translatable("screen.seekerdrones.charging_station.charge_rate", EnergyFormat.rate(status.chargeRate()))
                                .withStyle(ChatFormatting.GRAY)));
        addRenderableWidget(new Gauges.FluidTank(leftPos + 156, topPos + 16, 12, 86, () -> status.fluid(), () -> status.fluidAmount(),
                () -> status.tankCapacity()))
                .tooltip(() -> List.of(
                        status.fluid().getFluidType().getDescription(),
                        Component.translatable("screen.seekerdrones.fluid_value", status.fluidAmount(), status.tankCapacity())
                                .withStyle(ChatFormatting.GRAY),
                        Component.translatable("screen.seekerdrones.charging_station.repair_fluid.tooltip").withStyle(ChatFormatting.GRAY)));
        int barX = leftPos + DISPLAY_X + 18;
        addRenderableWidget(new Gauges.Bar(barX, topPos + DISPLAY_Y + 66, 100, 6, Gauges.Bar.Kind.ENERGY,
                () -> drone().map(d -> Kit.fraction(d.data().energy(), d.maxEnergy())).orElse(0F)))
                .tooltip(() -> drone().map(d -> List.<Component>of(
                        Component.translatable("screen.seekerdrones.charging_station.drone_energy"),
                        Component.literal(EnergyFormat.ratio(d.data().energy(), d.maxEnergy())).withStyle(ChatFormatting.GRAY)))
                        .orElse(List.of()));
        addRenderableWidget(new Gauges.Bar(barX, topPos + DISPLAY_Y + 75, 100, 6, Gauges.Bar.Kind.HEALTH,
                () -> drone().map(d -> Kit.fraction(d.data().health(), d.maxHealth())).orElse(0F)))
                .tooltip(() -> drone().map(d -> List.<Component>of(
                        Component.translatable("screen.seekerdrones.charging_station.drone_health"),
                        Component.translatable("screen.seekerdrones.drone_status.health_value", DroneItem.formatHealth(d.data().health()),
                                DroneItem.formatHealth(d.maxHealth())).withStyle(ChatFormatting.GRAY)))
                        .orElse(List.of()));
        updateBarVisibility();
    }

    private Optional<DockedDrone> drone() {
        return status.drone();
    }

    private void updateBarVisibility() {
        boolean hasDrone = drone().isPresent();
        children().forEach(child -> {
            if (child instanceof Gauges.Bar bar) {
                bar.visible = hasDrone;
            }
        });
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
        updateBarVisibility();
    }

    private Kit.Light statusLight() {
        return switch (status.status()) {
            case IDLE -> Kit.Light.IDLE;
            case DOCKING -> Kit.Light.WARN;
            case CHARGING, HEALING -> Kit.Light.OK;
            case NO_POWER -> Kit.Light.BAD;
        };
    }

    @Override
    protected void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.drawString(font, title, leftPos + 8, topPos + 6, Kit.LABEL, false);
        Component statusText = Component.translatable(status.status().getTranslationKey());
        Kit.pill(graphics, font, leftPos + imageWidth - 8 - Kit.pillWidth(font, statusText), topPos + 4, statusLight(), statusText);

        int x = leftPos + DISPLAY_X;
        int y = topPos + DISPLAY_Y;
        Kit.display(graphics, x, y, DISPLAY_WIDTH, DISPLAY_HEIGHT);
        graphics.blitSprite(Kit.ICON_ENERGY, x + 4, y + 4, 6, 8);
        Kit.smallText(graphics, font, EnergyFormat.rate(status.chargeRate()), x + 13, y + 5, Kit.DISPLAY_TEXT, false);

        Optional<DockedDrone> docked = drone();
        if (docked.isPresent() && status.status() != Status.NO_POWER) {
            renderFlows(graphics, x, y, docked.get());
        }
        if (docked.isEmpty()) {
            Kit.smallTextCentered(graphics, font, Component.translatable("screen.seekerdrones.charging_station.no_drone"), x + DISPLAY_WIDTH / 2,
                    y + (DISPLAY_HEIGHT - 6) / 2F, Kit.DISPLAY_TEXT_DIM);
            return;
        }
        DockedDrone drone = docked.get();
        graphics.blitSprite(DOCK, x + DOCK_X, y + DOCK_Y, 40, 5);
        // A drone that hasn't docked yet hovers higher, still flying in.
        float droneY = y + (drone.docked() ? 36 : 26);
        dronePreview.renderHovering(graphics, drone.data().config().color(), x + DOCK_X + 20, droneY, DRONE_SCALE);
        if (status.status() == Status.CHARGING || status.status() == Status.HEALING) {
            renderSparks(graphics, x, y);
        }
        Component name = identity(drone.data());
        int nameWidth = font.width(name);
        int nameX = nameWidth <= DISPLAY_WIDTH - 4 ? x + (DISPLAY_WIDTH - nameWidth) / 2 : x + 2;
        Kit.scrollingText(graphics, font, name, nameX, y + 54, DISPLAY_WIDTH - 4, 9, Kit.DISPLAY_TEXT, false, false);
        graphics.blitSprite(Kit.ICON_ENERGY, x + 8, y + 65, 6, 8);
        graphics.blitSprite(Kit.ICON_HEALTH, x + 7, y + 75, 7, 6);
    }

    /** The label in the drone's color, then the ID dimmed. */
    static Component identity(DroneData data) {
        MutableComponent id = data.hasDroneId()
                ? Component.literal("#" + data.droneId())
                : Component.translatable("tooltip.seekerdrones.drone.unassigned");
        id.withColor(Kit.DISPLAY_TEXT_DIM);
        String label = data.config().label();
        if (label.isEmpty()) {
            return id;
        }
        return Component.literal(label).withStyle(style -> style.withColor(TextColor.fromRgb(data.config().color().getTextColor())))
                .append(Component.literal(" ")).append(id);
    }

    /** Charge flows in from the left while charging, repair fluid from the right while the drone heals. */
    private void renderFlows(GuiGraphics graphics, int x, int y, DockedDrone drone) {
        long millis = Util.getMillis();
        boolean charging = drone.docked() && status.status() == Status.CHARGING;
        boolean healing = drone.docked() && drone.data().health() < drone.maxHealth() && status.fluidAmount() > 0;
        for (int i = 0; i < 3; i++) {
            if (charging) {
                float p = (millis / 1100F + i / 3F) % 1;
                arrow(graphics, x + 4 + Math.round(p * 40), y + FLOW_Y, true, alpha(p), ENERGY_FLOW);
            }
            if (healing) {
                float p = (millis / 1300F + i / 3F) % 1;
                arrow(graphics, x + 118 - Math.round(p * 40), y + FLOW_Y, false, alpha(p), FLUID_FLOW);
            }
        }
    }

    private static float alpha(float progress) {
        return (float) Math.sin(progress * Math.PI);
    }

    /** A small 4x4 triangle pointing right or left. */
    private static void arrow(GuiGraphics graphics, int x, int y, boolean right, float alpha, int color) {
        int argb = Math.round(alpha * 255) << 24 | (color & 0xFFFFFF);
        for (int column = 0; column < 3; column++) {
            int height = right ? 4 - column : column + 2;
            int top = y + (4 - height) / 2;
            graphics.fill(x + column, top, x + column + 1, top + height, argb);
        }
    }

    private static void renderSparks(GuiGraphics graphics, int x, int y) {
        long step = Util.getMillis() / 120;
        for (int i = 0; i < 4; i++) {
            long k = step + i * 7L;
            if (k * 13 % 5 >= 2) {
                continue;
            }
            int sparkX = x + 48 + (int) (k * 37 % 30);
            int sparkY = y + 36 + (int) (k * 17 % 9);
            graphics.pose().pushPose();
            graphics.pose().translate(0, 0, 300);
            graphics.fill(sparkX, sparkY, sparkX + 1, sparkY + 1, SPARK);
            graphics.pose().popPose();
        }
    }
}
