package com.elpinho.seekerdrones.drone;

import java.util.UUID;

import javax.annotation.Nullable;

import com.elpinho.seekerdrones.config.ServerConfig;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * The orders a drone is given from outside its own AI, and their NBT: the Drone Remote's Hold and Recall (section
 * 2.10) and the Command Center's Move (with guarding the spot) and Strike (section 7.10). Orders are saved with the
 * entity but not in the drone data, so picking the drone up clears them. The last order given wins, the return
 * threshold overrides any of them, and an order isn't restored after charging. Server side only.
 * <p>
 * HOLDING, RECALLED, MOVING and STRIKING are passive: no scanning, chasing or claiming. A guard order lives on while
 * the drone chases (CHASING or FOLLOWING), so it comes back to the spot afterwards.
 */
final class DroneOrders {
    private static final String TAG_RECALL_PLAYER = "RecallPlayer";
    private static final String TAG_RECALL_HOVER_LEFT = "RecallHoverLeft";
    private static final String TAG_RECALL_HOME = "RecallHome";
    private static final String TAG_ORDER = "Order";
    private static final String TAG_ORDER_SPOT = "OrderSpot";
    private static final String TAG_ORDER_TICKS_LEFT = "OrderTicksLeft";

    /** How close (blocks) a recalled drone must get to its spot by the player before it hovers there (section 2.10). */
    private static final double RECALL_REACH_DISTANCE = 0.75;
    /** How close (blocks) a drone must get to a Move or guard spot to count as there. */
    private static final double SPOT_REACH_DISTANCE = 1.0;

    private final DroneEntity drone;

    // --- Hold and Recall (section 2.10) ---

    /** The player a RECALLED drone flies to. */
    @Nullable
    private UUID recallPlayer;
    /**
     * Ticks a recalled drone has left to hold by the player before it goes back on its own, or -1 while it is still
     * flying to them (RECALLED) or for a Hold from the remote, which has no timeout (HOLDING).
     */
    private int recallHoverLeft = -1;
    /** Where a recalled drone without Patrol goes back to afterwards, or null. */
    @Nullable
    private Vec3 recallHome;
    /** How far in front of the player the recall spot is, shortened if the full distance is blocked. */
    private double recallRange = -1;

    // --- Command Center orders (section 7.10) ---

    /** The Command Center order being carried out, or null. */
    @Nullable
    private DroneOrder order;
    /** Where the order flies the drone: above the spot for Move and guarding, the top block for Strike. */
    @Nullable
    private Vec3 spot;
    /** Ticks left before a Move or Strike is dropped, or of guarding. */
    private int ticksLeft;

    DroneOrders(DroneEntity drone) {
        this.drone = drone;
    }

    /** The Command Center order being carried out, if any, with its spot and ticks left. */
    @Nullable
    DroneOrder.Info info() {
        return order != null && spot != null ? new DroneOrder.Info(order, ticksLeft, spot) : null;
    }

    /** Whether the drone guards a spot: hovering there (GUARDING), or chasing from it. */
    boolean isGuarding() {
        return order == DroneOrder.GUARD;
    }

    /** Whether the state is one of the passive orders: no scanning, chasing or claiming. */
    static boolean isPassive(DroneState state) {
        return state == DroneState.HOLDING || state == DroneState.RECALLED || state == DroneState.MOVING || state == DroneState.STRIKING;
    }

    /** Whether the state belongs to an order that Resume (or Release) ends. */
    static boolean isResumable(DroneState state) {
        return state.isResumable();
    }

    /** Forgets every order without changing the state: something else takes over (returning, piloting, a new order). */
    void clear() {
        recallPlayer = null;
        recallHoverLeft = -1;
        recallHome = null;
        order = null;
        spot = null;
        ticksLeft = 0;
    }

    // --- Giving orders ---

    /**
     * Recall: flies to {@code remote.recallDistance} blocks in front of the player at eye level, tracking them
     * (RECALLED), then holds there (HOLDING) for {@code remote.recallHoverTicks} and goes back to its patrol, unless
     * resumed first (section 2.10). A drone without Patrol goes back to where it was recalled.
     */
    void recall(Player player) {
        DroneState state = drone.getState();
        Vec3 home = null;
        if (state == DroneState.RECALLED || state == DroneState.HOLDING && recallHoverLeft >= 0) {
            // Recalled again: it still goes back to where it was first recalled.
            home = recallHome;
        } else if (!DroneStats.isPatrolling(drone.getDroneData())) {
            home = Vec3.atBottomCenterOf(drone.blockPosition());
        }
        drone.dropActivity();
        clear();
        recallPlayer = player.getUUID();
        recallRange = -1;
        recallHome = home;
        drone.setState(DroneState.RECALLED);
    }

