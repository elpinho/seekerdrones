package com.elpinho.seekerdrones.drone;

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
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
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

    /** Server-side drone state. Health lives in the entity itself and is copied back by {@link #snapshotData()}. */
    @Nullable
    private DroneData droneData;
    /** True from hand-deploy until the drone first comes to rest. */
    private boolean drifting;
    /** Where a hand-deployed drone came to rest (patrol center fallback, section 3.2). */
    @Nullable
    private BlockPos restPosition;

    public DroneEntity(EntityType<? extends DroneEntity> type, Level level) {
        super(type, level);
        setNoGravity(true);
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
        calculateEntityAnimation(false);
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide() && isAlive() && isInWater()) {
            int interval = ServerConfig.get(ServerConfig.DRONE_WATER_DAMAGE_INTERVAL);
            if ((tickCount + getId()) % interval == 0) {
                hurt(damageSources().drown(), ServerConfig.get(ServerConfig.DRONE_WATER_DAMAGE).floatValue());
            }
        }
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
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains(TAG_DRONE_DATA)) {
            DroneData.CODEC.parse(NbtOps.INSTANCE, tag.get(TAG_DRONE_DATA)).result().ifPresent(this::setDroneData);
        }
        drifting = tag.getBoolean(TAG_DRIFTING);
        restPosition = NbtUtils.readBlockPos(tag, TAG_REST_POSITION).orElse(null);
    }
}
