package com.example.planetmapper.command;

import com.example.planetmapper.entity.PhysicsBlockEntity;
import com.example.planetmapper.physics.NativePhysicsEngine;
import com.example.planetmapper.physics.PhysicsColliderManager;
import com.example.planetmapper.physics.PhysicsWorldManager;
import com.example.planetmapper.physics.WorldCollisionManager;
import com.example.planetmapper.physics.structure.StructurePhysicsProperties;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.world.phys.AABB;

public class SpawnCubeCommand {
    private static final int MAX_EDGE = 48;
    private static final long MAX_BLOCKS = 8192L;

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("pm_spawn_cube")
                .requires(s -> s.hasPermission(2))
                .then(Commands.argument("size", IntegerArgumentType.integer(1, MAX_EDGE))
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(SpawnCubeCommand::execute))));
    }

    private static int execute(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        if (!PhysicsWorldManager.isNativeAvailable()) {
            source.sendFailure(Component.literal("Native physics engine is not available."));
            return 0;
        }
        int size = IntegerArgumentType.getInteger(context, "size");
        long total = (long) size * (long) size * (long) size;
        if (total > MAX_BLOCKS) {
            source.sendFailure(Component.literal("Cube too large: " + total + " blocks (max " + MAX_BLOCKS + ")"));
            return 0;
        }

        BlockPos origin = BlockPosArgument.getBlockPos(context, "pos");
        int minChunkX = origin.getX() >> 4;
        int minChunkZ = origin.getZ() >> 4;
        int maxChunkX = (origin.getX() + size - 1) >> 4;
        int maxChunkZ = (origin.getZ() + size - 1) >> 4;

        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                if (!level.hasChunk(cx, cz)) {
                    source.sendFailure(Component.literal("Chunk not loaded at " + cx + ", " + cz));
                    return 0;
                }
            }
        }

        LongOpenHashSet dirtyChunks = new LongOpenHashSet();
        NativePhysicsEngine engine = PhysicsWorldManager.getEngine();
        if (engine == null) {
            source.sendFailure(Component.literal("Physics engine is not initialized."));
            return 0;
        }

        int baseX = origin.getX();
        int baseY = origin.getY();
        int baseZ = origin.getZ();
        StructurePhysicsProperties physicsProperties = new StructurePhysicsProperties();
        physicsProperties.addState(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        StructurePhysicsProperties.MaterialSummary material = physicsProperties.snapshot();

        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                for (int z = 0; z < size; z++) {
                    int wx = baseX + x;
                    int wy = baseY + y;
                    int wz = baseZ + z;

                    AABB box = new AABB(wx, wy, wz, wx + 1, wy + 1, wz + 1);
                    long bodyId = engine.createRigidBody(java.util.Collections.singletonList(box), material);
                    if (bodyId <= 0) {
                        source.sendFailure(Component.literal("Failed to create physics body at " + wx + " " + wy + " " + wz));
                        return 0;
                    }

                    PhysicsColliderManager.registerAndSyncBody(level, bodyId, java.util.Collections.singletonList(box),
                        new org.joml.Vector3f((float)box.getCenter().x, (float)box.getCenter().y, (float)box.getCenter().z));

                    PhysicsBlockEntity physicsEntity = com.example.planetmapper.entity.ModEntities.PHYSICS_BLOCK.get().create(level);
                    if (physicsEntity == null) {
                        source.sendFailure(Component.literal("Failed to create physics entity at " + wx + " " + wy + " " + wz));
                        return 0;
                    }

                    physicsEntity.setPos(wx + 0.5, wy + 0.5 - physicsEntity.getBodyYOffset(), wz + 0.5);
                    physicsEntity.setBodyId(bodyId);
                    level.addFreshEntity(physicsEntity);
                    PhysicsWorldManager.registerEntity(physicsEntity);

                    dirtyChunks.add(ChunkPos.asLong(wx >> 4, wz >> 4));
                }
            }
        }

        LongIterator iterator = dirtyChunks.iterator();
        while (iterator.hasNext()) {
            long key = iterator.nextLong();
            WorldCollisionManager.markChunkDirty(level, new ChunkPos(key));
        }

        source.sendSuccess(() -> Component.literal("Spawned physics cube: " + size + "x" + size + "x" + size + " (" + total + " bodies)"), true);
        return 1;
    }
}
