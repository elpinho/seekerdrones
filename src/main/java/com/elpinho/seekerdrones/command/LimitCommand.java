package com.elpinho.seekerdrones.command;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

import com.elpinho.seekerdrones.drone.DeployedDrones;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;

/**
 * Debug command for the deployed drone limit ({@code drone.maxDeployedPerPlayer}, DESIGN.md section 2.2): lists the
 * drones counted against a player, and forgets a counted drone that no longer exists (e.g. its chunk was deleted).
 * Requires permission level 2. Kept as an admin tool.
 */
public final class LimitCommand {
    private static final String KEY = "commands.seekerdrones.limit.";
    private static final DynamicCommandExceptionType NOT_COUNTED =
            new DynamicCommandExceptionType(id -> Component.translatable(KEY + "not_counted", String.valueOf(id)));

    private LimitCommand() {}

    /** The {@code limit} subtree of {@code /seekerdrones}, registered by {@link SeekerDronesCommand}. */
    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("limit")
                .then(Commands.literal("list")
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .executes(ctx -> list(ctx, GameProfileArgument.getGameProfiles(ctx, "player")))))
                .then(Commands.literal("forget")
                        .then(Commands.argument("droneId", StringArgumentType.word())
                                .executes(LimitCommand::forget)));
    }

    private static int list(CommandContext<CommandSourceStack> ctx, Collection<GameProfile> profiles) {
        CommandSourceStack source = ctx.getSource();
        DeployedDrones deployed = DeployedDrones.get(source.getServer());
        int limit = DeployedDrones.limit();
        for (GameProfile profile : profiles) {
            List<String> drones = deployed.dronesOf(profile.getId());
            Component max = limit > 0 ? Component.literal(String.valueOf(limit)) : Component.translatable(KEY + "no_limit");
            source.sendSuccess(() -> Component.translatable(KEY + "list", profile.getName(), drones.size(), max), false);
            if (!drones.isEmpty()) {
                source.sendSuccess(() -> Component.literal(String.join(", ", drones)), false);
            }
        }
        return profiles.size();
    }

    private static int forget(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String droneId = StringArgumentType.getString(ctx, "droneId").toUpperCase(Locale.ROOT);
        if (!DeployedDrones.get(ctx.getSource().getServer()).remove(droneId)) {
            throw NOT_COUNTED.create(droneId);
        }
        ctx.getSource().sendSuccess(() -> Component.translatable(KEY + "forgot", droneId), true);
        return 1;
    }
}
