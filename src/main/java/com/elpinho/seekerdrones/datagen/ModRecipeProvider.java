package com.elpinho.seekerdrones.datagen;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.elpinho.seekerdrones.SeekerDrones;
import com.elpinho.seekerdrones.drone.UpgradeType;
import com.elpinho.seekerdrones.factory.DroneAssemblyRecipe;
import com.elpinho.seekerdrones.registry.ModItems;
import com.elpinho.seekerdrones.station.RepairFluid;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.PackOutput;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.data.recipes.RecipeProvider;
import net.minecraft.data.recipes.ShapedRecipeBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.common.conditions.ModLoadedCondition;
import net.neoforged.neoforge.common.conditions.NotCondition;
import net.neoforged.neoforge.common.crafting.SizedIngredient;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;

/**
 * Crafting and drone assembly recipes. Components, the Factory and the drone assembly come in a base and a Mekanism
 * variant that exclude each other (DESIGN.md section 7.5). The Mekanism crafting recipes use Mekanism item IDs, so they
 * are written as plain JSON by {@link MekanismRecipeProvider}.
 */
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

    static final TagKey<Item> INGOTS_STEEL = TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("c", "ingots/steel"));
    static final TagKey<Item> ALLOYS_ULTIMATE = TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("c", "alloys/ultimate"));

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
        // Placeholder Charging Station recipe until the final recipes (section 11).
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ModItems.CHARGING_STATION.get())
                .pattern("ILI")
                .pattern("RBR")
                .pattern("III")
                .define('I', Items.IRON_INGOT)
                .define('L', Items.LIGHTNING_ROD)
                .define('R', Items.REDSTONE)
                .define('B', Items.REDSTONE_BLOCK)
                .unlockedBy(getHasName(Items.REDSTONE_BLOCK), has(Items.REDSTONE_BLOCK))
                .save(output);

        // Placeholder Programming Station recipe until the final recipes (section 11).
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ModItems.PROGRAMMING_STATION.get())
                .pattern("IOI")
                .pattern("RSR")
                .pattern("ICI")
                .define('I', Items.IRON_INGOT)
                .define('O', Items.OBSERVER)
                .define('R', Items.REDSTONE)
                .define('S', ModItems.SEEKER_CORE.get())
                .define('C', Items.COMPARATOR)
                .unlockedBy(getHasName(ModItems.SEEKER_CORE.get()), has(ModItems.SEEKER_CORE.get()))
                .save(output);

        buildBaseRecipes(output.withConditions(new NotCondition(new ModLoadedCondition(RepairFluid.MEKANISM))));
        buildMekanismAssembly(output.withConditions(new ModLoadedCondition(RepairFluid.MEKANISM)));
    }

    /** Recipes for packs without Mekanism (section 7.5). */
    private void buildBaseRecipes(RecipeOutput output) {
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ModItems.DRONE_ROTOR.get())
                .pattern("IMI")
                .pattern(" R ")
                .define('I', Tags.Items.INGOTS_IRON)
                .define('M', Items.PHANTOM_MEMBRANE)
                .define('R', Tags.Items.DUSTS_REDSTONE)
                .unlockedBy(getHasName(Items.PHANTOM_MEMBRANE), has(Items.PHANTOM_MEMBRANE))
                .save(output);
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ModItems.SEEKER_CORE.get())
                .pattern(" O ")
                .pattern("DED")
                .pattern(" B ")
                .define('O', Items.OBSERVER)
                .define('D', Tags.Items.GEMS_DIAMOND)
                .define('E', Items.ENDER_EYE)
                .define('B', Tags.Items.STORAGE_BLOCKS_REDSTONE)
                .unlockedBy(getHasName(Items.ENDER_EYE), has(Items.ENDER_EYE))
                .save(output);
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ModItems.DRONE_FACTORY.get())
                .pattern("DRD")
                .pattern("ICI")
                .pattern("DPD")
                .define('D', Tags.Items.GEMS_DIAMOND)
                .define('R', Tags.Items.STORAGE_BLOCKS_REDSTONE)
                .define('I', Tags.Items.STORAGE_BLOCKS_IRON)
                .define('C', Items.CRAFTER)
                .define('P', Items.PISTON)
                .unlockedBy(getHasName(Items.CRAFTER), has(Items.CRAFTER))
                .save(output);
        output.accept(assemblyId("drone"), new DroneAssemblyRecipe(
                List.of(SizedIngredient.of(ModItems.DRONE_ROTOR.get(), 4),
                        SizedIngredient.of(ModItems.SEEKER_CORE.get(), 1),
                        SizedIngredient.of(Tags.Items.INGOTS_IRON, 4)),
                SizedFluidIngredient.of(Fluids.LAVA, 1_000),
                50_000, 200), null);
    }

    /** The Mekanism drone assembly (section 7.5). Its crafting recipes are in {@link MekanismRecipeProvider}. */
    private void buildMekanismAssembly(RecipeOutput output) {
        output.accept(assemblyId("mekanism/drone"), new DroneAssemblyRecipe(
                List.of(SizedIngredient.of(ModItems.DRONE_ROTOR.get(), 4),
                        SizedIngredient.of(ModItems.SEEKER_CORE.get(), 1),
                        SizedIngredient.of(INGOTS_STEEL, 4),
                        SizedIngredient.of(ALLOYS_ULTIMATE, 2)),
                SizedFluidIngredient.of(RepairFluid.ETHENE, 500),
                100_000, 300), null);
    }

    private static ResourceLocation assemblyId(String path) {
        return ResourceLocation.fromNamespaceAndPath(SeekerDrones.MODID, "drone_assembly/" + path);
    }
}
