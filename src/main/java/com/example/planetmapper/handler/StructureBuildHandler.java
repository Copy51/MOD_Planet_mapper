package com.example.planetmapper.handler;

import com.example.planetmapper.PlanetMapper;
import com.example.planetmapper.physics.structure.StructureBuildManager;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

@EventBusSubscriber(modid = PlanetMapper.MODID, bus = EventBusSubscriber.Bus.GAME)
public class StructureBuildHandler {

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            StructureBuildManager.tick(serverLevel);
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        StructureBuildManager.shutdown();
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        StructureBuildManager.reset();
    }
}
