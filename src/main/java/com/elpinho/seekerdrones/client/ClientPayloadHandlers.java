package com.elpinho.seekerdrones.client;

import com.elpinho.seekerdrones.network.DroneStatusPayload;

import net.minecraft.client.Minecraft;

public final class ClientPayloadHandlers {
    private ClientPayloadHandlers() {}

    public static void handleDroneStatus(DroneStatusPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (payload.open()) {
            minecraft.setScreen(new DroneStatusScreen(payload));
        } else if (minecraft.screen instanceof DroneStatusScreen screen && screen.getEntityId() == payload.entityId()) {
            screen.update(payload);
        }
    }
}
