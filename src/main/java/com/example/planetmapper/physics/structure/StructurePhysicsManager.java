package com.example.planetmapper.physics.structure;

import com.example.planetmapper.physics.NativePhysicsEngine;
import com.example.planetmapper.physics.PhysicsColliderManager;
import com.example.planetmapper.physics.PhysicsWorldManager;
import com.example.planetmapper.physics.VoxelShapeOptimizer;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import com.example.planetmapper.entity.PhysicsBlockEntity;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class StructurePhysicsManager {
    private static final Map<Long, PhysicsStructure> STRUCTURES = new HashMap<>();
    private static final Map<UUID, MiningState> MINING = new HashMap<>();
    private static final Object EXECUTOR_LOCK = new Object();
    private static ExecutorService optimizerExecutor = createExecutor();
    private static volatile boolean acceptingTasks = true;

    private StructurePhysicsManager() {
    }

    public static void registerStructure(PhysicsStructure structure) {
        if (!acceptingTasks) {
            return;
        }
        STRUCTURES.put(structure.getBodyId(), structure);
    }

    public static void unregisterStructure(long bodyId) {
        STRUCTURES.remove(bodyId);
    }

    public static boolean handleAttack(ServerPlayer player, Entity target) {
        if (!acceptingTasks) {
            return false;
        }
        if (!(target instanceof PhysicsBlockEntity physicsEntity)) {
            return false;
        }
        long bodyId = physicsEntity.getBodyId();
        if (bodyId <= 0) {
            return false;
        }
        PhysicsStructure structure = STRUCTURES.get(bodyId);
        if (structure == null) {
            return false;
        }
        if (structure.getDimension() != player.level().dimension()) {
            return false;
        }

        NativePhysicsEngine engine = PhysicsWorldManager.getEngine();
        if (engine == null) {
            return false;
        }

        StructureHit hit = raycastStructure(player, structure, engine);
        if (hit == null) {
            return true;
        }

        if (player.getAbilities().instabuild) {
            breakBlock(player, structure, hit);
            return true;
        }

        MiningState state = MINING.get(player.getUUID());
        if (state == null || state.bodyId != bodyId || state.blockKey != hit.blockKey) {
            state = new MiningState(bodyId, hit.blockKey, hit.data.state());
            MINING.put(player.getUUID(), state);
        }
        state.lastTick = player.level().getGameTime();
        return true;
    }

    public static void onPlayerTick(ServerPlayer player) {
        if (!acceptingTasks) {
            return;
        }
        MiningState state = MINING.get(player.getUUID());
        if (state == null) {
            return;
        }
        long now = player.level().getGameTime();
        if (now - state.lastTick > 20L) {
            MINING.remove(player.getUUID());
            return;
        }

        PhysicsStructure structure = STRUCTURES.get(state.bodyId);
        if (structure == null || structure.getDimension() != player.level().dimension()) {
            MINING.remove(player.getUUID());
            return;
        }

        NativePhysicsEngine engine = PhysicsWorldManager.getEngine();
        if (engine == null) {
            MINING.remove(player.getUUID());
            return;
        }

        StructureHit hit = raycastStructure(player, structure, engine);
        if (hit == null || hit.blockKey != state.blockKey) {
            MINING.remove(player.getUUID());
            return;
        }

        BlockPos worldPos = hit.worldBlockPos;
        float progress = state.progress + state.state.getDestroyProgress(player, player.level(), worldPos);
        state.progress = progress;
        state.lastTick = now;

        if (progress >= 1.0f) {
            breakBlock(player, structure, hit);
            MINING.remove(player.getUUID());
        }
    }

    public static void onLevelTick(ServerLevel level) {
        if (!acceptingTasks) {
            return;
        }
        if (!PhysicsWorldManager.isNativeAvailable()) {
            return;
        }

        for (PhysicsStructure structure : STRUCTURES.values()) {
            if (structure.getDimension() != level.dimension()) {
                continue;
            }
            if (!structure.isDirty() || structure.isRebuildRunning()) {
                continue;
            }
            startRebuild(level, structure);
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
        STRUCTURES.clear();
        MINING.clear();
    }

    public static void reset() {
        acceptingTasks = true;
        getExecutor();
    }

    private static void startRebuild(ServerLevel level, PhysicsStructure structure) {
        structure.setRebuildRunning(true);
        structure.clearDirty();
        LongOpenHashSet snapshot = structure.snapshotCollidableBlocks();
        CompletableFuture
                .supplyAsync(() -> VoxelShapeOptimizer.optimizeLongSet(snapshot), getExecutor())
                .thenAccept(boxes -> level.getServer().execute(() -> applyRebuild(level, structure, boxes)))
                .exceptionally(ex -> {
                    structure.setRebuildRunning(false);
                    return null;
                });
    }

    private static void applyRebuild(ServerLevel level, PhysicsStructure structure, List<AABB> localBoxes) {
        NativePhysicsEngine engine = PhysicsWorldManager.getEngine();
        if (engine == null) {
            structure.setRebuildRunning(false);
            return;
        }

        List<AABB> bodyLocal = offsetBoxes(localBoxes, structure.getOriginOffset());
        if (bodyLocal.isEmpty()) {
            bodyLocal = List.of(new AABB(
                    structure.getOriginOffset().x - 0.01f,
                    structure.getOriginOffset().y - 0.01f,
                    structure.getOriginOffset().z - 0.01f,
                    structure.getOriginOffset().x + 0.01f,
                    structure.getOriginOffset().y + 0.01f,
                    structure.getOriginOffset().z + 0.01f
            ));
        }

        engine.updateBodyShape(structure.getBodyId(), bodyLocal);
        PhysicsColliderManager.updateAndSyncBody(level, structure.getBodyId(), bodyLocal);
        structure.setRebuildRunning(false);
    }

    private static List<AABB> offsetBoxes(List<AABB> boxes, Vector3f offset) {
        if (boxes.isEmpty()) {
            return List.of();
        }
        List<AABB> result = new ArrayList<>(boxes.size());
        for (AABB box : boxes) {
            result.add(new AABB(
                    box.minX + offset.x, box.minY + offset.y, box.minZ + offset.z,
                    box.maxX + offset.x, box.maxY + offset.y, box.maxZ + offset.z
            ));
        }
        return result;
    }

    private static StructureHit raycastStructure(Player player, PhysicsStructure structure, NativePhysicsEngine engine) {
        float[] state = structure.getStateBuffer();
        engine.getBodyState(structure.getBodyId(), state);
        Vector3f bodyPos = new Vector3f(state[0], state[1], state[2]);
        Quaternionf rotation = new Quaternionf(state[3], state[4], state[5], state[6]);

        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1.0f);
        double reach = player.blockInteractionRange();

        Vector3f originWorld = new Vector3f(structure.getOriginOffset());
        rotation.transform(originWorld);
        originWorld.add(bodyPos);

        Quaternionf inverse = new Quaternionf(rotation).invert();
        Vector3f localOrigin = new Vector3f((float) (eye.x - originWorld.x), (float) (eye.y - originWorld.y), (float) (eye.z - originWorld.z));
        inverse.transform(localOrigin);

        Vector3f localDir = new Vector3f((float) look.x, (float) look.y, (float) look.z);
        if (localDir.lengthSquared() < 1.0E-6f) {
            return null;
        }
        localDir.normalize();
        inverse.transform(localDir);

        BlockPos localHit = raycastLocalBlocks(structure, localOrigin, localDir, reach);
        if (localHit == null) {
            return null;
        }

        StructureBlockData data = structure.getBlock(BlockPos.asLong(localHit.getX(), localHit.getY(), localHit.getZ()));
        if (data == null) {
            return null;
        }

        Vec3 worldCenter = structure.localToWorldCenter(localHit, bodyPos, rotation);
        BlockPos worldBlockPos = BlockPos.containing(worldCenter);
        return new StructureHit(localHit, BlockPos.asLong(localHit.getX(), localHit.getY(), localHit.getZ()), worldCenter, worldBlockPos, data);
    }

    private static BlockPos raycastLocalBlocks(PhysicsStructure structure, Vector3f origin, Vector3f direction, double reach) {
        double ox = origin.x;
        double oy = origin.y;
        double oz = origin.z;

        int x = (int) Math.floor(ox);
        int y = (int) Math.floor(oy);
        int z = (int) Math.floor(oz);

        int stepX = direction.x > 0 ? 1 : (direction.x < 0 ? -1 : 0);
        int stepY = direction.y > 0 ? 1 : (direction.y < 0 ? -1 : 0);
        int stepZ = direction.z > 0 ? 1 : (direction.z < 0 ? -1 : 0);

        double tMaxX = stepX == 0 ? Double.POSITIVE_INFINITY : intBound(ox, direction.x, stepX);
        double tMaxY = stepY == 0 ? Double.POSITIVE_INFINITY : intBound(oy, direction.y, stepY);
        double tMaxZ = stepZ == 0 ? Double.POSITIVE_INFINITY : intBound(oz, direction.z, stepZ);

        double tDeltaX = stepX == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / direction.x);
        double tDeltaY = stepY == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / direction.y);
        double tDeltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / direction.z);

        double t = 0.0;
        while (t <= reach) {
            long key = BlockPos.asLong(x, y, z);
            if (structure.getBlocks().containsKey(key)) {
                return new BlockPos(x, y, z);
            }

            if (tMaxX < tMaxY) {
                if (tMaxX < tMaxZ) {
                    x += stepX;
                    t = tMaxX;
                    tMaxX += tDeltaX;
                } else {
                    z += stepZ;
                    t = tMaxZ;
                    tMaxZ += tDeltaZ;
                }
            } else {
                if (tMaxY < tMaxZ) {
                    y += stepY;
                    t = tMaxY;
                    tMaxY += tDeltaY;
                } else {
                    z += stepZ;
                    t = tMaxZ;
                    tMaxZ += tDeltaZ;
                }
            }
        }

        return null;
    }

    private static double intBound(double s, double ds, int step) {
        if (ds == 0.0) {
            return Double.POSITIVE_INFINITY;
        }
        if (step > 0) {
            return (Math.floor(s + 1.0) - s) / ds;
        }
        return (s - Math.floor(s)) / -ds;
    }

    private static void breakBlock(ServerPlayer player, PhysicsStructure structure, StructureHit hit) {
        StructureBlockData data = structure.removeBlock(hit.blockKey);
        if (data == null) {
            return;
        }

        if (data.collidable()) {
            structure.markDirty();
        }

        ServerLevel level = (ServerLevel) player.level();
        BlockStateDrops drops = collectDrops(level, hit.worldBlockPos, data, player);
        for (ItemStack stack : drops.items()) {
            Block.popResource(level, hit.worldBlockPos, stack);
        }
        if (drops.dropContents() && drops.blockEntity() instanceof net.minecraft.world.Container container) {
            Containers.dropContents(level, hit.worldBlockPos, container);
        }

        level.playSound(null, hit.worldCenter.x, hit.worldCenter.y, hit.worldCenter.z,
                data.state().getSoundType().getBreakSound(), SoundSource.BLOCKS, 1.0f, 1.0f);

        ItemStack tool = player.getMainHandItem();
        if (!tool.isEmpty()) {
            tool.hurtAndBreak(1, player, EquipmentSlot.MAINHAND);
        }
    }

    private static BlockStateDrops collectDrops(ServerLevel level, BlockPos worldPos, StructureBlockData data, Player player) {
        BlockEntity blockEntity = null;
        CompoundTag tag = data.blockEntityTag();
        if (tag != null && !tag.isEmpty()) {
            blockEntity = BlockEntity.loadStatic(worldPos, data.state(), tag, level.registryAccess());
        }

        ItemStack tool = player.getMainHandItem();
        List<ItemStack> drops = Block.getDrops(data.state(), level, worldPos, blockEntity, player, tool);

        boolean dropContents = blockEntity != null && !(data.state().getBlock() instanceof ShulkerBoxBlock);
        return new BlockStateDrops(blockEntity, drops, dropContents);
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
            Thread t = new Thread(r, "Physics-Structure-Rebuild");
            t.setDaemon(true);
            return t;
        });
    }

    private record StructureHit(BlockPos localPos, long blockKey, Vec3 worldCenter, BlockPos worldBlockPos, StructureBlockData data) {
    }

    private record BlockStateDrops(BlockEntity blockEntity, List<ItemStack> items, boolean dropContents) {
    }

    private static class MiningState {
        private final long bodyId;
        private final long blockKey;
        private final net.minecraft.world.level.block.state.BlockState state;
        private float progress = 0.0f;
        private long lastTick = 0L;

        private MiningState(long bodyId, long blockKey, net.minecraft.world.level.block.state.BlockState state) {
            this.bodyId = bodyId;
            this.blockKey = blockKey;
            this.state = state;
        }
    }

}
