package com.example.planetmapper.command;

import com.example.planetmapper.entity.PhysicsStructureEntity;
import com.example.planetmapper.physics.NativePhysicsEngine;
import com.example.planetmapper.physics.PhysicsColliderManager;
import com.example.planetmapper.physics.PhysicsWorldManager;
import com.example.planetmapper.util.StructureScanner;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ShipBuilderCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("pm_ship_build")
                .requires(s -> s.hasPermission(2))
                .executes(ShipBuilderCommand::execute));
    }

    private static int execute(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        
        if (!PhysicsWorldManager.isNativeAvailable()) {
            source.sendFailure(Component.literal("Native physics engine not available."));
            return 0;
        }

        BlockPos startPos = BlockPos.containing(source.getPosition()).below();
        Optional<StructureScanner.StructureResult> resultOpt = StructureScanner.scanStructure(source.getLevel(), startPos);

        if (resultOpt.isEmpty()) {
            source.sendFailure(Component.literal("No structure found at your position (below feet)."));
            return 0;
        }

        try {
            StructureScanner.StructureResult result = resultOpt.get();
            List<AABB> aabbs = StructureScanner.convertToLocalAABBs(result);
            
            // Create Rigid Body
            NativePhysicsEngine engine = PhysicsWorldManager.getEngine();
            // Estimate mass: 10 per block
            float mass = result.blocks().size() * 10.0f;
            
            long bodyId = engine.createRigidBody(aabbs, mass);
            if (bodyId <= 0) {
                source.sendFailure(Component.literal("Failed to create physics body."));
                return 0;
            }

            // Register body
            PhysicsColliderManager.registerAndSyncBody(source.getLevel(), bodyId, aabbs);

            // Create Entity
            PhysicsStructureEntity entity = com.example.planetmapper.entity.ModEntities.PHYSICS_STRUCTURE.get().create(source.getLevel());
            if (entity != null) {
                // Set Position to center of scanned structure
                entity.setPos(result.center().getX() + 0.5, result.center().getY(), result.center().getZ() + 0.5);
                entity.setBodyId(bodyId);
                entity.setStructure(result.blocks());
                
                source.getLevel().addFreshEntity(entity);
                PhysicsWorldManager.registerEntity(entity);
                
                // Force sync to the creator immediately
                if (source.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
                    entity.syncStructure();
                }

                // Clear original blocks
                for (BlockPos pos : result.blocks().keySet()) {
                    source.getLevel().setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                }
                
                source.sendSuccess(() -> Component.literal("Ship created! Blocks: " + result.blocks().size()), true);
                return 1;
            }
            
        } catch (Exception e) {
            e.printStackTrace();
            source.sendFailure(Component.literal("Error: " + e.getMessage()));
        }

        return 0;
    }
}
