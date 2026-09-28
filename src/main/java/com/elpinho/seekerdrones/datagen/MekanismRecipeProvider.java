package com.elpinho.seekerdrones.datagen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.elpinho.seekerdrones.SeekerDrones;
import com.elpinho.seekerdrones.registry.ModItems;
import com.elpinho.seekerdrones.station.RepairFluid;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

/**
 * The Mekanism variants of the component and Factory crafting recipes (DESIGN.md section 7.5). They reference
 * Mekanism items, which aren't registered when datagen runs, so they are written as JSON instead of through recipe
 * builders. Each is loaded only when Mekanism is.
 */
public class MekanismRecipeProvider implements DataProvider {
    private final PackOutput.PathProvider recipes;

    public MekanismRecipeProvider(PackOutput output) {
        this.recipes = output.createPathProvider(PackOutput.Target.DATA_PACK, "recipe");
    }

    @Override
    public CompletableFuture<?> run(CachedOutput cache) {
        List<CompletableFuture<?>> writes = new ArrayList<>();
        writes.add(shaped(cache, ModItems.DRONE_ROTOR.get(), List.of("SMS", " H "), keys(
                'S', tag("c:ingots/steel"),
                'M', item("minecraft:phantom_membrane"),
                'H', item("mekanism:hdpe_sheet"))));
        writes.add(shaped(cache, ModItems.SEEKER_CORE.get(), List.of(" O ", "DED", " C "), keys(
                'O', item("minecraft:observer"),
                'D', tag("c:gems/diamond"),
                'E', item("minecraft:ender_eye"),
                'C', tag("c:circuits/elite"))));
        writes.add(shaped(cache, ModItems.DRONE_FACTORY.get(), List.of("ACA", "DSD", "APA"), keys(
                'A', tag("c:alloys/ultimate"),
                'C', item("minecraft:crafter"),
                'D', tag("c:gems/diamond"),
                'S', item("mekanism:steel_casing"),
                'P', item("minecraft:piston"))));
        return CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new));
    }

    private CompletableFuture<?> shaped(CachedOutput cache, Item result, List<String> pattern, Map<Character, JsonObject> keys) {
        ResourceLocation resultId = BuiltInRegistries.ITEM.getKey(result);
        JsonObject json = new JsonObject();
        JsonArray conditions = new JsonArray();
        JsonObject modLoaded = new JsonObject();
        modLoaded.addProperty("type", "neoforge:mod_loaded");
        modLoaded.addProperty("modid", RepairFluid.MEKANISM);
        conditions.add(modLoaded);
        json.add("neoforge:conditions", conditions);
        json.addProperty("type", "minecraft:crafting_shaped");
        json.addProperty("category", "misc");
        JsonObject key = new JsonObject();
        keys.forEach((symbol, ingredient) -> key.add(String.valueOf(symbol), ingredient));
        json.add("key", key);
        JsonArray rows = new JsonArray();
        pattern.forEach(rows::add);
        json.add("pattern", rows);
        JsonObject resultJson = new JsonObject();
        resultJson.addProperty("count", 1);
        resultJson.addProperty("id", resultId.toString());
        json.add("result", resultJson);
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(SeekerDrones.MODID, "mekanism/" + resultId.getPath());
        return DataProvider.saveStable(cache, json, recipes.json(id));
    }

    private static Map<Character, JsonObject> keys(Object... entries) {
        Map<Character, JsonObject> keys = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            keys.put((Character) entries[i], (JsonObject) entries[i + 1]);
        }
        return keys;
    }

    private static JsonObject item(String id) {
        JsonObject json = new JsonObject();
        json.addProperty("item", id);
        return json;
    }

    private static JsonObject tag(String id) {
        JsonObject json = new JsonObject();
        json.addProperty("tag", id);
        return json;
    }

    @Override
    public String getName() {
        return "Seeker Drones Mekanism recipes";
    }
}
