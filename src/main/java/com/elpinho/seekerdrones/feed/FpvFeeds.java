package com.elpinho.seekerdrones.feed;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.drone.DronePermissions;
import com.elpinho.seekerdrones.drone.DroneStats;
import com.elpinho.seekerdrones.mixin.ChunkMapAccessor;
import com.elpinho.seekerdrones.network.FpvEndPayload;
import com.elpinho.seekerdrones.network.FpvStartPayload;
import com.elpinho.seekerdrones.network.FpvStatusPayload;
import com.elpinho.seekerdrones.remote.RemoteControl;
import com.elpinho.seekerdrones.remote.RemoteReach;

import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The server side of the FPV feed (DESIGN.md section 2.12): who watches which drone, the checks that end a feed, and
 * the view the chunk and entity tracking use for a viewer. While a player watches, the mixins in
 * {@code com.elpinho.seekerdrones.mixin} center their chunk tracking, entity tracking and distance-based sounds on
 * the drone instead of their body. Only chunks that are already loaded are sent, and nothing is loaded for the feed.
 * <p>
 * A pilot session (section 2.13) is a remote feed whose player also flies the drone: the drone is PILOTED while it
 * lasts, and its AI resumes when it ends, whatever ends it.
 */
public final class FpvFeeds {
    /** How often (ticks) the entities around the drone are re-checked for the viewer, like a moving player's would be. */
    private static final int TRACKING_REFRESH_TICKS = 10;

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    private static final class Session {
        final ServerPlayer player;
        final DroneEntity drone;
        /** Opened from a Drone Remote: the remote's reach rules apply. Otherwise from the operator command. */
        final boolean remote;
        /** The player flies the drone (section 2.13), not just watches it. Always opened from a remote. */
        final boolean pilot;
        long section;
        int age;

        Session(ServerPlayer player, DroneEntity drone, boolean remote, boolean pilot) {
            this.player = player;
            this.drone = drone;
            this.remote = remote;
            this.pilot = pilot;
            this.section = SectionPos.of(drone).asLong();
        }
    }

    private FpvFeeds() {}

    // --- Queries (also used by the mixins, so they return fast when nobody watches) ---

    /** The drone the player watches, or null. */
    @Nullable
    public static DroneEntity viewedDrone(ServerPlayer player) {
        if (SESSIONS.isEmpty()) {
            return null;
        }
        Session session = SESSIONS.get(player.getUUID());
        return session != null && session.player == player ? session.drone : null;
    }

    public static boolean isViewing(ServerPlayer player) {
        return viewedDrone(player) != null;
    }

    /** The chunk the player's chunk tracking centers on: the watched drone's, or their own. */
    public static ChunkPos viewChunk(ServerPlayer player, ChunkPos own) {
        DroneEntity drone = viewedDrone(player);
        return drone != null ? drone.chunkPosition() : own;
    }

    /** Where the player sees from, for entity tracking and sounds: the watched drone, or their own position. */
    public static Vec3 viewPosition(ServerPlayer player, Vec3 own) {
        DroneEntity drone = viewedDrone(player);
        return drone != null ? drone.position() : own;
    }

    /** The view distance for the player's chunk tracking: capped by {@code camera.viewDistance} while watching. */
    public static int viewDistance(ServerPlayer player, int own) {
        return isViewing(player) ? Math.min(own, ServerConfig.get(ServerConfig.CAMERA_VIEW_DISTANCE)) : own;
    }

    /** Whether anyone watches this drone, which costs it {@code camera.feedEnergyPerTick} (section 2.12). */
    public static boolean isWatched(DroneEntity drone) {
        if (SESSIONS.isEmpty()) {
            return false;
        }
        for (Session session : SESSIONS.values()) {
            if (session.drone == drone) {
                return true;
            }
        }
        return false;
    }

    /** The player piloting this drone, or null (section 2.13). */
    @Nullable
    public static ServerPlayer pilotOf(DroneEntity drone) {
        if (SESSIONS.isEmpty()) {
            return null;
        }
        for (Session session : SESSIONS.values()) {
            if (session.pilot && session.drone == drone) {
                return session.player;
            }
        }
        return null;
    }

    /** The drone the player pilots, or null. */
    @Nullable
    public static DroneEntity pilotedDrone(ServerPlayer player) {
        Session session = SESSIONS.isEmpty() ? null : SESSIONS.get(player.getUUID());
        return session != null && session.pilot && session.player == player ? session.drone : null;
    }

    // --- Starting and stopping ---

    /**
     * Starts a feed from a Drone Remote: the remote, operator and reach rules of section 2.10, plus the FPV Camera.
     * Tells the player why when it can't start.
     */
    public static void startFromRemote(ServerPlayer player, String droneId) {
        startFromRemote(player, droneId, false);
    }

    /**
     * Starts watching (or piloting, section 2.13) from a Drone Remote: the remote, operator and reach rules of section
     * 2.10, plus the FPV Camera. Only one player pilots a drone at a time. Tells the player why when it can't start.
     */
    public static void startFromRemote(ServerPlayer player, String droneId, boolean pilot) {
        if (RemoteControl.heldLink(player, droneId).isEmpty()) {
            return;
        }
        RemoteControl.Target target = RemoteControl.find(player, droneId);
        DroneEntity drone = target.drone();
        if (target.reach() != RemoteReach.OK || drone == null) {
            if (target.reach() == RemoteReach.DENIED) {
                DronePermissions.sendDenied(player);
            }
            return;
        }
        if (!DroneStats.hasFpvCamera(drone.snapshotData())) {
            player.displayClientMessage(Component.translatable("message.seekerdrones.feed.no_camera"), true);
            return;
        }
        if (pilot) {
            ServerPlayer current = pilotOf(drone);
            if (current != null && current != player) {
                player.displayClientMessage(Component.translatable("message.seekerdrones.pilot.taken", current.getDisplayName()), true);
                return;
            }
        }
        start(player, drone, true, pilot);
    }

