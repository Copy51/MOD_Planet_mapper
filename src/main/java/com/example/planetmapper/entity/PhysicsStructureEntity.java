package com.example.planetmapper.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3f;
import org.joml.Quaternionf;
import org.joml.Matrix3f;

import java.util.HashMap;
import java.util.Map;

public class PhysicsStructureEntity extends PhysicsBlockEntity {

    private final Map<BlockPos, BlockState> localBlocks = new HashMap<>();
    private final Map<BlockPos, CompoundTag> localBlockEntities = new HashMap<>();
    private final Map<BlockPos, Long> movingPistonTicks = new HashMap<>();
    private final Vector3f originOffset = new Vector3f();
    private AABB localBounds = new AABB(0, 0, 0, 0, 0, 0);
    private AABB lastColliderBounds = null;

    // Control inputs
    private float inputThrust;
    private float inputStrafe;
    private float inputVertical;
    private float inputYaw;

    public PhysicsStructureEntity(EntityType<?> entityType, Level level) {
        super(entityType, level);
    }

    public void updateControls(float thrust, float strafe, float vertical, float yaw) {
        this.inputThrust = thrust;
        this.inputStrafe = strafe;
        this.inputVertical = vertical;
        this.inputYaw = yaw;
    }

    @Override
    public void tick() {
        super.tick();
        if (!this.level().isClientSide) {
            applyControlForces();
        }
        updateBoundingBoxFromPhysics();
    }

    @Override
    public void updateStateFromNative() {
        super.updateStateFromNative();
        if (!this.level().isClientSide) {
            updateBoundingBoxFromPhysics();
        }
    }

    private void applyControlForces() {
        long bodyId = getBodyId();
        if (bodyId <= 0) return;

        var engine = com.example.planetmapper.physics.PhysicsWorldManager.getEngine();
        if (engine == null) return;

        // Simple flight model
        // Thrust: Forward vector
        // Strafe: Right vector
        // Vertical: Up vector
        
        org.joml.Quaternionf rot = getPhysicsRotation(1.0f);
        org.joml.Vector3f forward = new org.joml.Vector3f(0, 0, 1).rotate(rot); // Assuming Z is forward
        org.joml.Vector3f up = new org.joml.Vector3f(0, 1, 0).rotate(rot);
        org.joml.Vector3f right = new org.joml.Vector3f(1, 0, 0).rotate(rot);

        float forceMult = 5000.0f; // Arbitrary force multiplier, depends on mass

        org.joml.Vector3f force = new org.joml.Vector3f();
        
        if (Math.abs(inputThrust) > 0.01f) {
            force.add(forward.mul(inputThrust * forceMult));
        }
        if (Math.abs(inputStrafe) > 0.01f) {
            force.add(right.mul(inputStrafe * forceMult));
        }
        if (Math.abs(inputVertical) > 0.01f) {
            force.add(up.mul(inputVertical * forceMult));
        }
        
        if (force.lengthSquared() > 0.001f) {
            engine.applyForce(bodyId, force);
            engine.activateBody(bodyId);
        }

        // Torque for rotation (Yaw)
        // Native engine needs applyTorque? 
        // For now, let's just apply force at offset or wait for native support for torque.
        // Assuming updateControls sends yaw, we can hack it by applying force at front/back.
    }

    // Allow viewer to control direction if riding is re-enabled later.
    @Override
    public net.minecraft.world.entity.LivingEntity getControllingPassenger() {
        net.minecraft.world.entity.Entity passenger = this.getFirstPassenger();
        return passenger instanceof net.minecraft.world.entity.LivingEntity living ? living : null;
    }

    public void setStructure(Map<BlockPos, BlockState> blocks) {
        setStructure(blocks, Map.of());
    }

    public void setStructure(Map<BlockPos, BlockState> blocks, Map<BlockPos, CompoundTag> blockEntities) {
        this.localBlocks.clear();
        this.localBlocks.putAll(blocks);
        this.localBlockEntities.clear();
        this.localBlockEntities.putAll(blockEntities);
        recalculateLocalBounds();
        if (this.level().isClientSide) {
            rebuildPistonTracking();
        } else {
            syncStructure();
        }
    }

    public void setOriginOffset(Vector3f offset) {
        this.originOffset.set(offset);
    }

    public Vector3f getOriginOffset() {
        return originOffset;
    }

    @Override
    public float getBodyYOffset() {
        return 0.0f;
    }

