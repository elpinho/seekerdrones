package com.elpinho.seekerdrones.datagen;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.data.loot.LootTableProvider;

public class ModLootTableProvider extends LootTableProvider {
    public ModLootTableProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> lookupProvider) {
        // No blocks or entities with loot tables yet. Sub providers are added as those are registered.
        super(output, Set.of(), List.of(), lookupProvider);
    }
}
