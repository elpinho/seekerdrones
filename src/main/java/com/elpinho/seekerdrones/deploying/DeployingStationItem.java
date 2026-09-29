package com.elpinho.seekerdrones.deploying;

import java.util.List;

import com.elpinho.seekerdrones.registry.ModDataComponents;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;

/** The Deploying Station's item, which says when it keeps auto-deploy turned off (DESIGN.md section 7.3). */
public class DeployingStationItem extends BlockItem {
    public DeployingStationItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        Boolean autoDeploy = stack.get(ModDataComponents.DEPLOYING_STATION);
        if (autoDeploy != null && !autoDeploy) {
            tooltip.add(Component.translatable("tooltip.seekerdrones.deploying_station.auto_deploy_off").withStyle(ChatFormatting.GRAY));
        }
    }
}
