package com.elpinho.seekerdrones.client;

import com.elpinho.seekerdrones.SeekerDrones;
import com.elpinho.seekerdrones.client.sound.DroneSounds;
import com.elpinho.seekerdrones.deploying.DeployingStationBlock;
import com.elpinho.seekerdrones.drone.DroneConfig;
import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.energy.EnergyFormat;
import com.elpinho.seekerdrones.energy.EnergyUnit;
import com.elpinho.seekerdrones.registry.ModDataComponents;
import com.elpinho.seekerdrones.registry.ModEntityTypes;
import com.elpinho.seekerdrones.registry.ModItems;
import com.elpinho.seekerdrones.registry.ModMenuTypes;
import com.elpinho.seekerdrones.remote.RemoteLink;

import net.minecraft.util.FastColor;
import net.minecraft.world.item.DyeColor;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = SeekerDrones.MODID, dist = Dist.CLIENT)
public class SeekerDronesClient {
    /** The drone item model's tinted layer. */
    private static final int DRONE_TINT_LAYER = 1;
    /** The Drone Remote item model's tinted layer. */
    private static final int REMOTE_TINT_LAYER = 1;
    /** The Drone Remote's tint while it isn't linked to a drone. */
    private static final int UNLINKED_REMOTE_TINT = 0x7B8490;

    public SeekerDronesClient(IEventBus modEventBus) {
        modEventBus.addListener(SeekerDronesClient::registerRenderers);
        modEventBus.addListener(SeekerDronesClient::registerLayerDefinitions);
        modEventBus.addListener(SeekerDronesClient::registerItemColors);
        modEventBus.addListener(SeekerDronesClient::registerScreens);
        // The unit is saved per world/server, so forget it when leaving one (DESIGN.md section 5.4).
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> EnergyFormat.setClientUnit(EnergyUnit.AUTO));
        DeployingStationBlock.setClientDeployListener(DeployingStationScreen::onDeployed);
        // Drone flying and low-power loops (DESIGN.md section 2.9).
        NeoForge.EVENT_BUS.addListener(DroneSounds::onClientTick);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> DroneSounds.clear());
        DroneEntity.setClientSoundListener(DroneSounds::markDirty);
    }

    private static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenuTypes.DRONE_FACTORY.get(), DroneFactoryScreen::new);
        event.register(ModMenuTypes.PROGRAMMING_STATION.get(), ProgrammingStationScreen::new);
        event.register(ModMenuTypes.DEPLOYING_STATION.get(), DeployingStationScreen::new);
        event.register(ModMenuTypes.CHARGING_STATION.get(), ChargingStationScreen::new);
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
        // The remote takes the color its drone had when it was linked (DESIGN.md section 2.10).
        event.register((stack, tintIndex) -> {
            if (tintIndex != REMOTE_TINT_LAYER) {
                return -1;
            }
            RemoteLink link = stack.get(ModDataComponents.REMOTE_LINK);
            DyeColor color = link != null ? link.color() : null;
            return FastColor.ARGB32.opaque(color != null ? color.getTextureDiffuseColor() : UNLINKED_REMOTE_TINT);
        }, ModItems.DRONE_REMOTE.get());
    }
}
