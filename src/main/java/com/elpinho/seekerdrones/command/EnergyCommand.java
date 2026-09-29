package com.elpinho.seekerdrones.command;

import java.util.Collection;

import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.drone.DroneItem;
import com.elpinho.seekerdrones.drone.DroneStats;
import com.elpinho.seekerdrones.energy.EnergyFormat;
import com.elpinho.seekerdrones.energy.EnergyUnit;
import com.elpinho.seekerdrones.registry.ModAttachments;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * Debug command to read and set drone energy (DESIGN.md section 5) on the held drone item or on drone entities.
 * Requires permission level 2. Values are clamped to the drone's max energy. A drone entity set to 0 drops as an
 * item on its next energy drain. Kept as an admin/testing tool.
 */
public final class EnergyCommand {
    private static final String KEY = "commands.seekerdrones.energy.";

    private EnergyCommand() {}

    /** The {@code energy} subtree of {@code /seekerdrones}, registered by {@link SeekerDronesCommand}. */
    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("energy")
                .then(Commands.literal("set")
                        .then(ConfigCommand.onDrones(Commands.argument("amount", IntegerArgumentType.integer(0)),
                                (ctx, drones) -> set(ctx, drones, IntegerArgumentType.getInteger(ctx, "amount")))))
                .then(ConfigCommand.onDrones(Commands.literal("fill"), (ctx, drones) -> set(ctx, drones, Integer.MAX_VALUE)))
                .then(ConfigCommand.onDrones(Commands.literal("get"), EnergyCommand::get));
    }

    private static int set(CommandContext<CommandSourceStack> ctx, Collection<DroneEntity> drones, int amount) throws CommandSyntaxException {
        return ConfigCommand.apply(ctx.getSource(), drones, data -> data.withEnergy(Math.min(amount, DroneStats.maxEnergy(data))),
                count -> Component.translatable(KEY + "set", count));
    }

    private static int get(CommandContext<CommandSourceStack> ctx, Collection<DroneEntity> drones) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        if (drones == null) {
            get(source, DroneItem.getData(SeekerDronesCommand.heldDrone(source)));
            return 1;
        }
        for (DroneEntity drone : drones) {
            get(source, drone.snapshotData());
        }
        return drones.size();
    }

    /** Exact values, in the player's energy unit (DESIGN.md section 5.4). Amounts set with the command are always FE. */
    private static void get(CommandSourceStack source, DroneData data) {
        EnergyUnit unit = source.getPlayer() != null ? source.getPlayer().getData(ModAttachments.ENERGY_UNIT) : EnergyUnit.AUTO;
        source.sendSuccess(() -> Component.translatable(KEY + "get", DroneItem.identity(data),
                EnergyFormat.exactRatio(data.energy(), DroneStats.maxEnergy(data), unit)), false);
    }
}
