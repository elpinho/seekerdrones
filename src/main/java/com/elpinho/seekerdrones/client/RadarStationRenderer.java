package com.elpinho.seekerdrones.client;

import com.elpinho.seekerdrones.SeekerDrones;
import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.radar.RadarStationBlock;
import com.elpinho.seekerdrones.radar.RadarStationBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * Turns the Radar Station's head (the dish, feed horn and beacon on top of the mast) while the station is powered
 * (DESIGN.md section 7.6). It turns clockwise once per sweep, in step with the screen's sweep, about the mast's axis.
 * The powered head model lights the beacon. Client-side only, so it costs the server nothing.
 */
public class RadarStationRenderer implements BlockEntityRenderer<RadarStationBlockEntity> {
    public static final ModelResourceLocation HEAD = ModelResourceLocation.standalone(
            ResourceLocation.fromNamespaceAndPath(SeekerDrones.MODID, "block/radar_station_head"));
    public static final ModelResourceLocation HEAD_POWERED = ModelResourceLocation.standalone(
            ResourceLocation.fromNamespaceAndPath(SeekerDrones.MODID, "block/radar_station_head_powered"));
    /** The mast's axis in the model (pixels, front = north), which the head turns about. */
    private static final double AXIS_X = 8 / 16.0;
    private static final double AXIS_Z = 10 / 16.0;

    private final BlockRenderDispatcher blocks;

    public RadarStationRenderer(BlockEntityRendererProvider.Context context) {
        this.blocks = context.getBlockRenderDispatcher();
    }

    public static void registerModels(ModelEvent.RegisterAdditional event) {
        event.register(HEAD);
        event.register(HEAD_POWERED);
    }

    @Override
    public void render(RadarStationBlockEntity station, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        if (station.getLevel() == null) {
            return;
        }
        boolean powered = station.getBlockState().getValue(RadarStationBlock.POWERED);
        if (powered) {
            int sweepTicks = ServerConfig.get(ServerConfig.RADAR_STATION_SWEEP_TICKS);
            double time = station.getLevel().getGameTime() + partialTick;
            station.barAngle = (float) ((time % sweepTicks) / sweepTicks * 360);
        }
        BakedModel model = Minecraft.getInstance().getModelManager().getModel(powered ? HEAD_POWERED : HEAD);
        pose.pushPose();
        // Move to the mast's axis, which turns with the block's facing like the base model (blockstate y rotation)...
        float facing = 180 - station.getBlockState().getValue(RadarStationBlock.FACING).toYRot();
        pose.translate(0.5, 0, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(facing));
        pose.translate(AXIS_X - 0.5, 0, AXIS_Z - 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(-facing));
        // ...then point the dish at the sweep's bearing: clockwise seen from above, from north, like the screen.
        pose.mulPose(Axis.YN.rotationDegrees(station.barAngle));
        pose.translate(-AXIS_X, 0, -AXIS_Z);
        // The head is in the upper block, so use its light.
        int barLight = LevelRenderer.getLightColor(station.getLevel(), station.getBlockPos().above());
        blocks.getModelRenderer().renderModel(pose.last(), buffers.getBuffer(RenderType.cutout()), station.getBlockState(), model,
                1, 1, 1, barLight, overlay, ModelData.EMPTY, RenderType.cutout());
        pose.popPose();
    }

    /** The head is in the upper block, and the dish reaches about 2 px past the block's edge as it turns. */
    @Override
    public AABB getRenderBoundingBox(RadarStationBlockEntity station) {
        return new AABB(station.getBlockPos()).expandTowards(0, 1, 0).inflate(0.2, 0, 0.2);
    }
}
