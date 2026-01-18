package com.example.planetmapper.physics.structure;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

public record StructureBlockData(BlockState state, CompoundTag blockEntityTag, boolean collidable) {
}
