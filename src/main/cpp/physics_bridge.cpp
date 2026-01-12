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
    static constexpr JPH::ObjectLayer MOVING = 0;
    static constexpr JPH::ObjectLayer STATIC = 1;
    static constexpr JPH::ObjectLayer NUM_LAYERS = 2;
};

namespace BroadPhaseLayers {
    static constexpr JPH::BroadPhaseLayer MOVING(0);
    static constexpr JPH::BroadPhaseLayer STATIC(1);
    static constexpr JPH::uint NUM_LAYERS(2);
};

// Class that determines if two object layers can collide
class ObjectLayerPairFilterImpl : public JPH::ObjectLayerPairFilter
{
public:
    virtual bool ShouldCollide(JPH::ObjectLayer inObject1, JPH::ObjectLayer inObject2) const override
    {
        switch (inObject1)
        {
        case Layers::MOVING:
            return true; // Moving collides with everything
        case Layers::STATIC:
            return inObject2 == Layers::MOVING; // Static only collides with moving
        default:
            return false;
        }
    }
};

// Class that maps object layers to broad phase layers
class BPLayerInterfaceImpl : public JPH::BroadPhaseLayerInterface
{
public:
    BPLayerInterfaceImpl()
    {
        mObjectToBroadPhase[Layers::MOVING] = BroadPhaseLayers::MOVING;
        mObjectToBroadPhase[Layers::STATIC] = BroadPhaseLayers::STATIC;
    }

    virtual JPH::uint GetNumBroadPhaseLayers() const override
    {
        return BroadPhaseLayers::NUM_LAYERS;
    }

    virtual JPH::BroadPhaseLayer GetBroadPhaseLayer(JPH::ObjectLayer inLayer) const override
    {
        return mObjectToBroadPhase[inLayer];
    }

#if defined(JPH_EXTERNAL_PROFILE) || defined(JPH_PROFILE_ENABLED)
    virtual const char *GetBroadPhaseLayerName(JPH::BroadPhaseLayer inLayer) const override
    {
        switch ((JPH::BroadPhaseLayer::Type)inLayer)
        {
        case (JPH::BroadPhaseLayer::Type)BroadPhaseLayers::MOVING: return "MOVING";
        case (JPH::BroadPhaseLayer::Type)BroadPhaseLayers::STATIC: return "STATIC";
        default: return "INVALID";
        }
    }
#endif // JPH_EXTERNAL_PROFILE || JPH_PROFILE_ENABLED

private:
    JPH::BroadPhaseLayer mObjectToBroadPhase[Layers::NUM_LAYERS];
};

// Class that determines if an object layer can collide with a broad phase layer
class ObjectVsBroadPhaseLayerFilterImpl : public JPH::ObjectVsBroadPhaseLayerFilter
{
public:
    virtual bool ShouldCollide(JPH::ObjectLayer inLayer1, JPH::BroadPhaseLayer inLayer2) const override
    {
        switch (inLayer1)
        {
        case Layers::MOVING:
            return true;
        case Layers::STATIC:
            return inLayer2 == BroadPhaseLayers::MOVING;
        default:
            return false;
        }
    }
};

class PhysicsWorld {
public:
    JPH::PhysicsSystem mPhysicsSystem;
    JPH::TempAllocatorImpl mTempAllocator;
    JPH::JobSystemThreadPool mJobSystem;
    
    // Layer interfaces
    BPLayerInterfaceImpl mBPLayerInterface;
    ObjectVsBroadPhaseLayerFilterImpl mObjectVsBroadPhaseLayerFilter;
    ObjectLayerPairFilterImpl mObjectLayerPairFilter;

    PhysicsWorld() : 
        mTempAllocator(10 * 1024 * 1024), // 10MB temp buffer
        mJobSystem(JPH::cMaxPhysicsJobs, JPH::cMaxPhysicsBarriers, std::thread::hardware_concurrency() - 1) 
    {
        JPH::RegisterDefaultAllocator();
        JPH::Factory::sInstance = new JPH::Factory();
        JPH::RegisterTypes();

        mPhysicsSystem.Init(65536, 1024, 1024, 1024, mBPLayerInterface, mObjectVsBroadPhaseLayerFilter, mObjectLayerPairFilter);
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
    // mInertiaRotation removed in recent Jolt versions as it's part of the shape or calculated

    JPH::Body* body = bi.CreateBody(settings);
    bi.AddBody(body->GetID(), JPH::EActivation::Activate);

    return body->GetID().GetIndexAndSequenceNumber();
}

EXPORT void GetBodyState(void* world, uint64_t bodyId, BodyState* outState) {
    auto* pw = static_cast<PhysicsWorld*>(world);
    JPH::BodyID id((JPH::uint32)bodyId); // Proper cast to avoid warning
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
    JPH::BodyID id((JPH::uint32)bodyId);
    pw->mPhysicsSystem.GetBodyInterface().AddForce(id, JPH::Vec3(fx, fy, fz));
}

EXPORT void CleanupPhysicsWorld(void* world) {
    delete static_cast<PhysicsWorld*>(world);
}

}
