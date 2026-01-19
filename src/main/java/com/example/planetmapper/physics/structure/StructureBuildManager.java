package com.example.planetmapper.physics.structure;

import com.example.planetmapper.Config;
import com.example.planetmapper.physics.NativePhysicsEngine;
import com.example.planetmapper.physics.PhysicsColliderManager;
import com.example.planetmapper.physics.PhysicsWorldManager;
import com.example.planetmapper.physics.WorldCollisionManager;
import com.example.planetmapper.physics.VoxelShapeOptimizer;
import com.example.planetmapper.entity.PhysicsStructureEntity;
import com.example.planetmapper.shipyard.ShipyardManager;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class StructureBuildManager {
    private static final Map<ResourceKey<Level>, List<StructureBuildTask>> TASKS = new HashMap<>();
    private static final Map<UUID, StructureBuildTask> TASKS_BY_OWNER = new HashMap<>();
    private static final Object EXECUTOR_LOCK = new Object();
    private static ExecutorService optimizerExecutor = createExecutor();
    private static volatile boolean acceptingTasks = true;

    private StructureBuildManager() {
    }

    public static synchronized boolean enqueue(ServerPlayer player, ServerLevel level, StructureSelection selection) {
        if (!acceptingTasks) {
            player.sendSystemMessage(Component.literal("Physics system is shutting down."));
            return false;
        }
        if (!PhysicsWorldManager.isNativeAvailable()) {
            player.sendSystemMessage(Component.literal("Native physics engine is not available."));
            return false;
        }

        if (TASKS_BY_OWNER.containsKey(player.getUUID())) {
            player.sendSystemMessage(Component.literal("You already have a structure build in progress."));
            return false;
        }

        long volume = selection.getVolume();
        int maxVolume = Config.PHYSICS_MAX_SELECTION_VOLUME.get();
        if (volume > maxVolume) {
            player.sendSystemMessage(Component.literal("Selection volume too large: " + volume + " (max " + maxVolume + ")"));
            return false;
        }

        BlockPos min = selection.getMin();
        BlockPos max = selection.getMax();
        if (min == null || max == null) {
            player.sendSystemMessage(Component.literal("Selection is incomplete."));
            return false;
        }

        StructureBuildTask task = new StructureBuildTask(player.getUUID(), level.dimension(), min, max, volume);
        TASKS.computeIfAbsent(level.dimension(), key -> new ArrayList<>()).add(task);
        TASKS_BY_OWNER.put(player.getUUID(), task);
        player.sendSystemMessage(Component.literal("Structure build started. Volume: " + volume));
        return true;
    }

    public static synchronized void tick(ServerLevel level) {
        if (!acceptingTasks) {
            return;
        }
        List<StructureBuildTask> tasks = TASKS.get(level.dimension());
        if (tasks == null || tasks.isEmpty()) {
            return;
        }

        int blocksPerTick = Config.PHYSICS_BLOCKS_PER_TICK.get();
        Iterator<StructureBuildTask> iterator = tasks.iterator();
        while (iterator.hasNext()) {
            StructureBuildTask task = iterator.next();
            task.tick(level, blocksPerTick);
            if (task.isDone()) {
                iterator.remove();
                TASKS_BY_OWNER.remove(task.ownerId);
            }
        }
    }

    public static synchronized void shutdown() {
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
        TASKS_BY_OWNER.clear();
    }

    public static synchronized void reset() {
        acceptingTasks = true;
        getExecutor();
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
            Thread t = new Thread(r, "Physics-Structure-Optimizer");
            t.setDaemon(true);
            return t;
        });
    }

    private static void startOptimization(ServerLevel level, StructureBuildTask task) {
        task.state = BuildState.OPTIMIZING;
        if (!acceptingTasks) {
            task.state = BuildState.FAILED;
            return;
        }
        task.sendToOwner(level, "Optimizing " + task.solidBlockCount + " blocks...");

        CompletableFuture
                .supplyAsync(() -> {
                    if (task.solidBlocks.isEmpty()) {
                        return List.<AABB>of();
                    }
                    return VoxelShapeOptimizer.optimize(task.solidBlocks);
                }, getExecutor())
                .thenAccept(boxes -> level.getServer().execute(() -> finishBuild(level, task, boxes)))
                .exceptionally(ex -> {
                    level.getServer().execute(() -> failBuild(level, task, "Optimization failed: " + ex.getMessage()));
                    return null;
                });
    }

    private static void finishBuild(ServerLevel level, StructureBuildTask task, List<AABB> boxes) {
        if (!acceptingTasks) {
            task.state = BuildState.FAILED;
            task.solidBlocks.clear();
            return;
        }
        if (boxes.isEmpty()) {
            failBuild(level, task, "Selection contains no collidable blocks.");
            return;
        }

        if (!areChunkCollidersReady(level, task.min, task.max)) {
            task.pendingBoxes = boxes;
            task.state = BuildState.WAITING_COLLIDERS;
            task.sendToOwner(level, "Waiting for chunk collision data...");
            return;
        }

        NativePhysicsEngine engine = PhysicsWorldManager.getEngine();
        if (engine == null) {
            failBuild(level, task, "Physics engine is not initialized.");
            return;
        }

        StructurePhysicsProperties physicsProperties = StructurePhysicsProperties.fromBlocks(task.blocks);
        StructurePhysicsProperties.MaterialSummary material = physicsProperties.snapshot();
        float mass = Math.max(1.0f, material.mass());
        long bodyId = engine.createRigidBody(boxes, material);
        if (bodyId <= 0) {
            failBuild(level, task, "Failed to create physics body.");
            return;
        }

        float[] stateBuffer = new float[13];
        engine.getBodyState(bodyId, stateBuffer);
        Vector3f bodyPos = new Vector3f(stateBuffer[0], stateBuffer[1], stateBuffer[2]);
        org.joml.Quaternionf bodyRot = new org.joml.Quaternionf(stateBuffer[3], stateBuffer[4], stateBuffer[5], stateBuffer[6]);

        PhysicsColliderManager.registerAndSyncBody(level, bodyId, boxes, bodyPos);
        List<AABB> bodyLocalBoxes = new ArrayList<>(boxes.size());
        for (AABB box : boxes) {
            bodyLocalBoxes.add(new AABB(
                    box.minX - bodyPos.x, box.minY - bodyPos.y, box.minZ - bodyPos.z,
                    box.maxX - bodyPos.x, box.maxY - bodyPos.y, box.maxZ - bodyPos.z
            ));
        }
        PhysicsColliderManager.updateAndSyncBody(level, bodyId, bodyLocalBoxes);
        PhysicsColliderManager.updateBodyTransform(bodyId, bodyPos.x, bodyPos.y, bodyPos.z, bodyRot);

        Vector3f originOffset = new Vector3f(
                (float) (task.min.getX() - bodyPos.x),
                (float) (task.min.getY() - bodyPos.y),
                (float) (task.min.getZ() - bodyPos.z)
        );
        PhysicsStructure structure = new PhysicsStructure(level.dimension(), bodyId, task.min, originOffset, task.blocks, task.collidableBlocks, physicsProperties);
        StructurePhysicsManager.registerStructure(structure);

        Map<BlockPos, BlockState> renderBlocks = new HashMap<>(task.blocks.size());
        Map<BlockPos, net.minecraft.nbt.CompoundTag> renderBlockEntities = new HashMap<>();
        task.blocks.long2ObjectEntrySet().forEach(entry -> {
            BlockPos localPos = BlockPos.of(entry.getLongKey());
            StructureBlockData data = entry.getValue();
            renderBlocks.put(localPos, data.state());
            if (data.blockEntityTag() != null && !data.blockEntityTag().isEmpty()) {
                renderBlockEntities.put(localPos, data.blockEntityTag());
            }
        });

        PhysicsStructureEntity physicsEntity = com.example.planetmapper.entity.ModEntities.PHYSICS_STRUCTURE.get().create(level);
        if (physicsEntity != null) {
            physicsEntity.setPos(bodyPos.x, bodyPos.y - physicsEntity.getBodyYOffset(), bodyPos.z);
            physicsEntity.setBodyId(bodyId);
            physicsEntity.setOriginOffset(originOffset);
            physicsEntity.setStructure(renderBlocks, renderBlockEntities);
            level.addFreshEntity(physicsEntity);
            PhysicsWorldManager.registerEntity(physicsEntity);
            structure.setEntityId(physicsEntity.getId());
        }

        ServerLevel shipyard = ShipyardManager.getShipyardLevel(level.getServer());
        if (shipyard != null && physicsEntity != null) {
            int sizeX = task.max.getX() - task.min.getX() + 1;
            int sizeY = task.max.getY() - task.min.getY() + 1;
            int sizeZ = task.max.getZ() - task.min.getZ() + 1;
            ShipyardManager.ShipyardRegion region = ShipyardManager.ensureRegion(shipyard, physicsEntity.getUUID(), bodyId, sizeX, sizeY, sizeZ);
            ShipyardManager.placeBlocks(shipyard, region, task.blocks);
            ShipyardManager.queueNeighborUpdates(region, task.blocks);
        } else {
            task.sendToOwner(level, "Shipyard dimension not available. Functional blocks will not tick.");
        }

        task.solidBlocks.clear();
        task.state = BuildState.DONE;
        task.sendToOwner(level, "Structure physics created! Body ID: " + bodyId + ", blocks: " + task.totalBlockCount);
    }

    private static void failBuild(ServerLevel level, StructureBuildTask task, String reason) {
        task.state = BuildState.FAILED;
        task.sendToOwner(level, reason);
        task.solidBlocks.clear();
        task.blocks.clear();
        task.collidableBlocks.clear();
    }

    private static boolean areChunkCollidersReady(ServerLevel level, BlockPos min, BlockPos max) {
        int minChunkX = min.getX() >> 4;
        int minChunkZ = min.getZ() >> 4;
        int maxChunkX = max.getX() >> 4;
        int maxChunkZ = max.getZ() >> 4;
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                if (!level.hasChunk(cx, cz)) {
                    return false;
                }
                ChunkPos chunkPos = new ChunkPos(cx, cz);
                if (WorldCollisionManager.isChunkDirty(level, chunkPos)) {
                    WorldCollisionManager.markChunkDirtyNow(level, chunkPos);
                    return false;
                }
                if (!WorldCollisionManager.isChunkColliderReady(level, chunkPos)) {
                    WorldCollisionManager.ensureChunkCollider(level, chunkPos);
                    return false;
                }
            }
        }
        return true;
    }

    private enum BuildState {
        SCANNING,
        OPTIMIZING,
        WAITING_COLLIDERS,
        DONE,
        FAILED
    }

    private static class StructureBuildTask {
        private final UUID ownerId;
        private final ResourceKey<Level> dimension;
        private final BlockPos min;
        private final BlockPos max;
        private final long totalVolume;
        private final Set<BlockPos> solidBlocks = new HashSet<>();
        private final Long2ObjectOpenHashMap<StructureBlockData> blocks = new Long2ObjectOpenHashMap<>();
        private final LongOpenHashSet collidableBlocks = new LongOpenHashSet();
        private final LongOpenHashSet dirtyChunks = new LongOpenHashSet();
        private long processedVolume;
        private long solidBlockCount;
        private long totalBlockCount;
        private int currentX;
        private int currentY;
        private int currentZ;
        private int lastPercent = -1;
        private boolean warnedChunkMissing = false;
        private List<AABB> pendingBoxes;
        private BuildState state = BuildState.SCANNING;

        private StructureBuildTask(UUID ownerId, ResourceKey<Level> dimension, BlockPos min, BlockPos max, long totalVolume) {
            this.ownerId = ownerId;
            this.dimension = dimension;
            this.min = min;
            this.max = max;
            this.totalVolume = totalVolume;
            this.currentX = min.getX();
            this.currentY = min.getY();
            this.currentZ = min.getZ();
        }

        private void tick(ServerLevel level, int blocksPerTick) {
            if (level.dimension() != dimension) {
                return;
            }
            if (state == BuildState.WAITING_COLLIDERS) {
                tryFinalize(level);
                return;
            }
            if (state != BuildState.SCANNING) {
                return;
            }

            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            int processedThisTick = 0;

            while (processedThisTick < blocksPerTick && currentY <= max.getY()) {
                cursor.set(currentX, currentY, currentZ);
                int chunkX = cursor.getX() >> 4;
                int chunkZ = cursor.getZ() >> 4;
                if (!level.hasChunk(chunkX, chunkZ)) {
                    if (!warnedChunkMissing) {
                        sendToOwner(level, "Waiting for chunks to load...");
                        warnedChunkMissing = true;
                    }
                    return;
                }

                BlockState state = level.getBlockState(cursor);
                if (!state.isAir()) {
                    boolean collidable = !state.getCollisionShape(level, cursor).isEmpty();
                    if (collidable) {
                        solidBlocks.add(cursor.immutable());
                        solidBlockCount++;
                        collidableBlocks.add(BlockPos.asLong(cursor.getX() - min.getX(), cursor.getY() - min.getY(), cursor.getZ() - min.getZ()));
                    }

                    BlockEntity blockEntity = level.getBlockEntity(cursor);
                    blocks.put(BlockPos.asLong(cursor.getX() - min.getX(), cursor.getY() - min.getY(), cursor.getZ() - min.getZ()),
                            new StructureBlockData(state, blockEntity != null ? blockEntity.saveWithId(level.registryAccess()) : null, collidable));
                    totalBlockCount++;

                    level.setBlock(cursor, Blocks.AIR.defaultBlockState(),
                            Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);

                    long chunkKey = ChunkPos.asLong(cursor.getX() >> 4, cursor.getZ() >> 4);
                    if (dirtyChunks.add(chunkKey)) {
                        WorldCollisionManager.markChunkDirtyNow(level, new ChunkPos(chunkKey));
                    }
                }

                processedThisTick++;
                processedVolume++;
                advanceCursor();
            }

            reportProgress(level);

            if (currentY > max.getY()) {
                flushChunkColliders(level);
                startOptimization(level, this);
            }
        }

        private void tryFinalize(ServerLevel level) {
            if (pendingBoxes == null) {
                return;
            }
            if (!areChunkCollidersReady(level, min, max)) {
                return;
            }
            List<AABB> boxes = pendingBoxes;
            pendingBoxes = null;
            finishBuild(level, this, boxes);
        }

        private void advanceCursor() {
            currentX++;
            if (currentX > max.getX()) {
                currentX = min.getX();
                currentZ++;
                if (currentZ > max.getZ()) {
                    currentZ = min.getZ();
                    currentY++;
                }
            }
        }

        private void reportProgress(ServerLevel level) {
            if (totalVolume <= 0) {
                return;
            }
            int percent = (int) Math.min(100L, (processedVolume * 100L) / totalVolume);
            if (percent >= lastPercent + 10) {
                lastPercent = percent;
                sendToOwner(level, "Selection scan: " + percent + "%");
            }
        }

        private void sendToOwner(ServerLevel level, String message) {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(ownerId);
            if (player != null) {
                player.sendSystemMessage(Component.literal(message));
            }
        }

        private void flushChunkColliders(ServerLevel level) {
            if (dirtyChunks.isEmpty()) {
                return;
            }
            LongIterator iterator = dirtyChunks.iterator();
            while (iterator.hasNext()) {
                long key = iterator.nextLong();
                WorldCollisionManager.markChunkDirtyNow(level, new ChunkPos(key));
            }
        }

        private boolean isDone() {
            return state == BuildState.DONE || state == BuildState.FAILED;
        }
    }
}
