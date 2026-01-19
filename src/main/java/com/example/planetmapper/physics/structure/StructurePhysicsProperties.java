package com.example.planetmapper.physics.structure;

import com.example.planetmapper.physics.BlockPhysicsProfile;
import com.example.planetmapper.physics.BlockPhysicsProfiles;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.level.block.state.BlockState;

public final class StructurePhysicsProperties {
    private double totalMass;
    private double sumFriction;
    private double sumRestitution;
    private double sumLinearDamping;
    private double sumAngularDamping;
    private double sumLevitation;
    private int blockCount;

    public static StructurePhysicsProperties fromBlocks(Long2ObjectOpenHashMap<StructureBlockData> blocks) {
        StructurePhysicsProperties props = new StructurePhysicsProperties();
        if (blocks == null || blocks.isEmpty()) {
            return props;
        }
        blocks.long2ObjectEntrySet().fastForEach(entry -> {
            StructureBlockData data = entry.getValue();
            if (data != null && data.state() != null && !data.state().isAir()) {
                props.addState(data.state());
            }
        });
        return props;
    }

    public void addState(BlockState state) {
        if (state == null || state.isAir()) {
            return;
        }
        BlockPhysicsProfile profile = BlockPhysicsProfiles.profileFor(state);
        addProfile(profile);
    }

    public void removeState(BlockState state) {
        if (state == null || state.isAir()) {
            return;
        }
        BlockPhysicsProfile profile = BlockPhysicsProfiles.profileFor(state);
        removeProfile(profile);
    }

    private void addProfile(BlockPhysicsProfile profile) {
        double mass = Math.max(0.0f, profile.density());
        totalMass += mass;
        sumFriction += profile.friction() * mass;
        sumRestitution += profile.restitution() * mass;
        sumLinearDamping += profile.linearDamping() * mass;
        sumAngularDamping += profile.angularDamping() * mass;
        sumLevitation += profile.levitationAccel() * mass;
        blockCount++;
    }

    private void removeProfile(BlockPhysicsProfile profile) {
        double mass = Math.max(0.0f, profile.density());
        totalMass = Math.max(0.0, totalMass - mass);
        sumFriction = Math.max(0.0, sumFriction - profile.friction() * mass);
        sumRestitution = Math.max(0.0, sumRestitution - profile.restitution() * mass);
        sumLinearDamping = Math.max(0.0, sumLinearDamping - profile.linearDamping() * mass);
        sumAngularDamping = Math.max(0.0, sumAngularDamping - profile.angularDamping() * mass);
        sumLevitation = Math.max(0.0, sumLevitation - profile.levitationAccel() * mass);
        blockCount = Math.max(0, blockCount - 1);
    }

    public MaterialSummary snapshot() {
        if (totalMass <= 1.0E-6) {
            BlockPhysicsProfile defaults = BlockPhysicsProfiles.DEFAULT;
            return new MaterialSummary(0.0f,
                    defaults.friction(),
                    defaults.restitution(),
                    defaults.linearDamping(),
                    defaults.angularDamping(),
                    0.0f,
                    blockCount);
        }
        float mass = (float) totalMass;
        float friction = (float) (sumFriction / totalMass);
        float restitution = (float) (sumRestitution / totalMass);
        float linearDamping = (float) (sumLinearDamping / totalMass);
        float angularDamping = (float) (sumAngularDamping / totalMass);
        float levitationAccel = (float) (sumLevitation / totalMass);
        return new MaterialSummary(mass, friction, restitution, linearDamping, angularDamping, levitationAccel, blockCount);
    }

    public int getBlockCount() {
        return blockCount;
    }

    public record MaterialSummary(float mass,
                                  float friction,
                                  float restitution,
                                  float linearDamping,
                                  float angularDamping,
                                  float levitationAccel,
                                  int blockCount) {
    }
}
