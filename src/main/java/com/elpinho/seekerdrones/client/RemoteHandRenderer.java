package com.elpinho.seekerdrones.client;

import com.elpinho.seekerdrones.registry.ModItems;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.common.asm.enumextension.EnumProxy;
import net.neoforged.neoforge.client.IArmPoseTransformer;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;

/**
 * Holds the Drone Remote like a real RC controller: in both hands, in front of the chest, when it's in the main hand
 * and the off hand is empty (like a map). Otherwise it's held by one grip, with the model's own hand transforms.
 */
public final class RemoteHandRenderer {
    /** Both arms forward and in, the hands on the remote's grips, following the head a little. */
    public static final EnumProxy<HumanoidModel.ArmPose> ARM_POSE = new EnumProxy<>(HumanoidModel.ArmPose.class, true,
            (IArmPoseTransformer) (model, entity, arm) -> {
                float pitch = Mth.clamp(model.head.xRot, -0.8F, 0.8F) * 0.5F;
                model.rightArm.xRot = -1.0F + pitch;
                model.leftArm.xRot = -1.0F + pitch;
                model.rightArm.yRot = -0.2F + model.head.yRot;
                model.leftArm.yRot = 0.2F + model.head.yRot;
            });

    private RemoteHandRenderer() {
    }

    public static void registerClientExtensions(RegisterClientExtensionsEvent event) {
        event.registerItem(new IClientItemExtensions() {
            @Override
            public HumanoidModel.ArmPose getArmPose(LivingEntity entity, InteractionHand hand, ItemStack stack) {
                return isHeldInBothHands(entity, hand) ? ARM_POSE.getValue() : null;
            }
        }, ModItems.DRONE_REMOTE.get());
    }

    private static boolean isHeldInBothHands(LivingEntity entity, InteractionHand hand) {
        return hand == InteractionHand.MAIN_HAND && entity.getOffhandItem().isEmpty();
    }

    /** First person: draws both arms and the remote centered in front of the camera, in place of the main hand. */
    public static void onRenderHand(RenderHandEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        AbstractClientPlayer player = minecraft.player;
        if (player == null || !event.getItemStack().is(ModItems.DRONE_REMOTE.get()) || !isHeldInBothHands(player, event.getHand())) {
            return;
        }
        event.setCanceled(true);
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource buffers = event.getMultiBufferSource();
        int light = event.getPackedLight();

        // Moves like a map held in both hands (ItemInHandRenderer.renderTwoHandedMap): held low while looking ahead,
        // raised to face the camera while looking down.
        float swing = event.getSwingProgress();
        float swingRoot = Mth.sqrt(swing);
        poseStack.pushPose();
        poseStack.translate(0.0F, 0.1F * Mth.sin(swing * Mth.PI), -0.4F * Mth.sin(swingRoot * Mth.PI));
        float tilt = tilt(event.getInterpolatedPitch());
        poseStack.translate(0.0F, 0.04F + event.getEquipProgress() * -1.2F + tilt * -0.4F, -0.72F);
        poseStack.mulPose(Axis.XP.rotationDegrees(tilt * -85.0F));
        if (!player.isInvisible()) {
            poseStack.pushPose();
            poseStack.mulPose(Axis.YP.rotationDegrees(90.0F));
            renderArm(poseStack, buffers, light, player, HumanoidArm.RIGHT);
            renderArm(poseStack, buffers, light, player, HumanoidArm.LEFT);
            poseStack.popPose();
        }

        // The controls face the camera, the grips in the hands.
        poseStack.mulPose(Axis.XP.rotationDegrees(Mth.sin(swingRoot * Mth.PI) * 20.0F));
        poseStack.translate(0.0F, -0.12F, 0.0F);
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
        poseStack.scale(0.65F, 0.65F, 0.65F);
        minecraft.getItemRenderer().renderStatic(player, event.getItemStack(), ItemDisplayContext.NONE, false, poseStack, buffers,
                player.level(), light, OverlayTexture.NO_OVERLAY, player.getId());
        poseStack.popPose();
    }

    /** How far the remote is lowered and laid back, from 0 (looking down) to 1 (looking ahead or up), like a map. */
    private static float tilt(float pitch) {
        float f = Mth.clamp(1.0F - pitch / 45.0F + 0.1F, 0.0F, 1.0F);
        return -Mth.cos(f * Mth.PI) * 0.5F + 0.5F;
    }

    /** One arm reaching for the remote's grip, posed like the arms holding a map ({@code ItemInHandRenderer.renderMapHand}). */
    private static void renderArm(PoseStack poseStack, MultiBufferSource buffers, int light, AbstractClientPlayer player, HumanoidArm arm) {
        PlayerRenderer renderer = (PlayerRenderer) Minecraft.getInstance().getEntityRenderDispatcher().<AbstractClientPlayer>getRenderer(player);
        float side = arm == HumanoidArm.RIGHT ? 1.0F : -1.0F;
        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(92.0F));
        poseStack.mulPose(Axis.XP.rotationDegrees(45.0F));
        poseStack.mulPose(Axis.ZP.rotationDegrees(side * -41.0F));
        poseStack.translate(side * 0.3F, -1.1F, 0.45F);
        if (arm == HumanoidArm.RIGHT) {
            renderer.renderRightHand(poseStack, buffers, light, player);
        } else {
            renderer.renderLeftHand(poseStack, buffers, light, player);
        }
        poseStack.popPose();
    }
}
