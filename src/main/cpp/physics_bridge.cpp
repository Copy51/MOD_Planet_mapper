#include "physics_bridge.h"
#include <Jolt/Jolt.h>
#include <Jolt/RegisterTypes.h>
#include <Jolt/Core/Factory.h>
#include <Jolt/Core/TempAllocator.h>
#include <Jolt/Core/JobSystemThreadPool.h>
#include <Jolt/Physics/PhysicsSettings.h>
#include <Jolt/Physics/PhysicsSystem.h>
#include <Jolt/Physics/Collision/Shape/BoxShape.h>
#include <Jolt/Physics/Collision/Shape/StaticCompoundShape.h>
#include <Jolt/Physics/Body/BodyCreationSettings.h>
#include <Jolt/Physics/Body/BodyActivationListener.h>

#include <vector>
#include <unordered_map>

// Layers used for collision (simplified)
namespace Layers {
    static constexpr uint8_t UNUSED = 0;
    static constexpr uint8_t MOVING = 1;
    static constexpr uint8_t STATIC = 2;
    static constexpr uint8_t NUM_LAYERS = 3;
};

class PhysicsWorld {
public:
    JPH::PhysicsSystem mPhysicsSystem;
    JPH::TempAllocatorImpl mTempAllocator;
    JPH::JobSystemThreadPool mJobSystem;

    PhysicsWorld() : 
        mTempAllocator(10 * 1024 * 1024), // 10MB temp buffer
        mJobSystem(JPH::cMaxPhysicsJobs, JPH::cMaxPhysicsBarriers, std::thread::hardware_concurrency() - 1) 
    {
        JPH::RegisterDefaultAllocator();
        JPH::Factory::sInstance = new JPH::Factory();
        JPH::RegisterTypes();

        // 65536 bodies, 1024 body mutexes, 1024 max proximities, 1024 max collision pairs, 1024 max contact constraints
        mPhysicsSystem.Init(65536, 1024, 1024, 1024, nullptr, nullptr, nullptr);
    }

    ~PhysicsWorld() {
        JPH::UnregisterTypes();
        delete JPH::Factory::sInstance;
        JPH::Factory::sInstance = nullptr;
    }
};

extern "C" {

EXPORT void* InitializePhysicsWorld() {
    return new PhysicsWorld();
}

EXPORT void SetGravity(void* world, float x, float y, float z) {
    auto* pw = static_cast<PhysicsWorld*>(world);
    pw->mPhysicsSystem.SetGravity(JPH::Vec3(x, y, z));
}

EXPORT void StepPhysics(void* world, float deltaTime) {
    auto* pw = static_cast<PhysicsWorld*>(world);
    // 1 collision step, max 1 update
    pw->mPhysicsSystem.Update(deltaTime, 1, &pw->mTempAllocator, &pw->mJobSystem);
}

EXPORT uint64_t CreateRigidBody(void* world, float* boxMin, float* boxMax, int boxCount, float mass) {
    auto* pw = static_cast<PhysicsWorld*>(world);
    JPH::BodyInterface &bi = pw->mPhysicsSystem.GetBodyInterface();

    JPH::StaticCompoundShapeSettings compoundSettings;
    for (int i = 0; i < boxCount; ++i) {
        int offset = i * 3;
        JPH::Vec3 min(boxMin[offset], boxMin[offset+1], boxMin[offset+2]);
        JPH::Vec3 max(boxMax[offset], boxMax[offset+1], boxMax[offset+2]);
        
        JPH::Vec3 center = (min + max) * 0.5f;
        JPH::Vec3 halfExtent = (max - min) * 0.5f;
        
        compoundSettings.AddShape(center, JPH::Quat::sIdentity(), new JPH::BoxShape(halfExtent));
    }

    JPH::ShapeSettings::ShapeResult result = compoundSettings.Create();
    if (result.HasError()) return 0;

    JPH::BodyCreationSettings settings(result.Get(), JPH::Vec3::sZero(), JPH::Quat::sIdentity(), JPH::EMotionType::Dynamic, Layers::MOVING);
    settings.mMassPropertiesOverride.mMass = mass;
    settings.mMassPropertiesOverride.mInertiaRotation = JPH::Mat44::sIdentity(); // Simplified

    JPH::Body* body = bi.CreateBody(settings);
    bi.AddBody(body->GetID(), JPH::EActivation::Activate);

    return body->GetID().GetIndexAndSequenceNumber();
}

EXPORT void GetBodyState(void* world, uint64_t bodyId, BodyState* outState) {
    auto* pw = static_cast<PhysicsWorld*>(world);
    JPH::BodyID id(bodyId);
    JPH::BodyInterface &bi = pw->mPhysicsSystem.GetBodyInterface();

    if (!bi.IsAdded(id)) return;

    JPH::RVec3 pos = bi.GetPosition(id);
    JPH::Quat rot = bi.GetRotation(id);
    JPH::Vec3 vel = bi.GetLinearVelocity(id);
    JPH::Vec3 angVel = bi.GetAngularVelocity(id);

    outState->bodyId = bodyId;
    outState->posX = (float)pos.GetX();
    outState->posY = (float)pos.GetY();
    outState->posZ = (float)pos.GetZ();
    outState->quatX = rot.GetX();
    outState->quatY = rot.GetY();
    outState->quatZ = rot.GetZ();
    outState->quatW = rot.GetW();
    outState->velX = vel.GetX();
    outState->velY = vel.GetY();
    outState->velZ = vel.GetZ();
    outState->angVelX = angVel.GetX();
    outState->angVelY = angVel.GetY();
    outState->angVelZ = angVel.GetZ();
    outState->flags = bi.IsActive(id) ? 1 : 0;
}

EXPORT void ApplyForce(void* world, uint64_t bodyId, float fx, float fy, float fz) {
    auto* pw = static_cast<PhysicsWorld*>(world);
    JPH::BodyID id(bodyId);
    pw->mPhysicsSystem.GetBodyInterface().AddForce(id, JPH::Vec3(fx, fy, fz));
}

EXPORT void CleanupPhysicsWorld(void* world) {
    delete static_cast<PhysicsWorld*>(world);
}

}
