package com.elpinho.seekerdrones.drone;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.network.DroneStatusPayload;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
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
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
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

    /** Server-side drone state. Health lives in the entity itself and is copied back by {@link #snapshotData()}. */
    @Nullable
    private DroneData droneData;
    /** The drone's target entries resolved for matching. Rebuilt by {@link #setDroneData}. */
    private TargetMatcher targetMatcher = TargetMatcher.EMPTY;
    /** True from hand-deploy until the drone first comes to rest. */
    private boolean drifting;
    /** Where a hand-deployed drone came to rest (patrol center fallback, section 3.2). */
    @Nullable
    private BlockPos restPosition;

    // --- AI (server only) ---
    /** The entity being chased or followed. */
    @Nullable
    private Entity target;
    /** A target loaded from NBT that hasn't been looked up in the level yet. */
    @Nullable
    private UUID pendingTargetId;
    /** The tick at which line of sight to the target was first found lost, or -1 while it is visible. */
    private int lostSightSince = -1;
    /** Horizontal unit vector from the target to the drone, held while following (section 3.1). */
    private Vec3 followBearing = new Vec3(1, 0, 0);
    /** Whether the straight line to the goal was clear at the last check, so no path is needed. */
    private boolean directPathClear;
    /** Forces a direct-path check on the next tick instead of waiting for the staggered tick. */
    private boolean recheckDirectPath;
    /** The goal of the current navigation path, or null if not path finding. */
    @Nullable
    private Vec3 pathGoal;

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
        return restPosition;
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
                restPosition = blockPosition();
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
        } else if (isTargetLost(data, staggered)) {
            loseTarget();
        } else {
            pursue(data, staggered);
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
        // A drifting drone that spots a target starts chasing at once (section 2.2). It never comes to rest, so where
        // it spotted the target becomes its rest position (patrol center fallback, section 3.2).
        if (drifting) {
            drifting = false;
            restPosition = blockPosition();
        }
        setState(DroneState.CHASING);
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

    /** Drops the target and hovers in place. Drift drag brings the drone to a stop. */
    private void loseTarget() {
        target = null;
        lostSightSince = -1;
        pathGoal = null;
        navigation.stop();
        setState(DroneState.IDLE);
    }

    private void pursue(DroneData data, boolean staggered) {
        Vec3 goal = followPosition(data);
        double speed = DroneStats.chaseSpeed(distanceTo(target), DroneStats.sightRange(data));
        double goalDistanceSqr = distanceToSqr(goal);
        // The exit distance is kept above the enter distance so the state can't flip back and forth (hysteresis).
        double enter = ServerConfig.get(ServerConfig.DRONE_FOLLOW_ENTER_DISTANCE);
        double exit = Math.max(enter, ServerConfig.get(ServerConfig.DRONE_FOLLOW_EXIT_DISTANCE));
        DroneState state = getState();
        if (state != DroneState.FOLLOWING && goalDistanceSqr <= enter * enter) {
            setState(DroneState.FOLLOWING);
        } else if (state != DroneState.CHASING && goalDistanceSqr > exit * exit) {
            setState(DroneState.CHASING);
        }
        steerTowards(goal, speed, staggered);
        getLookControl().setLookAt(target);
    }

    /**
     * Where the drone wants to be (section 3.1): at the follow distance from the target along the current bearing,
     * and {@code followHeightOffset} above the target's eyes. Chasing drones fly toward this position too.
     */
    private Vec3 followPosition(DroneData data) {
        double dx = getX() - target.getX();
        double dz = getZ() - target.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal > 1.0E-3) {
            followBearing = new Vec3(dx / horizontal, 0, dz / horizontal);
        }
        Vec3 offset = followBearing.scale(data.config().followDistance());
        double y = target.getEyeY() + ServerConfig.get(ServerConfig.DRONE_FOLLOW_HEIGHT_OFFSET);
        return new Vec3(target.getX() + offset.x, y, target.getZ() + offset.z);
    }

    /**
     * Flies straight at the goal while the line to it is clear, otherwise follows a path. The straight line is
     * checked on the staggered tick, or at once after bumping into something. Paths are recomputed on the staggered
     * tick or when the goal has moved more than {@code drone.repathDistance}.
     */
    private void steerTowards(Vec3 goal, double speed, boolean staggered) {
        if (staggered || recheckDirectPath || (directPathClear && (horizontalCollision || verticalCollision))) {
            directPathClear = isClearPath(goal);
            recheckDirectPath = false;
        }
        if (directPathClear) {
            if (pathGoal != null) {
                navigation.stop();
                pathGoal = null;
            }
            moveControl.setWantedPosition(goal.x, goal.y, goal.z, speed);
        } else if (staggered || pathGoal == null || pathGoal.distanceToSqr(goal) > Mth.square(ServerConfig.get(ServerConfig.DRONE_REPATH_DISTANCE))) {
            navigation.moveTo(goal.x, goal.y, goal.z, speed);
            pathGoal = goal;
        } else {
            navigation.setSpeedModifier(speed);
        }
    }

    /** Whether the drone's center can fly in a straight line to the goal (a position for the drone's feet). */
    private boolean isClearPath(Vec3 goal) {
        double halfHeight = getBbHeight() / 2;
        Vec3 from = position().add(0, halfHeight, 0);
        Vec3 to = goal.add(0, halfHeight, 0);
        return level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this))
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

    // --- Persistence ---

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        DroneData.CODEC.encodeStart(NbtOps.INSTANCE, snapshotData()).result().ifPresent(data -> tag.put(TAG_DRONE_DATA, data));
        tag.putBoolean(TAG_DRIFTING, drifting);
        if (restPosition != null) {
            tag.put(TAG_REST_POSITION, NbtUtils.writeBlockPos(restPosition));
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
        restPosition = NbtUtils.readBlockPos(tag, TAG_REST_POSITION).orElse(null);
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
