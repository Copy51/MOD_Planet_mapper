package com.example.planetmapper.util;

import com.example.planetmapper.entity.PhysicsStructureEntity;
import com.example.planetmapper.physics.PhysicsColliderManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.*;

public class StructureScanner {

    private static final int MAX_BLOCKS = 10000;

    public record StructureResult(Map<BlockPos, BlockState> blocks, BoundingBox bounds, BlockPos center) {}

    /**
     * Scans a connected structure starting from the given position.
     * Uses BFS to find all connected blocks that are not air.
     */
    public static Optional<StructureResult> scanStructure(Level level, BlockPos startPos) {
        if (level.isEmptyBlock(startPos)) {
            return Optional.empty();
        }

        Map<BlockPos, BlockState> blocks = new HashMap<>();
        Queue<BlockPos> queue = new LinkedList<>();
        Set<BlockPos> visited = new HashSet<>();

        queue.add(startPos);
        visited.add(startPos);

        int minX = startPos.getX(), minY = startPos.getY(), minZ = startPos.getZ();
        int maxX = startPos.getX(), maxY = startPos.getY(), maxZ = startPos.getZ();

        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            BlockState state = level.getBlockState(current);

            if (state.isAir() || state.is(Blocks.WATER) || state.is(Blocks.LAVA)) {
                continue;
            }

            blocks.put(current, state);

            minX = Math.min(minX, current.getX());
            minY = Math.min(minY, current.getY());
            minZ = Math.min(minZ, current.getZ());
            maxX = Math.max(maxX, current.getX());
            maxY = Math.max(maxY, current.getY());
            maxZ = Math.max(maxZ, current.getZ());

            if (blocks.size() >= MAX_BLOCKS) {
                break;
            }

            for (Direction dir : Direction.values()) {
                BlockPos neighbor = current.relative(dir);
                if (!visited.contains(neighbor)) {
                    BlockState neighborState = level.getBlockState(neighbor);
                    if (!neighborState.isAir() && !neighborState.is(Blocks.WATER) && !neighborState.is(Blocks.LAVA)) {
                        visited.add(neighbor);
                        queue.add(neighbor);
                    }
                }
            }
        }

        if (blocks.isEmpty()) {
            return Optional.empty();
        }

        BoundingBox bounds = new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
        BlockPos center = new BlockPos(
            (minX + maxX) / 2,
            (minY + maxY) / 2,
            (minZ + maxZ) / 2
        );

        return Optional.of(new StructureResult(blocks, bounds, center));
    }

    /**
     * Converts a scanned structure into a list of AABBs relative to the center.
     */
    public static List<AABB> convertToLocalAABBs(StructureResult result) {
        List<AABB> aabbs = new ArrayList<>();
        BlockPos center = result.center;

        for (BlockPos pos : result.blocks.keySet()) {
            double x = pos.getX() - center.getX();
            double y = pos.getY() - center.getY();
            double z = pos.getZ() - center.getZ();
            aabbs.add(new AABB(x, y, z, x + 1, y + 1, z + 1));
        }
        return aabbs;
    }
}
