package com.elpinho.seekerdrones.client;

import com.elpinho.seekerdrones.energy.EnergyFormat;
import com.elpinho.seekerdrones.energy.EnergyUnit;
import com.elpinho.seekerdrones.energy.MekanismEnergy;
import com.elpinho.seekerdrones.network.EnergyUnitPayload;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Cycles the player's energy display unit: Auto, FE, Joules (DESIGN.md section 5.4). The server saves the choice for
 * the player. Hidden without Mekanism, since FE is then the only unit.
 */
public class EnergyUnitButton extends Button {
    public static final int WIDTH = 30;
    public static final int HEIGHT = 14;

    private final Runnable onChange;

    /** {@code onChange} runs after the unit changes, for screens that cache formatted text. */
    public EnergyUnitButton(int x, int y, Runnable onChange) {
        super(x, y, WIDTH, HEIGHT, Component.empty(), button -> ((EnergyUnitButton) button).cycle(), DEFAULT_NARRATION);
        this.onChange = onChange;
        this.visible = MekanismEnergy.isLoaded();
        refresh();
    }

    public EnergyUnitButton(int x, int y) {
        this(x, y, () -> {});
    }

    private void cycle() {
        EnergyUnit next = EnergyFormat.clientUnit().next();
        EnergyFormat.setClientUnit(next);
        PacketDistributor.sendToServer(new EnergyUnitPayload(next));
        refresh();
        onChange.run();
    }

    private void refresh() {
        EnergyUnit unit = EnergyFormat.clientUnit();
        setMessage(Component.translatable(unit.getTranslationKey()));
        setTooltip(Tooltip.create(Component.translatable("screen.seekerdrones.energy_unit.tooltip",
                Component.translatable(unit.getTranslationKey() + ".name"))));
    }
}
