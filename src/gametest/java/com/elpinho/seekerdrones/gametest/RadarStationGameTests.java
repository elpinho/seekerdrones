package com.elpinho.seekerdrones.gametest;

import static com.elpinho.seekerdrones.gametest.GameTestUtil.removePlayer;
import static com.elpinho.seekerdrones.gametest.GameTestUtil.server;
import static com.elpinho.seekerdrones.gametest.GameTestUtil.spawnDrone;
import static com.elpinho.seekerdrones.gametest.GameTestUtil.spawnPlayer;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.network.RadarContactsPayload;
import com.elpinho.seekerdrones.operator.OperatorGroups;
import com.elpinho.seekerdrones.radar.RadarStationBlock;
import com.elpinho.seekerdrones.radar.RadarStationBlockEntity;
import com.elpinho.seekerdrones.registry.ModBlocks;
import com.elpinho.seekerdrones.registry.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Drone Radar Station GameTests (DESIGN.md section 7.8): energy, the POWERED state, per-viewer contact filtering, the
 * radius, two-block placement and breaking. The terrain tests are in {@code radar.RadarTerrainGameTests}, which needs
 * package access to the terrain builder. The contact list is built by a private method that the tick sends to each
 * viewer, so these tests reach it through reflection (as {@code OperatorGameTests} does for a private loader).
 */
@GameTestHolder("seekerdrones")
@PrefixGameTestTemplate(false)
public class RadarStationGameTests {
    private static final String RADIUS_BATCH = "config_radar_radius_8";
    private static int originalRadius;

    @BeforeBatch(batch = RADIUS_BATCH)
    public static void beforeRadius(ServerLevel level) {
        originalRadius = ServerConfig.get(ServerConfig.RADAR_STATION_RADIUS);
        ServerConfig.RADAR_STATION_RADIUS.set(8);
    }

    @AfterBatch(batch = RADIUS_BATCH)
    public static void afterRadius(ServerLevel level) {
        ServerConfig.RADAR_STATION_RADIUS.set(originalRadius);
    }

    // --- Helpers ---

    /** Places both halves (like a player placing it) and returns the lower half's block entity. */
    private static RadarStationBlockEntity placeStation(GameTestHelper helper, BlockPos rel) {
        BlockState lower = ModBlocks.RADAR_STATION.get().defaultBlockState();
        helper.setBlock(rel, lower);
        helper.setBlock(rel.above(), lower.setValue(RadarStationBlock.HALF, DoubleBlockHalf.UPPER));
        return (RadarStationBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(rel));
    }

