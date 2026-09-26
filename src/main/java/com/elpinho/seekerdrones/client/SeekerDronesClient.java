package com.elpinho.seekerdrones.client;

import com.elpinho.seekerdrones.SeekerDrones;
import com.elpinho.seekerdrones.drone.DroneConfig;
import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.registry.ModDataComponents;
import com.elpinho.seekerdrones.registry.ModEntityTypes;
import com.elpinho.seekerdrones.registry.ModItems;

import net.minecraft.util.FastColor;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;

@Mod(value = SeekerDrones.MODID, dist = Dist.CLIENT)
public class SeekerDronesClient {
    /** The drone item model's tinted layer. */
    private static final int DRONE_TINT_LAYER = 1;

    public SeekerDronesClient(IEventBus modEventBus) {
        modEventBus.addListener(SeekerDronesClient::registerRenderers);
        modEventBus.addListener(SeekerDronesClient::registerLayerDefinitions);
        modEventBus.addListener(SeekerDronesClient::registerItemColors);
    }

    private static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntityTypes.DRONE.get(), DroneRenderer::new);
    }

    private static void registerLayerDefinitions(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(DroneModel.LAYER, DroneModel::createBodyLayer);
    }

    private static void registerItemColors(RegisterColorHandlersEvent.Item event) {
        event.register((stack, tintIndex) -> {
            if (tintIndex != DRONE_TINT_LAYER) {
                return -1;
            }
            DroneData data = stack.get(ModDataComponents.DRONE_DATA);
            return FastColor.ARGB32.opaque((data != null ? data.config().color() : DroneConfig.DEFAULT_COLOR).getTextureDiffuseColor());
        }, ModItems.DRONE.get());
    }
}
