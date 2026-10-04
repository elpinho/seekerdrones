package com.elpinho.seekerdrones.drone;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * An upgrade item (DESIGN.md section 4). Installed on a drone by the Programming Station, or by the debug command
 * until it exists.
 */
public class UpgradeItem extends Item {
    private final UpgradeType type;

    public UpgradeItem(UpgradeType type, Properties properties) {
        super(properties);
        this.type = type;
    }

    public UpgradeType getType() {
        return type;
    }

    /** The registry path of the item for this upgrade type, e.g. {@code player_seek_upgrade}. */
    public static String itemName(UpgradeType type) {
        return type.getSerializedName() + "_upgrade";
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable(type.getTranslationKey() + ".description").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.seekerdrones.upgrade.max_count", type.maxCount()).withStyle(ChatFormatting.DARK_GRAY));
    }
}
