package com.elpinho.seekerdrones.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.IntFunction;

import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.drone.DroneItem;
import com.elpinho.seekerdrones.drone.DroneStats;
import com.elpinho.seekerdrones.drone.TargetBlacklist;
import com.elpinho.seekerdrones.drone.TargetEntry;
import com.elpinho.seekerdrones.registry.ModDataComponents;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.Dynamic2CommandExceptionType;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;

/**
 * Debug command for a drone's targets, follow distance, color (DESIGN.md section 2.6), patrol center and patrol radius
 * (section 3.2), acting on the held drone item or on drone entities. Requires permission level 2. Kept after M7 as an
 * admin/testing tool alongside the Programming Station.
 */
public final class ConfigCommand {
    private static final String KEY = "commands.seekerdrones.config.";

    private static final DynamicCommandExceptionType UNKNOWN_TAG =
            new DynamicCommandExceptionType(tag -> Component.translatable(KEY + "unknown_tag", String.valueOf(tag)));
    private static final DynamicCommandExceptionType DUPLICATE_TARGET =
            new DynamicCommandExceptionType(entry -> Component.translatable(KEY + "duplicate_target", String.valueOf(entry)));
    private static final DynamicCommandExceptionType BLACKLISTED_TARGET =
            new DynamicCommandExceptionType(entry -> Component.translatable(KEY + "blacklisted_target", String.valueOf(entry)));
    private static final DynamicCommandExceptionType UNKNOWN_COLOR =
            new DynamicCommandExceptionType(color -> Component.translatable(KEY + "unknown_color", String.valueOf(color)));
    private static final DynamicCommandExceptionType FOLLOW_DISTANCE_TOO_LARGE =
            new DynamicCommandExceptionType(max -> Component.translatable(KEY + "followdistance.too_large", max));
    private static final Dynamic2CommandExceptionType NO_SUCH_INDEX =
            new Dynamic2CommandExceptionType((index, size) -> Component.translatable(KEY + "no_such_index", index, size));

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_TAGS = (context, builder) ->
            SharedSuggestionProvider.suggestResource(BuiltInRegistries.ENTITY_TYPE.getTagNames().map(TagKey::location), builder);
    private static final SuggestionProvider<CommandSourceStack> SUGGEST_PLAYERS = (context, builder) ->
            SharedSuggestionProvider.suggest(context.getSource().getOnlinePlayerNames(), builder);
    private static final SuggestionProvider<CommandSourceStack> SUGGEST_COLORS = (context, builder) ->
            SharedSuggestionProvider.suggest(Arrays.stream(DyeColor.values()).map(DyeColor::getSerializedName), builder);

    private ConfigCommand() {}

    /** An edit applied to each drone's data. Throwing leaves every drone unchanged. */
    @FunctionalInterface
    interface Edit {
        DroneData apply(DroneData data) throws CommandSyntaxException;
    }

    /** The executor for an edit, given the drone entities to act on, or null for the held drone. */
    @FunctionalInterface
    interface EditRunner {
        int run(CommandContext<CommandSourceStack> ctx, Collection<DroneEntity> drones) throws CommandSyntaxException;
    }

    /** The {@code config} subtree of {@code /seekerdrones}, registered by {@link SeekerDronesCommand}. */
    public static LiteralArgumentBuilder<CommandSourceStack> build(CommandBuildContext buildContext) {
        return Commands.literal("config")
                .then(Commands.literal("target")
                        .then(Commands.literal("add")
                                .then(Commands.literal("entity")
                                        .then(onDrones(Commands.argument("type", ResourceArgument.resource(buildContext, Registries.ENTITY_TYPE)),
                                                (ctx, drones) -> addTarget(ctx, drones, new TargetEntry(TargetEntry.Kind.ENTITY_TYPE,
                                                        ResourceArgument.getResource(ctx, "type", Registries.ENTITY_TYPE).key().location().toString())))))
                                .then(Commands.literal("tag")
                                        .then(onDrones(Commands.argument("tag", ResourceLocationArgument.id()).suggests(SUGGEST_TAGS),
                                                (ctx, drones) -> addTarget(ctx, drones, new TargetEntry(TargetEntry.Kind.TAG, tagArgument(ctx).toString())))))
                                .then(Commands.literal("player")
                                        .then(onDrones(Commands.argument("name", StringArgumentType.word()).suggests(SUGGEST_PLAYERS),
                                                (ctx, drones) -> addTarget(ctx, drones, new TargetEntry(TargetEntry.Kind.PLAYER_NAME,
                                                        StringArgumentType.getString(ctx, "name")))))))
                        .then(Commands.literal("remove")
                                .then(onDrones(Commands.argument("index", IntegerArgumentType.integer(1)),
                                        (ctx, drones) -> removeTarget(ctx, drones, IntegerArgumentType.getInteger(ctx, "index")))))
                        .then(onDrones(Commands.literal("clear"), ConfigCommand::clearTargets))
                        .then(onDrones(Commands.literal("list"), ConfigCommand::listTargets)))
                .then(Commands.literal("followdistance")
                        .then(onDrones(Commands.argument("distance", IntegerArgumentType.integer(1)),
                                (ctx, drones) -> setFollowDistance(ctx, drones, IntegerArgumentType.getInteger(ctx, "distance")))))
                .then(Commands.literal("patrolcenter")
                        .then(Commands.literal("set")
                                .then(onDrones(Commands.argument("pos", BlockPosArgument.blockPos()),
                                        (ctx, drones) -> setPatrolCenter(ctx, drones, Optional.of(GlobalPos.of(ctx.getSource().getLevel().dimension(),
                                                BlockPosArgument.getBlockPos(ctx, "pos")))))))
                        .then(onDrones(Commands.literal("clear"), (ctx, drones) -> setPatrolCenter(ctx, drones, Optional.empty()))))
                .then(Commands.literal("patrolradius")
                        .then(Commands.literal("set")
                                .then(onDrones(Commands.argument("radius", IntegerArgumentType.integer(1)),
                                        (ctx, drones) -> setPatrolRadius(ctx, drones, Optional.of(IntegerArgumentType.getInteger(ctx, "radius"))))))
                        .then(onDrones(Commands.literal("clear"), (ctx, drones) -> setPatrolRadius(ctx, drones, Optional.empty()))))
                .then(Commands.literal("color")
                        .then(onDrones(Commands.argument("color", StringArgumentType.word()).suggests(SUGGEST_COLORS),
                                (ctx, drones) -> setColor(ctx, drones, colorArgument(ctx)))));
    }

