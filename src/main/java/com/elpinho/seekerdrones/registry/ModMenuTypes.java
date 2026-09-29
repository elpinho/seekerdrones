package com.elpinho.seekerdrones.registry;

import com.elpinho.seekerdrones.SeekerDrones;
import com.elpinho.seekerdrones.deploying.DeployingStationMenu;
import com.elpinho.seekerdrones.factory.DroneFactoryMenu;
import com.elpinho.seekerdrones.programming.ProgrammingStationMenu;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModMenuTypes {
    public static final DeferredRegister<MenuType<?>> MENU_TYPES =
            DeferredRegister.create(Registries.MENU, SeekerDrones.MODID);

    public static final DeferredHolder<MenuType<?>, MenuType<DroneFactoryMenu>> DRONE_FACTORY =
            MENU_TYPES.register("drone_factory", () -> IMenuTypeExtension.create(DroneFactoryMenu::new));
    public static final DeferredHolder<MenuType<?>, MenuType<ProgrammingStationMenu>> PROGRAMMING_STATION =
            MENU_TYPES.register("programming_station", () -> IMenuTypeExtension.create(ProgrammingStationMenu::new));
    public static final DeferredHolder<MenuType<?>, MenuType<DeployingStationMenu>> DEPLOYING_STATION =
            MENU_TYPES.register("deploying_station", () -> IMenuTypeExtension.create(DeployingStationMenu::new));
}
