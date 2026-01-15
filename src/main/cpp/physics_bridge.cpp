#include <jni.h>
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
#include <atomic>

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
    JPH::PhysicsSystem* mPhysicsSystem = nullptr;
    JPH::TempAllocatorImpl* mTempAllocator = nullptr;
    JPH::JobSystemThreadPool* mJobSystem = nullptr;
    
    // Layer interfaces
    BPLayerInterfaceImpl mBPLayerInterface;
    ObjectVsBroadPhaseLayerFilterImpl mObjectVsBroadPhaseLayerFilter;
    ObjectLayerPairFilterImpl mObjectLayerPairFilter;

    PhysicsWorld() {
        if (s_worldCount.fetch_add(1) == 0) {
            InitJolt();
        }

        mTempAllocator = new JPH::TempAllocatorImpl(10 * 1024 * 1024); // 10MB temp buffer
        uint32_t hw = std::thread::hardware_concurrency();
        uint32_t workerThreads = (hw > 1) ? (hw - 1) : 1;
        mJobSystem = new JPH::JobSystemThreadPool(JPH::cMaxPhysicsJobs, JPH::cMaxPhysicsBarriers, workerThreads);
        mPhysicsSystem = new JPH::PhysicsSystem();
        mPhysicsSystem->Init(65536, 1024, 1024, 1024, mBPLayerInterface, mObjectVsBroadPhaseLayerFilter, mObjectLayerPairFilter);
    }

    ~PhysicsWorld() {
        delete mPhysicsSystem;
        mPhysicsSystem = nullptr;
        delete mJobSystem;
        mJobSystem = nullptr;
        delete mTempAllocator;
        mTempAllocator = nullptr;

        if (s_worldCount.fetch_sub(1) == 1) {
            ShutdownJolt();
        }
    }

private:
    static std::atomic<int> s_worldCount;

    static void InitJolt() {
        JPH::RegisterDefaultAllocator();
        JPH::Factory::sInstance = new JPH::Factory();
        JPH::RegisterTypes();
    }

    static void ShutdownJolt() {
        JPH::UnregisterTypes();
        delete JPH::Factory::sInstance;
        JPH::Factory::sInstance = nullptr;
    }
};

std::atomic<int> PhysicsWorld::s_worldCount{0};

// JNI functions
// Note: JNI function names follow the pattern: Java_packagename_ClassName_methodName
// Package: com.example.planetmapper.physics
// Class: NativePhysicsEngine

