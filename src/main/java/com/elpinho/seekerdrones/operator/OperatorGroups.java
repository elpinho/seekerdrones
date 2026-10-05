package com.elpinho.seekerdrones.operator;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * All Operator Groups, stored once per world on the overworld (DESIGN.md section 8.3). Groups are never deleted in v1.
 */
public class OperatorGroups extends SavedData {
    private static final String DATA_NAME = "seekerdrones_operator_groups";
    private static final String TAG_GROUPS = "groups";

    private record Entry(UUID id, OperatorGroup group) {
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                UUIDUtil.CODEC.fieldOf("id").forGetter(Entry::id),
                OperatorGroup.MAP_CODEC.forGetter(Entry::group)
        ).apply(instance, Entry::new));
    }

    private static final Codec<List<Entry>> ENTRIES_CODEC = Entry.CODEC.listOf();

    private static final SavedData.Factory<OperatorGroups> FACTORY = new SavedData.Factory<>(OperatorGroups::new, OperatorGroups::load, null);

    private final Map<UUID, OperatorGroup> groups = new LinkedHashMap<>();

    public static OperatorGroups get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    private static OperatorGroups load(CompoundTag tag, HolderLookup.Provider registries) {
        OperatorGroups data = new OperatorGroups();
        if (!tag.contains(TAG_GROUPS)) {
            return data;
        }
        ENTRIES_CODEC.parse(NbtOps.INSTANCE, tag.get(TAG_GROUPS))
                .resultOrPartial()
                .ifPresent(entries -> entries.forEach(entry -> data.groups.put(entry.id(), entry.group())));
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        List<Entry> entries = groups.entrySet().stream().map(e -> new Entry(e.getKey(), e.getValue())).toList();
        ENTRIES_CODEC.encodeStart(NbtOps.INSTANCE, entries).ifSuccess(nbt -> tag.put(TAG_GROUPS, nbt));
        return tag;
    }

    /** Creates a new group owned by {@code owner} and returns its ID. */
    public UUID createGroup(UUID owner) {
        UUID id;
        do {
            id = UUID.randomUUID();
        } while (groups.containsKey(id));
        groups.put(id, new OperatorGroup(owner, Set.of()));
        setDirty();
        return id;
    }

    public Optional<OperatorGroup> getGroup(UUID groupId) {
        return Optional.ofNullable(groups.get(groupId));
    }

    public Map<UUID, OperatorGroup> getGroups() {
        return Collections.unmodifiableMap(groups);
    }

    /** Adds an operator. Returns false if the group doesn't exist or the player is already an operator (or the owner). */
    public boolean addOperator(UUID groupId, UUID player) {
        OperatorGroup group = groups.get(groupId);
        if (group == null || group.isOperator(player)) {
            return false;
        }
        Set<UUID> operators = new HashSet<>(group.operators());
        operators.add(player);
        groups.put(groupId, new OperatorGroup(group.owner(), operators));
        setDirty();
        return true;
    }

    /** Removes an operator. Returns false if the group doesn't exist or the player isn't a removable operator. The owner can't be removed. */
    public boolean removeOperator(UUID groupId, UUID player) {
        OperatorGroup group = groups.get(groupId);
        if (group == null || !group.operators().contains(player)) {
            return false;
        }
        Set<UUID> operators = new HashSet<>(group.operators());
        operators.remove(player);
        groups.put(groupId, new OperatorGroup(group.owner(), operators));
        setDirty();
        return true;
    }

    /** Whether the player is the owner or an operator of the group. False for unknown groups. */
    public boolean isOperator(UUID groupId, UUID player) {
        OperatorGroup group = groups.get(groupId);
        return group != null && group.isOperator(player);
    }

    /**
     * Whether the player is the owner or an operator of any group owned by {@code owner}, e.g. to manage that owner's
     * Charging Stations (DESIGN.md section 7.4). Walks every group, so it's only for player actions, never per tick.
     */
    public boolean isOperatorOfAnyGroupOwnedBy(UUID owner, UUID player) {
        return groups.values().stream().anyMatch(group -> group.owner().equals(owner) && group.isOperator(player));
    }

    /** All online players who are operators of the group, e.g. for Transmitter notifications (section 4). */
    public List<ServerPlayer> onlineOperators(MinecraftServer server, UUID groupId) {
        OperatorGroup group = groups.get(groupId);
        if (group == null) {
            return List.of();
        }
        return server.getPlayerList().getPlayers().stream().filter(p -> group.isOperator(p.getUUID())).toList();
    }
}
