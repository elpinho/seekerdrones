package com.elpinho.seekerdrones.client.gui;

import org.joml.Quaternionf;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * Draws an entity in a GUI, like {@code InventoryScreen.renderEntityInInventory} but for any entity (previews can show
 * boats and minecarts too) and with a free rotation. The entities are never added to the level.
 */
public final class EntityRendering {
    /** Previews sit far below the world, so their renderers never draw a name tag (those check the camera distance). */
    private static final double PREVIEW_Y = -10_000;

    private EntityRendering() {}

    /** Prepares a client-only entity for previews: facing straight ahead, far from the camera. */
    public static void preparePreview(Entity entity) {
        entity.setPos(0, PREVIEW_Y, 0);
        entity.setYRot(0);
        entity.setXRot(0);
        entity.yRotO = 0;
        entity.xRotO = 0;
        if (entity instanceof LivingEntity living) {
            living.yBodyRot = 0;
            living.yBodyRotO = 0;
            living.yHeadRot = 0;
            living.yHeadRotO = 0;
        }
    }

    /**
     * Renders the entity with its feet (or, with {@code centered}, the middle of its box) at (x, y).
     *
     * @param scale GUI pixels per block
     * @param yaw   turn around the vertical axis, in degrees (0 faces the viewer)
     * @param pitch tilt toward the viewer, in degrees, to see it from slightly above
     */
    public static void render(GuiGraphics graphics, Entity entity, float x, float y, float scale, float yaw, float pitch, boolean centered) {
        PoseStack pose = graphics.pose();
        EntityRenderDispatcher dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
        pose.pushPose();
        try {
            pose.translate(x, y, 100);
            pose.scale(scale, scale, -scale);
            pose.mulPose(new Quaternionf().rotateZ(Mth.PI).rotateX(pitch * Mth.DEG_TO_RAD).rotateY((180 + yaw) * Mth.DEG_TO_RAD));
            float offsetY = centered ? -entity.getBbHeight() / 2 : 0;
            Lighting.setupForEntityInInventory();
            dispatcher.setRenderShadow(false);
            RenderSystem.runAsFancy(() -> dispatcher.render(entity, 0, offsetY, 0, 0, 1, pose, graphics.bufferSource(), LightTexture.FULL_BRIGHT));
            graphics.flush();
        } finally {
            dispatcher.setRenderShadow(true);
            pose.popPose();
            Lighting.setupFor3DItems();
        }
    }
}
