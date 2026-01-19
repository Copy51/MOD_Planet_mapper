package com.example.planetmapper.physics;

import com.example.planetmapper.Config;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WorldCollisionManager {
    private static final Map<ResourceKey<Level>, Long2LongOpenHashMap> CHUNK_BODIES = new HashMap<>();
    private static final Map<ResourceKey<Level>, Long2ObjectOpenHashMap<ChunkBuildTask>> TASKS = new HashMap<>();
    private static final Map<ResourceKey<Level>, Long2LongOpenHashMap> DIRTY_CHUNKS = new HashMap<>();
    private static final Map<ResourceKey<Level>, LongOpenHashSet> PRIORITY_CHUNKS = new HashMap<>();
    private static final Map<ResourceKey<Level>, ServerLevel> LEVEL_IDENTITY = new HashMap<>();
    private static final Object EXECUTOR_LOCK = new Object();
    private static ExecutorService optimizerExecutor = createExecutor();
    private static volatile boolean acceptingTasks = true;

    private WorldCollisionManager() {
    }

    public static void onChunkLoad(ServerLevel level, ChunkAccess chunk) {
        if (!acceptingTasks) {
            return;
        }
        if (!PhysicsWorldManager.isNativeAvailable()) {
            return;
        }
        ensureLevelIdentity(level);
        scheduleChunkBuild(level, chunk.getPos(), false, false);
    }

    public static void onChunkUnload(ServerLevel level, ChunkAccess chunk) {
        if (!acceptingTasks) {
            return;
        }
        if (!PhysicsWorldManager.isNativeAvailable()) {
            return;
        }
        ensureLevelIdentity(level);
        long key = chunk.getPos().toLong();

        Long2ObjectOpenHashMap<ChunkBuildTask> tasks = TASKS.get(level.dimension());
        if (tasks != null) {
            ChunkBuildTask task = tasks.remove(key);
            if (task != null) {
                task.cancel();
            }
        }

        Long2LongOpenHashMap dirty = DIRTY_CHUNKS.get(level.dimension());
        if (dirty != null) {
            dirty.remove(key);
        }

        LongOpenHashSet priority = PRIORITY_CHUNKS.get(level.dimension());
        if (priority != null) {
            priority.remove(key);
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
        if (!acceptingTasks) {
            return;
        }
        if (!PhysicsWorldManager.isNativeAvailable()) {
            return;
        }
        ensureLevelIdentity(level);
        Long2ObjectOpenHashMap<ChunkBuildTask> tasks = TASKS.computeIfAbsent(level.dimension(), d -> new Long2ObjectOpenHashMap<>());
        processDirtyChunks(level, tasks);

        int budget = Config.PHYSICS_COLLISION_BLOCKS_PER_TICK.get();
        LongOpenHashSet priority = PRIORITY_CHUNKS.computeIfAbsent(level.dimension(), d -> new LongOpenHashSet());

        if (!priority.isEmpty()) {
            LongIterator priorityIterator = priority.iterator();
            while (priorityIterator.hasNext() && budget > 0) {
                long key = priorityIterator.nextLong();
                ChunkBuildTask task = tasks.get(key);
                if (task == null) {
                    priorityIterator.remove();
                    continue;
                }
                int used = task.scan(level, budget);
                budget -= used;
                if (task.isDone()) {
                    tasks.remove(key);
                    priorityIterator.remove();
                }
            }
        }

        if (budget <= 0) {
            return;
        }

        ObjectIterator<Long2ObjectMap.Entry<ChunkBuildTask>> iterator = tasks.long2ObjectEntrySet().iterator();
        while (iterator.hasNext() && budget > 0) {
            Long2ObjectMap.Entry<ChunkBuildTask> entry = iterator.next();
            long key = entry.getLongKey();
            if (priority.contains(key)) {
                continue;
            }
            ChunkBuildTask task = entry.getValue();
            int used = task.scan(level, budget);
            budget -= used;
            if (task.isDone()) {
                iterator.remove();
            }
        }
    }

    public static void shutdown() {
        acceptingTasks = false;
        ExecutorService executorToShutdown;
        synchronized (EXECUTOR_LOCK) {
            executorToShutdown = optimizerExecutor;
            optimizerExecutor = null;
        }
        if (executorToShutdown != null) {
            executorToShutdown.shutdownNow();
        }
        TASKS.clear();
        CHUNK_BODIES.clear();
        DIRTY_CHUNKS.clear();
        PRIORITY_CHUNKS.clear();
        LEVEL_IDENTITY.clear();
    }

    public static void reset() {
        acceptingTasks = true;
        getExecutor();
    }

    public static void markChunkDirty(ServerLevel level, BlockPos pos) {
        markChunkDirty(level, new ChunkPos(pos));
    }

    public static void markChunkDirty(ServerLevel level, ChunkPos chunkPos) {
        if (!acceptingTasks || !PhysicsWorldManager.isNativeAvailable()) {
            return;
        }
        ensureLevelIdentity(level);
        long key = chunkPos.toLong();
        long dueTime = level.getGameTime() + Config.PHYSICS_COLLISION_REBUILD_DELAY_TICKS.get();
        markChunkDirty(level, key, dueTime, false);
    }

    public static void markChunkDirtyNow(ServerLevel level, ChunkPos chunkPos) {
        if (!acceptingTasks || !PhysicsWorldManager.isNativeAvailable()) {
            return;
        }
        ensureLevelIdentity(level);
        long key = chunkPos.toLong();
        long dueTime = level.getGameTime();
        markChunkDirty(level, key, dueTime, true);
    }

    public static boolean isChunkDirty(ServerLevel level, ChunkPos chunkPos) {
        ensureLevelIdentity(level);
        Long2LongOpenHashMap dirty = DIRTY_CHUNKS.get(level.dimension());
        return dirty != null && dirty.containsKey(chunkPos.toLong());
    }

    public static boolean isChunkColliderReady(ServerLevel level, ChunkPos chunkPos) {
        ensureLevelIdentity(level);
        Long2LongOpenHashMap bodies = CHUNK_BODIES.get(level.dimension());
        return bodies != null && bodies.containsKey(chunkPos.toLong());
    }

    public static void ensureChunkCollider(ServerLevel level, ChunkPos chunkPos) {
        ensureLevelIdentity(level);
        scheduleChunkBuild(level, chunkPos, false, true);
    }

    private static ExecutorService getExecutor() {
        synchronized (EXECUTOR_LOCK) {
            if (optimizerExecutor == null || optimizerExecutor.isShutdown() || optimizerExecutor.isTerminated()) {
                optimizerExecutor = createExecutor();
            }
            return optimizerExecutor;
        }
    }

    private static ExecutorService createExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Physics-Chunk-Optimizer");
            t.setDaemon(true);
            return t;
        });
    }

    private static void ensureLevelIdentity(ServerLevel level) {
        ResourceKey<Level> dimension = level.dimension();
        ServerLevel current = LEVEL_IDENTITY.get(dimension);
        if (current == level) {
            return;
        }
        clearDimension(level, dimension);
        LEVEL_IDENTITY.put(dimension, level);
    }

    private static void clearDimension(ServerLevel level, ResourceKey<Level> dimension) {
        Long2LongOpenHashMap bodies = CHUNK_BODIES.remove(dimension);
        if (bodies != null && !bodies.isEmpty()) {
            NativePhysicsEngine engine = PhysicsWorldManager.getEngine();
            if (engine != null) {
                LongIterator iterator = bodies.values().iterator();
                while (iterator.hasNext()) {
                    engine.removeBody(iterator.nextLong());
                }
            }
        }
        TASKS.remove(dimension);
        DIRTY_CHUNKS.remove(dimension);
        PRIORITY_CHUNKS.remove(dimension);
    }

    private static void markChunkDirty(ServerLevel level, long key, long dueTime, boolean priority) {
        Long2LongOpenHashMap dirty = DIRTY_CHUNKS.computeIfAbsent(level.dimension(), d -> new Long2LongOpenHashMap());
        dirty.put(key, dueTime);
        if (priority) {
            markPriority(level, key);
        }
    }

    private static void startOptimization(ServerLevel level, ChunkBuildTask task) {
        task.state = BuildState.OPTIMIZING;
        if (!acceptingTasks) {
            task.state = BuildState.FAILED;
            return;
        }
        CompletableFuture
                .supplyAsync(() -> {
                    if (task.solidBlocks.isEmpty()) {
                        return List.<AABB>of();
                    }
                    return VoxelShapeOptimizer.optimizeLongSet(task.solidBlocks);
                }, getExecutor())
                .thenAccept(boxes -> level.getServer().execute(() -> finishBuild(level, task, boxes)))
                .exceptionally(ex -> {
                    task.state = BuildState.FAILED;
                    return null;
                });
    }

    private static void finishBuild(ServerLevel level, ChunkBuildTask task, List<AABB> boxes) {
        if (!acceptingTasks) {
            task.state = BuildState.FAILED;
            task.solidBlocks.clear();
            return;
        }
        if (task.cancelled) {
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

        long key = task.chunkPos.toLong();
        Long2LongOpenHashMap bodies = CHUNK_BODIES.computeIfAbsent(level.dimension(), d -> new Long2LongOpenHashMap());
        long existingBody = bodies.containsKey(key) ? bodies.get(key) : 0L;

        if (boxes.isEmpty()) {
            if (existingBody != 0L) {
                engine.removeBody(existingBody);
                bodies.remove(key);
            }
            task.solidBlocks.clear();
            task.state = BuildState.DONE;
            AABB region = chunkRegion(level, task.chunkPos);
            PhysicsColliderManager.activateBodiesInRegion(level, region);
            return;
        }

        long bodyId = engine.createStaticBody(boxes);
        if (bodyId <= 0) {
            task.state = BuildState.FAILED;
            task.solidBlocks.clear();
            return;
        }

        if (existingBody != 0L) {
            engine.removeBody(existingBody);
        }
        bodies.put(key, bodyId);

        task.solidBlocks.clear();
        task.state = BuildState.DONE;
        AABB region = chunkRegion(level, task.chunkPos);
        PhysicsColliderManager.activateBodiesInRegion(level, region);
    }

    private static void scheduleChunkBuild(ServerLevel level, ChunkPos chunkPos, boolean force, boolean priority) {
        if (!acceptingTasks || !PhysicsWorldManager.isNativeAvailable()) {
            return;
        }
        long key = chunkPos.toLong();
        Long2ObjectOpenHashMap<ChunkBuildTask> tasks = TASKS.computeIfAbsent(level.dimension(), d -> new Long2ObjectOpenHashMap<>());
        if (tasks.containsKey(key)) {
            if (priority) {
                markPriority(level, key);
            }
            return;
        }

        if (!force) {
            Long2LongOpenHashMap bodies = CHUNK_BODIES.get(level.dimension());
            if (bodies != null && bodies.containsKey(key)) {
                return;
            }
        }

        ChunkBuildTask task = new ChunkBuildTask(chunkPos, level.getMinBuildHeight(), level.getMaxBuildHeight() - 1);
        tasks.put(key, task);
        if (priority) {
            markPriority(level, key);
        }
    }

    private static void markPriority(ServerLevel level, long key) {
        LongOpenHashSet priority = PRIORITY_CHUNKS.computeIfAbsent(level.dimension(), d -> new LongOpenHashSet());
        priority.add(key);
    }

    private static void processDirtyChunks(ServerLevel level, Long2ObjectOpenHashMap<ChunkBuildTask> tasks) {
        Long2LongOpenHashMap dirty = DIRTY_CHUNKS.get(level.dimension());
        if (dirty == null || dirty.isEmpty()) {
            return;
        }

        long now = level.getGameTime();
        ObjectIterator<Long2LongMap.Entry> iterator = dirty.long2LongEntrySet().iterator();
        int scheduled = 0;
        while (iterator.hasNext()) {
            Long2LongMap.Entry entry = iterator.next();
            long key = entry.getLongKey();
            long due = entry.getLongValue();
            if (due > now) {
                continue;
            }
            if (tasks.containsKey(key)) {
                markPriority(level, key);
                continue;
            }
            ChunkPos chunkPos = new ChunkPos(key);
            if (!level.hasChunk(chunkPos.x, chunkPos.z)) {
                iterator.remove();
                continue;
            }
            scheduleChunkBuild(level, chunkPos, true, true);
            iterator.remove();
            scheduled++;
            if (scheduled >= 4) {
                break;
            }
        }
    }

    private static AABB chunkRegion(ServerLevel level, ChunkPos chunkPos) {
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();
        return new AABB(
                chunkPos.getMinBlockX(), minY, chunkPos.getMinBlockZ(),
                chunkPos.getMaxBlockX() + 1, maxY, chunkPos.getMaxBlockZ() + 1
        );
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
        private final LongOpenHashSet solidBlocks = new LongOpenHashSet();
        private int currentX;
        private int currentY;
        private int currentZ;
        private int currentSectionIndex = Integer.MIN_VALUE;
        private boolean currentSectionEmpty = false;
        private int nextSectionStartY = 0;
        private boolean cancelled = false;
        private BuildState state = BuildState.SCANNING;

        private ChunkBuildTask(ChunkPos chunkPos, int minY, int maxY) {
            this.chunkPos = chunkPos;
            this.minY = minY;
            this.maxY = maxY;
            this.currentX = chunkPos.getMinBlockX();
            this.currentZ = chunkPos.getMinBlockZ();
            this.currentY = minY;
            this.currentSectionIndex = Integer.MIN_VALUE;
            this.nextSectionStartY = minY;
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

            ChunkAccess chunk = level.getChunk(chunkPos.x, chunkPos.z);
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            int processed = 0;
            int maxX = chunkPos.getMaxBlockX();
            int maxZ = chunkPos.getMaxBlockZ();

            while (processed < budget && currentY <= maxY) {
                int sectionIndex = chunk.getSectionIndex(currentY);
                if (sectionIndex != currentSectionIndex) {
                    currentSectionIndex = sectionIndex;
                    LevelChunkSection section = chunk.getSection(sectionIndex);
                    currentSectionEmpty = section.hasOnlyAir();
                    int sectionY = chunk.getMinSection() + sectionIndex;
                    nextSectionStartY = SectionPos.sectionToBlockCoord(sectionY + 1);
                }

                if (currentSectionEmpty) {
                    int nextY = Math.max(nextSectionStartY, currentY + 1);
                    currentY = nextY;
                    currentX = chunkPos.getMinBlockX();
                    currentZ = chunkPos.getMinBlockZ();
                    currentSectionIndex = Integer.MIN_VALUE;
                    continue;
                }

                cursor.set(currentX, currentY, currentZ);
                BlockState state = chunk.getBlockState(cursor);
                if (!state.isAir() && !state.getCollisionShape(level, cursor).isEmpty()) {
                    solidBlocks.add(BlockPos.asLong(cursor.getX(), cursor.getY(), cursor.getZ()));
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
