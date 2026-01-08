package com.example.planetmapper.handler;

import com.example.planetmapper.PlanetMapper;
import com.example.planetmapper.space.CelestialBody;
import com.example.planetmapper.space.CelestialBodyRegistry;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.Collection;

/**
 * Handles N-Body physics updates for unique, realistic gravity.
 * Universal Gravitation: F = G * m1 * m2 / r^2
 */
@EventBusSubscriber(modid = PlanetMapper.MODID, bus = EventBusSubscriber.Bus.GAME)
public class SpacePhysicsHandler {

    // Gravitational constant. Scaled for gameplay.
    // Real G is 6.67e-11, but our masses/distances are not real scale.
    // Adjusted to make "100 mass" and "2000 distance" feel okay.
    private static final double G = 0.5;

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel().isClientSide() || event.getLevel().dimension() != PlanetMapper.SPACE_LEVEL) {
            return;
        }

        CelestialBodyRegistry registry = CelestialBodyRegistry.getServerInstance();
        Collection<CelestialBody> bodies = registry.getAllBodies();

        applyGravity(bodies);

        // Update positions (Integration)
        for (CelestialBody body : bodies) {
            body.tick(); // Rotation

            // Velocity Verlet / Euler integration
            Vec3 pos = body.getPosition();
            Vec3 vel = body.getVelocity();

            body.setPosition(pos.add(vel));
        }

        if (!bodies.isEmpty()) {
            registry.markDirty(); // Need to save changes eventually
        }
    }

    private static void applyGravity(Collection<CelestialBody> bodies) {
        for (CelestialBody a : bodies) {
            for (CelestialBody b : bodies) {
                if (a == b)
                    continue;

                Vec3 posA = a.getPosition();
                Vec3 posB = b.getPosition();

                // Vector from A to B
                Vec3 dir = posB.subtract(posA);
                double distSq = dir.lengthSqr();
                double dist = Math.sqrt(distSq);

                if (dist < 1.0)
                    continue; // Collision/inside -> ignore to prevent singularity

                // Force magnitude: F = G * Ma * Mb / r^2
                // Force on A: F_a = F * normalized(dir)
                // Accel on A: Acc_a = F_a / Ma = (G * Mb / r^2) * normalized(dir)

                double forceMag = G * b.getMass() / distSq;
                Vec3 accel = dir.normalize().scale(forceMag);

                a.setVelocity(a.getVelocity().add(accel));
            }
        }
    }
}
