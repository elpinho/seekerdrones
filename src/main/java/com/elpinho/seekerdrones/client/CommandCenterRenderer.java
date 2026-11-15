package com.elpinho.seekerdrones.client;

import com.elpinho.seekerdrones.SeekerDrones;
import com.elpinho.seekerdrones.commandcenter.CommandCenterBlockEntity;
import com.elpinho.seekerdrones.radar.TwoBlockMachineBlock;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * The Command Center's hologram (DESIGN.md sections 7.6 and 7.10), drawn while powered in the upper block's space,
 * translucent and full-bright: a beam from the lens up to a floating contour-map disc that turns once every 20 s, and
 * four blips floating above it on thin stems, bobbing gently, one red and blinking, with a faint scanline flicker. It's
 * generic, never the real map or drones. Client-side only.
 */
public class CommandCenterRenderer implements BlockEntityRenderer<CommandCenterBlockEntity> {
    public static final ModelResourceLocation DISC = model("command_center_holo_disc");
    public static final ModelResourceLocation BLIP = model("command_center_holo_blip");
    public static final ModelResourceLocation BLIP_RED = model("command_center_holo_blip_red");
    public static final ModelResourceLocation STEM = model("command_center_holo_stem");
    /** Ticks for one turn of the disc. */
    private static final float TURN_TICKS = 400;
    /** The disc's height in the model (pixels from the lower block's bottom). */
    private static final float DISC_Y = 16;
    /** The floating blips: x, height and z in pixels, and whether it's the red one. */
    private static final float[][] BLIPS = {{5, 21, 4, 0}, {11, 23, 8, 0}, {3, 20, 12, 0}, {12, 22, 13, 1}};

    private final BlockRenderDispatcher blocks;

    public CommandCenterRenderer(BlockEntityRendererProvider.Context context) {
        this.blocks = context.getBlockRenderDispatcher();
    }

    private static ModelResourceLocation model(String name) {
        return ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(SeekerDrones.MODID, "block/" + name));
    }

    public static void registerModels(ModelEvent.RegisterAdditional event) {
        event.register(DISC);
        event.register(BLIP);
        event.register(BLIP_RED);
        event.register(STEM);
    }

    @Override
    public void render(CommandCenterBlockEntity center, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        if (center.getLevel() == null || !center.getBlockState().getValue(TwoBlockMachineBlock.POWERED)) {
            return;
        }
        float time = center.getLevel().getGameTime() + partialTick;
        // A different phase per table, so neighbors don't turn in lockstep.
        time += Math.floorMod(center.getBlockPos().asLong(), 997);
        var models = Minecraft.getInstance().getModelManager();
        // The scanline flicker: a faint dip in brightness every so often.
        float flicker = Mth.sin(time * 0.9F) > 0.97F ? 0.75F : 0.92F + 0.08F * Mth.sin(time * 0.31F);

        pose.pushPose();
        pose.translate(0.5, 0, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(time / TURN_TICKS * 360));
        pose.translate(-0.5, 0, -0.5);
        render(pose, buffers, center, models.getModel(DISC), flicker, overlay);
        for (int i = 0; i < BLIPS.length; i++) {
            float[] blip = BLIPS[i];
            boolean red = blip[3] != 0;
            if (red && (int) (time / 10) % 2 == 1) {
                continue;
            }
            float bob = 0.5F * Mth.sin(time * 0.08F + i * 1.7F);
            float top = blip[1] + bob;
            // The stem from the disc up to the blip.
            pose.pushPose();
            pose.translate(blip[0] / 16, DISC_Y / 16, blip[2] / 16);
            // The stem model is one pixel tall: stretch it to the blip.
            pose.scale(1, top - DISC_Y, 1);
            render(pose, buffers, center, models.getModel(STEM), flicker, overlay);
            pose.popPose();
            pose.pushPose();
            pose.translate(blip[0] / 16, top / 16, blip[2] / 16);
            render(pose, buffers, center, models.getModel(red ? BLIP_RED : BLIP), flicker, overlay);
            pose.popPose();
        }
        pose.popPose();
    }

    /** Full-bright and translucent; the flicker dims the colors. */
    private void render(PoseStack pose, MultiBufferSource buffers, CommandCenterBlockEntity center, BakedModel model, float brightness, int overlay) {
        blocks.getModelRenderer().renderModel(pose.last(), buffers.getBuffer(RenderType.translucent()), center.getBlockState(), model,
                brightness, brightness, brightness, LightTexture.FULL_BRIGHT, overlay, ModelData.EMPTY, RenderType.translucent());
    }

    /** The hologram is in the upper block. */
    @Override
    public AABB getRenderBoundingBox(CommandCenterBlockEntity center) {
        return new AABB(center.getBlockPos()).expandTowards(0, 1, 0);
    }
}
