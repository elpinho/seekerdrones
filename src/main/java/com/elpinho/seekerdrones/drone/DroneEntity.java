package com.elpinho.seekerdrones.drone;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.annotation.Nullable;

import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.feed.PilotInput;
import com.elpinho.seekerdrones.remote.DroneRemoteItem;
import com.elpinho.seekerdrones.network.DroneStatusPayload;
import com.elpinho.seekerdrones.registry.ModSounds;
import com.elpinho.seekerdrones.operator.OperatorGroups;
import com.elpinho.seekerdrones.station.ChargingStationBlockEntity;

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
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
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
import net.minecraft.world.level.block.state.BlockState;
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
    private static final EntityDataAccessor<Float> DATA_SCALE = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> DATA_STATE = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.INT);
    // Sounds (section 2.9): the client plays the flying and low-power loops from these.
    private static final EntityDataAccessor<Byte> DATA_LOW_POWER = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Boolean> DATA_EXPLOSIVE = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Float> DATA_SOUND_VOLUME = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_SOUND_PITCH = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.FLOAT);

    /** Client side: told when a drone's low-power level or Explosive approach changes, so the loops are re-picked at once. */
    private static Runnable clientSoundListener = () -> {};

    private static final String TAG_DRONE_DATA = "DroneData";
    private static final String TAG_DRIFTING = "Drifting";
    private static final String TAG_REST_POSITION = "RestPosition";
    private static final String TAG_STATE = "State";
    private static final String TAG_TARGET = "Target";

    /** The follow range the path finder's node budget was sized for (vanilla's default follow range). */
    private static final float BASE_FOLLOW_RANGE = 16.0F;

    /** Min ticks between free-space and clear-path re-checks triggered by bumping into blocks. */
    static final int COLLISION_RECHECK_TICKS = 5;
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
    /** The team whose shared target claims this drone counts toward (section 3.3). Kept in step by {@link #setDroneData}. */
    private TargetClaims.Team claimTeam = TargetClaims.Team.of(DroneData.createNew());
    /** Whether the drone has an Explosive upgrade. Kept in step by {@link #setDroneData}. */
    private boolean explosive;
    /** True from hand-deploy or a Deploying Station launch until the drone first comes to rest. */
    private boolean drifting;
    /**
     * Where a hand-deployed drone came to rest, or the block above the Deploying Station that launched it (patrol center
     * fallback, section 3.2).
     */
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
    /** Whether the last scan skipped an entity only because a teammate had claimed it. Not saved. */
    private boolean scanSkippedClaimed;
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

    // --- Siren and Transmitter (server only, not saved) ---
    /** The tick at which the siren repeats while a target is held. */
    private int nextSirenTick;
    /** Game time of the last Transmitter message, or -1 if none was sent yet. */
    private long lastTransmitTime = -1;

    // --- Behavior helpers (server only) ---
    private final DroneEnergy energy = new DroneEnergy(this);
    private final DroneCharging charging = new DroneCharging(this);
    private final DronePatrol patrol = new DronePatrol(this);
    private final DronePursuit pursuit = new DronePursuit(this);
    private final DroneOrders orders = new DroneOrders(this);

    // --- Drone Remote (server only, section 2.10) ---
    /** The drone ID this drone is under in the {@link DroneIndex}, or null while it isn't indexed. */
    @Nullable
    private String indexedId;
    /** The pilot's latest input while PILOTED (section 2.13). Not saved: piloting ends when the drone unloads. */
    private PilotInput pilotInput = PilotInput.NONE;
    /** Whether the pilot boosted this tick, which multiplies the distance cost (section 2.13). */
    private boolean boosting;
    /** Client side: the game time until which the drone glows for the local player after being linked. */
    private long clientGlowUntil;

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
        builder.define(DATA_SCALE, 1.0F);
        builder.define(DATA_STATE, DroneState.IDLE.ordinal());
        builder.define(DATA_LOW_POWER, (byte) LowPowerLevel.NONE.ordinal());
        builder.define(DATA_EXPLOSIVE, false);
        builder.define(DATA_SOUND_VOLUME, 1.0F);
        builder.define(DATA_SOUND_PITCH, 1.0F);
    }

    public static void setClientSoundListener(Runnable listener) {
        clientSoundListener = listener;
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (level().isClientSide() && (key == DATA_LOW_POWER || key == DATA_EXPLOSIVE || key == DATA_STATE && entityData.get(DATA_EXPLOSIVE))) {
            clientSoundListener.run();
        }
    }

    @Override
    public void onAddedToLevel() {
        super.onAddedToLevel();
        if (level().isClientSide()) {
            clientSoundListener.run();
        } else if (droneData != null) {
            indexedId = droneData.droneId();
            DroneIndex.add(this, indexedId);
            updateDeployedCount(droneData);
        }
    }

    /** Client side: makes the drone glow for the local player only, until the given game time (section 2.10). */
    public void glowUntil(long gameTime) {
        clientGlowUntil = gameTime;
    }

    @Override
    public boolean isCurrentlyGlowing() {
        return super.isCurrentlyGlowing() || level().isClientSide() && level().getGameTime() < clientGlowUntil;
    }

    // --- Drone data ---

    DroneData getDroneData() {
        if (droneData == null) {
            setDroneData(DroneData.createNew());
        }
        return droneData;
    }

    /** The cached target matcher, rebuilt first if the server config changed since (section 2.7 fail-safe). */
    private TargetMatcher targetMatcher() {
        if (targetMatcher.isStale()) {
            targetMatcher = TargetMatcher.of(getDroneData());
        }
        return targetMatcher;
    }

    /**
     * Replaces the data without the side effects of {@link #setDroneData}, for changes that only touch the energy
     * (draining and charging).
     */
    void replaceData(DroneData data) {
        droneData = data;
    }

    /** Replaces the drone's data, including its current health. */
    public void setDroneData(DroneData data) {
        droneData = data;
        if (indexedId != null && !indexedId.equals(data.droneId())) {
            DroneIndex.remove(this, indexedId);
            indexedId = data.droneId();
            DroneIndex.add(this, indexedId);
        } else if (indexedId == null && isAddedToLevel() && !level().isClientSide()) {
            // Given its data after joining the level: index it now.
            indexedId = data.droneId();
            DroneIndex.add(this, indexedId);
        }
        if (isAddedToLevel()) {
            updateDeployedCount(data);
        }
        AttributeInstance maxHealth = getAttribute(Attributes.MAX_HEALTH);
        if (maxHealth != null) {
            maxHealth.setBaseValue(DroneStats.maxHealth(data));
        }
        setHealth(Math.min(data.health(), getMaxHealth()));
        targetMatcher = TargetMatcher.of(data);
        updateClaimTeam(data);
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
        entityData.set(DATA_SCALE, DroneStats.visualScale(data));
        entityData.set(DATA_EXPLOSIVE, DroneStats.isExplosive(data));
        entityData.set(DATA_SOUND_VOLUME, DroneStats.soundVolume(data));
        entityData.set(DATA_SOUND_PITCH, DroneStats.soundPitch(data));
        updateLowPower();
    }

    /**
     * Keeps the claim team and Explosive flag in step with the data. A drone holding a target whose team or kind
     * changed moves its claim, and drops the target if the new team's limit is already reached (section 3.3).
     */
    private void updateClaimTeam(DroneData data) {
        TargetClaims.Team team = TargetClaims.Team.of(data);
        boolean nowExplosive = DroneStats.isExplosive(data);
        if (team.equals(claimTeam) && nowExplosive == explosive) {
            return;
        }
        boolean reclaim = target != null && !level().isClientSide();
        if (reclaim) {
            TargetClaims.release(this);
        }
        claimTeam = team;
        explosive = nowExplosive;
        if (reclaim && !TargetClaims.claim(this)) {
            loseTarget();
        }
    }

    TargetClaims.Team getClaimTeam() {
        return claimTeam;
    }

    boolean isExplosive() {
        return explosive;
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

    /** Sets only the shown color, for client-side GUI previews of a drone that is never added to the level. */
    public void setPreviewColor(DyeColor color) {
        entityData.set(DATA_COLOR, color.getId());
    }

    /** How big the drone is drawn (section 3.7). Visual only: the hitbox stays the same. */
    public float getVisualScale() {
        return entityData.get(DATA_SCALE);
    }

    public DroneState getState() {
        return DroneState.BY_ID.apply(entityData.get(DATA_STATE));
    }

    void setState(DroneState state) {
        entityData.set(DATA_STATE, state.ordinal());
        updateLowPower();
    }

    // --- Sounds (section 2.9) ---

    /** Which low-power beep loop the drone plays. */
    public LowPowerLevel getLowPower() {
        return LowPowerLevel.BY_ID.apply(entityData.get(DATA_LOW_POWER));
    }

    /** Whether the drone has an Explosive upgrade, from the synced field, so it works on the client too. */
    public boolean hasExplosiveUpgrade() {
        return entityData.get(DATA_EXPLOSIVE);
    }

    /** Whether the drone is an Explosive drone flying at its target, whose flying loop always plays. */
    public boolean isApproachingExplosive() {
        return entityData.get(DATA_EXPLOSIVE) && getState() == DroneState.CHASING;
    }

    /** Volume multiplier for the drone's own sounds (not the Siren), from its upgrade count. */
    public float getSoundVolumeMultiplier() {
        return entityData.get(DATA_SOUND_VOLUME);
    }

    /** Pitch multiplier for the drone's own sounds (not the Siren), from its upgrade count. */
    public float getSoundPitchMultiplier() {
        return entityData.get(DATA_SOUND_PITCH);
    }

    /**
     * Re-derives the synced low-power level from the state and energy: none while docked, critical while undocked with
     * little energy left (in any state), else returning while RETURNING.
     */
    void updateLowPower() {
        if (level().isClientSide() || droneData == null) {
            return;
        }
        DroneState state = getState();
        LowPowerLevel lowPower;
        if (state == DroneState.CHARGING) {
            lowPower = LowPowerLevel.NONE;
        } else if (DroneStats.isCriticalEnergy(droneData)) {
            lowPower = LowPowerLevel.CRITICAL;
        } else {
            lowPower = state == DroneState.RETURNING ? LowPowerLevel.RETURNING : LowPowerLevel.NONE;
        }
        entityData.set(DATA_LOW_POWER, (byte) lowPower.ordinal());
    }

    /** Plays one of the drone's own one-shot sounds, louder and lower with more upgrades. */
    void playDroneSound(SoundEvent sound, double volume, double pitch) {
        level().playSound(null, getX(), getY(), getZ(), sound, SoundSource.NEUTRAL,
                (float) volume * getSoundVolumeMultiplier(), (float) pitch * getSoundPitchMultiplier());
    }

    /** The propeller spin-up, played whenever a drone is deployed by hand or by a Deploying Station. */
    public void playDeploySound() {
        playDroneSound(ModSounds.DRONE_DEPLOY.get(), ServerConfig.get(ServerConfig.SOUNDS_DEPLOY_VOLUME), 1.0);
    }

    /** The entity being chased or followed, if any. Server side only. */
    @Nullable
    public Entity getSeekTarget() {
        return target;
    }

    public boolean isDrifting() {
        return drifting;
    }

    public int getEnergy() {
        return getDroneData().energy();
    }

    /** The station being returned to or charged at, if any. Server side only. */
    @Nullable
    public BlockPos getChargingStation() {
        return charging.station();
    }

    /**
     * Why the drone is in its current state (section 2.4), worked out from state it already keeps. Server side only,
     * called when a status screen or Jade asks, never per tick.
     */
    public DroneReason.Info resolveReason() {
        DroneData data = getDroneData();
        MinecraftServer server = getServer();
        if (server != null && data.groupId().isPresent() && OperatorGroups.get(server).getGroup(data.groupId().get()).isEmpty()) {
            return DroneReason.Info.of(DroneReason.UNKNOWN_GROUP);
        }
        DroneState state = getState();
        if (state == DroneState.CHARGING || state == DroneState.RETURNING) {
            return DroneReason.Info.of(charging.stationReason(state));
        }
        DroneReason.Info order = orders.reason(state);
        if (order != null) {
            return order;
        }
        if (state == DroneState.CHASING || state == DroneState.FOLLOWING) {
            if (lostSightSince >= 0) {
                int ticksLeft = ServerConfig.get(ServerConfig.DRONE_LOST_SIGHT_TIMEOUT) - (tickCount - lostSightSince);
                return new DroneReason.Info(DroneReason.TARGET_OUT_OF_SIGHT, Math.max(0, Mth.ceil(ticksLeft / (float) DroneStats.TICKS_PER_SECOND)));
            }
        } else if (state == DroneState.IDLE || state == DroneState.PATROLLING) {
            if (data.config().targets().isEmpty()) {
                return DroneReason.Info.of(DroneReason.NO_TARGET_ENTRIES);
            }
            if (TargetMatcher.targetableEntries(data).isEmpty()) {
                return DroneReason.Info.of(DroneReason.NO_USABLE_TARGETS);
            }
            if (scanSkippedClaimed) {
                return DroneReason.Info.of(DroneReason.TARGET_CLAIMED);
            }
        }
        return charging.nearestStation(data) == null ? DroneReason.Info.of(DroneReason.NO_STATION) : DroneReason.Info.NONE;
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

    /**
     * Called on a Deploying Station launch (section 7.3): the drone drifts like after hand-deploy, but its rest position
     * is the given spot above the station, wherever it ends up.
     */
    public void startDrifting(GlobalPos fixedRestPosition) {
        drifting = true;
        restPosition = fixedRestPosition;
    }

    /** Ends the drift. The rest position is where the drone is now, unless a Deploying Station already fixed it. */
    private void stopDrifting() {
        drifting = false;
        if (restPosition == null) {
            restPosition = currentPosition();
        }
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
                stopDrifting();
            }
        }
        setDeltaMovement(velocity);
    }

    /** Records who the drone counts against for {@code drone.maxDeployedPerPlayer} (section 2.2). Server side only. */
    private void updateDeployedCount(DroneData data) {
        if (getServer() != null && !isRemoved()) {
            DeployedDrones.get(getServer()).update(getServer(), data);
        }
    }

    /** Destroyed, picked up, exploded, unloaded with its chunk or moved to another dimension: free the target (section 3.3). */
    @Override
    public void onRemovedFromLevel() {
        super.onRemovedFromLevel();
        if (!level().isClientSide()) {
            TargetClaims.release(this);
            // Gone for good (not unloaded or moved to another dimension): it no longer counts against its player.
            RemovalReason reason = getRemovalReason();
            if (reason != null && reason.shouldDestroy() && droneData != null && getServer() != null) {
                DeployedDrones.get(getServer()).remove(droneData.droneId());
            }
            if (indexedId != null) {
                DroneIndex.remove(this, indexedId);
                indexedId = null;
            }
        }
    }

    @Override
    public void tick() {
        // Docked for this tick if it starts it docked: a drone that finishes charging during the tick doesn't pay for it.
        boolean docked = getState() == DroneState.CHARGING;
        super.tick();
        if (!level().isClientSide() && isAlive() && isInWater()) {
            int interval = ServerConfig.get(ServerConfig.DRONE_WATER_DAMAGE_INTERVAL);
            if (isStaggeredTick(interval)) {
                hurt(damageSources().drown(), ServerConfig.get(ServerConfig.DRONE_WATER_DAMAGE).floatValue());
            }
        }
        if (!level().isClientSide() && isAlive() && !isRemoved()) {
            energy.tick(docked);
        }
    }

    /** Spreads periodic work over different ticks per drone (section 3.3). */
    boolean isStaggeredTick(int interval) {
        return (tickCount + getId()) % interval == 0;
    }

    // --- Seeking AI (section 3) ---

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        DroneData data = getDroneData();
        boolean staggered = isStaggeredTick(ServerConfig.get(ServerConfig.DRONE_SCAN_INTERVAL));
        DroneState state = getState();
        if (state == DroneState.PILOTED) {
            // Flown by hand: the AI is paused (section 2.13).
            tickPiloted();
            return;
        }
        if (state == DroneState.RETURNING || state == DroneState.CHARGING) {
            // Returning overrides everything else, and the drone doesn't scan for targets (section 5.2).
            charging.tick(data, staggered);
            updateFacing(null);
            return;
        }
        if (orders.tickPassive(state, staggered)) {
            // Hold, Recall, Move or Strike: no scanning, chasing or claiming (sections 2.10 and 7.10). The return
            // threshold still overrides them.
            return;
        }
        orders.tickGuardTimer();
        if (pendingTargetId != null) {
            resolvePendingTarget(staggered);
            return;
        }
        if (target == null) {
            if (staggered) {
                scanForTarget(data);
            }
            if (target == null && !drifting) {
                if (orders.isGuarding()) {
                    orders.tickGuardHover(staggered);
                } else if (charging.hasHomeGoal()) {
                    charging.flyHome(data, staggered);
                } else {
                    patrol.tick(data, staggered);
                }
            }
        } else if (isTargetLost(data, staggered)) {
            loseTarget();
        } else {
            if (tickCount >= nextSirenTick) {
                playSiren(data);
            }
            pursuit.pursue(data, staggered);
        }
        // A following drone keeps an eye on its target; any other drone faces where it is flying.
        updateFacing(target != null && !DroneStats.isExplosive(data) ? pursuit.targetAnchor() : null);
    }

    /**
     * Turns the drone toward {@code lookAt}, or toward its direction of travel if null (section 3.4). It only starts
     * turning once it faces more than {@code drone.facingTolerance} away, then eases into the turn at up to
     * {@code drone.turnSpeed}, so small target movements don't make it twitch.
     */
    void updateFacing(@Nullable Vec3 lookAt) {
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
            // Claims aren't saved: take the claim again, or drop the target if the team's limit is reached (section 3.3).
            if (!TargetClaims.claim(this)) {
                target = null;
                setState(DroneState.IDLE);
                return;
            }
            lostSightSince = -1;
            recheckDirectPath = true;
            pursuit.reset();
            // Not a new acquisition: no Transmitter message, and the siren keeps its repeat interval.
            nextSirenTick = tickCount + ServerConfig.get(ServerConfig.UPGRADES_SIREN_REPEAT_INTERVAL);
        } else if (staggered) {
            pendingTargetId = null;
            setState(DroneState.IDLE);
        }
    }

    /**
     * Cheap filtering first, raycasts last (section 3.3): AABB query, target match, sight sphere, then raycasts to
     * the nearest candidates until one is visible.
     */
    private void scanForTarget(DroneData data) {
        scanSkippedClaimed = false;
        TargetMatcher matcher = targetMatcher();
        if (matcher.isEmpty()) {
            return;
        }
        double range = DroneStats.sightRange(data);
        double rangeSqr = range * range;
        List<Entity> candidates = level().getEntities(this, getBoundingBox().inflate(range), entity -> {
            if (!matcher.matches(this, entity) || distanceToSqr(entity) > rangeSqr || isHidden(entity)) {
                return false;
            }
            if (!TargetClaims.canClaim(this, entity)) {
                // Remembered for the status reason (section 2.4), at no extra cost.
                scanSkippedClaimed = true;
                return false;
            }
            return true;
        });
        if (candidates.isEmpty()) {
            return;
        }
        candidates.sort(Comparator.comparingDouble(this::distanceToSqr));
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
        // The scan only offers entities that can be claimed.
        TargetClaims.claim(this);
        lostSightSince = -1;
        recheckDirectPath = true;
        pursuit.reset();
        // A drifting drone that spots a target starts chasing at once (section 2.2). It never comes to rest, so where
        // it spotted the target becomes its rest position (patrol center fallback, section 3.2).
        if (drifting) {
            stopDrifting();
        }
        patrol.reset();
        charging.setHomeGoal(null);
        setState(DroneState.CHASING);
        playDroneSound(ModSounds.DRONE_LOCK_ON.get(), ServerConfig.get(ServerConfig.SOUNDS_LOCK_ON_VOLUME), ServerConfig.get(ServerConfig.SOUNDS_LOCK_ON_PITCH));
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
     * Section 3.3: an invisible entity is hidden from every drone unless it glows, wears armor or
     * holds an item.
     */
    private static boolean isHidden(Entity entity) {
        if (!entity.isInvisible() || !ServerConfig.get(ServerConfig.DRONE_INVISIBILITY_HIDES) || entity.hasGlowingTag()) {
            return false;
        }
        if (entity instanceof LivingEntity living) {
            if (living.hasEffect(MobEffects.GLOWING)) {
                return false;
            }
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                if (!living.getItemBySlot(slot).isEmpty()) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Section 3.5. The cheap checks run every tick. Line of sight is re-checked only on the staggered tick, and a
     * hidden target (invisibility) counts as out of sight.
     */
    private boolean isTargetLost(DroneData data, boolean staggered) {
        if (target.isRemoved() || target.level() != level() || !targetMatcher().matches(this, target)) {
            return true;
        }
        double pursuitRange = DroneStats.pursuitRange(data);
        if (distanceToSqr(target) > pursuitRange * pursuitRange) {
            return true;
        }
        if (staggered) {
            if (!isHidden(target) && canSee(target)) {
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
        TargetClaims.release(this);
        target = null;
        lostSightSince = -1;
        pursuit.reset();
        stopNavigating();
        setState(DroneState.IDLE);
        playDroneSound(ModSounds.DRONE_TARGET_LOST.get(), ServerConfig.get(ServerConfig.SOUNDS_TARGET_LOST_VOLUME),
                ServerConfig.get(ServerConfig.SOUNDS_TARGET_LOST_PITCH));
    }

    void stopNavigating() {
        pathGoal = null;
        navigation.stop();
    }

    /** Whether the drone is following a navigation path. */
    boolean hasPathGoal() {
        return pathGoal != null;
    }

    /** Forces a direct-path check on the next {@link #steerTowards} instead of waiting for the staggered tick. */
    void requestPathRecheck() {
        recheckDirectPath = true;
    }

    DroneMoveControl droneMoveControl() {
        return (DroneMoveControl) moveControl;
    }

    /** Whether the drone fits at {@code pos} (its feet position) without touching any block, in a loaded chunk. */
    boolean hasRoomAt(Vec3 pos) {
        return level().hasChunkAt(BlockPos.containing(pos)) && level().noBlockCollision(this, getDimensions(getPose()).makeBoundingBox(pos));
    }

    // --- Explosive (section 3.1) ---

    /** The drone is consumed: no drop. Whether the explosion breaks blocks is up to the server config. */
    void explode(DroneData data) {
        level().explode(this, getX(), getY(0.5), getZ(), DroneStats.explosionPower(data),
                ServerConfig.get(ServerConfig.UPGRADES_EXPLOSIVE_BLOCK_DAMAGE).interaction());
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

    GlobalPos currentPosition() {
        return GlobalPos.of(level().dimension(), blockPosition());
    }

    /** Sets the rest position, e.g. the spot a drone without Patrol guarded (section 7.10). */
    void setRestPosition(GlobalPos position) {
        restPosition = position;
    }

    /** Makes where the drone is now its rest position (patrol center fallback 4, section 3.2), and returns it. */
    GlobalPos restHere() {
        restPosition = currentPosition();
        return restPosition;
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
    boolean steerTowards(Vec3 goal, double speed, double acceleration, @Nullable Vec3 stopAt, boolean staggered, boolean movingGoal) {
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

    // --- Energy and charging (section 5) ---

    DroneEnergy energy() {
        return energy;
    }

    DroneCharging charging() {
        return charging;
    }

    DronePatrol patrol() {
        return patrol;
    }

    /** FE per tick the Solar upgrades generated at the last energy drain (section 4), 0 without any or under cover. */
    public double getSolarRate() {
        return energy.solarRate();
    }

    public double getSelfHealRate() {
        return energy.selfHealRate();
    }

    /** FE per tick the last energy drain cost, everything included (section 2.12). */
    public double getDrainRate() {
        return energy.drainRate();
    }

    /** Whether the drone holds a target or has one loaded from NBT still to look up. */
    boolean hasOrAwaitsTarget() {
        return target != null || pendingTargetId != null;
    }

    /**
     * Drops any target, recall, drift and patrol, and heads for the station. Where the drone is now is where it comes
     * back to (section 5.3).
     */
    void startReturning(BlockPos station) {
        orders.clear();
        TargetClaims.release(this);
        target = null;
        pendingTargetId = null;
        lostSightSince = -1;
        pursuit.reset();
        if (drifting) {
            stopDrifting();
        }
        patrol.reset();
        charging.startReturning(station);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        boolean hurt = super.hurt(source, amount);
        if (hurt) {
            energy.onHurt();
        }
        return hurt;
    }

    /** Drones never use portals, so they never change dimension by themselves (section 3.6). */
    @Override
    public boolean canUsePortal(boolean allowPassengers) {
        return false;
    }

    @Override
    public boolean canChangeDimensions(Level oldLevel, Level newLevel) {
        return false;
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

    // Drones fly: no footstep sounds or step vibrations, and no fall tracking, which would spawn block particles
    // (and trample farmland) when a descending drone touches the ground (section 2.5).

    @Override
    protected MovementEmission getMovementEmission() {
        return MovementEmission.NONE;
    }

    @Override
    protected void checkFallDamage(double y, boolean onGround, BlockState state, BlockPos pos) {
    }

    // --- Interaction ---

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (player.getItemInHand(hand).getItem() instanceof DroneRemoteItem) {
            // The Drone Remote links the drone instead (section 2.10).
            return InteractionResult.PASS;
        }
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
            playDroneSound(ModSounds.DRONE_DESTROY.get(), ServerConfig.get(ServerConfig.SOUNDS_DESTROY_VOLUME), 1.0);
            remove(RemovalReason.KILLED);
        }
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return ModSounds.DRONE_HURT.get();
    }

    /** Only used for the hurt sound: drones have no ambient sound, and tickDeath() plays the destruction sounds. */
    @Override
    protected float getSoundVolume() {
        return ServerConfig.get(ServerConfig.SOUNDS_HURT_VOLUME).floatValue() * getSoundVolumeMultiplier();
    }

    @Override
    public float getVoicePitch() {
        return super.getVoicePitch() * getSoundPitchMultiplier();
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
        tag.putString(TAG_STATE, getState().getSerializedName());
        UUID targetId = target != null ? target.getUUID() : pendingTargetId;
        if (targetId != null) {
            tag.putUUID(TAG_TARGET, targetId);
        }
        charging.save(tag);
        energy.save(tag);
        orders.save(tag);
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
        pendingTargetId = null;
        charging.load(tag);
        energy.load(tag);
        // Restores Hold, Recall, Move, guarding and Strike, and keeps a guard order for a drone loaded mid-chase.
        boolean ordered = orders.load(tag, state);
        if (tag.hasUUID(TAG_TARGET) && (state == DroneState.CHASING || state == DroneState.FOLLOWING)) {
            pendingTargetId = tag.getUUID(TAG_TARGET);
            setState(state);
        } else if (charging.station() != null && (state == DroneState.RETURNING || state == DroneState.CHARGING)) {
            // Claims aren't saved: a docked drone claims its station again and re-docks, without a second dock clunk.
            charging.resumeAfterLoad(state == DroneState.CHARGING);
        } else if (ordered) {
            charging.forgetStation();
            setState(state);
        } else {
            charging.forgetStation();
            orders.clear();
            setState(DroneState.IDLE);
        }
    }

    // --- Drone Remote (section 2.10) ---

    /** The drone's ID, or an empty string if it has none yet. */
    public String getDroneId() {
        return getDroneData().droneId();
    }

    /** Whether the drone is HOLDING, RECALLED, MOVING or STRIKING: it ignores targets and holds no claim. */
    public boolean isPassive() {
        return DroneOrders.isPassive(getState());
    }

    /** Whether the drone carries out an order that Resume (the remote) or Release (the Command Center) ends. */
    public boolean hasResumableOrder() {
        return DroneOrders.isResumable(getState()) || orders.isGuarding();
    }

    /** The Command Center order the drone carries out, if any (section 7.10). Server side only. */
    @Nullable
    public DroneOrder.Info getOrder() {
        return orders.info();
    }

    /**
     * Drops everything the drone was doing before an order takes over: the target (and its claim), a station it was
     * flying to or docked at, a drift and the patrol or home flight.
     */
    void dropActivity() {
        DroneState state = getState();
        if (state == DroneState.RETURNING || state == DroneState.CHARGING) {
            charging.abandon();
        }
        TargetClaims.release(this);
        target = null;
        pendingTargetId = null;
        lostSightSince = -1;
        pursuit.reset();
        if (drifting) {
            stopDrifting();
        }
        patrol.reset();
        charging.setHomeGoal(null);
        stopNavigating();
    }

    /**
     * Recall: flies to {@code remote.recallDistance} blocks in front of the player at eye level, tracking them (RECALLED),
     * then holds there (HOLDING) for {@code remote.recallHoverTicks} and goes back to its patrol, unless resumed first
     * (section 2.10). A drone without Patrol goes back to where it was recalled. Works on a docked drone too.
     */
    public void recall(Player player) {
        orders.recall(player);
    }

    /** Hold: drops any target and hovers where it is until resumed (section 2.10). Works on a docked drone too. */
    public void hold() {
        orders.hold();
    }

    /**
     * Resume (the remote) or Release (the Command Center): ends Hold, Recall, Move, guarding or Strike, and the drone
     * goes back to normal behavior at once (sections 2.10 and 7.10). A recalled drone without Patrol goes back to where
     * it was recalled, as when its hold times out. Returns false if there was nothing to end.
     */
    public boolean resume() {
        return orders.release();
    }

    /**
     * Move (section 7.10): flies to {@code commandCenter.moveHeight} blocks above {@code top} (the top block's top
     * center at the spot), passive, then guards the spot. Works on a docked drone too.
     */
    public void moveTo(Vec3 top) {
        orders.move(top);
    }

    /**
     * Strike (section 7.10): rushes to {@code top} (the top block's top center at the spot), passive, and detonates
     * there. Returns false, and does nothing, without an Explosive upgrade.
     */
    public boolean strike(Vec3 top) {
        if (!DroneStats.isExplosive(getDroneData())) {
            return false;
        }
        orders.strike(top);
        return true;
    }

    /**
     * Return to charge: sends the drone to the nearest usable station now, even an Explosive drone that is chasing
     * (section 2.10). Returns false, and does nothing, if there is none or the drone is already returning or charging.
     */
    public boolean returnToCharge() {
        DroneState state = getState();
        if (state == DroneState.RETURNING || state == DroneState.CHARGING) {
            return false;
        }
        charging.invalidateStationCache();
        BlockPos station = charging.nearestStation(getDroneData());
        if (station == null) {
            return false;
        }
        startReturning(station);
        return true;
    }

    /** Patrol center here: sets the configured patrol center (section 3.2). Returns false without a Patrol upgrade. */
    public boolean setPatrolCenter(GlobalPos center) {
        DroneData data = snapshotData();
        if (!DroneStats.isPatrolling(data)) {
            return false;
        }
        setDroneData(data.withConfig(data.config().withPatrolCenter(Optional.of(center))));
        return true;
    }

    // --- Remote piloting (section 2.13) ---

    public boolean isPiloted() {
        return getState() == DroneState.PILOTED;
    }

    /** An operator takes control: the drone drops what it was doing and stops its AI until piloting ends. */
    public void startPiloting() {
        dropActivity();
        orders.clear();
        pilotInput = PilotInput.NONE;
        boosting = false;
        setState(DroneState.PILOTED);
    }

    /**
     * Piloting ended: the AI takes over again right away. A drone with Patrol heads for the nearest waypoint, one
     * without hovers where it was released, which becomes its rest position. If it's below its return threshold, it
     * goes to charge.
     */
    public void stopPiloting() {
        if (!isPiloted()) {
            return;
        }
        pilotInput = PilotInput.NONE;
        boosting = false;
        DroneData data = getDroneData();
        if (!DroneStats.isPatrolling(data)) {
            restPosition = currentPosition();
        }
        patrol.reset();
        recheckDirectPath = true;
        setState(DroneState.IDLE);
        charging.checkReturnThreshold(data);
    }

    /** The pilot's movement keys, ignored unless the drone is piloted. */
    public void setPilotInput(PilotInput input) {
        if (isPiloted()) {
            pilotInput = input;
        }
    }

    public boolean isBoosting() {
        return boosting;
    }

    /** Detonates a piloted Explosive drone where it is. False, and nothing happens, for any other drone. */
    public boolean detonate() {
        DroneData data = getDroneData();
        if (!isPiloted() || !DroneStats.isExplosive(data)) {
            return false;
        }
        explode(data);
        return true;
    }

    /**
     * Whether the drone has no more energy than it needs to get home: the HUD's LOW POWER warning while piloted. With
     * no station in range, whether its energy is critical.
     */
    public boolean isBelowReturnThreshold() {
        DroneData data = getDroneData();
        BlockPos station = charging.nearestStation(data);
        if (station == null) {
            return DroneStats.isCriticalEnergy(data);
        }
        return data.energy() <= DroneStats.returnThreshold(data, Math.sqrt(distanceToSqr(ChargingStationBlockEntity.dockPosition(station))));
    }

    /** Flies where the pilot's keys point, at the piloting speed, with the usual inertia. */
    private void tickPiloted() {
        if (pathGoal != null) {
            stopNavigating();
        }
        PilotInput input = pilotInput;
        boosting = input.boost() && input.isMoving();
        double perSecond = ServerConfig.get(boosting ? ServerConfig.PILOT_BOOST_SPEED : ServerConfig.PILOT_SPEED);
        double speed = Math.min(perSecond / DroneStats.TICKS_PER_SECOND, ServerConfig.get(ServerConfig.DRONE_MAX_SPEED));
        ((DroneMoveControl) moveControl).fly(input.direction().scale(speed), ServerConfig.get(ServerConfig.DRONE_ACCELERATION));
        updateFacing(null);
    }
}
