package com.elpinho.seekerdrones.station;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * All Charging Stations in one dimension, with their placers (DESIGN.md section 8.3). Drones search this instead of
 * scanning blocks. Stations register on placement and unregister when removed.
 */
public class ChargingStationRegistry extends SavedData {
    private static final String DATA_NAME = "seekerdrones_charging_stations";
    private static final String TAG_STATIONS = "stations";

    private record Entry(BlockPos pos, Optional<UUID> owner) {
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                BlockPos.CODEC.fieldOf("pos").forGetter(Entry::pos),
                UUIDUtil.CODEC.optionalFieldOf("owner").forGetter(Entry::owner)
        ).apply(instance, Entry::new));
    }

    private static final Codec<List<Entry>> ENTRIES_CODEC = Entry.CODEC.listOf();

    private static final SavedData.Factory<ChargingStationRegistry> FACTORY =
            new SavedData.Factory<>(ChargingStationRegistry::new, ChargingStationRegistry::load, null);

    /** Station position to its placer, empty if placed by a non-player. */
    private final Map<BlockPos, Optional<UUID>> stations = new HashMap<>();

    public static ChargingStationRegistry get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    private static ChargingStationRegistry load(CompoundTag tag, HolderLookup.Provider registries) {
        ChargingStationRegistry data = new ChargingStationRegistry();
        if (tag.contains(TAG_STATIONS)) {
            ENTRIES_CODEC.parse(NbtOps.INSTANCE, tag.get(TAG_STATIONS))
                    .resultOrPartial()
                    .ifPresent(entries -> entries.forEach(entry -> data.stations.put(entry.pos(), entry.owner())));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        List<Entry> entries = stations.entrySet().stream().map(e -> new Entry(e.getKey(), e.getValue())).toList();
        ENTRIES_CODEC.encodeStart(NbtOps.INSTANCE, entries).ifSuccess(nbt -> tag.put(TAG_STATIONS, nbt));
        return tag;
    }

    public void add(BlockPos pos, Optional<UUID> owner) {
        if (!owner.equals(stations.put(pos.immutable(), owner))) {
            setDirty();
        }
    }

    public void remove(BlockPos pos) {
        if (stations.remove(pos) != null) {
            setDirty();
        }
    }

    public Map<BlockPos, Optional<UUID>> getStations() {
        return Collections.unmodifiableMap(stations);
    }
}
