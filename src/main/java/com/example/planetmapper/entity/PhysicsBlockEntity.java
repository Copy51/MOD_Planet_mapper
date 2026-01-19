package com.example.planetmapper.entity;

import com.example.planetmapper.physics.PhysicsBodyEntity;
import com.example.planetmapper.physics.PhysicsWorldManager;
import com.example.planetmapper.physics.NativePhysicsEngine;
import com.example.planetmapper.shipyard.ShipyardManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
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
    private final Quaternionf targetRotation = new Quaternionf();
    private final Vector3f targetPosition = new Vector3f();
    private boolean clientHasTarget = false;
    private boolean clientFirstUpdate = true;
    private boolean firstTick = true;

    // Last sent state to avoid spamming packets
    protected final Vector3f lastSentPos = new Vector3f();
    protected final Quaternionf lastSentRot = new Quaternionf();
    protected final Vector3f angularVelocity = new Vector3f();

    public PhysicsBlockEntity(EntityType<?> entityType, Level level) {
        super(entityType, level);
    }
    
    @Override
    public void tick() {
        if (this.level().isClientSide) {
            prevRotation.set(rotation); // Keep this for interpolation
            applyClientInterpolation();
            // Update collider with current interpolated transform
            long id = getBodyId();
            if (id > 0) {
                com.example.planetmapper.physics.PhysicsColliderManager.updateBodyState(
                    id, (float)getX(), (float)getY(), (float)getZ(), rotation, 
                    (float)getDeltaMovement().x, (float)getDeltaMovement().y, (float)getDeltaMovement().z,
                    this.angularVelocity.x, this.angularVelocity.y, this.angularVelocity.z
                );
            }
        } else {
            updateStateFromNative();
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
        return false;
    }

    @Override
    public boolean isPickable() {
        return true;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (this.level().isClientSide || this.isRemoved()) {
            return false;
        }
        Entity attacker = source.getEntity();
        if (attacker instanceof net.minecraft.world.entity.player.Player) {
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
                     net.minecraft.server.level.ServerLevel shipyard = ShipyardManager.getShipyardLevel(serverLevel.getServer());
                     ShipyardManager.removeRegion(shipyard, id);
                 }
             }
         }
    }
    
    public void updateFromPacket(double x, double y, double z, Quaternionf rot) {
        if (this.level().isClientSide) {
            float yOffset = getBodyYOffset();
            targetPosition.set((float) x, (float) (y - yOffset), (float) z);
            targetRotation.set(rot);
            clientHasTarget = true;
            if (clientFirstUpdate) {
                this.setPos(targetPosition.x, targetPosition.y, targetPosition.z);
                this.rotation.set(targetRotation);
                prevRotation.set(rotation);
                clientFirstUpdate = false;
            }
            return;
        }
        float yOffset = getBodyYOffset();
        this.setPos(x, y - yOffset, z);
        this.rotation.set(rot);
    }

    public void updateFromPacket(double x, double y, double z, Quaternionf rot, float avx, float avy, float avz) {
        updateFromPacket(x, y, z, rot);
        this.angularVelocity.set(avx, avy, avz);
    }

    @Override
    public void updateStateFromNative() {
        if (this.isRemoved() || this.level().isClientSide) return;

        NativePhysicsEngine engine = PhysicsWorldManager.getEngine();
        long id = getBodyId();
        
        if (engine != null && id > 0) {
            engine.getBodyState(id, stateBuffer);
            
            // Sync Position (Center of Mass -> Feet)
            float cx = stateBuffer[0];
            float cy = stateBuffer[1];
            float cz = stateBuffer[2];
            float yOffset = getBodyYOffset();
            this.setPos(cx, cy - yOffset, cz);
            
            // Sync Rotation
            rotation.set(stateBuffer[3], stateBuffer[4], stateBuffer[5], stateBuffer[6]);
            
            // Capture Angular Velocity
            angularVelocity.set(stateBuffer[10], stateBuffer[11], stateBuffer[12]);
            
            // Send Packet if changed significantly
            if (firstTick || shouldSendUpdate(cx, cy, cz, rotation)) {
                sendSyncPacket(cx, cy, cz, rotation, stateBuffer[7], stateBuffer[8], stateBuffer[9], stateBuffer[10], stateBuffer[11], stateBuffer[12]);
                lastSentPos.set(cx, cy, cz);
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
    
    private void sendSyncPacket(float px, float py, float pz, Quaternionf rot, float vx, float vy, float vz, float avx, float avy, float avz) {
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingEntityAndSelf(
            this,
            new com.example.planetmapper.network.PhysicsEntitySyncPacket(
                this.getId(), getBodyId(), px, py, pz, rot.x, rot.y, rot.z, rot.w, vx, vy, vz, avx, avy, avz
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

    public float getBodyYOffset() {
        return 0.5f;
    }

    private void applyClientInterpolation() {
        if (!clientHasTarget) {
            return;
        }
        double dx = targetPosition.x - this.getX();
        double dy = targetPosition.y - this.getY();
        double dz = targetPosition.z - this.getZ();
        double distSqr = dx * dx + dy * dy + dz * dz;

        if (distSqr > 9.0) {
            this.setPos(targetPosition.x, targetPosition.y, targetPosition.z);
            this.rotation.set(targetRotation);
            return;
        }

        float alpha = 0.5f; // Faster response
        double nx = Mth.lerp(alpha, this.getX(), targetPosition.x);
        double ny = Mth.lerp(alpha, this.getY(), targetPosition.y);
        double nz = Mth.lerp(alpha, this.getZ(), targetPosition.z);
        this.setPos(nx, ny, nz);
        this.rotation.slerp(targetRotation, alpha);
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