    /** Runs {@code runner} on the held drone, or on the drones picked by an optional trailing selector. */
    static <T extends ArgumentBuilder<CommandSourceStack, T>> T onDrones(T node, EditRunner runner) {
        return node
                .executes(ctx -> runner.run(ctx, null))
                .then(Commands.argument("drones", EntityArgument.entities())
                        .executes(ctx -> runner.run(ctx, SeekerDronesCommand.drones(EntityArgument.getEntities(ctx, "drones")))));
    }

    private static ResourceLocation tagArgument(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ResourceLocation id = ResourceLocationArgument.getId(ctx, "tag");
        if (BuiltInRegistries.ENTITY_TYPE.getTag(TagKey.create(Registries.ENTITY_TYPE, id)).isEmpty()) {
            throw UNKNOWN_TAG.create(id);
        }
        return id;
    }

    private static DyeColor colorArgument(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String name = StringArgumentType.getString(ctx, "color");
        DyeColor color = DyeColor.byName(name, null);
        if (color == null) {
            throw UNKNOWN_COLOR.create(name);
        }
        return color;
    }

    // --- Edits ---

    private static int addTarget(CommandContext<CommandSourceStack> ctx, Collection<DroneEntity> drones, TargetEntry entry) throws CommandSyntaxException {
        return apply(ctx.getSource(), drones, data -> {
            List<TargetEntry> targets = data.config().targets();
            if (targets.stream().anyMatch(existing -> existing.sameAs(entry))) {
                throw DUPLICATE_TARGET.create(entry.displayString());
            }
            if (TargetBlacklist.blocks(entry)) {
                throw BLACKLISTED_TARGET.create(entry.displayString());
            }
            List<TargetEntry> updated = new ArrayList<>(targets);
            updated.add(entry);
            return data.withConfig(data.config().withTargets(updated));
        }, count -> Component.translatable(KEY + "target.added", entry.displayString(), count));
    }

    /** Removes the entry at a 1-based index into the stored list, as shown by {@code target list}. */
    private static int removeTarget(CommandContext<CommandSourceStack> ctx, Collection<DroneEntity> drones, int index) throws CommandSyntaxException {
        return apply(ctx.getSource(), drones, data -> {
            List<TargetEntry> targets = data.config().targets();
            if (index > targets.size()) {
                throw NO_SUCH_INDEX.create(index, targets.size());
            }
            List<TargetEntry> updated = new ArrayList<>(targets);
            updated.remove(index - 1);
            return data.withConfig(data.config().withTargets(updated));
        }, count -> Component.translatable(KEY + "target.removed", index, count));
    }

    private static int clearTargets(CommandContext<CommandSourceStack> ctx, Collection<DroneEntity> drones) throws CommandSyntaxException {
        return apply(ctx.getSource(), drones, data -> data.withConfig(data.config().withTargets(List.of())),
                count -> Component.translatable(KEY + "target.cleared", count));
    }

    private static int setFollowDistance(CommandContext<CommandSourceStack> ctx, Collection<DroneEntity> drones, int distance) throws CommandSyntaxException {
        int max = ServerConfig.get(ServerConfig.DRONE_MAX_FOLLOW_DISTANCE);
        if (distance > max) {
            throw FOLLOW_DISTANCE_TOO_LARGE.create(max);
        }
        return apply(ctx.getSource(), drones, data -> data.withConfig(data.config().withFollowDistance(distance)),
                count -> Component.translatable(KEY + "followdistance.set", count, distance));
    }

