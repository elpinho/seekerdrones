package com.elpinho.seekerdrones.drone;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.annotation.Nullable;

import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.network.DroneStatusPayload;
import com.elpinho.seekerdrones.registry.ModSounds;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.BodyRotationControl;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The deployed, flying form of a drone (DESIGN.md section 2).
 */
public class DroneEntity extends PathfinderMob {
    private static final EntityDataAccessor<String> DATA_LABEL = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> DATA_COLOR = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_STATE = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.INT);

    private static final String TAG_DRONE_DATA = "DroneData";
    private static final String TAG_DRIFTING = "Drifting";
    private static final String TAG_REST_POSITION = "RestPosition";
    private static final String TAG_STATE = "State";
    private static final String TAG_TARGET = "Target";

    /** The follow range the path finder's node budget was sized for (vanilla's default follow range). */
    private static final float BASE_FOLLOW_RANGE = 16.0F;

    /** At least this many patrol waypoints, however small the circle (section 3.2). */
    private static final int MIN_PATROL_WAYPOINTS = 8;
    /** How close (blocks) the drone must get to a patrol waypoint before heading for the next one. */
    private static final double WAYPOINT_REACH_DISTANCE = 1.0;
    /** Distance (blocks) between the points checked for obstacles along a patrol leg; less than the drone's width. */
    private static final double LEG_SAMPLE_SPACING = 0.5;
    /** Extra ticks on top of twice the straight flight time before a patrol waypoint is given up. */
    private static final int WAYPOINT_GRACE_TICKS = 60;
    /** {@link #patrolWaypoint} value: no waypoint is usable, so the drone hovers at the patrol center. */
    private static final int NO_FREE_WAYPOINT = -2;

    /**
     * Bearing turns (degrees) tried in order when the follow position is blocked (section 3.1): the current bearing
     * first, then ever further around the target.
     */
    private static final double[] FOLLOW_BEARING_TURNS = {0, 45, -45, 90, -90, 135, -135, 180};
    /** Min ticks between free-space and clear-path re-checks triggered by bumping into blocks. */
    private static final int COLLISION_RECHECK_TICKS = 5;
    /** Shrinks the drone's box for the clear-path check, so merely touching a block doesn't count as blocked. */
    private static final double CLEAR_PATH_MARGIN = 0.01;
    /** Fraction of the remaining angle turned each tick, so turns ease in and out. */
    private static final float TURN_EASING = 0.3F;
    /** A turn ends once the drone faces within this many degrees of where it wants to face. */
    private static final float TURN_DONE_ANGLE = 1.0F;
    /** Below this horizontal speed (blocks/tick), a drone that faces its direction of travel keeps its facing. */
    private static final double FACE_MOVEMENT_MIN_SPEED = 0.03;

    /** Server-side drone state. Health lives in the entity itself and is copied back by {@link #snapshotData()}. */
    @Nullable
    private DroneData droneData;
    /** The drone's target entries resolved for matching. Rebuilt by {@link #setDroneData}. */
    private TargetMatcher targetMatcher = TargetMatcher.EMPTY;
    /** True from hand-deploy until the drone first comes to rest. */
    private boolean drifting;
    /** Where a hand-deployed drone came to rest (patrol center fallback, section 3.2). */
    @Nullable
    private GlobalPos restPosition;

    // --- AI (server only) ---
    /** The entity being chased or followed. */
    @Nullable
    private Entity target;
    /** A target loaded from NBT that hasn't been looked up in the level yet. */
    @Nullable
    private UUID pendingTargetId;
    /** The tick at which line of sight to the target was first found lost, or -1 while it is visible. */
    private int lostSightSince = -1;
    /**
     * The target's position (feet x/z, eye y) smoothed over time, so the drone reacts to where the target is going
     * rather than to every hop (section 3.1). Null until the first pursuit tick.
     */
    @Nullable
    private Vec3 targetAnchor;
    /** How far the smoothed target position moved on the last tick (blocks/tick). */
    private double targetAnchorSpeed;
    /** Horizontal unit vector from the target to the follow position (section 3.1). */
    private Vec3 followBearing = new Vec3(1, 0, 0);
    /** True while {@link #followBearing} was turned to get around an obstacle, so it isn't re-derived each tick. */
    private boolean followBearingLocked;
    /** Horizontal distance (blocks) from the target to the follow position, shortened if the full distance is blocked. */
    private double followRange;
    /** Height (blocks) of the follow position above the target's eyes, lowered if blocked (e.g. under a ceiling). */
    private double followHeight;
    /** The tick of the last free-space check of the follow position. */
    private int followSlotCheckTick;
    /** Forces a free-space check of the follow position on the next tick. */
    private boolean followSlotStale = true;
    /** True while a following drone holds still, until the follow position moves more than the slack away. */
    private boolean followHolding;
    /** Whether the straight line to the goal was clear at the last check, so no path is needed. */
    private boolean directPathClear;
    /** Forces a direct-path check on the next tick instead of waiting for the staggered tick. */
    private boolean recheckDirectPath;
    /** The tick of the last clear-path check. */
    private int directPathCheckTick;
    /** True while the drone turns toward where it wants to face (section 3.4). */
    private boolean turning;
    /** The goal of the current navigation path, or null if not path finding. */
    @Nullable
    private Vec3 pathGoal;

    // --- Upgrades (server only, not saved) ---
    /** The patrol waypoint being flown to, -1 to pick the nearest one, or {@link #NO_FREE_WAYPOINT}. */
    private int patrolWaypoint = -1;
    /** Where the drone flies for the current patrol waypoint, raised over any obstacle. Null if none is selected. */
    @Nullable
    private Vec3 patrolGoal;
    /** The patrol circle's center (at the patrol height) and radius when the waypoint was picked. */
    @Nullable
    private Vec3 patrolCircleCenter;
    private double patrolCircleRadius;
    /** The tick after which the current patrol waypoint is given up. */
    private int waypointDeadline;
    /** Patrol waypoints skipped in a row because no path reached them. */
    private int unreachableWaypoints;
    /** The tick at which the siren repeats while a target is held. */
    private int nextSirenTick;
    /** Game time of the last Transmitter message, or -1 if none was sent yet. */
    private long lastTransmitTime = -1;

    public DroneEntity(EntityType<? extends DroneEntity> type, Level level) {
        super(type, level);
        setNoGravity(true);
        moveControl = new DroneMoveControl(this);
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        FlyingPathNavigation navigation = new FlyingPathNavigation(this, level);
        navigation.setCanFloat(true);
        navigation.setCanOpenDoors(false);
        navigation.setCanPassDoors(false);
        return navigation;
    }

    @Override
    protected BodyRotationControl createBodyControl() {
        // The drone turns as a whole (see updateFacing): its body and head always match its yaw, on both sides.
        return new BodyRotationControl(this) {
            @Override
            public void clientTick() {
                yBodyRot = getYRot();
                yHeadRot = getYRot();
            }
        };
    }

    public static AttributeSupplier.Builder createAttributes() {
        // Registered before the config loads. The real max health is set per drone in setDroneData().
        // Drones are never knocked back by hits or explosions (section 2.5).
        return Mob.createMobAttributes()
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0)
                .add(Attributes.EXPLOSION_KNOCKBACK_RESISTANCE, 1.0);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_LABEL, "");
        builder.define(DATA_COLOR, DroneConfig.DEFAULT_COLOR.getId());
        builder.define(DATA_STATE, DroneState.IDLE.ordinal());
    }

    // --- Drone data ---

    private DroneData getDroneData() {
        if (droneData == null) {
            setDroneData(DroneData.createNew());
        }
        return droneData;
    }

    /** Replaces the drone's data, including its current health. */
    public void setDroneData(DroneData data) {
        droneData = data;
        AttributeInstance maxHealth = getAttribute(Attributes.MAX_HEALTH);
        if (maxHealth != null) {
            maxHealth.setBaseValue(DroneStats.maxHealth(data));
        }
        setHealth(Math.min(data.health(), getMaxHealth()));
        targetMatcher = TargetMatcher.of(data);
        // Paths may need to span the whole pursuit range. The path finder's node budget was fixed at construction for
        // the default follow range, so scale it along.
        float pursuitRange = (float) DroneStats.pursuitRange(data);
        AttributeInstance followRange = getAttribute(Attributes.FOLLOW_RANGE);
        if (followRange != null) {
            followRange.setBaseValue(pursuitRange);
        }
        navigation.setMaxVisitedNodesMultiplier(Math.max(1.0F, pursuitRange / BASE_FOLLOW_RANGE));
        entityData.set(DATA_LABEL, data.config().label());
        entityData.set(DATA_COLOR, data.config().color().getId());
    }

    /** The drone's full data with its current health, as it would be stored on the item. */
    public DroneData snapshotData() {
        return getDroneData().withHealth(getHealth());
    }

    public String getLabel() {
        return entityData.get(DATA_LABEL);
    }

    public DyeColor getColor() {
        return DyeColor.byId(entityData.get(DATA_COLOR));
    }

    public DroneState getState() {
        return DroneState.BY_ID.apply(entityData.get(DATA_STATE));
    }

    private void setState(DroneState state) {
        entityData.set(DATA_STATE, state.ordinal());
    }

    /** The entity being chased or followed, if any. Server side only. */
    @Nullable
    public Entity getSeekTarget() {
        return target;
    }

    public boolean isDrifting() {
        return drifting;
    }

    @Nullable
    public BlockPos getRestPosition() {
        return restPosition != null ? restPosition.pos() : null;
    }

    /** Called on hand-deploy: the drone drifts with its current velocity until drag brings it to rest. */
    public void startDrifting() {
        drifting = true;
        restPosition = null;
    }

    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty, MobSpawnType spawnType, @Nullable SpawnGroupData spawnGroupData) {
        // Summoned by command or spawn egg: give it a complete, fresh data set.
        if (droneData == null) {
            setDroneData(DroneData.createNew().withDroneId(DroneIds.generate(getRandom())));
        }
        return super.finalizeSpawn(level, difficulty, spawnType, spawnGroupData);
    }

    // --- Movement ---

    @Override
    public void travel(Vec3 input) {
        if (isControlledByLocalInstance()) {
            if (((DroneMoveControl) moveControl).isSteering()) {
                // Navigating: the move control set the exact velocity for this tick.
                move(MoverType.SELF, getDeltaMovement());
            } else {
                drift(input);
            }
        }
        calculateEntityAnimation(false);
    }

    /** Drifting after hand-deploy, or idle: drag slows the drone down until it comes to rest and hovers. */
    private void drift(Vec3 input) {
        moveRelative(getSpeed(), input);
        move(MoverType.SELF, getDeltaMovement());
        double restSpeed = ServerConfig.get(ServerConfig.DRONE_DEPLOY_REST_SPEED);
        Vec3 velocity = getDeltaMovement().scale(ServerConfig.get(ServerConfig.DRONE_DEPLOY_DRAG));
        if (velocity.lengthSqr() < restSpeed * restSpeed) {
            velocity = Vec3.ZERO;
            if (drifting) {
                drifting = false;
                restPosition = currentPosition();
            }
        }
        setDeltaMovement(velocity);
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide() && isAlive() && isInWater()) {
            int interval = ServerConfig.get(ServerConfig.DRONE_WATER_DAMAGE_INTERVAL);
            if (isStaggeredTick(interval)) {
                hurt(damageSources().drown(), ServerConfig.get(ServerConfig.DRONE_WATER_DAMAGE).floatValue());
            }
        }
    }

    /** Spreads periodic work over different ticks per drone (section 3.3). */
    private boolean isStaggeredTick(int interval) {
        return (tickCount + getId()) % interval == 0;
    }

    // --- Seeking AI (section 3) ---

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        DroneData data = getDroneData();
        boolean staggered = isStaggeredTick(ServerConfig.get(ServerConfig.DRONE_SCAN_INTERVAL));
        if (pendingTargetId != null) {
            resolvePendingTarget(staggered);
            return;
        }
        if (target == null) {
            if (staggered) {
                scanForTarget(data);
            }
            if (target == null && !drifting) {
                patrolOrHover(data, staggered);
            }
        } else if (isTargetLost(data, staggered)) {
            loseTarget();
        } else {
            pursue(data, staggered);
        }
        // A following drone keeps an eye on its target; any other drone faces where it is flying.
        updateFacing(target != null && !DroneStats.isExplosive(data) ? targetAnchor : null);
    }

    /**
     * Turns the drone toward {@code lookAt}, or toward its direction of travel if null (section 3.4). It only starts
     * turning once it faces more than {@code drone.facingTolerance} away, then eases into the turn at up to
     * {@code drone.turnSpeed}, so small target movements don't make it twitch.
     */
    private void updateFacing(@Nullable Vec3 lookAt) {
        double dx;
        double dz;
        if (lookAt != null) {
            dx = lookAt.x - getX();
            dz = lookAt.z - getZ();
        } else {
            Vec3 velocity = getDeltaMovement();
            if (velocity.horizontalDistanceSqr() < FACE_MOVEMENT_MIN_SPEED * FACE_MOVEMENT_MIN_SPEED) {
                return;
            }
            dx = velocity.x;
            dz = velocity.z;
        }
        if (dx * dx + dz * dz < 1.0E-4) {
            return;
        }
        float wanted = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
        float difference = Mth.wrapDegrees(wanted - getYRot());
        if (Math.abs(difference) > ServerConfig.get(ServerConfig.DRONE_FACING_TOLERANCE)) {
            turning = true;
        }
        if (!turning) {
            return;
        }
        float maxTurn = ServerConfig.get(ServerConfig.DRONE_TURN_SPEED).floatValue();
        float step = Mth.clamp(difference * TURN_EASING, -maxTurn, maxTurn);
        setYRot(getYRot() + step);
        setYHeadRot(getYRot());
        setYBodyRot(getYRot());
        if (Math.abs(difference - step) < TURN_DONE_ANGLE) {
            turning = false;
        }
    }

    /** Looks up a target loaded from NBT. Gives up at the next staggered tick if it isn't in the level. */
    private void resolvePendingTarget(boolean staggered) {
        Entity found = ((ServerLevel) level()).getEntity(pendingTargetId);
        if (found != null) {
            pendingTargetId = null;
            target = found;
            lostSightSince = -1;
            recheckDirectPath = true;
            resetFollow();
            // Not a new acquisition: no Transmitter message, and the siren keeps its repeat interval.
            nextSirenTick = tickCount + ServerConfig.get(ServerConfig.UPGRADES_SIREN_REPEAT_INTERVAL);
        } else if (staggered) {
            pendingTargetId = null;
            setState(DroneState.IDLE);
        }
    }

    /**
     * Cheap filtering first, raycasts last (section 3.3): AABB query, target match, sight sphere, then raycasts to
     * the nearest candidates until one is visible. X-ray skips the raycasts.
     */
    private void scanForTarget(DroneData data) {
        if (targetMatcher.isEmpty()) {
            return;
        }
        double range = DroneStats.sightRange(data);
        double rangeSqr = range * range;
        List<Entity> candidates = level().getEntities(this, getBoundingBox().inflate(range),
                entity -> targetMatcher.matches(this, entity) && distanceToSqr(entity) <= rangeSqr);
        if (candidates.isEmpty()) {
            return;
        }
        candidates.sort(Comparator.comparingDouble(this::distanceToSqr));
        if (DroneStats.hasXray(data)) {
            acquireTarget(candidates.getFirst());
            return;
        }
        int raycasts = Math.min(candidates.size(), ServerConfig.get(ServerConfig.DRONE_MAX_RAYCASTS_PER_SCAN));
        for (int i = 0; i < raycasts; i++) {
            Entity candidate = candidates.get(i);
            if (canSee(candidate)) {
                acquireTarget(candidate);
                return;
            }
        }
    }

    private void acquireTarget(Entity entity) {
        target = entity;
        lostSightSince = -1;
        recheckDirectPath = true;
        resetFollow();
        // A drifting drone that spots a target starts chasing at once (section 2.2). It never comes to rest, so where
        // it spotted the target becomes its rest position (patrol center fallback, section 3.2).
        if (drifting) {
            drifting = false;
            restPosition = currentPosition();
        }
        patrolWaypoint = -1;
        setState(DroneState.CHASING);
        DroneData data = getDroneData();
        playSiren(data);
        transmit(data, entity);
    }

    /** Block collision raycast from the drone's eyes to the entity's eyes. */
    private boolean canSee(Entity entity) {
        return level().clip(new ClipContext(getEyePosition(), entity.getEyePosition(), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this))
                .getType() == HitResult.Type.MISS;
    }

    /**
     * Section 3.5. The cheap checks run every tick. Line of sight is re-checked only on the staggered tick, and never
     * for X-ray drones.
     */
    private boolean isTargetLost(DroneData data, boolean staggered) {
        if (target.isRemoved() || target.level() != level() || !targetMatcher.matches(this, target)) {
            return true;
        }
        double pursuitRange = DroneStats.pursuitRange(data);
        if (distanceToSqr(target) > pursuitRange * pursuitRange) {
            return true;
        }
        if (staggered && !DroneStats.hasXray(data)) {
            if (canSee(target)) {
                lostSightSince = -1;
            } else if (lostSightSince < 0) {
                lostSightSince = tickCount;
            } else if (tickCount - lostSightSince > ServerConfig.get(ServerConfig.DRONE_LOST_SIGHT_TIMEOUT)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Drops the target. A Patrol drone goes back to patrolling on the next tick, any other drone hovers in place and
     * drift drag brings it to a stop (section 3.5).
     */
    private void loseTarget() {
        target = null;
        lostSightSince = -1;
        resetFollow();
        stopNavigating();
        setState(DroneState.IDLE);
    }

    /** Forgets the smoothed target position and the follow position chosen for the previous target. */
    private void resetFollow() {
        targetAnchor = null;
        targetAnchorSpeed = 0;
        followBearingLocked = false;
        followHolding = false;
        followRange = getDroneData().config().followDistance();
        followHeight = ServerConfig.get(ServerConfig.DRONE_FOLLOW_HEIGHT_OFFSET);
        // Check the follow position for free space on the first pursuit tick.
        followSlotStale = true;
    }

    private void stopNavigating() {
        pathGoal = null;
        navigation.stop();
    }

    private void pursue(DroneData data, boolean staggered) {
        if (tickCount >= nextSirenTick) {
            playSiren(data);
        }
        if (DroneStats.isExplosive(data)) {
            pursueExplosive(data, staggered);
            return;
        }
        updateTargetAnchor();
        Vec3 goal = followPosition(data, staggered);
        double goalDistance = Math.sqrt(distanceToSqr(goal));
        // The exit distance is kept above the enter distance so the state can't flip back and forth (hysteresis).
        double enter = ServerConfig.get(ServerConfig.DRONE_FOLLOW_ENTER_DISTANCE);
        double exit = Math.max(enter, ServerConfig.get(ServerConfig.DRONE_FOLLOW_EXIT_DISTANCE));
        DroneState state = getState();
        if (state != DroneState.FOLLOWING && goalDistance <= enter) {
            setState(DroneState.FOLLOWING);
        } else if (state != DroneState.CHASING && goalDistance > exit) {
            setState(DroneState.CHASING);
        }
        // Leash: once there, the drone holds still until the follow position has moved more than the slack away.
        double slack = Math.max(enter, ServerConfig.get(ServerConfig.DRONE_FOLLOW_SLACK));
        if (goalDistance <= enter) {
            followHolding = true;
        } else if (goalDistance > slack) {
            followHolding = false;
        }
        double acceleration = ServerConfig.get(ServerConfig.DRONE_ACCELERATION);
        if (followHolding) {
            if (pathGoal != null) {
                stopNavigating();
            }
            ((DroneMoveControl) moveControl).hold(acceleration);
        } else {
            steerTowards(goal, DroneStats.followSpeed(targetAnchorSpeed), acceleration, goal, staggered, true);
        }
    }

    /** Moves the smoothed target position a fraction of the way toward the target (section 3.1). */
    private void updateTargetAnchor() {
        Vec3 actual = new Vec3(target.getX(), target.getEyeY(), target.getZ());
        if (targetAnchor == null) {
            targetAnchor = actual;
            targetAnchorSpeed = 0;
            return;
        }
        Vec3 next = targetAnchor.lerp(actual, ServerConfig.get(ServerConfig.DRONE_FOLLOW_SMOOTHING));
        targetAnchorSpeed = next.distanceTo(targetAnchor);
        targetAnchor = next;
    }

    /**
     * Where the drone wants to be (section 3.1): {@link #followRange} from the smoothed target position along the
     * bearing, and {@link #followHeight} above its eyes. Chasing drones fly toward this position too. The spot is
     * checked for free space on the staggered tick, and soon after bumping into a block.
     */
    private Vec3 followPosition(DroneData data, boolean staggered) {
        if (!followBearingLocked) {
            followBearing = bearingFromAnchor();
        }
        boolean bumped = (horizontalCollision || verticalCollision) && tickCount - followSlotCheckTick >= COLLISION_RECHECK_TICKS;
        if (staggered || followSlotStale || bumped) {
            chooseFollowSlot(data);
        }
        return followSlot(followBearing, followRange, followHeight);
    }

    /** The horizontal direction from the smoothed target position to the drone, or the last one if right above it. */
    private Vec3 bearingFromAnchor() {
        double dx = getX() - targetAnchor.x;
        double dz = getZ() - targetAnchor.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        return horizontal > 1.0E-3 ? new Vec3(dx / horizontal, 0, dz / horizontal) : followBearing;
    }

    private Vec3 followSlot(Vec3 bearing, double range, double height) {
        return new Vec3(targetAnchor.x + bearing.x * range, targetAnchor.y + height, targetAnchor.z + bearing.z * range);
    }

    /**
     * Picks a follow position with room for the drone (section 3.1). The ideal one comes first. If it's blocked, the
     * drone tries lower heights (under a ceiling), then half the distance (against a wall), then other bearings
     * around the target. If nothing is free, it aims for the ideal spot and path finding gets as close as it can.
     */
    private void chooseFollowSlot(DroneData data) {
        followSlotStale = false;
        followSlotCheckTick = tickCount;
        double distance = data.config().followDistance();
        double[] heights = {ServerConfig.get(ServerConfig.DRONE_FOLLOW_HEIGHT_OFFSET), 0, -target.getEyeHeight() / 2};
        double[] ranges = {distance, distance / 2};
        Vec3 base = bearingFromAnchor();
        for (double turn : FOLLOW_BEARING_TURNS) {
            Vec3 bearing = base.yRot((float) Math.toRadians(turn));
            for (double range : ranges) {
                for (double height : heights) {
                    if (hasRoomAt(followSlot(bearing, range, height))) {
                        followBearing = bearing;
                        followBearingLocked = turn != 0;
                        followRange = range;
                        followHeight = height;
                        return;
                    }
                }
            }
        }
        followBearing = base;
        followBearingLocked = false;
        followRange = distance;
        followHeight = heights[0];
    }

    /** Whether the drone fits at {@code pos} (its feet position) without touching any block, in a loaded chunk. */
    private boolean hasRoomAt(Vec3 pos) {
        return level().hasChunkAt(BlockPos.containing(pos)) && level().noBlockCollision(this, getDimensions(getPose()).makeBoundingBox(pos));
    }

    // --- Explosive (section 3.1) ---

    /**
     * Explosive drones never follow: they fly straight at the target's center and explode once close enough. They
     * track the target's actual position with a higher acceleration and never brake (section 3.4).
     */
    private void pursueExplosive(DroneData data, boolean staggered) {
        Vec3 targetCenter = target.getBoundingBox().getCenter();
        double halfHeight = getBbHeight() / 2;
        double trigger = ServerConfig.get(ServerConfig.DRONE_EXPLOSION_TRIGGER_DISTANCE);
        if (position().add(0, halfHeight, 0).distanceToSqr(targetCenter) <= trigger * trigger) {
            explode(data);
            return;
        }
        if (getState() != DroneState.CHASING) {
            setState(DroneState.CHASING);
        }
        double speed = DroneStats.chaseSpeed(distanceTo(target), DroneStats.sightRange(data));
        steerTowards(targetCenter.subtract(0, halfHeight, 0), speed, ServerConfig.get(ServerConfig.DRONE_EXPLOSIVE_ACCELERATION),
                null, staggered, true);
    }

    /** The drone is consumed: no drop, and the explosion never breaks blocks in v1. */
    private void explode(DroneData data) {
        level().explode(this, getX(), getY(0.5), getZ(), DroneStats.explosionPower(data), Level.ExplosionInteraction.NONE);
        discard();
    }

    // --- Siren and Transmitter (section 4) ---

    /** Plays the siren if the drone has one and schedules the next repeat. */
    private void playSiren(DroneData data) {
        float volume = DroneStats.sirenVolume(data);
        if (volume <= 0) {
            return;
        }
        level().playSound(null, getX(), getY(), getZ(), ModSounds.DRONE_SIREN.get(), SoundSource.NEUTRAL, volume, 1.0F);
        nextSirenTick = tickCount + ServerConfig.get(ServerConfig.UPGRADES_SIREN_REPEAT_INTERVAL);
    }

    /** Tells all online operators of the drone (group, else owner) about a newly spotted target, at most once per cooldown. */
    private void transmit(DroneData data, Entity spotted) {
        if (!DroneStats.hasTransmitter(data)) {
            return;
        }
        long now = level().getGameTime();
        if (lastTransmitTime >= 0 && now - lastTransmitTime < ServerConfig.get(ServerConfig.UPGRADES_TRANSMITTER_COOLDOWN)) {
            return;
        }
        lastTransmitTime = now;
        MinecraftServer server = level().getServer();
        if (server == null) {
            return;
        }
        Component name = spotted instanceof Player player ? player.getName() : spotted.getType().getDescription();
        BlockPos pos = spotted.blockPosition();
        Component message = Component.translatable("message.seekerdrones.transmitter", DroneItem.identity(data), name,
                pos.getX(), pos.getY(), pos.getZ());
        for (ServerPlayer operator : DronePermissions.onlineOperators(server, data)) {
            operator.sendSystemMessage(message);
        }
    }

    // --- Patrol (section 3.2) ---

    private GlobalPos currentPosition() {
        return GlobalPos.of(level().dimension(), blockPosition());
    }

    /**
     * The patrol center this drone uses right now (section 3.2): the configured one, else the rest position, as long
     * as it is in the drone's dimension. Null if there is none yet.
     */
    @Nullable
    public GlobalPos getPatrolCenter() {
        DroneData data = getDroneData();
        Optional<GlobalPos> configured = data.config().patrolCenter();
        if (configured.isPresent() && configured.get().dimension() == level().dimension()) {
            return configured.get();
        }
        if (restPosition != null && restPosition.dimension() == level().dimension()) {
            return restPosition;
        }
        return null;
    }

    /** With no target: a Patrol drone flies its circle, any other drone hovers where it is. */
    private void patrolOrHover(DroneData data, boolean staggered) {
        if (!DroneStats.isPatrolling(data)) {
            if (getState() == DroneState.PATROLLING) {
                // The Patrol upgrades were removed mid-patrol.
                stopNavigating();
                patrolWaypoint = -1;
                setState(DroneState.IDLE);
            }
            return;
        }
        GlobalPos center = getPatrolCenter();
        if (center == null) {
            // Fallback 4: e.g. a summoned drone. Wherever it is now becomes its rest position.
            restPosition = currentPosition();
            center = restPosition;
        }
        if (getState() != DroneState.PATROLLING) {
            setState(DroneState.PATROLLING);
            patrolWaypoint = -1;
        }
        Vec3 centerPos = patrolCenterAtHeight(center, data);
        double radius = DroneStats.patrolRadius(data);
        if (!centerPos.equals(patrolCircleCenter) || radius != patrolCircleRadius) {
            // A new center, radius or altitude: start over from the nearest waypoint of the new circle.
            patrolCircleCenter = centerPos;
            patrolCircleRadius = radius;
            patrolWaypoint = -1;
        }
        int count = patrolWaypointCount(radius);
        double speed = ServerConfig.get(ServerConfig.UPGRADES_PATROL_SPEED);
        double acceleration = ServerConfig.get(ServerConfig.DRONE_ACCELERATION);
        if (patrolWaypoint < 0 || patrolWaypoint >= count) {
            if (!staggered && patrolWaypoint == NO_FREE_WAYPOINT) {
                // Every waypoint was skipped: hover at the center and look again on the next staggered tick.
                steerTowards(centerPos, speed, acceleration, centerPos, false, false);
                return;
            }
            selectWaypoint(nearestWaypoint(centerPos, count), centerPos, radius, count);
        }
        if (patrolWaypoint == NO_FREE_WAYPOINT) {
            steerTowards(centerPos, speed, acceleration, centerPos, staggered, false);
            return;
        }
        Vec3 goal = patrolGoal;
        // Waypoints are flown through, not stopped at. The reach distance covers the drone's turning circle at patrol
        // speed, so it can't end up circling a waypoint it keeps missing.
        double reach = Math.max(WAYPOINT_REACH_DISTANCE, speed * speed / acceleration);
        boolean reached = distanceToSqr(goal) <= reach * reach;
        boolean timedOut = tickCount > waypointDeadline;
        boolean reachable = steerTowards(goal, speed, acceleration, null, staggered, false);
        if (reached) {
            unreachableWaypoints = 0;
        } else if (!reachable || timedOut) {
            // Each unreachable waypoint costs a path search, so stop trying once a full lap has failed.
            if (++unreachableWaypoints >= count) {
                unreachableWaypoints = 0;
                patrolWaypoint = NO_FREE_WAYPOINT;
                patrolGoal = null;
                return;
            }
        } else {
            return;
        }
        selectWaypoint((patrolWaypoint + 1) % count, centerPos, radius, count);
    }

    /**
     * The patrol center at the patrol height (section 3.2): the configured altitude, kept within the build height,
     * or the center's own height if no altitude is set.
     */
    private Vec3 patrolCenterAtHeight(GlobalPos center, DroneData data) {
        Vec3 pos = Vec3.atBottomCenterOf(center.pos());
        return data.config().patrolAltitude()
                .map(altitude -> new Vec3(pos.x, Mth.clamp(altitude, level().getMinBuildHeight(), level().getMaxBuildHeight() - 1), pos.z))
                .orElse(pos);
    }

    private static int patrolWaypointCount(double radius) {
        double spacing = ServerConfig.get(ServerConfig.UPGRADES_PATROL_WAYPOINT_SPACING);
        return Math.max(MIN_PATROL_WAYPOINTS, (int) Math.ceil(2 * Math.PI * radius / spacing));
    }

    /** Waypoint positions go counter-clockwise seen from above (east, then north). */
    private static Vec3 waypoint(Vec3 center, double radius, int count, int index) {
        double angle = 2 * Math.PI * index / count;
        return new Vec3(center.x + radius * Math.cos(angle), center.y, center.z - radius * Math.sin(angle));
    }

    private int nearestWaypoint(Vec3 center, int count) {
        double angle = Math.atan2(-(getZ() - center.z), getX() - center.x);
        return Math.floorMod((int) Math.round(angle / (2 * Math.PI) * count), count);
    }

    /**
     * Picks the first usable waypoint from {@code start} on, skipping spots in unloaded chunks and obstacles too tall
     * to climb over. Sets {@link #NO_FREE_WAYPOINT} if none is usable.
     */
    private void selectWaypoint(int start, Vec3 center, double radius, int count) {
        double speed = ServerConfig.get(ServerConfig.UPGRADES_PATROL_SPEED);
        for (int i = 0; i < count; i++) {
            int index = (start + i) % count;
            Vec3 spot = waypoint(center, radius, count, index);
            Vec3 goal = patrolHeightAt(spot);
            if (goal != null) {
                // Climb before an obstacle on the legs to either neighbor and come down after it, so the drone
                // crosses it level instead of flying into its face.
                double legTop = Math.max(legClimbHeight(waypoint(center, radius, count, index - 1), spot),
                        legClimbHeight(spot, waypoint(center, radius, count, index + 1)));
                if (legTop > goal.y && hasRoomAt(new Vec3(goal.x, legTop, goal.z))) {
                    goal = new Vec3(goal.x, legTop, goal.z);
                }
                patrolWaypoint = index;
                patrolGoal = goal;
                recheckDirectPath = true;
                // Give up on a waypoint that takes far longer than a straight flight would, e.g. when stuck.
                waypointDeadline = tickCount + (int) (2 * Math.sqrt(distanceToSqr(goal)) / speed) + WAYPOINT_GRACE_TICKS;
                return;
            }
        }
        patrolWaypoint = NO_FREE_WAYPOINT;
        patrolGoal = null;
    }

    /**
     * The height the drone must fly at to clear every obstacle on the straight leg between two waypoints at the
     * patrol height (section 3.2), sampled every {@link #LEG_SAMPLE_SPACING} blocks. Obstacles too tall to climb are
     * left to path finding.
     */
    private double legClimbHeight(Vec3 from, Vec3 to) {
        double top = from.y;
        int steps = Math.max(1, Mth.ceil(from.distanceTo(to) / LEG_SAMPLE_SPACING));
        for (int step = 1; step < steps; step++) {
            Vec3 raised = patrolHeightAt(from.lerp(to, (double) step / steps));
            if (raised != null) {
                top = Math.max(top, raised.y);
            }
        }
        return top;
    }

    /**
     * Where the drone flies for a waypoint at {@code spot} (section 3.2): the spot itself if the drone fits there,
     * otherwise raised over the obstacle to {@code upgrades.patrol.climbClearance} above the highest block under the
     * drone. It never drops below the spot, so it climbs over hills and buildings rather than diving into caves. Null
     * if the chunk isn't loaded or the climb would be more than {@code upgrades.patrol.maxClimb}.
     */
    @Nullable
    private Vec3 patrolHeightAt(Vec3 spot) {
        if (!level().hasChunkAt(BlockPos.containing(spot))) {
            return null;
        }
        EntityDimensions dimensions = getDimensions(getPose());
        AABB box = dimensions.makeBoundingBox(spot);
        if (level().noBlockCollision(this, box)) {
            return spot;
        }
        int top = level().getMinBuildHeight();
        for (int x = Mth.floor(box.minX); x <= Mth.floor(box.maxX); x++) {
            for (int z = Mth.floor(box.minZ); z <= Mth.floor(box.maxZ); z++) {
                top = Math.max(top, level().getHeight(Heightmap.Types.MOTION_BLOCKING, x, z));
            }
        }
        Vec3 raised = new Vec3(spot.x, top + ServerConfig.get(ServerConfig.UPGRADES_PATROL_CLIMB_CLEARANCE), spot.z);
        if (raised.y <= spot.y || raised.y - spot.y > ServerConfig.get(ServerConfig.UPGRADES_PATROL_MAX_CLIMB)) {
            return null;
        }
        return level().noBlockCollision(this, dimensions.makeBoundingBox(raised)) ? raised : null;
    }

    /**
     * Flies straight at the goal while the line to it is clear, otherwise follows a path. The straight line is
     * checked on the staggered tick, or soon after bumping into something. Paths are recomputed when the goal has
     * moved more than {@code drone.repathDistance}, and on the staggered tick: always for a moving goal (a target),
     * but for a fixed goal (a patrol waypoint) only once the current path has ended, since recomputing it wouldn't
     * change anything and path finding is the most expensive part of a drone's tick.
     *
     * @param acceleration the max change in velocity per tick (section 3.4)
     * @param stopAt       where the drone should come to rest, or null to fly through the goal at full speed
     * @return false if a path was computed this tick and it can't reach the goal
     */
    private boolean steerTowards(Vec3 goal, double speed, double acceleration, @Nullable Vec3 stopAt, boolean staggered, boolean movingGoal) {
        ((DroneMoveControl) moveControl).configure(acceleration, stopAt);
        boolean bumped = directPathClear && (horizontalCollision || verticalCollision)
                && tickCount - directPathCheckTick >= COLLISION_RECHECK_TICKS;
        if (staggered || recheckDirectPath || bumped) {
            directPathClear = isClearPath(goal);
            directPathCheckTick = tickCount;
            recheckDirectPath = false;
        }
        if (directPathClear) {
            if (pathGoal != null) {
                stopNavigating();
            }
            moveControl.setWantedPosition(goal.x, goal.y, goal.z, speed);
            return true;
        }
        boolean reachable = true;
        if ((staggered && (movingGoal || navigation.isDone())) || pathGoal == null || pathGoal.distanceToSqr(goal) > Mth.square(ServerConfig.get(ServerConfig.DRONE_REPATH_DISTANCE))) {
            boolean started = navigation.moveTo(goal.x, goal.y, goal.z, speed);
            pathGoal = goal;
            Path path = navigation.getPath();
            reachable = started && path != null && path.canReach();
        } else {
            navigation.setSpeedModifier(speed);
        }
        // The navigation already steered this tick before the AI step ran, toward the old path. Steer along the
        // current one right away, or straight at the goal once the path has ended, so the drone never coasts.
        Vec3 next = navigation.isDone() ? goal : navigation.getPath().getNextEntityPos(this);
        moveControl.setWantedPosition(next.x, next.y, next.z, speed);
        return reachable;
    }

    /**
     * Whether the drone's whole box can fly in a straight line to the goal (a position for the drone's feet). Rays
     * are cast from the box's center and corners, since a single center ray misses ledges that catch the box.
     */
    private boolean isClearPath(Vec3 goal) {
        Vec3 delta = goal.subtract(position());
        AABB box = getBoundingBox().deflate(CLEAR_PATH_MARGIN);
        if (!isClearRay(box.getCenter(), delta)) {
            return false;
        }
        for (int corner = 0; corner < 8; corner++) {
            Vec3 from = new Vec3((corner & 1) == 0 ? box.minX : box.maxX, (corner & 2) == 0 ? box.minY : box.maxY, (corner & 4) == 0 ? box.minZ : box.maxZ);
            if (!isClearRay(from, delta)) {
                return false;
            }
        }
        return true;
    }

    private boolean isClearRay(Vec3 from, Vec3 delta) {
        return level().clip(new ClipContext(from, from.add(delta), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this))
                .getType() == HitResult.Type.MISS;
    }

    @Override
    public boolean canDrownInFluidType(FluidType type) {
        // Water damage is handled on its own interval in tick().
        return false;
    }

    @Override
    public boolean causeFallDamage(float fallDistance, float multiplier, DamageSource source) {
        return false;
    }

    // --- Interaction ---

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }
        boolean pickUp = player.isSecondaryUseActive();
        if (pickUp && !player.getMainHandItem().isEmpty()) {
            // Shift+right-click with an item: let the item act (e.g. hand-deploying another drone).
            return InteractionResult.PASS;
        }
        if (level().isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!DronePermissions.canInteract(player, getDroneData())) {
            DronePermissions.sendDenied(player);
            return InteractionResult.CONSUME;
        }
        if (pickUp) {
            pickUp(player);
        } else if (player instanceof ServerPlayer serverPlayer) {
            PacketDistributor.sendToPlayer(serverPlayer, DroneStatusPayload.of(this, true));
        }
        return InteractionResult.CONSUME;
    }

    /** Turns the drone into an item in the player's inventory, or at their feet if it's full (section 2.3). */
    private void pickUp(Player player) {
        ItemStack stack = DroneItem.createStack(snapshotData());
        if (!player.getInventory().add(stack)) {
            ItemEntity item = new ItemEntity(level(), player.getX(), player.getY(), player.getZ(), stack, 0, 0, 0);
            level().addFreshEntity(item);
        }
        playSound(SoundEvents.ITEM_PICKUP, 0.3F, 1.0F);
        discard();
    }

    // --- Health and destruction ---

    @Override
    protected void tickDeath() {
        // Destroyed drones vanish at once with a cosmetic explosion (section 2.5), skipping the vanilla death animation.
        if (level() instanceof ServerLevel serverLevel && !isRemoved()) {
            double y = getY() + getBbHeight() / 2;
            serverLevel.sendParticles(ParticleTypes.EXPLOSION, getX(), y, getZ(), 1, 0, 0, 0, 0);
            serverLevel.sendParticles(ParticleTypes.SMOKE, getX(), y, getZ(), 8, 0.2, 0.2, 0.2, 0.02);
            serverLevel.playSound(null, getX(), y, getZ(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.NEUTRAL, 0.5F, 1.5F);
            remove(RemovalReason.KILLED);
        }
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        // Placeholder until the drone gets its own damage sound.
        return SoundEvents.IRON_GOLEM_DAMAGE;
    }

    @Override
    @Nullable
    protected SoundEvent getDeathSound() {
        // tickDeath() plays the destruction explosion instead.
        return null;
    }

    @Override
    protected boolean shouldDropLoot() {
        return false;
    }

    @Override
    public boolean shouldDropExperience() {
        return false;
    }

    // --- Misc vanilla behavior ---

    @Override
    public Component getName() {
        String label = getLabel();
        return label.isEmpty() ? super.getName() : Component.literal(label);
    }

    @Override
    public boolean shouldShowName() {
        return !getLabel().isEmpty() || super.shouldShowName();
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    public boolean requiresCustomPersistence() {
        return true;
    }

    @Override
    public boolean canBeLeashed() {
        return false;
    }

    // Drones never push or get pushed by other entities (section 2.5). Skipping pushEntities() also saves its entity
    // query every tick, and with it cramming damage.

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected void pushEntities() {
    }

    // --- Persistence ---

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        DroneData.CODEC.encodeStart(NbtOps.INSTANCE, snapshotData()).result().ifPresent(data -> tag.put(TAG_DRONE_DATA, data));
        tag.putBoolean(TAG_DRIFTING, drifting);
        if (restPosition != null) {
            GlobalPos.CODEC.encodeStart(NbtOps.INSTANCE, restPosition).result().ifPresent(pos -> tag.put(TAG_REST_POSITION, pos));
        }
        UUID targetId = target != null ? target.getUUID() : pendingTargetId;
        if (targetId != null) {
            tag.putString(TAG_STATE, getState().getSerializedName());
            tag.putUUID(TAG_TARGET, targetId);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains(TAG_DRONE_DATA)) {
            DroneData.CODEC.parse(NbtOps.INSTANCE, tag.get(TAG_DRONE_DATA)).result().ifPresent(this::setDroneData);
        }
        drifting = tag.getBoolean(TAG_DRIFTING);
        restPosition = tag.contains(TAG_REST_POSITION)
                ? GlobalPos.CODEC.parse(NbtOps.INSTANCE, tag.get(TAG_REST_POSITION)).result().orElse(null)
                : null;
        // The target is looked up lazily on the first AI tick; see resolvePendingTarget().
        target = null;
        DroneState state = DroneState.bySerializedName(tag.getString(TAG_STATE));
        if (tag.hasUUID(TAG_TARGET) && (state == DroneState.CHASING || state == DroneState.FOLLOWING)) {
            pendingTargetId = tag.getUUID(TAG_TARGET);
            setState(state);
        } else {
            pendingTargetId = null;
            setState(DroneState.IDLE);
        }
    }
}
