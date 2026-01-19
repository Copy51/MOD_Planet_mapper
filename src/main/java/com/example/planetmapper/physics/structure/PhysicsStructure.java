package com.example.planetmapper.physics.structure;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.concurrent.atomic.AtomicBoolean;

public class PhysicsStructure {
    private final ResourceKey<Level> dimension;
    private final long bodyId;
    private final BlockPos origin;
    private final Vector3f originOffset;
    private final Long2ObjectOpenHashMap<StructureBlockData> blocks;
    private final LongOpenHashSet collidableBlocks;
    private final StructurePhysicsProperties physicsProperties;
    private int entityId;
    private final float[] stateBuffer = new float[13];
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private final AtomicBoolean rebuildRunning = new AtomicBoolean(false);

    public PhysicsStructure(ResourceKey<Level> dimension,
                            long bodyId,
                            BlockPos origin,
                            Vector3f originOffset,
                            Long2ObjectOpenHashMap<StructureBlockData> blocks,
                            LongOpenHashSet collidableBlocks,
                            StructurePhysicsProperties physicsProperties) {
        this.dimension = dimension;
        this.bodyId = bodyId;
        this.origin = origin;
        this.originOffset = originOffset;
        this.blocks = blocks;
        this.collidableBlocks = collidableBlocks;
        this.physicsProperties = physicsProperties;
    }

    public ResourceKey<Level> getDimension() {
        return dimension;
    }

    public long getBodyId() {
        return bodyId;
    }

    public int getEntityId() {
        return entityId;
    }

    public void setEntityId(int entityId) {
        this.entityId = entityId;
    }

    public BlockPos getOrigin() {
        return origin;
    }

    public Vector3f getOriginOffset() {
        return originOffset;
    }

    public Long2ObjectOpenHashMap<StructureBlockData> getBlocks() {
        return blocks;
    }

    public StructurePhysicsProperties getPhysicsProperties() {
        return physicsProperties;
    }

    public LongOpenHashSet snapshotCollidableBlocks() {
        return new LongOpenHashSet(collidableBlocks);
    }

    public StructureBlockData getBlock(long localKey) {
        return blocks.get(localKey);
    }

    public StructureBlockData removeBlock(long localKey) {
        StructureBlockData data = blocks.remove(localKey);
        if (data != null && data.collidable()) {
            collidableBlocks.remove(localKey);
        }
        return data;
    }

    public void putBlock(long localKey, StructureBlockData data) {
        blocks.put(localKey, data);
        if (data.collidable()) {
            collidableBlocks.add(localKey);
        }
    }

    public boolean isDirty() {
        return dirty.get();
    }

    public void markDirty() {
        dirty.set(true);
    }

    public void clearDirty() {
        dirty.set(false);
    }

    public boolean isRebuildRunning() {
        return rebuildRunning.get();
    }

    public void setRebuildRunning(boolean running) {
        rebuildRunning.set(running);
    }

    public float[] getStateBuffer() {
        return stateBuffer;
    }

    public Vec3 getOriginWorld(Vector3f bodyPos, Quaternionf rotation) {
        Vector3f originWorld = new Vector3f(originOffset);
        rotation.transform(originWorld);
        originWorld.add(bodyPos);
        return new Vec3(originWorld.x, originWorld.y, originWorld.z);
    }

    public Vec3 localToWorldCenter(BlockPos localPos, Vector3f bodyPos, Quaternionf rotation) {
        Vector3f originWorld = new Vector3f(originOffset);
        rotation.transform(originWorld);
        originWorld.add(bodyPos);

        Vector3f local = new Vector3f(localPos.getX() + 0.5f, localPos.getY() + 0.5f, localPos.getZ() + 0.5f);
        rotation.transform(local);
        local.add(originWorld);
        return new Vec3(local.x, local.y, local.z);
    }

    public int getBlockCount() {
        return blocks.size();
    }

    public int getCollidableCount() {
        return collidableBlocks.size();
    }

    public void shiftLocal(int dx, int dy, int dz) {
        if (dx == 0 && dy == 0 && dz == 0) {
            return;
        }
        Long2ObjectOpenHashMap<StructureBlockData> shifted = new Long2ObjectOpenHashMap<>(blocks.size());
        for (var entry : blocks.long2ObjectEntrySet()) {
            long key = entry.getLongKey();
            int x = BlockPos.getX(key) + dx;
            int y = BlockPos.getY(key) + dy;
            int z = BlockPos.getZ(key) + dz;
            shifted.put(BlockPos.asLong(x, y, z), entry.getValue());
        }
        blocks.clear();
        blocks.putAll(shifted);

        LongOpenHashSet shiftedCollidable = new LongOpenHashSet(collidableBlocks.size());
        LongIterator iterator = collidableBlocks.iterator();
        while (iterator.hasNext()) {
            long key = iterator.nextLong();
            int x = BlockPos.getX(key) + dx;
            int y = BlockPos.getY(key) + dy;
            int z = BlockPos.getZ(key) + dz;
            shiftedCollidable.add(BlockPos.asLong(x, y, z));
        }
        collidableBlocks.clear();
        collidableBlocks.addAll(shiftedCollidable);

        originOffset.sub(dx, dy, dz);
    }
}
