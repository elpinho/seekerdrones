package com.elpinho.seekerdrones.gametest;

import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import javax.annotation.Nullable;

import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.DroneItem;
import com.elpinho.seekerdrones.drone.DronePermissions;
import com.elpinho.seekerdrones.drone.DroneStats;
import com.elpinho.seekerdrones.factory.DroneFactoryBlockEntity;
import com.elpinho.seekerdrones.operator.OperatorGroups;
import com.elpinho.seekerdrones.registry.ModBlocks;
import com.elpinho.seekerdrones.registry.ModDataComponents;
import com.elpinho.seekerdrones.registry.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * M6 (Drone Factory) GameTests: building a drone from items, fluid and FE, the operation rules around FE, the
 * output slot and mismatched inputs, the automation item handler, and the Factory's Operator Group lifecycle
 * (DESIGN.md sections 6.1, 7.1 and 7.5).
 *
 * <p>The GameTest server runs without Mekanism, so every test here uses the base drone assembly recipe: 4
 * {@code seekerdrones:drone_rotor}, 1 {@code seekerdrones:seeker_core}, 4 {@code c:ingots/iron} (plain iron ingots),
 * 1000 mB lava, 50 000 FE over 200 ticks (see {@code src/generated/resources/data/seekerdrones/recipe/drone_assembly/drone.json}).
 *
 * <p>Charging Station repair fluid tests (fluid capability and healing behavior, DESIGN.md sections 5.3 and 7.4) live
 * in {@link EnergyGameTests}, which already has the drone-charging helpers ({@code forceReturning}, {@code pollUntil},
 * {@code placeChargingStation}) this behavior needs.
 *
 * <p>All tests share the {@code seekerdrones:empty} structure template.
 */
@GameTestHolder("seekerdrones")
@PrefixGameTestTemplate(false)
public class FactoryGameTests {

    // --- 1. Building a drone (DESIGN.md section 7.1) ---

    @GameTest(template = "empty", timeoutTicks = 260)
    public static void factoryBuildsFullyChargedDroneLinkedToItsGroup(GameTestHelper helper) {
        UUID ownerUuid = UUID.randomUUID();
        Player owner = ownerAt(helper, ownerUuid);
        BlockPos rel = new BlockPos(4, 3, 4);
        DroneFactoryBlockEntity factory = placeFactoryOwnedBy(helper, rel, owner);
        UUID groupId = factory.getGroupId().orElseThrow();

        // One extra iron ingot and 500 extra mB of lava, to prove leftovers stay and only the recipe amount is spent.
        factory.getItems().setStackInSlot(0, new ItemStack(ModItems.DRONE_ROTOR.get(), 4));
        factory.getItems().setStackInSlot(1, new ItemStack(ModItems.SEEKER_CORE.get(), 1));
        factory.getItems().setStackInSlot(2, new ItemStack(Items.IRON_INGOT, 5));
        factory.getFluidHandler().fill(new FluidStack(Fluids.LAVA, 1500), IFluidHandler.FluidAction.EXECUTE);
        factory.getEnergyStorage().receiveEnergy(Integer.MAX_VALUE, false);

        helper.succeedWhen(() -> {
            ItemStack output = factory.getItems().getStackInSlot(DroneFactoryBlockEntity.OUTPUT_SLOT);
            if (output.isEmpty()) {
                throw new GameTestAssertException("Factory hasn't finished building yet, progress=" + factory.getProgress());
            }
            DroneData built = DroneItem.getData(output);
            helper.assertTrue(built.hasDroneId(), "Built drone should have a non-empty drone ID");
            helper.assertValueEqual(built.groupId(), Optional.of(groupId), "Built drone should be linked to the Factory's group");
            helper.assertTrue(built.energy() == DroneStats.maxEnergy(built),
                    "Built drone should be fully charged, was " + built.energy() + "/" + DroneStats.maxEnergy(built));
            helper.assertTrue(built.health() == DroneStats.maxHealth(built),
                    "Built drone should be at full health, was " + built.health() + "/" + DroneStats.maxHealth(built));
            helper.assertTrue(built.upgrades().isEmpty(), "A freshly built drone should have no upgrades");

            helper.assertTrue(factory.getItems().getStackInSlot(0).isEmpty(), "All 4 Drone Rotors should be consumed");
            helper.assertTrue(factory.getItems().getStackInSlot(1).isEmpty(), "The Seeker Core should be consumed");
            ItemStack leftoverIron = factory.getItems().getStackInSlot(2);
            helper.assertTrue(leftoverIron.getItem() == Items.IRON_INGOT && leftoverIron.getCount() == 1,
                    "Only 4 of the 5 iron ingots should be consumed, 1 should remain, slot had " + leftoverIron);
            helper.assertTrue(factory.getFluid().getAmount() == 500,
                    "Only 1000 of the 1500 mB lava should be consumed, 500 should remain, tank had " + factory.getFluid().getAmount());
        });
    }

