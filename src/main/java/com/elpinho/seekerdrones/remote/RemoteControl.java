package com.elpinho.seekerdrones.remote;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import javax.annotation.Nullable;

import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.drone.DroneConfig;
import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.drone.DroneIndex;
import com.elpinho.seekerdrones.drone.DronePermissions;
import com.elpinho.seekerdrones.drone.DroneState;
import com.elpinho.seekerdrones.drone.DroneStats;
import com.elpinho.seekerdrones.network.DroneStatusPayload;
import com.elpinho.seekerdrones.network.EditRemoteSettingsPayload;
import com.elpinho.seekerdrones.network.LinkGlowPayload;
import com.elpinho.seekerdrones.network.RemoteStatusPayload;
import com.elpinho.seekerdrones.programming.ProgramRules;
import com.elpinho.seekerdrones.registry.ModDataComponents;
import com.elpinho.seekerdrones.registry.ModSounds;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The server side of the Drone Remote (DESIGN.md section 2.10): linking, finding the linked drone, and the commands
 * and settings edits sent from the remote's screen. Operator permission and reach are checked on every use, never
 * when the remote was linked.
 */
public final class RemoteControl {
    private RemoteControl() {}

    /**
     * What looking for a link's drone found.
     *
     * @param drone    the drone, only for {@link RemoteReach#OK} and {@link RemoteReach#OUT_OF_RANGE}
     * @param distance blocks from the player to the drone, only meaningful with a drone
     */
    public record Target(RemoteReach reach, @Nullable DroneEntity drone, int distance) {
        private static Target of(RemoteReach reach) {
            return new Target(reach, null, 0);
        }
    }

    // --- Reach (section 2.10) ---

    /**
     * Finds the drone with this ID for the player: deployed, loaded, in their dimension, one they may use, and within
     * {@code remote.range} blocks. The operator check comes before the range, so a non-operator learns nothing about
     * where the drone is.
     */
    public static Target find(ServerPlayer player, String droneId) {
        if (droneId.isEmpty()) {
            return Target.of(RemoteReach.NOT_DEPLOYED);
        }
        DroneEntity drone = DroneIndex.find(player.serverLevel(), droneId);
        if (drone == null) {
            return Target.of(DroneIndex.find(player.server, droneId) != null ? RemoteReach.OTHER_DIMENSION : RemoteReach.NOT_DEPLOYED);
        }
        if (!drone.isAlive()) {
            return Target.of(RemoteReach.NOT_DEPLOYED);
        }
        if (!DronePermissions.canInteract(player, drone.snapshotData())) {
            return Target.of(RemoteReach.DENIED);
        }
        double distance = player.distanceTo(drone);
        double range = ServerConfig.get(ServerConfig.REMOTE_RANGE);
        return new Target(distance > range ? RemoteReach.OUT_OF_RANGE : RemoteReach.OK, drone, (int) distance);
    }

