package com.elpinho.seekerdrones.commandcenter;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.drone.DroneState;
import com.elpinho.seekerdrones.drone.DroneStats;
import com.elpinho.seekerdrones.feed.FpvFeeds;
import com.elpinho.seekerdrones.network.CommandCenterOrderPayload;
import com.elpinho.seekerdrones.network.CommandCenterOrderPayload.Mode;
import com.elpinho.seekerdrones.remote.RemoteControl;
import com.elpinho.seekerdrones.remote.RemoteReach;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * The server side of the Command Center's group orders (DESIGN.md section 7.10). The player must have a Command
 * Center's menu open (and still valid), and it must be powered. Each drone must pass the station reach rule and be
 * able to take the mode; the spot must be inside the area and in a loaded chunk. Drones that fail are skipped, and the
 * action bar reports what happened. Machines never check operator permissions; this is direct player interaction, so
 * the station reach rule's operator check applies (with no server-operator bypass).
 */
public final class CommandCenterOrders {
    private static final String KEY = "message.seekerdrones.command_center.";

    private CommandCenterOrders() {}

    /**
     * The Move spot moved at random by up to {@code commandCenter.moveSpread} blocks along each axis, so a group doesn't
     * stack on one block.
     */
    private static Vec3 spread(ServerLevel level, Vec3 top) {
        double spread = ServerConfig.get(ServerConfig.COMMAND_CENTER_MOVE_SPREAD);
        var random = level.getRandom();
        return top.add((random.nextDouble() * 2 - 1) * spread, (random.nextDouble() * 2 - 1) * spread, (random.nextDouble() * 2 - 1) * spread);
    }

    public static void give(ServerPlayer player, CommandCenterOrderPayload payload) {
        if (!(player.containerMenu instanceof CommandCenterMenu menu) || !menu.stillValid(player)) {
            return;
        }
        CommandCenterBlockEntity station = menu.getCommandCenter();
        if (station == null || !(station.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!station.isPowered()) {
            player.displayClientMessage(Component.translatable(KEY + "no_power"), true);
            return;
        }
        Mode mode = payload.mode();
        Optional<Vec3> top = Optional.empty();
        CommandCenterOrderPayload.Spot spot = payload.spot().orElse(null);
        if (mode.aims()) {
            if (spot == null || !station.inArea(spot.x(), spot.z())) {
                player.displayClientMessage(Component.translatable(KEY + "out_of_range"), true);
                return;
            }
            if (!level.hasChunkAt(BlockPos.containing(spot.x(), station.getBlockPos().getY(), spot.z()))) {
                player.displayClientMessage(Component.translatable(KEY + "not_loaded"), true);
                return;
            }
            if (mode != Mode.PATROL_HERE) {
                top = station.orderTop(level, spot.x(), spot.z());
                if (top.isEmpty()) {
                    player.displayClientMessage(Component.translatable(KEY + "not_loaded"), true);
                    return;
                }
            }
        }
        int sent = 0;
        int outOfReach = 0;
        int noStation = 0;
        Set<String> ids = new LinkedHashSet<>(payload.droneIds());
        for (String id : ids) {
            RemoteControl.Target target = RemoteControl.findThroughStation(player, id, station);
            DroneEntity drone = target.drone();
            if (target.reach() != RemoteReach.OK || drone == null) {
                outOfReach++;
                continue;
            }
            DroneData data = drone.snapshotData();
            if (mode == Mode.STRIKE && !DroneStats.isExplosive(data) || mode == Mode.PATROL_HERE && !DroneStats.isPatrolling(data)) {
                // Can't take the mode: left out without a mention, the picker's tooltip said so.
                continue;
            }
            if (mode != Mode.PATROL_HERE) {
                // Any order ends piloting (section 7.10).
                FpvFeeds.endPiloting(drone);
            }
            switch (mode) {
                case MOVE -> drone.moveTo(spread(level, top.get()));
                case STRIKE -> drone.strike(top.get());
                case PATROL_HERE -> {
                    GlobalPos current = drone.getPatrolCenter();
                    int y = current != null ? current.pos().getY() : drone.blockPosition().getY();
                    drone.setPatrolCenter(GlobalPos.of(level.dimension(), BlockPos.containing(spot.x(), y, spot.z())));
                }
                case HOLD -> drone.hold();
                case RETURN_TO_CHARGE -> {
                    if (!drone.returnToCharge() && drone.getState() != DroneState.RETURNING
                            && drone.getState() != DroneState.CHARGING) {
                        noStation++;
                        continue;
                    }
                }
                case RELEASE -> drone.resume();
            }
            sent++;
        }
        MutableComponent message = Component.translatable(KEY + (sent == 1 ? "sent_one" : "sent"), sent);
        if (outOfReach > 0) {
            message.append(" ").append(Component.translatable(KEY + "out_of_reach", outOfReach));
        }
        if (noStation > 0) {
            message.append(" ").append(Component.translatable(KEY + "no_station", noStation));
        }
        player.displayClientMessage(message, true);
    }
}
