package com.elpinho.seekerdrones.uplink;

import static com.elpinho.seekerdrones.gametest.GameTestUtil.pollUntil;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.elpinho.seekerdrones.radar.ColorMap;
import com.elpinho.seekerdrones.radar.TerrainBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Satellite Uplink terrain GameTests (DESIGN.md section 7.9): the map in vanilla map colors for loaded chunks, for
 * chunks that are saved but not loaded, and for chunks that were never generated. Only public terrain API is used.
 */
@GameTestHolder("seekerdrones")
@PrefixGameTestTemplate(false)
public class UplinkTerrainGameTests {
    /** A chunk that nothing ever generates (the radar tests use another one). */
    private static final ChunkPos NEVER_GENERATED = new ChunkPos(-7100, 7100);

    private static int colorId(byte packed) {
        return (packed & 0xFF) >> 2;
    }

    private static int brightness(byte packed) {
        return packed & 3;
    }

    private static byte at(ColorMap map, BlockPos center, BlockPos pos) {
        return map.colors()[map.index(pos.getX() - (center.getX() - map.radius()), pos.getZ() - (center.getZ() - map.radius()))];
    }

    private static void build(GameTestHelper helper, ServerLevel level, TerrainBuilder<ColorMap> builder, int ticks, Consumer<ColorMap> then) {
        AtomicReference<ColorMap> built = new AtomicReference<>();
        pollUntil(helper, () -> {
            built.set(builder.tick(level, 16));
            return built.get() != null;
        }, ticks, () -> then.accept(built.get()), () -> helper.fail("The color map was not finished in " + ticks + " ticks"));
    }

