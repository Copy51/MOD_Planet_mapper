package com.example.planetmapper.physics;

import com.example.planetmapper.Config;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkPos;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WorldCollisionManager {
    private static final Map<ResourceKey<Level>, Long2LongOpenHashMap> CHUNK_BODIES = new HashMap<>();
    private static final Map<ResourceKey<Level>, Long2ObjectOpenHashMap<ChunkBuildTask>> TASKS = new HashMap<>();
    private static final ExecutorService OPTIMIZER_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Physics-Chunk-Optimizer");
        t.setDaemon(true);
        return t;
    });

    private WorldCollisionManager() {
    }

    public static void onChunkLoad(ServerLevel level, ChunkAccess chunk) {
        if (!PhysicsWorldManager.isNativeAvailable()) {
            return;
        }
        long key = chunk.getPos().toLong();
        Long2LongOpenHashMap bodies = CHUNK_BODIES.computeIfAbsent(level.dimension(), d -> new Long2LongOpenHashMap());
        if (bodies.containsKey(key)) {
            return;
        }

        Long2ObjectOpenHashMap<ChunkBuildTask> tasks = TASKS.computeIfAbsent(level.dimension(), d -> new Long2ObjectOpenHashMap<>());
        if (tasks.containsKey(key)) {
            return;
        }

        ChunkBuildTask task = new ChunkBuildTask(chunk.getPos(), level.getMinBuildHeight(), level.getMaxBuildHeight() - 1);
        tasks.put(key, task);
    }

    public static void onChunkUnload(ServerLevel level, ChunkAccess chunk) {
        if (!PhysicsWorldManager.isNativeAvailable()) {
            return;
        }
        long key = chunk.getPos().toLong();

        Long2ObjectOpenHashMap<ChunkBuildTask> tasks = TASKS.get(level.dimension());
        if (tasks != null) {
            ChunkBuildTask task = tasks.remove(key);
            if (task != null) {
                task.cancel();
            }
        }

        Long2LongOpenHashMap bodies = CHUNK_BODIES.get(level.dimension());
        if (bodies != null && bodies.containsKey(key)) {
            long bodyId = bodies.remove(key);
            NativePhysicsEngine engine = PhysicsWorldManager.getEngine();
            if (engine != null) {
                engine.removeBody(bodyId);
            }
        }
    }

    public static void tick(ServerLevel level) {
        if (!PhysicsWorldManager.isNativeAvailable()) {
            return;
        }
        Long2ObjectOpenHashMap<ChunkBuildTask> tasks = TASKS.get(level.dimension());
        if (tasks == null || tasks.isEmpty()) {
            return;
        }

        int budget = Config.PHYSICS_BLOCKS_PER_TICK.get();
        ObjectIterator<Long2ObjectMap.Entry<ChunkBuildTask>> iterator = tasks.long2ObjectEntrySet().iterator();
        while (iterator.hasNext() && budget > 0) {
            Long2ObjectMap.Entry<ChunkBuildTask> entry = iterator.next();
            ChunkBuildTask task = entry.getValue();
            int used = task.scan(level, budget);
            budget -= used;
            if (task.isDone()) {
                iterator.remove();
            }
        }
    }

    public static void shutdown() {
        OPTIMIZER_EXECUTOR.shutdown();
        TASKS.clear();
        CHUNK_BODIES.clear();
    }

    private static void startOptimization(ServerLevel level, ChunkBuildTask task) {
        task.state = BuildState.OPTIMIZING;
        CompletableFuture
                .supplyAsync(() -> {
                    if (task.solidBlocks.isEmpty()) {
                        return List.<AABB>of();
                    }
                    return VoxelShapeOptimizer.optimize(task.solidBlocks);
                }, OPTIMIZER_EXECUTOR)
                .thenAccept(boxes -> level.getServer().execute(() -> finishBuild(level, task, boxes)))
                .exceptionally(ex -> {
                    task.state = BuildState.FAILED;
                    return null;
                });
    }

    private static void finishBuild(ServerLevel level, ChunkBuildTask task, List<AABB> boxes) {
        if (task.cancelled || boxes.isEmpty()) {
            task.state = BuildState.FAILED;
            task.solidBlocks.clear();
            return;
        }

        if (!level.hasChunk(task.chunkPos.x, task.chunkPos.z)) {
            task.state = BuildState.FAILED;
            task.solidBlocks.clear();
            return;
        }

        NativePhysicsEngine engine = PhysicsWorldManager.getEngine();
        if (engine == null) {
            task.state = BuildState.FAILED;
            task.solidBlocks.clear();
            return;
        }

        long bodyId = engine.createStaticBody(boxes);
        if (bodyId <= 0) {
            task.state = BuildState.FAILED;
            task.solidBlocks.clear();
            return;
        }

        Long2LongOpenHashMap bodies = CHUNK_BODIES.computeIfAbsent(level.dimension(), d -> new Long2LongOpenHashMap());
        bodies.put(task.chunkPos.toLong(), bodyId);

        task.solidBlocks.clear();
        task.state = BuildState.DONE;
    }

    private enum BuildState {
        SCANNING,
        OPTIMIZING,
        DONE,
        FAILED
    }

    private static class ChunkBuildTask {
        private final ChunkPos chunkPos;
        private final int minY;
        private final int maxY;
        private final Set<BlockPos> solidBlocks = new HashSet<>();
        private int currentX;
        private int currentY;
        private int currentZ;
        private boolean cancelled = false;
        private BuildState state = BuildState.SCANNING;

        private ChunkBuildTask(ChunkPos chunkPos, int minY, int maxY) {
            this.chunkPos = chunkPos;
            this.minY = minY;
            this.maxY = maxY;
            this.currentX = chunkPos.getMinBlockX();
            this.currentZ = chunkPos.getMinBlockZ();
            this.currentY = minY;
        }

        private int scan(ServerLevel level, int budget) {
            if (state != BuildState.SCANNING || cancelled || budget <= 0) {
                return 0;
            }

            if (!level.hasChunk(chunkPos.x, chunkPos.z)) {
                cancelled = true;
                state = BuildState.FAILED;
                solidBlocks.clear();
                return 0;
            }

            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            int processed = 0;
            int maxX = chunkPos.getMaxBlockX();
            int maxZ = chunkPos.getMaxBlockZ();

            while (processed < budget && currentY <= maxY) {
                cursor.set(currentX, currentY, currentZ);
                BlockState state = level.getBlockState(cursor);
                if (!state.isAir() && !state.getCollisionShape(level, cursor).isEmpty()) {
                    solidBlocks.add(cursor.immutable());
                }

                processed++;
                advanceCursor(maxX, maxZ);
            }

            if (currentY > maxY) {
                startOptimization(level, this);
            }

            return processed;
        }

        private void advanceCursor(int maxX, int maxZ) {
            currentX++;
            if (currentX > maxX) {
                currentX = chunkPos.getMinBlockX();
                currentZ++;
                if (currentZ > maxZ) {
                    currentZ = chunkPos.getMinBlockZ();
                    currentY++;
                }
            }
        }

        private void cancel() {
            cancelled = true;
        }

        private boolean isDone() {
            return state == BuildState.DONE || state == BuildState.FAILED;
        }
    }
}
