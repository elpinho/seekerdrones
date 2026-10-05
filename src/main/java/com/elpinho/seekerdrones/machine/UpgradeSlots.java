package com.elpinho.seekerdrones.machine;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

import com.elpinho.seekerdrones.drone.UpgradeType;
import com.elpinho.seekerdrones.registry.ModItems;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.Slot;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.SlotItemHandler;

/**
 * The upgrades a machine accepts in its Upgrades side tab (DESIGN.md section 7.7): one slot per accepted type, stacking
 * up to that type's cap. The handler is the machine's own and is never part of its item capability, so automation
 * can't reach it. The slots sit in the tab's panel, which is always the top side tab, so their positions are fixed.
 */
public final class UpgradeSlots {
    /** Must match {@code SideTabs}: how far a tab tucks under the frame and the top tab's offset from the frame's top. */
    public static final int TAB_OVERLAP = 4;
    public static final int TAB_FIRST_Y = 6;
    public static final int PANEL_WIDTH = 86;
    public static final int PANEL_HEADER = 16;
    public static final int ROW_HEIGHT = 20;
    private static final int PANEL_PADDING = 4;
    /** The slot box's offset in its row. The item sits one pixel further in. */
    public static final int SLOT_BOX_X = 8;
    public static final int SLOT_BOX_Y = 1;

    /** An upgrade type a machine accepts, with its cap (read from the server config). */
    public record Accepted(UpgradeType type, IntSupplier cap) {
        public Item item() {
            return ModItems.upgrade(type).get();
        }

        public int maxCount() {
            return Math.max(0, cap.getAsInt());
        }
    }

    private UpgradeSlots() {}

    public static int panelHeight(int rows) {
        return PANEL_HEADER + rows * ROW_HEIGHT + PANEL_PADDING;
    }

    /** A machine's upgrade inventory: one slot per accepted type, holding only that type, up to its cap. */
    public static ItemStackHandler createHandler(List<Accepted> accepted, Runnable onChanged) {
        return new ItemStackHandler(accepted.size()) {
            @Override
            public int getSlotLimit(int slot) {
                return slot < accepted.size() ? accepted.get(slot).maxCount() : 0;
            }

            @Override
            public boolean isItemValid(int slot, ItemStack stack) {
                return slot < accepted.size() && stack.is(accepted.get(slot).item());
            }

            @Override
            protected void onContentsChanged(int slot) {
                onChanged.run();
            }
        };
    }

    /**
     * The menu slots for the tab's panel, placed for a frame {@code frameWidth} wide. They are only active (drawn and
     * clickable) while {@code shown} is true, i.e. while the tab is unfolded on the client.
     */
    public static List<Slot> createSlots(IItemHandler handler, List<Accepted> accepted, int frameWidth, BooleanSupplier shown) {
        List<Slot> slots = new ArrayList<>();
        for (int row = 0; row < accepted.size(); row++) {
            int x = frameWidth - TAB_OVERLAP + SLOT_BOX_X + 1;
            int y = TAB_FIRST_Y + PANEL_HEADER + row * ROW_HEIGHT + SLOT_BOX_Y + 1;
            slots.add(new UpgradeSlot(handler, row, x, y, accepted.get(row), shown));
        }
        return slots;
    }

    public static class UpgradeSlot extends SlotItemHandler {
        private final Accepted accepted;
        private final BooleanSupplier shown;

        UpgradeSlot(IItemHandler handler, int index, int x, int y, Accepted accepted, BooleanSupplier shown) {
            super(handler, index, x, y);
            this.accepted = accepted;
            this.shown = shown;
        }

        public Accepted getAccepted() {
            return accepted;
        }

        @Override
        public boolean isActive() {
            return shown.getAsBoolean();
        }
    }
}
