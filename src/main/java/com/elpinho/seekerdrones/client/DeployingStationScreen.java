package com.elpinho.seekerdrones.client;

import java.util.ArrayList;
import java.util.List;

import com.elpinho.seekerdrones.client.gui.Gauges;
import com.elpinho.seekerdrones.client.gui.Kit;
import com.elpinho.seekerdrones.client.gui.KitWidget;
import com.elpinho.seekerdrones.client.gui.MachineScreen;
import com.elpinho.seekerdrones.client.gui.SideTab;
import com.elpinho.seekerdrones.client.gui.StatusStrip;
import com.elpinho.seekerdrones.client.gui.ToggleSwitch;
import com.elpinho.seekerdrones.deploying.DeployingStationBlock;
import com.elpinho.seekerdrones.deploying.DeployingStationMenu;
import com.elpinho.seekerdrones.deploying.DeployingStatus;
import com.elpinho.seekerdrones.energy.EnergyFormat;

import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Items;

/**
 * Deploying Station screen (DESIGN.md section 7.3): a side view of the launch shaft with the drone slot on the pad, a
 * big launch button under a safety cover that stays closed while auto-deploy is on, an Auto/Manual switch, the energy
 * gauge, a status strip, and the energy unit and Redstone side tabs. The launch animation is client-side only: it
 * plays when the server's deploy block event arrives.
 */
public class DeployingStationScreen extends MachineScreen<DeployingStationMenu> {
    private static final int WIDTH = 176;
    private static final int HEIGHT = 194;

    // The launch shaft display, and its parts relative to it.
    private static final int SHAFT_X = 24;
    private static final int SHAFT_Y = 16;
    private static final int SHAFT_WIDTH = 94;
    private static final int SHAFT_HEIGHT = 66;
    private static final int[] RAIL_X = {29, 61};
    private static final int[] LIGHT_X = {27, 63};
    private static final int LIGHTS_PER_RAIL = 6;
    private static final int HOVER_X = 37;
    private static final int HOVER_Y = 6;
    /** Where the drone rises to after launch: a faint drone shows there while one is ready. */
    private static final int GHOST_X = 38;
    private static final int GHOST_Y = -2;
    private static final int BLOCK_X = 38;
    private static final int BLOCK_Y = 15;
    private static final int PAD_X = 22;
    private static final int PAD_Y = 56;
    private static final int LAUNCH_MILLIS = 900;

    private static final int HOUSING_X = 122;
    private static final int HOUSING_Y = 16;
    private static final int BUTTON_X = HOUSING_X + 8;
    private static final int BUTTON_Y = HOUSING_Y + 7;
    private static final int BUTTON_SIZE = 30;

    private static final int RAIL_COLOR = 0xFF2C3640;
    private static final int LIGHT_OFF = 0xFF1D2A30;
    private static final int LIGHT_OK = 0xFF4ADE80;
    private static final int LIGHT_WARN = 0xFFFBBF24;
    private static final int LIGHT_BAD = 0xFFF45B5B;
    private static final int HOVER_COLOR = 0xFF3F6F6A;
    private static final int GHOST_FADE = 0xD0101A22;
    private static final int PUFF_COLOR = 0xFFDFE6EA;
    private static final int COVER_LABEL = 0xFFD6D6D6;

    private static final ResourceLocation SHAFT = Kit.sprite("deploying/shaft");
    private static final ResourceLocation HAZARD = Kit.sprite("deploying/hazard");
    private static final ResourceLocation HOUSING = Kit.sprite("deploying/housing");
    private static final ResourceLocation PAD = Kit.sprite("deploying/pad");
    private static final ResourceLocation LAUNCH_BUTTON = Kit.sprite("deploying/launch_button");
    private static final ResourceLocation LAUNCH_BUTTON_PRESSED = Kit.sprite("deploying/launch_button_pressed");
    private static final ResourceLocation LAUNCH_BUTTON_DISABLED = Kit.sprite("deploying/launch_button_disabled");
    private static final ResourceLocation COVER = Kit.sprite("deploying/cover");
    private static final ResourceLocation COVER_OPEN = Kit.sprite("deploying/cover_open");
    private static final ResourceLocation REDSTONE = Kit.sprite("icon/redstone");
    private static final ResourceLocation REDSTONE_OFF = Kit.sprite("icon/redstone_off");

    /** The last drone seen in the slot, drawn as the ghost at the hover point and in the launch animation. */
    private ItemStack lastDrone = ItemStack.EMPTY;
    /** When the last launch started, or 0 if none is playing. */
    private long launchStart;