    // --- 2. Two builds give two different drone IDs (DESIGN.md section 7.1) ---

    @GameTest(template = "empty", timeoutTicks = 500)
    public static void twoBuildsProduceDifferentDroneIds(GameTestHelper helper) {
        DroneFactoryBlockEntity factory = placeFactory(helper, new BlockPos(4, 3, 4));
        fillRecipeInputs(factory);
        factory.getEnergyStorage().receiveEnergy(Integer.MAX_VALUE, false);

        pollUntil(helper, () -> !factory.getItems().getStackInSlot(DroneFactoryBlockEntity.OUTPUT_SLOT).isEmpty(), 250, () -> {
            DroneData first = DroneItem.getData(factory.getItems().extractItem(DroneFactoryBlockEntity.OUTPUT_SLOT, 1, false));
            helper.assertTrue(first.hasDroneId(), "Sanity: first drone should have a drone ID");

            fillRecipeInputs(factory);
            factory.getEnergyStorage().receiveEnergy(Integer.MAX_VALUE, false);

            pollUntil(helper, () -> !factory.getItems().getStackInSlot(DroneFactoryBlockEntity.OUTPUT_SLOT).isEmpty(), 250, () -> {
                DroneData second = DroneItem.getData(factory.getItems().getStackInSlot(DroneFactoryBlockEntity.OUTPUT_SLOT));
                helper.assertTrue(!second.droneId().equals(first.droneId()),
                        "Two builds should produce different drone IDs, both were " + first.droneId());
                helper.succeed();
            }, () -> helper.fail("Second build never finished, progress=" + factory.getProgress()));
        }, () -> helper.fail("First build never finished, progress=" + factory.getProgress()));
    }

    // --- 3. No FE means no progress, and it resumes once FE arrives (DESIGN.md section 7.1 Operation) ---

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void factoryDoesNotProgressWithoutEnergyThenResumes(GameTestHelper helper) {
        DroneFactoryBlockEntity factory = placeFactory(helper, new BlockPos(4, 3, 4));
        fillRecipeInputs(factory);
        // Deliberately no energy given yet.

        helper.runAfterDelay(15, () -> {
            helper.assertTrue(factory.getProgress() == 0, "Factory shouldn't progress without FE, progress=" + factory.getProgress());

            factory.getEnergyStorage().receiveEnergy(Integer.MAX_VALUE, false);
            helper.runAfterDelay(5, () -> {
                helper.assertTrue(factory.getProgress() > 0, "Factory should resume progress once FE arrives, progress=" + factory.getProgress());
                helper.succeed();
            });
        });
    }

    // --- 4. Output slot occupied blocks the build; it resumes once cleared (DESIGN.md section 7.1 Operation) ---

