package com.elpinho.seekerdrones.client;

import com.elpinho.seekerdrones.SeekerDrones;
import com.elpinho.seekerdrones.radar.TwoBlockMachineBlock;
import com.elpinho.seekerdrones.uplink.DishAim;
import com.elpinho.seekerdrones.uplink.UplinkBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * Aims the Satellite Uplink's dish (DESIGN.md sections 7.6 and 7.9): the az-el mount turns in yaw about the pedestal's
 * axis, and the dish tilts in pitch about the fork's axle. The dish is modeled pointing straight up. While powered it
 * slowly re-aims at random ({@link DishAim}), and unpowered it stops where it is. The powered dish model lights the
 * tracking light. Client-side only, so it costs the server nothing.
 */
public class UplinkRenderer implements BlockEntityRenderer<UplinkBlockEntity> {
    public static final ModelResourceLocation MOUNT = model("satellite_uplink_mount");
    public static final ModelResourceLocation DISH = model("satellite_uplink_dish");
    public static final ModelResourceLocation DISH_POWERED = model("satellite_uplink_dish_powered");
    /** The mount's axis in the model (pixels, front = north), which it turns about. */
    private static final double AXIS_X = 8 / 16.0;
    private static final double AXIS_Z = 9.5 / 16.0;
    /** The height of the dish's elevation axle, from the lower block's bottom. */
    private static final double PIVOT_Y = 22 / 16.0;

    private final BlockRenderDispatcher blocks;

    public UplinkRenderer(BlockEntityRendererProvider.Context context) {
        this.blocks = context.getBlockRenderDispatcher();
    }

    private static ModelResourceLocation model(String name) {
        return ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(SeekerDrones.MODID, "block/" + name));
    }

    public static void registerModels(ModelEvent.RegisterAdditional event) {
        event.register(MOUNT);
        event.register(DISH);
        event.register(DISH_POWERED);
    }

    @Override
    public void render(UplinkBlockEntity uplink, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        if (uplink.getLevel() == null) {
            return;
        }
        boolean powered = uplink.getBlockState().getValue(TwoBlockMachineBlock.POWERED);
        DishAim aim = uplink.dishAim.update(uplink.getBlockPos().asLong(), uplink.getLevel().getGameTime() + partialTick, powered);
        var models = Minecraft.getInstance().getModelManager();
        // The mount and dish are mostly in the upper block, so use its light.
        int headLight = LevelRenderer.getLightColor(uplink.getLevel(), uplink.getBlockPos().above());

        pose.pushPose();
        // Move to the mount's axis, which turns with the block's facing like the base model (blockstate y rotation)...
        float facing = 180 - uplink.getBlockState().getValue(TwoBlockMachineBlock.FACING).toYRot();
        pose.translate(0.5, 0, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(facing));
        pose.translate(AXIS_X - 0.5, 0, AXIS_Z - 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(-facing));
        // ...then turn the mount to the dish's bearing, clockwise seen from above.
        pose.mulPose(Axis.YN.rotationDegrees(aim.yaw()));
        pose.translate(-AXIS_X, 0, -AXIS_Z);
        render(pose, buffers, uplink, models.getModel(MOUNT), headLight, overlay);

        // The dish tilts down from straight up toward the front (north, before the turn) to its elevation.
        pose.translate(AXIS_X, PIVOT_Y, AXIS_Z);
        pose.mulPose(Axis.XN.rotationDegrees(90 - aim.elevation()));
        pose.translate(-AXIS_X, -PIVOT_Y, -AXIS_Z);
        render(pose, buffers, uplink, models.getModel(powered ? DISH_POWERED : DISH), headLight, overlay);
        pose.popPose();
    }

    private void render(PoseStack pose, MultiBufferSource buffers, UplinkBlockEntity uplink, BakedModel model, int light, int overlay) {
        blocks.getModelRenderer().renderModel(pose.last(), buffers.getBuffer(RenderType.cutout()), uplink.getBlockState(), model,
                1, 1, 1, light, overlay, ModelData.EMPTY, RenderType.cutout());
    }

    /** The mount and dish are in the upper block, and the tilted dish reaches past the block's edges as it turns. */
    @Override
    public AABB getRenderBoundingBox(UplinkBlockEntity uplink) {
        return new AABB(uplink.getBlockPos()).expandTowards(0, 1, 0).inflate(0.5, 0, 0.5);
    }
}
