package com.elpinho.seekerdrones.datagen;

import java.util.Set;

import com.elpinho.seekerdrones.radar.RadarStationBlock;
import com.elpinho.seekerdrones.registry.ModBlocks;
import com.elpinho.seekerdrones.registry.ModDataComponents;

import net.minecraft.core.HolderLookup;
import net.minecraft.data.loot.BlockLootSubProvider;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.functions.CopyComponentsFunction;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;

public class ModBlockLootProvider extends BlockLootSubProvider {
    public ModBlockLootProvider(HolderLookup.Provider registries) {
        super(Set.of(), FeatureFlags.REGISTRY.allFlags(), registries);
    }

    @Override
    protected void generate() {
        // The Charging Station item keeps its upgrades (DESIGN.md section 7.4).
        Block chargingStation = ModBlocks.CHARGING_STATION.get();
        add(chargingStation, LootTable.lootTable().withPool(applyExplosionCondition(chargingStation, LootPool.lootPool()
                .setRolls(ConstantValue.exactly(1))
                .add(LootItem.lootTableItem(chargingStation)
                        .apply(CopyComponentsFunction.copyComponents(CopyComponentsFunction.Source.BLOCK_ENTITY)
                                .include(ModDataComponents.MACHINE_UPGRADES.get()))))));
        // The Factory item keeps its Operator Group ID (DESIGN.md section 6.1).
        Block factory = ModBlocks.DRONE_FACTORY.get();
        add(factory, LootTable.lootTable().withPool(applyExplosionCondition(factory, LootPool.lootPool()
                .setRolls(ConstantValue.exactly(1))
                .add(LootItem.lootTableItem(factory)
                        .apply(CopyComponentsFunction.copyComponents(CopyComponentsFunction.Source.BLOCK_ENTITY)
                                .include(ModDataComponents.OPERATOR_GROUP.get()))))));
        // The Programming Station item keeps its mode, template and upgrades (DESIGN.md section 7.2).
        Block programmingStation = ModBlocks.PROGRAMMING_STATION.get();
        add(programmingStation, LootTable.lootTable().withPool(applyExplosionCondition(programmingStation, LootPool.lootPool()
                .setRolls(ConstantValue.exactly(1))
                .add(LootItem.lootTableItem(programmingStation)
                        .apply(CopyComponentsFunction.copyComponents(CopyComponentsFunction.Source.BLOCK_ENTITY)
                                .include(ModDataComponents.PROGRAMMING_STATION.get())
                                .include(ModDataComponents.MACHINE_UPGRADES.get()))))));
        // The Deploying Station item keeps its auto-deploy setting (DESIGN.md section 7.3).
        Block deployingStation = ModBlocks.DEPLOYING_STATION.get();
        add(deployingStation, LootTable.lootTable().withPool(applyExplosionCondition(deployingStation, LootPool.lootPool()
                .setRolls(ConstantValue.exactly(1))
                .add(LootItem.lootTableItem(deployingStation)
                        .apply(CopyComponentsFunction.copyComponents(CopyComponentsFunction.Source.BLOCK_ENTITY)
                                .include(ModDataComponents.DEPLOYING_STATION.get()))))));
        // The Radar Station is two blocks tall; only the lower half drops it (DESIGN.md section 7.8).
        Block radarStation = ModBlocks.RADAR_STATION.get();
        add(radarStation, createSinglePropConditionTable(radarStation, RadarStationBlock.HALF, DoubleBlockHalf.LOWER));
        Block uplink = ModBlocks.UPLINK.get();
        add(uplink, createSinglePropConditionTable(uplink, RadarStationBlock.HALF, DoubleBlockHalf.LOWER));
    }

    @Override
    protected Iterable<Block> getKnownBlocks() {
        return ModBlocks.BLOCKS.getEntries().stream().<Block>map(holder -> holder.get())::iterator;
    }
}