    @Override
    public AABB getBoundingBoxForCulling() {
        AABB bounds = com.example.planetmapper.physics.PhysicsColliderManager.getBodyBounds(level(), getBodyId());
        return bounds != null ? bounds : super.getBoundingBoxForCulling();
    }

    private void updateBoundingBoxFromPhysics() {
        long bodyId = getBodyId();
        if (bodyId <= 0) {
            return;
        }

        AABB colliderBounds = com.example.planetmapper.physics.PhysicsColliderManager.getBodyBounds(bodyId);
        if (colliderBounds != null) {
            lastColliderBounds = colliderBounds;
            this.setBoundingBox(colliderBounds.inflate(0.2));
            return;
        }
        if (localBlocks.isEmpty()) {
            if (lastColliderBounds != null) {
                this.setBoundingBox(lastColliderBounds.inflate(0.2));
            }
            return;
        }
        
        // Use local bounds transformed to world space for the entity hitbox.
        // This is more reliable than the collider's bounds which might be subdivided or laggy.
        org.joml.Quaternionf rotation = getPhysicsRotation(1.0f);
        double px = getX();
        double py = getY();
        double pz = getZ();
        
        // Transform local AABB to world AABB (envelope)
        Matrix3f rot = new Matrix3f().set(rotation);
        float m00 = Math.abs(rot.m00());
        float m01 = Math.abs(rot.m01());
        float m02 = Math.abs(rot.m02());
        float m10 = Math.abs(rot.m10());
        float m11 = Math.abs(rot.m11());
        float m12 = Math.abs(rot.m12());
        float m20 = Math.abs(rot.m20());
        float m21 = Math.abs(rot.m21());
        float m22 = Math.abs(rot.m22());
        
        double hvalX = (localBounds.maxX - localBounds.minX) * 0.5;
        double hvalY = (localBounds.maxY - localBounds.minY) * 0.5;
        double hvalZ = (localBounds.maxZ - localBounds.minZ) * 0.5;
        
        double centerX = (localBounds.minX + localBounds.maxX) * 0.5 + originOffset.x;
        double centerY = (localBounds.minY + localBounds.maxY) * 0.5 + originOffset.y;
        double centerZ = (localBounds.minZ + localBounds.maxZ) * 0.5 + originOffset.z;
        
        Vector3f worldCenter = new Vector3f((float)centerX, (float)centerY, (float)centerZ).rotate(rotation).add((float)px, (float)py, (float)pz);
        
        double hx = m00 * hvalX + m01 * hvalY + m02 * hvalZ;
        double hy = m10 * hvalX + m11 * hvalY + m12 * hvalZ;
        double hz = m20 * hvalX + m21 * hvalY + m22 * hvalZ;
        
        AABB newBounds = new AABB(
            worldCenter.x - hx, worldCenter.y - hy, worldCenter.z - hz,
            worldCenter.x + hx, worldCenter.y + hy, worldCenter.z + hz
        );
        
        this.setBoundingBox(newBounds.inflate(0.2));
    }
    
    private void recalculateLocalBounds() {
        if (localBlocks.isEmpty()) {
            localBounds = new AABB(0,0,0,0,0,0);
            return;
        }
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        
        for (BlockPos pos : localBlocks.keySet()) {
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX() + 1);
            maxY = Math.max(maxY, pos.getY() + 1);
            maxZ = Math.max(maxZ, pos.getZ() + 1);
        }
        localBounds = new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public void syncStructure() {
        CompoundTag tag = new CompoundTag();
        this.addAdditionalSaveData(tag); 
        byte[] payload = com.example.planetmapper.network.StructureSyncCodec.encode(tag);
        if (payload.length == 0) {
            com.example.planetmapper.PlanetMapper.LOGGER.warn("Structure sync skipped for entity {} (empty payload)", this.getId());
            return;
        }

        final int chunkSize = 60 * 1024;
        if (payload.length <= chunkSize) {
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingEntityAndSelf(
                    this,
                    new com.example.planetmapper.network.StructureSyncPacket(this.getId(), payload)
            );
            return;
        }

        int totalChunks = (payload.length + chunkSize - 1) / chunkSize;
        for (int index = 0; index < totalChunks; index++) {
            int start = index * chunkSize;
            int len = Math.min(chunkSize, payload.length - start);
            byte[] chunk = java.util.Arrays.copyOfRange(payload, start, start + len);
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingEntityAndSelf(
                    this,
                    new com.example.planetmapper.network.StructureSyncChunkPacket(this.getId(), index, totalChunks, chunk)
            );
        }
    }

