package com.elpinho.seekerdrones.gametest;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import javax.annotation.Nullable;

import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.drone.DroneItem;
import com.elpinho.seekerdrones.drone.DronePermissions;
import com.elpinho.seekerdrones.operator.OperatorGroup;
import com.elpinho.seekerdrones.operator.OperatorGroups;
import com.elpinho.seekerdrones.registry.ModEntityTypes;
import com.elpinho.seekerdrones.registry.ModItems;
import com.mojang.authlib.GameProfile;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * M2 (operators) GameTests: Operator Group membership, permission edge cases, revocation, saved data
 * round-tripping, and the {@code /seekerdrones group} debug command (DESIGN.md sections 6.1, 6.2, 8.3).
 *
 * All tests share the {@code seekerdrones:empty} structure template. Each test uses fresh random group and
 * player UUIDs so tests don't interfere with each other through the shared {@link OperatorGroups} saved data.
 */
@GameTestHolder("seekerdrones")
@PrefixGameTestTemplate(false)
public class OperatorGameTests {

    // --- 1. Group operators can interact with a grouped drone (DESIGN.md 6.2) ---

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void operatorCanPickUpGroupedDroneEntity(GameTestHelper helper) {
        OperatorGroups groups = OperatorGroups.get(server(helper));
        UUID owner = UUID.randomUUID();
        UUID operatorUuid = UUID.randomUUID();
        UUID groupId = groups.createGroup(owner);
        helper.assertTrue(groups.addOperator(groupId, operatorUuid), "addOperator should succeed for a new operator");

        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        DroneData data = groupedData(groupId);
        drone.setDroneData(data);

        Player operator = spawnPlayer(helper, operatorUuid, 4, 1, 4, true, 0);
        operator.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);

        helper.assertTrue(DronePermissions.canInteract(operator, data), "Operator should be able to interact with the drone's group");

        drone.interact(operator, InteractionHand.MAIN_HAND);

        helper.assertTrue(drone.isRemoved(), "Drone entity should be removed after pickup by an operator");
        helper.assertTrue(findDroneStack(operator) != null, "Operator's inventory should contain the picked-up drone item");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void operatorCanHandDeployGroupedDroneItem(GameTestHelper helper) {
        OperatorGroups groups = OperatorGroups.get(server(helper));
        UUID owner = UUID.randomUUID();
        UUID operatorUuid = UUID.randomUUID();
        UUID groupId = groups.createGroup(owner);
        groups.addOperator(groupId, operatorUuid);

        Player operator = spawnPlayer(helper, operatorUuid, 4, 1, 4, true, 0);
        ItemStack stack = DroneItem.createStack(groupedData(groupId));
        operator.setItemInHand(InteractionHand.MAIN_HAND, stack);

        ModItems.DRONE.get().use(helper.getLevel(), operator, InteractionHand.MAIN_HAND);

        helper.assertTrue(stack.isEmpty(), "Drone item stack should be consumed when an operator hand-deploys it");
        helper.assertEntityPresent(ModEntityTypes.DRONE.get());
        helper.succeed();
    }

    // --- 2. Non-operators are blocked (DESIGN.md 6.2) ---

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void nonOperatorCannotPickUpGroupedDroneEntity(GameTestHelper helper) {
        OperatorGroups groups = OperatorGroups.get(server(helper));
        UUID owner = UUID.randomUUID();
        UUID groupId = groups.createGroup(owner);

        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        DroneData data = groupedData(groupId);
        drone.setDroneData(data);

        Player intruder = spawnPlayer(helper, UUID.randomUUID(), 4, 1, 4, true, 0);
        intruder.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);

        helper.assertFalse(DronePermissions.canInteract(intruder, data), "Non-operator should not be able to interact with the drone's group");

        drone.interact(intruder, InteractionHand.MAIN_HAND);

