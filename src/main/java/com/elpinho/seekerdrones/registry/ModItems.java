package com.elpinho.seekerdrones.registry;

import java.util.EnumMap;
import java.util.Map;

import com.elpinho.seekerdrones.SeekerDrones;
import com.elpinho.seekerdrones.drone.DroneItem;
import com.elpinho.seekerdrones.drone.UpgradeItem;
import com.elpinho.seekerdrones.drone.UpgradeType;

import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(SeekerDrones.MODID);

    public static final DeferredItem<DroneItem> DRONE = ITEMS.register("drone", () -> new DroneItem(new Item.Properties().stacksTo(1)));

    public static final Map<UpgradeType, DeferredItem<UpgradeItem>> UPGRADES = registerUpgrades();

    private static Map<UpgradeType, DeferredItem<UpgradeItem>> registerUpgrades() {
        Map<UpgradeType, DeferredItem<UpgradeItem>> upgrades = new EnumMap<>(UpgradeType.class);
        for (UpgradeType type : UpgradeType.values()) {
            upgrades.put(type, ITEMS.register(UpgradeItem.itemName(type), () -> new UpgradeItem(type, new Item.Properties())));
        }
        return Map.copyOf(upgrades);
    }

    public static DeferredItem<UpgradeItem> upgrade(UpgradeType type) {
        return UPGRADES.get(type);
    }
}
