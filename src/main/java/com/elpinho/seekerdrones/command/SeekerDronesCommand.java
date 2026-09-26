package com.elpinho.seekerdrones.command;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.drone.DroneItem;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;

import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

/**
 * The {@code /seekerdrones} root command (permission level 2) and helpers shared by its subcommands, which act on
 * the drone item in the player's main hand or on drone entities picked by a selector.
 */
public final class SeekerDronesCommand {
    private static final SimpleCommandExceptionType NOT_HOLDING_DRONE =
            new SimpleCommandExceptionType(Component.translatable("commands.seekerdrones.not_holding_drone"));
    private static final SimpleCommandExceptionType NO_DRONES =
            new SimpleCommandExceptionType(Component.translatable("commands.seekerdrones.no_drones"));

    private SeekerDronesCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext) {
        dispatcher.register(Commands.literal("seekerdrones")
                .requires(source -> source.hasPermission(2))
                .then(GroupCommand.build())
                .then(ConfigCommand.build(buildContext)));
    }

    /** The drone item in the executing player's main hand. */
    static ItemStack heldDrone(CommandSourceStack source) throws CommandSyntaxException {
        ItemStack stack = source.getPlayerOrException().getMainHandItem();
        if (!(stack.getItem() instanceof DroneItem)) {
            throw NOT_HOLDING_DRONE.create();
        }
        return stack;
    }

    /** The drones among the selected entities. Fails if there are none. */
    static List<DroneEntity> drones(Collection<? extends Entity> entities) throws CommandSyntaxException {
        List<DroneEntity> drones = new ArrayList<>();
        for (Entity entity : entities) {
            if (entity instanceof DroneEntity drone) {
                drones.add(drone);
            }
        }
        if (drones.isEmpty()) {
            throw NO_DRONES.create();
        }
        return drones;
    }
}