    /** The link of a Drone Remote in either of the player's hands that is linked to this drone, if any. */
    public static Optional<RemoteLink> heldLink(ServerPlayer player, String droneId) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            RemoteLink link = stack.getItem() instanceof DroneRemoteItem ? stack.get(ModDataComponents.REMOTE_LINK) : null;
            if (link != null && link.droneId().equals(droneId)) {
                return Optional.of(link);
            }
        }
        return Optional.empty();
    }

    /** Sends the remote screen's values. {@code open} opens the screen, otherwise it refreshes one that is open. */
    public static void sendStatus(ServerPlayer player, RemoteLink link, boolean open) {
        Target target = find(player, link.droneId());
        Optional<DroneStatusPayload> status = target.drone() != null
                ? Optional.of(DroneStatusPayload.of(target.drone(), false))
                : Optional.empty();
        send(player, new RemoteStatusPayload(open, link, target.reach(), target.distance(), status));
    }

    /** Skipped for connections that didn't negotiate the channel (e.g. GameTest fake players). */
    private static void send(ServerPlayer player, CustomPacketPayload payload) {
        if (player.connection.hasChannel(payload.type())) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }

    // --- Linking (section 2.10) ---

    /** Links the remote to the drone: replaces any earlier link, beeps and makes the drone glow for this player. */
    public static void link(ServerPlayer player, ItemStack remote, DroneEntity drone) {
        DroneData data = drone.snapshotData();
        if (!data.hasDroneId()) {
            player.displayClientMessage(Component.translatable("message.seekerdrones.remote.no_id"), true);
            return;
        }
        if (!DronePermissions.canInteract(player, data)) {
            DronePermissions.sendDenied(player);
            return;
        }
        RemoteLink link = RemoteLink.of(data);
        remote.set(ModDataComponents.REMOTE_LINK, link);
        player.displayClientMessage(Component.translatable("message.seekerdrones.remote.linked", link.identity()), true);
        // The lock-on beep, heard only by the linking player.
        player.playNotifySound(ModSounds.DRONE_LOCK_ON.get(), SoundSource.PLAYERS, ServerConfig.get(ServerConfig.SOUNDS_LOCK_ON_VOLUME).floatValue(),
                ServerConfig.get(ServerConfig.SOUNDS_LOCK_ON_PITCH).floatValue());
        send(player, new LinkGlowPayload(drone.getId(), ServerConfig.get(ServerConfig.REMOTE_LINK_GLOW_TICKS)));
    }

    /**
     * Shift + right-click in the air: links the drone nearest the crosshair among those within
     * {@code remote.linkRange} blocks and {@code remote.linkConeAngle} degrees of the look direction, that the player
     * may use and can see. One AABB query, sorted by angle and raycast nearest-to-the-crosshair first, with the same
     * cap on raycasts as the target scan (section 3.3).
     */
    public static void linkByAiming(ServerPlayer player, ItemStack remote) {
        ServerLevel level = player.serverLevel();
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        double range = ServerConfig.get(ServerConfig.REMOTE_LINK_RANGE);
        double cone = Math.toRadians(ServerConfig.get(ServerConfig.REMOTE_LINK_CONE_ANGLE));
        double minCosine = Math.cos(cone);
        double rangeSqr = range * range;
        // The box around the look ray, as wide as the cone is at the far end.
        double spread = Math.min(range, range * Math.tan(Math.min(cone, Math.toRadians(89)))) + 1;
        AABB box = new AABB(eye, eye.add(look.scale(range))).inflate(spread);

        record Candidate(DroneEntity drone, Vec3 center, double cosine) {}
        List<Candidate> candidates = new ArrayList<>();
        for (DroneEntity drone : level.getEntitiesOfClass(DroneEntity.class, box, DroneEntity::isAlive)) {
            Vec3 center = drone.getBoundingBox().getCenter();
            Vec3 toDrone = center.subtract(eye);
            double distanceSqr = toDrone.lengthSqr();
            if (distanceSqr > rangeSqr || distanceSqr < 1.0E-6) {
                continue;
            }
            double cosine = toDrone.normalize().dot(look);
            if (cosine >= minCosine && !drone.getDroneId().isEmpty() && DronePermissions.canInteract(player, drone.snapshotData())) {
                candidates.add(new Candidate(drone, center, cosine));
            }
        }
        // The smallest angle is the largest cosine.
        candidates.sort(Comparator.comparingDouble(Candidate::cosine).reversed());
        int raycasts = Math.min(candidates.size(), ServerConfig.get(ServerConfig.DRONE_MAX_RAYCASTS_PER_SCAN));
        for (int i = 0; i < raycasts; i++) {
            Candidate candidate = candidates.get(i);
            if (level.clip(new ClipContext(eye, candidate.center(), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
                    .getType() == HitResult.Type.MISS) {
                link(player, remote, candidate.drone());
                return;
            }
        }
        player.displayClientMessage(Component.translatable("message.seekerdrones.remote.none_in_sight"), true);
    }

    // --- Commands and settings (section 2.10) ---

    /**
     * Runs a command from the remote screen. The player must hold a remote linked to the drone, still be an operator
     * of it, and have it within reach. Whatever happens, the screen gets fresh values back.
     */
    public static void command(ServerPlayer player, String droneId, RemoteCommand command) {
        Optional<RemoteLink> link = heldLink(player, droneId);
        if (link.isEmpty()) {
            return;
        }
        Target target = find(player, droneId);
        DroneEntity drone = target.drone();
        if (target.reach() == RemoteReach.OK && drone != null) {
            switch (command) {
                case RECALL -> drone.recall(player);
                case HOLD -> drone.hold();
                case RESUME -> drone.resume();
                case CHARGE -> {
                    if (!drone.returnToCharge()) {
                        DroneState state = drone.getState();
                        boolean busy = state == DroneState.RETURNING || state == DroneState.CHARGING;
                        player.displayClientMessage(Component.translatable(busy
                                ? "message.seekerdrones.remote.already_charging"
                                : "message.seekerdrones.remote.no_station"), true);
                    }
                }
                case CENTER -> {
                    GlobalPos here = GlobalPos.of(player.level().dimension(), BlockPos.containing(player.getEyePosition()));
                    if (!drone.setPatrolCenter(here)) {
                        player.displayClientMessage(Component.translatable("message.seekerdrones.remote.needs_patrol"), true);
                    }
                }
            }
        } else if (target.reach() == RemoteReach.DENIED) {
            DronePermissions.sendDenied(player);
        }
        sendStatus(player, link.get(), false);
    }

    /**
     * Applies the settings a remote can change: follow distance, label and color, plus the patrol radius and speed for
     * a drone with Patrol. Everything else of the drone's configuration stays as it is, and the Programming Station's
     * rules and caps check the values (section 7.2).
     */
    public static void editSettings(ServerPlayer player, EditRemoteSettingsPayload edit) {
        Optional<RemoteLink> link = heldLink(player, edit.droneId());
        if (link.isEmpty()) {
            return;
        }
        Target target = find(player, edit.droneId());
        DroneEntity drone = target.drone();
        if (target.reach() == RemoteReach.OK && drone != null) {
            DroneData data = drone.snapshotData();
            DroneConfig previous = data.config();
            DroneConfig requested = previous.withFollowDistance(edit.followDistance()).withLabel(edit.label()).withColor(edit.color());
            if (DroneStats.isPatrolling(data)) {
                requested = requested.withPatrolRadius(edit.patrolRadius()).withPatrolSpeed(edit.patrolSpeed());
            }
            ProgramRules.checkConfig(requested, previous, data.upgrades(), drone.level().dimension())
                    .ifPresent(config -> drone.setDroneData(data.withConfig(config)));
        } else if (target.reach() == RemoteReach.DENIED) {
            DronePermissions.sendDenied(player);
        }
        sendStatus(player, link.get(), false);
    }
}
