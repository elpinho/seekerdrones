package com.elpinho.seekerdrones.programming;

import java.util.List;

import com.elpinho.seekerdrones.registry.ModDataComponents;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;

/** The Programming Station's item, which says when it carries a saved mode and template (DESIGN.md section 7.2). */
public class ProgrammingStationItem extends BlockItem {
    public ProgrammingStationItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        ProgrammingStationSettings settings = stack.get(ModDataComponents.PROGRAMMING_STATION);
        if (settings != null) {
            tooltip.add(Component.translatable("tooltip.seekerdrones.programming_station.programmed",
                    Component.translatable(settings.mode().getTranslationKey())).withStyle(ChatFormatting.GRAY));
        }
    }
}
