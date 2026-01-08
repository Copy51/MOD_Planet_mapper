package com.example.planetmapper.physics;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Utility class for coordinate transformations and interpolation.
 */
public class PhysicsMathUtils {

    /**
     * Interpolates position and rotation between two states.
     * used for smooth rendering on the client side at 60+ FPS.
     */
    public record State(Vector3f position, Quaternionf rotation) {}

    public static State interpolate(State prev, State current, float partialTicks) {
        Vector3f lerpPos = new Vector3f();
        prev.position.lerp(current.position, partialTicks, lerpPos);

        Quaternionf slerpRot = new Quaternionf();
        prev.rotation.slerp(current.rotation, partialTicks, slerpRot);

        return new State(lerpPos, slerpRot);
    }

    /**
     * Converts Minecraft World Space to Physics Local Space.
     * Useful for voxelization relative to the ship's center.
     */
    public static Vector3f worldToLocal(Vec3 worldPos, Vec3 bodyOrigin, Quaternionf bodyRotation) {
        Vector3f local = new Vector3f(
                (float)(worldPos.x - bodyOrigin.x),
                (float)(worldPos.y - bodyOrigin.y),
                (float)(worldPos.z - bodyOrigin.z)
        );
        
        // Inverse rotation: Local = Rotation^-1 * (World - Origin)
        Quaternionf invRot = new Quaternionf(bodyRotation).invert();
        invRot.transform(local);
        
        return local;
    }

    /**
     * Converts Physics Local Space to Minecraft World Space.
     */
    public static Vec3 localToWorld(Vector3f localPos, Vec3 bodyOrigin, Quaternionf bodyRotation) {
        Vector3f rotated = new Vector3f(localPos);
        bodyRotation.transform(rotated);
        
        return new Vec3(
                rotated.x() + bodyOrigin.x,
                rotated.y() + bodyOrigin.y,
                rotated.z() + bodyOrigin.z
        );
    }
}
