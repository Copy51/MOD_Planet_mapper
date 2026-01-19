package com.example.planetmapper.physics;

public record BlockPhysicsProfile(
        float density,
        float friction,
        float restitution,
        float linearDamping,
        float angularDamping,
        float levitationAccel
) {
    public BlockPhysicsProfile {
        density = Math.max(0.0f, density);
        friction = clamp01(friction);
        restitution = clamp01(restitution);
        linearDamping = clamp01(linearDamping);
        angularDamping = clamp01(angularDamping);
    }

    private static float clamp01(float value) {
        if (value < 0.0f) {
            return 0.0f;
        }
        return Math.min(1.0f, value);
    }
}
