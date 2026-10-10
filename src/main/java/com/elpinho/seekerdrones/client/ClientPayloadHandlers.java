package com.elpinho.seekerdrones.client;

import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.factory.DroneFactoryMenu;
import com.elpinho.seekerdrones.programming.ProgrammingStationMenu;
import com.elpinho.seekerdrones.network.DroneStatusPayload;
import com.elpinho.seekerdrones.network.FactoryOperatorsPayload;
import com.elpinho.seekerdrones.network.LinkGlowPayload;
import com.elpinho.seekerdrones.network.ProgramTemplatePayload;
import com.elpinho.seekerdrones.network.RemoteStatusPayload;
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

    public static void handleRemoteStatus(RemoteStatusPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (payload.open()) {
            minecraft.setScreen(new DroneRemoteScreen(payload));
        } else if (minecraft.screen instanceof DroneRemoteScreen screen && screen.getDroneId().equals(payload.link().droneId())) {
            screen.update(payload);
        }
    }

    public static void handleLinkGlow(LinkGlowPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null && minecraft.level.getEntity(payload.entityId()) instanceof DroneEntity drone) {
            drone.glowUntil(minecraft.level.getGameTime() + payload.ticks());
        }
    }

    public static void handleStationStatus(StationStatusPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof ChargingStationScreen screen && screen.getPos().equals(payload.pos())) {
            screen.update(payload);
        }
    }

    public static void handleFactoryOperators(FactoryOperatorsPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null && minecraft.player.containerMenu instanceof DroneFactoryMenu menu && menu.containerId == payload.containerId()) {
            menu.setOperators(payload);
            if (minecraft.screen instanceof DroneFactoryScreen screen) {
                screen.onOperatorsUpdated(payload);
            }
        }
    }

    public static void handleProgramTemplate(ProgramTemplatePayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null && minecraft.player.containerMenu instanceof ProgrammingStationMenu menu
                && menu.containerId == payload.containerId()) {
            menu.setTemplate(payload.template());
        }
    }
}
