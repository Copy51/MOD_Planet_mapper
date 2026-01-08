package com.example.planetmapper.physics;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * An entity that is driven by the native physics engine.
 */
public class PhysicsBodyEntity extends Entity {

    private long bodyId = -1;
    private final Arena stateArena = Arena.ofConfined();
    private final MemorySegment stateBuffer;

    // Rendering interpolation states
    private PhysicsMathUtils.State prevPhysState;
    private PhysicsMathUtils.State currentPhysState;

    public PhysicsBodyEntity(EntityType<?> type, Level level) {
        super(type, level);
        this.stateBuffer = stateArena.allocate(NativePhysicsEngine.BODY_STATE_LAYOUT);
    }

    public void setBodyId(long id) {
        this.bodyId = id;
    }

    @Override
    public void tick() {
        super.tick();

        if (!level().isClientSide && bodyId != -1) {
            // In a real implementation, the PhysicsEngine.step() is called globally.
            // Here we just pull the state for synchronization.
            updateStateFromNative();
        }
    }

    private void updateStateFromNative() {
        // Assume we have a global access to the engine
        // NativePhysicsEngine engine = ...;
        // engine.getBodyState(bodyId, stateBuffer);

        // Extract data from stateBuffer
        float px = stateBuffer.get(NativePhysicsEngine.BODY_STATE_LAYOUT.select(java.lang.foreign.MemoryLayout.PathElement.groupElement("posX")), 0);
        float py = stateBuffer.get(NativePhysicsEngine.BODY_STATE_LAYOUT.select(java.lang.foreign.MemoryLayout.PathElement.groupElement("posY")), 0);
        float pz = stateBuffer.get(NativePhysicsEngine.BODY_STATE_LAYOUT.select(java.lang.foreign.MemoryLayout.PathElement.groupElement("posZ")), 0);

        float qx = stateBuffer.get(NativePhysicsEngine.BODY_STATE_LAYOUT.select(java.lang.foreign.MemoryLayout.PathElement.groupElement("quatX")), 0);
        float qy = stateBuffer.get(NativePhysicsEngine.BODY_STATE_LAYOUT.select(java.lang.foreign.MemoryLayout.PathElement.groupElement("quatY")), 0);
        float qz = stateBuffer.get(NativePhysicsEngine.BODY_STATE_LAYOUT.select(java.lang.foreign.MemoryLayout.PathElement.groupElement("quatZ")), 0);
        float qw = stateBuffer.get(NativePhysicsEngine.BODY_STATE_LAYOUT.select(java.lang.foreign.MemoryLayout.PathElement.groupElement("quatW")), 0);

        this.prevPhysState = this.currentPhysState;
        this.currentPhysState = new PhysicsMathUtils.State(
                new Vector3f(px, py, pz),
                new Quaternionf(qx, qy, qz, qw)
        );

        // Update Minecraft's position for frustum culling and logical presence
        setPos(px, py, pz);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        // Sync bodyId to client
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        if (tag.contains("BodyId")) {
            this.bodyId = tag.getLong("BodyId");
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putLong("BodyId", bodyId);
    }

    @Override
    public void remove(RemovalReason reason) {
        super.remove(reason);
        stateArena.close();
        // Native cleanup should happen in the engine
    }
}
