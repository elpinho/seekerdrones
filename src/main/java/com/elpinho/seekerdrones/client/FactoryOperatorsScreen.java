package com.elpinho.seekerdrones.client;

import java.util.List;

import com.elpinho.seekerdrones.factory.DroneFactoryMenu;
import com.elpinho.seekerdrones.network.EditFactoryOperatorsPayload;
import com.elpinho.seekerdrones.network.FactoryOperatorsPayload;
import com.elpinho.seekerdrones.network.FactoryOperatorsPayload.Operator;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The Drone Factory's Operator list (DESIGN.md sections 6.1 and 7.1), owner only. It is a separate screen on top of
 * the Factory menu, which stays open on the server: Back returns to the Factory screen, Escape closes both.
 */
public class FactoryOperatorsScreen extends Screen {
    private static final int PANEL_WIDTH = 200;
    private static final int ROW_HEIGHT = 20;
    private static final int VISIBLE_ROWS = 6;
    private static final int PADDING = 8;
    private static final int PANEL_COLOR = 0xE0101018;
    private static final int BORDER_COLOR = 0xFF5A5A70;
    private static final int TEXT_COLOR = 0xFFE0E0E0;

    private final DroneFactoryScreen parent;
    private final DroneFactoryMenu menu;
    private List<Operator> operators = List.of();
    private Component message = Component.empty();
    private int scroll;
    private EditBox nameBox;
    private String pendingName = "";

    public FactoryOperatorsScreen(DroneFactoryScreen parent) {
        super(Component.translatable("screen.seekerdrones.drone_factory.operators.title"));
        this.parent = parent;
        this.menu = parent.getMenu();
        FactoryOperatorsPayload payload = menu.getOperators();
        if (payload != null) {
            operators = payload.operators();
        }
    }

    /** A fresh list from the server, e.g. after an edit. */
    public void update(FactoryOperatorsPayload payload) {
        if (!payload.owner()) {
            // Lost ownership (e.g. the Factory was swapped): nothing to show here.
            minecraft.setScreen(parent);
            return;
        }
        operators = payload.operators();
        payload.message().ifPresent(text -> message = text);
        scroll = Math.clamp(scroll, 0, maxScroll());
        pendingName = nameBox != null ? nameBox.getValue() : "";
        rebuildWidgets();
    }

    private int panelHeight() {
        return PADDING * 2 + 14 + VISIBLE_ROWS * ROW_HEIGHT + 12 + 24 + 24;
    }

    private int left() {
        return (width - PANEL_WIDTH) / 2;
    }

    private int top() {
        return (height - panelHeight()) / 2;
    }

    private int maxScroll() {
        return Math.max(0, operators.size() - VISIBLE_ROWS);
    }

    @Override
    protected void init() {
        int left = left() + PADDING;
        int innerWidth = PANEL_WIDTH - PADDING * 2;
        int y = top() + PADDING + 14;
        for (int i = 0; i < VISIBLE_ROWS && scroll + i < operators.size(); i++) {
            Operator operator = operators.get(scroll + i);
            addRenderableWidget(Button.builder(Component.translatable("screen.seekerdrones.drone_factory.operators.remove"),
                            button -> PacketDistributor.sendToServer(EditFactoryOperatorsPayload.remove(menu.containerId, operator.id())))
                    .bounds(left + innerWidth - 56, y + i * ROW_HEIGHT, 56, 18)
                    .build());
        }
        y += VISIBLE_ROWS * ROW_HEIGHT + 12;
        nameBox = addRenderableWidget(new EditBox(font, left, y, innerWidth - 60, 18,
                Component.translatable("screen.seekerdrones.drone_factory.operators.name")));
        nameBox.setMaxLength(16);
        nameBox.setHint(Component.translatable("screen.seekerdrones.drone_factory.operators.name").withStyle(ChatFormatting.DARK_GRAY));
        nameBox.setValue(pendingName);
        addRenderableWidget(Button.builder(Component.translatable("screen.seekerdrones.drone_factory.operators.add"), button -> submit())
                .bounds(left + innerWidth - 56, y, 56, 18)
                .build());
        y += 24;
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), button -> minecraft.setScreen(parent))
                .bounds(left, y, innerWidth, 18)
                .build());
        setInitialFocus(nameBox);
    }

    private void submit() {
        String name = nameBox.getValue().trim();
        if (!name.isEmpty()) {
            PacketDistributor.sendToServer(EditFactoryOperatorsPayload.add(menu.containerId, name));
            nameBox.setValue("");
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Enter in the name box adds the player.
        if (nameBox.isFocused() && (keyCode == 257 || keyCode == 335)) {
            submit();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int next = Math.clamp(scroll - (int) Math.signum(scrollY), 0, maxScroll());
        if (next != scroll) {
            scroll = next;
            pendingName = nameBox.getValue();
            rebuildWidgets();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void tick() {
        super.tick();
        // The menu was closed by the server (e.g. the Factory was broken or the player walked away).
        if (minecraft.player == null || minecraft.player.containerMenu != menu) {
            minecraft.setScreen(null);
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        int left = left();
        int top = top();
        graphics.fill(left - 1, top - 1, left + PANEL_WIDTH + 1, top + panelHeight() + 1, BORDER_COLOR);
        graphics.fill(left, top, left + PANEL_WIDTH, top + panelHeight(), PANEL_COLOR);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        int x = left() + PADDING;
        int y = top() + PADDING;
        graphics.drawString(font, title.copy().withStyle(ChatFormatting.BOLD), x, y, TEXT_COLOR, false);
        y += 14;
        if (operators.isEmpty()) {
            graphics.drawString(font, Component.translatable("screen.seekerdrones.drone_factory.operators.none").withStyle(ChatFormatting.GRAY),
                    x, y + 5, TEXT_COLOR, false);
        }
        for (int i = 0; i < VISIBLE_ROWS && scroll + i < operators.size(); i++) {
            graphics.drawString(font, operators.get(scroll + i).name(), x, y + i * ROW_HEIGHT + 5, TEXT_COLOR, false);
        }
        if (maxScroll() > 0) {
            String range = (scroll + 1) + "-" + Math.min(operators.size(), scroll + VISIBLE_ROWS) + " / " + operators.size();
            graphics.drawString(font, range, x + PANEL_WIDTH - PADDING * 2 - font.width(range), top() + PADDING, 0xFF909090, false);
        }
        y += VISIBLE_ROWS * ROW_HEIGHT;
        graphics.drawString(font, message, x, y + 1, 0xFFFFD166, false);
    }

    /** Escape closes the Factory menu too, like any container screen. */
    @Override
    public void onClose() {
        if (minecraft.player != null) {
            minecraft.player.closeContainer();
        } else {
            super.onClose();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
