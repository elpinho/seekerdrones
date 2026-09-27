package com.elpinho.seekerdrones.client;

import com.elpinho.seekerdrones.network.DroneStatusPayload;
import com.elpinho.seekerdrones.network.StationStatusPayload;

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

    public static void handleStationStatus(StationStatusPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (payload.open()) {
            minecraft.setScreen(new ChargingStationScreen(payload));
        } else if (minecraft.screen instanceof ChargingStationScreen screen && screen.getPos().equals(payload.pos())) {
            screen.update(payload);
        }
    }
}
