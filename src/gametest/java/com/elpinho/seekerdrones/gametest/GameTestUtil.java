package com.elpinho.seekerdrones.gametest;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import javax.annotation.Nullable;

import com.elpinho.seekerdrones.deploying.DeployingStationBlockEntity;
import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.drone.UpgradeType;
import com.elpinho.seekerdrones.programming.ProgrammingStationBlockEntity;
import com.elpinho.seekerdrones.radar.RemoteStationBlockEntity;
import com.elpinho.seekerdrones.radar.TwoBlockMachineBlock;
import com.elpinho.seekerdrones.registry.ModBlocks;
import com.elpinho.seekerdrones.registry.ModEntityTypes;
import com.elpinho.seekerdrones.station.ChargingStationBlockEntity;
import com.mojang.authlib.GameProfile;

import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Helpers shared by the GameTest classes. Positions are relative to the test structure unless a name says otherwise. */
public final class GameTestUtil {
    /** Where {@link #runCommand} runs its commands from: the middle of a 9x9 test structure, one block up. */
    private static final Vec3 COMMAND_POS = new Vec3(4, 3, 4);

    private GameTestUtil() {
    }

    public static MinecraftServer server(GameTestHelper helper) {
        return helper.getLevel().getServer();
    }

    /** Runs a command at permission level 4 and returns whether it succeeded. */
    public static boolean runCommand(GameTestHelper helper, String command) {
        return runCommand(helper, command, 4);
    }

    /** Runs a command at the given permission level and returns whether it succeeded. */
    public static boolean runCommand(GameTestHelper helper, String command, int permission) {
        MinecraftServer server = server(helper);
        AtomicBoolean success = new AtomicBoolean(false);
        CommandSourceStack source = server.createCommandSourceStack()
                .withLevel(helper.getLevel())
                .withPosition(helper.absoluteVec(COMMAND_POS))
                .withPermission(permission)
                .withCallback((ok, result) -> success.set(ok));
        server.getCommands().performPrefixedCommand(source, command);
        return success.get();
    }

    /**
     * Runs {@code task} once per tick until the test finishes. Use this instead of {@code GameTestHelper.onEachTick},
     * which isn't safe from inside a callback.
     *
     * <p>Why: {@code GameTestInfo} keeps its {@code runAfterDelay}/{@code runAtTickTime} callbacks in a fastutil
     * {@code Object2LongOpenHashMap} and iterates it every tick while running them. Vanilla {@code onEachTick} (and
     * {@code failIfEver}) adds one entry per remaining tick at once. Called from inside a callback, that grows the map
     * past its fill limit mid-iteration, the map rehashes, and the live iterator is left pointing into the old layout:
     * it skips or repeats callbacks, deletes the wrong entry, or runs off its array and throws a
     * {@code NullPointerException} ({@code "this.wrapped" is null}) that crashes the whole GameTest server. Callbacks
     * hash by identity, so whether it crashes changes from run to run. This keeps exactly one pending entry and
     * reschedules it each tick, which never triggers a rehash. A callback adding one entry is safe: the new entry is
     * either picked up in a later tick or, if the iterator reaches it, not yet due.
     */
    public static void eachTick(GameTestHelper helper, Runnable task) {
        helper.runAtTickTime(helper.getTick(), () -> eachTickStep(helper, task));
    }

    private static void eachTickStep(GameTestHelper helper, Runnable task) {
        task.run();
        // A fresh lambda each step: re-registering the running instance would be removed right after it returns.
        helper.runAtTickTime(helper.getTick() + 1, () -> eachTickStep(helper, task));
    }

    /**
     * Polls {@code condition} every tick for up to {@code ticks} ticks, running {@code onReady} as soon as it's true,
     * or {@code onTimeout} if it never becomes true in time. Exactly one of them runs; the latch is a safety net. Each
     * step reschedules with a fresh lambda: {@code GameTestInfo} keys {@code runAtTickTime} by the {@code Runnable}'s
     * identity, so re-registering the same instance from inside its own call gets cancelled. It keeps a single pending
     * entry, so it's safe from inside other callbacks (see {@link #eachTick}).
     */
    public static void pollUntil(GameTestHelper helper, BooleanSupplier condition, int ticks, Runnable onReady, Runnable onTimeout) {
        pollStep(helper, condition, ticks, onReady, onTimeout, new AtomicBoolean());
    }

    private static void pollStep(GameTestHelper helper, BooleanSupplier condition, int ticks, Runnable onReady, Runnable onTimeout,
            AtomicBoolean done) {
        if (done.get()) {
            return;
        }
        if (condition.getAsBoolean()) {
            done.set(true);
            onReady.run();
            return;
        }
        if (ticks <= 1) {
            done.set(true);
            onTimeout.run();
            return;
        }
        helper.runAtTickTime(helper.getTick() + 1, () -> pollStep(helper, condition, ticks - 1, onReady, onTimeout, done));
    }