    @GameTest(template = "empty", timeoutTicks = 260)
    public static void factoryDoesNotBuildWhileOutputSlotOccupied(GameTestHelper helper) {
        DroneFactoryBlockEntity factory = placeFactory(helper, new BlockPos(4, 3, 4));
        fillRecipeInputs(factory);
        factory.getEnergyStorage().receiveEnergy(Integer.MAX_VALUE, false);
        factory.getItems().setStackInSlot(DroneFactoryBlockEntity.OUTPUT_SLOT, new ItemStack(Blocks.DIRT));

        helper.runAfterDelay(15, () -> {
            helper.assertTrue(factory.getProgress() == 0,
                    "Factory shouldn't start a build while the output slot is occupied, progress=" + factory.getProgress());

            factory.getItems().setStackInSlot(DroneFactoryBlockEntity.OUTPUT_SLOT, ItemStack.EMPTY);
            pollUntil(helper, () -> {
                ItemStack output = factory.getItems().getStackInSlot(DroneFactoryBlockEntity.OUTPUT_SLOT);
                return !output.isEmpty() && output.getItem() == ModItems.DRONE.get();
            }, 220, helper::succeed,
                    () -> helper.fail("Factory never finished a build once the output slot was cleared, progress=" + factory.getProgress()));
        });
    }

    // --- 5. Automation item handler restrictions (DESIGN.md section 7.1 Operation) ---

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void automationItemHandlerRestrictsInsertAndExtract(GameTestHelper helper) {
        DroneFactoryBlockEntity factory = placeFactory(helper, new BlockPos(4, 3, 4));
        ItemStack ironStack = new ItemStack(Items.IRON_INGOT, 4);
        factory.getItems().setStackInSlot(0, ironStack.copy());
        DroneData outputData = DroneData.createNew().withDroneId("AUTO0001");
        factory.getItems().setStackInSlot(DroneFactoryBlockEntity.OUTPUT_SLOT, DroneItem.createStack(outputData));

        IItemHandler automation = factory.getAutomationItems();

        ItemStack toInsert = new ItemStack(Items.DIAMOND, 1);
        ItemStack leftover = automation.insertItem(DroneFactoryBlockEntity.OUTPUT_SLOT, toInsert.copy(), false);
        helper.assertTrue(ItemStack.matches(leftover, toInsert), "Inserting into the output slot should be fully rejected, got back " + leftover);
        helper.assertFalse(automation.isItemValid(DroneFactoryBlockEntity.OUTPUT_SLOT, toInsert),
                "The output slot should never report as valid for automation inserts");

        ItemStack extractedFromInput = automation.extractItem(0, 64, false);
        helper.assertTrue(extractedFromInput.isEmpty(), "Automation should not be able to extract from an input slot, got " + extractedFromInput);
        helper.assertTrue(ItemStack.matches(factory.getItems().getStackInSlot(0), ironStack),
                "The input slot's contents should be unchanged after a denied extraction");

        ItemStack extractedOutput = automation.extractItem(DroneFactoryBlockEntity.OUTPUT_SLOT, 64, false);
        helper.assertFalse(extractedOutput.isEmpty(), "Automation should be able to extract the finished drone from the output slot");
        helper.assertTrue(DroneItem.getData(extractedOutput).droneId().equals("AUTO0001"),
                "The extracted stack should be the drone that was sitting in the output slot");

        helper.succeed();
    }

    // --- 6. Mismatched inputs never progress (DESIGN.md section 7.1) ---

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void mismatchedInputsNeverProgress(GameTestHelper helper) {
        // Case A: items match, but the tank holds water instead of lava.
        DroneFactoryBlockEntity waterFactory = placeFactory(helper, new BlockPos(2, 3, 2));
        waterFactory.getItems().setStackInSlot(0, new ItemStack(ModItems.DRONE_ROTOR.get(), 4));
        waterFactory.getItems().setStackInSlot(1, new ItemStack(ModItems.SEEKER_CORE.get(), 1));
        waterFactory.getItems().setStackInSlot(2, new ItemStack(Items.IRON_INGOT, 4));
        waterFactory.getFluidHandler().fill(new FluidStack(Fluids.WATER, 1000), IFluidHandler.FluidAction.EXECUTE);
        waterFactory.getEnergyStorage().receiveEnergy(Integer.MAX_VALUE, false);

        // Case B: fluid matches, but the Seeker Core is missing.
        DroneFactoryBlockEntity missingCoreFactory = placeFactory(helper, new BlockPos(6, 3, 6));
        missingCoreFactory.getItems().setStackInSlot(0, new ItemStack(ModItems.DRONE_ROTOR.get(), 4));
        missingCoreFactory.getItems().setStackInSlot(2, new ItemStack(Items.IRON_INGOT, 4));
        missingCoreFactory.getFluidHandler().fill(new FluidStack(Fluids.LAVA, 1000), IFluidHandler.FluidAction.EXECUTE);
        missingCoreFactory.getEnergyStorage().receiveEnergy(Integer.MAX_VALUE, false);

        helper.runAfterDelay(20, () -> {
            helper.assertTrue(waterFactory.getProgress() == 0,
                    "A Factory with water instead of lava shouldn't progress, progress=" + waterFactory.getProgress());
            helper.assertTrue(missingCoreFactory.getProgress() == 0,
                    "A Factory missing the Seeker Core shouldn't progress, progress=" + missingCoreFactory.getProgress());
            helper.succeed();
        });
    }

