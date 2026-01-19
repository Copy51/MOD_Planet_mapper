package com.example.planetmapper.physics;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

public final class BlockPhysicsTags {
    public static final TagKey<Block> STONE = tag("physics/stone");
    public static final TagKey<Block> METAL = tag("physics/metal");
    public static final TagKey<Block> WOOD = tag("physics/wood");
    public static final TagKey<Block> GLASS = tag("physics/glass");
    public static final TagKey<Block> ICE = tag("physics/ice");
    public static final TagKey<Block> SLIME = tag("physics/slime");
    public static final TagKey<Block> SOFT = tag("physics/soft");
    public static final TagKey<Block> LIGHT = tag("physics/light");
    public static final TagKey<Block> SAND = tag("physics/sand");
    public static final TagKey<Block> LEVITATING = tag("physics/levitating");

    private BlockPhysicsTags() {
    }

    private static TagKey<Block> tag(String path) {
        return TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("planetmapper", path));
    }
}
