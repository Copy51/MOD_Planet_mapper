package com.example.planetmapper.physics.structure;

import com.example.planetmapper.entity.PhysicsStructureEntity;
import com.example.planetmapper.physics.NativePhysicsEngine;
import com.example.planetmapper.physics.PhysicsColliderManager;
import com.example.planetmapper.physics.PhysicsWorldManager;
import com.example.planetmapper.physics.VoxelShapeOptimizer;
import com.example.planetmapper.physics.WorldCollisionManager;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class WorldStructureSpawner {
    private WorldStructureSpawner() {
    }

    public static boolean spawnFromWorld(ServerLevel level, LongOpenHashSet positions) {
        if (level == null || positions == null || positions.isEmpty()) {
            return false;
        }
        if (!PhysicsWorldManager.isNativeAvailable()) {
            return false;
        }

        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        boolean hasBlocks = false;

        LongIterator iterator = positions.iterator();
        while (iterator.hasNext()) {
            long key = iterator.nextLong();
            int wx = BlockPos.getX(key);
            int wy = BlockPos.getY(key);
            int wz = BlockPos.getZ(key);
            BlockState state = level.getBlockState(new BlockPos(wx, wy, wz));
            if (state.isAir()) {
                continue;
            }
            hasBlocks = true;
            minX = Math.min(minX, wx);
            minY = Math.min(minY, wy);
            minZ = Math.min(minZ, wz);
            maxX = Math.max(maxX, wx);
            maxY = Math.max(maxY, wy);
            maxZ = Math.max(maxZ, wz);
        }

        if (!hasBlocks) {
            return false;
        }

        Long2ObjectOpenHashMap<StructureBlockData> blocks = new Long2ObjectOpenHashMap<>();
        LongOpenHashSet collidable = new LongOpenHashSet();
        Map<BlockPos, BlockState> renderBlocks = new HashMap<>();
        Map<BlockPos, net.minecraft.nbt.CompoundTag> renderBlockEntities = new HashMap<>();

        iterator = positions.iterator();
        while (iterator.hasNext()) {
            long key = iterator.nextLong();
            int wx = BlockPos.getX(key);
            int wy = BlockPos.getY(key);
            int wz = BlockPos.getZ(key);
            BlockPos worldPos = new BlockPos(wx, wy, wz);
            BlockState state = level.getBlockState(worldPos);
            if (state.isAir()) {
                continue;
            }

            boolean collidableBlock = !state.getCollisionShape(level, worldPos).isEmpty();
            BlockEntity blockEntity = level.getBlockEntity(worldPos);
            net.minecraft.nbt.CompoundTag blockEntityTag = blockEntity != null ? blockEntity.saveWithId(level.registryAccess()) : null;

            int lx = wx - minX;
            int ly = wy - minY;
            int lz = wz - minZ;
            long localKey = BlockPos.asLong(lx, ly, lz);

            blocks.put(localKey, new StructureBlockData(state, blockEntityTag, collidableBlock));
            if (collidableBlock) {
                collidable.add(localKey);
            }

            BlockPos localPos = new BlockPos(lx, ly, lz);
            renderBlocks.put(localPos, state);
            if (blockEntityTag != null && !blockEntityTag.isEmpty()) {
                renderBlockEntities.put(localPos, blockEntityTag);
            }

            level.setBlock(worldPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);
            WorldCollisionManager.markChunkDirtyNow(level, new ChunkPos(worldPos));
        }

        if (blocks.isEmpty() || collidable.isEmpty()) {
            return false;
        }

        List<AABB> localBoxes = VoxelShapeOptimizer.optimizeLongSet(collidable);
        Vector3f worldOrigin = new Vector3f(minX, minY, minZ);
        List<AABB> worldBoxes = offsetBoxes(localBoxes, worldOrigin);
        if (worldBoxes.isEmpty()) {
            return false;
        }

        NativePhysicsEngine engine = PhysicsWorldManager.getEngine();
        if (engine == null) {
            return false;
        }

        StructurePhysicsProperties physicsProperties = StructurePhysicsProperties.fromBlocks(blocks);
        StructurePhysicsProperties.MaterialSummary material = physicsProperties.snapshot();
        long bodyId = engine.createRigidBody(worldBoxes, material);
        if (bodyId <= 0) {
            return false;
        }

        float[] stateBuffer = new float[13];
        engine.getBodyState(bodyId, stateBuffer);
        Vector3f bodyPos = new Vector3f(stateBuffer[0], stateBuffer[1], stateBuffer[2]);
        Quaternionf bodyRot = new Quaternionf(stateBuffer[3], stateBuffer[4], stateBuffer[5], stateBuffer[6]);

        Vector3f originOffset = new Vector3f(worldOrigin).sub(bodyPos);
        PhysicsStructure structure = new PhysicsStructure(level.dimension(), bodyId, new BlockPos(minX, minY, minZ),
                originOffset, blocks, collidable, physicsProperties);
        StructurePhysicsManager.registerStructure(structure);

        List<AABB> bodyLocal = offsetBoxes(localBoxes, originOffset);
        PhysicsColliderManager.registerAndSyncBody(level, bodyId, worldBoxes, bodyPos);
        PhysicsColliderManager.updateAndSyncBody(level, bodyId, bodyLocal);
        PhysicsColliderManager.updateBodyTransform(bodyId, bodyPos.x, bodyPos.y, bodyPos.z, bodyRot);

        PhysicsStructureEntity entity = com.example.planetmapper.entity.ModEntities.PHYSICS_STRUCTURE.get().create(level);
        if (entity != null) {
            entity.setPos(bodyPos.x, bodyPos.y - entity.getBodyYOffset(), bodyPos.z);
            entity.setBodyId(bodyId);
            entity.setOriginOffset(originOffset);
            entity.setStructure(renderBlocks, renderBlockEntities);
            level.addFreshEntity(entity);
            PhysicsWorldManager.registerEntity(entity);
            structure.setEntityId(entity.getId());
        }

        return true;
    }

    private static List<AABB> offsetBoxes(List<AABB> boxes, Vector3f offset) {
        if (boxes.isEmpty()) {
            return List.of();
        }
        java.util.ArrayList<AABB> result = new java.util.ArrayList<>(boxes.size());
        for (AABB box : boxes) {
            result.add(new AABB(
                    box.minX + offset.x, box.minY + offset.y, box.minZ + offset.z,
                    box.maxX + offset.x, box.maxY + offset.y, box.maxZ + offset.z
            ));
        }
        return result;
    }
}