        helper.assertFalse(drone.isRemoved(), "Drone entity should remain in the world when pickup is denied");
        helper.assertTrue(findDroneStack(intruder) == null, "Non-operator's inventory should not contain a drone item");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void nonOperatorCannotHandDeployGroupedDroneItem(GameTestHelper helper) {
        OperatorGroups groups = OperatorGroups.get(server(helper));
        UUID owner = UUID.randomUUID();
        UUID groupId = groups.createGroup(owner);

        Player intruder = spawnPlayer(helper, UUID.randomUUID(), 4, 1, 4, true, 0);
        ItemStack stack = DroneItem.createStack(groupedData(groupId));
        intruder.setItemInHand(InteractionHand.MAIN_HAND, stack);

        ModItems.DRONE.get().use(helper.getLevel(), intruder, InteractionHand.MAIN_HAND);

        helper.assertFalse(stack.isEmpty(), "Denied hand-deploy should not consume the drone item stack");
        helper.assertTrue(stack.getCount() == 1, "Denied hand-deploy should leave the stack count unchanged, was " + stack.getCount());
        helper.assertEntityNotPresent(ModEntityTypes.DRONE.get());
        helper.succeed();
    }

    // --- 3. The group owner counts as an operator without being in the operators set (DESIGN.md 6.1) ---

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void groupOwnerCountsAsOperatorWithoutBeingInOperatorsSet(GameTestHelper helper) {
        OperatorGroups groups = OperatorGroups.get(server(helper));
        UUID owner = UUID.randomUUID();
        UUID groupId = groups.createGroup(owner);

        OperatorGroup group = groups.getGroup(groupId).orElseThrow();
        helper.assertFalse(group.operators().contains(owner), "Owner should not be stored in the operators set");

        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        DroneData data = groupedData(groupId);
        drone.setDroneData(data);

        Player ownerPlayer = spawnPlayer(helper, owner, 4, 1, 4, true, 0);
        ownerPlayer.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);

        helper.assertTrue(DronePermissions.canInteract(ownerPlayer, data), "Owner should count as an operator");

        drone.interact(ownerPlayer, InteractionHand.MAIN_HAND);