    // --- 7. Operator Group lifecycle (DESIGN.md section 6.1) ---

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void placingCreatesGroupBreakingDropsItAndReplacingReconnects(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        UUID placerUuid = UUID.randomUUID();
        Player placer = ownerAt(helper, placerUuid);

        BlockPos rel = new BlockPos(4, 3, 4);
        DroneFactoryBlockEntity factory = placeFactoryOwnedBy(helper, rel, placer);
        UUID groupId = factory.getGroupId().orElseThrow();
        OperatorGroups groups = OperatorGroups.get(level.getServer());
        helper.assertTrue(groups.isOperator(groupId, placerUuid), "The first placer should be the new group's owner");

        BlockPos abs = helper.absolutePos(rel);
        BlockState state = level.getBlockState(abs);
        Block.dropResources(state, level, abs, factory);
        helper.setBlock(rel, Blocks.AIR);

        ItemStack dropped = findDroppedItem(helper, helper.absoluteVec(new Vec3(4, 3, 4)), ModItems.DRONE_FACTORY.get());
        helper.assertTrue(dropped != null, "Breaking the Factory should drop a Factory item");
        UUID droppedGroup = dropped.get(ModDataComponents.OPERATOR_GROUP);
        helper.assertValueEqual(droppedGroup, groupId, "The dropped Factory item should carry the same group ID");

        // Placing a block entity from that item reconnects to the same group instead of creating a new one:
        // applyComponentsFromItemStack runs before setPlacedBy in the real placement pipeline (see
        // DroneFactoryBlock.setPlacedBy's javadoc), and setPlacedBy only creates a fresh group when none is set yet.
        BlockPos rel2 = new BlockPos(6, 3, 6);
        BlockPos abs2 = helper.absolutePos(rel2);
        helper.setBlock(rel2, ModBlocks.DRONE_FACTORY.get());
        DroneFactoryBlockEntity factory2 = (DroneFactoryBlockEntity) level.getBlockEntity(abs2);
        factory2.applyComponentsFromItemStack(dropped);
        ModBlocks.DRONE_FACTORY.get().setPlacedBy(level, abs2, level.getBlockState(abs2), placer, dropped);

        helper.assertValueEqual(factory2.getGroupId(), Optional.of(groupId),
                "Placing the dropped item should reconnect to the same group, not create a new one");
        helper.succeed();
    }

    // --- 8. Operator changes immediately apply to a Factory-built drone (DESIGN.md sections 6.1, 6.2) ---

