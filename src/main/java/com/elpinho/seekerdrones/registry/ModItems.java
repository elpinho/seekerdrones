package com.elpinho.seekerdrones.registry;

import com.elpinho.seekerdrones.SeekerDrones;
import com.elpinho.seekerdrones.drone.DroneItem;

import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(SeekerDrones.MODID);

    public static final DeferredItem<DroneItem> DRONE = ITEMS.register("drone", () -> new DroneItem(new Item.Properties().stacksTo(1)));
}
