package com.example.planetmapper.physics;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
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
    private static final Map<ResourceKey<Level>, Long2ObjectOpenHashMap<LongOpenHashSet>> CHUNK_INDEX = new HashMap<>();
    private static final Map<ResourceKey<Level>, Long> LAST_UPDATE_TICK = new HashMap<>();
    private static final double PLATFORM_PROBE_DEPTH = 0.35;
    private static final double PLATFORM_MAX_GAP = 0.25;
    private static final float LOCAL_GRID_CELL_SIZE = 4.0f;

    private PhysicsColliderManager() {
    }

    public static void registerDynamicBody(ResourceKey<Level> dimension, long bodyId, List<AABB> worldBoxes, Vector3f bodyCenter) {
        if (dimension == null || bodyId <= 0 || worldBoxes == null || worldBoxes.isEmpty()) {
            return;
        }
        Vector3f center = bodyCenter != null ? new Vector3f(bodyCenter) : DynamicCollider.computeCenter(worldBoxes);
        DynamicCollider collider = DynamicCollider.fromWorldBoxes(dimension, bodyId, worldBoxes, center);
        synchronized (DYNAMIC_BODIES) {
            DYNAMIC_BODIES.put(bodyId, collider);
            collider.updateTransform(center.x, center.y, center.z, new Quaternionf());
        }
    }

    public static void registerAndSyncBody(ServerLevel level, long bodyId, List<AABB> worldBoxes, Vector3f bodyCenter) {
        registerDynamicBody(level.dimension(), bodyId, worldBoxes, bodyCenter);
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayersInDimension(
                level,
                new com.example.planetmapper.network.DynamicColliderSyncPacket(level.dimension().location(), bodyId, worldBoxes, 
                        bodyCenter.x, bodyCenter.y, bodyCenter.z, 0, 0, 0)
        );
    }

    public static void updateAndSyncBody(ServerLevel level, long bodyId, List<AABB> bodyLocalBoxes) {
        updateDynamicBody(bodyId, bodyLocalBoxes);
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayersInDimension(
                level,
                new com.example.planetmapper.network.DynamicColliderUpdatePacket(level.dimension().location(), bodyId, bodyLocalBoxes)
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

    public static void updateBodyState(long bodyId, float px, float py, float pz, Quaternionf rotation, 
                                      float vx, float vy, float vz, float avx, float avy, float avz) {
        synchronized (DYNAMIC_BODIES) {
            DynamicCollider collider = DYNAMIC_BODIES.get(bodyId);
            if (collider != null) {
                collider.updateTransform(px, py, pz, rotation);
                collider.linearVelocity.set(vx, vy, vz);
                collider.angularVelocity.set(avx, avy, avz);
            }
        }
    }

    public static void updateBodyState(long bodyId, float px, float py, float pz, Quaternionf rotation, float vx, float vy, float vz) {
        synchronized (DYNAMIC_BODIES) {
            DynamicCollider collider = DYNAMIC_BODIES.get(bodyId);
            if (collider != null) {
                collider.updateTransform(px, py, pz, rotation);
                collider.linearVelocity.set(vx, vy, vz);
                collider.angularVelocity.set(0, 0, 0); // Default angular to 0 for old callers
            }
        }
    }

    public static void updateBodyState(long bodyId, float px, float py, float pz, Quaternionf rotation, Vector3f velocity) {
        updateBodyState(bodyId, px, py, pz, rotation, velocity.x, velocity.y, velocity.z);
    }

    public static void updateDynamicBody(long bodyId, List<AABB> bodyLocalBoxes) {
        if (bodyLocalBoxes == null) {
            return;
        }
        synchronized (DYNAMIC_BODIES) {
            DynamicCollider collider = DYNAMIC_BODIES.get(bodyId);
            if (collider != null) {
                collider.setLocalBoxes(bodyLocalBoxes);
            }
        }
    }

    public static Float raycastBody(long bodyId, Vec3 origin, Vec3 direction, double maxDistance) {
        synchronized (DYNAMIC_BODIES) {
            DynamicCollider collider = DYNAMIC_BODIES.get(bodyId);
            if (collider == null) {
                return null;
            }
            return collider.raycast(origin, direction, maxDistance);
        }
    }

    public static void unregisterDynamicBody(long bodyId) {
        synchronized (DYNAMIC_BODIES) {
            DynamicCollider collider = DYNAMIC_BODIES.remove(bodyId);
            if (collider != null) {
                collider.clearChunkIndex();
            }
        }
    }

    public static void resetAll() {
        synchronized (DYNAMIC_BODIES) {
            DYNAMIC_BODIES.clear();
        }
        LAST_UPDATE_TICK.clear();
        CHUNK_INDEX.clear();
    }

    public static List<AABB> getWorldBoxes(ServerLevel level, long bodyId) {
        if (level == null || bodyId <= 0) {
            return Collections.emptyList();
        }
        ensureUpdated(level);
        synchronized (DYNAMIC_BODIES) {
            DynamicCollider collider = DYNAMIC_BODIES.get(bodyId);
            if (collider == null || collider.dimension != level.dimension()) {
                return Collections.emptyList();
            }
            return collider.buildWorldBoxes();
        }
    }

    public static Vector3f getBodyCenter(long bodyId) {
        synchronized (DYNAMIC_BODIES) {
            DynamicCollider collider = DYNAMIC_BODIES.get(bodyId);
            if (collider == null) {
                return null;
            }
            return new Vector3f(collider.lastPx, collider.lastPy, collider.lastPz);
        }
    }

    public static AABB getBodyBounds(long bodyId) {
        synchronized (DYNAMIC_BODIES) {
            DynamicCollider collider = DYNAMIC_BODIES.get(bodyId);
            if (collider == null || collider.bounds == null) {
                return null;
            }
            AABB bounds = collider.bounds;
            return new AABB(bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ);
        }
    }

    public static AABB getBodyBounds(Level level, long bodyId) {
        if (level instanceof ServerLevel serverLevel) {
            ensureUpdated(serverLevel);
        }
        return getBodyBounds(bodyId);
    }

    private static LongOpenHashSet collectCandidateBodiesLocked(ResourceKey<Level> dimension, AABB query) {
        Long2ObjectOpenHashMap<LongOpenHashSet> index = CHUNK_INDEX.get(dimension);
        if (index == null || index.isEmpty()) {
            return null;
        }
        int minChunkX = Mth.floor(query.minX) >> 4;
        int minChunkZ = Mth.floor(query.minZ) >> 4;
        int maxChunkX = Mth.floor(query.maxX) >> 4;
        int maxChunkZ = Mth.floor(query.maxZ) >> 4;

        LongOpenHashSet candidates = null;
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                LongOpenHashSet bodies = index.get(ChunkPos.asLong(cx, cz));
                if (bodies == null || bodies.isEmpty()) {
                    continue;
                }
                if (candidates == null) {
                    candidates = new LongOpenHashSet();
                }
                candidates.addAll(bodies);
            }
        }
        return candidates;
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
            LongOpenHashSet candidates = collectCandidateBodiesLocked(dimension, query);
            if (candidates == null || candidates.isEmpty()) {
                return Collections.emptyList();
            }
            LongIterator iterator = candidates.iterator();
            while (iterator.hasNext()) {
                long bodyId = iterator.nextLong();
                DynamicCollider collider = DYNAMIC_BODIES.get(bodyId);
                if (collider == null || collider.dimension != dimension) {
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

    public static PlatformSupport getPlatformSupport(ServerLevel level, AABB playerBox) {
        if (level == null || playerBox == null) {
            return null;
        }
        ensureUpdated(level);
        SupportInfo support = findSupport(level.dimension(), playerBox);
        if (support == null) {
            return null;
        }
        return new PlatformSupport(support.topY, computePlatformVelocity(support, playerBox));
    }

    public static Vec3 getPlatformVelocity(ServerLevel level, AABB playerBox) {
        PlatformSupport support = getPlatformSupport(level, playerBox);
        return support == null ? null : support.velocity();
    }

    public static Vec3 getPlatformVelocityClient(Level level, AABB playerBox) {
        if (level == null || playerBox == null) {
            return null;
        }
        SupportInfo support = findSupport(level.dimension(), playerBox);
        return support == null ? null : computePlatformVelocity(support, playerBox);
    }

    private static SupportInfo findSupport(ResourceKey<Level> dimension, AABB playerBox) {
        AABB probe = playerBox.expandTowards(0.0, -PLATFORM_PROBE_DEPTH, 0.0);
        double bestTop = Double.NEGATIVE_INFINITY;
        SupportInfo bestSupport = null;

        synchronized (DYNAMIC_BODIES) {
            LongOpenHashSet candidates = collectCandidateBodiesLocked(dimension, probe);
            if (candidates != null) {
                LongIterator iterator = candidates.iterator();
                while (iterator.hasNext()) {
                    long bodyId = iterator.nextLong();
                    DynamicCollider collider = DYNAMIC_BODIES.get(bodyId);
                    if (collider == null || collider.dimension != dimension) {
                        continue;
                    }
                    if (collider.bounds == null || !collider.bounds.intersects(probe)) {
                        continue;
                    }
                    SupportInfo support = collider.findSupport(playerBox, probe, PLATFORM_MAX_GAP);
                    if (support != null && support.topY > bestTop) {
                        bestTop = support.topY;
                        bestSupport = support;
                    }
                }
            }
        }

        return bestSupport;
    }

    private static Vec3 computePlatformVelocity(SupportInfo support, AABB playerBox) {
        Vec3 center = playerBox.getCenter();
        Vec3 playerPos = new Vec3(center.x, playerBox.minY, center.z);
        Vector3f r = new Vector3f((float) playerPos.x - support.bodyX, (float) playerPos.y - support.bodyY, (float) playerPos.z - support.bodyZ);
        Vector3f tangential = new Vector3f(support.angularVelocity).cross(r);

        return new Vec3(support.linearVelocity.x + tangential.x,
                support.linearVelocity.y + tangential.y,
                support.linearVelocity.z + tangential.z);
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
            LongOpenHashSet candidates = collectCandidateBodiesLocked(dimension, region);
            if (candidates == null) {
                return;
            }
            LongIterator iterator = candidates.iterator();
            while (iterator.hasNext()) {
                long bodyId = iterator.nextLong();
                DynamicCollider collider = DYNAMIC_BODIES.get(bodyId);
                if (collider == null || collider.dimension != dimension) {
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
        private List<LocalBox> localBoxes;
        private LocalGrid localGrid = LocalGrid.empty();
        private final Vector3f localBoundsCenter = new Vector3f();
        private final Vector3f localBoundsHalf = new Vector3f();
        private boolean hasLocalBounds = false;
        private final float[] stateBuffer = new float[13];
        private final Vector3f linearVelocity = new Vector3f();
        private final Vector3f angularVelocity = new Vector3f();
        private AABB bounds;
        private final Quaternionf lastRotation = new Quaternionf();
        private boolean hasTransform = false;
        private float lastPx;
        private float lastPy;
        private float lastPz;
        private int minChunkX;
        private int minChunkZ;
        private int maxChunkX;
        private int maxChunkZ;
        private boolean hasChunkRange = false;

        private DynamicCollider(ResourceKey<Level> dimension, long bodyId, List<LocalBox> localBoxes) {
            this.dimension = dimension;
            this.bodyId = bodyId;
            this.localBoxes = localBoxes;
            rebuildSpatialIndex();
        }

        public static DynamicCollider fromWorldBoxes(ResourceKey<Level> dimension, long bodyId, List<AABB> worldBoxes, Vector3f bodyCenter) {
            List<LocalBox> localBoxes = buildLocalBoxes(worldBoxes, bodyCenter);
            return new DynamicCollider(dimension, bodyId, localBoxes);
        }

        private void setLocalBoxes(List<AABB> bodyLocalBoxes) {
            this.localBoxes = buildLocalBoxes(bodyLocalBoxes, new Vector3f());
            rebuildSpatialIndex();
            if (hasTransform) {
                updateTransform(lastPx, lastPy, lastPz, lastRotation);
            }
        }

        private static List<LocalBox> buildLocalBoxes(List<AABB> boxes, Vector3f bodyCenter) {
            List<LocalBox> localBoxes = new ArrayList<>();

            for (AABB box : boxes) {
                double minX = box.minX - bodyCenter.x;
                double minY = box.minY - bodyCenter.y;
                double minZ = box.minZ - bodyCenter.z;
                double maxX = box.maxX - bodyCenter.x;
                double maxY = box.maxY - bodyCenter.y;
                double maxZ = box.maxZ - bodyCenter.z;

                double sizeX = maxX - minX;
                double sizeY = maxY - minY;
                double sizeZ = maxZ - minZ;

                int subdivX = clampSubdivisions(sizeX);
                int subdivY = clampSubdivisions(sizeY);
                int subdivZ = clampSubdivisions(sizeZ);

                double stepX = sizeX / subdivX;
                double stepY = sizeY / subdivY;
                double stepZ = sizeZ / subdivZ;

                Vector3f halfExtents = new Vector3f(
                        (float) (stepX * 0.5),
                        (float) (stepY * 0.5),
                        (float) (stepZ * 0.5)
                );

                for (int x = 0; x < subdivX; x++) {
                    for (int y = 0; y < subdivY; y++) {
                        for (int z = 0; z < subdivZ; z++) {
                            double centerX = minX + (x + 0.5) * stepX;
                            double centerY = minY + (y + 0.5) * stepY;
                            double centerZ = minZ + (z + 0.5) * stepZ;

                            Vector3f localCenter = new Vector3f((float) centerX, (float) centerY, (float) centerZ);
                            localBoxes.add(new LocalBox(localCenter, halfExtents));
                        }
                    }
                }
            }

            return localBoxes;
        }

        private static Vector3f computeCenter(List<AABB> worldBoxes) {
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

            return new Vector3f(
                    (float) ((minX + maxX) * 0.5),
                    (float) ((minY + maxY) * 0.5),
                    (float) ((minZ + maxZ) * 0.5)
            );
        }

        private void rebuildSpatialIndex() {
            if (localBoxes == null || localBoxes.isEmpty()) {
                localGrid = LocalGrid.empty();
                localBoundsCenter.set(0, 0, 0);
                localBoundsHalf.set(0, 0, 0);
                hasLocalBounds = false;
                return;
            }

            double minX = Double.POSITIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY;
            double minZ = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY;
            double maxY = Double.NEGATIVE_INFINITY;
            double maxZ = Double.NEGATIVE_INFINITY;

            for (LocalBox local : localBoxes) {
                minX = Math.min(minX, local.minX);
                minY = Math.min(minY, local.minY);
                minZ = Math.min(minZ, local.minZ);
                maxX = Math.max(maxX, local.maxX);
                maxY = Math.max(maxY, local.maxY);
                maxZ = Math.max(maxZ, local.maxZ);
            }

            localBoundsCenter.set((float) ((minX + maxX) * 0.5), (float) ((minY + maxY) * 0.5), (float) ((minZ + maxZ) * 0.5));
            localBoundsHalf.set((float) ((maxX - minX) * 0.5), (float) ((maxY - minY) * 0.5), (float) ((maxZ - minZ) * 0.5));
            hasLocalBounds = true;
            localGrid = LocalGrid.build(localBoxes, LOCAL_GRID_CELL_SIZE);
        }

        private static int clampSubdivisions(double size) {
            int target = (int) Math.ceil(size);
            if (target < 1) {
                target = 1;
            } else if (target > 4) {
                target = 4;
            }
            return target;
        }

        private void update(NativePhysicsEngine engine) {
            engine.getBodyState(bodyId, stateBuffer);
            float px = stateBuffer[0];
            float py = stateBuffer[1];
            float pz = stateBuffer[2];
            linearVelocity.set(stateBuffer[7], stateBuffer[8], stateBuffer[9]);
            angularVelocity.set(stateBuffer[10], stateBuffer[11], stateBuffer[12]);
            Quaternionf rotation = new Quaternionf(stateBuffer[3], stateBuffer[4], stateBuffer[5], stateBuffer[6]);
            updateTransform(px, py, pz, rotation);
        }

        public void updateTransform(float px, float py, float pz, Quaternionf rotation) {
            this.lastPx = px;
            this.lastPy = py;
            this.lastPz = pz;
            this.lastRotation.set(rotation);
            this.hasTransform = true;

            if (!hasLocalBounds) {
                this.bounds = null;
                updateChunkIndex(null);
                return;
            }

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

            Vector3f center = new Vector3f(localBoundsCenter).rotate(rotation).add(px, py, pz);
            float hx = m00 * localBoundsHalf.x + m01 * localBoundsHalf.y + m02 * localBoundsHalf.z;
            float hy = m10 * localBoundsHalf.x + m11 * localBoundsHalf.y + m12 * localBoundsHalf.z;
            float hz = m20 * localBoundsHalf.x + m21 * localBoundsHalf.y + m22 * localBoundsHalf.z;

            AABB newBounds = new AABB(
                    center.x - hx, center.y - hy, center.z - hz,
                    center.x + hx, center.y + hy, center.z + hz
            );
            this.bounds = newBounds;
            updateChunkIndex(newBounds);
        }

        private void setLinearVelocity(float vx, float vy, float vz) {
            this.linearVelocity.set(vx, vy, vz);
        }

        private void setAngularVelocity(float avx, float avy, float avz) {
            this.angularVelocity.set(avx, avy, avz);
        }

        private void clearChunkIndex() {
            if (!hasChunkRange) {
                return;
            }
            removeChunkRange(minChunkX, minChunkZ, maxChunkX, maxChunkZ);
            hasChunkRange = false;
        }

        private void updateChunkIndex(AABB newBounds) {
            if (newBounds == null) {
                clearChunkIndex();
                return;
            }
            int newMinChunkX = Mth.floor(newBounds.minX) >> 4;
            int newMinChunkZ = Mth.floor(newBounds.minZ) >> 4;
            int newMaxChunkX = Mth.floor(newBounds.maxX) >> 4;
            int newMaxChunkZ = Mth.floor(newBounds.maxZ) >> 4;

            if (hasChunkRange
                    && newMinChunkX == minChunkX && newMinChunkZ == minChunkZ
                    && newMaxChunkX == maxChunkX && newMaxChunkZ == maxChunkZ) {
                return;
            }

            clearChunkIndex();
            addChunkRange(newMinChunkX, newMinChunkZ, newMaxChunkX, newMaxChunkZ);
            minChunkX = newMinChunkX;
            minChunkZ = newMinChunkZ;
            maxChunkX = newMaxChunkX;
            maxChunkZ = newMaxChunkZ;
            hasChunkRange = true;
        }

        private void addChunkRange(int minX, int minZ, int maxX, int maxZ) {
            Long2ObjectOpenHashMap<LongOpenHashSet> index = CHUNK_INDEX.computeIfAbsent(dimension, d -> new Long2ObjectOpenHashMap<>());
            for (int cx = minX; cx <= maxX; cx++) {
                for (int cz = minZ; cz <= maxZ; cz++) {
                    long key = ChunkPos.asLong(cx, cz);
                    LongOpenHashSet set = index.get(key);
                    if (set == null) {
                        set = new LongOpenHashSet();
                        index.put(key, set);
                    }
                    set.add(bodyId);
                }
            }
        }

        private void removeChunkRange(int minX, int minZ, int maxX, int maxZ) {
            Long2ObjectOpenHashMap<LongOpenHashSet> index = CHUNK_INDEX.get(dimension);
            if (index == null) {
                return;
            }
            for (int cx = minX; cx <= maxX; cx++) {
                for (int cz = minZ; cz <= maxZ; cz++) {
                    long key = ChunkPos.asLong(cx, cz);
                    LongOpenHashSet set = index.get(key);
                    if (set == null) {
                        continue;
                    }
                    set.remove(bodyId);
                    if (set.isEmpty()) {
                        index.remove(key);
                    }
                }
            }
        }

        private AABB toWorldAabb(LocalBox local,
                                 float m00, float m01, float m02,
                                 float m10, float m11, float m12,
                                 float m20, float m21, float m22) {
            Vector3f center = new Vector3f(local.center).rotate(lastRotation).add(lastPx, lastPy, lastPz);
            Vector3f half = local.halfExtents;
            float hx = m00 * half.x + m01 * half.y + m02 * half.z;
            float hy = m10 * half.x + m11 * half.y + m12 * half.z;
            float hz = m20 * half.x + m21 * half.y + m22 * half.z;
            return new AABB(
                    center.x - hx, center.y - hy, center.z - hz,
                    center.x + hx, center.y + hy, center.z + hz
            );
        }

        private LocalAabb toLocalAabb(AABB worldAabb) {
            Quaternionf inverse = new Quaternionf(lastRotation).invert();
            float minX = Float.POSITIVE_INFINITY;
            float minY = Float.POSITIVE_INFINITY;
            float minZ = Float.POSITIVE_INFINITY;
            float maxX = Float.NEGATIVE_INFINITY;
            float maxY = Float.NEGATIVE_INFINITY;
            float maxZ = Float.NEGATIVE_INFINITY;

            double[] xs = new double[]{worldAabb.minX, worldAabb.maxX};
            double[] ys = new double[]{worldAabb.minY, worldAabb.maxY};
            double[] zs = new double[]{worldAabb.minZ, worldAabb.maxZ};

            for (double x : xs) {
                for (double y : ys) {
                    for (double z : zs) {
                        Vector3f v = new Vector3f((float) (x - lastPx), (float) (y - lastPy), (float) (z - lastPz));
                        inverse.transform(v);
                        minX = Math.min(minX, v.x);
                        minY = Math.min(minY, v.y);
                        minZ = Math.min(minZ, v.z);
                        maxX = Math.max(maxX, v.x);
                        maxY = Math.max(maxY, v.y);
                        maxZ = Math.max(maxZ, v.z);
                    }
                }
            }
            return new LocalAabb(minX, minY, minZ, maxX, maxY, maxZ);
        }

        private List<AABB> buildWorldBoxes() {
            if (!hasTransform || localBoxes.isEmpty()) {
                return Collections.emptyList();
            }
            Matrix3f rot = new Matrix3f().set(lastRotation);
            float m00 = Math.abs(rot.m00());
            float m01 = Math.abs(rot.m01());
            float m02 = Math.abs(rot.m02());
            float m10 = Math.abs(rot.m10());
            float m11 = Math.abs(rot.m11());
            float m12 = Math.abs(rot.m12());
            float m20 = Math.abs(rot.m20());
            float m21 = Math.abs(rot.m21());
            float m22 = Math.abs(rot.m22());

            List<AABB> boxes = new ArrayList<>(localBoxes.size());
            for (LocalBox local : localBoxes) {
                boxes.add(toWorldAabb(local, m00, m01, m02, m10, m11, m12, m20, m21, m22));
            }
            return boxes;
        }

        private void appendShapes(AABB query, List<VoxelShape> shapes) {
            if (!hasTransform || localBoxes.isEmpty()) {
                return;
            }
            LocalAabb localQuery = toLocalAabb(query);
            IntOpenHashSet candidates = localGrid.collectCandidates(localQuery);
            if (candidates == null || candidates.isEmpty()) {
                return;
            }

            Matrix3f rot = new Matrix3f().set(lastRotation);
            float m00 = Math.abs(rot.m00());
            float m01 = Math.abs(rot.m01());
            float m02 = Math.abs(rot.m02());
            float m10 = Math.abs(rot.m10());
            float m11 = Math.abs(rot.m11());
            float m12 = Math.abs(rot.m12());
            float m20 = Math.abs(rot.m20());
            float m21 = Math.abs(rot.m21());
            float m22 = Math.abs(rot.m22());

            int[] raw = candidates.toIntArray();
            for (int idx : raw) {
                if (idx < 0 || idx >= localBoxes.size()) {
                    continue;
                }
                LocalBox local = localBoxes.get(idx);
                if (!localQuery.intersects(local)) {
                    continue;
                }
                AABB worldBox = toWorldAabb(local, m00, m01, m02, m10, m11, m12, m20, m21, m22);
                if (worldBox.intersects(query)) {
                    shapes.add(Shapes.create(worldBox));
                }
            }
        }

        private Float raycast(Vec3 origin, Vec3 direction, double maxDistance) {
            if (!hasTransform || localBoxes.isEmpty()) {
                return null;
            }
            Quaternionf inverse = new Quaternionf(lastRotation).invert();
            Vector3f originLocal = new Vector3f((float) (origin.x - lastPx), (float) (origin.y - lastPy), (float) (origin.z - lastPz));
            inverse.transform(originLocal);
            Vector3f dirLocal = new Vector3f((float) direction.x, (float) direction.y, (float) direction.z);
            inverse.transform(dirLocal);

            if (dirLocal.lengthSquared() < 1.0E-9f) {
                return null;
            }

            Vector3f endLocal = new Vector3f(dirLocal).mul((float) maxDistance).add(originLocal);
            LocalAabb localQuery = LocalAabb.fromSegment(originLocal, endLocal);
            IntOpenHashSet candidates = localGrid.collectCandidates(localQuery);
            if (candidates == null || candidates.isEmpty()) {
                return null;
            }

            double best = Double.POSITIVE_INFINITY;
            int[] raw = candidates.toIntArray();
            for (int idx : raw) {
                if (idx < 0 || idx >= localBoxes.size()) {
                    continue;
                }
                LocalBox local = localBoxes.get(idx);
                if (!localQuery.intersects(local)) {
                    continue;
                }
                Float hit = intersectRayLocal(originLocal, dirLocal, local);
                if (hit != null && hit >= 0.0 && hit <= maxDistance && hit < best) {
                    best = hit;
                }
            }
            return best == Double.POSITIVE_INFINITY ? null : (float) best;
        }

        private Float intersectRayLocal(Vector3f origin, Vector3f dir, LocalBox box) {
            double tMin = 0.0;
            double tMax = Double.MAX_VALUE;

            double ox = origin.x;
            double oy = origin.y;
            double oz = origin.z;
            double dx = dir.x;
            double dy = dir.y;
            double dz = dir.z;

            if (Math.abs(dx) < 1.0E-9) {
                if (ox < box.minX || ox > box.maxX) return null;
            } else {
                double inv = 1.0 / dx;
                double t1 = (box.minX - ox) * inv;
                double t2 = (box.maxX - ox) * inv;
                tMin = Math.max(tMin, Math.min(t1, t2));
                tMax = Math.min(tMax, Math.max(t1, t2));
            }

            if (Math.abs(dy) < 1.0E-9) {
                if (oy < box.minY || oy > box.maxY) return null;
            } else {
                double inv = 1.0 / dy;
                double t1 = (box.minY - oy) * inv;
                double t2 = (box.maxY - oy) * inv;
                tMin = Math.max(tMin, Math.min(t1, t2));
                tMax = Math.min(tMax, Math.max(t1, t2));
            }

            if (Math.abs(dz) < 1.0E-9) {
                if (oz < box.minZ || oz > box.maxZ) return null;
            } else {
                double inv = 1.0 / dz;
                double t1 = (box.minZ - oz) * inv;
                double t2 = (box.maxZ - oz) * inv;
                tMin = Math.max(tMin, Math.min(t1, t2));
                tMax = Math.min(tMax, Math.max(t1, t2));
            }

            if (tMin > tMax) return null;
            return (float) tMin;
        }

        private SupportInfo findSupport(AABB playerBox, AABB probe, double maxGap) {
            if (!hasTransform || localBoxes.isEmpty()) {
                return null;
            }
            LocalAabb localProbe = toLocalAabb(probe);
            IntOpenHashSet candidates = localGrid.collectCandidates(localProbe);
            if (candidates == null || candidates.isEmpty()) {
                return null;
            }

            Matrix3f rot = new Matrix3f().set(lastRotation);
            float m00 = Math.abs(rot.m00());
            float m01 = Math.abs(rot.m01());
            float m02 = Math.abs(rot.m02());
            float m10 = Math.abs(rot.m10());
            float m11 = Math.abs(rot.m11());
            float m12 = Math.abs(rot.m12());
            float m20 = Math.abs(rot.m20());
            float m21 = Math.abs(rot.m21());
            float m22 = Math.abs(rot.m22());

            double feetY = playerBox.minY;
            double bestTop = Double.NEGATIVE_INFINITY;
            int[] raw = candidates.toIntArray();
            for (int idx : raw) {
                if (idx < 0 || idx >= localBoxes.size()) {
                    continue;
                }
                LocalBox local = localBoxes.get(idx);
                if (!localProbe.intersects(local)) {
                    continue;
                }
                AABB worldBox = toWorldAabb(local, m00, m01, m02, m10, m11, m12, m20, m21, m22);
                if (!worldBox.intersects(probe)) {
                    continue;
                }
                double gap = feetY - worldBox.maxY;
                if (gap < -PLATFORM_PROBE_DEPTH || gap > maxGap) {
                    continue;
                }
                if (worldBox.maxY > bestTop) {
                    bestTop = worldBox.maxY;
                }
            }
            if (bestTop == Double.NEGATIVE_INFINITY) {
                return null;
            }
            return new SupportInfo(bestTop, lastPx, lastPy, lastPz, linearVelocity, angularVelocity);
        }
    }

    public record PlatformSupport(double topY, Vec3 velocity) {
    }

    private record SupportInfo(double topY, float bodyX, float bodyY, float bodyZ, Vector3f linearVelocity, Vector3f angularVelocity) {
    }

    private record LocalAabb(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        boolean intersects(LocalBox box) {
            return box.maxX >= minX && box.minX <= maxX
                    && box.maxY >= minY && box.minY <= maxY
                    && box.maxZ >= minZ && box.minZ <= maxZ;
        }

        static LocalAabb fromSegment(Vector3f start, Vector3f end) {
            float minX = Math.min(start.x, end.x);
            float minY = Math.min(start.y, end.y);
            float minZ = Math.min(start.z, end.z);
            float maxX = Math.max(start.x, end.x);
            float maxY = Math.max(start.y, end.y);
            float maxZ = Math.max(start.z, end.z);
            return new LocalAabb(minX, minY, minZ, maxX, maxY, maxZ);
        }
    }

    private static final class LocalGrid {
        private static final LocalGrid EMPTY = new LocalGrid(1.0f, true);
        private final float cellSize;
        private final float invCellSize;
        private final Long2ObjectOpenHashMap<IntArrayList> cells;

        private LocalGrid(float cellSize, boolean empty) {
            this.cellSize = cellSize;
            this.invCellSize = 1.0f / cellSize;
            this.cells = empty ? null : new Long2ObjectOpenHashMap<>();
        }

        static LocalGrid empty() {
            return EMPTY;
        }

        static LocalGrid build(List<LocalBox> boxes, float cellSize) {
            if (boxes == null || boxes.isEmpty()) {
                return empty();
            }
            LocalGrid grid = new LocalGrid(cellSize, false);
            for (int i = 0; i < boxes.size(); i++) {
                LocalBox box = boxes.get(i);
                grid.insert(i, box);
            }
            return grid;
        }

        private void insert(int index, LocalBox box) {
            int minX = Mth.floor(box.minX * invCellSize);
            int minY = Mth.floor(box.minY * invCellSize);
            int minZ = Mth.floor(box.minZ * invCellSize);
            int maxX = Mth.floor(box.maxX * invCellSize);
            int maxY = Mth.floor(box.maxY * invCellSize);
            int maxZ = Mth.floor(box.maxZ * invCellSize);

            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        long key = BlockPos.asLong(x, y, z);
                        IntArrayList list = cells.get(key);
                        if (list == null) {
                            list = new IntArrayList();
                            cells.put(key, list);
                        }
                        list.add(index);
                    }
                }
            }
        }

        IntOpenHashSet collectCandidates(LocalAabb query) {
            if (cells == null || cells.isEmpty()) {
                return null;
            }
            int minX = Mth.floor(query.minX() * invCellSize);
            int minY = Mth.floor(query.minY() * invCellSize);
            int minZ = Mth.floor(query.minZ() * invCellSize);
            int maxX = Mth.floor(query.maxX() * invCellSize);
            int maxY = Mth.floor(query.maxY() * invCellSize);
            int maxZ = Mth.floor(query.maxZ() * invCellSize);

            IntOpenHashSet result = null;
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        long key = BlockPos.asLong(x, y, z);
                        IntArrayList list = cells.get(key);
                        if (list == null || list.isEmpty()) {
                            continue;
                        }
                        if (result == null) {
                            result = new IntOpenHashSet();
                        }
                        result.addAll(list);
                    }
                }
            }
            return result;
        }
    }

    private static final class LocalBox {
        private final Vector3f center;
        private final Vector3f halfExtents;
        private final float minX;
        private final float minY;
        private final float minZ;
        private final float maxX;
        private final float maxY;
        private final float maxZ;

        private LocalBox(Vector3f center, Vector3f halfExtents) {
            this.center = center;
            this.halfExtents = halfExtents;
            this.minX = center.x - halfExtents.x;
            this.minY = center.y - halfExtents.y;
            this.minZ = center.z - halfExtents.z;
            this.maxX = center.x + halfExtents.x;
            this.maxY = center.y + halfExtents.y;
            this.maxZ = center.z + halfExtents.z;
        }
    }
}