    /** Hold: drops any target and hovers where it is until resumed (section 2.10). */
    void hold() {
        drone.dropActivity();
        clear();
        drone.setState(DroneState.HOLDING);
    }

    /**
     * Move (section 7.10): flies to {@code commandCenter.moveHeight} blocks above the top block {@code top}, then guards
     * the spot for {@code commandCenter.guardTicks}.
     */
    void move(Vec3 top) {
        drone.dropActivity();
        clear();
        order = DroneOrder.MOVE;
        spot = top.add(0, ServerConfig.get(ServerConfig.COMMAND_CENTER_MOVE_HEIGHT), 0);
        ticksLeft = ServerConfig.get(ServerConfig.COMMAND_CENTER_MOVE_TIMEOUT_TICKS);
        drone.setState(DroneState.MOVING);
    }

    /** Strike (section 7.10): rushes to the top block {@code top} and detonates there. Only for Explosive drones. */
    void strike(Vec3 top) {
        drone.dropActivity();
        clear();
        order = DroneOrder.STRIKE;
        spot = top;
        ticksLeft = ServerConfig.get(ServerConfig.COMMAND_CENTER_MOVE_TIMEOUT_TICKS);
        drone.setState(DroneState.STRIKING);
    }

    /**
     * Resume (the remote) and Release (the Command Center) end Hold, Recall, Move, guarding and Strike: the drone goes
     * back to normal behavior at once. A recalled drone without Patrol goes back to where it was recalled, as when its
     * hold times out (section 2.10). Returns false if there was nothing to end.
     */
    boolean release() {
        if (order == DroneOrder.GUARD) {
            // Guarding, or chasing from the spot: a chase goes on, then the drone goes back to normal.
            endGuard();
            return true;
        }
        if (isPassive(drone.getState())) {
            endOrder();
            return true;
        }
        return false;
    }

    // --- Ticking ---

    /**
     * Runs the passive orders (HOLDING, RECALLED, MOVING, STRIKING). Returns false, and does nothing, in any other
     * state, so the normal AI runs.
     */
    boolean tickPassive(DroneState state, boolean staggered) {
        switch (state) {
            case HOLDING, RECALLED -> tickHoldOrRecall(state, staggered);
            case MOVING -> tickMoving(staggered);
            case STRIKING -> tickStriking(staggered);
            default -> {
                return false;
            }
        }
        return true;
    }

    /**
     * Counts a guard order down, whether the drone hovers at the spot or chases from it. When it runs out, the drone
     * goes back to normal; one in the middle of a chase finishes it first (section 7.10).
     */
    void tickGuardTimer() {
        if (order == DroneOrder.GUARD && --ticksLeft <= 0) {
            endGuard();
        }
    }

    /** With no target while guarding: flies back to the spot, then hovers there (GUARDING). */
    void tickGuardHover(boolean staggered) {
        if (drone.getState() != DroneState.GUARDING) {
            drone.setState(DroneState.GUARDING);
        }
        double acceleration = ServerConfig.get(ServerConfig.DRONE_ACCELERATION);
        if (drone.distanceToSqr(spot) > SPOT_REACH_DISTANCE * SPOT_REACH_DISTANCE
                && drone.steerTowards(spot, ServerConfig.get(ServerConfig.DRONE_CRUISE_SPEED), acceleration, spot, staggered, false)) {
            return;
        }
        hover(acceleration);
    }

