package com.elpinho.seekerdrones.network;

import com.elpinho.seekerdrones.client.ClientPayloadHandlers;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class ModNetwork {
    private static final String PROTOCOL_VERSION = "1";

    private ModNetwork() {}

    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
        // The lambda keeps ClientPayloadHandlers from loading on a dedicated server.
        registrar.playToClient(DroneStatusPayload.TYPE, DroneStatusPayload.STREAM_CODEC,
                (payload, context) -> ClientPayloadHandlers.handleDroneStatus(payload));
        registrar.playToServer(RequestDroneStatusPayload.TYPE, RequestDroneStatusPayload.STREAM_CODEC,
                RequestDroneStatusPayload::handle);
    }
}
