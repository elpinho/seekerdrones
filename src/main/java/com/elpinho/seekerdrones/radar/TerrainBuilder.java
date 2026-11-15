package com.elpinho.seekerdrones.radar;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.annotation.Nullable;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Builds a radar machine's terrain map a few chunks per tick (DESIGN.md sections 7.8 and 7.9): the Radar Station's
 * phosphor height map ({@link #heights}) or the Uplink's map colors ({@link #colors}). Loaded chunks are read on the
 * main thread. Chunks that aren't loaded are read from their saved data on a background thread, without loading them
 * into the world, so the whole area shows even far from players. Chunks that were never generated stay unknown.
 */
public final class TerrainBuilder<M extends RadarMap> {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final int radius;
    private final BlockPos center;
    private final boolean ceiling;
    private final TerrainCanvas<M> canvas;
    /** Chunks still to read, nearest to the station first so the middle of the map fills in first. */
    private final Deque<ChunkPos> todo = new ArrayDeque<>();
    /** Chunks read from saved data, handed back to the main thread. Empty results are chunks with no data. */
    private final Queue<Optional<ChunkSurface>> finished = new ConcurrentLinkedQueue<>();
    private int pending;

    /** The Radar Station's height map, relative to {@code center}'s Y. */
    public static TerrainBuilder<TerrainMap> heights(ServerLevel level, BlockPos center, int radius) {
        return new TerrainBuilder<>(level, center, radius, new HeightCanvas(radius, center.getY()));
    }

    /** The Uplink's map colors. */
    public static TerrainBuilder<ColorMap> colors(ServerLevel level, BlockPos center, int radius) {
        return new TerrainBuilder<>(level, center, radius, new ColorCanvas(radius, center.getX() - radius, center.getZ() - radius));
    }

    private TerrainBuilder(ServerLevel level, BlockPos center, int radius, TerrainCanvas<M> canvas) {
        this.radius = radius;
        this.center = center;
        this.ceiling = level.dimensionType().hasCeiling();
        this.canvas = canvas;
        ChunkPos min = new ChunkPos(new BlockPos(center.getX() - radius, 0, center.getZ() - radius));
        ChunkPos max = new ChunkPos(new BlockPos(center.getX() + radius, 0, center.getZ() + radius));
        ChunkPos middle = new ChunkPos(center);
        List<ChunkPos> chunks = new ArrayList<>();
        for (int x = min.x; x <= max.x; x++) {
            for (int z = min.z; z <= max.z; z++) {
                chunks.add(new ChunkPos(x, z));
            }
        }
        chunks.sort(Comparator.comparingInt(chunk -> chunk.distanceSquared(middle)));
        todo.addAll(chunks);
    }

    /** Reads up to {@code chunksPerTick} more chunks. Returns the map once every chunk is in, otherwise null. */
    @Nullable
    public M tick(ServerLevel level, int chunksPerTick) {
        Optional<ChunkSurface> done;
        while ((done = finished.poll()) != null) {
            pending--;
            done.ifPresent(this::apply);
        }
        for (int i = 0; i < chunksPerTick && !todo.isEmpty(); i++) {
            ChunkPos pos = todo.poll();
            LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
            if (chunk != null) {
                apply(ChunkSurface.of(chunk, ceiling, center.getY(), canvas.colors()));
            } else {
                readSaved(level, pos);
            }
        }
        return todo.isEmpty() && pending == 0 ? canvas.finish() : null;
    }

    /** Reads the chunk's saved data (or an unsaved write still queued) and samples it off the main thread. */
    private void readSaved(ServerLevel level, ChunkPos pos) {
        pending++;
        int stationY = center.getY();
        boolean colors = canvas.colors();
        level.getChunkSource().chunkMap.read(pos)
                .thenApplyAsync(tag -> tag.map(data -> ChunkSurface.of(pos, data, level, ceiling, stationY, colors)), Util.backgroundExecutor())
                .whenComplete((surface, error) -> {
                    if (error != null) {
                        LOGGER.debug("Radar map couldn't read chunk {}", pos, error);
                    }
                    finished.add(error != null || surface == null ? Optional.empty() : surface);
                });
    }

    /** Copies the chunk's columns that fall inside the map. */
    private void apply(@Nullable ChunkSurface surface) {
        if (surface == null) {
            return;
        }
        int size = RadarMap.size(radius);
        int originX = center.getX() - radius;
        int originZ = center.getZ() - radius;
        for (int localZ = 0; localZ < 16; localZ++) {
            int row = surface.pos().getMinBlockZ() + localZ - originZ;
            if (row < 0 || row >= size) {
                continue;
            }
            for (int localX = 0; localX < 16; localX++) {
                int column = surface.pos().getMinBlockX() + localX - originX;
                int top = surface.top(localX, localZ);
                if (column < 0 || column >= size || top == ChunkSurface.NONE) {
                    continue;
                }
                canvas.set(row * size + column, surface, localX, localZ);
            }
        }
    }
}