    @GameTest(template = "empty", timeoutTicks = 260)
    public static void operatorChangesApplyToFactoryBuiltDrone(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        UUID ownerUuid = UUID.randomUUID();
        Player owner = ownerAt(helper, ownerUuid);

        DroneFactoryBlockEntity factory = placeFactoryOwnedBy(helper, new BlockPos(4, 3, 4), owner);
        UUID groupId = factory.getGroupId().orElseThrow();
        fillRecipeInputs(factory);
        factory.getEnergyStorage().receiveEnergy(Integer.MAX_VALUE, false);

        helper.succeedWhen(() -> {
            ItemStack output = factory.getItems().getStackInSlot(DroneFactoryBlockEntity.OUTPUT_SLOT);
            if (output.isEmpty()) {
                throw new GameTestAssertException("Factory hasn't finished building yet, progress=" + factory.getProgress());
            }
            DroneData built = DroneItem.getData(output);
            helper.assertValueEqual(built.groupId(), Optional.of(groupId), "Sanity: built drone should carry the Factory's group ID");

            UUID playerXUuid = UUID.randomUUID();
            Player playerX = helper.makeMockPlayer(GameType.SURVIVAL);
            playerX.setUUID(playerXUuid);
            helper.assertFalse(DronePermissions.canInteract(playerX, built), "Player X should not be usable before being added as an operator");

            OperatorGroups groups = OperatorGroups.get(level.getServer());
            helper.assertTrue(groups.addOperator(groupId, playerXUuid), "addOperator should succeed for a fresh player");
            helper.assertTrue(DronePermissions.canInteract(playerX, built), "Player X should be usable once added as an operator");

            helper.assertTrue(groups.removeOperator(groupId, playerXUuid), "removeOperator should succeed to revoke access");
            helper.assertFalse(DronePermissions.canInteract(playerX, built), "Player X should be denied again after being removed");
        });
    }

    // --- Helpers ---

    /** Places a Drone Factory with no player placer (no group). */
    private static DroneFactoryBlockEntity placeFactory(GameTestHelper helper, BlockPos relativePos) {
        helper.setBlock(relativePos, ModBlocks.DRONE_FACTORY.get());
        BlockPos abs = helper.absolutePos(relativePos);
        return (DroneFactoryBlockEntity) helper.getLevel().getBlockEntity(abs);
    }

    /** Places a Drone Factory and runs it through {@code setPlacedBy} with the given player, creating its group. */
    private static DroneFactoryBlockEntity placeFactoryOwnedBy(GameTestHelper helper, BlockPos relativePos, Player placer) {
        DroneFactoryBlockEntity factory = placeFactory(helper, relativePos);
        BlockPos abs = helper.absolutePos(relativePos);
        BlockState state = helper.getLevel().getBlockState(abs);
        ModBlocks.DRONE_FACTORY.get().setPlacedBy(helper.getLevel(), abs, state, placer, new ItemStack(ModItems.DRONE_FACTORY.get()));
        return factory;
    }

    /** A mock player at a fixed UUID, for use as a Factory placer / group owner. */
    private static Player ownerAt(GameTestHelper helper, UUID uuid) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setUUID(uuid);
        return player;
    }

    /** Fills the Factory's inputs and tank with exactly the base drone assembly recipe (no leftovers). */
    private static void fillRecipeInputs(DroneFactoryBlockEntity factory) {
        factory.getItems().setStackInSlot(0, new ItemStack(ModItems.DRONE_ROTOR.get(), 4));
        factory.getItems().setStackInSlot(1, new ItemStack(ModItems.SEEKER_CORE.get(), 1));
        factory.getItems().setStackInSlot(2, new ItemStack(Items.IRON_INGOT, 4));
        factory.getFluidHandler().fill(new FluidStack(Fluids.LAVA, 1000), IFluidHandler.FluidAction.EXECUTE);
    }

    @Nullable
    private static ItemStack findDroppedItem(GameTestHelper helper, Vec3 pos, Item item) {
        for (ItemEntity entity : helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(pos, pos).inflate(2.0))) {
            if (entity.getItem().getItem() == item) {
                return entity.getItem();
            }
        }
        return null;
    }

    /**
     * Polls {@code condition} every tick for up to {@code ticksRemaining} ticks, running {@code onReady} as soon as
     * it's true, or {@code onTimeout} if it never becomes true in time. Same convention as {@code EnergyGameTests}.
     */
    private static void pollUntil(GameTestHelper helper, BooleanSupplier condition, int ticksRemaining, Runnable onReady, Runnable onTimeout) {
        if (condition.getAsBoolean()) {
            onReady.run();
            return;
        }
        if (ticksRemaining <= 1) {
            onTimeout.run();
            return;
        }
        helper.runAtTickTime(helper.getTick() + 1, () -> pollUntil(helper, condition, ticksRemaining - 1, onReady, onTimeout));
    }
}
