package com.example.planetmapper.physics;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

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

    public static List<AABB> optimizeLongSet(LongOpenHashSet blocks) {
        List<AABB> result = new ArrayList<>();
        LongOpenHashSet processed = new LongOpenHashSet();

        LongIterator iterator = blocks.iterator();
        while (iterator.hasNext()) {
            long key = iterator.nextLong();
            if (processed.contains(key)) {
                continue;
            }

            int x = BlockPos.getX(key);
            int y = BlockPos.getY(key);
            int z = BlockPos.getZ(key);

            int width = 1;
            while (containsBlock(blocks, processed, x + width, y, z)) {
                width++;
            }

            int height = 1;
            boolean canGrowY = true;
            while (canGrowY) {
                for (int dx = 0; dx < width; dx++) {
                    if (!containsBlock(blocks, processed, x + dx, y + height, z)) {
                        canGrowY = false;
                        break;
                    }
                }
                if (canGrowY) {
                    height++;
                }
            }

            int depth = 1;
            boolean canGrowZ = true;
            while (canGrowZ) {
                for (int dx = 0; dx < width; dx++) {
                    for (int dy = 0; dy < height; dy++) {
                        if (!containsBlock(blocks, processed, x + dx, y + dy, z + depth)) {
                            canGrowZ = false;
                            break;
                        }
                    }
                    if (!canGrowZ) {
                        break;
                    }
                }
                if (canGrowZ) {
                    depth++;
                }
            }

            for (int dx = 0; dx < width; dx++) {
                for (int dy = 0; dy < height; dy++) {
                    for (int dz = 0; dz < depth; dz++) {
                        processed.add(BlockPos.asLong(x + dx, y + dy, z + dz));
                    }
                }
            }

            result.add(new AABB(
                    x, y, z,
                    x + width, y + height, z + depth
            ));
        }

        return result;
    }

    private static boolean containsBlock(LongOpenHashSet blocks, LongOpenHashSet processed, int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        return blocks.contains(key) && !processed.contains(key);
    }
}
