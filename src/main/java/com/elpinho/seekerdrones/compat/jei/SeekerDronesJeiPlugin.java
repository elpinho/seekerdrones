package com.elpinho.seekerdrones.compat.jei;

import com.elpinho.seekerdrones.SeekerDrones;
import java.util.List;

import com.elpinho.seekerdrones.client.DroneFactoryScreen;
import com.elpinho.seekerdrones.client.gui.MachineScreen;
import com.elpinho.seekerdrones.factory.DroneFactoryBlockEntity;
import com.elpinho.seekerdrones.factory.DroneFactoryMenu;
import com.elpinho.seekerdrones.registry.ModItems;
import com.elpinho.seekerdrones.registry.ModMenuTypes;
import com.elpinho.seekerdrones.registry.ModRecipeTypes;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.handlers.IGuiContainerHandler;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import mezz.jei.api.registration.IRecipeTransferRegistration;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.resources.ResourceLocation;

/**
 * JEI integration. Crafting recipes show up on their own; this adds the Drone Factory's {@code drone_assembly} recipes.
 * JEI is optional: this class is only loaded when JEI finds it.
 */
@JeiPlugin
public class SeekerDronesJeiPlugin implements IModPlugin {
    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(SeekerDrones.MODID, "jei_plugin");

    @Override
    public ResourceLocation getPluginUid() {
        return ID;
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration registration) {
        registration.addRecipeCategories(new DroneAssemblyCategory(registration.getJeiHelpers().getGuiHelper()));
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        var level = Minecraft.getInstance().level;
        if (level != null) {
            registration.addRecipes(DroneAssemblyCategory.TYPE,
                    level.getRecipeManager().getAllRecipesFor(ModRecipeTypes.DRONE_ASSEMBLY.get()));
        }
    }

    @Override
    public void registerRecipeCatalysts(IRecipeCatalystRegistration registration) {
        registration.addRecipeCatalyst(ModItems.DRONE_FACTORY.get(), DroneAssemblyCategory.TYPE);
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        // Keep JEI's overlays clear of the machine screens' side tabs.
        registration.addGenericGuiContainerHandler(MachineScreen.class, new IGuiContainerHandler<MachineScreen<?>>() {
            @Override
            public List<Rect2i> getGuiExtraAreas(MachineScreen<?> screen) {
                return screen.getSideTabAreas();
            }
        });
        registration.addRecipeClickArea(DroneFactoryScreen.class, DroneFactoryScreen.PROGRESS_X, DroneFactoryScreen.PROGRESS_Y - 4,
                DroneFactoryScreen.PROGRESS_WIDTH, DroneFactoryScreen.PROGRESS_HEIGHT + 8, DroneAssemblyCategory.TYPE);
    }

    @Override
    public void registerRecipeTransferHandlers(IRecipeTransferRegistration registration) {
        registration.addRecipeTransferHandler(DroneFactoryMenu.class, ModMenuTypes.DRONE_FACTORY.get(), DroneAssemblyCategory.TYPE,
                0, DroneFactoryBlockEntity.INPUT_SLOTS, DroneFactoryBlockEntity.SLOT_COUNT, 36);
    }
}
