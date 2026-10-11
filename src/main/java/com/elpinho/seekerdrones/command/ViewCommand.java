package com.elpinho.seekerdrones.command;

import java.util.Locale;

import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.drone.DroneIndex;
import com.elpinho.seekerdrones.feed.FpvFeeds;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /seekerdrones view <droneId> [player]} and {@code /seekerdrones view stop [player]} (DESIGN.md section 2.12):
 * server operators watch any loaded drone's FPV feed in their dimension, for moderation. No remote, operator check,
 * range limit or FPV Camera is needed. Requires permission level 2, like the rest of {@code /seekerdrones}.
 */
public final class ViewCommand {
    private static final String KEY = "commands.seekerdrones.view.";
    private static final DynamicCommandExceptionType NOT_FOUND =
            new DynamicCommandExceptionType(id -> Component.translatable(KEY + "not_found", String.valueOf(id)));
    private static final DynamicCommandExceptionType OTHER_DIMENSION =
            new DynamicCommandExceptionType(id -> Component.translatable(KEY + "other_dimension", String.valueOf(id)));
    private static final SimpleCommandExceptionType NOT_VIEWING =
            new SimpleCommandExceptionType(Component.translatable(KEY + "not_viewing"));

    private ViewCommand() {}

    /** The {@code view} subtree of {@code /seekerdrones}, registered by {@link SeekerDronesCommand}. */
    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("view")
                .then(Commands.literal("stop")
                        .executes(ctx -> stop(ctx, ctx.getSource().getPlayerOrException()))
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> stop(ctx, EntityArgument.getPlayer(ctx, "player")))))
                .then(Commands.argument("droneId", StringArgumentType.word())
                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(DroneIndex.ids(ctx.getSource().getLevel()), builder))
                        .executes(ctx -> view(ctx, ctx.getSource().getPlayerOrException()))
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> view(ctx, EntityArgument.getPlayer(ctx, "player")))));
    }

    private static int view(CommandContext<CommandSourceStack> ctx, ServerPlayer player) throws CommandSyntaxException {
        String droneId = StringArgumentType.getString(ctx, "droneId").toUpperCase(Locale.ROOT);
        DroneEntity drone = DroneIndex.find(player.serverLevel(), droneId);
        if (drone == null || !drone.isAlive()) {
            throw (DroneIndex.find(player.server, droneId) != null ? OTHER_DIMENSION : NOT_FOUND).create(droneId);
        }
        FpvFeeds.start(player, drone, false);
        ctx.getSource().sendSuccess(() -> Component.translatable(KEY + "started", player.getDisplayName(), droneId), true);
        return 1;
    }

    private static int stop(CommandContext<CommandSourceStack> ctx, ServerPlayer player) throws CommandSyntaxException {
        if (!FpvFeeds.stop(player, false)) {
            throw NOT_VIEWING.create();
        }
        ctx.getSource().sendSuccess(() -> Component.translatable(KEY + "stopped", player.getDisplayName()), true);
        return 1;
    }
}
