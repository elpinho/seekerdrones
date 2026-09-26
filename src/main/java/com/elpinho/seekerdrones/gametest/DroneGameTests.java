package com.elpinho.seekerdrones.gametest;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.elpinho.seekerdrones.drone.DroneConfig;
import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.drone.DroneItem;
import com.elpinho.seekerdrones.drone.DroneStats;
import com.elpinho.seekerdrones.drone.TargetEntry;
import com.elpinho.seekerdrones.drone.UpgradeType;
import com.elpinho.seekerdrones.operator.OperatorGroups;
import com.elpinho.seekerdrones.registry.ModEntityTypes;
import com.elpinho.seekerdrones.registry.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * M1 (drone core) GameTests: lossless item/entity conversion, hand-deploy, drift-to-rest, destruction,
 * pickup (including a full inventory), water damage and NBT persistence (DESIGN.md section 2).
 *
 * All tests share the {@code seekerdrones:empty} structure template: an all-air 9x6x9 volume with no
 * pre-placed blocks, since drones fly with no gravity and don't need ground.
 */
@GameTestHolder("seekerdrones")
@PrefixGameTestTemplate(false)
public class DroneGameTests {
    private static final Pattern DRONE_ID_PATTERN = Pattern.compile("^[A-Z0-9]{4}-[A-Z0-9]{4}$");

    // --- 1. Lossless item -> entity -> item (DESIGN.md 2.1) ---

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void pickupPreservesDroneDataLosslessly(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        Player player = spawnSneakingPlayer(helper, 4, 1, 4);
        DroneData data = sampleDroneDataOwnedBy(helper, player, "LOSS-0001");
        drone.setDroneData(data);
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);

        drone.interact(player, InteractionHand.MAIN_HAND);

