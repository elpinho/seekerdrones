package com.elpinho.seekerdrones.drone;

import java.util.List;
import java.util.Locale;

import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.network.DroneStatusPayload;
import com.elpinho.seekerdrones.registry.ModDataComponents;
import com.elpinho.seekerdrones.registry.ModEntityTypes;
import com.elpinho.seekerdrones.registry.ModItems;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The item form of a drone (DESIGN.md section 2.1). All state is in the {@code seekerdrones:drone_data} component.
 * A stack without the component (creative tab, {@code /give}) counts as a fresh, fully charged drone.
 */
public class DroneItem extends Item {
    public DroneItem(Properties properties) {
        super(properties);
    }

    public static ItemStack createStack(DroneData data) {
        ItemStack stack = new ItemStack(ModItems.DRONE.get());
        stack.set(ModDataComponents.DRONE_DATA, data);
        return stack;
    }

    public static DroneData getData(ItemStack stack) {
        DroneData data = stack.get(ModDataComponents.DRONE_DATA);
        return data != null ? data : DroneData.createNew();
    }

    // --- Hand-deploy (section 2.2) ---

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide()) {
            return InteractionResultHolder.success(stack);
        }
        DroneData data = getData(stack);
        if (!DronePermissions.canInteract(player, data)) {
            DronePermissions.sendDenied(player);
            return InteractionResultHolder.fail(stack);
        }
        if (!player.isSecondaryUseActive()) {
            // Plain right-click: show the stored drone's status (section 2.4).
            if (player instanceof ServerPlayer serverPlayer) {
                PacketDistributor.sendToPlayer(serverPlayer, DroneStatusPayload.ofItem(data));
            }
            return InteractionResultHolder.consume(stack);
        }
        if (!deploy(level, player, data)) {
            return InteractionResultHolder.fail(stack);
        }
        // A drone with an ID is one specific drone, so it's used up even in creative mode. An "Unassigned" item stays
        // in a creative player's hand like a spawn egg: each deploy gets a fresh ID anyway (section 2.8).
        if (data.hasDroneId()) {
            stack.shrink(1);
        } else {
            stack.consume(1, player);
        }
        return InteractionResultHolder.consume(stack);
    }

    private static boolean deploy(Level level, Player player, DroneData data) {
        DroneEntity drone = ModEntityTypes.DRONE.get().create(level);
        if (drone == null) {
            return false;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        Vec3 center = eye.add(look.scale(ServerConfig.get(ServerConfig.DRONE_DEPLOY_SPAWN_DISTANCE)));
        drone.moveTo(center.x, center.y - drone.getBbHeight() / 2, center.z, player.getYRot(), 0);

        // Don't deploy through walls or into blocks.
        HitResult hit = level.clip(new ClipContext(eye, center, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (hit.getType() != HitResult.Type.MISS || !level.noCollision(drone, drone.getBoundingBox())) {
            return false;
        }

        data = data.withIdAssigned(level.getRandom());
        // The first player to hand-deploy a drone becomes its owner for good (section 6.3). The owner redeploying it
        // refreshes the stored name, in case they renamed.
        if (data.ownerId().isEmpty() || data.ownerId().get().equals(player.getUUID())) {
            data = data.withOwner(player.getUUID(), player.getGameProfile().getName());
        }
        drone.setDroneData(data);
        double throwSpeed = ServerConfig.get(ServerConfig.DRONE_DEPLOY_THROW_SPEED);
        // Server-side player velocity is unreliable, so use the movement last reported by the client.
        drone.setDeltaMovement(player.getKnownMovement().add(look.scale(throwSpeed)));
        drone.startDrifting();
        return level.addFreshEntity(drone);
    }

    // --- Tooltip (section 2.1) ---

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        DroneData data = getData(stack);
        tooltip.add(identity(data));
        data.ownerId().ifPresent(ownerId -> {
            String name = data.ownerName().isEmpty() ? ownerId.toString() : data.ownerName();
            tooltip.add(Component.translatable("tooltip.seekerdrones.drone.owner", name).withStyle(ChatFormatting.GRAY));
        });
        tooltip.add(Component.translatable("tooltip.seekerdrones.drone.energy", data.energy(), DroneStats.maxEnergy(data))
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.seekerdrones.drone.health", formatHealth(data.health()), formatHealth(DroneStats.maxHealth(data)))
                .withStyle(ChatFormatting.GRAY));
        if (!data.upgrades().isEmpty()) {
            tooltip.add(Component.translatable("tooltip.seekerdrones.drone.upgrades").withStyle(ChatFormatting.GRAY));
            for (UpgradeType type : UpgradeType.values()) {
                int count = data.upgradeCount(type);
                if (count > 0) {
                    tooltip.add(Component.literal("  ")
                            .append(Component.translatable("tooltip.seekerdrones.drone.upgrade_entry", Component.translatable(type.getTranslationKey()), count))
                            .withStyle(ChatFormatting.DARK_GRAY));
                }
            }
        }
        tooltip.add(Component.translatable("tooltip.seekerdrones.drone.targets", data.config().targets().size())
                .withStyle(ChatFormatting.GRAY));
    }

    /** "label - ID" (or just the ID) in the drone's color. */
    public static MutableComponent identity(DroneData data) {
        Component id = data.hasDroneId()
                ? Component.literal(data.droneId())
                : Component.translatable("tooltip.seekerdrones.drone.unassigned");
        String label = data.config().label();
        MutableComponent line = label.isEmpty()
                ? id.copy()
                : Component.literal(label + " - ").append(id);
        return line.withStyle(style -> style.withColor(TextColor.fromRgb(data.config().color().getTextColor())));
    }

    public static String formatHealth(float health) {
        return health == Math.floor(health) ? String.valueOf((int) health) : String.format(Locale.ROOT, "%.1f", health);
    }
}
