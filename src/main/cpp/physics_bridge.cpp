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
#include <Jolt/Physics/Collision/CastResult.h>
#include <Jolt/Physics/Collision/RayCast.h>
#include <Jolt/Physics/Collision/Shape/Shape.h>

#include <vector>
#include <unordered_map>
#include <algorithm>
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
    if (!minData || !maxData || boxCount <= 0) {
        if (minData) env->ReleaseFloatArrayElements(mins, minData, 0);
        if (maxData) env->ReleaseFloatArrayElements(maxs, maxData, 0);
        return 0;
    }

    float minX = minData[0];
    float minY = minData[1];
    float minZ = minData[2];
    float maxX = maxData[0];
    float maxY = maxData[1];
    float maxZ = maxData[2];

    for (int i = 1; i < boxCount; ++i) {
        int offset = i * 3;
        minX = std::min(minX, minData[offset]);
        minY = std::min(minY, minData[offset + 1]);
        minZ = std::min(minZ, minData[offset + 2]);
        maxX = std::max(maxX, maxData[offset]);
        maxY = std::max(maxY, maxData[offset + 1]);
        maxZ = std::max(maxZ, maxData[offset + 2]);
    }

    JPH::Vec3 bodyCenter((minX + maxX) * 0.5f, (minY + maxY) * 0.5f, (minZ + maxZ) * 0.5f);

    JPH::BodyInterface& bi = pw->mPhysicsSystem->GetBodyInterface();

    JPH::StaticCompoundShapeSettings compoundSettings;
    for (int i = 0; i < boxCount; ++i) {
        int offset = i * 3;
        JPH::Vec3 min(minData[offset], minData[offset+1], minData[offset+2]);
        JPH::Vec3 max(maxData[offset], maxData[offset+1], maxData[offset+2]);
        
        JPH::Vec3 center = (min + max) * 0.5f;
        JPH::Vec3 halfExtent = (max - min) * 0.5f;
        JPH::Vec3 localCenter = center - bodyCenter;


        compoundSettings.AddShape(localCenter, JPH::Quat::sIdentity(), new JPH::BoxShape(halfExtent), i);
    }

    env->ReleaseFloatArrayElements(mins, minData, 0);
    env->ReleaseFloatArrayElements(maxs, maxData, 0);

    JPH::ShapeSettings::ShapeResult result = compoundSettings.Create();
    if (result.HasError()) return 0;

    JPH::BodyCreationSettings settings(result.Get(), bodyCenter, JPH::Quat::sIdentity(), JPH::EMotionType::Dynamic, Layers::MOVING);
    settings.mMassPropertiesOverride.mMass = mass;

    JPH::Body* body = bi.CreateBody(settings);
    bi.AddBody(body->GetID(), JPH::EActivation::Activate);

    return static_cast<jlong>(body->GetID().GetIndexAndSequenceNumber());
}