        helper.assertTrue(drone.isRemoved(), "Drone entity should be removed after pickup");
        ItemStack pickedUp = findDroneStack(player);
        helper.assertTrue(pickedUp != null, "Player inventory should contain the picked-up drone item");
        helper.assertValueEqual(DroneItem.getData(pickedUp), data, "picked-up drone item data");
        helper.succeed();
    }

    // --- 2. Hand-deploy (DESIGN.md 2.2, 2.8) ---

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void handDeployGeneratesFreshIdAndFullStats(GameTestHelper helper) {
        Player player = spawnSneakingPlayer(helper, 4, 1, 4);
        ItemStack stack = new ItemStack(ModItems.DRONE.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);

        ModItems.DRONE.get().use(helper.getLevel(), player, InteractionHand.MAIN_HAND);

        helper.assertTrue(stack.isEmpty(), "Drone item stack should be consumed on hand-deploy in survival");
        DroneEntity drone = helper.findOneEntity(ModEntityTypes.DRONE.get());
        DroneData data = drone.snapshotData();
        helper.assertTrue(DRONE_ID_PATTERN.matcher(data.droneId()).matches(),
                "Drone ID '" + data.droneId() + "' should match the readable ID pattern");
        helper.assertValueEqual(data.energy(), DroneStats.maxEnergy(data), "energy after hand-deploy without prior data");
        helper.assertValueEqual(data.health(), DroneStats.maxHealth(data), "health after hand-deploy without prior data");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void handDeployPreservesExistingData(GameTestHelper helper) {
        Player player = spawnSneakingPlayer(helper, 4, 1, 4);
        DroneData original = sampleDroneDataOwnedBy(helper, player, "ABCD-1234");
        ItemStack stack = DroneItem.createStack(original);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);

        ModItems.DRONE.get().use(helper.getLevel(), player, InteractionHand.MAIN_HAND);

        helper.assertTrue(stack.isEmpty(), "Drone item stack should be consumed on hand-deploy in survival");
        DroneEntity drone = helper.findOneEntity(ModEntityTypes.DRONE.get());
        helper.assertValueEqual(drone.snapshotData(), original, "hand-deployed drone data (existing ID kept)");
        helper.succeed();
    }

    // --- 3. Drift to rest (DESIGN.md 2.2) ---

    @GameTest(template = "empty", timeoutTicks = 90)
    public static void driftComesToRestWithoutFalling(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        double startY = drone.getY();
        drone.setDeltaMovement(0.5, 0.0, 0.0);
        drone.startDrifting();

        helper.runAfterDelay(60, () -> {
            helper.assertTrue(drone.getDeltaMovement().equals(Vec3.ZERO),
                    "Drone should have zero velocity after drifting to rest, was " + drone.getDeltaMovement());
            helper.assertTrue(drone.getRestPosition() != null, "Drone should have recorded a rest position");
            helper.assertTrue(Math.abs(drone.getY() - startY) < 0.01,
                    "Drone should not have fallen while drifting (no gravity), dY=" + (drone.getY() - startY));
            BlockPos restPos = drone.getRestPosition();

            helper.runAfterDelay(10, () -> {
                helper.assertTrue(drone.getDeltaMovement().equals(Vec3.ZERO), "Drone should remain at rest (zero velocity)");
                helper.assertValueEqual(drone.getRestPosition(), restPos, "rest position should stay the same once at rest");
                helper.succeed();
            });
        });
    }

    // --- 4. Destruction (DESIGN.md 2.5) ---

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void destructionLeavesNoDrops(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        drone.hurt(helper.getLevel().damageSources().generic(), 1000.0F);

        helper.runAfterDelay(5, () -> {
            helper.assertTrue(drone.isRemoved(), "Drone should be removed after taking lethal damage");
            helper.assertItemEntityNotPresent(ModItems.DRONE.get());
            helper.assertEntityNotPresent(EntityType.EXPERIENCE_ORB);
            helper.succeed();
        });
    }

    // --- 5. Pickup with a full inventory (DESIGN.md 2.3) ---

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void pickupWithFullInventoryDropsItemAtFeet(GameTestHelper helper) {
        Player player = spawnSneakingPlayer(helper, 4, 1, 4);
        fillInventoryCompletely(player);

        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 1, 4));
        DroneData data = sampleDroneData("FULL-0001");
        drone.setDroneData(data);

        invokePrivatePickUp(drone, player);

        helper.assertTrue(drone.isRemoved(), "Drone should be removed after pickup even when the inventory is full");
        List<ItemEntity> drops = helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                new AABB(player.position(), player.position()).inflate(1.0));
        ItemEntity droppedDrone = null;
        for (ItemEntity item : drops) {
            if (item.getItem().getItem() == ModItems.DRONE.get()) {
                droppedDrone = item;
                break;
            }
        }
        helper.assertTrue(droppedDrone != null, "Expected a dropped drone item entity at the player's position");
        helper.assertValueEqual(DroneItem.getData(droppedDrone.getItem()), data, "dropped drone item data");
        helper.succeed();
    }

    // --- 6. Water damage (DESIGN.md 2.5) ---

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void waterDamageAppliesOverTimeInWaterOnly(GameTestHelper helper) {
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.WATER);

        DroneEntity wetDrone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(2, 1, 2));
        DroneEntity dryDrone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(6, 3, 6));
        float wetStartHealth = wetDrone.getHealth();
        float dryStartHealth = dryDrone.getHealth();

        helper.assertFalse(wetDrone.canDrownInFluidType(Fluids.WATER.getFluidType()), "Drone should not be able to drown");

        helper.runAfterDelay(30, () -> {
            helper.assertTrue(wetDrone.isInWater(), "Drone should be recognized as in water");
            helper.assertTrue(wetDrone.getHealth() < wetStartHealth,
                    "Drone in water should have lost health, was " + wetDrone.getHealth() + " (started at " + wetStartHealth + ")");
            helper.assertValueEqual(dryDrone.getHealth(), dryStartHealth, "drone in air should keep full health");
            helper.succeed();
        });
    }

    // --- 7. NBT persistence (DESIGN.md 8.2) ---

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void nbtRoundTripPreservesDroneData(GameTestHelper helper) {
        DroneEntity original = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        DroneData data = sampleDroneData("NBTS-0001");
        original.setDroneData(data);

        CompoundTag tag = original.saveWithoutId(new CompoundTag());

        DroneEntity loaded = ModEntityTypes.DRONE.get().create(helper.getLevel());
        loaded.load(tag);

        helper.assertValueEqual(loaded.snapshotData(), data, "drone data after NBT round-trip");
        helper.succeed();
    }

    // --- 8. No knockback (DESIGN.md 2.5) ---

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void hitsApplyNoKnockback(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        drone.setDeltaMovement(Vec3.ZERO);
        Vec3 startPos = drone.position();
        float startHealth = drone.getHealth();

        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        Vec3 playerPos = helper.absoluteVec(new Vec3(4, 3, 5));
        player.moveTo(playerPos.x, playerPos.y, playerPos.z, 0.0F, 0.0F);

        drone.hurt(helper.getLevel().damageSources().playerAttack(player), 1.0F);

        helper.assertTrue(drone.getHealth() < startHealth, "Drone should have taken damage from the attack");

        helper.runAfterDelay(5, () -> {
            helper.assertTrue(drone.getDeltaMovement().equals(Vec3.ZERO),
                    "Drone should have zero velocity after being hit, was " + drone.getDeltaMovement());
            helper.assertTrue(drone.position().distanceTo(startPos) < 0.01,
                    "Drone should not have moved after being hit, moved to " + drone.position());
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void explosionsApplyNoKnockback(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        DroneData data = sampleDroneData("BOOM-0001");
        drone.setDroneData(data.withHealth(DroneStats.maxHealth(data)));
        drone.setDeltaMovement(Vec3.ZERO);
        Vec3 startPos = drone.position();
        float startHealth = drone.getHealth();

        // 2 blocks away; strong enough to damage the boosted-health drone but not kill it.
        Vec3 explosionPos = helper.absoluteVec(new Vec3(4, 3, 6));
        helper.getLevel().explode(null, explosionPos.x, explosionPos.y, explosionPos.z, 2.0F, Level.ExplosionInteraction.NONE);

        helper.assertTrue(drone.isAlive(), "Drone should have survived the explosion with boosted health");
        helper.assertTrue(drone.getHealth() < startHealth, "Drone should have taken damage from the explosion");

        helper.runAfterDelay(5, () -> {
            helper.assertTrue(drone.getDeltaMovement().equals(Vec3.ZERO),
                    "Drone should have zero velocity after an explosion, was " + drone.getDeltaMovement());
            helper.assertTrue(drone.position().distanceTo(startPos) < 0.01,
                    "Drone should not have moved after an explosion, moved to " + drone.position());
            helper.succeed();
        });
    }

    // --- 9. Plain right-click shows status without deploying (DESIGN.md 2.4) ---

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void plainRightClickShowsStatusWithoutDeploying(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        Vec3 pos = helper.absoluteVec(new Vec3(4, 1, 4));
        player.moveTo(pos.x, pos.y, pos.z, 0.0F, 0.0F);
        player.setShiftKeyDown(false);
        ItemStack stack = new ItemStack(ModItems.DRONE.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);

        ModItems.DRONE.get().use(helper.getLevel(), player, InteractionHand.MAIN_HAND);

        helper.assertFalse(stack.isEmpty(), "Plain right-click should not consume the drone item stack");
        helper.assertTrue(stack.getCount() == 1, "Plain right-click should leave the stack count unchanged, was " + stack.getCount());
        helper.assertEntityNotPresent(ModEntityTypes.DRONE.get());
        helper.succeed();
    }

    // --- Helpers ---

    /** A drone data set exercising every field: mixed targets, patrol center, label, non-default color, upgrades. */
    private static DroneData sampleDroneData(String droneId) {
        List<TargetEntry> targets = List.of(
                new TargetEntry(TargetEntry.Kind.ENTITY_TYPE, "minecraft:zombie"),
                new TargetEntry(TargetEntry.Kind.TAG, "minecraft:raiders"),
                new TargetEntry(TargetEntry.Kind.PLAYER_NAME, "Steve"));
        DroneConfig config = new DroneConfig(targets, 6, Optional.of(new BlockPos(10, 70, 10)), "Sentry", DyeColor.RED);
        Map<UpgradeType, Integer> upgrades = Map.of(UpgradeType.ENERGY, 1, UpgradeType.HEALTH, 2);
        // Base max energy 100_000 + 1*100_000 = 200_000; base max health 20 + 2*10 = 40. Both values below max.
        return new DroneData(droneId, Optional.of(UUID.randomUUID()), 123_456, 15.0F, upgrades, config);
    }

    /** Sample data linked to a real Operator Group owned by {@code player}, so the M2 permission check lets them interact. */
    private static DroneData sampleDroneDataOwnedBy(GameTestHelper helper, Player player, String droneId) {
        UUID groupId = OperatorGroups.get(helper.getLevel().getServer()).createGroup(player.getUUID());
        return sampleDroneData(droneId).withGroupId(Optional.of(groupId));
    }

    private static Player spawnSneakingPlayer(GameTestHelper helper, double x, double y, double z) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        Vec3 pos = helper.absoluteVec(new Vec3(x, y, z));
        player.moveTo(pos.x, pos.y, pos.z, 0.0F, 0.0F);
        player.setShiftKeyDown(true);
        return player;
    }

    @Nullable
    private static ItemStack findDroneStack(Player player) {
        for (ItemStack stack : player.getInventory().items) {
            if (stack.getItem() == ModItems.DRONE.get()) {
                return stack;
            }
        }
        return null;
    }

    private static void fillInventoryCompletely(Player player) {
        NonNullList<ItemStack> items = player.getInventory().items;
        for (int i = 0; i < items.size(); i++) {
            items.set(i, new ItemStack(Items.DIRT, 64));
        }
    }

    /**
     * Calls {@code DroneEntity.pickUp(Player)} directly. It's private because it's only ever meant to be
     * reached through the sneak+empty-hand interaction, but that precondition guarantees an empty inventory
     * slot (the hand itself), so the "inventory full" drop branch can never be exercised through the public
     * entry point. Reflection lets the test reach it directly to verify the branch still works.
     */
    private static void invokePrivatePickUp(DroneEntity drone, Player player) {
        try {
            Method pickUp = DroneEntity.class.getDeclaredMethod("pickUp", Player.class);
            pickUp.setAccessible(true);
            pickUp.invoke(drone, player);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new RuntimeException(e);
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e.getCause());
        }
    }
}
