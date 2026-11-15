package com.elpinho.seekerdrones.radar;

import static com.elpinho.seekerdrones.gametest.GameTestUtil.pollUntil;

import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Drone Radar Station terrain GameTests (DESIGN.md section 7.8). They live in the radar package to reach the
 * package-private {@link TerrainBuilder}. The saved-chunk tests generate chunks far from the test area (and from each
 * other), so nothing else loads them; a chunk left saved by an earlier run only makes the setup quicker.
 */
@GameTestHolder("seekerdrones")
@PrefixGameTestTemplate(false)
public class RadarTerrainGameTests {
    /** Chunks (a fresh random one per run, so the data is never left over from an earlier run) generated, edited and saved by {@link #terrainIsReadFromSavedChunksThatAreNotLoaded}. */
    /** Chunk that nothing ever generates. */
    private static final ChunkPos NEVER_GENERATED = new ChunkPos(-7000, 7000);
    private static final int DELAY = 0;
    private static final ChunkPos NETHER_CHUNK = new ChunkPos(9000, 9000);

    private static int column(TerrainMap map, BlockPos center, BlockPos pos) {
        return map.index(pos.getX() - (center.getX() - map.radius()), pos.getZ() - (center.getZ() - map.radius()));
    }

    /** Ticks the builder every game tick until it hands over the map. */
    private static void buildMap(GameTestHelper helper, ServerLevel level, TerrainBuilder<TerrainMap> builder, int ticks, java.util.function.Consumer<TerrainMap> then) {
        AtomicReference<TerrainMap> built = new AtomicReference<>();
        pollUntil(helper, () -> {
            built.set(builder.tick(level, 16));
            return built.get() != null;
        }, ticks, () -> then.accept(built.get()), () -> helper.fail("The terrain map was not finished in " + ticks + " ticks"));
    }

