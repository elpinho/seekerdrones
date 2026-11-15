package com.elpinho.seekerdrones.machine;

import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;

/**
 * Container data made of full ints. Vanilla syncs container data as shorts, so every int is split into two 16-bit
 * halves: the server side wraps a value getter with {@link #server}, the client side keeps a {@link #client} copy, and
 * menus read values back with {@link #read}.
 */
public final class IntMenuData {
    /** Gives the current value of each int, by index. */
    @FunctionalInterface
    public interface Values {
        int get(int index);
    }

    private IntMenuData() {}

    /** The server's read-only view of {@code count} ints. */
    public static ContainerData server(int count, Values values) {
        return new ContainerData() {
            @Override
            public int get(int index) {
                int value = values.get(index / 2);
                return index % 2 == 0 ? value & 0xFFFF : value >>> 16;
            }

            @Override
            public void set(int index, int value) {
                // Read-only on the server; the client copy lives in the menu.
            }

            @Override
            public int getCount() {
                return count * 2;
            }
        };
    }

    /** The client's copy of {@code count} ints, filled by the menu's data sync. */
    public static ContainerData client(int count) {
        return new SimpleContainerData(count * 2);
    }

    /** The int at {@code index}. Each half arrives as a sign-extended short. */
    public static int read(ContainerData data, int index) {
        return (data.get(index * 2 + 1) & 0xFFFF) << 16 | data.get(index * 2) & 0xFFFF;
    }
}
