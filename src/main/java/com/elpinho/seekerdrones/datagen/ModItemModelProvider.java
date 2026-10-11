package com.elpinho.seekerdrones.datagen;

import com.elpinho.seekerdrones.SeekerDrones;
import com.elpinho.seekerdrones.drone.UpgradeItem;
import com.elpinho.seekerdrones.drone.UpgradeType;

import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.neoforged.neoforge.client.model.generators.ItemModelBuilder;
import net.neoforged.neoforge.client.model.generators.ItemModelProvider;
import net.neoforged.neoforge.client.model.generators.loaders.SeparateTransformsModelBuilder;
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

        // A 3D model in the hand and on the ground (drone_remote_3d, made by scripts/textures/generate_textures.py), the
        // sprite in the inventory and item frames. layer1 and the 3D model's tinted faces are tinted with the linked
        // drone's color, gray while unlinked (see SeekerDronesClient).
        ItemModelBuilder sprite = nested().parent(getExistingFile(ResourceLocation.withDefaultNamespace("item/generated")))
                .texture("layer0", modLoc("item/drone_remote"))
                .texture("layer1", modLoc("item/drone_remote_tint"));
        getBuilder("drone_remote")
                .customLoader(SeparateTransformsModelBuilder::begin)
                .base(nested().parent(getExistingFile(modLoc("item/drone_remote_3d"))))
                .perspective(ItemDisplayContext.GUI, sprite)
                .perspective(ItemDisplayContext.FIXED, sprite)
                .end();

        basicItem(modLoc("drone_rotor"));
        basicItem(modLoc("seeker_core"));

        for (UpgradeType type : UpgradeType.values()) {
            basicItem(modLoc(UpgradeItem.itemName(type)));
        }
    }
}
