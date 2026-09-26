package com.elpinho.seekerdrones.registry;

import com.elpinho.seekerdrones.SeekerDrones;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModMenuTypes {
    public static final DeferredRegister<MenuType<?>> MENU_TYPES =
            DeferredRegister.create(Registries.MENU, SeekerDrones.MODID);
}
