package com.example.planetmapper.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;

public class PhysicsStructureEntity extends PhysicsBlockEntity {

    private final Map<BlockPos, BlockState> localBlocks = new HashMap<>();

    // Control inputs
    private float inputThrust;
    private float inputStrafe;
    private float inputVertical;
    private float inputYaw;

    public PhysicsStructureEntity(EntityType<?> entityType, Level level) {
        super(entityType, level);
    }

    public void updateControls(float thrust, float strafe, float vertical, float yaw) {
        this.inputThrust = thrust;
        this.inputStrafe = strafe;
        this.inputVertical = vertical;
        this.inputYaw = yaw;
    }

    @Override
    public void tick() {
        super.tick();
        if (!this.level().isClientSide) {
            applyControlForces();
        }
    }

    private void applyControlForces() {
        long bodyId = getBodyId();
        if (bodyId <= 0) return;

        var engine = com.example.planetmapper.physics.PhysicsWorldManager.getEngine();
        if (engine == null) return;

        // Simple flight model
        // Thrust: Forward vector
        // Strafe: Right vector
        // Vertical: Up vector
        
        org.joml.Quaternionf rot = getPhysicsRotation(1.0f);
        org.joml.Vector3f forward = new org.joml.Vector3f(0, 0, 1).rotate(rot); // Assuming Z is forward
        org.joml.Vector3f up = new org.joml.Vector3f(0, 1, 0).rotate(rot);
        org.joml.Vector3f right = new org.joml.Vector3f(1, 0, 0).rotate(rot);

        float forceMult = 5000.0f; // Arbitrary force multiplier, depends on mass

        org.joml.Vector3f force = new org.joml.Vector3f();
        
        if (Math.abs(inputThrust) > 0.01f) {
            force.add(forward.mul(inputThrust * forceMult));
        }
        if (Math.abs(inputStrafe) > 0.01f) {
            force.add(right.mul(inputStrafe * forceMult));
        }
        if (Math.abs(inputVertical) > 0.01f) {
            force.add(up.mul(inputVertical * forceMult));
        }
        
        if (force.lengthSquared() > 0.001f) {
            engine.applyForce(bodyId, force);
            engine.activateBody(bodyId);
        }

        // Torque for rotation (Yaw)
        // Native engine needs applyTorque? 
        // For now, let's just apply force at offset or wait for native support for torque.
        // Assuming updateControls sends yaw, we can hack it by applying force at front/back.
    }

    @Override
    public net.minecraft.world.InteractionResult interact(net.minecraft.world.entity.player.Player player, net.minecraft.world.InteractionHand hand) {
        if (!this.level().isClientSide) {
            return player.startRiding(this) ? net.minecraft.world.InteractionResult.CONSUME : net.minecraft.world.InteractionResult.PASS;
        }
        return net.minecraft.world.InteractionResult.PASS;
    }
    
    // Allow viewer to control direction
     @Override
    public net.minecraft.world.entity.LivingEntity getControllingPassenger() {
        net.minecraft.world.entity.Entity passenger = this.getFirstPassenger();
        return passenger instanceof net.minecraft.world.entity.LivingEntity living ? living : null;
    }

    public void setStructure(Map<BlockPos, BlockState> blocks) {
        this.localBlocks.clear();
        this.localBlocks.putAll(blocks);
        if (!this.level().isClientSide) {
           syncStructure();
        }
    }
    
    public void syncStructure() {
        CompoundTag tag = new CompoundTag();
        this.addAdditionalSaveData(tag); 
        
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingEntityAndSelf(
                this,
                new com.example.planetmapper.network.StructureSyncPacket(this.getId(), tag)
        );
    }

    public void readStructureData(CompoundTag compound) {
        this.readAdditionalSaveData(compound);
    }

    public Map<BlockPos, BlockState> getStructure() {
        return localBlocks;
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag compound) {
        super.addAdditionalSaveData(compound);
        
        ListTag blockList = new ListTag();
        for (Map.Entry<BlockPos, BlockState> entry : localBlocks.entrySet()) {
            CompoundTag result = new CompoundTag();
            result.putLong("Pos", entry.getKey().asLong());
            result.put("State", NbtUtils.writeBlockState(entry.getValue()));
            blockList.add(result);
        }
        compound.put("Structure", blockList);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag compound) {
        super.readAdditionalSaveData(compound);
        
        if (compound.contains("Structure", 9)) { // 9 is ListTag
            ListTag blockList = compound.getList("Structure", 10);
            localBlocks.clear();
            for (int i = 0; i < blockList.size(); i++) {
                CompoundTag tag = blockList.getCompound(i);
                BlockPos pos = BlockPos.of(tag.getLong("Pos"));
                BlockState state = NbtUtils.readBlockState(this.level().holderLookup(net.minecraft.core.registries.Registries.BLOCK), tag.getCompound("State"));
                localBlocks.put(pos, state);
            }
        }
    }
}
