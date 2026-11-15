package com.elpinho.seekerdrones.radar;

import javax.annotation.Nullable;

import com.mojang.serialization.Codec;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Reads the surface of one chunk for the radar map (DESIGN.md section 7.8): for each of its 16 × 16 columns, the Y of
 * the top block and whether it's water. Works on a loaded chunk (main thread) or on a chunk's saved data (any thread),
 * so the map covers unloaded chunks without loading them.
 *
 * <p>Where the dimension has a ceiling, the top is the highest non-air block at or below {@code scanFromY} (the
 * station's Y), so the Nether shows its floor instead of the bedrock roof. Elsewhere it's the world surface.
 */
public final class ChunkSurface {
    /** The Y of a column with no blocks at all. */
    public static final int NONE = Integer.MIN_VALUE;

    /** The same codec vanilla uses for a saved section's block states. */
    private static final Codec<PalettedContainer<BlockState>> BLOCK_STATES = PalettedContainer.codecRW(
            Block.BLOCK_STATE_REGISTRY, BlockState.CODEC, PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());

    private static final String TAG_SECTIONS = "sections";
    private static final String TAG_SECTION_Y = "Y";
    private static final String TAG_BLOCK_STATES = "block_states";
    private static final String TAG_HEIGHTMAPS = "Heightmaps";

    private final ChunkPos pos;
    private final int[] tops = new int[256];
    private final boolean[] water = new boolean[256];

    private ChunkSurface(ChunkPos pos) {
        this.pos = pos;
    }

    public ChunkPos pos() {
        return pos;
    }

    /** The world Y of the column's top block, or {@link #NONE}. Local coordinates, 0 to 15. */
    public int top(int localX, int localZ) {
        return tops[localZ * 16 + localX];
    }

    public boolean isWater(int localX, int localZ) {
        return water[localZ * 16 + localX];
    }

    /** Reads a loaded chunk. Main thread only. */
    public static ChunkSurface of(LevelChunk chunk, boolean ceiling, int scanFromY) {
        ChunkSurface surface = new ChunkSurface(chunk.getPos());
        LevelChunkSection[] sections = chunk.getSections();
        Blocks3 blocks = (x, y, z) -> {
            int index = chunk.getSectionIndex(y);
            return index < 0 || index >= sections.length || sections[index].hasOnlyAir()
                    ? null : sections[index].getBlockState(x, y & 15, z);
        };
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                surface.sample(blocks, chunk, x, z, ceiling ? scanFromY : chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z));
            }
        }
        return surface;
    }

    /**
     * Reads a chunk's saved data, without loading it. Safe off the main thread. Returns null if the data has no
     * sections (a chunk that barely started generating).
     */
    @Nullable
    public static ChunkSurface of(ChunkPos pos, CompoundTag tag, LevelHeightAccessor height, boolean ceiling, int scanFromY) {
        ListTag sectionTags = tag.getList(TAG_SECTIONS, Tag.TAG_COMPOUND);
        if (sectionTags.isEmpty()) {
            return null;
        }
        @SuppressWarnings("unchecked")
        PalettedContainer<BlockState>[] sections = new PalettedContainer[height.getSectionsCount()];
        for (int i = 0; i < sectionTags.size(); i++) {
            CompoundTag section = sectionTags.getCompound(i);
            int index = height.getSectionIndexFromSectionY(section.getByte(TAG_SECTION_Y));
            if (index >= 0 && index < sections.length && section.contains(TAG_BLOCK_STATES, Tag.TAG_COMPOUND)) {
                BLOCK_STATES.parse(NbtOps.INSTANCE, section.getCompound(TAG_BLOCK_STATES)).result()
                        .ifPresent(states -> sections[index] = states);
            }
        }
        Blocks3 blocks = (x, y, z) -> {
            int index = height.getSectionIndex(y);
            return index < 0 || index >= sections.length || sections[index] == null ? null : sections[index].get(x, y & 15, z);
        };
        SimpleBitStorage heightmap = savedHeightmap(tag, height);
        ChunkSurface surface = new ChunkSurface(pos);
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int from = ceiling ? scanFromY
                        : heightmap != null ? heightmap.get(z * 16 + x) + height.getMinBuildHeight() - 1
                        : height.getMaxBuildHeight() - 1;
                surface.sample(blocks, height, x, z, from);
            }
        }
        return surface;
    }

    /** The saved world-surface heightmap (first free Y above the top block, minus the min build height), if there is one. */
    @Nullable
    private static SimpleBitStorage savedHeightmap(CompoundTag tag, LevelHeightAccessor height) {
        CompoundTag heightmaps = tag.getCompound(TAG_HEIGHTMAPS);
        String key = Heightmap.Types.WORLD_SURFACE.getSerializationKey();
        if (!heightmaps.contains(key, Tag.TAG_LONG_ARRAY)) {
            return null;
        }
        try {
            return new SimpleBitStorage(Mth.ceillog2(height.getHeight() + 1), 256, heightmaps.getLongArray(key));
        } catch (RuntimeException e) {
            // A heightmap from another world height doesn't fit; scan from the top instead.
            return null;
        }
    }

    /** Scans down from {@code fromY} to the first non-air block. */
    private void sample(Blocks3 blocks, LevelHeightAccessor height, int x, int z, int fromY) {
        int i = z * 16 + x;
        tops[i] = NONE;
        for (int y = Math.min(fromY, height.getMaxBuildHeight() - 1); y >= height.getMinBuildHeight(); y--) {
            BlockState state = blocks.get(x, y, z);
            if (state == null) {
                // An empty section: skip to the one below.
                y = (y >> 4) << 4;
                continue;
            }
            if (!state.isAir()) {
                tops[i] = y;
                water[i] = state.getFluidState().is(FluidTags.WATER);
                return;
            }
        }
    }

    /** A block state by local X, world Y and local Z, or null where the whole section is empty. */
    @FunctionalInterface
    private interface Blocks3 {
        @Nullable
        BlockState get(int x, int y, int z);
    }
}
