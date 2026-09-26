package com.elpinho.seekerdrones.command;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.drone.DroneItem;
import com.elpinho.seekerdrones.operator.OperatorGroup;
import com.elpinho.seekerdrones.operator.OperatorGroups;
import com.elpinho.seekerdrones.registry.ModDataComponents;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

/**
 * Debug command for managing Operator Groups and assigning them to drones (DESIGN.md section 6).
 * Requires permission level 2. Kept after M6 as an admin/testing tool alongside the Factory's operator list.
 */
public final class GroupCommand {
    private static final String KEY = "commands.seekerdrones.group.";

    private static final DynamicCommandExceptionType UNKNOWN_GROUP =
            new DynamicCommandExceptionType(id -> Component.translatable(KEY + "unknown", String.valueOf(id)));
    private static final SimpleCommandExceptionType SINGLE_OWNER =
            new SimpleCommandExceptionType(Component.translatable(KEY + "single_owner"));

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_GROUPS = (context, builder) ->
            SharedSuggestionProvider.suggest(
                    OperatorGroups.get(context.getSource().getServer()).getGroups().keySet().stream().map(UUID::toString),
                    builder);

    private GroupCommand() {}

    /** The {@code group} subtree of {@code /seekerdrones}, registered by {@link SeekerDronesCommand}. */
    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("group")
                .then(Commands.literal("create")
                        .executes(ctx -> create(ctx.getSource(), ctx.getSource().getPlayerOrException().getGameProfile()))
                        .then(Commands.argument("owner", GameProfileArgument.gameProfile())
                                .executes(ctx -> create(ctx.getSource(), singleProfile(ctx, "owner")))))
                .then(Commands.literal("list")
                        .executes(ctx -> list(ctx.getSource())))
                .then(Commands.literal("info")
                        .then(groupArgument().executes(ctx -> info(ctx.getSource(), groupId(ctx)))))
                .then(Commands.literal("add")
                        .then(groupArgument()
                                .then(Commands.argument("players", GameProfileArgument.gameProfile())
                                        .executes(ctx -> add(ctx.getSource(), groupId(ctx), GameProfileArgument.getGameProfiles(ctx, "players"))))))
                .then(Commands.literal("remove")
                        .then(groupArgument()
                                .then(Commands.argument("players", GameProfileArgument.gameProfile())
                                        .executes(ctx -> remove(ctx.getSource(), groupId(ctx), GameProfileArgument.getGameProfiles(ctx, "players"))))))
                .then(Commands.literal("assign")
                        .then(groupArgument()
                                .executes(ctx -> assignHeld(ctx.getSource(), Optional.of(groupId(ctx))))
                                .then(Commands.argument("drones", EntityArgument.entities())
                                        .executes(ctx -> assignEntities(ctx.getSource(), Optional.of(groupId(ctx)), EntityArgument.getEntities(ctx, "drones"))))))
                .then(Commands.literal("clear")
                        .executes(ctx -> assignHeld(ctx.getSource(), Optional.empty()))
                        .then(Commands.argument("drones", EntityArgument.entities())
                                .executes(ctx -> assignEntities(ctx.getSource(), Optional.empty(), EntityArgument.getEntities(ctx, "drones")))));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, UUID> groupArgument() {
        return Commands.argument("group", UuidArgument.uuid()).suggests(SUGGEST_GROUPS);
    }

    /** The group argument, which must name an existing group. */
    private static UUID groupId(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        UUID id = UuidArgument.getUuid(ctx, "group");
        if (OperatorGroups.get(ctx.getSource().getServer()).getGroup(id).isEmpty()) {
            throw UNKNOWN_GROUP.create(id);
        }
        return id;
    }

    private static GameProfile singleProfile(CommandContext<CommandSourceStack> ctx, String name) throws CommandSyntaxException {
        Collection<GameProfile> profiles = GameProfileArgument.getGameProfiles(ctx, name);
        if (profiles.size() != 1) {
            throw SINGLE_OWNER.create();
        }
        return profiles.iterator().next();
    }

