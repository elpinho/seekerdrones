package com.elpinho.seekerdrones.station;

import java.util.List;
import java.util.Map;

import com.elpinho.seekerdrones.drone.UpgradeType;
import com.elpinho.seekerdrones.registry.ModDataComponents;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;

/** The Charging Station's item, which lists the upgrades it keeps (DESIGN.md section 7.4). */
public class ChargingStationItem extends BlockItem {
    public ChargingStationItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        Map<UpgradeType, Integer> upgrades = stack.get(ModDataComponents.MACHINE_UPGRADES);
        if (upgrades != null) {
            for (UpgradeType type : UpgradeType.values()) {
                int count = upgrades.getOrDefault(type, 0);
                if (count > 0) {
                    tooltip.add(Component.translatable("tooltip.seekerdrones.machine_upgrades", Component.translatable(type.getTranslationKey()), count)
                            .withStyle(ChatFormatting.GRAY));
                }
            }
        }
    }
}
