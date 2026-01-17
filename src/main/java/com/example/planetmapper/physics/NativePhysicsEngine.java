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
    private static native void nativeActivateBody(long worldPtr, long bodyId);
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

    public synchronized void activateBody(long bodyId) {
        if (worldPointer != 0) {
            nativeActivateBody(worldPointer, bodyId);
        }
    }

    public synchronized void removeBody(long bodyId) {
        if (worldPointer != 0) {
            nativeRemoveBody(worldPointer, bodyId);
        }
    }

    private static native boolean nativeRaycast(long worldPtr, float ox, float oy, float oz, float dx, float dy, float dz, float maxDist, float[] hitInfo);

    public static class RaycastResult {
        public boolean hit;
        public long bodyId;
        public int subShapeIndex;
        public Vector3f position;
        public Vector3f normal;
        
        public RaycastResult() {
             this.hit = false;
             this.position = new Vector3f();
             this.normal = new Vector3f();
        }
    }

    public synchronized RaycastResult raycast(Vector3f origin, Vector3f direction, float maxDistance) {
        RaycastResult result = new RaycastResult();
        if (worldPointer == 0) return result;

        float[] hitInfo = new float[6];
        boolean hit = nativeRaycast(worldPointer, origin.x(), origin.y(), origin.z(), direction.x(), direction.y(), direction.z(), maxDistance, hitInfo);
        
        if (hit) {
            result.hit = true;
            result.bodyId = (long) hitInfo[0];
            result.subShapeIndex = (int) hitInfo[1];
            result.position.set(hitInfo[2], hitInfo[3], hitInfo[4]);
            // Normal not fully implemented in native yet, but structure is there
            result.normal.set(0, 1, 0); 
        }
        return result;
    }

    private static native int nativeSyncAllBodies(long worldPtr, java.nio.ByteBuffer buffer, int maxBodies);

    /**
     * Updates all active bodies from the native physics world into the provided ByteBuffer.
     * buffer must be a DirectByteBuffer.
     * The layout of the buffer is:
     * [Long ID (8)] [Float PosX (4)] [Float PosY (4)] [Float PosZ (4)]
     * [Float RotX (4)] [Float RotY (4)] [Float RotZ (4)] [Float RotW (4)]
     * [Float VelX (4)] [Float VelY (4)] [Float VelZ (4)]
     * [Float AngVelX (4)] [Float AngVelY (4)] [Float AngVelZ (4)]
     * TotalStride: 60 bytes.
     * @return Number of bodies synced.
     */
    public synchronized int syncAllBodies(java.nio.ByteBuffer buffer, int maxBodies) {
        if (worldPointer == 0) return 0;
        return nativeSyncAllBodies(worldPointer, buffer, maxBodies);
    }

    public synchronized void close() {
        if (worldPointer != 0) {
            nativeCleanupPhysicsWorld(worldPointer);
            worldPointer = 0;
        }
    }
}