        helper.assertTrue(drone.isRemoved(), "Owner should be able to pick up the drone");
        helper.succeed();
    }

    // --- 4. Ungrouped drones are usable by anyone (DESIGN.md 6.2 edge cases) ---

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void droneWithNoGroupIsUsableByAnyone(GameTestHelper helper) {
        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        DroneData data = DroneData.createNew();
        helper.assertTrue(data.groupId().isEmpty(), "Sanity: a fresh drone should have no group");
        drone.setDroneData(data);

        Player stranger = spawnPlayer(helper, UUID.randomUUID(), 4, 1, 4, true, 0);
        stranger.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);

        helper.assertTrue(DronePermissions.canInteract(stranger, data), "An ungrouped drone should be usable by anyone");

        drone.interact(stranger, InteractionHand.MAIN_HAND);

        helper.assertTrue(drone.isRemoved(), "Any player should be able to pick up an ungrouped drone");
        helper.succeed();
    }

    // --- 5. A drone whose group doesn't exist is usable by nobody, not even its would-be owner (DESIGN.md 6.2 edge cases) ---

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void droneWithUnknownGroupIsDeniedToEveryoneWithoutBypass(GameTestHelper helper) {
        UUID unknownGroupId = UUID.randomUUID(); // never created via OperatorGroups
        DroneData data = DroneData.createNew().withGroupId(Optional.of(unknownGroupId));

        DroneEntity strangerDrone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(2, 3, 2));
        strangerDrone.setDroneData(data);
        Player stranger = spawnPlayer(helper, UUID.randomUUID(), 2, 1, 2, true, 0);
        stranger.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        helper.assertFalse(DronePermissions.canInteract(stranger, data), "An unknown group should deny a random stranger");
        strangerDrone.interact(stranger, InteractionHand.MAIN_HAND);
        helper.assertFalse(strangerDrone.isRemoved(), "Stranger should not be able to pick up a drone with an unknown group");

        // Even the UUID that would have been the owner, had the group actually existed, is denied.
        DroneEntity wouldBeOwnerDrone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(6, 3, 6));
        wouldBeOwnerDrone.setDroneData(data);
        UUID wouldBeOwner = UUID.randomUUID();
        Player wouldBeOwnerPlayer = spawnPlayer(helper, wouldBeOwner, 6, 1, 6, true, 0);
        wouldBeOwnerPlayer.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        helper.assertFalse(DronePermissions.canInteract(wouldBeOwnerPlayer, data), "An unknown group should deny even its would-be owner");
        wouldBeOwnerDrone.interact(wouldBeOwnerPlayer, InteractionHand.MAIN_HAND);
        helper.assertFalse(wouldBeOwnerDrone.isRemoved(), "Would-be owner should not be able to pick up a drone with an unknown group");

        helper.succeed();
    }

    // --- 6. Server operators (permission level >= 2) bypass the interaction check only (DESIGN.md 6.2 edge cases) ---

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void serverOperatorBypassesInteractionCheckButNotGroupMembership(GameTestHelper helper) {
        OperatorGroups groups = OperatorGroups.get(server(helper));
        UUID owner = UUID.randomUUID();
        UUID groupId = groups.createGroup(owner);

        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        DroneData data = groupedData(groupId);
        drone.setDroneData(data);

        UUID opUuid = UUID.randomUUID();
        // Permission level 2, not a member of the group: exercises the bypass in DronePermissions.canInteract.
        Player op = spawnPlayer(helper, opUuid, 4, 1, 4, true, 2);
        op.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);

        helper.assertTrue(DronePermissions.canInteract(op, data), "A permission-level-2 player should bypass the group check for direct interaction");
        helper.assertFalse(DronePermissions.isOperator(server(helper), data, opUuid),
                "isOperator must ignore the permission bypass (Player Seek exemption / Transmitter recipients stay group-only)");

        drone.interact(op, InteractionHand.MAIN_HAND);
        helper.assertTrue(drone.isRemoved(), "The bypassing server operator should be able to pick up the drone");
        helper.succeed();
    }

    // --- 7. Revocation applies immediately to already-deployed drones (DESIGN.md 6.1) ---

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void revocationImmediatelyBlocksPickupAfterRemoveOperator(GameTestHelper helper) {
        OperatorGroups groups = OperatorGroups.get(server(helper));
        UUID owner = UUID.randomUUID();
        UUID operatorUuid = UUID.randomUUID();
        UUID groupId = groups.createGroup(owner);
        groups.addOperator(groupId, operatorUuid);

        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        DroneData data = groupedData(groupId);
        drone.setDroneData(data);

        Player operator = spawnPlayer(helper, operatorUuid, 4, 1, 4, true, 0);
        helper.assertTrue(DronePermissions.canInteract(operator, data), "Sanity: should be an operator before revocation");

        helper.assertTrue(groups.removeOperator(groupId, operatorUuid), "removeOperator should succeed for a real, non-owner operator");

        operator.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        drone.interact(operator, InteractionHand.MAIN_HAND);

        helper.assertFalse(drone.isRemoved(), "A revoked operator should immediately be denied pickup of an already-deployed drone");
        helper.succeed();
    }

    // --- 8. The owner can't be removed from their own group (DESIGN.md 6.1) ---

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void removeOperatorCannotRemoveOwner(GameTestHelper helper) {
        OperatorGroups groups = OperatorGroups.get(server(helper));
        UUID owner = UUID.randomUUID();
        UUID groupId = groups.createGroup(owner);

        helper.assertFalse(groups.removeOperator(groupId, owner), "removeOperator should refuse to remove the owner");
        helper.assertTrue(groups.isOperator(groupId, owner), "Owner should still be an operator after the failed removal");
        helper.succeed();
    }

    // --- 9. Saved data round trip (DESIGN.md 8.3) ---

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void savedDataRoundTripPreservesGroups(GameTestHelper helper) {
        OperatorGroups groups = OperatorGroups.get(server(helper));
        UUID owner = UUID.randomUUID();
        UUID operatorUuid = UUID.randomUUID();
        UUID groupId = groups.createGroup(owner);
        groups.addOperator(groupId, operatorUuid);

        HolderLookup.Provider registries = helper.getLevel().registryAccess();
        CompoundTag tag = groups.save(new CompoundTag(), registries);
        OperatorGroups loaded = invokePrivateLoad(tag, registries);

        helper.assertTrue(loaded.isOperator(groupId, owner), "Owner should survive a save/load round trip");
        helper.assertTrue(loaded.isOperator(groupId, operatorUuid), "Operator should survive a save/load round trip");
        helper.assertFalse(loaded.isOperator(groupId, UUID.randomUUID()), "An unrelated UUID should not be an operator after the round trip");
        helper.succeed();
    }

    // --- 10. The /seekerdrones group assign command sets the group ID on entities (DESIGN.md 6, optional) ---

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void groupAssignCommandSetsGroupIdOnDroneEntities(GameTestHelper helper) {
        MinecraftServer server = server(helper);
        OperatorGroups groups = OperatorGroups.get(server);
        UUID owner = UUID.randomUUID();
        UUID groupId = groups.createGroup(owner);

        DroneEntity drone = helper.spawn(ModEntityTypes.DRONE.get(), new BlockPos(4, 3, 4));
        helper.assertTrue(drone.snapshotData().groupId().isEmpty(), "Sanity: drone starts with no group");

        Vec3 pos = helper.absoluteVec(new Vec3(4, 3, 4));
        CommandSourceStack source = server.createCommandSourceStack()
                .withLevel(helper.getLevel())
                .withPosition(pos)
                .withPermission(4);
        String command = String.format(Locale.ROOT,
                "seekerdrones group assign %s @e[type=seekerdrones:drone,distance=..3]", groupId);
        server.getCommands().performPrefixedCommand(source, command);

        helper.runAfterDelay(2, () -> {
            helper.assertValueEqual(drone.snapshotData().groupId(), Optional.of(groupId), "drone group ID after /seekerdrones group assign");
            helper.succeed();
        });
    }

    // --- Helpers ---

    private static MinecraftServer server(GameTestHelper helper) {
        return helper.getLevel().getServer();
    }

    private static DroneData groupedData(UUID groupId) {
        return DroneData.createNew().withGroupId(Optional.of(groupId));
    }

    /** A mock player at a fixed UUID and permission level, positioned in the test structure. */
    private static Player spawnPlayer(GameTestHelper helper, UUID uuid, double x, double y, double z, boolean sneaking, int permissionLevel) {
        Player player = new Player(helper.getLevel(), BlockPos.ZERO, 0.0F, new GameProfile(uuid, "op-test-player")) {
            @Override
            public boolean isSpectator() {
                return false;
            }

            @Override
            public boolean isCreative() {
                return false;
            }

            @Override
            protected int getPermissionLevel() {
                return permissionLevel;
            }
        };
        Vec3 pos = helper.absoluteVec(new Vec3(x, y, z));
        player.moveTo(pos.x, pos.y, pos.z, 0.0F, 0.0F);
        player.setShiftKeyDown(sneaking);
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

    /**
     * Reaches {@code OperatorGroups.load(CompoundTag, HolderLookup.Provider)} directly, since it's private
     * (only ever called through the {@code SavedData.Factory} machinery in production).
     */
    private static OperatorGroups invokePrivateLoad(CompoundTag tag, HolderLookup.Provider registries) {
        try {
            Method load = OperatorGroups.class.getDeclaredMethod("load", CompoundTag.class, HolderLookup.Provider.class);
            load.setAccessible(true);
            return (OperatorGroups) load.invoke(null, tag, registries);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new RuntimeException(e);
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e.getCause());
        }
    }
}
