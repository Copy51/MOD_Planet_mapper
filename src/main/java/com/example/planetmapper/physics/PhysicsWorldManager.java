package com.example.planetmapper.physics;

import com.example.planetmapper.PlanetMapper;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Handles the global physics simulation loop (60Hz) and 
 * synchronization with Minecraft (20Hz).
 */
@EventBusSubscriber(modid = PlanetMapper.MODID)
public class PhysicsWorldManager {

    private static final NativePhysicsEngine engine = new NativePhysicsEngine();
    private static final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "Physics-Thread");
        thread.setDaemon(true);
        return thread;
    });

    private static final List<PhysicsBodyEntity> trackedEntities = new ArrayList<>();

    public static void init() {
        engine.setGravity(new Vector3f(0, -9.81f, 0));
        
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
    }

    public static void registerEntity(PhysicsBodyEntity entity) {
        synchronized (trackedEntities) {
            trackedEntities.add(entity);
        }
    }

    public static void unregisterEntity(PhysicsBodyEntity entity) {
        synchronized (trackedEntities) {
            trackedEntities.remove(entity);
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        // Synchronize physics state to logical entities every Minecraft tick
        synchronized (trackedEntities) {
            for (PhysicsBodyEntity entity : trackedEntities) {
                if (entity.isAlive()) {
                    // This pulls data from native memory (Zero-copy via stateBuffer)
                    // entity.updateStateFromNative();
                }
            }
        }
    }

    public static NativePhysicsEngine getEngine() {
        return engine;
    }

    public static void shutdown() {
        executor.shutdown();
        engine.close();
    }
}
