package com.elpinho.seekerdrones.registry;

import com.elpinho.seekerdrones.SeekerDrones;
import com.elpinho.seekerdrones.drone.UpgradeType;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, SeekerDrones.MODID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> SEEKER_DRONES_TAB = CREATIVE_MODE_TABS.register(
            "seekerdrones_tab",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.seekerdrones"))
                    .withTabsBefore(CreativeModeTabs.COMBAT)
                    .icon(() -> new ItemStack(ModItems.DRONE.get()))
                    .displayItems((parameters, output) -> {
                        output.accept(ModItems.DRONE.get());
                        output.accept(ModItems.DRONE_FACTORY.get());
                        output.accept(ModItems.PROGRAMMING_STATION.get());
                        output.accept(ModItems.CHARGING_STATION.get());
                        output.accept(ModItems.DRONE_ROTOR.get());
                        output.accept(ModItems.SEEKER_CORE.get());
                        for (UpgradeType type : UpgradeType.values()) {
                            output.accept(ModItems.upgrade(type).get());
                        }
                    })
                    .build());
}
