package com.elpinho.seekerdrones.datagen;

import com.elpinho.seekerdrones.SeekerDrones;
import com.elpinho.seekerdrones.drone.UpgradeItem;
import com.elpinho.seekerdrones.drone.UpgradeType;

import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.model.generators.ItemModelProvider;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

public class ModItemModelProvider extends ItemModelProvider {
    public ModItemModelProvider(PackOutput output, ExistingFileHelper existingFileHelper) {
        super(output, SeekerDrones.MODID, existingFileHelper);
    }

    @Override
    protected void registerModels() {
        // layer1 is tinted with the drone's color (see SeekerDronesClient).
        withExistingParent("drone", ResourceLocation.withDefaultNamespace("item/generated"))
                .texture("layer0", modLoc("item/drone"))
                .texture("layer1", modLoc("item/drone_tint"));

        basicItem(modLoc("drone_rotor"));
        basicItem(modLoc("seeker_core"));

        for (UpgradeType type : UpgradeType.values()) {
            basicItem(modLoc(UpgradeItem.itemName(type)));
        }
    }
}
