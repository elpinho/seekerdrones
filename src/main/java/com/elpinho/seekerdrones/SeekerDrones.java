package com.elpinho.seekerdrones;

import org.slf4j.Logger;

import com.elpinho.seekerdrones.command.SeekerDronesCommand;
import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.datagen.SeekerDronesDataGenerators;
import com.elpinho.seekerdrones.network.ModNetwork;
import com.elpinho.seekerdrones.registry.ModBlockEntities;
import com.elpinho.seekerdrones.registry.ModBlocks;
import com.elpinho.seekerdrones.registry.ModCreativeTabs;
import com.elpinho.seekerdrones.registry.ModDataComponents;
import com.elpinho.seekerdrones.registry.ModEntityTypes;
import com.elpinho.seekerdrones.registry.ModItems;
import com.elpinho.seekerdrones.registry.ModMenuTypes;
import com.elpinho.seekerdrones.registry.ModRecipeTypes;
import com.elpinho.seekerdrones.registry.ModSounds;
import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

@Mod(SeekerDrones.MODID)
public class SeekerDrones {
    public static final String MODID = "seekerdrones";
    private static final Logger LOGGER = LogUtils.getLogger();

    public SeekerDrones(IEventBus modEventBus, ModContainer modContainer) {
        ModCreativeTabs.CREATIVE_MODE_TABS.register(modEventBus);
        ModBlocks.BLOCKS.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITY_TYPES.register(modEventBus);
        ModEntityTypes.ENTITY_TYPES.register(modEventBus);
        ModMenuTypes.MENU_TYPES.register(modEventBus);
        ModDataComponents.DATA_COMPONENT_TYPES.register(modEventBus);
        ModRecipeTypes.RECIPE_TYPES.register(modEventBus);
        ModRecipeTypes.RECIPE_SERIALIZERS.register(modEventBus);
        ModSounds.SOUND_EVENTS.register(modEventBus);

        modContainer.registerConfig(ModConfig.Type.SERVER, ServerConfig.SPEC);

        modEventBus.addListener(ModEntityTypes::registerAttributes);
        modEventBus.addListener(ModNetwork::registerPayloads);
        modEventBus.addListener(SeekerDronesDataGenerators::gatherData);

        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> SeekerDronesCommand.register(event.getDispatcher(), event.getBuildContext()));

        LOGGER.info("Seeker Drones initializing");
    }
}