    @SuppressWarnings("unchecked")
    private static List<RadarContactsPayload.Contact> contacts(GameTestHelper helper, RadarStationBlockEntity station, ServerPlayer viewer) {
        try {
            Method method = RadarStationBlockEntity.class.getDeclaredMethod("contacts", ServerLevel.class, ServerPlayer.class);
            method.setAccessible(true);
            return (List<RadarContactsPayload.Contact>) method.invoke(station, helper.getLevel(), viewer);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<ServerPlayer, Boolean> viewers(RadarStationBlockEntity station) {
        try {
            java.lang.reflect.Field field = RadarStationBlockEntity.class.getDeclaredField("viewers");
            field.setAccessible(true);
            return (java.util.Map<ServerPlayer, Boolean>) field.get(station);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Set<String> ids(List<RadarContactsPayload.Contact> contacts) {
        return contacts.stream().map(RadarContactsPayload.Contact::droneId).collect(Collectors.toSet());
    }

    private static DroneData data(String id) {
        return DroneData.createNew().withDroneId(id);
    }

    private static boolean powered(GameTestHelper helper, BlockPos rel) {
        return helper.getBlockState(rel).getValue(RadarStationBlock.POWERED);
    }

    private static int energyPerTick() {
        return ServerConfig.get(ServerConfig.RADAR_STATION_ENERGY_PER_TICK);
    }

    /** Total radar station items lying within 4 blocks of the test structure's middle. */
    private static int droppedStations(GameTestHelper helper, BlockPos rel) {
        Vec3 center = helper.absoluteVec(Vec3.atCenterOf(rel));
        int total = 0;
        for (ItemEntity entity : helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(center, center).inflate(4.0))) {
            if (entity.getItem().is(ModItems.RADAR_STATION.get())) {
                total += entity.getItem().getCount();
            }
        }
        return total;
    }

    // --- 1. Energy ---

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void radarUsesExactlyEnergyPerTickEveryTick(GameTestHelper helper) {
        RadarStationBlockEntity station = placeStation(helper, new BlockPos(4, 1, 4));
        IEnergyStorage energy = station.getEnergyStorage();
        int perTick = energyPerTick();
        helper.assertTrue(perTick > 0, "Fixture needs a positive energyPerTick");
        energy.receiveEnergy(perTick * 20, false);
        int[] last = {-1};
        int[] drops = {0};
        helper.onEachTick(() -> {
            int now = energy.getEnergyStored();
            if (last[0] >= 0 && (now != last[0] || drops[0] > 0)) {
                // Once it has started using FE it must use exactly energyPerTick on every tick.
                helper.assertTrue(last[0] - now == perTick, "FE should drop by exactly " + perTick + " per tick, went " + last[0] + " -> " + now);
                drops[0]++;
                if (drops[0] >= 6) {
                    helper.succeed();
                }
            }
            last[0] = now;
        });
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void radarWithLessThanOneTicksEnergyIsUnpoweredAndUsesNothing(GameTestHelper helper) {
        BlockPos rel = new BlockPos(4, 1, 4);
        RadarStationBlockEntity station = placeStation(helper, rel);
        IEnergyStorage energy = station.getEnergyStorage();
        int stored = energyPerTick() - 1;
        energy.receiveEnergy(stored, false);
        helper.runAfterDelay(8, () -> {
            helper.assertTrue(energy.getEnergyStored() == stored, "An unpowered station should use no FE, has " + energy.getEnergyStored() + " of " + stored);
            helper.assertTrue(!station.isPowered(), "Station should be unpowered");
            helper.assertTrue(!powered(helper, rel), "POWERED should be false");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void radarAcceptsEnergyOnEverySideOfLowerHalfOnly(GameTestHelper helper) {
        BlockPos rel = new BlockPos(4, 1, 4);
        placeStation(helper, rel);
        ServerLevel level = helper.getLevel();
        for (Direction side : Direction.values()) {
            IEnergyStorage cap = level.getCapability(Capabilities.EnergyStorage.BLOCK, helper.absolutePos(rel), side);
            helper.assertTrue(cap != null && cap.canReceive(), "Lower half should accept FE from " + side);
            helper.assertTrue(cap.receiveEnergy(10, true) == 10, "Lower half should take FE from " + side);
            helper.assertTrue(level.getCapability(Capabilities.EnergyStorage.BLOCK, helper.absolutePos(rel.above()), side) == null,
                    "Upper half must have no energy access from " + side);
        }
        helper.succeed();
    }

    // --- 2. POWERED state ---

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void poweredStateTurnsOnWhenPoweredAndOffWhenEnergyRunsOutWithoutFlicker(GameTestHelper helper) {
        BlockPos rel = new BlockPos(4, 1, 4);
        RadarStationBlockEntity station = placeStation(helper, rel);
        int perTick = energyPerTick();
        List<Boolean> seen = new ArrayList<>();
        helper.onEachTick(() -> seen.add(powered(helper, rel)));
        // Exactly two ticks of FE, given after a few empty ticks.
        helper.runAfterDelay(5, () -> station.getEnergyStorage().receiveEnergy(perTick * 2, false));
        helper.runAfterDelay(15, () -> {
            List<Boolean> runs = new ArrayList<>();
            for (boolean value : seen) {
                if (runs.isEmpty() || runs.get(runs.size() - 1) != value) {
                    runs.add(value);
                }
            }
            helper.assertTrue(runs.equals(List.of(false, true, false)),
                    "POWERED should go false -> true -> false exactly once each, saw runs " + runs + " over " + seen);
            helper.assertTrue(station.getEnergyStorage().getEnergyStored() == 0, "All FE should be used");
            helper.assertTrue(!station.isPowered(), "isPowered should be false once FE ran out");
            helper.assertTrue(!helper.getBlockState(rel.above()).getValue(RadarStationBlock.POWERED), "The upper half's POWERED must never be written");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 30)
    public static void poweredStateStaysTrueWhileEnergyLasts(GameTestHelper helper) {
        BlockPos rel = new BlockPos(4, 1, 4);
        RadarStationBlockEntity station = placeStation(helper, rel);
        station.getEnergyStorage().receiveEnergy(energyPerTick() * 30, false);
        List<Boolean> seen = new ArrayList<>();
        helper.onEachTick(() -> {
            if (helper.getTick() >= 3) {
                seen.add(powered(helper, rel));
            }
        });
        helper.runAfterDelay(12, () -> {
            helper.assertTrue(seen.size() >= 5 && seen.stream().allMatch(Boolean::booleanValue), "POWERED should stay true while FE lasts: " + seen);
            helper.succeed();
        });
    }

    // --- 3. Per-viewer filtering ---

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void viewerSeesOnlyDronesTheyAreOperatorOf(GameTestHelper helper) {
        RadarStationBlockEntity station = placeStation(helper, new BlockPos(0, 1, 0));
        OperatorGroups groups = OperatorGroups.get(server(helper));
        UUID viewerId = UUID.randomUUID();
        UUID otherOwner = UUID.randomUUID();
        UUID viewerGroup = groups.createGroup(otherOwner);
        groups.addOperator(viewerGroup, viewerId);
        UUID foreignGroup = groups.createGroup(otherOwner);
        String tag = UUID.randomUUID().toString().substring(0, 6);

        DroneEntity owned = spawnDrone(helper, 2, 1, 2, data("own-" + tag).withOwnerId(Optional.of(viewerId)));
        DroneEntity grouped = spawnDrone(helper, 3, 1, 2, data("grp-" + tag).withGroupId(Optional.of(viewerGroup)).withOwnerId(Optional.of(otherOwner)));
        spawnDrone(helper, 4, 1, 2, data("oth-" + tag).withOwnerId(Optional.of(otherOwner)));
        spawnDrone(helper, 5, 1, 2, data("unowned-" + tag));
        spawnDrone(helper, 6, 1, 2, data("foreign-" + tag).withGroupId(Optional.of(foreignGroup)));
        // The viewer is the owner, but the group takes over and it doesn't exist.
        spawnDrone(helper, 7, 1, 2, data("unknown-" + tag).withGroupId(Optional.of(UUID.randomUUID())).withOwnerId(Optional.of(viewerId)));

        ServerPlayer viewer = spawnPlayer(helper, viewerId, 1, 1, 6, 0);
        try {
            List<RadarContactsPayload.Contact> seen = contacts(helper, station, viewer);
            Set<String> ours = ids(seen).stream().filter(id -> id.endsWith(tag)).collect(Collectors.toSet());
            helper.assertTrue(ours.equals(Set.of("own-" + tag, "grp-" + tag)),
                    "Viewer should see only the owned and the group drone, saw " + ours);
            helper.assertTrue(seen.size() == 2, "No other drones should be listed, saw " + ids(seen));

            RadarContactsPayload.Contact contact = seen.stream().filter(c -> c.droneId().equals("own-" + tag)).findFirst().orElseThrow();
            Vec3 center = Vec3.atCenterOf(helper.absolutePos(new BlockPos(0, 1, 0)));
            helper.assertTrue(Math.abs(contact.dx() - (owned.getX() - center.x)) < 1e-4 && Math.abs(contact.dz() - (owned.getZ() - center.z)) < 1e-4,
                    "Contact should hold the position relative to the station, got " + contact);
            helper.assertTrue(contact.color() == owned.getColor() && contact.label().equals(owned.getLabel()), "Contact should carry the label and color");
            helper.assertTrue(grouped.isAlive(), "Fixture drone should be alive");
        } finally {
            removePlayer(helper, viewer);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void serverOperatorWhoIsNotTheDronesOperatorSeesNothing(GameTestHelper helper) {
        RadarStationBlockEntity station = placeStation(helper, new BlockPos(0, 1, 0));
        UUID ownerId = UUID.randomUUID();
        String tag = UUID.randomUUID().toString().substring(0, 6);
        spawnDrone(helper, 3, 1, 3, data("owned-" + tag).withOwnerId(Optional.of(ownerId)));
        spawnDrone(helper, 4, 1, 3, data("unowned-" + tag));

        ServerPlayer op = spawnPlayer(helper, UUID.randomUUID(), 1, 1, 6, 4);
        ServerPlayer owner = spawnPlayer(helper, ownerId, 2, 1, 6, 0);
        try {
            helper.assertTrue(op.hasPermissions(2), "Fixture: the player should be a server operator");
            helper.assertTrue(contacts(helper, station, op).isEmpty(), "A server operator must not see other players' or unowned drones");
            helper.assertTrue(ids(contacts(helper, station, owner)).equals(Set.of("owned-" + tag)), "The owner sees exactly their drone");
        } finally {
            removePlayer(helper, op);
            removePlayer(helper, owner);
        }
        helper.succeed();
    }

    // --- 4. Radius (own batch: radius 8) ---

    @GameTest(template = "empty", timeoutTicks = 20, batch = RADIUS_BATCH)
    public static void dronesOutsideRadarRadiusAreExcluded(GameTestHelper helper) {
        helper.assertTrue(ServerConfig.get(ServerConfig.RADAR_STATION_RADIUS) == 8, "Fixture: radius should be 8 in this batch");
        RadarStationBlockEntity station = placeStation(helper, new BlockPos(0, 1, 0));
        UUID viewerId = UUID.randomUUID();
        String tag = UUID.randomUUID().toString().substring(0, 6);
        // Distances from the station's center (0.5, 0.5): 5.1, exactly 8.0 and 11.3 blocks (height doesn't count).
        spawnDrone(helper, 4, 1, 4, data("near-" + tag).withOwnerId(Optional.of(viewerId)));
        spawnDrone(helper, 8, 1, 0, data("edge-" + tag).withOwnerId(Optional.of(viewerId)));
        spawnDrone(helper, 8, 1, 8, data("far-" + tag).withOwnerId(Optional.of(viewerId)));

        ServerPlayer viewer = spawnPlayer(helper, viewerId, 1, 1, 6, 0);
        try {
            Set<String> seen = ids(contacts(helper, station, viewer));
            helper.assertTrue(seen.contains("near-" + tag), "A drone inside the radius should be listed, saw " + seen);
            helper.assertTrue(seen.contains("edge-" + tag), "A drone exactly at the radius should be listed, saw " + seen);
            helper.assertTrue(!seen.contains("far-" + tag), "A drone outside the radius must not be listed, saw " + seen);
        } finally {
            removePlayer(helper, viewer);
        }
        helper.succeed();
    }

    // --- 7. Placing and breaking ---

    private static BlockPlaceContext placeContext(GameTestHelper helper, BlockPos supportAbs) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(supportAbs).add(0, 0.5, 0), Direction.UP, supportAbs, false);
        return new BlockPlaceContext(player, InteractionHand.MAIN_HAND, new ItemStack(ModItems.RADAR_STATION.get()), hit);
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void placingTheItemPlacesBothHalves(GameTestHelper helper) {
        helper.setBlock(new BlockPos(4, 0, 4), Blocks.STONE);
        BlockPlaceContext context = placeContext(helper, helper.absolutePos(new BlockPos(4, 0, 4)));
        InteractionResult result = ModItems.RADAR_STATION.get().place(context);
        helper.assertTrue(result.consumesAction(), "Placing with free space above should succeed, got " + result);
        BlockState lower = helper.getBlockState(new BlockPos(4, 1, 4));
        BlockState upper = helper.getBlockState(new BlockPos(4, 2, 4));
        helper.assertTrue(lower.is(ModBlocks.RADAR_STATION.get()) && lower.getValue(RadarStationBlock.HALF) == DoubleBlockHalf.LOWER, "Lower half should be placed");
        helper.assertTrue(upper.is(ModBlocks.RADAR_STATION.get()) && upper.getValue(RadarStationBlock.HALF) == DoubleBlockHalf.UPPER, "Upper half should be placed");
        helper.assertTrue(lower.getValue(RadarStationBlock.FACING) == upper.getValue(RadarStationBlock.FACING), "Both halves should face the same way");
        helper.assertTrue(helper.getLevel().getBlockEntity(helper.absolutePos(new BlockPos(4, 1, 4))) instanceof RadarStationBlockEntity, "Lower half should have the block entity");
        helper.assertTrue(helper.getLevel().getBlockEntity(helper.absolutePos(new BlockPos(4, 2, 4))) == null, "Upper half should have no block entity");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void placingFailsWhenSpaceAboveIsBlocked(GameTestHelper helper) {
        helper.setBlock(new BlockPos(4, 0, 4), Blocks.STONE);
        helper.setBlock(new BlockPos(4, 2, 4), Blocks.STONE);
        InteractionResult result = ModItems.RADAR_STATION.get().place(placeContext(helper, helper.absolutePos(new BlockPos(4, 0, 4))));
        helper.assertTrue(!result.consumesAction(), "Placing under a solid block should fail, got " + result);
        helper.assertTrue(helper.getBlockState(new BlockPos(4, 1, 4)).isAir(), "No lower half should be placed");
        helper.assertTrue(helper.getBlockState(new BlockPos(4, 2, 4)).is(Blocks.STONE), "The blocker should stay");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void placingSucceedsWhenSpaceAboveIsReplaceable(GameTestHelper helper) {
        helper.setBlock(new BlockPos(4, 0, 4), Blocks.STONE);
        helper.setBlock(new BlockPos(4, 2, 4), Blocks.SHORT_GRASS);
        InteractionResult result = ModItems.RADAR_STATION.get().place(placeContext(helper, helper.absolutePos(new BlockPos(4, 0, 4))));
        helper.assertTrue(result.consumesAction(), "Replaceable plants above shouldn't block placing, got " + result);
        helper.assertTrue(helper.getBlockState(new BlockPos(4, 1, 4)).is(ModBlocks.RADAR_STATION.get()),
                "The lower half should stay placed, but it is " + helper.getBlockState(new BlockPos(4, 1, 4)) + " (dropped items: " + droppedStations(helper, new BlockPos(4, 1, 4)) + ")");
        helper.assertTrue(helper.getBlockState(new BlockPos(4, 2, 4)).getValue(RadarStationBlock.HALF) == DoubleBlockHalf.UPPER, "Upper half should replace the plant");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void placingSucceedsWhenSpaceAboveIsWater(GameTestHelper helper) {
        helper.setBlock(new BlockPos(4, 0, 4), Blocks.STONE);
        helper.setBlock(new BlockPos(4, 2, 4), Blocks.WATER);
        InteractionResult result = ModItems.RADAR_STATION.get().place(placeContext(helper, helper.absolutePos(new BlockPos(4, 0, 4))));
        helper.assertTrue(result.consumesAction(), "Water above shouldn't block placing, got " + result);
        helper.assertTrue(helper.getBlockState(new BlockPos(4, 1, 4)).is(ModBlocks.RADAR_STATION.get()), "The lower half should stay placed");
        helper.assertTrue(helper.getBlockState(new BlockPos(4, 2, 4)).getValue(RadarStationBlock.HALF) == DoubleBlockHalf.UPPER, "Upper half should replace the water");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void placingFailsAtTheWorldHeightLimitAndWorksJustBelow(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos column = helper.absolutePos(new BlockPos(4, 0, 4));
        int top = level.getMaxBuildHeight() - 1;
        BlockPos support = new BlockPos(column.getX(), top - 1, column.getZ());
        BlockPos high = support.above();
        BlockPos lowSupport = support.below();
        try {
            level.setBlock(support, Blocks.STONE.defaultBlockState(), 3);
            InteractionResult atLimit = ModItems.RADAR_STATION.get().place(placeContext(helper, support));
            helper.assertTrue(!atLimit.consumesAction(), "Placing at the top block (no room for the upper half) should fail, got " + atLimit);
            helper.assertTrue(level.getBlockState(high).isAir(), "Nothing should be placed at the height limit");

            level.setBlock(lowSupport, Blocks.STONE.defaultBlockState(), 3);
            InteractionResult below = ModItems.RADAR_STATION.get().place(placeContext(helper, lowSupport));
            // The block at 'support' is stone (not replaceable), so the click targets the face of lowSupport: pos 'support' is taken.
            helper.assertTrue(!below.consumesAction(), "Fixture sanity: the click onto an occupied position fails, got " + below);
            level.setBlock(support, Blocks.AIR.defaultBlockState(), 3);
            InteractionResult ok = ModItems.RADAR_STATION.get().place(placeContext(helper, lowSupport));
            helper.assertTrue(ok.consumesAction(), "Placing with the upper half exactly at the last block should succeed, got " + ok);
            helper.assertTrue(level.getBlockState(support).getValue(RadarStationBlock.HALF) == DoubleBlockHalf.LOWER
                    && level.getBlockState(high).getValue(RadarStationBlock.HALF) == DoubleBlockHalf.UPPER, "Both halves should be placed");
        } finally {
            level.setBlock(high, Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(support, Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(lowSupport, Blocks.AIR.defaultBlockState(), 3);
        }
        helper.succeed();
    }

    /** Breaks one half as the given player and returns nothing: the caller checks the world and the drops. */
    private static void survivalBreak(GameTestHelper helper, boolean upperHalf) {
        BlockPos rel = new BlockPos(6, 1, 6);
        placeStation(helper, rel);
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 1, 1, 1, 0);
        try {
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_PICKAXE));
            helper.assertTrue(player.gameMode.destroyBlock(helper.absolutePos(upperHalf ? rel.above() : rel)), "destroyBlock should go through");
            helper.assertTrue(helper.getBlockState(rel).isAir(), "Lower half should be gone");
            helper.assertTrue(helper.getBlockState(rel.above()).isAir(), "Upper half should be gone");
            helper.assertTrue(droppedStations(helper, rel) == 1, "Exactly one radar station item should drop, found " + droppedStations(helper, rel));
        } finally {
            removePlayer(helper, player);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void breakingLowerHalfInSurvivalRemovesBothAndDropsOneItem(GameTestHelper helper) {
        survivalBreak(helper, false);
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void breakingUpperHalfInSurvivalRemovesBothAndDropsOneItem(GameTestHelper helper) {
        survivalBreak(helper, true);
    }

    @SuppressWarnings("deprecation")
    private static void creativeBreak(GameTestHelper helper, boolean upperHalf) {
        BlockPos rel = new BlockPos(6, 1, 6);
        placeStation(helper, rel);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        try {
            player.setGameMode(GameType.CREATIVE);
            player.moveTo(helper.absoluteVec(new Vec3(1, 1, 1)));
            helper.assertTrue(player.gameMode.destroyBlock(helper.absolutePos(upperHalf ? rel.above() : rel)), "destroyBlock should go through");
            helper.assertTrue(helper.getBlockState(rel).isAir(), "Lower half should be gone");
            helper.assertTrue(helper.getBlockState(rel.above()).isAir(), "Upper half should be gone");
            helper.assertTrue(droppedStations(helper, rel) == 0, "Creative breaking drops nothing, found " + droppedStations(helper, rel));
        } finally {
            removePlayer(helper, player);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void breakingLowerHalfInCreativeRemovesBothAndDropsNothing(GameTestHelper helper) {
        creativeBreak(helper, false);
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void breakingUpperHalfInCreativeRemovesBothAndDropsNothing(GameTestHelper helper) {
        creativeBreak(helper, true);
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void rightClickingUpperHalfOpensTheLowerHalfsMenu(GameTestHelper helper) {
        BlockPos rel = new BlockPos(4, 1, 4);
        placeStation(helper, rel);
        ServerPlayer player = spawnPlayer(helper, UUID.randomUUID(), 4, 1, 2, 0);
        try {
            BlockPos upperAbs = helper.absolutePos(rel.above());
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(upperAbs), Direction.NORTH, upperAbs, false);
            // The fake connection of a test player can't take NeoForge's open-screen payload, so opening throws after
            // the menu was created; the lower half's block entity registering the viewer shows which half's menu it was.
            try {
                helper.getBlockState(rel.above()).useWithoutItem(helper.getLevel(), player, hit);
            } catch (RuntimeException expected) {
                helper.assertTrue(String.valueOf(expected.getMessage()).contains("advanced_open_screen"), "Unexpected failure: " + expected);
            }
            RadarStationBlockEntity station = (RadarStationBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(rel));
            helper.assertTrue(viewers(station).containsKey(player), "Using the upper half should open the lower half's menu (the station should have the viewer)");
        } finally {
            removePlayer(helper, player);
        }
        helper.succeed();
    }
}
