package com.example.planetmapper.handler;

import com.example.planetmapper.PlanetMapper;
import com.example.planetmapper.physics.WorldCollisionManager;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
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

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        WorldCollisionManager.reset();
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof ServerLevel level) {
            WorldCollisionManager.markChunkDirty(level, event.getPos());
        }
    }

    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel() instanceof ServerLevel level) {
            WorldCollisionManager.markChunkDirty(level, event.getPos());
        }
    }

    @SubscribeEvent
    public static void onBlockMultiPlace(BlockEvent.EntityMultiPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        for (BlockSnapshot snapshot : event.getReplacedBlockSnapshots()) {
            WorldCollisionManager.markChunkDirty(level, snapshot.getPos());
        }
    }

    @SubscribeEvent
    public static void onBlockToolModify(BlockEvent.BlockToolModificationEvent event) {
        if (event.getLevel() instanceof ServerLevel level) {
            WorldCollisionManager.markChunkDirty(level, event.getPos());
        }
    }

    @SubscribeEvent
    public static void onFluidPlace(BlockEvent.FluidPlaceBlockEvent event) {
        if (event.getLevel() instanceof ServerLevel level) {
            WorldCollisionManager.markChunkDirty(level, event.getPos());
        }
    }
}
