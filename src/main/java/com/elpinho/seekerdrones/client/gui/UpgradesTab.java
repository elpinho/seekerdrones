package com.elpinho.seekerdrones.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;

import com.elpinho.seekerdrones.machine.UpgradeSlots;
import com.elpinho.seekerdrones.machine.UpgradeSlots.Accepted;
import com.elpinho.seekerdrones.machine.UpgradeSlots.UpgradeSlot;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * The Upgrades side tab (DESIGN.md section 7.7), for any machine with {@link UpgradeSlots}. Folded, it shows the generic
 * upgrades icon (two upward chevrons). Unfolded, it is a panel with one row per type: the slot
 * (drawn by the screen like any other slot, with a ghost while empty), the type's name and its count and cap. It must be
 * the screen's top tab, since its slots can't move. The screen keeps the slots' visibility in step with
 * {@link #isShown()} and draws ghosts and empty-slot tooltips through this class.
 */
public class UpgradesTab {
    private static final int TAB_HEIGHT = 22;
    /** Two upward chevrons: a generic "machine upgrades" icon, not any one upgrade's item. */
    private static final ResourceLocation ICON = Kit.sprite("icon/upgrades");
    private static final int ICON_WIDTH = 9;
    private static final int ICON_HEIGHT = 8;
    private static final int TEXT_X = 29;
    private static final int TEXT_WIDTH = UpgradeSlots.PANEL_WIDTH - TEXT_X - 5;

    private final List<UpgradeSlot> slots;
    private final BiFunction<Accepted, Integer, Component> effect;
    private final SideTab tab;
    private int mouseY;

    /**
     * @param slots   the menu's upgrade slots, in row order
     * @param visible whether the player may manage the machine's upgrades
     * @param effect  what a type's current count does, for the tooltips
     */
    public UpgradesTab(List<Slot> slots, BooleanSupplier visible, BiFunction<Accepted, Integer, Component> effect) {
        this.slots = slots.stream().map(UpgradeSlot.class::cast).toList();
        this.effect = effect;
        this.tab = new SideTab(TAB_HEIGHT, this::renderIcon)
                .visibleWhen(() -> !this.slots.isEmpty() && visible.getAsBoolean())
                .tooltip(this::tooltip)
                .panel(UpgradeSlots.PANEL_WIDTH, UpgradeSlots.panelHeight(this.slots.size()), this::renderPanel)
                .panelClick(UpgradeSlots.PANEL_HEADER, (mouseX, mouseY, x, y) -> false)
                .passUnusedClicks();
    }

    public SideTab tab() {
        return tab;
    }

    /** Whether the panel is open, so its slots should be drawn and take clicks. */
    public boolean isShown() {
        return tab.isVisible() && tab.isExpanded();
    }

    /** Folds the panel, e.g. when the player loses access. */
    public void fold() {
        tab.setExpanded(false);
    }

    private static Font font() {
        return Minecraft.getInstance().font;
    }

    private void renderIcon(GuiGraphics graphics, int x, int y, int mouseX, int mouseY) {
        graphics.blitSprite(ICON, x + 8, y + 7, ICON_WIDTH, ICON_HEIGHT);
    }

    private void renderPanel(GuiGraphics graphics, int x, int y, int mouseX, int mouseY) {
        this.mouseY = mouseY;
        Font font = font();
        graphics.blitSprite(ICON, x + 8, y + 5, ICON_WIDTH, ICON_HEIGHT);
        graphics.drawString(font, Component.translatable("screen.seekerdrones.upgrades"), x + 21, y + 6, Kit.LABEL, false);
        for (int row = 0; row < slots.size(); row++) {
            UpgradeSlot slot = slots.get(row);
            int rowY = y + UpgradeSlots.PANEL_HEADER + row * UpgradeSlots.ROW_HEIGHT;
            Kit.scrollingText(graphics, font, typeName(slot.getAccepted()), x + TEXT_X, rowY + 4, TEXT_WIDTH, 7, Kit.LABEL, false, true);
            Kit.smallText(graphics, font, countLabel(slot), x + TEXT_X, rowY + 11, Kit.LABEL, false);
        }
    }

    private static Component typeName(Accepted accepted) {
        return Component.translatable(accepted.type().getTranslationKey());
    }

    private static Component countLabel(UpgradeSlot slot) {
        return Component.translatable("screen.seekerdrones.upgrades.value", slot.getItem().getCount(), slot.getAccepted().maxCount());
    }

    private List<Component> tooltip() {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("screen.seekerdrones.upgrades"));
        if (tab.isExpanded()) {
            // Only the header has a tooltip; the slots show their own.
            if (mouseY < tab.getY() + UpgradeSlots.PANEL_HEADER) {
                lines.add(Component.translatable("screen.seekerdrones.upgrades.close").withStyle(ChatFormatting.GRAY));
                return lines;
            }
            return List.of();
        }
        for (UpgradeSlot slot : slots) {
            lines.add(Component.translatable("screen.seekerdrones.upgrades.count", typeName(slot.getAccepted()), slot.getItem().getCount(),
                    slot.getAccepted().maxCount()).withStyle(ChatFormatting.GRAY));
            lines.add(effect.apply(slot.getAccepted(), slot.getItem().getCount()).copy().withStyle(ChatFormatting.GRAY));
        }
        lines.add(Component.translatable("screen.seekerdrones.upgrades.open").withStyle(ChatFormatting.GRAY));
        return lines;
    }

    /** Draws an empty upgrade slot's ghost (item position x, y). Returns false for other slots. */
    public boolean renderGhost(GuiGraphics graphics, Slot slot, int x, int y) {
        if (!(slot instanceof UpgradeSlot upgradeSlot) || !slots.contains(upgradeSlot)) {
            return false;
        }
        if (!slot.hasItem()) {
            Kit.ghost(graphics, new ItemStack(upgradeSlot.getAccepted().item()), x, y);
        }
        return true;
    }

    /** The tooltip for a hovered empty upgrade slot, or nothing for other slots (filled ones show the item's tooltip). */
    public List<Component> emptySlotTooltip(Slot slot) {
        if (!(slot instanceof UpgradeSlot upgradeSlot) || !slots.contains(upgradeSlot) || slot.hasItem()) {
            return List.of();
        }
        Accepted accepted = upgradeSlot.getAccepted();
        return List.of(
                Component.translatable("screen.seekerdrones.upgrades.slot", typeName(accepted)),
                Component.translatable("screen.seekerdrones.upgrades.value", 0, accepted.maxCount()).withStyle(ChatFormatting.GRAY),
                effect.apply(accepted, 0).copy().withStyle(ChatFormatting.GRAY));
    }
}
