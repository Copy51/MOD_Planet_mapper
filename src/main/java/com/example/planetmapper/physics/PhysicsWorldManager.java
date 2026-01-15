package com.example.planetmapper.physics;

import com.example.planetmapper.PlanetMapper;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Handles the global physics simulation loop (60Hz) and 
 * synchronization with Minecraft (20Hz).
 * 
 * NOTE: This class is NOT auto-registered as an event subscriber
 * to avoid triggering native library loading at mod bootstrap time.
 */
public class PhysicsWorldManager {

    private static NativePhysicsEngine engine;
    private static ScheduledExecutorService executor;
    private static boolean initialized = false;
    private static boolean nativeAvailable = false;

    private static final List<PhysicsBodyEntity> trackedEntities = new ArrayList<>();

    public static void init() {
        if (initialized) return;
        initialized = true;
        
        try {
            engine = new NativePhysicsEngine();
            engine.setGravity(new Vector3f(0, -9.81f, 0));
            
            executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "Physics-Thread");
                thread.setDaemon(true);
                return thread;
            });
            
            // Run physics at 60Hz
            long stepTimeMs = 1000 / 60;
            executor.scheduleAtFixedRate(() -> {
                try {
                    // Fixed time step for stability
                    engine.step(1.0f / 60.0f);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }, 0, stepTimeMs, TimeUnit.MILLISECONDS);
            
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
        if (executor != null) {
            executor.shutdown();
        }
        if (engine != null) {
            engine.close();
        }
    }
}