    // --- 5. Terrain from loaded and saved chunks ---

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void terrainIsBuiltFromLoadedChunks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos center = helper.absolutePos(new BlockPos(4, 1, 4));
        BlockPos tower = helper.absolutePos(new BlockPos(6, 3, 4));
        helper.setBlock(new BlockPos(6, 3, 4), Blocks.STONE);
        TerrainBuilder<TerrainMap> builder = TerrainBuilder.heights(level, center, 8);
        buildMap(helper, level, builder, 40, map -> {
            helper.assertTrue(map.radius() == 8 && map.size() == 17, "Map should be 17 x 17 for radius 8");
            byte height = map.heights()[column(map, center, tower)];
            helper.assertTrue(height != TerrainMap.UNKNOWN, "A loaded column should be known");
            // The test area has a barrier box around it, so the surface is whatever the world's heightmap says.
            int realTop = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, tower.getX(), tower.getZ()) - 1;
            helper.assertTrue(height == realTop - center.getY(),
                    "Height should be the surface relative to the station's Y, expected " + (realTop - center.getY()) + " got " + height);
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void terrainIsReadFromSavedChunksThatAreNotLoaded(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ChunkPos SAVED_CHUNK = new ChunkPos(6000 + java.util.concurrent.ThreadLocalRandom.current().nextInt(100000), 6000);
        LevelChunk chunk = level.getChunk(SAVED_CHUNK.x, SAVED_CHUNK.z);
        BlockPos high = new BlockPos(SAVED_CHUNK.getMinBlockX() + 3, 200, SAVED_CHUNK.getMinBlockZ() + 5);
        BlockPos lower = new BlockPos(SAVED_CHUNK.getMinBlockX() + 10, 150, SAVED_CHUNK.getMinBlockZ() + 10);
        level.setBlock(high, Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(lower, Blocks.STONE.defaultBlockState(), 3);
        chunk.setUnsaved(true);
        BlockPos center = new BlockPos(SAVED_CHUNK.getMinBlockX() + 8, 100, SAVED_CHUNK.getMinBlockZ() + 8);
        // Wait until the chunk has been unloaded (and saved), then build.
        // Vanilla saves an unloaded chunk only when the server has time left in a tick, which can come later than the
        // chunk leaving the loaded map while other tests keep the server busy, so wait for its saved data too.
        pollUntil(helper, () -> level.getChunkSource().getChunkNow(SAVED_CHUNK.x, SAVED_CHUNK.z) == null
                && level.getChunkSource().chunkMap.read(SAVED_CHUNK).join().isPresent(), 200, () -> helper.runAfterDelay(DELAY, () -> {
            TerrainBuilder<TerrainMap> builder = TerrainBuilder.heights(level, center, 8);
            buildMap(helper, level, builder, 100, map -> {
                helper.assertTrue(level.getChunkSource().getChunkNow(SAVED_CHUNK.x, SAVED_CHUNK.z) == null,
                        "Building the map must not load the chunk");
                byte highHeight = map.heights()[column(map, center, high)];
                byte lowerHeight = map.heights()[column(map, center, lower)];
                helper.assertTrue(highHeight != TerrainMap.UNKNOWN, "A saved but unloaded chunk should not be unknown; saved tag present: "
                        + level.getChunkSource().chunkMap.read(SAVED_CHUNK).join().map(tag -> "yes, sections=" + tag.getList("sections", 10).size()
                        + " status=" + tag.getString("Status") + " surface top=" + ChunkSurface.of(SAVED_CHUNK, tag, level, false, 100).top(3, 5)).orElse("no"));
                helper.assertTrue(highHeight == 100, "Column with a block at Y 200 should read +100, got " + highHeight);
                helper.assertTrue(lowerHeight == 50, "Column with a block at Y 150 should read +50, got " + lowerHeight);
                helper.succeed();
            });
        }), () -> helper.fail("The saved chunk was not unloaded and saved in time"));
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void terrainOfNeverGeneratedChunksStaysUnknown(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos center = new BlockPos(NEVER_GENERATED.getMinBlockX() + 8, 64, NEVER_GENERATED.getMinBlockZ() + 8);
        TerrainBuilder<TerrainMap> builder = TerrainBuilder.heights(level, center, 8);
        buildMap(helper, level, builder, 80, map -> {
            for (int i = 0; i < map.heights().length; i++) {
                helper.assertTrue(map.heights()[i] == TerrainMap.UNKNOWN, "Never generated column " + i + " should be UNKNOWN, got " + map.heights()[i]);
            }
            helper.assertTrue(level.getChunkSource().getChunkNow(NEVER_GENERATED.x, NEVER_GENERATED.z) == null, "The builder must not load the chunk");
            helper.succeed();
        });
    }

    // --- 6. Dimensions with a ceiling ---

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void netherTerrainShowsTheFloorBelowTheStationNotTheBedrockRoof(GameTestHelper helper) {
        ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
        helper.assertTrue(nether != null, "The Nether should be loaded in the GameTest server");
        helper.assertTrue(nether.dimensionType().hasCeiling(), "Fixture: the Nether has a ceiling");
        LevelChunk chunk = nether.getChunk(NETHER_CHUNK.x, NETHER_CHUNK.z);
        BlockPos floor = new BlockPos(NETHER_CHUNK.getMinBlockX() + 4, 60, NETHER_CHUNK.getMinBlockZ() + 4);
        nether.setBlock(floor, Blocks.NETHERRACK.defaultBlockState(), 3);
        BlockPos center = new BlockPos(NETHER_CHUNK.getMinBlockX() + 8, 60, NETHER_CHUNK.getMinBlockZ() + 8);

        ChunkSurface ceilingSurface = ChunkSurface.of(chunk, true, 60);
        ChunkSurface plainSurface = ChunkSurface.of(chunk, false, 60);
        helper.assertTrue(ceilingSurface.top(4, 4) == 60, "With a ceiling, the top should be the highest block at or below Y 60, got " + ceilingSurface.top(4, 4));
        helper.assertTrue(plainSurface.top(4, 4) > 100, "Without the ceiling rule the top is the roof, got " + plainSurface.top(4, 4));
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int top = ceilingSurface.top(x, z);
                helper.assertTrue(top == ChunkSurface.NONE || top <= 60, "Column " + x + "," + z + " should be at or below the station's Y, got " + top);
            }
        }

        // Same tick, so the chunk is still loaded: the builder reads it directly (the neighbors are read from disk or stay unknown).
        TerrainBuilder<TerrainMap> builder = TerrainBuilder.heights(nether, center, 8);
        buildMap(helper, nether, builder, 80, map -> {
            helper.assertTrue(map.heights()[column(map, center, floor)] == 0, "The floor column should read 0 (relative to the station), got " + map.heights()[column(map, center, floor)]);
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    byte height = map.heights()[column(map, center, new BlockPos(NETHER_CHUNK.getMinBlockX() + x, 60, NETHER_CHUNK.getMinBlockZ() + z))];
                    if (NETHER_CHUNK.getMinBlockX() + x >= center.getX() - 8 && NETHER_CHUNK.getMinBlockX() + x <= center.getX() + 8
                            && NETHER_CHUNK.getMinBlockZ() + z >= center.getZ() - 8 && NETHER_CHUNK.getMinBlockZ() + z <= center.getZ() + 8) {
                        helper.assertTrue(height == TerrainMap.UNKNOWN || height <= 0, "Map column should be at or below the station, got " + height);
                    }
                }
            }
            helper.succeed();
        });
    }
}
