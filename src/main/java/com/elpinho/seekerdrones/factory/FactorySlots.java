package com.elpinho.seekerdrones.factory;

import java.util.function.Supplier;

import javax.annotation.Nullable;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * The Drone Factory's slots (DESIGN.md section 7.1): seven inputs laid out like a drone, by role, then the output.
 * Rotor slots 0 to 3 (top left, top right, bottom left, bottom right), the core slot 4, the plating slots 5 (top) and
 * 6 (bottom), and the output slot 7.
 */
public final class FactorySlots {
    public static final int FIRST_ROTOR = 0;
    public static final int ROTOR_SLOTS = 4;
    public static final int CORE = 4;
    public static final int FIRST_PLATING = 5;
    public static final int PLATING_SLOTS = 2;
    public static final int INPUT_SLOTS = 7;
    public static final int OUTPUT = INPUT_SLOTS;
    public static final int COUNT = INPUT_SLOTS + 1;

    private FactorySlots() {}

    public enum Role {
        ROTOR,
        CORE,
        PLATING;

        public String getTranslationKey() {
            return "screen.seekerdrones.drone_factory.role." + name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** The role of an input slot. */
    public static Role role(int slot) {
        if (slot < CORE) {
            return Role.ROTOR;
        }
        return slot == CORE ? Role.CORE : Role.PLATING;
    }

    /**
     * The Factory's item storage. Rotor and core slots hold one item. An input slot only accepts items that some loaded
     * recipe wants there, and nothing can be inserted into the output (the Factory sets it directly).
     */
    public static class Handler extends ItemStackHandler {
        private final Supplier<Level> level;

        public Handler(Supplier<Level> level) {
            super(COUNT);
            this.level = level;
        }

        @Override
        public int getSlotLimit(int slot) {
            return slot < FIRST_PLATING ? 1 : super.getSlotLimit(slot);
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            @Nullable Level current = level.get();
            return slot != OUTPUT && current != null && DroneAssemblyRecipe.accepts(current.getRecipeManager(), slot, stack);
        }
    }
}