    /** Places {@code block} and returns its block entity. */
    @SuppressWarnings("unchecked")
    public static <T extends BlockEntity> T place(GameTestHelper helper, BlockPos rel, Block block) {
        helper.setBlock(rel, block);
        return (T) helper.getLevel().getBlockEntity(helper.absolutePos(rel));
    }

    /** Places a Charging Station with no recorded placer (as if placed by a non-player, e.g. automation). */
    public static ChargingStationBlockEntity placeChargingStation(GameTestHelper helper, BlockPos rel) {
        return place(helper, rel, ModBlocks.CHARGING_STATION.get());
    }

    /**
     * Places both halves of a two-block radar machine (Radar Station or Satellite Uplink) the way a player placing it
     * would, and returns the lower half's block entity.
     */
    @SuppressWarnings("unchecked")
    public static <T extends BlockEntity> T placeTwoBlock(GameTestHelper helper, BlockPos rel, TwoBlockMachineBlock block) {
        BlockState lower = block.defaultBlockState();
        helper.setBlock(rel, lower);
        helper.setBlock(rel.above(), lower.setValue(TwoBlockMachineBlock.HALF, DoubleBlockHalf.UPPER));
        return (T) helper.getLevel().getBlockEntity(helper.absolutePos(rel));
    }

    public static ProgrammingStationBlockEntity placeProgrammingStation(GameTestHelper helper, BlockPos rel) {
        return place(helper, rel, ModBlocks.PROGRAMMING_STATION.get());
    }

    public static DeployingStationBlockEntity placeDeployingStation(GameTestHelper helper, BlockPos rel) {
        return place(helper, rel, ModBlocks.DEPLOYING_STATION.get());
    }

    /**
     * Spawns a drone standing on the block at {@code (x, y, z)}. The data goes in before the drone is added, so it is
     * indexed by ID (see {@code DroneIndex.add} in {@code onAddedToLevel}).
     */
    public static DroneEntity spawnDrone(GameTestHelper helper, int x, int y, int z, DroneData data) {
        return spawnDrone(helper, new BlockPos(x, y, z), data);
    }

    public static DroneEntity spawnDrone(GameTestHelper helper, BlockPos rel, DroneData data) {
        DroneEntity drone = ModEntityTypes.DRONE.get().create(helper.getLevel());
        drone.setPersistenceRequired();
        Vec3 pos = helper.absoluteVec(Vec3.atBottomCenterOf(rel));
        drone.moveTo(pos.x, pos.y, pos.z, 0, 0);
        drone.setDroneData(data);
        helper.getLevel().addFreshEntity(drone);
        return drone;
    }

    /** A stationary bait zombie: no AI, no gravity, invulnerable (so daylight or stray hits don't remove it). */
    public static Zombie spawnStationaryZombie(GameTestHelper helper, int x, int y, int z) {
        Zombie zombie = helper.spawn(EntityType.ZOMBIE, new BlockPos(x, y, z));
        zombie.setNoAi(true);
        zombie.setNoGravity(true);
        zombie.setInvulnerable(true);
        return zombie;
    }

    public static ServerPlayer spawnPlayer(GameTestHelper helper, UUID uuid, double x, double y, double z, int permissionLevel) {
        return spawnPlayer(helper, uuid, x, y, z, permissionLevel, 0);
    }

    /**
     * A real survival {@link ServerPlayer} at a fixed UUID and permission level, in the player list (so
     * {@code getPlayer(uuid)} finds it), at the given spot facing +Z with pitch {@code xRot}. Remove it with
     * {@link #removePlayer} when the test ends.
     */
    public static ServerPlayer spawnPlayer(GameTestHelper helper, UUID uuid, double x, double y, double z, int permissionLevel, float xRot) {
        MinecraftServer server = server(helper);
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(uuid, "test-" + uuid.toString().substring(0, 8)), false);
        ServerPlayer player = new ServerPlayer(server, helper.getLevel(), cookie.gameProfile(), cookie.clientInformation()) {
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
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        Vec3 pos = helper.absoluteVec(new Vec3(x, y, z));
        player.moveTo(pos.x, pos.y, pos.z, 0.0F, xRot);
        player.setNoGravity(true);
        // The GameTest server's default game mode is creative; tests that don't care use survival.
        player.setGameMode(GameType.SURVIVAL);
        // Settle the chunk tracking on the new position, like the server does when a player moves between sections.
        helper.getLevel().getChunkSource().move(player);
        return player;
    }

    public static void removePlayer(GameTestHelper helper, @Nullable ServerPlayer player) {
        if (player != null) {
            server(helper).getPlayerList().remove(player);
        }
    }

