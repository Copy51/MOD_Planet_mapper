package com.example.planetmapper.physics;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.InputStream;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;

/**
 * Java wrapper for the native physics engine using Project Panama.
 * Handles native memory allocation and low-latency calls.
 */
public class NativePhysicsEngine {

    // BodyState Memory Layout (matches physics_bridge.h)
    public static final GroupLayout BODY_STATE_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_LONG.withName("bodyId"),
            ValueLayout.JAVA_FLOAT.withName("posX"),
            ValueLayout.JAVA_FLOAT.withName("posY"),
            ValueLayout.JAVA_FLOAT.withName("posZ"),
            ValueLayout.JAVA_FLOAT.withName("quatX"),
            ValueLayout.JAVA_FLOAT.withName("quatY"),
            ValueLayout.JAVA_FLOAT.withName("quatZ"),
            ValueLayout.JAVA_FLOAT.withName("quatW"),
            ValueLayout.JAVA_FLOAT.withName("velX"),
            ValueLayout.JAVA_FLOAT.withName("velY"),
            ValueLayout.JAVA_FLOAT.withName("velZ"),
            ValueLayout.JAVA_FLOAT.withName("angVelX"),
            ValueLayout.JAVA_FLOAT.withName("angVelY"),
            ValueLayout.JAVA_FLOAT.withName("angVelZ"),
            ValueLayout.JAVA_INT.withName("flags")
    ).withName("BodyState");

    private static final Linker LINKER = Linker.nativeLinker();
    private MemorySegment worldPointer;
    private final Arena arena;

    // Method Handles
    private static MethodHandle initializePhysicsWorld;
    private static MethodHandle setGravity;
    private static MethodHandle stepPhysics;
    private static MethodHandle createRigidBody;
    private static MethodHandle getBodyState;
    private static MethodHandle applyForce;
    private static MethodHandle cleanupPhysicsWorld;

    public NativePhysicsEngine() {
        this.arena = Arena.ofGlobal();
        loadNativeLibrary();
    }

    private void loadNativeLibrary() {
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

            SymbolLookup lookup = SymbolLookup.libraryLookup(libPath, arena);

            initializePhysicsWorld = LINKER.downcallHandle(
                    lookup.find("InitializePhysicsWorld").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS)
            );

            setGravity = LINKER.downcallHandle(
                    lookup.find("SetGravity").orElseThrow(),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT)
            );

            stepPhysics = LINKER.downcallHandle(
                    lookup.find("StepPhysics").orElseThrow(),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_FLOAT)
            );

            createRigidBody = LINKER.downcallHandle(
                    lookup.find("CreateRigidBody").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS, 
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_FLOAT)
            );

            getBodyState = LINKER.downcallHandle(
                    lookup.find("GetBodyState").orElseThrow(),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS)
            );

            applyForce = LINKER.downcallHandle(
                    lookup.find("ApplyForce").orElseThrow(),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT)
            );

            cleanupPhysicsWorld = LINKER.downcallHandle(
                    lookup.find("CleanupPhysicsWorld").orElseThrow(),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
            );

            worldPointer = (MemorySegment) initializePhysicsWorld.invokeExact();

        } catch (Throwable t) {
            throw new RuntimeException("Failed to load native physics engine", t);
        }
    }

    public void setGravity(Vector3f gravity) {
        try {
            setGravity.invokeExact(worldPointer, gravity.x(), gravity.y(), gravity.z());
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    public void step(float deltaTime) {
        try {
            stepPhysics.invokeExact(worldPointer, deltaTime);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    /**
     * Creates a rigid body from a set of AABBs.
     */
    public long createRigidBody(List<net.minecraft.world.phys.AABB> boxes, float mass) {
        try (Arena localArena = Arena.ofConfined()) {
            int count = boxes.size();
            MemorySegment minSeg = localArena.allocate(ValueLayout.JAVA_FLOAT, count * 3);
            MemorySegment maxSeg = localArena.allocate(ValueLayout.JAVA_FLOAT, count * 3);

            for (int i = 0; i < count; i++) {
                net.minecraft.world.phys.AABB box = boxes.get(i);
                int offset = i * 3;
                minSeg.setAtIndex(ValueLayout.JAVA_FLOAT, offset, (float)box.minX);
                minSeg.setAtIndex(ValueLayout.JAVA_FLOAT, offset + 1, (float)box.minY);
                minSeg.setAtIndex(ValueLayout.JAVA_FLOAT, offset + 2, (float)box.minZ);

                maxSeg.setAtIndex(ValueLayout.JAVA_FLOAT, offset, (float)box.maxX);
                maxSeg.setAtIndex(ValueLayout.JAVA_FLOAT, offset + 1, (float)box.maxY);
                maxSeg.setAtIndex(ValueLayout.JAVA_FLOAT, offset + 2, (float)box.maxZ);
            }

            return (long) createRigidBody.invokeExact(worldPointer, minSeg, maxSeg, count, mass);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    /**
     * Reads the state from native memory into the provided segment.
     * Zero-copy: The segment should be allocated in native memory.
     */
    public void getBodyState(long bodyId, MemorySegment outState) {
        try {
            getBodyState.invokeExact(worldPointer, bodyId, outState);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    public void applyForce(long bodyId, Vector3f force) {
        try {
            applyForce.invokeExact(worldPointer, bodyId, force.x(), force.y(), force.z());
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    public void close() {
        try {
            cleanupPhysicsWorld.invokeExact(worldPointer);
            arena.close();
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}
