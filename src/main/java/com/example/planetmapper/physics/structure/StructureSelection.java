package com.example.planetmapper.physics.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public class StructureSelection {
    private BlockPos pos1;
    private BlockPos pos2;
    private ResourceKey<Level> dimension;

    public void setPos1(ResourceKey<Level> dimension, BlockPos pos) {
        this.dimension = dimension;
        this.pos1 = pos.immutable();
    }

    public boolean setPos2(ResourceKey<Level> dimension, BlockPos pos) {
        if (this.dimension != null && this.dimension != dimension) {
            return false;
        }
        this.dimension = dimension;
        this.pos2 = pos.immutable();
        return true;
    }

    public boolean isComplete() {
        return pos1 != null && pos2 != null && dimension != null;
    }

    public ResourceKey<Level> getDimension() {
        return dimension;
    }

    public BlockPos getMin() {
        if (!isComplete()) {
            return null;
        }
        return new BlockPos(
                Math.min(pos1.getX(), pos2.getX()),
                Math.min(pos1.getY(), pos2.getY()),
                Math.min(pos1.getZ(), pos2.getZ())
        );
    }

    public BlockPos getMax() {
        if (!isComplete()) {
            return null;
        }
        return new BlockPos(
                Math.max(pos1.getX(), pos2.getX()),
                Math.max(pos1.getY(), pos2.getY()),
                Math.max(pos1.getZ(), pos2.getZ())
        );
    }

    public long getVolume() {
        if (!isComplete()) {
            return 0L;
        }
        BlockPos min = getMin();
        BlockPos max = getMax();
        long dx = (long) max.getX() - min.getX() + 1;
        long dy = (long) max.getY() - min.getY() + 1;
        long dz = (long) max.getZ() - min.getZ() + 1;
        return dx * dy * dz;
    }

    public void clear() {
        pos1 = null;
        pos2 = null;
        dimension = null;
    }
}