    /** A water column of {@code depth} blocks over sand, walled in so it can't flow into the other columns. */
    private static void waterColumn(ServerLevel level, BlockPos sand, int depth) {
        int top = sand.getY() + depth;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx != 0 || dz != 0) {
                    for (int y = sand.getY(); y <= top; y++) {
                        level.setBlock(sand.offset(dx, y - sand.getY(), dz), Blocks.STONE.defaultBlockState(), 3);
                    }
                }
            }
        }
        level.setBlock(sand, Blocks.SAND.defaultBlockState(), 3);
        for (int y = sand.getY() + 1; y <= top; y++) {
            level.setBlock(new BlockPos(sand.getX(), y, sand.getZ()), Blocks.WATER.defaultBlockState(), 3);
        }
    }

    // --- 6. Color terrain ---

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void uplinkTerrainIsBuiltInMapColorsFromLoadedChunks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos center = helper.absolutePos(new BlockPos(4, 1, 4));
        BlockPos gold = helper.absolutePos(new BlockPos(6, 3, 4));
        helper.setBlock(new BlockPos(6, 3, 4), Blocks.GOLD_BLOCK);
        // Shallow water (2 deep) over sand.
        BlockPos sand = helper.absolutePos(new BlockPos(2, 1, 2));
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.SAND);
        helper.setBlock(new BlockPos(2, 2, 2), Blocks.WATER);
        helper.setBlock(new BlockPos(2, 3, 2), Blocks.WATER);
        TerrainBuilder<ColorMap> builder = TerrainBuilder.colors(level, center, 8);
        build(helper, level, builder, 40, map -> {
            helper.assertTrue(map.radius() == 8 && map.colors().length == 17 * 17, "Map should be 17 x 17 bytes for radius 8");
            byte goldColor = at(map, center, gold);
            helper.assertTrue(colorId(goldColor) == MapColor.GOLD.id, "A gold block column should have MapColor.GOLD (" + MapColor.GOLD.id + "), got " + colorId(goldColor));
            byte waterColor = at(map, center, sand.above(2));
            helper.assertTrue(colorId(waterColor) == MapColor.WATER.id, "A water column should have MapColor.WATER (" + MapColor.WATER.id + "), got " + colorId(waterColor));
            helper.assertTrue(brightness(waterColor) == MapColor.Brightness.HIGH.id, "Shallow water should be the bright shade, got " + brightness(waterColor));
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void uplinkTerrainIsReadInMapColorsFromSavedChunksThatAreNotLoaded(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ChunkPos saved = new ChunkPos(6000 + ThreadLocalRandom.current().nextInt(100000), 6100);
        LevelChunk chunk = level.getChunk(saved.x, saved.z);
        BlockPos gold = new BlockPos(saved.getMinBlockX() + 3, 200, saved.getMinBlockZ() + 5);
        BlockPos emerald = new BlockPos(saved.getMinBlockX() + 10, 150, saved.getMinBlockZ() + 5);
        level.setBlock(gold, Blocks.GOLD_BLOCK.defaultBlockState(), 3);
        level.setBlock(emerald, Blocks.EMERALD_BLOCK.defaultBlockState(), 3);
        // Water columns of 1, 5 and 12 blocks, three columns apart, over sand at Y 190.
        BlockPos shallow = new BlockPos(saved.getMinBlockX() + 3, 190, saved.getMinBlockZ() + 12);
        BlockPos medium = new BlockPos(saved.getMinBlockX() + 6, 190, saved.getMinBlockZ() + 12);
        BlockPos deep = new BlockPos(saved.getMinBlockX() + 9, 190, saved.getMinBlockZ() + 12);
        waterColumn(level, shallow, 1);
        waterColumn(level, medium, 5);
        waterColumn(level, deep, 12);
        chunk.setUnsaved(true);
        BlockPos center = new BlockPos(saved.getMinBlockX() + 8, 100, saved.getMinBlockZ() + 8);
        pollUntil(helper, () -> level.getChunkSource().getChunkNow(saved.x, saved.z) == null
                && level.getChunkSource().chunkMap.read(saved).join().isPresent(), 200, () -> {
            TerrainBuilder<ColorMap> builder = TerrainBuilder.colors(level, center, 8);
            build(helper, level, builder, 100, map -> {
                helper.assertTrue(level.getChunkSource().getChunkNow(saved.x, saved.z) == null, "Building the map must not load the chunk");
                byte goldColor = at(map, center, gold);
                byte emeraldColor = at(map, center, emerald);
                helper.assertTrue(goldColor != ColorMap.UNKNOWN, "A saved but unloaded chunk should not be unknown");
                helper.assertTrue(colorId(goldColor) == MapColor.GOLD.id, "Saved gold column should have MapColor.GOLD, got " + colorId(goldColor));
                helper.assertTrue(colorId(emeraldColor) == MapColor.EMERALD.id, "Saved emerald column should have MapColor.EMERALD, got " + colorId(emeraldColor));
                byte shallowColor = at(map, center, shallow.above(1));
                byte mediumColor = at(map, center, medium.above(5));
                byte deepColor = at(map, center, deep.above(12));
                helper.assertTrue(colorId(shallowColor) == MapColor.WATER.id && colorId(mediumColor) == MapColor.WATER.id && colorId(deepColor) == MapColor.WATER.id,
                        "Water columns should use MapColor.WATER, got " + colorId(shallowColor) + ", " + colorId(mediumColor) + ", " + colorId(deepColor));
                helper.assertTrue(brightness(shallowColor) == MapColor.Brightness.HIGH.id, "1 deep water should be the bright shade, got " + brightness(shallowColor));
                helper.assertTrue(brightness(mediumColor) == MapColor.Brightness.NORMAL.id, "5 deep water should be the normal shade, got " + brightness(mediumColor));
                helper.assertTrue(brightness(deepColor) == MapColor.Brightness.LOW.id, "12 deep water should be the dark shade, got " + brightness(deepColor));
                helper.succeed();
            });
        }, () -> helper.fail("The saved chunk was not unloaded and saved in time"));
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void uplinkTerrainOfNeverGeneratedChunksIsColorZero(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos center = new BlockPos(NEVER_GENERATED.getMinBlockX() + 8, 64, NEVER_GENERATED.getMinBlockZ() + 8);
        TerrainBuilder<ColorMap> builder = TerrainBuilder.colors(level, center, 8);
        build(helper, level, builder, 80, map -> {
            for (int i = 0; i < map.colors().length; i++) {
                helper.assertTrue(map.colors()[i] == ColorMap.UNKNOWN, "Never generated column " + i + " should be color 0, got " + map.colors()[i]);
            }
            helper.assertTrue(level.getChunkSource().getChunkNow(NEVER_GENERATED.x, NEVER_GENERATED.z) == null, "The builder must not load the chunk");
            helper.succeed();
        });
    }
}
