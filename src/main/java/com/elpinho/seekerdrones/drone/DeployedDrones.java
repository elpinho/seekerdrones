package com.elpinho.seekerdrones.drone;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.elpinho.seekerdrones.config.ServerConfig;
import com.elpinho.seekerdrones.operator.OperatorGroup;
import com.elpinho.seekerdrones.operator.OperatorGroups;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Every deployed drone that counts against a player, for {@code drone.maxDeployedPerPlayer} (DESIGN.md sections 2.2
 * and 8.3). Stored once per world on the overworld, so drones in unloaded chunks and other dimensions still count. A
 * drone is added when it joins a level (deploying, or its chunk loading) and removed when it leaves the world for good
 * (picked up, destroyed, exploded or dropped as an item); unloading or changing dimension keeps it.
 */
public class DeployedDrones extends SavedData {
    private static final String DATA_NAME = "seekerdrones_deployed_drones";
    private static final String TAG_DRONES = "drones";

    private record Entry(String droneId, UUID player) {
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("drone").forGetter(Entry::droneId),
                UUIDUtil.CODEC.fieldOf("player").forGetter(Entry::player)
        ).apply(instance, Entry::new));
    }

    private static final Codec<List<Entry>> ENTRIES_CODEC = Entry.CODEC.listOf();

    private static final SavedData.Factory<DeployedDrones> FACTORY = new SavedData.Factory<>(DeployedDrones::new, DeployedDrones::load, null);

    /** Drone ID to the player it counts against. */
    private final Map<String, UUID> drones = new HashMap<>();
    /** How many drones count against each player, kept in step with {@link #drones}. */
    private final Map<UUID, Integer> counts = new HashMap<>();

    public static DeployedDrones get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    private static DeployedDrones load(CompoundTag tag, HolderLookup.Provider registries) {
        DeployedDrones data = new DeployedDrones();
        if (tag.contains(TAG_DRONES)) {
            ENTRIES_CODEC.parse(NbtOps.INSTANCE, tag.get(TAG_DRONES))
                    .resultOrPartial()
                    .ifPresent(entries -> entries.forEach(entry -> data.put(entry.droneId(), entry.player())));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        List<Entry> entries = drones.entrySet().stream().map(e -> new Entry(e.getKey(), e.getValue())).toList();
        ENTRIES_CODEC.encodeStart(NbtOps.INSTANCE, entries).ifSuccess(nbt -> tag.put(TAG_DRONES, nbt));
        return tag;
    }

    /**
     * The player a drone counts against: its group's owner if it has a group, otherwise its owner. Empty for unowned
     * drones and drones whose group doesn't exist.
     */
    public static Optional<UUID> countedPlayer(MinecraftServer server, DroneData data) {
        if (data.groupId().isPresent()) {
            return OperatorGroups.get(server).getGroup(data.groupId().get()).map(OperatorGroup::owner);
        }
        return data.ownerId();
    }

    /** The configured limit, or 0 for none. */
    public static int limit() {
        return ServerConfig.get(ServerConfig.DRONE_MAX_DEPLOYED_PER_PLAYER);
    }

    /**
     * Whether deploying this drone would go over its player's limit. A drone already counted against the same player
     * (e.g. a stale entry) doesn't count twice.
     */
    public boolean wouldExceedLimit(MinecraftServer server, DroneData data) {
        int limit = limit();
        if (limit <= 0) {
            return false;
        }
        Optional<UUID> player = countedPlayer(server, data);
        if (player.isEmpty()) {
            return false;
        }
        int count = count(player.get());
        if (player.get().equals(drones.get(data.droneId()))) {
            count--;
        }
        return count >= limit;
    }

    public int count(UUID player) {
        return counts.getOrDefault(player, 0);
    }

    /** The IDs of the drones counted against the player. */
    public List<String> dronesOf(UUID player) {
        return drones.entrySet().stream().filter(e -> e.getValue().equals(player)).map(Map.Entry::getKey).sorted().toList();
    }

    /** Records a deployed drone against the player it counts against now, or forgets it if that's nobody. */
    public void update(MinecraftServer server, DroneData data) {
        if (data.droneId().isEmpty()) {
            return;
        }
        Optional<UUID> player = countedPlayer(server, data);
        if (player.isEmpty()) {
            remove(data.droneId());
        } else if (!player.get().equals(drones.get(data.droneId()))) {
            put(data.droneId(), player.get());
            setDirty();
        }
    }

    /** Forgets a drone that left the world. Returns false if it wasn't counted. */
    public boolean remove(String droneId) {
        UUID player = drones.remove(droneId);
        if (player == null) {
            return false;
        }
        decrement(player);
        setDirty();
        return true;
    }

    private void put(String droneId, UUID player) {
        UUID previous = drones.put(droneId, player);
        if (previous != null) {
            decrement(previous);
        }
        counts.merge(player, 1, Integer::sum);
    }

    private void decrement(UUID player) {
        counts.computeIfPresent(player, (key, count) -> count > 1 ? count - 1 : null);
    }
}