    /** Sets the configured patrol center (section 3.2) in the command's dimension, or clears it. */
    private static int setPatrolCenter(CommandContext<CommandSourceStack> ctx, Collection<DroneEntity> drones, Optional<GlobalPos> center) throws CommandSyntaxException {
        return apply(ctx.getSource(), drones, data -> data.withConfig(data.config().withPatrolCenter(center)),
                count -> center
                        .map(c -> Component.translatable(KEY + "patrolcenter.set", count, c.pos().getX(), c.pos().getY(), c.pos().getZ(),
                                c.dimension().location().toString()))
                        .orElseGet(() -> Component.translatable(KEY + "patrolcenter.cleared", count)));
    }

    /**
     * Sets the wanted patrol radius, or clears it so the drone patrols at the largest radius its Patrol upgrades allow
     * (section 3.2). A radius above that max is kept but capped at runtime.
     */
    private static int setPatrolRadius(CommandContext<CommandSourceStack> ctx, Collection<DroneEntity> drones, Optional<Integer> radius) throws CommandSyntaxException {
        return apply(ctx.getSource(), drones, data -> data.withConfig(data.config().withPatrolRadius(radius)),
                count -> radius
                        .map(r -> Component.translatable(KEY + "patrolradius.set", count, r))
                        .orElseGet(() -> Component.translatable(KEY + "patrolradius.cleared", count)));
    }

    /** Sets the drone's color (section 2.6), which tints its model and item. */
    private static int setColor(CommandContext<CommandSourceStack> ctx, Collection<DroneEntity> drones, DyeColor color) throws CommandSyntaxException {
        return apply(ctx.getSource(), drones, data -> data.withConfig(data.config().withColor(color)),
                count -> Component.translatable(KEY + "color.set", count, Component.translatable("color.minecraft." + color.getName())));
    }

    /**
     * Applies the edit to the held drone or to every drone entity. All edits are computed first, so a failure on
     * any drone changes nothing. Entities go through setDroneData so their target matcher is rebuilt.
     */
    static int apply(CommandSourceStack source, Collection<DroneEntity> drones, Edit edit, IntFunction<Component> message) throws CommandSyntaxException {
        if (drones == null) {
            ItemStack stack = SeekerDronesCommand.heldDrone(source);
            stack.set(ModDataComponents.DRONE_DATA, edit.apply(DroneItem.getData(stack)));
            source.sendSuccess(() -> message.apply(1), true);
            return 1;
        }
        List<DroneData> updated = new ArrayList<>();
        for (DroneEntity drone : drones) {
            updated.add(edit.apply(drone.snapshotData()));
        }
        int i = 0;
        for (DroneEntity drone : drones) {
            drone.setDroneData(updated.get(i++));
        }
        int count = drones.size();
        source.sendSuccess(() -> message.apply(count), true);
        return count;
    }

    // --- List ---

    private static int listTargets(CommandContext<CommandSourceStack> ctx, Collection<DroneEntity> drones) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        if (drones == null) {
            listTargets(source, DroneItem.getData(SeekerDronesCommand.heldDrone(source)));
            return 1;
        }
        for (DroneEntity drone : drones) {
            listTargets(source, drone.snapshotData());
        }
        return drones.size();
    }

    /** Lists the stored entries with their 1-based index, marking the ones the fail-safe ignores (section 2.7). */
    private static void listTargets(CommandSourceStack source, DroneData data) {
        List<TargetEntry> targets = data.config().targets();
        int allowed = DroneStats.allowedTargetCount(data);
        boolean playerSeek = DroneStats.hasPlayerSeek(data);
        source.sendSuccess(() -> Component.translatable(KEY + "target.list.header",
                DroneItem.identity(data), targets.size(), allowed, data.config().followDistance()), false);
        if (targets.isEmpty()) {
            source.sendSuccess(() -> Component.translatable(KEY + "target.list.empty").withStyle(ChatFormatting.GRAY), false);
            return;
        }
        for (int i = 0; i < targets.size(); i++) {
            TargetEntry entry = targets.get(i);
            MutableComponent line = Component.translatable(KEY + "target.list.entry", i + 1,
                    Component.translatable(KEY + "target.kind." + entry.kind().getSerializedName()), entry.displayString());
            if (i >= allowed) {
                line.append(Component.translatable(KEY + "target.list.ignored_slot").withStyle(ChatFormatting.RED));
            } else if (entry.kind() == TargetEntry.Kind.PLAYER_NAME && !playerSeek) {
                line.append(Component.translatable(KEY + "target.list.ignored_player_seek").withStyle(ChatFormatting.RED));
            } else if (TargetBlacklist.blocks(entry)) {
                line.append(Component.translatable(KEY + "target.list.ignored_blacklisted").withStyle(ChatFormatting.RED));
            }
            source.sendSuccess(() -> line, false);
        }
    }
}
