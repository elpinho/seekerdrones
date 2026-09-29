package com.elpinho.seekerdrones.client;

import java.util.List;

import com.elpinho.seekerdrones.deploying.DeployingStationMenu;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * Deploying Station screen (DESIGN.md section 7.3): the drone slot, the energy bar, the auto-deploy toggle, the Deploy
 * button and a status line. Drawn with plain fills until the M9 polish pass adds textures.
 */
public class DeployingStationScreen extends AbstractContainerScreen<DeployingStationMenu> {
    private static final int PANEL_COLOR = 0xFFC6C6C6;
    private static final int PANEL_BORDER_COLOR = 0xFF555555;
    private static final int SLOT_COLOR = 0xFF8B8B8B;
    private static final int SLOT_BORDER_COLOR = 0xFF373737;
    private static final int BAR_BACKGROUND_COLOR = 0xFF2A2A38;
    private static final int ENERGY_BAR_COLOR = 0xFFD83A2E;
    private static final int LABEL_COLOR = 0xFF404040;

    private static final int ENERGY_X = 8;
    private static final int ENERGY_Y = 18;
    private static final int ENERGY_WIDTH = 10;
    private static final int ENERGY_HEIGHT = 40;
    private static final int BUTTON_X = 56;
    private static final int BUTTON_WIDTH = 112;
    private static final int BUTTON_HEIGHT = 18;
    private static final int TOGGLE_Y = 17;
    private static final int DEPLOY_Y = 39;
    private static final int STATUS_Y = 63;

    private Button toggleButton;
    private Button deployButton;

    public DeployingStationScreen(DeployingStationMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = 176;
        this.imageHeight = 166;
        this.inventoryLabelY = DeployingStationMenu.INVENTORY_Y - 11;
    }

    @Override
    protected void init() {
        super.init();
        toggleButton = addRenderableWidget(Button.builder(Component.empty(), button -> click(DeployingStationMenu.BUTTON_TOGGLE_AUTO_DEPLOY))
                .bounds(leftPos + BUTTON_X, topPos + TOGGLE_Y, BUTTON_WIDTH, BUTTON_HEIGHT)
                .tooltip(Tooltip.create(Component.translatable("screen.seekerdrones.deploying_station.auto_deploy.tooltip")))
                .build());
        deployButton = addRenderableWidget(Button.builder(Component.translatable("screen.seekerdrones.deploying_station.deploy"),
                        button -> click(DeployingStationMenu.BUTTON_DEPLOY))
                .bounds(leftPos + BUTTON_X, topPos + DEPLOY_Y, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build());
        updateButtons();
    }

    private void click(int buttonId) {
        minecraft.gameMode.handleInventoryButtonClick(menu.containerId, buttonId);
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        updateButtons();
    }

    private void updateButtons() {
        boolean autoDeploy = menu.isAutoDeploy();
        toggleButton.setMessage(Component.translatable(autoDeploy
                ? "screen.seekerdrones.deploying_station.auto_deploy.on"
                : "screen.seekerdrones.deploying_station.auto_deploy.off"));
        deployButton.active = !autoDeploy && menu.hasDrone();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
        if (isHovering(ENERGY_X, ENERGY_Y, ENERGY_WIDTH, ENERGY_HEIGHT, mouseX, mouseY)) {
            graphics.renderComponentTooltip(font, List.of(
                    Component.translatable("screen.seekerdrones.deploying_station.energy_value", menu.getEnergy(), menu.getEnergyCapacity()),
                    Component.translatable("screen.seekerdrones.deploying_station.energy_per_deploy", menu.getEnergyPerDeploy())),
                    mouseX, mouseY);
        }
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        graphics.fill(x - 1, y - 1, x + imageWidth + 1, y + imageHeight + 1, PANEL_BORDER_COLOR);
        graphics.fill(x, y, x + imageWidth, y + imageHeight, PANEL_COLOR);
        for (var slot : menu.slots) {
            graphics.fill(x + slot.x - 1, y + slot.y - 1, x + slot.x + 17, y + slot.y + 17, SLOT_BORDER_COLOR);
            graphics.fill(x + slot.x, y + slot.y, x + slot.x + 16, y + slot.y + 16, SLOT_COLOR);
        }

        graphics.fill(x + ENERGY_X - 1, y + ENERGY_Y - 1, x + ENERGY_X + ENERGY_WIDTH + 1, y + ENERGY_Y + ENERGY_HEIGHT + 1, SLOT_BORDER_COLOR);
        graphics.fill(x + ENERGY_X, y + ENERGY_Y, x + ENERGY_X + ENERGY_WIDTH, y + ENERGY_Y + ENERGY_HEIGHT, BAR_BACKGROUND_COLOR);
        int capacity = menu.getEnergyCapacity();
        int energyHeight = capacity <= 0 ? 0 : (int) Math.min(ENERGY_HEIGHT, (long) menu.getEnergy() * ENERGY_HEIGHT / capacity);
        graphics.fill(x + ENERGY_X, y + ENERGY_Y + ENERGY_HEIGHT - energyHeight, x + ENERGY_X + ENERGY_WIDTH, y + ENERGY_Y + ENERGY_HEIGHT,
                ENERGY_BAR_COLOR);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, titleLabelX, titleLabelY, LABEL_COLOR, false);
        graphics.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, LABEL_COLOR, false);
        graphics.drawString(font, Component.translatable("screen.seekerdrones.deploying_station.status",
                Component.translatable(menu.getStatus().getTranslationKey())), ENERGY_X, STATUS_Y, LABEL_COLOR, false);
    }
}
