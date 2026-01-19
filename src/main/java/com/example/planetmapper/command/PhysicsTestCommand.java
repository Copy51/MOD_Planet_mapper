package com.example.planetmapper.command;

import com.example.planetmapper.physics.NativePhysicsEngine;
import com.example.planetmapper.physics.PhysicsBodyEntityAdapter;
import com.example.planetmapper.physics.PhysicsColliderManager;
import com.example.planetmapper.physics.PhysicsWorldManager;
import com.example.planetmapper.physics.WorldCollisionManager;
import com.example.planetmapper.physics.structure.StructurePhysicsProperties;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.core.BlockPos;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;

public class PhysicsTestCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("pm_physics_test")
                .requires(s -> s.hasPermission(2))
                .executes(PhysicsTestCommand::execute));
    }

    private static int execute(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        
        if (!PhysicsWorldManager.isNativeAvailable()) {
            source.sendFailure(Component.literal("Native physics engine is NOT available. Check logs for initialization errors."));
            return 0;
        }

        try {
            NativePhysicsEngine engine = PhysicsWorldManager.getEngine();
            Vec3 pos = source.getPosition();

            ChunkPos chunkPos = new ChunkPos(BlockPos.containing(pos));
            WorldCollisionManager.ensureChunkCollider(source.getLevel(), chunkPos);
            if (!WorldCollisionManager.isChunkColliderReady(source.getLevel(), chunkPos)) {
                source.sendFailure(Component.literal("Chunk collision not ready yet. Wait a few seconds and try again."));
                return 0;
            }
            
            // Create a simple box shape 1x1x1 at player position
            AABB box = new AABB(
                pos.x - 0.5, pos.y, pos.z - 0.5,
                pos.x + 0.5, pos.y + 1.0, pos.z + 0.5
            );
            
            StructurePhysicsProperties physicsProperties = new StructurePhysicsProperties();
            physicsProperties.addState(Blocks.STONE.defaultBlockState());
            StructurePhysicsProperties.MaterialSummary material = physicsProperties.snapshot();
            long bodyId = engine.createRigidBody(Collections.singletonList(box), material);
            if (bodyId <= 0) {
                source.sendFailure(Component.literal("Failed to create physics body."));
                return 0;
            }

            PhysicsColliderManager.registerAndSyncBody(source.getLevel(), bodyId, Collections.singletonList(box), 
                new org.joml.Vector3f((float)box.getCenter().x, (float)box.getCenter().y, (float)box.getCenter().z));

            com.example.planetmapper.entity.PhysicsBlockEntity physicsEntity = com.example.planetmapper.entity.ModEntities.PHYSICS_BLOCK.get().create(source.getLevel());
            if (physicsEntity == null) {
                source.sendFailure(Component.literal("Failed to create physics block entity."));
                return 0;
            }

            // Spawn at feet position (center - 0.5) to match physics offset
            physicsEntity.setPos(pos.x, pos.y + 0.5 - physicsEntity.getBodyYOffset(), pos.z);
            physicsEntity.setBodyId(bodyId);
            source.getLevel().addFreshEntity(physicsEntity);
            PhysicsWorldManager.registerEntity(physicsEntity);

            source.sendSuccess(() -> Component.literal("Physics block spawned! Body ID: " + bodyId), true);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Error creating physics body: " + e.getMessage()));
            e.printStackTrace();
            return 0;
        }
    }
}