extern "C" {

JNIEXPORT jlong JNICALL Java_com_example_planetmapper_physics_NativePhysicsEngine_nativeInitializePhysicsWorld(JNIEnv* env, jclass clazz) {
    return reinterpret_cast<jlong>(new PhysicsWorld());
}

JNIEXPORT void JNICALL Java_com_example_planetmapper_physics_NativePhysicsEngine_nativeSetGravity(JNIEnv* env, jclass clazz, jlong worldPtr, jfloat x, jfloat y, jfloat z) {
    auto* pw = reinterpret_cast<PhysicsWorld*>(worldPtr);
    if (pw && pw->mPhysicsSystem) {
        pw->mPhysicsSystem->SetGravity(JPH::Vec3(x, y, z));
    }
}

JNIEXPORT void JNICALL Java_com_example_planetmapper_physics_NativePhysicsEngine_nativeStepPhysics(JNIEnv* env, jclass clazz, jlong worldPtr, jfloat deltaTime) {
    auto* pw = reinterpret_cast<PhysicsWorld*>(worldPtr);
    if (pw && pw->mPhysicsSystem && pw->mTempAllocator && pw->mJobSystem) {
        pw->mPhysicsSystem->Update(deltaTime, 1, pw->mTempAllocator, pw->mJobSystem);
    }
}

JNIEXPORT jlong JNICALL Java_com_example_planetmapper_physics_NativePhysicsEngine_nativeCreateRigidBody(JNIEnv* env, jclass clazz, jlong worldPtr, jfloatArray mins, jfloatArray maxs, jint boxCount, jfloat mass) {
    auto* pw = reinterpret_cast<PhysicsWorld*>(worldPtr);
    if (!pw || !pw->mPhysicsSystem) return 0;

    jfloat* minData = env->GetFloatArrayElements(mins, nullptr);
    jfloat* maxData = env->GetFloatArrayElements(maxs, nullptr);

    JPH::BodyInterface& bi = pw->mPhysicsSystem->GetBodyInterface();

    JPH::StaticCompoundShapeSettings compoundSettings;
    for (int i = 0; i < boxCount; ++i) {
        int offset = i * 3;
        JPH::Vec3 min(minData[offset], minData[offset+1], minData[offset+2]);
        JPH::Vec3 max(maxData[offset], maxData[offset+1], maxData[offset+2]);
        
        JPH::Vec3 center = (min + max) * 0.5f;
        JPH::Vec3 halfExtent = (max - min) * 0.5f;
        
        compoundSettings.AddShape(center, JPH::Quat::sIdentity(), new JPH::BoxShape(halfExtent));
    }

    env->ReleaseFloatArrayElements(mins, minData, 0);
    env->ReleaseFloatArrayElements(maxs, maxData, 0);

    JPH::ShapeSettings::ShapeResult result = compoundSettings.Create();
    if (result.HasError()) return 0;

    JPH::BodyCreationSettings settings(result.Get(), JPH::Vec3::sZero(), JPH::Quat::sIdentity(), JPH::EMotionType::Dynamic, Layers::MOVING);
    settings.mMassPropertiesOverride.mMass = mass;

    JPH::Body* body = bi.CreateBody(settings);
    bi.AddBody(body->GetID(), JPH::EActivation::Activate);

    return static_cast<jlong>(body->GetID().GetIndexAndSequenceNumber());
}

JNIEXPORT void JNICALL Java_com_example_planetmapper_physics_NativePhysicsEngine_nativeGetBodyState(JNIEnv* env, jclass clazz, jlong worldPtr, jlong bodyId, jfloatArray outState) {
    auto* pw = reinterpret_cast<PhysicsWorld*>(worldPtr);
    if (!pw || !pw->mPhysicsSystem) return;

    JPH::BodyID id(static_cast<JPH::uint32>(bodyId));
    JPH::BodyInterface& bi = pw->mPhysicsSystem->GetBodyInterface();

    if (!bi.IsAdded(id)) return;

    JPH::RVec3 pos = bi.GetPosition(id);
    JPH::Quat rot = bi.GetRotation(id);
    JPH::Vec3 vel = bi.GetLinearVelocity(id);
    JPH::Vec3 angVel = bi.GetAngularVelocity(id);

    jfloat state[13];
    state[0] = static_cast<jfloat>(pos.GetX());
    state[1] = static_cast<jfloat>(pos.GetY());
    state[2] = static_cast<jfloat>(pos.GetZ());
    state[3] = rot.GetX();
    state[4] = rot.GetY();
    state[5] = rot.GetZ();
    state[6] = rot.GetW();
    state[7] = vel.GetX();
    state[8] = vel.GetY();
    state[9] = vel.GetZ();
    state[10] = angVel.GetX();
    state[11] = angVel.GetY();
    state[12] = angVel.GetZ();

    env->SetFloatArrayRegion(outState, 0, 13, state);
}

JNIEXPORT void JNICALL Java_com_example_planetmapper_physics_NativePhysicsEngine_nativeApplyForce(JNIEnv* env, jclass clazz, jlong worldPtr, jlong bodyId, jfloat fx, jfloat fy, jfloat fz) {
    auto* pw = reinterpret_cast<PhysicsWorld*>(worldPtr);
    if (!pw || !pw->mPhysicsSystem) return;

    JPH::BodyID id(static_cast<JPH::uint32>(bodyId));
    pw->mPhysicsSystem->GetBodyInterface().AddForce(id, JPH::Vec3(fx, fy, fz));
}

JNIEXPORT void JNICALL Java_com_example_planetmapper_physics_NativePhysicsEngine_nativeCleanupPhysicsWorld(JNIEnv* env, jclass clazz, jlong worldPtr) {
    auto* pw = reinterpret_cast<PhysicsWorld*>(worldPtr);
    delete pw;
}

}
