package com.elpinho.seekerdrones.command;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.drone.DroneItem;
import com.elpinho.seekerdrones.drone.DroneStats;
import com.elpinho.seekerdrones.drone.UpgradeType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.Dynamic2CommandExceptionType;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

/**
 * Debug command to install and remove upgrades (DESIGN.md section 4) on the held drone item or on drone entities.
 * Requires permission level 2. Enforces the per-type caps and the total slot limit. Energy and Health upgrades arrive
 * full, like in the Programming Station. Kept after M7 as an admin/testing tool alongside the Programming Station.
 */
public final class UpgradeCommand {
    private static final String KEY = "commands.seekerdrones.upgrade.";

    private static final DynamicCommandExceptionType UNKNOWN_TYPE =
            new DynamicCommandExceptionType(name -> Component.translatable(KEY + "unknown_type", String.valueOf(name)));
    private static final Dynamic2CommandExceptionType OVER_TYPE_CAP =
            new Dynamic2CommandExceptionType((type, max) -> Component.translatable(KEY + "over_type_cap", type, max));
    private static final Dynamic2CommandExceptionType OVER_TOTAL_SLOTS =
            new Dynamic2CommandExceptionType((total, max) -> Component.translatable(KEY + "over_total_slots", total, max));

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_TYPES = (context, builder) ->
            SharedSuggestionProvider.suggest(Arrays.stream(UpgradeType.values()).map(UpgradeType::getSerializedName), builder);

    private UpgradeCommand() {}

    /** The {@code upgrade} subtree of {@code /seekerdrones}, registered by {@link SeekerDronesCommand}. */
    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("upgrade")
                .then(Commands.literal("set")
                        .then(Commands.argument("type", StringArgumentType.word()).suggests(SUGGEST_TYPES)
                                .then(ConfigCommand.onDrones(Commands.argument("count", IntegerArgumentType.integer(0)),
                                        (ctx, drones) -> setCount(ctx, drones, typeArgument(ctx), IntegerArgumentType.getInteger(ctx, "count"))))))
                .then(ConfigCommand.onDrones(Commands.literal("clear"), UpgradeCommand::clear))
                .then(ConfigCommand.onDrones(Commands.literal("list"), UpgradeCommand::list));
    }

    private static UpgradeType typeArgument(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String name = StringArgumentType.getString(ctx, "type");
        UpgradeType type = UpgradeType.CODEC.byName(name);
        if (type == null) {
            throw UNKNOWN_TYPE.create(name);
        }
        return type;
    }

    private static int setCount(CommandContext<CommandSourceStack> ctx, Collection<DroneEntity> drones, UpgradeType type, int count) throws CommandSyntaxException {
        Component typeName = Component.translatable(type.getTranslationKey());
        return ConfigCommand.apply(ctx.getSource(), drones, data -> {
            if (count > type.maxCount()) {
                throw OVER_TYPE_CAP.create(typeName, type.maxCount());
            }
            Map<UpgradeType, Integer> upgrades = new HashMap<>(data.upgrades());
            upgrades.put(type, count);
            int total = DroneStats.totalUpgrades(upgrades);
            int slots = ServerConfig.get(ServerConfig.UPGRADES_TOTAL_SLOTS);
            if (total > slots) {
                throw OVER_TOTAL_SLOTS.create(total, slots);
            }
            return data.withUpgradeCount(type, count);
        }, affected -> Component.translatable(KEY + "set", typeName, count, affected));
    }

    private static int clear(CommandContext<CommandSourceStack> ctx, Collection<DroneEntity> drones) throws CommandSyntaxException {
        return ConfigCommand.apply(ctx.getSource(), drones, data -> {
            DroneData result = data;
            for (UpgradeType type : UpgradeType.values()) {
                result = result.withUpgradeCount(type, 0);
            }
            return result;
        }, count -> Component.translatable(KEY + "cleared", count));
    }

    private static int list(CommandContext<CommandSourceStack> ctx, Collection<DroneEntity> drones) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        if (drones == null) {
            list(source, DroneItem.getData(SeekerDronesCommand.heldDrone(source)));
            return 1;
        }
        for (DroneEntity drone : drones) {
            list(source, drone.snapshotData());
        }
        return drones.size();
    }

    private static void list(CommandSourceStack source, DroneData data) {
        source.sendSuccess(() -> Component.translatable(KEY + "list.header", DroneItem.identity(data),
                DroneStats.totalUpgrades(data.upgrades()), ServerConfig.get(ServerConfig.UPGRADES_TOTAL_SLOTS)), false);
        if (data.upgrades().isEmpty()) {
            source.sendSuccess(() -> Component.translatable(KEY + "list.empty").withStyle(ChatFormatting.GRAY), false);
            return;
        }
        for (UpgradeType type : UpgradeType.values()) {
            int count = data.upgradeCount(type);
            if (count > 0) {
                source.sendSuccess(() -> Component.translatable(KEY + "list.entry", Component.translatable(type.getTranslationKey()),
                        count, type.maxCount()), false);
            }
        }
    }
}
