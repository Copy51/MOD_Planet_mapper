package com.example.planetmapper.handler;

import com.example.planetmapper.Config;
import com.example.planetmapper.PlanetMapper;
import com.example.planetmapper.space.CelestialBody;
import com.example.planetmapper.space.CelestialBodyRegistry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import qouteall.imm_ptl.core.api.PortalAPI;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.chunk_loading.ChunkLoader;
import qouteall.q_misc_util.my_util.DQuaternion;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ImmersivePortalsHandler {

    private static final double PORTAL_SPAWN_DISTANCE = 220.0;
    private static final double PORTAL_DESPAWN_DISTANCE = 260.0;
    private static final double PORTAL_PRELOAD_DISTANCE = 600.0;
    private static final int PRELOAD_CHUNK_RADIUS = 8;
    private static final double PORTAL_OFFSET = 2.0;
    private static final double DESTINATION_Y = 310.0;
    private static final double MIN_PORTAL_SIZE = 12.0;
    private static final double MAX_PORTAL_SIZE = 120.0;
    private static final double MAX_SURFACE_PORTAL_SIZE = 256.0;
    private static final double PORTAL_THICKNESS = 1.0;
    private static final double EPS = 1.0e-6;

    private boolean alreadyConfigured = false;
    private final Map<UUID, ActivePortalPair> activePortals = new ConcurrentHashMap<>();
    private final Map<UUID, ChunkLoader> activePreloaders = new ConcurrentHashMap<>();

    private static class ActivePortalPair {
        UUID planetId;
        Portal spacePortal;
        Portal surfacePortal;
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        if (alreadyConfigured) {
            return;
        }

        MinecraftServer server = event.getServer();
        int radius = Config.WORLD_RADIUS.get();

        String command = String.format("portal global create_inward_wrapping %d %d %d %d",
                -radius, -radius, radius, radius);

        try {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                    "portal global clear_wrapping_border");
            PlanetMapper.LOGGER.info("Seamless world borders active.");
            alreadyConfigured = true;
        } catch (Exception e) {
            PlanetMapper.LOGGER.error("Failed to configure Immersive Portals wrapping", e);
        }
    }

    @SubscribeEvent
    public void onPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity().level().isClientSide()) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if ((player.tickCount & 1) != 0) {
            return;
        }

        CelestialBodyRegistry registry = CelestialBodyRegistry.getServerInstance();
        if (player.level().dimension() == PlanetMapper.SPACE_LEVEL) {
            handleSpacePlayer(player, registry);
        } else {
            handleSurfacePlayer(player, registry);
        }
    }

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        // No-op for now, but could be used to initialize state
    }

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            removePortalPair(player.getUUID(), player);
        }
    }

    private void handleSpacePlayer(ServerPlayer player, CelestialBodyRegistry registry) {
        Vec3 pos = player.position();
        CelestialBody nearest = findNearestPlanet(pos, registry);
        if (nearest == null) {
            removePortalPair(player.getUUID(), player);
            return;
        }

        double distToSurface = pos.distanceTo(nearest.getPosition()) - nearest.getRadius();
        
        // Automatic landing if very close (and portals somehow didn't pick up or we want to force it)
        if (distToSurface < 2.0) {
             // If we are this close, we should be on surface.
             // But if we are here, it means we are still in space.
             // Trigger landing logic similar to command.
             performAutomaticLanding(player, nearest);
             return;
        }

        if (distToSurface > PORTAL_PRELOAD_DISTANCE) {
            removePortalPair(player.getUUID(), player);
            return;
        }

        // Manage pre-loading
        if (distToSurface <= PORTAL_PRELOAD_DISTANCE) {
            updatePreloader(player, nearest);
        }

        if (distToSurface > PORTAL_DESPAWN_DISTANCE) {
            removePortalPair(player.getUUID(), player);
            return;
        }

        ActivePortalPair pair = activePortals.get(player.getUUID());
        if (pair != null && !nearest.getId().equals(pair.planetId)) {
            removePortalPair(player.getUUID(), player);
            pair = null;
        }

        if (distToSurface > PORTAL_SPAWN_DISTANCE && pair == null) {
            return;
        }

        updateFromSpace(player, nearest);
    }

    private void handleSurfacePlayer(ServerPlayer player, CelestialBodyRegistry registry) {
        ActivePortalPair pair = activePortals.get(player.getUUID());
        if (pair == null) {
            return;
        }

        CelestialBody planet = findPlanetById(registry, pair.planetId);
        if (planet == null) {
            removePortalPair(player.getUUID(), player);
            return;
        }

        ResourceKey<Level> destKey = getTargetDimensionKey(planet);
        if (destKey == null || player.level().dimension() != destKey) {
            removePortalPair(player.getUUID(), player);
            return;
        }

        updateFromSurface(player, planet);
    }

    private void updatePreloader(ServerPlayer player, CelestialBody planet) {
        Vec3 planetCenter = planet.getPosition();
        Vec3 rel = player.position().subtract(planetCenter);
        if (rel.lengthSqr() < EPS) {
            return;
        }

        Vec3 dirWorld = rel.normalize();
        Quaternionf planetRotation = buildPlanetRotation(planet);
        Vec3 dirLocal = rotateVec(new Quaternionf(planetRotation).conjugate(), dirWorld);

        Vec3 destPos = mapDirToSurface(dirLocal);
        ResourceKey<Level> destKey = getTargetDimensionKey(planet);
        if (destKey == null) {
            return;
        }

        ServerLevel destLevel = player.server.getLevel(destKey);
        if (destLevel == null) {
            return;
        }

        ChunkLoader existing = activePreloaders.get(player.getUUID());
        if (existing != null) {
            // If dimension changed, remove old preloader
            if (!existing.dimension().equals(destKey)) {
                PortalAPI.removeChunkLoaderForPlayer(player, existing);
                existing = null;
            }
        }

        if (existing == null) {
            int chunkX = (int) Math.floor(destPos.x) >> 4;
            int chunkZ = (int) Math.floor(destPos.z) >> 4;
            ChunkLoader loader = new ChunkLoader(destKey, chunkX, chunkZ, PRELOAD_CHUNK_RADIUS);
            PortalAPI.addChunkLoaderForPlayer(player, loader);
            activePreloaders.put(player.getUUID(), loader);
        }
    }

    private void updateFromSpace(ServerPlayer player, CelestialBody planet) {
        Vec3 planetCenter = planet.getPosition();
        Vec3 rel = player.position().subtract(planetCenter);
        if (rel.lengthSqr() < EPS) {
            return;
        }

        Vec3 dirWorld = rel.normalize();
        Quaternionf planetRotation = buildPlanetRotation(planet);
        Vec3 dirLocal = rotateVec(new Quaternionf(planetRotation).conjugate(), dirWorld);

        Vec3 axisWLocal = computeAxisW(dirLocal);
        Vec3 axisHLocal = computeAxisH(dirLocal, axisWLocal);
        Vec3 axisWWorld = rotateVec(planetRotation, axisWLocal);
        Vec3 axisHWorld = rotateVec(planetRotation, axisHLocal);

        Vec3 portalPos = planetCenter.add(dirWorld.scale(planet.getRadius() + PORTAL_OFFSET));
        Vec3 destPos = mapDirToSurface(dirLocal);

        double scale = getScale(planet);
        double distanceToPortal = player.position().distanceTo(portalPos);
        double size = clamp(distanceToPortal * 0.9, MIN_PORTAL_SIZE, MAX_PORTAL_SIZE);

        DQuaternion rotation = computeRotation(axisWWorld, axisHWorld, dirWorld);

        ResourceKey<Level> destKey = getTargetDimensionKey(planet);
        if (destKey == null) {
            removePortalPair(player.getUUID(), player);
            return;
        }
        ServerLevel spaceLevel = (ServerLevel) player.level();
        ServerLevel destLevel = player.server.getLevel(destKey);
        if (destLevel == null) {
            removePortalPair(player.getUUID(), player);
            return;
        }

        ActivePortalPair pair = activePortals.computeIfAbsent(player.getUUID(), id -> new ActivePortalPair());
        pair.planetId = planet.getId();
        applyPortals(player, pair, spaceLevel, destLevel, destKey,
                portalPos, axisWWorld, axisHWorld, destPos, rotation, scale, size, size);
    }

    private void updateFromSurface(ServerPlayer player, CelestialBody planet) {
        Vec3 playerPos = player.position();
        Vec3 dirLocal = mapSurfaceToDir(playerPos);
        Quaternionf planetRotation = buildPlanetRotation(planet);
        Vec3 dirWorld = rotateVec(planetRotation, dirLocal);

        Vec3 axisWLocal = computeAxisW(dirLocal);
        Vec3 axisHLocal = computeAxisH(dirLocal, axisWLocal);
        Vec3 axisWWorld = rotateVec(planetRotation, axisWLocal);
        Vec3 axisHWorld = rotateVec(planetRotation, axisHLocal);

        Vec3 portalPos = planet.getPosition().add(dirWorld.scale(planet.getRadius() + PORTAL_OFFSET));
        Vec3 destPos = new Vec3(playerPos.x, DESTINATION_Y, playerPos.z);

        double scale = getScale(planet);
        double size = clamp(planet.getRadius() * 0.35, MIN_PORTAL_SIZE, MAX_PORTAL_SIZE);
        DQuaternion rotation = computeRotation(axisWWorld, axisHWorld, dirWorld);

        ResourceKey<Level> destKey = getTargetDimensionKey(planet);
        if (destKey == null) {
            removePortalPair(player.getUUID(), player);
            return;
        }
        ServerLevel destLevel = (ServerLevel) player.level();
        ServerLevel spaceLevel = player.server.getLevel(PlanetMapper.SPACE_LEVEL);
        if (spaceLevel == null) {
            removePortalPair(player.getUUID(), player);
            return;
        }

        ActivePortalPair pair = activePortals.computeIfAbsent(player.getUUID(), id -> new ActivePortalPair());
        pair.planetId = planet.getId();
        applyPortals(player, pair, spaceLevel, destLevel, destKey,
                portalPos, axisWWorld, axisHWorld, destPos, rotation, scale, size, size);
    }

    private void applyPortals(ServerPlayer player, ActivePortalPair pair, ServerLevel spaceLevel, ServerLevel destLevel,
                              ResourceKey<Level> destKey, Vec3 originPos, Vec3 axisW, Vec3 axisH, Vec3 destPos,
                              DQuaternion rotation, double scale, double width, double height) {
        Portal spacePortal = pair.spacePortal;
        if (spacePortal == null || spacePortal.isRemoved()) {
            spacePortal = new Portal(Portal.ENTITY_TYPE, spaceLevel);
            PortalAPI.spawnServerEntity(spacePortal);
            pair.spacePortal = spacePortal;
        }

        Portal surfacePortal = pair.surfacePortal;
        if (surfacePortal == null || surfacePortal.isRemoved()) {
            surfacePortal = new Portal(Portal.ENTITY_TYPE, destLevel);
            PortalAPI.spawnServerEntity(surfacePortal);
            pair.surfacePortal = surfacePortal;
        }

        configurePortal(spacePortal, player, originPos, axisW, axisH, destKey, destPos, rotation, scale, width, height);

        Vec3 destAxisW = new Vec3(1.0, 0.0, 0.0);
        Vec3 destAxisH = new Vec3(0.0, 0.0, -1.0);
        double destWidth = clamp(width * scale, MIN_PORTAL_SIZE, MAX_SURFACE_PORTAL_SIZE);
        double destHeight = clamp(height * scale, MIN_PORTAL_SIZE, MAX_SURFACE_PORTAL_SIZE);
        configurePortal(surfacePortal, player, destPos, destAxisW, destAxisH, PlanetMapper.SPACE_LEVEL, originPos,
                rotation.getConjugated(), 1.0 / Math.max(1.0e-6, scale), destWidth, destHeight);

    }

    private void configurePortal(Portal portal, ServerPlayer player, Vec3 originPos, Vec3 axisW, Vec3 axisH,
                                 ResourceKey<Level> destKey, Vec3 destPos, DQuaternion rotation, double scale,
                                 double width, double height) {
        portal.setOriginPos(originPos);
        portal.setOrientationAndSize(axisW, axisH, width, height);
        PortalAPI.setPortalTransformation(portal, destKey, destPos, rotation, scale);
        portal.setThickness(PORTAL_THICKNESS);
        portal.setTeleportable(true);
        portal.setFuseView(true);
        portal.specificPlayerId = player.getUUID();
        portal.reloadAndSyncToClientNextTick();
    }

    private void removePortalPair(UUID playerId, ServerPlayer player) {
        ActivePortalPair pair = activePortals.remove(playerId);
        if (pair != null) {
            discardPortal(pair.spacePortal);
            discardPortal(pair.surfacePortal);
        }

        if (player != null) {
            removePreloader(player);
        } else {
            activePreloaders.remove(playerId);
        }
    }

    private void removePreloader(ServerPlayer player) {
        ChunkLoader loader = activePreloaders.remove(player.getUUID());
        if (loader != null) {
            PortalAPI.removeChunkLoaderForPlayer(player, loader);
        }
    }

    private void performAutomaticLanding(ServerPlayer player, CelestialBody planet) {
        ResourceKey<Level> destKey = getTargetDimensionKey(planet);
        if (destKey == null) return;
        
        ServerLevel destLevel = player.server.getLevel(destKey);
        if (destLevel == null) return;

        Vec3 planetCenter = planet.getPosition();
        Vec3 rel = player.position().subtract(planetCenter);
        Vec3 dirWorld = rel.normalize();

        Quaternionf planetRotation = buildPlanetRotation(planet);
        Vec3 dirLocal = rotateVec(new Quaternionf(planetRotation).conjugate(), dirWorld);
        Vec3 destPos = mapDirToSurface(dirLocal);

        player.teleportTo(destLevel, destPos.x, destPos.y, destPos.z, player.getYRot(), player.getXRot());
    }

    private void discardPortal(Portal portal) {
        if (portal != null && !portal.isRemoved()) {
            portal.discard();
        }
    }

    public static CelestialBody findNearestPlanet(Vec3 pos, CelestialBodyRegistry registry) {
        CelestialBody nearest = null;
        double minDist = Double.MAX_VALUE;

        for (CelestialBody body : registry.getAllBodies()) {
            if (body.getType() != CelestialBody.BodyType.PLANET) {
                continue;
            }
            if (getTargetDimensionKey(body) == null) {
                continue;
            }
            double dist = pos.distanceTo(body.getPosition()) - body.getRadius();
            if (dist < minDist) {
                minDist = dist;
                nearest = body;
            }
        }
        return nearest;
    }

    private CelestialBody findPlanetById(CelestialBodyRegistry registry, UUID id) {
        if (id == null) {
            return null;
        }
        for (CelestialBody body : registry.getAllBodies()) {
            if (body.getType() == CelestialBody.BodyType.PLANET && id.equals(body.getId())) {
                return body;
            }
        }
        return null;
    }

    public static ResourceKey<Level> getTargetDimensionKey(CelestialBody body) {
        ResourceLocation target = body.getTargetDimension();
        if (target == null) {
            target = ResourceLocation.withDefaultNamespace("overworld");
        }
        return ResourceKey.create(Registries.DIMENSION, target);
    }

    public static Vec3 mapDirToSurface(Vec3 dirLocal) {
        float[] uv = new float[2];
        octaEncode(dirLocal, uv);
        float v = 1.0f - uv[1];
        double radius = Config.WORLD_RADIUS.get();
        double worldX = clamp((uv[0] - 0.5) * 2.0 * radius, -radius + 1.0, radius - 1.0);
        double worldZ = clamp((v - 0.5) * 2.0 * radius, -radius + 1.0, radius - 1.0);
        return new Vec3(worldX, DESTINATION_Y, worldZ);
    }

    public static Vec3 mapSurfaceToDir(Vec3 surfacePos) {
        double radius = Config.WORLD_RADIUS.get();
        double u = surfacePos.x / (2.0 * radius) + 0.5;
        double v = 0.5 - surfacePos.z / (2.0 * radius);
        u = clamp(u, 0.0, 1.0);
        v = clamp(v, 0.0, 1.0);
        return octaDecode((float) u, (float) v);
    }

    private double getScale(CelestialBody planet) {
        return Config.WORLD_RADIUS.get() / Math.max(1.0, planet.getRadius());
    }

    private Vec3 computeAxisW(Vec3 dirLocal) {
        Vec3 axis = projectOntoPlane(new Vec3(1.0, 0.0, 0.0), dirLocal);
        if (axis.lengthSqr() < EPS) {
            axis = projectOntoPlane(new Vec3(0.0, 0.0, 1.0), dirLocal);
        }
        if (axis.lengthSqr() < EPS) {
            axis = projectOntoPlane(new Vec3(0.0, 1.0, 0.0), dirLocal);
        }
        axis = axis.normalize();
        Vec3 axisH = dirLocal.cross(axis).normalize();
        if (axisH.dot(new Vec3(0.0, 1.0, 0.0)) < 0.0) {
            axis = axis.scale(-1.0);
        }
        return axis;
    }

    private Vec3 computeAxisH(Vec3 dirLocal, Vec3 axisW) {
        return dirLocal.cross(axisW).normalize();
    }

    public static Quaternionf buildPlanetRotation(CelestialBody body) {
        Quaternionf q = new Quaternionf();
        if (body.getAxialTilt() != 0.0f) {
            q.rotateZ((float) Math.toRadians(body.getAxialTilt()));
        }
        if (body.getCurrentRotation() != 0.0f) {
            q.rotateY((float) Math.toRadians(body.getCurrentRotation()));
        }
        return q;
    }

    public static Vec3 rotateVec(Quaternionf q, Vec3 v) {
        Vector3f vec = new Vector3f((float) v.x, (float) v.y, (float) v.z);
        q.transform(vec);
        return new Vec3(vec.x, vec.y, vec.z);
    }

    private DQuaternion computeRotation(Vec3 axisW, Vec3 axisH, Vec3 normal) {
        DQuaternion src = DQuaternion.matrixToQuaternion(axisW, axisH, normal);
        DQuaternion dst = DQuaternion.matrixToQuaternion(
                new Vec3(1.0, 0.0, 0.0),
                new Vec3(0.0, 0.0, -1.0),
                new Vec3(0.0, 1.0, 0.0));
        return dst.hamiltonProduct(src.getConjugated());
    }

    private Vec3 projectOntoPlane(Vec3 v, Vec3 normal) {
        return v.subtract(normal.scale(v.dot(normal)));
    }

    private static void octaEncode(Vec3 dir, float[] out) {
        float x = (float) dir.x;
        float y = (float) dir.y;
        float z = (float) dir.z;
        float ax = Math.abs(x);
        float ay = Math.abs(y);
        float az = Math.abs(z);
        float inv = 1.0f / Math.max(1.0e-6f, ax + ay + az);
        float nx = x * inv;
        float ny = y * inv;
        float nz = z * inv;

        if (nz < 0.0f) {
            float oldX = nx;
            float oldY = ny;
            float sx = oldX >= 0.0f ? 1.0f : -1.0f;
            float sy = oldY >= 0.0f ? 1.0f : -1.0f;
            nx = (1.0f - Math.abs(oldY)) * sx;
            ny = (1.0f - Math.abs(oldX)) * sy;
        }

        out[0] = clamp(nx * 0.5f + 0.5f, 0.0f, 1.0f);
        out[1] = clamp(ny * 0.5f + 0.5f, 0.0f, 1.0f);
    }

    private static Vec3 octaDecode(float u, float v) {
        float x = u * 2.0f - 1.0f;
        float y = v * 2.0f - 1.0f;
        float z = 1.0f - Math.abs(x) - Math.abs(y);
        float t = Math.max(-z, 0.0f);
        x += x >= 0.0f ? -t : t;
        y += y >= 0.0f ? -t : t;
        float inv = 1.0f / (float) Math.sqrt(x * x + y * y + z * z);
        return new Vec3(x * inv, y * inv, z * inv);
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