    private static int create(CommandSourceStack source, GameProfile owner) {
        UUID id = OperatorGroups.get(source.getServer()).createGroup(owner.getId());
        source.sendSuccess(() -> Component.translatable(KEY + "created", groupComponent(id), owner.getName()), true);
        return 1;
    }

    private static int list(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        Map<UUID, OperatorGroup> groups = OperatorGroups.get(server).getGroups();
        if (groups.isEmpty()) {
            source.sendSuccess(() -> Component.translatable(KEY + "list.empty"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.translatable(KEY + "list.header", groups.size()), false);
        groups.forEach((id, group) -> source.sendSuccess(() -> Component.translatable(KEY + "list.entry",
                groupComponent(id), playerName(server, group.owner()), group.operators().size()), false));
        return groups.size();
    }

    private static int info(CommandSourceStack source, UUID id) {
        MinecraftServer server = source.getServer();
        OperatorGroup group = OperatorGroups.get(server).getGroup(id).orElseThrow();
        String operators = group.operators().isEmpty()
                ? "-"
                : group.operators().stream().map(uuid -> playerName(server, uuid)).sorted().collect(Collectors.joining(", "));
        source.sendSuccess(() -> Component.translatable(KEY + "info", groupComponent(id), playerName(server, group.owner()), operators), false);
        return 1;
    }

    private static int add(CommandSourceStack source, UUID id, Collection<GameProfile> players) {
        OperatorGroups groups = OperatorGroups.get(source.getServer());
        int changed = 0;
        for (GameProfile profile : players) {
            if (groups.addOperator(id, profile.getId())) {
                changed++;
            }
        }
        int count = changed;
        source.sendSuccess(() -> Component.translatable(KEY + "added", count, groupComponent(id)), true);
        return count;
    }

    private static int remove(CommandSourceStack source, UUID id, Collection<GameProfile> players) {
        OperatorGroups groups = OperatorGroups.get(source.getServer());
        OperatorGroup group = groups.getGroup(id).orElseThrow();
        int changed = 0;
        for (GameProfile profile : players) {
            if (profile.getId().equals(group.owner())) {
                source.sendFailure(Component.translatable(KEY + "cannot_remove_owner", profile.getName()));
            } else if (groups.removeOperator(id, profile.getId())) {
                changed++;
            }
        }
        int count = changed;
        source.sendSuccess(() -> Component.translatable(KEY + "removed", count, groupComponent(id)), true);
        return count;
    }

    private static int assignHeld(CommandSourceStack source, Optional<UUID> groupId) throws CommandSyntaxException {
        ItemStack stack = SeekerDronesCommand.heldDrone(source);
        stack.set(ModDataComponents.DRONE_DATA, DroneItem.getData(stack).withGroupId(groupId));
        sendAssigned(source, groupId, 1);
        return 1;
    }

    private static int assignEntities(CommandSourceStack source, Optional<UUID> groupId, Collection<? extends Entity> entities) throws CommandSyntaxException {
        List<DroneEntity> drones = SeekerDronesCommand.drones(entities);
        for (DroneEntity drone : drones) {
            drone.setDroneData(drone.snapshotData().withGroupId(groupId));
        }
        int count = drones.size();
        sendAssigned(source, groupId, count);
        return count;
    }

    private static void sendAssigned(CommandSourceStack source, Optional<UUID> groupId, int count) {
        source.sendSuccess(() -> groupId
                .map(id -> Component.translatable(KEY + "assigned", count, groupComponent(id)))
                .orElseGet(() -> Component.translatable(KEY + "cleared", count)), true);
    }

    /** The group ID, clickable to copy it. */
    private static MutableComponent groupComponent(UUID id) {
        String text = id.toString();
        return Component.literal(text).withStyle(style -> style
                .withColor(ChatFormatting.GREEN)
                .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, text))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable("chat.copy.click"))));
    }

    private static String playerName(MinecraftServer server, UUID uuid) {
        return Optional.ofNullable(server.getProfileCache())
                .flatMap(cache -> cache.get(uuid))
                .map(GameProfile::getName)
                .orElse(uuid.toString());
    }
}
