package com.elpinho.seekerdrones.network;

import com.elpinho.seekerdrones.client.ClientPayloadHandlers;
import com.elpinho.seekerdrones.energy.EnergyFormat;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.DirectionalPayloadHandler;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class ModNetwork {
    private static final String PROTOCOL_VERSION = "6";

    private ModNetwork() {}

    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
        // The lambda keeps ClientPayloadHandlers from loading on a dedicated server.
        registrar.playToClient(DroneStatusPayload.TYPE, DroneStatusPayload.STREAM_CODEC,
                (payload, context) -> ClientPayloadHandlers.handleDroneStatus(payload));
        registrar.playToServer(RequestDroneStatusPayload.TYPE, RequestDroneStatusPayload.STREAM_CODEC,
                RequestDroneStatusPayload::handle);
        registrar.playToClient(StationStatusPayload.TYPE, StationStatusPayload.STREAM_CODEC,
                (payload, context) -> ClientPayloadHandlers.handleStationStatus(payload));
        registrar.playToServer(RequestStationStatusPayload.TYPE, RequestStationStatusPayload.STREAM_CODEC,
                RequestStationStatusPayload::handle);
        registrar.playToClient(FactoryOperatorsPayload.TYPE, FactoryOperatorsPayload.STREAM_CODEC,
                (payload, context) -> ClientPayloadHandlers.handleFactoryOperators(payload));
        registrar.playToServer(EditFactoryOperatorsPayload.TYPE, EditFactoryOperatorsPayload.STREAM_CODEC,
                EditFactoryOperatorsPayload::handle);
        registrar.playToClient(ProgramTemplatePayload.TYPE, ProgramTemplatePayload.STREAM_CODEC,
                (payload, context) -> ClientPayloadHandlers.handleProgramTemplate(payload));
        registrar.playToServer(EditProgramPayload.TYPE, EditProgramPayload.STREAM_CODEC,
                EditProgramPayload::handle);
        registrar.playToClient(RemoteStatusPayload.TYPE, RemoteStatusPayload.STREAM_CODEC,
                (payload, context) -> ClientPayloadHandlers.handleRemoteStatus(payload));
        registrar.playToServer(RequestRemoteStatusPayload.TYPE, RequestRemoteStatusPayload.STREAM_CODEC,
                RequestRemoteStatusPayload::handle);
        registrar.playToServer(RemoteCommandPayload.TYPE, RemoteCommandPayload.STREAM_CODEC,
                RemoteCommandPayload::handle);
        registrar.playToServer(EditRemoteSettingsPayload.TYPE, EditRemoteSettingsPayload.STREAM_CODEC,
                EditRemoteSettingsPayload::handle);
        registrar.playToClient(LinkGlowPayload.TYPE, LinkGlowPayload.STREAM_CODEC,
                (payload, context) -> ClientPayloadHandlers.handleLinkGlow(payload));
        registrar.playToServer(StartFpvFeedPayload.TYPE, StartFpvFeedPayload.STREAM_CODEC, StartFpvFeedPayload::handle);
        registrar.playToServer(StopFpvFeedPayload.TYPE, StopFpvFeedPayload.STREAM_CODEC, StopFpvFeedPayload::handle);
        registrar.playToClient(FpvStartPayload.TYPE, FpvStartPayload.STREAM_CODEC,
                (payload, context) -> ClientPayloadHandlers.handleFpvStart(payload));
        registrar.playToClient(FpvEndPayload.TYPE, FpvEndPayload.STREAM_CODEC,
                (payload, context) -> ClientPayloadHandlers.handleFpvEnd(payload));
        registrar.playToClient(FpvStatusPayload.TYPE, FpvStatusPayload.STREAM_CODEC,
                (payload, context) -> ClientPayloadHandlers.handleFpvStatus(payload));
        registrar.playBidirectional(EnergyUnitPayload.TYPE, EnergyUnitPayload.STREAM_CODEC, new DirectionalPayloadHandler<>(
                (payload, context) -> EnergyFormat.setClientUnit(payload.unit()),
                EnergyUnitPayload::handleOnServer));
    }
}
