package com.example.planetmapper.physics;

import net.minecraft.world.phys.AABB;
import org.joml.Vector3f;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;

/**
 * Java wrapper for the native physics engine using JNI.
 * Handles native library loading and provides Java-friendly API.
 */
public class NativePhysicsEngine {

    private static boolean libraryLoaded = false;
    private long worldPointer = 0;

    // Native methods - implemented in C++
    private static native long nativeInitializePhysicsWorld();
    private static native void nativeSetGravity(long worldPtr, float x, float y, float z);
    private static native void nativeStepPhysics(long worldPtr, float deltaTime);
    private static native long nativeCreateRigidBody(long worldPtr, float[] mins, float[] maxs, int boxCount, float mass);
    private static native long nativeCreateStaticBody(long worldPtr, float[] mins, float[] maxs, int boxCount);
    private static native void nativeGetBodyState(long worldPtr, long bodyId, float[] outState);
    private static native void nativeApplyForce(long worldPtr, long bodyId, float fx, float fy, float fz);
    private static native void nativeRemoveBody(long worldPtr, long bodyId);
    private static native void nativeCleanupPhysicsWorld(long worldPtr);

    public NativePhysicsEngine() {
        loadNativeLibrary();
        worldPointer = nativeInitializePhysicsWorld();
        if (worldPointer == 0) {
            throw new RuntimeException("Failed to initialize physics world");
        }
    }

    private static synchronized void loadNativeLibrary() {
        if (libraryLoaded) return;
        
        try {
            String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
            String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
            String suffix = os.contains("win") ? ".dll" : (os.contains("mac") ? ".dylib" : ".so");
            String platformDir = (os.contains("win") ? "windows" : (os.contains("mac") ? "macos" : "linux")) + "_" + 
                                (arch.contains("64") ? "x64" : arch);

            String libName = "native_physics" + suffix;
            String resourcePath = "/natives/" + platformDir + "/" + libName;

            // Extract to temporary directory
            Path tempDir = Files.createTempDirectory("planetmapper_physics");
            Path libPath = tempDir.resolve(libName);
            
            try (InputStream is = NativePhysicsEngine.class.getResourceAsStream(resourcePath)) {
                if (is == null) {
                    throw new RuntimeException("Native library not found in resources: " + resourcePath);
                }
                Files.copy(is, libPath, StandardCopyOption.REPLACE_EXISTING);
            }

            // Register shutdown hook to cleanup
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    Files.deleteIfExists(libPath);
                    Files.deleteIfExists(tempDir);
                } catch (Exception ignored) {}
            }));

            System.load(libPath.toAbsolutePath().toString());
            libraryLoaded = true;
            
        } catch (Throwable t) {
            throw new RuntimeException("Failed to load native physics engine", t);
        }
    }

    public synchronized void setGravity(Vector3f gravity) {
        if (worldPointer != 0) {
            nativeSetGravity(worldPointer, gravity.x(), gravity.y(), gravity.z());
        }
    }

    public synchronized void step(float deltaTime) {
        if (worldPointer != 0) {
            nativeStepPhysics(worldPointer, deltaTime);
        }
    }

    /**
     * Creates a rigid body from a set of AABBs.
     */
    public synchronized long createRigidBody(List<AABB> boxes, float mass) {
        if (worldPointer == 0) return -1;
        
        int count = boxes.size();
        float[] mins = new float[count * 3];
        float[] maxs = new float[count * 3];

        for (int i = 0; i < count; i++) {
            AABB box = boxes.get(i);
            int offset = i * 3;
            mins[offset] = (float) box.minX;
            mins[offset + 1] = (float) box.minY;
            mins[offset + 2] = (float) box.minZ;

            maxs[offset] = (float) box.maxX;
            maxs[offset + 1] = (float) box.maxY;
            maxs[offset + 2] = (float) box.maxZ;
        }

        return nativeCreateRigidBody(worldPointer, mins, maxs, count, mass);
    }

    /**
     * Creates a static rigid body from a set of AABBs.
     */
    public synchronized long createStaticBody(List<AABB> boxes) {
        if (worldPointer == 0) return -1;

        int count = boxes.size();
        float[] mins = new float[count * 3];
        float[] maxs = new float[count * 3];

        for (int i = 0; i < count; i++) {
            AABB box = boxes.get(i);
            int offset = i * 3;
            mins[offset] = (float) box.minX;
            mins[offset + 1] = (float) box.minY;
            mins[offset + 2] = (float) box.minZ;

            maxs[offset] = (float) box.maxX;
            maxs[offset + 1] = (float) box.maxY;
            maxs[offset + 2] = (float) box.maxZ;
        }

        return nativeCreateStaticBody(worldPointer, mins, maxs, count);
    }

    /**
     * Gets the body state into the provided array.
     * Array layout: [posX, posY, posZ, quatX, quatY, quatZ, quatW, velX, velY, velZ, angVelX, angVelY, angVelZ]
     */
    public synchronized void getBodyState(long bodyId, float[] outState) {
        if (worldPointer != 0 && outState != null && outState.length >= 13) {
            nativeGetBodyState(worldPointer, bodyId, outState);
        }
    }

    public synchronized void applyForce(long bodyId, Vector3f force) {
        if (worldPointer != 0) {
            nativeApplyForce(worldPointer, bodyId, force.x(), force.y(), force.z());
        }
    }

    public synchronized void removeBody(long bodyId) {
        if (worldPointer != 0) {
            nativeRemoveBody(worldPointer, bodyId);
        }
    }

    public synchronized void close() {
        if (worldPointer != 0) {
            nativeCleanupPhysicsWorld(worldPointer);
            worldPointer = 0;
        }
    }
}
