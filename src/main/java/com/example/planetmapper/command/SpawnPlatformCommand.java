package com.example.planetmapper.command;

import com.example.planetmapper.entity.PhysicsStructureEntity;
import com.example.planetmapper.physics.NativePhysicsEngine;
import com.example.planetmapper.physics.PhysicsColliderManager;
import com.example.planetmapper.physics.PhysicsWorldManager;
import com.example.planetmapper.physics.VoxelShapeOptimizer;
import com.example.planetmapper.physics.structure.PhysicsStructure;
import com.example.planetmapper.physics.structure.StructureBlockData;
import com.example.planetmapper.physics.structure.StructurePhysicsManager;
import com.example.planetmapper.physics.structure.StructurePhysicsProperties;
import com.example.planetmapper.shipyard.ShipyardManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SpawnPlatformCommand {
    private static final int MAX_EDGE = 512;
    private static final int MAX_HEIGHT = 16;
    private static final long MAX_BLOCKS = 200_000L;

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("pm_spawn_platform")
                .requires(s -> s.hasPermission(2))
                .then(Commands.argument("width", IntegerArgumentType.integer(1, MAX_EDGE))
                        .then(Commands.argument("depth", IntegerArgumentType.integer(1, MAX_EDGE))
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(ctx -> execute(ctx, 1)))
                                .then(Commands.argument("height", IntegerArgumentType.integer(1, MAX_HEIGHT))
                                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                                .executes(ctx -> execute(ctx, IntegerArgumentType.getInteger(ctx, "height"))))))));
    }

    private static int execute(CommandContext<CommandSourceStack> context, int height) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        if (!PhysicsWorldManager.isNativeAvailable()) {
            source.sendFailure(Component.literal("Native physics engine is not available."));
            return 0;
        }

        int width = IntegerArgumentType.getInteger(context, "width");
        int depth = IntegerArgumentType.getInteger(context, "depth");
        BlockPos origin = BlockPosArgument.getBlockPos(context, "pos");

        long total = (long) width * (long) depth * (long) height;
        if (total > MAX_BLOCKS) {
            source.sendFailure(Component.literal("Platform too large: " + total + " blocks (max " + MAX_BLOCKS + ")"));
            return 0;
        }

        if (!isAreaEmpty(level, origin, width, height, depth)) {
            source.sendFailure(Component.literal("Platform area is not empty."));
            return 0;
        }

        BlockState state = Blocks.STONE.defaultBlockState();
        Long2ObjectOpenHashMap<StructureBlockData> blocks = new Long2ObjectOpenHashMap<>();
        LongOpenHashSet collidable = new LongOpenHashSet();
        Map<BlockPos, BlockState> renderBlocks = new HashMap<>();

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
                    BlockPos local = new BlockPos(x, y, z);
                    long key = BlockPos.asLong(x, y, z);
                    blocks.put(key, new StructureBlockData(state, null, true));
                    collidable.add(key);
                    renderBlocks.put(local, state);
                }
            }
        }

        List<AABB> localBoxes = VoxelShapeOptimizer.optimizeLongSet(collidable);
        Vector3f worldOrigin = new Vector3f(origin.getX(), origin.getY(), origin.getZ());
        List<AABB> worldBoxes = offsetBoxes(localBoxes, worldOrigin);
        if (worldBoxes.isEmpty()) {
            source.sendFailure(Component.literal("Platform contains no collidable blocks."));
            return 0;
        }

        NativePhysicsEngine engine = PhysicsWorldManager.getEngine();
        if (engine == null) {
            source.sendFailure(Component.literal("Physics engine is not initialized."));
            return 0;
        }

        StructurePhysicsProperties physicsProperties = StructurePhysicsProperties.fromBlocks(blocks);
        StructurePhysicsProperties.MaterialSummary material = physicsProperties.snapshot();
        float mass = Math.max(1.0f, material.mass());
        long bodyId = engine.createRigidBody(worldBoxes, material);
        if (bodyId <= 0) {
            source.sendFailure(Component.literal("Failed to create physics body."));
            return 0;
        }

        float[] stateBuffer = new float[13];
        engine.getBodyState(bodyId, stateBuffer);
        Vector3f bodyPos = new Vector3f(stateBuffer[0], stateBuffer[1], stateBuffer[2]);
        Quaternionf bodyRot = new Quaternionf(stateBuffer[3], stateBuffer[4], stateBuffer[5], stateBuffer[6]);

        Vector3f originOffset = new Vector3f(worldOrigin).sub(bodyPos);
        PhysicsStructure structure = new PhysicsStructure(level.dimension(), bodyId, origin, originOffset, blocks, collidable, physicsProperties);
        StructurePhysicsManager.registerStructure(structure);

        List<AABB> bodyLocal = offsetBoxes(localBoxes, originOffset);
        PhysicsColliderManager.registerAndSyncBody(level, bodyId, worldBoxes, bodyPos);
        PhysicsColliderManager.updateAndSyncBody(level, bodyId, bodyLocal);
        PhysicsColliderManager.updateBodyTransform(bodyId, bodyPos.x, bodyPos.y, bodyPos.z, bodyRot);

        PhysicsStructureEntity entity = com.example.planetmapper.entity.ModEntities.PHYSICS_STRUCTURE.get().create(level);
        if (entity == null) {
            source.sendFailure(Component.literal("Failed to create structure entity."));
            return 0;
        }
        entity.setPos(bodyPos.x, bodyPos.y - entity.getBodyYOffset(), bodyPos.z);
        entity.setBodyId(bodyId);
        entity.setOriginOffset(originOffset);
        entity.setStructure(renderBlocks, Map.of());
        level.addFreshEntity(entity);
        PhysicsWorldManager.registerEntity(entity);
        structure.setEntityId(entity.getId());

        ServerLevel shipyard = ShipyardManager.getShipyardLevel(level.getServer());
        if (shipyard != null) {
            ShipyardManager.ShipyardRegion region = ShipyardManager.ensureRegion(shipyard, entity.getUUID(), bodyId, width, height, depth);
            ShipyardManager.placeBlocks(shipyard, region, blocks);
            ShipyardManager.queueNeighborUpdates(region, blocks);
            ShipyardManager.bindBodyId(entity.getUUID(), bodyId, shipyard);
        }

        source.sendSuccess(() -> Component.literal("Spawned physics platform: " + width + "x" + height + "x" + depth + " (" + total + " blocks)"), true);
        return 1;
    }

    private static boolean isAreaEmpty(ServerLevel level, BlockPos origin, int width, int height, int depth) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
                    cursor.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    if (!level.getBlockState(cursor).isAir()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static List<AABB> offsetBoxes(List<AABB> boxes, Vector3f offset) {
        if (boxes.isEmpty()) {
            return List.of();
        }
        java.util.ArrayList<AABB> result = new java.util.ArrayList<>(boxes.size());
        for (AABB box : boxes) {
            result.add(new AABB(
                    box.minX + offset.x, box.minY + offset.y, box.minZ + offset.z,
                    box.maxX + offset.x, box.maxY + offset.y, box.maxZ + offset.z
            ));
        }
        return result;
    }
}
