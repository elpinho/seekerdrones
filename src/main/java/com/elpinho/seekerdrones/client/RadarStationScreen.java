package com.elpinho.seekerdrones.client;

import java.util.List;

import com.elpinho.seekerdrones.client.gui.Kit;
import com.elpinho.seekerdrones.client.gui.KitWidget;
import com.elpinho.seekerdrones.client.gui.StatusStrip;
import com.elpinho.seekerdrones.client.radar.RadarMachineScreen;
import com.elpinho.seekerdrones.client.radar.RadarScope;
import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.network.RadarContactsPayload;
import com.elpinho.seekerdrones.network.RadarTerrainPayload;
import com.elpinho.seekerdrones.radar.RadarStationMenu;

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
public class RadarStationScreen extends RadarMachineScreen<RadarStationMenu> {
    /** The display's size: the map at 2 px per block for the default radius (97 blocks), plus its 2 px border. */
    private static final int SCOPE_SIZE = 198;
    private static final int SCOPE_BORDER = 2;
    private static final int WIDTH = MAP_X + SCOPE_SIZE + 8;

    private final RadarScope scope = new RadarScope();

    public RadarStationScreen(RadarStationMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, WIDTH, SCOPE_SIZE);
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
    protected StatusStrip.Status status() {
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
        int x = leftPos + MAP_X;
        int y = topPos + MAP_Y;
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
        Component hovered = scope.hovered(leftPos + MAP_X + SCOPE_BORDER, topPos + MAP_Y + SCOPE_BORDER, inner, time(partialTick),
                sweepTicks(), ServerConfig.get(ServerConfig.RADAR_STATION_RADIUS), mouseX, mouseY);
        if (hovered != null) {
            KitWidget.showTooltip(List.of(hovered));
        }
    }

    private static int sweepTicks() {
        return ServerConfig.get(ServerConfig.RADAR_STATION_SWEEP_TICKS);
    }

    @Override
    public void removed() {
        super.removed();
        scope.close();
    }
}
