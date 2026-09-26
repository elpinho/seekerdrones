package com.elpinho.seekerdrones.client;

import com.elpinho.seekerdrones.SeekerDrones;
import com.elpinho.seekerdrones.drone.DroneEntity;

import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;

public class DroneRenderer extends MobRenderer<DroneEntity, DroneModel> {
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(SeekerDrones.MODID, "textures/entity/drone.png");

    public DroneRenderer(EntityRendererProvider.Context context) {
        super(context, new DroneModel(context.bakeLayer(DroneModel.LAYER)), 0.3F);
    }

    @Override
    public ResourceLocation getTextureLocation(DroneEntity entity) {
        return TEXTURE;
    }
}
