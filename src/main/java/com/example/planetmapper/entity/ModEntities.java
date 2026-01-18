package com.example.planetmapper.entity;

import com.example.planetmapper.PlanetMapper;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredHolder;

public class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, PlanetMapper.MODID);

    public static final DeferredHolder<EntityType<?>, EntityType<PhysicsBlockEntity>> PHYSICS_BLOCK = ENTITIES.register("physics_block", () ->
            EntityType.Builder.<PhysicsBlockEntity>of(PhysicsBlockEntity::new, MobCategory.MISC)
                    .sized(1.0f, 1.0f)
                    .clientTrackingRange(10)
                    .updateInterval(1) // Important for smooth physics!
                    .build("physics_block"));

    public static final DeferredHolder<EntityType<?>, EntityType<PhysicsStructureEntity>> PHYSICS_STRUCTURE = ENTITIES.register("physics_structure", () ->
            EntityType.Builder.<PhysicsStructureEntity>of(PhysicsStructureEntity::new, MobCategory.MISC)
                    .sized(1.0f, 1.0f)
                    .clientTrackingRange(10)
                    .updateInterval(1)
                    .build("physics_structure"));

    public static void register(IEventBus eventBus) {
        ENTITIES.register(eventBus);
    }
}
