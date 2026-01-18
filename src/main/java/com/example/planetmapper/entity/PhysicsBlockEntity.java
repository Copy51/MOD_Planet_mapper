package com.example.planetmapper.entity;

import com.example.planetmapper.physics.PhysicsBodyEntity;
import com.example.planetmapper.physics.PhysicsWorldManager;
import com.example.planetmapper.physics.NativePhysicsEngine;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public class PhysicsBlockEntity extends Entity implements PhysicsBodyEntity {
    
    // We sync the physics body ID to the client so it knows which body to query/render
    private static final EntityDataAccessor<Long> BODY_ID = SynchedEntityData.defineId(PhysicsBlockEntity.class, EntityDataSerializers.LONG);
    
    // Local buffers for reading native state
    private final float[] stateBuffer = new float[13];
    private final Quaternionf rotation = new Quaternionf();
    private final Quaternionf prevRotation = new Quaternionf();
    private boolean firstTick = true;

    // Last sent state to avoid spamming packets
    private final Vector3f lastSentPos = new Vector3f();
    private final Quaternionf lastSentRot = new Quaternionf();

    public PhysicsBlockEntity(EntityType<?> entityType, Level level) {
        super(entityType, level);
    }
    
    @Override
    public void tick() {
        if (this.level().isClientSide) {
            prevRotation.set(rotation);
        }
        super.tick();
    }

    public void setBodyId(long id) {
        this.entityData.set(BODY_ID, id);
    }

    public long getBodyId() {
        return this.entityData.get(BODY_ID);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(BODY_ID, 0L);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag compound) {
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag compound) {
    }

    @Override
    public boolean canBeCollidedWith() {
        return true;
    }

    @Override
    public boolean isPickable() {
        return true;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (!this.level().isClientSide && !this.isRemoved()) {
            this.remove(RemovalReason.KILLED);
            return true;
        }
        return false;
    }
    
    @Override
    public void remove(RemovalReason reason) {
        super.remove(reason);
        // Cleanup native body and sync to clients to remove ghost collision
        if (!this.level().isClientSide) {
             long id = getBodyId();
             if (id > 0) {
                 com.example.planetmapper.physics.structure.StructurePhysicsManager.unregisterStructure(id);
                 if (this.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
                     com.example.planetmapper.physics.PhysicsColliderManager.unregisterAndSyncBody(serverLevel, id);
                 }
             }
        }
    }
    
    public void updateFromPacket(double x, double y, double z, Quaternionf rot) {
        // Update interpolation targets
        // IMPORTANT: Move feet position to center - 0.5 to align with physics
        this.setPos(x, y - 0.5, z);
        this.rotation.set(rot);
    }

    @Override
    public void updateStateFromNative() {
        if (this.isRemoved() || this.level().isClientSide) return;

        NativePhysicsEngine engine = PhysicsWorldManager.getEngine();
        long id = getBodyId();
        
        if (engine != null && id > 0) {
            engine.getBodyState(id, stateBuffer);
            
            // Sync Position (Center of Mass -> Feet)
            float px = stateBuffer[0];
            float py = stateBuffer[1] - 0.5f;
            float pz = stateBuffer[2];
            this.setPos(px, py, pz);
            
            // Sync Rotation
            rotation.set(stateBuffer[3], stateBuffer[4], stateBuffer[5], stateBuffer[6]);
            
            // Send Packet if changed significantly
            if (firstTick || shouldSendUpdate(px, py, pz, rotation)) {
                sendSyncPacket(px, py, pz, rotation);
                lastSentPos.set(px, py, pz);
                lastSentRot.set(rotation);
                firstTick = false;
            }
        }
    }
    
    private boolean shouldSendUpdate(float px, float py, float pz, Quaternionf rot) {
        float distSq = lastSentPos.distanceSquared(px, py, pz);
        if (distSq > 0.0001f) return true; // Approx 1cm movement
        
        float dot = lastSentRot.dot(rot);
        return Math.abs(dot) < 0.9999f; // Significant rotation change
    }
    
    private void sendSyncPacket(float px, float py, float pz, Quaternionf rot) {
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingEntityAndSelf(
            this,
            new com.example.planetmapper.network.PhysicsEntitySyncPacket(
                this.getId(), getBodyId(), px, py, pz, rot.x, rot.y, rot.z, rot.w
            )
        );
    }
    
    public Quaternionf getPhysicsRotation(float partialTick) {
        if (partialTick == 1.0f) return rotation;
        return new Quaternionf(prevRotation).slerp(rotation, partialTick);
    }
    
    // Deprecated single-param getter
    public Quaternionf getPhysicsRotation() {
        return rotation;
    }
    @Override
    public net.minecraft.world.phys.HitResult pick(double hitDist, float partialTicks, boolean screen) {
        Vec3 eyePos = this.getEyePosition(partialTicks);
        Vec3 viewVec = this.getViewVector(partialTicks);
        Float t = com.example.planetmapper.physics.PhysicsColliderManager.raycastBody(getBodyId(), eyePos, viewVec, hitDist);
        if (t != null && t >= 0.0f && t <= hitDist) {
            Vec3 hitPos = eyePos.add(viewVec.scale(t));
            return new net.minecraft.world.phys.EntityHitResult(this, hitPos);
        }
        return null;
    }
    
    // Also override getBoundingBox explicitly just in case, though Entity handles it.
    // We want the AABB to be large enough to catch the ray
    @Override
    public net.minecraft.world.phys.AABB getBoundingBoxForCulling() {
        return super.getBoundingBox().inflate(1.0); // Simple inflation
    }

}
