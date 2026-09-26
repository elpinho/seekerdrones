package com.elpinho.seekerdrones.client;

import com.elpinho.seekerdrones.SeekerDrones;
import com.elpinho.seekerdrones.drone.DroneEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;

/**
 * Placeholder drone model: a flat body with cross arms and four rotors (the frame), plus a canopy (the shell)
 * tinted with the drone's color.
 */
public class DroneModel extends EntityModel<DroneEntity> {
    public static final ModelLayerLocation LAYER =
            new ModelLayerLocation(ResourceLocation.fromNamespaceAndPath(SeekerDrones.MODID, "drone"), "main");

    private final ModelPart frame;
    private final ModelPart shell;
    private int tint = -1;

    public DroneModel(ModelPart root) {
        this.frame = root.getChild("frame");
        this.shell = root.getChild("shell");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        root.addOrReplaceChild("frame", CubeListBuilder.create()
                        .texOffs(0, 0).addBox(-4, 0, -4, 8, 2, 8)
                        .texOffs(0, 10).addBox(-7, 0.5F, -0.5F, 14, 1, 1)
                        .texOffs(0, 12).addBox(-0.5F, 0.5F, -7, 1, 1, 14)
                        .texOffs(32, 10).addBox(-9, 0, -2, 4, 0, 4)
                        .texOffs(32, 10).addBox(5, 0, -2, 4, 0, 4)
                        .texOffs(32, 10).addBox(-2, 0, -9, 4, 0, 4)
                        .texOffs(32, 10).addBox(-2, 0, 5, 4, 0, 4),
                PartPose.offset(0, 20, 0));
        root.addOrReplaceChild("shell", CubeListBuilder.create()
                        .texOffs(32, 0).addBox(-3, -1, -3, 6, 1, 6),
                PartPose.offset(0, 20, 0));
        return LayerDefinition.create(mesh, 64, 32);
    }

    @Override
    public void setupAnim(DroneEntity entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
        tint = FastColor.ARGB32.opaque(entity.getColor().getTextureDiffuseColor());
    }

    @Override
    public void renderToBuffer(PoseStack poseStack, VertexConsumer buffer, int packedLight, int packedOverlay, int color) {
        // Ignore packedOverlay: drones don't get the vanilla red hurt flash (DESIGN.md section 2.5).
        frame.render(poseStack, buffer, packedLight, OverlayTexture.NO_OVERLAY, color);
        shell.render(poseStack, buffer, packedLight, OverlayTexture.NO_OVERLAY, FastColor.ARGB32.multiply(color, tint));
    }
}
