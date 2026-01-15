package com.example.planetmapper.handler;

import com.example.planetmapper.PlanetMapper;
import com.example.planetmapper.physics.WorldCollisionManager;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

@EventBusSubscriber(modid = PlanetMapper.MODID, bus = EventBusSubscriber.Bus.GAME)
public class WorldCollisionHandler {

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (event.getLevel() instanceof ServerLevel level) {
            WorldCollisionManager.onChunkLoad(level, event.getChunk());
        }
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (event.getLevel() instanceof ServerLevel level) {
            WorldCollisionManager.onChunkUnload(level, event.getChunk());
        }
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (event.getLevel() instanceof ServerLevel level) {
            WorldCollisionManager.tick(level);
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        WorldCollisionManager.shutdown();
    }
}
