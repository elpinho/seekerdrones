package com.elpinho.seekerdrones.client;

import java.util.List;

import com.elpinho.seekerdrones.client.gui.Gauges;
import com.elpinho.seekerdrones.client.gui.Kit;
import com.elpinho.seekerdrones.client.gui.KitWidget;
import com.elpinho.seekerdrones.client.gui.MachineScreen;
import com.elpinho.seekerdrones.client.gui.SideTab;
import com.elpinho.seekerdrones.client.gui.StatusStrip;
import com.elpinho.seekerdrones.client.radar.RadarScope;
import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.energy.EnergyFormat;
import com.elpinho.seekerdrones.network.RadarContactsPayload;
import com.elpinho.seekerdrones.network.RadarTerrainPayload;
import com.elpinho.seekerdrones.radar.RadarStationMenu;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.phys.Vec3;

/**
 * Radar Station screen (DESIGN.md section 7.8): the energy gauge, the round scope at 2 px per block (the terrain map,
 * the sweep and the viewer's own drones), a status strip and the energy unit side tab. Everything moving on the scope
 * is client-side; the server only sends the map and the drones' positions.
 */
public class RadarStationScreen extends MachineScreen<RadarStationMenu> {
    private static final int SCOPE_X = 24;
    private static final int SCOPE_Y = 16;
    /** The display's size: the map at 2 px per block for the default radius (97 blocks), plus its 2 px border. */
    private static final int SCOPE_SIZE = 198;
    private static final int SCOPE_BORDER = 2;
    private static final int STRIP_Y = SCOPE_Y + SCOPE_SIZE + 4;
    private static final int WIDTH = SCOPE_X + SCOPE_SIZE + 8;
    private static final int HEIGHT = STRIP_Y + StatusStrip.HEIGHT + 7;

    private final RadarScope scope = new RadarScope();

    public RadarStationScreen(RadarStationMenu menu, Inventory inventory, Component title) {
        // No inventory: its label is never drawn (see renderLabels).
        super(menu, inventory, title, WIDTH, HEIGHT, HEIGHT);
        sideTabs.add(SideTab.energyUnit());
    }

    /** Hands a terrain map to the open screen of the station it belongs to. */
    public static void onTerrain(RadarTerrainPayload payload) {
        if (Minecraft.getInstance().screen instanceof RadarStationScreen screen && screen.menu.getPos().equals(payload.pos())) {
            screen.scope.setTerrain(payload.map());
        }
    }

    /** Hands the viewer's drones to the open screen of the station they belong to. */
    public static void onContacts(RadarContactsPayload payload) {
        if (Minecraft.getInstance().screen instanceof RadarStationScreen screen && screen.menu.getPos().equals(payload.pos())) {
            screen.scope.setContacts(payload.contacts());
        }
    }

    @Override
    protected void init() {
        super.init();
        addRenderableWidget(new Gauges.Energy(leftPos + 8, topPos + SCOPE_Y, 12, SCOPE_SIZE,
                () -> Kit.fraction(menu.getEnergy(), menu.getEnergyCapacity())))
                .tooltip(() -> List.of(
                        Component.translatable("screen.seekerdrones.energy"),
                        Component.literal(EnergyFormat.ratio(menu.getEnergy(), menu.getEnergyCapacity())).withStyle(ChatFormatting.GRAY),
                        Component.translatable("screen.seekerdrones.radar_station.energy_use",
                                EnergyFormat.rate(menu.getEnergyPerTick())).withStyle(ChatFormatting.GRAY)));
        addRenderableWidget(new StatusStrip(leftPos + 8, topPos + STRIP_Y, WIDTH - 16, this::status));
    }

    private StatusStrip.Status status() {
        if (!menu.isPowered()) {
            return new StatusStrip.Status(Kit.Light.BAD, true, Component.translatable("screen.seekerdrones.radar_station.status.no_power"));
        }
        if (!scope.hasTerrain()) {
            return new StatusStrip.Status(Kit.Light.WARN, false, Component.translatable("screen.seekerdrones.radar_station.status.building"));
        }
        return new StatusStrip.Status(Kit.Light.OK, false,
                Component.translatable("screen.seekerdrones.radar_station.status.scanning", scope.contactCount()));
    }

    @Override
    protected void renderContents(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = leftPos + SCOPE_X;
        int y = topPos + SCOPE_Y;
        Kit.display(graphics, x, y, SCOPE_SIZE, SCOPE_SIZE);
        Vec3 center = menu.getPos().getCenter();
        var player = minecraft.player;
        scope.render(graphics, font, x + SCOPE_BORDER, y + SCOPE_BORDER, SCOPE_SIZE - 2 * SCOPE_BORDER, time(partialTick), sweepTicks(),
                ServerConfig.get(ServerConfig.RADAR_STATION_RADIUS), menu.isPowered(),
                player.getX() - center.x, player.getZ() - center.z, player.getViewYRot(partialTick));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        int inner = SCOPE_SIZE - 2 * SCOPE_BORDER;
        Component hovered = scope.hovered(leftPos + SCOPE_X + SCOPE_BORDER, topPos + SCOPE_Y + SCOPE_BORDER, inner, time(partialTick),
                sweepTicks(), ServerConfig.get(ServerConfig.RADAR_STATION_RADIUS), mouseX, mouseY);
        if (hovered != null) {
            KitWidget.showTooltip(List.of(hovered));
        }
    }

    private double time(float partialTick) {
        return minecraft.level.getGameTime() + partialTick;
    }

    private static int sweepTicks() {
        return ServerConfig.get(ServerConfig.RADAR_STATION_SWEEP_TICKS);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, titleLabelX, titleLabelY, Kit.LABEL, false);
    }

    @Override
    public void removed() {
        super.removed();
        scope.close();
    }
}
