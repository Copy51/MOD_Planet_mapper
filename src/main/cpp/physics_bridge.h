#ifndef PHYSICS_BRIDGE_H
#define PHYSICS_BRIDGE_H

#include <cstdint>

#ifdef _WIN32
#define EXPORT __declspec(dllexport)
#else
#define EXPORT __attribute__((visibility("default")))
#endif

extern "C" {

/**
 * Structure for high-performance state transfer.
 * Mapped directly to MemorySegment in Java (Zero-copy).
 */
struct BodyState {
    uint64_t bodyId;
    
    // World Space Transform
    float posX, posY, posZ;
    float quatX, quatY, quatZ, quatW;
    
    // Linear and Angular Velocities
    float velX, velY, velZ;
    float angVelX, angVelY, angVelZ;
    
    // Flags (is_active, is_sleeping, etc.)
    uint32_t flags;
};

/**
 * Initialize the physics world.
 */
EXPORT void* InitializePhysicsWorld();

/**
 * Set global gravity.
 */
EXPORT void SetGravity(void* world, float x, float y, float z);

/**
 * Step the simulation.
 * deltaTime: seconds since last call (usually 1/60th or 1/20th)
 */
EXPORT void StepPhysics(void* world, float deltaTime);

/**
 * Create a rigid body from a voxel shape.
 * vertices: array of float3 (voxels)
 */
EXPORT uint64_t CreateRigidBody(void* world, float* boxMin, float* boxMax, int boxCount, float mass);

/**
 * Get the current state of a body.
 * outState: pointer to a BodyState allocated in Java (Native memory)
 */
EXPORT void GetBodyState(void* world, uint64_t bodyId, BodyState* outState);

/**
 * Apply force in world space.
 */
EXPORT void ApplyForce(void* world, uint64_t bodyId, float fx, float fy, float fz);

/**
 * Destroy the physics world and free resources.
 */
EXPORT void CleanupPhysicsWorld(void* world);

}

#endif // PHYSICS_BRIDGE_H