JNIEXPORT jlong JNICALL Java_com_example_planetmapper_physics_NativePhysicsEngine_nativeCreateStaticBody(JNIEnv* env, jclass clazz, jlong worldPtr, jfloatArray mins, jfloatArray maxs, jint boxCount) {
    auto* pw = reinterpret_cast<PhysicsWorld*>(worldPtr);
    if (!pw || !pw->mPhysicsSystem) return 0;

    jfloat* minData = env->GetFloatArrayElements(mins, nullptr);
    jfloat* maxData = env->GetFloatArrayElements(maxs, nullptr);
    if (!minData || !maxData || boxCount <= 0) {
        if (minData) env->ReleaseFloatArrayElements(mins, minData, 0);
        if (maxData) env->ReleaseFloatArrayElements(maxs, maxData, 0);
        return 0;
    }

    float minX = minData[0];
    float minY = minData[1];
    float minZ = minData[2];
    float maxX = maxData[0];
    float maxY = maxData[1];
    float maxZ = maxData[2];

    for (int i = 1; i < boxCount; ++i) {
        int offset = i * 3;
        minX = std::min(minX, minData[offset]);
        minY = std::min(minY, minData[offset + 1]);
        minZ = std::min(minZ, minData[offset + 2]);
        maxX = std::max(maxX, maxData[offset]);
        maxY = std::max(maxY, maxData[offset + 1]);
        maxZ = std::max(maxZ, maxData[offset + 2]);
    }

    JPH::Vec3 bodyCenter((minX + maxX) * 0.5f, (minY + maxY) * 0.5f, (minZ + maxZ) * 0.5f);

    JPH::BodyInterface& bi = pw->mPhysicsSystem->GetBodyInterface();
    JPH::StaticCompoundShapeSettings compoundSettings;
    for (int i = 0; i < boxCount; ++i) {
        int offset = i * 3;
        JPH::Vec3 min(minData[offset], minData[offset+1], minData[offset+2]);
        JPH::Vec3 max(maxData[offset], maxData[offset+1], maxData[offset+2]);

        JPH::Vec3 center = (min + max) * 0.5f;
        JPH::Vec3 halfExtent = (max - min) * 0.5f;
        JPH::Vec3 localCenter = center - bodyCenter;


        compoundSettings.AddShape(localCenter, JPH::Quat::sIdentity(), new JPH::BoxShape(halfExtent), i);
    }

    env->ReleaseFloatArrayElements(mins, minData, 0);
    env->ReleaseFloatArrayElements(maxs, maxData, 0);

    JPH::ShapeSettings::ShapeResult result = compoundSettings.Create();
    if (result.HasError()) return 0;

    JPH::BodyCreationSettings settings(result.Get(), bodyCenter, JPH::Quat::sIdentity(), JPH::EMotionType::Static, Layers::STATIC);
    JPH::Body* body = bi.CreateBody(settings);
    bi.AddBody(body->GetID(), JPH::EActivation::DontActivate);

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

JNIEXPORT void JNICALL Java_com_example_planetmapper_physics_NativePhysicsEngine_nativeActivateBody(JNIEnv* env, jclass clazz, jlong worldPtr, jlong bodyId) {
    auto* pw = reinterpret_cast<PhysicsWorld*>(worldPtr);
    if (!pw || !pw->mPhysicsSystem) return;

    JPH::BodyID id(static_cast<JPH::uint32>(bodyId));
    JPH::BodyInterface& bi = pw->mPhysicsSystem->GetBodyInterface();
    if (bi.IsAdded(id)) {
        bi.ActivateBody(id);
    }
}

JNIEXPORT void JNICALL Java_com_example_planetmapper_physics_NativePhysicsEngine_nativeUpdateBodyShape(JNIEnv* env, jclass clazz, jlong worldPtr, jlong bodyId, jfloatArray mins, jfloatArray maxs, jint boxCount) {
    auto* pw = reinterpret_cast<PhysicsWorld*>(worldPtr);
    if (!pw || !pw->mPhysicsSystem) return;

    jfloat* minData = env->GetFloatArrayElements(mins, nullptr);
    jfloat* maxData = env->GetFloatArrayElements(maxs, nullptr);
    if (!minData || !maxData || boxCount <= 0) {
        if (minData) env->ReleaseFloatArrayElements(mins, minData, 0);
        if (maxData) env->ReleaseFloatArrayElements(maxs, maxData, 0);
        return;
    }

    JPH::StaticCompoundShapeSettings compoundSettings;
    for (int i = 0; i < boxCount; ++i) {
        int offset = i * 3;
        JPH::Vec3 min(minData[offset], minData[offset + 1], minData[offset + 2]);
        JPH::Vec3 max(maxData[offset], maxData[offset + 1], maxData[offset + 2]);

        JPH::Vec3 center = (min + max) * 0.5f;
        JPH::Vec3 halfExtent = (max - min) * 0.5f;
        compoundSettings.AddShape(center, JPH::Quat::sIdentity(), new JPH::BoxShape(halfExtent));
    }

    env->ReleaseFloatArrayElements(mins, minData, 0);
    env->ReleaseFloatArrayElements(maxs, maxData, 0);

    JPH::ShapeSettings::ShapeResult result = compoundSettings.Create();
    if (result.HasError()) return;

    JPH::BodyID id(static_cast<JPH::uint32>(bodyId));
    JPH::BodyInterface& bi = pw->mPhysicsSystem->GetBodyInterface();
    if (bi.IsAdded(id)) {
        bi.SetShape(id, result.Get(), false, JPH::EActivation::Activate);
    }
}

JNIEXPORT void JNICALL Java_com_example_planetmapper_physics_NativePhysicsEngine_nativeRemoveBody(JNIEnv* env, jclass clazz, jlong worldPtr, jlong bodyId) {
    auto* pw = reinterpret_cast<PhysicsWorld*>(worldPtr);
    if (!pw || !pw->mPhysicsSystem) return;

    JPH::BodyID id(static_cast<JPH::uint32>(bodyId));
    JPH::BodyInterface& bi = pw->mPhysicsSystem->GetBodyInterface();
    if (bi.IsAdded(id)) {
        bi.RemoveBody(id);
    }
    bi.DestroyBody(id);
}


JNIEXPORT jboolean JNICALL Java_com_example_planetmapper_physics_NativePhysicsEngine_nativeRaycast(JNIEnv* env, jclass clazz, jlong worldPtr, 
    jfloat originX, jfloat originY, jfloat originZ, 
    jfloat dirX, jfloat dirY, jfloat dirZ, 
    jfloat maxDistance, jfloatArray hitInfo) {
    
    auto* pw = reinterpret_cast<PhysicsWorld*>(worldPtr);
    if (!pw || !pw->mPhysicsSystem) return false;

    JPH::Vec3 origin(originX, originY, originZ);
    JPH::Vec3 direction(dirX, dirY, dirZ);
    // Normalize direction and scale by maxDistance if direction is just a vector
    // Assumes input direction is normalized? Let's treat (dirX, dirY, dirZ) as the full ray vector?
    // Usually RayCast takes Origin and Direction (where Direction length is distance).
    // Let's assume input dir is normalized and we scale by maxDistance, OR input dir is the full vector.
    // Standard convention: Origin + Direction.
    
    JPH::RRayCast ray{ origin, direction * maxDistance };
    JPH::RayCastResult result;

    // Cast against everything
    if (pw->mPhysicsSystem->GetNarrowPhaseQuery().CastRay(ray, result)) {
        if (hitInfo != nullptr) {
            jfloat info[6];
            JPH::BodyID bodyID = result.mBodyID;
            info[0] = static_cast<jfloat>(bodyID.GetIndexAndSequenceNumber());
            
            // Get the sub shape ID to know which specific block was hit in the compound shape
            JPH::SubShapeID subShapeID = result.mSubShapeID2;
            // We need to map this SubShapeID to an index.
            // For a StaticCompoundShape, we can use the body's shape to get the sub shape user data or index.
            // However, directly returning the raw SubShapeID value might be opaque. 
            // But Jolt's SubShapeID is a bitfield.
            // We need to drill down.
            
            JPH::BodyLockRead lock(pw->mPhysicsSystem->GetBodyLockInterface(), bodyID);
            if (lock.Succeeded()) {
                const JPH::Body& body = lock.GetBody();
                const JPH::Shape* shape = body.GetShape();
                
                // Convert SubShapeID to leaf index if possible
                // This creates a "SubShapeIDCreator" to walk down? No.
                // shape->GetSubShapeUserData(subShapeID) is often used if we utilized UserData.
                // We didn't set UserData in CreateRigidBody.
                // BUT: Simple Compound Shapes usually index sequentially.
                
                // Let's try to get the Leaf Shape Key or similar.
                // Actually, for StaticCompoundShape, the subShapeID can be used to get the child index.
                // We might need to handle this in Java or ensure we pass the index.
                
                // For now, let's just return the value and refine if needed.
                // Wait, without UserData, identifying the block index is hard if the tree is optimized.
                // FIX: We should have set UserData in createRigidBody.
                

                // Get the sub shape ID to know which specific block was hit
                JPH::uint32 userData = shape->GetSubShapeUserData(result.mSubShapeID2);
                info[1] = static_cast<jfloat>(userData);

                // Calculating hit position
                JPH::Vec3 hitPos = ray.GetPointOnRay(result.mFraction);
                info[2] = hitPos.GetX();
                info[3] = hitPos.GetY();
                info[4] = hitPos.GetZ();
                
                // Normal
                JPH::Vec3 normal = body.GetWorldSpaceSurfaceNormal(result.mSubShapeID2, hitPos);
                info[5] = 0.0f; // Could pack normal here if needed, or separate.
            }
            
            env->SetFloatArrayRegion(hitInfo, 0, 6, info);
        }
        return true;
        }
    return false;
}

JNIEXPORT jint JNICALL Java_com_example_planetmapper_physics_NativePhysicsEngine_nativeSyncAllBodies(JNIEnv* env, jclass clazz, jlong worldPtr, jobject byteBuffer, jint maxBodies) {
    auto* pw = reinterpret_cast<PhysicsWorld*>(worldPtr);
    if (!pw || !pw->mPhysicsSystem) return 0;

    void* rawBuf = env->GetDirectBufferAddress(byteBuffer);
    if (!rawBuf) return 0;
    
    // Stride: ID (8) + Pos(12) + Rot(16) + LinVel(12) + AngVel(12) = 60 bytes
    // Using simple float layout for easier Java parsing:
    // long id, float x,y,z, qx,qy,qz,qw, vx,vy,vz, avx,avy,avz
    // In ByteBuffer, we write raw bytes. 
    // Let's assume the Java side expects a specific struct layout.
    // Let's write: ID (8 bytes), then 13 floats (52 bytes). Total 60 bytes.
    
    char* buf = static_cast<char*>(rawBuf);
    int count = 0;
    
    JPH::BodyInterface& bi = pw->mPhysicsSystem->GetBodyInterface();
    // iterate active bodies? Or all bodies?
    // Usually we only care about active (moving) bodies for sync.
    JPH::BodyIDVector bodies;
    pw->mPhysicsSystem->GetActiveBodies(JPH::EBodyType::RigidBody, bodies);
    
    for (const JPH::BodyID& id : bodies) {
        if (count >= maxBodies) break;
        
        // Write ID
        jlong bodyIdVal = static_cast<jlong>(id.GetIndexAndSequenceNumber());
        *reinterpret_cast<jlong*>(buf) = bodyIdVal; // Endianness? typical x64 LE is fine for Java ByteBuffer.order(nativeOrder)
        buf += 8;
        
        // Lock body (lighter lock if possible, or assume single threaded sync)
        // Jolt allows reading active bodies without lock if we accept slight tearing, 
        // but BodyLockRead is safer.
        JPH::BodyLockRead lock(pw->mPhysicsSystem->GetBodyLockInterface(), id);
        if (lock.Succeeded()) {
             const JPH::Body& body = lock.GetBody();
             JPH::RVec3 pos = body.GetPosition();
             JPH::Quat rot = body.GetRotation();
             JPH::Vec3 vel = body.GetLinearVelocity();
             JPH::Vec3 angVel = body.GetAngularVelocity();
             
             // Positions are double (RVec3) if JPH_DOUBLE_PRECISION is on. 
             // Provided code uses JPH::Vec3 for gravity so likely single precision or we cast.
             // NativeCreateRigidBody uses floats.
             // We cast to float.
             
             float* fbuf = reinterpret_cast<float*>(buf);
             fbuf[0] = static_cast<float>(pos.GetX());
             fbuf[1] = static_cast<float>(pos.GetY());
             fbuf[2] = static_cast<float>(pos.GetZ());
             
             fbuf[3] = rot.GetX();
             fbuf[4] = rot.GetY();
             fbuf[5] = rot.GetZ();
             fbuf[6] = rot.GetW();
             
             fbuf[7] = vel.GetX();
             fbuf[8] = vel.GetY();
             fbuf[9] = vel.GetZ();
             
             fbuf[10] = angVel.GetX();
             fbuf[11] = angVel.GetY();
             fbuf[12] = angVel.GetZ();
             
             buf += 52; // 13 * 4
             count++;
        } else {
             // Failed lock, rollback buf?
             buf -= 8; 
        }
    }
    
    return count;
}


JNIEXPORT void JNICALL Java_com_example_planetmapper_physics_NativePhysicsEngine_nativeCleanupPhysicsWorld(JNIEnv* env, jclass clazz, jlong worldPtr) {
    auto* pw = reinterpret_cast<PhysicsWorld*>(worldPtr);
    delete pw;
}

}
