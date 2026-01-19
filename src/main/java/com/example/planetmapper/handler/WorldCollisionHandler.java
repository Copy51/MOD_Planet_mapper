package com.example.planetmapper.handler;

import com.example.planetmapper.PlanetMapper;
import com.example.planetmapper.physics.WorldCollisionManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

@EventBusSubscriber(modid = PlanetMapper.MODID, bus = EventBusSubscriber.Bus.GAME)
public class WorldCollisionHandler {

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (event.getLevel() instanceof ServerLevel level) {
            if (level.dimension() == PlanetMapper.SHIPYARD_LEVEL) {
                return;
            }
            WorldCollisionManager.onChunkLoad(level, event.getChunk());
        }
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (event.getLevel() instanceof ServerLevel level) {
            if (level.dimension() == PlanetMapper.SHIPYARD_LEVEL) {
                return;
            }
            WorldCollisionManager.onChunkUnload(level, event.getChunk());
        }
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (event.getLevel() instanceof ServerLevel level) {
            if (level.dimension() == PlanetMapper.SHIPYARD_LEVEL) {
                return;
            }
            WorldCollisionManager.tick(level);
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        if (level.dimension() == PlanetMapper.SHIPYARD_LEVEL) {
            return;
        }
        long gameTime = level.getGameTime();
        if ((gameTime + player.getId()) % 20 != 0) {
            return;
        }
        ChunkPos center = player.chunkPosition();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                WorldCollisionManager.ensureChunkCollider(level, new ChunkPos(center.x + dx, center.z + dz));
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        WorldCollisionManager.shutdown();
        com.example.planetmapper.physics.PhysicsColliderManager.resetAll();
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        WorldCollisionManager.reset();
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof ServerLevel level) {
            if (level.dimension() == PlanetMapper.SHIPYARD_LEVEL) {
                return;
            }
            WorldCollisionManager.markChunkDirty(level, event.getPos());
        }
    }

    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel() instanceof ServerLevel level) {
            if (level.dimension() == PlanetMapper.SHIPYARD_LEVEL) {
                return;
            }
            WorldCollisionManager.markChunkDirty(level, event.getPos());
        }
    }

    @SubscribeEvent
    public static void onBlockMultiPlace(BlockEvent.EntityMultiPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (level.dimension() == PlanetMapper.SHIPYARD_LEVEL) {
            return;
        }
        for (BlockSnapshot snapshot : event.getReplacedBlockSnapshots()) {
            WorldCollisionManager.markChunkDirty(level, snapshot.getPos());
        }
    }

    @SubscribeEvent
    public static void onBlockToolModify(BlockEvent.BlockToolModificationEvent event) {
        if (event.getLevel() instanceof ServerLevel level) {
            if (level.dimension() == PlanetMapper.SHIPYARD_LEVEL) {
                return;
            }
            WorldCollisionManager.markChunkDirty(level, event.getPos());
        }
    }

    @SubscribeEvent
    public static void onFluidPlace(BlockEvent.FluidPlaceBlockEvent event) {
        if (event.getLevel() instanceof ServerLevel level) {
            if (level.dimension() == PlanetMapper.SHIPYARD_LEVEL) {
                return;
            }
            WorldCollisionManager.markChunkDirty(level, event.getPos());
        }
    }
}
