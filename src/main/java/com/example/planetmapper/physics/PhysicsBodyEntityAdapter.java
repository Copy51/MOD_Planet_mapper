package com.example.planetmapper.physics;

import net.minecraft.world.entity.Entity;

/**
 * Minimal bridge between a Minecraft entity and a native physics body.
 * Currently syncs only position to keep integration safe and lightweight.
 */
public class PhysicsBodyEntityAdapter implements PhysicsBodyEntity {
    private final Entity entity;
    private final long bodyId;
    private final float[] stateBuffer = new float[13];
    private boolean alive = true;

    public PhysicsBodyEntityAdapter(Entity entity, long bodyId) {
        this.entity = entity;
        this.bodyId = bodyId;
    }

    public long getBodyId() {
        return bodyId;
    }

    public Entity getEntity() {
        return entity;
    }

    public void detach() {
        alive = false;
        PhysicsWorldManager.unregisterEntity(this);
        PhysicsColliderManager.unregisterDynamicBody(bodyId);
    }

    @Override
    public boolean isAlive() {
        return alive && entity.isAlive();
    }

    @Override
    public void updateStateFromNative() {
        if (!isAlive()) {
            return;
        }
        NativePhysicsEngine engine = PhysicsWorldManager.getEngine();
        if (engine == null) {
            return;
        }
        engine.getBodyState(bodyId, stateBuffer);
        entity.setPos(stateBuffer[0], stateBuffer[1], stateBuffer[2]);
    }
}
