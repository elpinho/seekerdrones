package com.elpinho.seekerdrones.gametest;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import javax.annotation.Nullable;

import com.elpinho.seekerdrones.deploying.DeployingStationBlockEntity;
import com.elpinho.seekerdrones.drone.DroneData;
import com.elpinho.seekerdrones.drone.DroneEntity;
import com.elpinho.seekerdrones.programming.ProgrammingStationBlockEntity;
import com.elpinho.seekerdrones.registry.ModBlocks;
import com.elpinho.seekerdrones.registry.ModEntityTypes;
import com.elpinho.seekerdrones.station.ChargingStationBlockEntity;
import com.mojang.authlib.GameProfile;

import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
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
     * Polls {@code condition} every tick for up to {@code ticks} ticks, running {@code onReady} as soon as it's true,
     * or {@code onTimeout} if it never becomes true in time. Exactly one of them runs: the GameTest tick queue can run
     * a poll chain twice when callbacks register more handlers, so the chain latches once it's done. Each step
     * reschedules with a fresh lambda: {@code GameTestInfo} keys {@code runAtTickTime} by the {@code Runnable}'s
     * identity, so re-registering the same instance from inside its own call gets cancelled.
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
}
