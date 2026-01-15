package com.example.planetmapper.physics;

/**
 * Marker interface for entities backed by a native physics body.
 * Implementations should sync their game state from native data.
 */
public interface PhysicsBodyEntity {
    boolean isAlive();

    default void updateStateFromNative() {
        // No-op by default to keep integration lightweight.
    }
}