    private void tickHoldOrRecall(DroneState state, boolean staggered) {
        double acceleration = ServerConfig.get(ServerConfig.DRONE_ACCELERATION);
        if (state == DroneState.RECALLED) {
            MinecraftServer server = drone.level().getServer();
            Player player = recallPlayer != null && server != null ? server.getPlayerList().getPlayer(recallPlayer) : null;
            if (player != null && player.isAlive() && player.level() == drone.level()) {
                Vec3 target = recallSpot(player, staggered);
                if (drone.distanceToSqr(target) > RECALL_REACH_DISTANCE * RECALL_REACH_DISTANCE
                        && drone.steerTowards(target, ServerConfig.get(ServerConfig.DRONE_CRUISE_SPEED), acceleration, target, staggered, true)) {
                    drone.updateFacing(player.getEyePosition());
                    return;
                }
            }
            // There, can't get any closer, or the player left: hold here, going back on its own after the timeout.
            recallPlayer = null;
            recallHoverLeft = ServerConfig.get(ServerConfig.REMOTE_RECALL_HOVER_TICKS);
            drone.setState(DroneState.HOLDING);
        }
        hover(acceleration);
        if (recallHoverLeft >= 0 && --recallHoverLeft <= 0) {
            endOrder();
        }
    }

    /** Flies to the Move spot at cruise pace, steering like a chase, then starts guarding it. */
    private void tickMoving(boolean staggered) {
        if (spot == null || --ticksLeft <= 0) {
            endOrder();
            return;
        }
        if (drone.distanceToSqr(spot) <= SPOT_REACH_DISTANCE * SPOT_REACH_DISTANCE) {
            order = DroneOrder.GUARD;
            ticksLeft = ServerConfig.get(ServerConfig.COMMAND_CENTER_GUARD_TICKS);
            drone.setState(DroneState.GUARDING);
            hover(ServerConfig.get(ServerConfig.DRONE_ACCELERATION));
            return;
        }
        drone.steerTowards(spot, ServerConfig.get(ServerConfig.DRONE_CRUISE_SPEED), ServerConfig.get(ServerConfig.DRONE_ACCELERATION), spot,
                staggered, false);
        drone.updateFacing(null);
    }

    /**
     * Rushes at the top block like an Explosive chase, at {@code commandCenter.strikeSpeedFactor × drone.maxSpeed} the
     * whole way, and detonates on arrival.
     */
    private void tickStriking(boolean staggered) {
        DroneData data = drone.getDroneData();
        if (spot == null || !DroneStats.isExplosive(data) || --ticksLeft <= 0) {
            endOrder();
            return;
        }
        double halfHeight = drone.getBbHeight() / 2;
        double trigger = ServerConfig.get(ServerConfig.DRONE_EXPLOSION_TRIGGER_DISTANCE);
        if (drone.position().add(0, halfHeight, 0).distanceToSqr(spot) <= trigger * trigger) {
            drone.explode(data);
            return;
        }
        // A rush at a fixed speed the whole way (an Explosive chase would start at cruise speed this far out).
        double speed = ServerConfig.get(ServerConfig.COMMAND_CENTER_STRIKE_SPEED_FACTOR) * ServerConfig.get(ServerConfig.DRONE_MAX_SPEED);
        drone.steerTowards(spot.subtract(0, halfHeight, 0), speed, ServerConfig.get(ServerConfig.DRONE_EXPLOSIVE_ACCELERATION), null, staggered, false);
        drone.updateFacing(null);
    }

    private void hover(double acceleration) {
        if (drone.hasPathGoal()) {
            drone.stopNavigating();
        }
        drone.droneMoveControl().hold(acceleration);
        drone.updateFacing(null);
    }

    /**
     * Where a recalled drone stops: {@code remote.recallDistance} blocks in front of the player at eye level, or closer
     * if that spot is blocked. The free distance is looked up on the staggered tick only.
     */
    private Vec3 recallSpot(Player player, boolean staggered) {
        Vec3 eye = player.getEyePosition();
        Vec3 forward = Vec3.directionFromRotation(0, player.getYRot());
        double distance = ServerConfig.get(ServerConfig.REMOTE_RECALL_DISTANCE);
        double feetOffset = drone.getBbHeight() / 2;
        if (staggered || recallRange < 0) {
            recallRange = distance;
            for (double range : new double[] {distance, distance / 2, 0}) {
                if (drone.hasRoomAt(eye.add(forward.scale(range)).subtract(0, feetOffset, 0))) {
                    recallRange = range;
                    break;
                }
            }
        }
        return eye.add(forward.scale(recallRange)).subtract(0, feetOffset, 0);
    }

    /**
     * Ends a hold, recall, Move or Strike: patrols again, or a recalled drone without Patrol flies back to where it was
     * recalled (any other drone without Patrol hovers where it is).
     */
    private void endOrder() {
        drone.charging().setHomeGoal(recallHome);
        clear();
        backToNormal();
    }

