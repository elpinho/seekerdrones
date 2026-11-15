package com.elpinho.seekerdrones.client.radar;

import java.util.List;
import java.util.function.Supplier;

import com.elpinho.seekerdrones.client.gui.Gauges;
import com.elpinho.seekerdrones.client.gui.Kit;
import com.elpinho.seekerdrones.client.gui.MachineScreen;
import com.elpinho.seekerdrones.client.gui.SideTab;
import com.elpinho.seekerdrones.client.gui.StatusStrip;
import com.elpinho.seekerdrones.energy.EnergyFormat;
import com.elpinho.seekerdrones.radar.RadarMachineMenu;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * The radar machines' shared screen (DESIGN.md sections 7.8 and 7.9): no inventory, the energy gauge on the left (as
 * tall as the map, with the FE/t use in its tooltip), a status strip along the bottom and the energy unit side tab.
 */
public abstract class RadarMachineScreen<M extends RadarMachineMenu> extends MachineScreen<M> {
    protected static final int GAUGE_X = 8;
    protected static final int GAUGE_WIDTH = 12;
    protected static final int MAP_X = 24;
    protected static final int MAP_Y = 16;

    private final int mapHeight;

    protected RadarMachineScreen(M menu, Inventory inventory, Component title, int width, int mapHeight) {
        // No inventory: its label is never drawn (see renderLabels).
        super(menu, inventory, title, width, stripY(mapHeight) + StatusStrip.HEIGHT + 7, stripY(mapHeight) + StatusStrip.HEIGHT + 7);
        this.mapHeight = mapHeight;
        sideTabs.add(SideTab.energyUnit());
    }

    protected static int stripY(int mapHeight) {
        return MAP_Y + mapHeight + 4;
    }

    /** The status strip's contents, every frame. */
    protected abstract StatusStrip.Status status();

    @Override
    protected void init() {
        super.init();
        addRenderableWidget(new Gauges.Energy(leftPos + GAUGE_X, topPos + MAP_Y, GAUGE_WIDTH, mapHeight,
                () -> Kit.fraction(menu.getEnergy(), menu.getEnergyCapacity())))
                .tooltip(energyTooltip());
        addRenderableWidget(new StatusStrip(leftPos + 8, topPos + stripY(mapHeight), imageWidth - 16, this::status));
    }

    private Supplier<List<Component>> energyTooltip() {
        return () -> List.of(
                Component.translatable("screen.seekerdrones.energy"),
                Component.literal(EnergyFormat.ratio(menu.getEnergy(), menu.getEnergyCapacity())).withStyle(ChatFormatting.GRAY),
                Component.translatable("screen.seekerdrones.radar_station.energy_use",
                        EnergyFormat.rate(menu.getEnergyPerTick())).withStyle(ChatFormatting.GRAY));
    }

    /** The world time in ticks, with the partial tick. */
    protected double time(float partialTick) {
        return minecraft.level.getGameTime() + partialTick;
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, titleLabelX, titleLabelY, Kit.LABEL, false);
    }
}
