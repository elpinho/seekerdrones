package com.elpinho.seekerdrones.registry;

import com.elpinho.seekerdrones.SeekerDrones;
import com.elpinho.seekerdrones.drone.DroneEntity;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModEntityTypes {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, SeekerDrones.MODID);

    public static final DeferredHolder<EntityType<?>, EntityType<DroneEntity>> DRONE = ENTITY_TYPES.register(
            "drone",
            () -> EntityType.Builder.of(DroneEntity::new, MobCategory.MISC)
                    .sized(0.75F, 0.4F)
                    .clientTrackingRange(8)
                    .build("drone"));

    public static void registerAttributes(EntityAttributeCreationEvent event) {
        event.put(DRONE.get(), DroneEntity.createAttributes().build());
    }
}
