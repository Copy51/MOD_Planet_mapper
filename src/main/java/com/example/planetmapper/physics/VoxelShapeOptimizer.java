package com.example.planetmapper.physics;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Implements Greedy Meshing to optimize thousands of Minecraft blocks
 * into a few large AABBs for the native physics engine.
 */
public class VoxelShapeOptimizer {

    /**
     * Takes a list of block positions and returns a list of optimized AABBs.
     * Complexity: O(N) where N is the number of blocks.
     */
    public static List<AABB> optimize(Set<BlockPos> blocks) {
        List<AABB> result = new ArrayList<>();
        Set<BlockPos> processed = new HashSet<>();

        for (BlockPos pos : blocks) {
            if (processed.contains(pos)) continue;

            // Attempt to grow the box in X, then Y, then Z
            int width = 1, height = 1, depth = 1;

            // Grow X
            while (blocks.contains(pos.offset(width, 0, 0)) && !processed.contains(pos.offset(width, 0, 0))) {
                width++;
            }

            // Grow Y
            boolean canGrowY = true;
            while (canGrowY) {
                for (int x = 0; x < width; x++) {
                    if (!blocks.contains(pos.offset(x, height, 0)) || processed.contains(pos.offset(x, height, 0))) {
                        canGrowY = false;
                        break;
                    }
                }
                if (canGrowY) height++;
            }

            // Grow Z
            boolean canGrowZ = true;
            while (canGrowZ) {
                for (int x = 0; x < width; x++) {
                    for (int y = 0; y < height; y++) {
                        if (!blocks.contains(pos.offset(x, y, depth)) || processed.contains(pos.offset(x, y, depth))) {
                            canGrowZ = false;
                            break;
                        }
                    }
                }
                if (canGrowZ) depth++;
            }

            // Mark these blocks as processed
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    for (int z = 0; z < depth; z++) {
                        processed.add(pos.offset(x, y, z));
                    }
                }
            }

            result.add(new AABB(
                    pos.getX(), pos.getY(), pos.getZ(),
                    pos.getX() + width, pos.getY() + height, pos.getZ() + depth
            ));
        }

        return result;
    }
}
