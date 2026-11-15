package com.elpinho.seekerdrones.client;

import com.elpinho.seekerdrones.commandcenter.CommandCenterMenu;
import com.elpinho.seekerdrones.uplink.UplinkMenu;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Shows a station's screen again for the player's open Satellite Uplink or Command Center menu (DESIGN.md sections
 * 7.9 and 7.10), e.g. coming back from a drone's remote screen or feed. The menu, and the map kept with it, stay as
 * they were.
 */
public final class StationScreens {
    private StationScreens() {}

    /** Returns false if no station menu is open any more. */
    public static boolean reopen() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return false;
        }
        if (minecraft.player.containerMenu instanceof UplinkMenu menu) {
            minecraft.setScreen(new UplinkScreen(menu, minecraft.player.getInventory(), Component.translatable("screen.seekerdrones.uplink")));
            return true;
        }
        if (minecraft.player.containerMenu instanceof CommandCenterMenu menu) {
            minecraft.setScreen(new CommandCenterScreen(menu, minecraft.player.getInventory(), Component.translatable("screen.seekerdrones.command_center")));
            return true;
        }
        return false;
    }
}
