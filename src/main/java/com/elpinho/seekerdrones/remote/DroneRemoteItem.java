package com.elpinho.seekerdrones.remote;

import java.util.List;

import javax.annotation.Nullable;

import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.registry.ModDataComponents;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/**
 * The Drone Remote (DESIGN.md section 2.10): a handheld controller linked to one deployed drone, by right-clicking the
 * drone or Shift + right-clicking in its direction. A plain right-click opens its screen. The link is the
 * {@code seekerdrones:remote_link} component.
 */
public class DroneRemoteItem extends Item {
    public DroneRemoteItem(Properties properties) {
        super(properties);
    }

    @Nullable
    public static RemoteLink getLink(ItemStack stack) {
        return stack.get(ModDataComponents.REMOTE_LINK);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide()) {
            return InteractionResultHolder.success(stack);
        }
        if (player instanceof ServerPlayer serverPlayer) {
            if (player.isSecondaryUseActive()) {
                RemoteControl.linkByAiming(serverPlayer, stack);
            } else {
                RemoteLink link = getLink(stack);
                if (link == null) {
                    player.displayClientMessage(Component.translatable("message.seekerdrones.remote.not_linked"), true);
                } else {
                    RemoteControl.sendStatus(serverPlayer, link, true);
                }
            }
        }
        return InteractionResultHolder.consume(stack);
    }

    /** Right-clicking a drone with the remote (with or without Shift) links it. The drone passes the click on to us. */
    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        if (!(target instanceof DroneEntity drone)) {
            return InteractionResult.PASS;
        }
        if (player instanceof ServerPlayer serverPlayer) {
            // In creative mode the game passes a copy of the held stack (so name tags and the like aren't used up),
            // so the link is set on the stack actually in the hand.
            RemoteControl.link(serverPlayer, player.getItemInHand(hand), drone);
        }
        return InteractionResult.sidedSuccess(player.level().isClientSide());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        RemoteLink link = getLink(stack);
        tooltip.add(link != null ? link.identity() : Component.translatable("tooltip.seekerdrones.remote.not_linked").withStyle(ChatFormatting.GRAY));
    }
}
