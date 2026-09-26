package com.elpinho.seekerdrones.registry;

import com.elpinho.seekerdrones.SeekerDrones;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, SeekerDrones.MODID);

    // Icon and contents are placeholders until the Drone item exists (M1).
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> SEEKER_DRONES_TAB = CREATIVE_MODE_TABS.register(
            "seekerdrones_tab",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.seekerdrones"))
                    .withTabsBefore(CreativeModeTabs.COMBAT)
                    .icon(() -> new ItemStack(Items.ENDER_EYE))
                    .displayItems((parameters, output) -> {
                        // Populated as drone items and machines are registered in later milestones.
                    })
                    .build());
}
