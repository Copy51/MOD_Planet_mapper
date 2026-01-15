package com.example.planetmapper.physics.structure;

import com.example.planetmapper.Config;
import com.example.planetmapper.physics.NativePhysicsEngine;
import com.example.planetmapper.physics.PhysicsBodyEntityAdapter;
import com.example.planetmapper.physics.PhysicsWorldManager;
import com.example.planetmapper.physics.VoxelShapeOptimizer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

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
    private static final ExecutorService OPTIMIZER_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Physics-Structure-Optimizer");
        t.setDaemon(true);
        return t;
    });

    private StructureBuildManager() {
    }

    public static synchronized boolean enqueue(ServerPlayer player, ServerLevel level, StructureSelection selection) {
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
        OPTIMIZER_EXECUTOR.shutdown();
        TASKS.clear();
        TASKS_BY_OWNER.clear();
    }

    private static void startOptimization(ServerLevel level, StructureBuildTask task) {
        task.state = BuildState.OPTIMIZING;
        task.sendToOwner(level, "Optimizing " + task.solidBlockCount + " blocks...");

        CompletableFuture
                .supplyAsync(() -> {
                    if (task.solidBlocks.isEmpty()) {
                        return List.<AABB>of();
                    }
                    return VoxelShapeOptimizer.optimize(task.solidBlocks);
                }, OPTIMIZER_EXECUTOR)
                .thenAccept(boxes -> level.getServer().execute(() -> finishBuild(level, task, boxes)))
                .exceptionally(ex -> {
                    level.getServer().execute(() -> failBuild(level, task, "Optimization failed: " + ex.getMessage()));
                    return null;
                });
    }

    private static void finishBuild(ServerLevel level, StructureBuildTask task, List<AABB> boxes) {
        if (boxes.isEmpty()) {
            failBuild(level, task, "Selection contains no collidable blocks.");
            return;
        }

        NativePhysicsEngine engine = PhysicsWorldManager.getEngine();
        if (engine == null) {
            failBuild(level, task, "Physics engine is not initialized.");
            return;
        }

        float mass = Math.max(1.0f, (float) task.solidBlockCount);
        long bodyId = engine.createRigidBody(boxes, mass);
        if (bodyId <= 0) {
            failBuild(level, task, "Failed to create physics body.");
            return;
        }

        BlockPos min = task.min;
        BlockPos max = task.max;
        double centerX = (min.getX() + max.getX() + 1) / 2.0;
        double centerY = (min.getY() + max.getY() + 1) / 2.0;
        double centerZ = (min.getZ() + max.getZ() + 1) / 2.0;

        Display.ItemDisplay display = EntityType.ITEM_DISPLAY.create(level);
        if (display != null) {
            display.setPos(centerX, centerY, centerZ);
            display.getSlot(0).set(new ItemStack(Blocks.STONE));
            level.addFreshEntity(display);
            PhysicsWorldManager.registerEntity(new PhysicsBodyEntityAdapter(display, bodyId));
        }

        task.solidBlocks.clear();
        task.state = BuildState.DONE;
        task.sendToOwner(level, "Structure physics created! Body ID: " + bodyId + ", blocks: " + task.solidBlockCount);
    }

    private static void failBuild(ServerLevel level, StructureBuildTask task, String reason) {
        task.state = BuildState.FAILED;
        task.sendToOwner(level, reason);
        task.solidBlocks.clear();
    }

    private enum BuildState {
        SCANNING,
        OPTIMIZING,
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
        private long processedVolume;
        private long solidBlockCount;
        private int currentX;
        private int currentY;
        private int currentZ;
        private int lastPercent = -1;
        private boolean warnedChunkMissing = false;
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
            if (state != BuildState.SCANNING || level.dimension() != dimension) {
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
                if (!state.isAir() && !state.getCollisionShape(level, cursor).isEmpty()) {
                    solidBlocks.add(cursor.immutable());
                    solidBlockCount++;
                    level.setBlock(cursor, Blocks.AIR.defaultBlockState(),
                            Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);
                }

                processedThisTick++;
                processedVolume++;
                advanceCursor();
            }

            reportProgress(level);

            if (currentY > max.getY()) {
                startOptimization(level, this);
            }
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

        private boolean isDone() {
            return state == BuildState.DONE || state == BuildState.FAILED;
        }
    }
}
