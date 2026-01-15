package com.example.planetmapper.handler;

import com.example.planetmapper.PlanetMapper;
import com.example.planetmapper.physics.PhysicsWorldManager;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Synchronizes native physics state back to game entities each server tick.
 */
@EventBusSubscriber(modid = PlanetMapper.MODID, bus = EventBusSubscriber.Bus.GAME)
public class PhysicsSyncHandler {

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        // Run once per server tick (overworld is always loaded).
        if (event.getLevel().dimension() != Level.OVERWORLD) {
            return;
        }
        PhysicsWorldManager.onServerTick();
    }
}
