package com.example.planetmapper.physics;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class PhysicsColliderManager {
    private static final Long2ObjectOpenHashMap<DynamicCollider> DYNAMIC_BODIES = new Long2ObjectOpenHashMap<>();
    private static final Map<ResourceKey<Level>, Long> LAST_UPDATE_TICK = new HashMap<>();

    private PhysicsColliderManager() {
    }

    public static void registerDynamicBody(ResourceKey<Level> dimension, long bodyId, List<AABB> worldBoxes) {
        if (dimension == null || bodyId <= 0 || worldBoxes == null || worldBoxes.isEmpty()) {
            return;
        }
        DynamicCollider collider = DynamicCollider.fromWorldBoxes(dimension, bodyId, worldBoxes);
        synchronized (DYNAMIC_BODIES) {
            DYNAMIC_BODIES.put(bodyId, collider);
        }
    }
    
    public static void registerAndSyncBody(ServerLevel level, long bodyId, List<AABB> worldBoxes) {
        registerDynamicBody(level.dimension(), bodyId, worldBoxes);
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayersInDimension(
                level,
                new com.example.planetmapper.network.DynamicColliderSyncPacket(level.dimension().location(), bodyId, worldBoxes)
        );
    }
    

    public static void unregisterAndSyncBody(ServerLevel level, long bodyId) {
        unregisterDynamicBody(bodyId);
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayersInDimension(
                level,
                new com.example.planetmapper.network.DynamicColliderRemovePacket(bodyId)
        );
    }
    
    public static void updateBodyTransform(long bodyId, float px, float py, float pz, Quaternionf rotation) {
        synchronized (DYNAMIC_BODIES) {
            DynamicCollider collider = DYNAMIC_BODIES.get(bodyId);
            if (collider != null) {
                collider.updateTransform(px, py, pz, rotation);
            }
        }
    }

    public static void unregisterDynamicBody(long bodyId) {
        synchronized (DYNAMIC_BODIES) {
            DYNAMIC_BODIES.remove(bodyId);
        }
    }

    public static List<VoxelShape> collectCollisionShapes(Entity entity, Vec3 movement) {
        if (!(entity instanceof Player)) {
            return Collections.emptyList();
        }
        // Removed client-side check to allow client prediction
        
        if (!PhysicsWorldManager.isNativeAvailable() && !entity.level().isClientSide()) {
            return Collections.emptyList();
        }
        
        // On server, we update from native. On client, we rely on packets.
        if (entity.level() instanceof ServerLevel level) {
            ensureUpdated(level);
        }

        AABB query = entity.getBoundingBox().expandTowards(movement);
        
        ResourceKey<Level> dimension = entity.level().dimension();
        if (entity.level() instanceof ServerLevel level) {
             dimension = level.dimension();
        }
        List<VoxelShape> shapes = null;

        synchronized (DYNAMIC_BODIES) {
            for (DynamicCollider collider : DYNAMIC_BODIES.values()) {
                if (collider.dimension != dimension) {
                    continue;
                }
                if (collider.bounds == null || !collider.bounds.intersects(query)) {
                    continue;
                }
                if (shapes == null) {
                    shapes = new ArrayList<>();
                }
                collider.appendShapes(query, shapes);
            }
        }

        return shapes == null ? Collections.emptyList() : shapes;
    }

    public static void activateBodiesInRegion(ServerLevel level, AABB region) {
        if (level == null || region == null) {
            return;
        }
        if (!PhysicsWorldManager.isNativeAvailable()) {
            return;
        }
        NativePhysicsEngine engine = PhysicsWorldManager.getEngine();
        if (engine == null) {
            return;
        }

        ensureUpdated(level);
        ResourceKey<Level> dimension = level.dimension();
        synchronized (DYNAMIC_BODIES) {
            for (DynamicCollider collider : DYNAMIC_BODIES.values()) {
                if (collider.dimension != dimension) {
                    continue;
                }
                if (collider.bounds != null && collider.bounds.intersects(region)) {
                    engine.activateBody(collider.bodyId);
                }
            }
        }
    }

    private static void ensureUpdated(ServerLevel level) {
        long tick = level.getGameTime();
        ResourceKey<Level> dimension = level.dimension();
        Long last = LAST_UPDATE_TICK.get(dimension);
        if (last != null && last == tick) {
            return;
        }
        updateForDimension(level, dimension);
        LAST_UPDATE_TICK.put(dimension, tick);
    }

    private static void updateForDimension(ServerLevel level, ResourceKey<Level> dimension) {
        NativePhysicsEngine engine = PhysicsWorldManager.getEngine();
        if (engine == null) {
            return;
        }
        synchronized (DYNAMIC_BODIES) {
            for (DynamicCollider collider : DYNAMIC_BODIES.values()) {
                if (collider.dimension == dimension) {
                    collider.update(engine);
                }
            }
        }
    }

    private static final class DynamicCollider {
        private final ResourceKey<Level> dimension;
        private final long bodyId;
        private final List<LocalBox> localBoxes;
        private final float[] stateBuffer = new float[13];
        private List<AABB> worldBoxes = Collections.emptyList();
        private AABB bounds;

        private DynamicCollider(ResourceKey<Level> dimension, long bodyId, List<LocalBox> localBoxes) {
            this.dimension = dimension;
            this.bodyId = bodyId;
            this.localBoxes = localBoxes;
        }

        private static DynamicCollider fromWorldBoxes(ResourceKey<Level> dimension, long bodyId, List<AABB> worldBoxes) {
            double minX = Double.POSITIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY;
            double minZ = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY;
            double maxY = Double.NEGATIVE_INFINITY;
            double maxZ = Double.NEGATIVE_INFINITY;

            for (AABB box : worldBoxes) {
                minX = Math.min(minX, box.minX);
                minY = Math.min(minY, box.minY);
                minZ = Math.min(minZ, box.minZ);
                maxX = Math.max(maxX, box.maxX);
                maxY = Math.max(maxY, box.maxY);
                maxZ = Math.max(maxZ, box.maxZ);
            }

            Vector3f bodyCenter = new Vector3f(
                    (float) ((minX + maxX) * 0.5),
                    (float) ((minY + maxY) * 0.5),
                    (float) ((minZ + maxZ) * 0.5)
            );

            List<LocalBox> localBoxes = new ArrayList<>(worldBoxes.size());
            for (AABB box : worldBoxes) {
                float centerX = (float) ((box.minX + box.maxX) * 0.5);
                float centerY = (float) ((box.minY + box.maxY) * 0.5);
                float centerZ = (float) ((box.minZ + box.maxZ) * 0.5);
                Vector3f localCenter = new Vector3f(centerX, centerY, centerZ).sub(bodyCenter);
                Vector3f halfExtents = new Vector3f(
                        (float) ((box.maxX - box.minX) * 0.5),
                        (float) ((box.maxY - box.minY) * 0.5),
                        (float) ((box.maxZ - box.minZ) * 0.5)
                );
                localBoxes.add(new LocalBox(localCenter, halfExtents));
            }

            return new DynamicCollider(dimension, bodyId, localBoxes);
        }

        private void update(NativePhysicsEngine engine) {
            engine.getBodyState(bodyId, stateBuffer);
            float px = stateBuffer[0];
            float py = stateBuffer[1];
            float pz = stateBuffer[2];
            Quaternionf rotation = new Quaternionf(stateBuffer[3], stateBuffer[4], stateBuffer[5], stateBuffer[6]);
            updateTransform(px, py, pz, rotation);
        }

        public void updateTransform(float px, float py, float pz, Quaternionf rotation) {
            Matrix3f rot = new Matrix3f().set(rotation);
            float m00 = Math.abs(rot.m00());
            float m01 = Math.abs(rot.m01());
            float m02 = Math.abs(rot.m02());
            float m10 = Math.abs(rot.m10());
            float m11 = Math.abs(rot.m11());
            float m12 = Math.abs(rot.m12());
            float m20 = Math.abs(rot.m20());
            float m21 = Math.abs(rot.m21());
            float m22 = Math.abs(rot.m22());

            List<AABB> updatedBoxes = new ArrayList<>(localBoxes.size());
            double minX = Double.POSITIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY;
            double minZ = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY;
            double maxY = Double.NEGATIVE_INFINITY;
            double maxZ = Double.NEGATIVE_INFINITY;

            for (LocalBox local : localBoxes) {
                Vector3f center = new Vector3f(local.center).rotate(rotation).add(px, py, pz);
                Vector3f half = local.halfExtents;
                float hx = m00 * half.x + m01 * half.y + m02 * half.z;
                float hy = m10 * half.x + m11 * half.y + m12 * half.z;
                float hz = m20 * half.x + m21 * half.y + m22 * half.z;

                double minBoxX = center.x - hx;
                double minBoxY = center.y - hy;
                double minBoxZ = center.z - hz;
                double maxBoxX = center.x + hx;
                double maxBoxY = center.y + hy;
                double maxBoxZ = center.z + hz;

                minX = Math.min(minX, minBoxX);
                minY = Math.min(minY, minBoxY);
                minZ = Math.min(minZ, minBoxZ);
                maxX = Math.max(maxX, maxBoxX);
                maxY = Math.max(maxY, maxBoxY);
                maxZ = Math.max(maxZ, maxBoxZ);

                updatedBoxes.add(new AABB(minBoxX, minBoxY, minBoxZ, maxBoxX, maxBoxY, maxBoxZ));
            }

            this.worldBoxes = updatedBoxes;
            if (!updatedBoxes.isEmpty()) {
                this.bounds = new AABB(minX, minY, minZ, maxX, maxY, maxZ);
            } else {
                this.bounds = null;
            }
        }

        private void appendShapes(AABB query, List<VoxelShape> shapes) {
            for (AABB box : worldBoxes) {
                if (box.intersects(query)) {
                    shapes.add(Shapes.create(box));
                }
            }
        }
    }

    private record LocalBox(Vector3f center, Vector3f halfExtents) {
    }
}
