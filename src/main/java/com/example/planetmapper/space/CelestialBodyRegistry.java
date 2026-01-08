package com.example.planetmapper.space;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for all celestial bodies in the space dimension.
 * Handles storage, retrieval, and persistence of bodies.
 */
public class CelestialBodyRegistry {

    private static CelestialBodyRegistry clientInstance;
    private static CelestialBodyRegistry serverInstance;

    private final Map<UUID, CelestialBody> bodies = new ConcurrentHashMap<>();
    private boolean dirty = false; // Tracks if save is needed

    private CelestialBodyRegistry() {
    }

    public static CelestialBodyRegistry getClientInstance() {
        if (clientInstance == null) {
            clientInstance = new CelestialBodyRegistry();
        }
        return clientInstance;
    }

    public static CelestialBodyRegistry getServerInstance() {
        if (serverInstance == null) {
            serverInstance = new CelestialBodyRegistry();
        }
        return serverInstance;
    }

    public static void resetClient() {
        clientInstance = null;
    }

    public static void resetServer() {
        serverInstance = null;
    }

    // Body management
    public void addBody(CelestialBody body) {
        bodies.put(body.getId(), body);
        dirty = true;
    }

    public void removeBody(UUID id) {
        bodies.remove(id);
        dirty = true;
    }

    public CelestialBody getBody(UUID id) {
        return bodies.get(id);
    }

    public Collection<CelestialBody> getAllBodies() {
        return bodies.values();
    }

    public int getBodyCount() {
        return bodies.size();
    }

    public void clear() {
        bodies.clear();
        dirty = true;
    }

    /**
     * Get all bodies within a certain range of a position.
     * Useful for rendering optimization.
     */
    public List<CelestialBody> getBodiesInRange(Vec3 center, double range) {
        List<CelestialBody> result = new ArrayList<>();
        double rangeSq = range * range;

        for (CelestialBody body : bodies.values()) {
            Vec3 pos = body.getWorldPosition(this);
            double distSq = pos.distanceToSqr(center);
            // Include body if center of body OR edge of body is within range
            double bodyEdgeDist = Math.sqrt(distSq) - body.getRadius();
            if (bodyEdgeDist <= range) {
                result.add(body);
            }
        }

        return result;
    }

    /**
     * Tick all bodies (update rotation, orbital position)
     */
    public void tick() {
        for (CelestialBody body : bodies.values()) {
            body.tick();
        }
    }

    // Persistence
    public void markDirty() {
        dirty = true;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void markClean() {
        dirty = false;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        ListTag bodiesList = new ListTag();

        for (CelestialBody body : bodies.values()) {
            bodiesList.add(body.save());
        }

        tag.put("bodies", bodiesList);
        return tag;
    }

    public void load(CompoundTag tag) {
        bodies.clear();

        if (tag.contains("bodies")) {
            ListTag bodiesList = tag.getList("bodies", Tag.TAG_COMPOUND);
            for (int i = 0; i < bodiesList.size(); i++) {
                Tag entry = bodiesList.get(i);
                if (!(entry instanceof CompoundTag bodyTag)) {
                    continue;
                }
                try {
                    CelestialBody body = CelestialBody.load(bodyTag);
                    bodies.put(body.getId(), body);
                } catch (Exception e) {
                    // Skip malformed entries to avoid crashing client load
                }
            }
        }

        dirty = false;
    }

    /**
     * Initialize with default solar system if empty.
     */
    public void initializeDefaultSystem() {
        // Empty by default as per user request
        if (!bodies.isEmpty()) {
            return;
        }

        // No default bodies created. The universe starts empty.

        dirty = true;
    }
}