    public DeployingStationScreen(DeployingStationMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, WIDTH, HEIGHT, DeployingStationMenu.INVENTORY_Y);
        sideTabs.add(SideTab.energyUnit());
        sideTabs.add(new SideTab(22, (graphics, x, y, mouseX, mouseY) ->
                graphics.blitSprite(isSignalUsed() ? REDSTONE : REDSTONE_OFF, x + 8, y + 7, 8, 7))
                .tooltip(this::redstoneTooltip));
    }

    /** Plays the launch animation if this screen shows the station at {@code pos}. Called for every deploy block event. */
    public static void onDeployed(BlockPos pos) {
        if (Minecraft.getInstance().screen instanceof DeployingStationScreen screen && screen.menu.getPos().equals(pos)) {
            screen.launchStart = Util.getMillis();
        }
    }

    @Override
    protected void init() {
        super.init();
        addRenderableWidget(new Gauges.Energy(leftPos + 8, topPos + 16, 12, 66,
                () -> Kit.fraction(menu.getEnergy(), menu.getEnergyCapacity())))
                .tooltip(() -> List.of(
                        Component.translatable("screen.seekerdrones.energy"),
                        Component.literal(EnergyFormat.ratio(menu.getEnergy(), menu.getEnergyCapacity())).withStyle(ChatFormatting.GRAY),
                        Component.translatable("screen.seekerdrones.deploying_station.energy_per_deploy",
                                EnergyFormat.amount(menu.getEnergyPerDeploy())).withStyle(ChatFormatting.GRAY)));
        addRenderableWidget(new LaunchButton(leftPos + HOUSING_X, topPos + HOUSING_Y, 48, 50));
        addRenderableWidget(new ToggleSwitch(leftPos + HOUSING_X, topPos + 70, 48, 12, menu::isAutoDeploy,
                Component.translatable("screen.seekerdrones.deploying_station.auto"),
                Component.translatable("screen.seekerdrones.deploying_station.manual"),
                () -> click(DeployingStationMenu.BUTTON_TOGGLE_AUTO_DEPLOY)))
                .tooltip(() -> List.of(
                        Component.translatable("screen.seekerdrones.deploying_station.auto_deploy"),
                        Component.translatable("screen.seekerdrones.deploying_station.auto_deploy.tooltip").withStyle(ChatFormatting.GRAY)));
        addRenderableWidget(new StatusStrip(leftPos + 8, topPos + 86, 160, this::status));
    }

    private void click(int buttonId) {
        minecraft.gameMode.handleInventoryButtonClick(menu.containerId, buttonId);
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        ItemStack drone = menu.getSlot(0).getItem();
        if (!drone.isEmpty()) {
            lastDrone = drone.copyWithCount(1);
        }
    }

    /** Redstone only matters with auto-deploy off. The station's TRIGGERED state is its current signal. */
    private boolean isSignalUsed() {
        return !menu.isAutoDeploy() && isPowered();
    }

    private boolean isPowered() {
        var state = minecraft.level.getBlockState(menu.getPos());
        return state.hasProperty(DeployingStationBlock.TRIGGERED) && state.getValue(DeployingStationBlock.TRIGGERED);
    }

    private List<Component> redstoneTooltip() {
        return List.of(
                Component.translatable("screen.seekerdrones.deploying_station.redstone"),
                Component.translatable("screen.seekerdrones.deploying_station.redstone.manual").withStyle(ChatFormatting.GRAY),
                Component.translatable("screen.seekerdrones.deploying_station.redstone.auto").withStyle(ChatFormatting.GRAY),
                Component.translatable("screen.seekerdrones.deploying_station.redstone.signal", Component.translatable(isPowered()
                        ? "screen.seekerdrones.deploying_station.redstone.on"
                        : "screen.seekerdrones.deploying_station.redstone.off")));
    }

    private StatusStrip.Status status() {
        DeployingStatus status = menu.getStatus();
        Kit.Light light = switch (status) {
            case IDLE -> Kit.Light.IDLE;
            case READY -> Kit.Light.OK;
            case NO_ENERGY -> Kit.Light.WARN;
            case BLOCKED -> Kit.Light.BAD;
        };
        Component hint = status == DeployingStatus.NO_ENERGY
                ? Component.translatable(status.getHintKey(), EnergyFormat.amount(menu.getEnergyPerDeploy()))
                : Component.translatable(status.getHintKey());
        Component text = Component.literal(Kit.upper(Component.translatable(status.getTranslationKey())) + " · ").append(hint);
        return new StatusStrip.Status(light, status == DeployingStatus.BLOCKED, text);
    }

    private boolean isLaunching() {
        return launchStart != 0 && Util.getMillis() - launchStart < LAUNCH_MILLIS;
    }

    /** The launch button works with auto-deploy off and a drone that can go now. */
    private boolean canLaunch() {
        return !menu.isAutoDeploy() && menu.hasDrone() && menu.getStatus() == DeployingStatus.READY;
    }

    @Override
    protected void renderContents(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        renderShaft(graphics, leftPos + SHAFT_X, topPos + SHAFT_Y);
        graphics.blitSprite(HOUSING, leftPos + HOUSING_X, topPos + HOUSING_Y, 48, 50);
        graphics.blitSprite(HAZARD, leftPos + HOUSING_X + 1, topPos + HOUSING_Y + 1, 46, 3);
    }

    private void renderShaft(GuiGraphics graphics, int x, int y) {
        graphics.blitSprite(SHAFT, x, y, SHAFT_WIDTH, SHAFT_HEIGHT);
        DeployingStatus status = menu.getStatus();
        boolean blocked = status == DeployingStatus.BLOCKED;
        long millis = Util.getMillis();
        boolean launching = isLaunching();

        for (int railX : RAIL_X) {
            graphics.fill(x + railX, y + 4, x + railX + 2, y + 56, RAIL_COLOR);
        }
        // Chase lights run up the rails while a drone is ready, blink red while the space is blocked, glow amber while
        // waiting for energy, and flash during a launch.
        for (int i = 0; i < LIGHTS_PER_RAIL; i++) {
            int color = LIGHT_OFF;
            if (launching) {
                color = millis / 60 % 2 == 0 ? LIGHT_OK : LIGHT_OFF;
            } else if (blocked) {
                color = millis / 500 % 2 == 0 ? LIGHT_BAD : LIGHT_OFF;
            } else if (status == DeployingStatus.NO_ENERGY) {
                color = i == 0 ? LIGHT_WARN : LIGHT_OFF;
            } else if (menu.hasDrone()) {
                color = millis / 140 % LIGHTS_PER_RAIL == i ? LIGHT_OK : LIGHT_OFF;
            }
            for (int lightX : LIGHT_X) {
                int lightY = y + 50 - i * 9;
                graphics.fill(x + lightX, lightY, x + lightX + 2, lightY + 2, color);
            }
        }

        // The hover point, as a dashed line.
        int hoverColor = blocked ? LIGHT_BAD : HOVER_COLOR;
        for (int dx = 0; dx < 18; dx += 3) {
            graphics.fill(x + HOVER_X + dx, y + HOVER_Y, x + HOVER_X + dx + 2, y + HOVER_Y + 1, hoverColor);
        }

        // The drone slot's box sits on the pad. It's drawn here rather than with the other slots, so the launching
        // drone rises in front of it.
        Kit.slot(graphics, leftPos + DeployingStationMenu.DRONE_X - 1, topPos + DeployingStationMenu.DRONE_Y - 1);

        graphics.enableScissor(x + 1, y + 1, x + SHAFT_WIDTH - 1, y + SHAFT_HEIGHT - 1);
        if (blocked) {
            graphics.renderItem(new ItemStack(Items.STONE), x + BLOCK_X, y + BLOCK_Y);
            graphics.pose().pushPose();
            graphics.pose().translate(0, 0, 200);
            for (int i = 0; i < 12; i++) {
                graphics.fill(x + BLOCK_X + 2 + i, y + BLOCK_Y + 2 + i, x + BLOCK_X + 4 + i, y + BLOCK_Y + 4 + i, LIGHT_BAD);
                graphics.fill(x + BLOCK_X + 13 - i, y + BLOCK_Y + 2 + i, x + BLOCK_X + 15 - i, y + BLOCK_Y + 4 + i, LIGHT_BAD);
            }
            graphics.pose().popPose();
        } else if (menu.hasDrone() && !launching) {
            graphics.renderItem(menu.getSlot(0).getItem(), x + GHOST_X, y + GHOST_Y);
            graphics.pose().pushPose();
            graphics.pose().translate(0, 0, 200);
            graphics.fill(x + GHOST_X, y + GHOST_Y, x + GHOST_X + 16, y + GHOST_Y + 16, GHOST_FADE);
            graphics.pose().popPose();
        }
        if (launching) {
            renderLaunch(graphics, x, y, millis);
        }
        graphics.disableScissor();

        graphics.blitSprite(PAD, x + PAD_X, y + PAD_Y, 48, 8);
    }

    @Override
    protected void renderSlotBackground(GuiGraphics graphics, Slot slot, int x, int y) {
        if (slot.index != 0) {
            super.renderSlotBackground(graphics, slot, x, y);
        }
    }

    /** The drone rises from the pad with an ease-out while a ring of puffs spreads from under it. */
    private void renderLaunch(GuiGraphics graphics, int x, int y, long millis) {
        float progress = Math.min(1, (millis - launchStart) / (float) LAUNCH_MILLIS);
        float eased = 1 - (1 - progress) * (1 - progress) * (1 - progress);
        if (!lastDrone.isEmpty()) {
            int droneY = Math.round(39 - eased * 41);
            graphics.renderItem(lastDrone, x + 38, y + droneY);
        }
        int alpha = Math.round(Math.max(0, 0.8F - progress) * 255);
        if (alpha <= 0) {
            return;
        }
        int color = alpha << 24 | (PUFF_COLOR & 0xFFFFFF);
        for (int i = 0; i < 6; i++) {
            double angle = i / 6.0 * Math.PI;
            float radius = 4 + progress * 14;
            float puffX = 46 + (float) Math.cos(angle) * radius * (i % 2 == 1 ? 1 : -1);
            float puffY = 54 - (float) Math.sin(angle) * radius * 0.35F;
            int px = x + Mth.floor(puffX) - 1;
            int py = y + Mth.floor(puffY) - 1;
            graphics.fill(px, py, px + 3, py + 3, color);
        }
    }

    /**
     * The launch button in its housing, with the safety cover. With auto-deploy on, the cover is closed and says AUTO.
     * Clicking it opens the cover (switches to manual). With the cover open, the button deploys.
     */
    private class LaunchButton extends KitWidget {
        /** The button shows pressed for a moment after a click. */
        private static final long PRESS_MILLIS = 150;
        private long pressedAt;

        LaunchButton(int x, int y, int width, int height) {
            super(x, y, width, height);
            tooltip(this::lines);
        }

        private List<Component> lines() {
            List<Component> lines = new ArrayList<>();
            lines.add(Component.translatable("screen.seekerdrones.deploying_station.deploy"));
            if (menu.isAutoDeploy()) {
                lines.add(Component.translatable("screen.seekerdrones.deploying_station.deploy.auto").withStyle(ChatFormatting.RED));
                lines.add(Component.translatable("screen.seekerdrones.deploying_station.deploy.open_cover").withStyle(ChatFormatting.GRAY));
            } else {
                lines.add(Component.translatable("screen.seekerdrones.deploying_station.deploy.tooltip").withStyle(ChatFormatting.GRAY));
                lines.add(Component.translatable("screen.seekerdrones.deploying_station.energy_per_deploy",
                        EnergyFormat.amount(menu.getEnergyPerDeploy())).withStyle(ChatFormatting.GRAY));
            }
            return lines;
        }

        private boolean isOverButton(double mouseX, double mouseY) {
            double dx = mouseX - (leftPos + BUTTON_X + BUTTON_SIZE / 2.0);
            double dy = mouseY - (topPos + BUTTON_Y + BUTTON_SIZE / 2.0);
            return dx * dx + dy * dy <= BUTTON_SIZE * BUTTON_SIZE / 4.0;
        }

        @Override
        protected void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int buttonX = leftPos + BUTTON_X;
            int buttonY = topPos + BUTTON_Y;
            boolean auto = menu.isAutoDeploy();
            ResourceLocation sprite = !canLaunch() ? LAUNCH_BUTTON_DISABLED : Util.getMillis() - pressedAt < PRESS_MILLIS ? LAUNCH_BUTTON_PRESSED : LAUNCH_BUTTON;
            graphics.blitSprite(sprite, buttonX, buttonY, BUTTON_SIZE, BUTTON_SIZE + 2);
            var font = Minecraft.getInstance().font;
            Kit.smallTextCentered(graphics, font, Component.literal(Kit.upper(Component.translatable("screen.seekerdrones.deploying_station.deploy"))),
                    getX() + 24, getY() + 40, COVER_LABEL);
            if (auto) {
                Kit.blitTranslucent(graphics, COVER, getX() + 4, getY() + 4, 38, 34);
                Kit.smallTextCentered(graphics, font, Component.literal(Kit.upper(Component.translatable("screen.seekerdrones.deploying_station.auto"))),
                        getX() + 23, getY() + 29, Kit.WHITE);
            } else {
                Kit.blitTranslucent(graphics, COVER_OPEN, getX() + 4, getY() - 2, 38, 5);
            }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button != 0 || !isMouseOver(mouseX, mouseY)) {
                return false;
            }
            if (menu.isAutoDeploy()) {
                // The closed cover: lifting it switches to manual.
                playDownSound(Minecraft.getInstance().getSoundManager());
                click(DeployingStationMenu.BUTTON_TOGGLE_AUTO_DEPLOY);
                return true;
            }
            if (canLaunch() && isOverButton(mouseX, mouseY)) {
                pressedAt = Util.getMillis();
                playDownSound(Minecraft.getInstance().getSoundManager());
                click(DeployingStationMenu.BUTTON_DEPLOY);
                return true;
            }
            return false;
        }
    }
}