    public void readStructureData(CompoundTag compound) {
        this.readAdditionalSaveData(compound);
    }

    public Map<BlockPos, BlockState> getStructure() {
        return localBlocks;
    }

    public CompoundTag getBlockEntityTag(BlockPos localPos) {
        return localBlockEntities.get(localPos);
    }

    public long getPistonStartTick(BlockPos localPos) {
        Long start = movingPistonTicks.get(localPos);
        if (start != null) {
            return start;
        }
        long now = this.level() != null ? this.level().getGameTime() : 0L;
        movingPistonTicks.put(localPos, now);
        return now;
    }

    public Vec3 getWorldCenterForLocal(BlockPos localPos, float partialTick) {
        if (localPos == null) {
            return new Vec3(getX(), getY(), getZ());
        }
        org.joml.Quaternionf rotation = getPhysicsRotation(partialTick);
        Vector3f originWorld = new Vector3f(originOffset).rotate(rotation).add((float) getX(), (float) getY(), (float) getZ());
        Vector3f local = new Vector3f(localPos.getX() + 0.5f, localPos.getY() + 0.5f, localPos.getZ() + 0.5f);
        local.rotate(rotation).add(originWorld);
        return new Vec3(local.x, local.y, local.z);
    }

    public void applyBlockUpdate(BlockPos localPos, BlockState state, CompoundTag blockEntityTag) {
        if (state == null || state.isAir()) {
            localBlocks.remove(localPos);
            localBlockEntities.remove(localPos);
            movingPistonTicks.remove(localPos);
        } else {
            localBlocks.put(localPos, state);
            if (blockEntityTag != null && !blockEntityTag.isEmpty()) {
                localBlockEntities.put(localPos, blockEntityTag);
            } else {
                localBlockEntities.remove(localPos);
            }
            updatePistonTracking(localPos, state);
        }
        recalculateLocalBounds();
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag compound) {
        super.addAdditionalSaveData(compound);

        compound.putFloat("OriginOffsetX", originOffset.x);
        compound.putFloat("OriginOffsetY", originOffset.y);
        compound.putFloat("OriginOffsetZ", originOffset.z);
        
        ListTag blockList = new ListTag();
        for (Map.Entry<BlockPos, BlockState> entry : localBlocks.entrySet()) {
            CompoundTag result = new CompoundTag();
            result.putLong("Pos", entry.getKey().asLong());
            result.put("State", NbtUtils.writeBlockState(entry.getValue()));
            CompoundTag entityTag = localBlockEntities.get(entry.getKey());
            if (entityTag != null && !entityTag.isEmpty()) {
                result.put("EntityTag", entityTag);
            }
            blockList.add(result);
        }
        compound.put("Structure", blockList);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag compound) {
        super.readAdditionalSaveData(compound);

        if (compound.contains("OriginOffsetX")) {
            originOffset.set(
                    compound.getFloat("OriginOffsetX"),
                    compound.getFloat("OriginOffsetY"),
                    compound.getFloat("OriginOffsetZ")
            );
        }
        
        if (compound.contains("Structure", 9)) { // 9 is ListTag
            ListTag blockList = compound.getList("Structure", 10);
            localBlocks.clear();
            localBlockEntities.clear();
            movingPistonTicks.clear();
            for (int i = 0; i < blockList.size(); i++) {
                CompoundTag tag = blockList.getCompound(i);
                BlockPos pos = BlockPos.of(tag.getLong("Pos"));
                BlockState state = NbtUtils.readBlockState(this.level().holderLookup(net.minecraft.core.registries.Registries.BLOCK), tag.getCompound("State"));
                localBlocks.put(pos, state);
                if (tag.contains("EntityTag", 10)) {
                    localBlockEntities.put(pos, tag.getCompound("EntityTag"));
                }
                updatePistonTracking(pos, state);
            }
            recalculateLocalBounds();
        }
    }

    private void rebuildPistonTracking() {
        movingPistonTicks.clear();
        if (this.level() == null) {
            return;
        }
        for (Map.Entry<BlockPos, BlockState> entry : localBlocks.entrySet()) {
            updatePistonTracking(entry.getKey(), entry.getValue());
        }
    }

    private void updatePistonTracking(BlockPos localPos, BlockState state) {
        if (!this.level().isClientSide) {
            return;
        }
        if (state != null && state.getBlock() instanceof MovingPistonBlock) {
            movingPistonTicks.putIfAbsent(localPos, this.level().getGameTime());
        } else {
            movingPistonTicks.remove(localPos);
        }
    }
}