    /** Starts watching the drone, replacing any feed the player was watching. The caller has done the checks. */
    public static void start(ServerPlayer player, DroneEntity drone, boolean remote) {
        start(player, drone, remote, false);
    }

    private static void start(ServerPlayer player, DroneEntity drone, boolean remote, boolean pilot) {
        Session previous = SESSIONS.put(player.getUUID(), new Session(player, drone, remote, pilot));
        if (previous != null && previous.pilot && (previous.drone != drone || !pilot)) {
            previous.drone.stopPiloting();
        }
        if (pilot && !drone.isPiloted()) {
            drone.startPiloting();
        }
        refreshView(player);
        send(player, new FpvStartPayload(drone.getId(), drone.getDroneId(), remote, pilot));
        sendStatus(player, drone);
    }

    /** Ends the player's feed, if any. {@code signalLost} shows "Signal lost" before the view switches back. */
    public static boolean stop(ServerPlayer player, boolean signalLost) {
        Session session = SESSIONS.remove(player.getUUID());
        if (session == null) {
            return false;
        }
        if (session.pilot) {
            session.drone.stopPiloting();
        }
        if (session.player == player && !player.hasDisconnected()) {
            refreshView(player);
            send(player, new FpvEndPayload(signalLost));
        }
        return true;
    }

    /**
     * Ends the piloting of this drone, if anyone pilots it: a Recall, Hold or Charge from any operator's remote
     * (section 2.13). The pilot goes back to their own view.
     */
    public static void endPiloting(DroneEntity drone) {
        ServerPlayer pilot = pilotOf(drone);
        if (pilot != null) {
            stop(pilot, false);
        }
    }

    /** The pilot's movement keys, applied only to the drone they pilot. */
    public static void pilotInput(ServerPlayer player, PilotInput input) {
        DroneEntity drone = pilotedDrone(player);
        if (drone != null) {
            drone.setPilotInput(input.sanitized());
        }
    }

    /** Re-centers the player's chunk tracking and re-checks which entities they see, right away. */
    private static void refreshView(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        ((ChunkMapAccessor) level.getChunkSource().chunkMap).seekerdrones$updateChunkTracking(player);
        level.getChunkSource().move(player);
    }

    // --- Ticking ---

    public static void onServerTick(ServerTickEvent.Post event) {
        if (SESSIONS.isEmpty()) {
            return;
        }
        int recheck = ServerConfig.get(ServerConfig.CAMERA_RECHECK_INTERVAL);
        int hudSync = ServerConfig.get(ServerConfig.CAMERA_HUD_SYNC_INTERVAL);
        for (Session session : new ArrayList<>(SESSIONS.values())) {
            ServerPlayer player = session.player;
            if (player.hasDisconnected() || player.isRemoved()) {
                SESSIONS.remove(player.getUUID());
                if (session.pilot) {
                    session.drone.stopPiloting();
                }
                continue;
            }
            DroneEntity drone = session.drone;
            if (drone.isRemoved() || !drone.isAlive() || drone.level() != player.level()) {
                stop(player, true);
                continue;
            }
            session.age++;
            if (session.remote && session.age % recheck == 0) {
                Boolean lost = remoteCheck(player, drone);
                if (lost != null) {
                    stop(player, lost);
                    continue;
                }
            }
            long section = SectionPos.of(drone).asLong();
            if (section != session.section || session.age % TRACKING_REFRESH_TICKS == 0) {
                session.section = section;
                player.serverLevel().getChunkSource().move(player);
            }
            if (session.age % hudSync == 0) {
                sendStatus(player, drone);
            }
        }
    }

    /**
     * The checks of a feed opened from a remote (section 2.12). Null while it may go on, otherwise whether it ends
     * with "Signal lost" (the drone went out of reach) or right away (the player let go of it).
     */
    @Nullable
    private static Boolean remoteCheck(ServerPlayer player, DroneEntity drone) {
        String droneId = drone.getDroneId();
        if (RemoteControl.heldLink(player, droneId).isEmpty() || !DroneStats.hasFpvCamera(drone.snapshotData())) {
            return false;
        }
        RemoteControl.Target target = RemoteControl.find(player, droneId);
        if (target.reach() == RemoteReach.DENIED) {
            return false;
        }
        return target.reach() == RemoteReach.OK && target.drone() == drone ? null : true;
    }

    private static void sendStatus(ServerPlayer player, DroneEntity drone) {
        boolean lowPower = drone.isPiloted() && drone.isBelowReturnThreshold();
        send(player, new FpvStatusPayload(drone.getEnergy(), DroneStats.maxEnergy(drone.snapshotData()), (float) drone.getDrainRate(),
                (float) drone.getSolarRate(), lowPower));
    }

    /** Skipped for connections that didn't negotiate the channel (e.g. GameTest fake players). */
    private static void send(ServerPlayer player, CustomPacketPayload payload) {
        if (player.connection != null && player.connection.hasChannel(payload.type())) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }

    // --- Events that end a feed ---

    public static void onDamage(LivingDamageEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player && event.getNewDamage() > 0) {
            stop(player, false);
        }
    }

    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            stop(player, false);
        }
    }

    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            stop(player, false);
        }
    }

    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        Session session = SESSIONS.remove(event.getEntity().getUUID());
        if (session != null && session.pilot) {
            session.drone.stopPiloting();
        }
    }

    public static void onServerStopped(ServerStoppedEvent event) {
        SESSIONS.clear();
    }
}
