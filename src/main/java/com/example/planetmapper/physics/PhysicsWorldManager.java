package com.example.planetmapper.physics;

import com.example.planetmapper.PlanetMapper;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles the global physics simulation loop (fixed substeps per server tick)
 * and synchronization with Minecraft (20Hz).
 * 
 * NOTE: This class is NOT auto-registered as an event subscriber
 * to avoid triggering native library loading at mod bootstrap time.
 */
public class PhysicsWorldManager {

    private static NativePhysicsEngine engine;
    private static boolean initialized = false;
    private static boolean nativeAvailable = false;

    private static final List<PhysicsBodyEntity> trackedEntities = new ArrayList<>();

    public static void init() {
        if (initialized) return;
        initialized = true;
        
        try {
            engine = new NativePhysicsEngine();
            engine.setGravity(new Vector3f(0, -9.81f, 0));
            
            nativeAvailable = true;
            PlanetMapper.LOGGER.info("Native physics engine initialized successfully!");
        } catch (Throwable t) {
            PlanetMapper.LOGGER.error("Native physics engine not available. Physics features disabled.", t);
            nativeAvailable = false;
        }
    }
    
    public static boolean isNativeAvailable() {
        return nativeAvailable;
    }

    public static void registerEntity(PhysicsBodyEntity entity) {
        if (!nativeAvailable) return;
        synchronized (trackedEntities) {
            trackedEntities.add(entity);
        }
    }

    public static void unregisterEntity(PhysicsBodyEntity entity) {
        if (!nativeAvailable) return;
        synchronized (trackedEntities) {
            trackedEntities.remove(entity);
        }
    }

    /**
     * Called manually from a registered event handler if needed.
     */
    public static void onServerTick() {
        if (!nativeAvailable) return;
        int substeps = com.example.planetmapper.Config.PHYSICS_SUBSTEPS.get();
        substeps = Math.max(1, Math.min(8, substeps));
        float dt = (1.0f / 20.0f) / substeps;
        for (int i = 0; i < substeps; i++) {
            engine.step(dt);
        }
        // Synchronize physics state to logical entities every Minecraft tick
        synchronized (trackedEntities) {
            trackedEntities.removeIf(entity -> !entity.isAlive());
            for (PhysicsBodyEntity entity : trackedEntities) {
                // This pulls data from native memory (Zero-copy via stateBuffer)
                entity.updateStateFromNative();
            }
        }
    }

    public static NativePhysicsEngine getEngine() {
        return engine;
    }

    public static void shutdown() {
        if (engine != null) {
            engine.close();
        }
    }
}