    /**
     * A mock player (not a {@link ServerPlayer}, and not in the level) at a fixed UUID and permission level,
     * positioned in the test structure. {@code GameTestHelper.makeMockPlayer} is always permission level 0.
     */
    public static Player mockPlayer(GameTestHelper helper, UUID uuid, double x, double y, double z, boolean sneaking, int permissionLevel) {
        Player player = new Player(helper.getLevel(), BlockPos.ZERO, 0.0F, new GameProfile(uuid, "mock-" + uuid.toString().substring(0, 8))) {
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

    /** The first dropped stack of {@code item} within 3 blocks of the absolute position {@code pos}, or null. */
    @Nullable
    public static ItemStack findDropped(GameTestHelper helper, Vec3 pos, Item item) {
        for (ItemEntity entity : helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(pos, pos).inflate(3.0))) {
            if (entity.getItem().is(item)) {
                return entity.getItem();
            }
        }
        return null;
    }

    /** Drones within 2.5 blocks of the block at the absolute position {@code abs} (e.g. a station's launch spot). */
    public static List<DroneEntity> dronesNear(GameTestHelper helper, BlockPos abs) {
        return helper.getLevel().getEntitiesOfClass(DroneEntity.class, new AABB(abs).inflate(2.5));
    }

    /** Drones within {@code radius} blocks of the absolute position {@code center}. */
    public static List<DroneEntity> dronesNear(GameTestHelper helper, Vec3 center, double radius) {
        return helper.getLevel().getEntitiesOfClass(DroneEntity.class, new AABB(center, center).inflate(radius));
    }

    /**
     * Removes the barrier box the GameTest framework builds around a structure (a ring one block outside the 9 x 6 x 9
     * "empty" template, walls and a roof one block above it), so drones can fly out of it and the world surface above a
     * column is the column's own top. Tests that fly drones to spots above or outside the structure need it.
     */
    public static void clearTestBarriers(GameTestHelper helper) {
        for (int x = -1; x <= 9; x++) {
            for (int y = 0; y <= 7; y++) {
                for (int z = -1; z <= 9; z++) {
                    BlockPos rel = new BlockPos(x, y, z);
                    if (helper.getBlockState(rel).is(Blocks.BARRIER)) {
                        helper.setBlock(rel, Blocks.AIR);
                    }
                }
            }
        }
    }

    // --- Drone data fixtures ---

    /** A fresh drone ID that no other test uses. */
    public static String newDroneId() {
        return "T" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    /** A short random tag for telling this test's drones apart from other tests' in a shared snapshot. */
    public static String randomTag() {
        return UUID.randomUUID().toString().substring(0, 6);
    }

    /** Plain drone data with this ID: no owner, no group, no upgrades. */
    public static DroneData droneData(String id) {
        return DroneData.createNew().withDroneId(id);
    }

    /** Drone data owned by a player nobody in the test is. */
    public static DroneData foreignDroneData(String id) {
        return droneData(id).withOwnerId(Optional.of(UUID.randomUUID()));
    }

    /** Drone data with an FPV Camera. */
    public static DroneData cameraDroneData(String id) {
        return droneData(id).withUpgradeCount(UpgradeType.FPV_CAMERA, 1);
    }

    /**
     * Makes the drone track {@code target} without running its AI (a chase or follow would need line of sight and ticks),
     * so snapshots that carry the tracked entity can be checked.
     */
    public static void setSeekTarget(DroneEntity drone, Entity target) {
        try {
            Field field = DroneEntity.class.getDeclaredField("target");
            field.setAccessible(true);
            field.set(drone, target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Saves the drone to NBT and loads it into a new entity in the level, as a chunk reload would. {@code saveWithoutId}
     * still writes the UUID, so the original is discarded before the copy is added.
     */
    public static DroneEntity reloadDrone(GameTestHelper helper, DroneEntity original) {
        CompoundTag tag = original.saveWithoutId(new CompoundTag());
        original.discard();
        DroneEntity loaded = ModEntityTypes.DRONE.get().create(helper.getLevel());
        loaded.load(tag);
        helper.getLevel().addFreshEntity(loaded);
        return loaded;
    }

    // --- Intruder alarm (Satellite Uplink and Command Center) ---

    /** The comparator output of the block at {@code rel}. */
    public static int signal(GameTestHelper helper, BlockPos rel) {
        return helper.getBlockState(rel).getAnalogOutputSignal(helper.getLevel(), helper.absolutePos(rel));
    }

    /** Asserts the station's alarm and the comparator output of both of its halves (the lower one at {@code rel}). */
    public static void assertAlarm(GameTestHelper helper, RemoteStationBlockEntity<?> station, BlockPos rel, int expected) {
        helper.assertTrue(station.getAlarm() == expected, "Alarm should be " + expected + ", is " + station.getAlarm());
        helper.assertTrue(signal(helper, rel) == expected, "Lower half's comparator output should be " + expected + ", is " + signal(helper, rel));
        helper.assertTrue(signal(helper, rel.above()) == expected, "Upper half's comparator output should be " + expected + ", is " + signal(helper, rel.above()));
    }
}
