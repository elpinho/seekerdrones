package com.elpinho.seekerdrones.radar;

/**
 * What a {@link TerrainBuilder} fills in, column by column, and turns into the finished map once every chunk is in.
 * One canvas per build.
 */
interface TerrainCanvas<M extends RadarMap> {
    /** Whether the columns are sampled for map colors (the top is then the highest block with a map color). */
    boolean colors();

    /** Copies one column of a chunk's surface into the map. The column is known (its top isn't {@link ChunkSurface#NONE}). */
    void set(int index, ChunkSurface surface, int localX, int localZ);

    M finish();
}
