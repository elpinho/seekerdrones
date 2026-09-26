package com.elpinho.seekerdrones.datagen;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.elpinho.seekerdrones.drone.UpgradeType;
import com.elpinho.seekerdrones.registry.ModItems;

import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.data.recipes.RecipeProvider;
import net.minecraft.data.recipes.ShapedRecipeBuilder;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

public class ModRecipeProvider extends RecipeProvider {
    /** Placeholder upgrade recipes (DESIGN.md section 4): a type-specific core ringed by iron and redstone. */
    private static final Map<UpgradeType, Item> UPGRADE_CORES = Map.of(
            UpgradeType.PATROL, Items.COMPASS,
            UpgradeType.SIGHT, Items.SPYGLASS,
            UpgradeType.EXPLOSIVE, Items.TNT,
            UpgradeType.SIREN, Items.NOTE_BLOCK,
            UpgradeType.TRANSMITTER, Items.LIGHTNING_ROD,
            UpgradeType.ENERGY, Items.REDSTONE_BLOCK,
            UpgradeType.HEALTH, Items.GOLDEN_APPLE,
            UpgradeType.PLAYER_SEEK, Items.ENDER_EYE,
            UpgradeType.MULTI_TARGET, Items.TARGET,
            UpgradeType.XRAY, Items.TINTED_GLASS);

    public ModRecipeProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> lookupProvider) {
        super(output, lookupProvider);
    }

    @Override
    protected void buildRecipes(RecipeOutput output) {
        for (UpgradeType type : UpgradeType.values()) {
            Item core = UPGRADE_CORES.get(type);
            ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ModItems.upgrade(type).get())
                    .pattern("IRI")
                    .pattern("RCR")
                    .pattern("IRI")
                    .define('I', Items.IRON_INGOT)
                    .define('R', Items.REDSTONE)
                    .define('C', core)
                    .unlockedBy(getHasName(core), has(core))
                    .save(output);
        }
        // The Drone Assembly recipes are added in M6.
    }
}