    /**
     * Ends guarding (section 7.10): a drone with Patrol flies its circle again, one without stays at the spot, which
     * becomes its rest position. A drone in the middle of a chase keeps chasing and goes back to normal afterwards.
     */
    private void endGuard() {
        Vec3 guarded = spot;
        clear();
        if (guarded != null && !DroneStats.isPatrolling(drone.getDroneData())) {
            drone.setRestPosition(GlobalPos.of(drone.level().dimension(), BlockPos.containing(guarded)));
        }
        DroneState state = drone.getState();
        if (state != DroneState.CHASING && state != DroneState.FOLLOWING) {
            backToNormal();
        }
    }

    private void backToNormal() {
        drone.patrol().reset();
        drone.requestPathRecheck();
        drone.setState(DroneState.IDLE);
    }

    /** The reason shown for an order's state (section 7.10), or null for none. */
    @Nullable
    DroneReason.Info reason(DroneState state) {
        if (spot == null) {
            return null;
        }
        int x = Mth.floor(spot.x);
        int z = Mth.floor(spot.z);
        return switch (state) {
            case MOVING -> new DroneReason.Info(DroneReason.ORDER_MOVING, 0, x, z);
            case STRIKING -> new DroneReason.Info(DroneReason.ORDER_STRIKING, 0, x, z);
            case GUARDING -> new DroneReason.Info(DroneReason.ORDER_GUARDING, Mth.ceil(ticksLeft / (float) DroneStats.TICKS_PER_SECOND), x, z);
            default -> null;
        };
    }

    // --- Persistence ---

    void save(CompoundTag tag) {
        if (isPassive(drone.getState())) {
            if (recallPlayer != null) {
                tag.putUUID(TAG_RECALL_PLAYER, recallPlayer);
            }
            tag.putInt(TAG_RECALL_HOVER_LEFT, recallHoverLeft);
        }
        if (recallHome != null) {
            Vec3.CODEC.encodeStart(NbtOps.INSTANCE, recallHome).result().ifPresent(pos -> tag.put(TAG_RECALL_HOME, pos));
        }
        if (order != null && spot != null) {
            tag.putString(TAG_ORDER, order.getSerializedName());
            Vec3.CODEC.encodeStart(NbtOps.INSTANCE, spot).result().ifPresent(pos -> tag.put(TAG_ORDER_SPOT, pos));
            tag.putInt(TAG_ORDER_TICKS_LEFT, ticksLeft);
        }
    }

    /**
     * Loads the orders for the loaded state. Returns whether the state is an order state that was restored (HOLDING,
     * RECALLED, MOVING, GUARDING or STRIKING); a guard order is also kept for a drone loaded mid-chase.
     */
    boolean load(CompoundTag tag, DroneState state) {
        clear();
        recallHome = tag.contains(TAG_RECALL_HOME) ? Vec3.CODEC.parse(NbtOps.INSTANCE, tag.get(TAG_RECALL_HOME)).result().orElse(null) : null;
        if (state == DroneState.HOLDING || state == DroneState.RECALLED) {
            recallPlayer = tag.hasUUID(TAG_RECALL_PLAYER) ? tag.getUUID(TAG_RECALL_PLAYER) : null;
            recallHoverLeft = tag.contains(TAG_RECALL_HOVER_LEFT) ? tag.getInt(TAG_RECALL_HOVER_LEFT) : -1;
            return true;
        }
        DroneOrder loaded = tag.contains(TAG_ORDER) ? DroneOrder.bySerializedName(tag.getString(TAG_ORDER)) : null;
        Vec3 loadedSpot = tag.contains(TAG_ORDER_SPOT) ? Vec3.CODEC.parse(NbtOps.INSTANCE, tag.get(TAG_ORDER_SPOT)).result().orElse(null) : null;
        if (loaded == null || loadedSpot == null) {
            return false;
        }
        boolean matches = switch (loaded) {
            case MOVE -> state == DroneState.MOVING;
            case STRIKE -> state == DroneState.STRIKING;
            case GUARD -> state == DroneState.GUARDING || state == DroneState.CHASING || state == DroneState.FOLLOWING;
        };
        if (!matches) {
            return false;
        }
        order = loaded;
        spot = loadedSpot;
        ticksLeft = tag.getInt(TAG_ORDER_TICKS_LEFT);
        return state != DroneState.CHASING && state != DroneState.FOLLOWING;
    }
}
