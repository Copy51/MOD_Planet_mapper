package com.example.planetmapper.physics;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

public final class BlockPhysicsProfiles {
    public static final BlockPhysicsProfile DEFAULT = new BlockPhysicsProfile(
            1.0f,
            0.6f,
            0.0f,
            0.05f,
            0.1f,
            0.0f
    );

    private static final BlockPhysicsProfile STONE = new BlockPhysicsProfile(2.0f, 0.7f, 0.0f, 0.05f, 0.1f, 0.0f);
    private static final BlockPhysicsProfile METAL = new BlockPhysicsProfile(3.0f, 0.4f, 0.05f, 0.03f, 0.08f, 0.0f);
    private static final BlockPhysicsProfile WOOD = new BlockPhysicsProfile(0.8f, 0.6f, 0.02f, 0.08f, 0.12f, 0.0f);
    private static final BlockPhysicsProfile GLASS = new BlockPhysicsProfile(1.2f, 0.2f, 0.15f, 0.06f, 0.12f, 0.0f);
    private static final BlockPhysicsProfile ICE = new BlockPhysicsProfile(0.9f, 0.05f, 0.05f, 0.02f, 0.05f, 0.0f);
    private static final BlockPhysicsProfile SLIME = new BlockPhysicsProfile(1.0f, 0.8f, 0.8f, 0.12f, 0.2f, 0.0f);
    private static final BlockPhysicsProfile SOFT = new BlockPhysicsProfile(0.4f, 0.7f, 0.1f, 0.2f, 0.25f, 0.0f);
    private static final BlockPhysicsProfile LIGHT = new BlockPhysicsProfile(0.2f, 0.4f, 0.0f, 0.25f, 0.3f, 0.0f);
    private static final BlockPhysicsProfile SAND = new BlockPhysicsProfile(1.4f, 0.55f, 0.02f, 0.18f, 0.22f, 0.0f);
    private static final BlockPhysicsProfile LEVITATING = new BlockPhysicsProfile(1.5f, 0.6f, 0.02f, 0.05f, 0.1f, 12.0f);

    private static final List<TagProfile> TAG_PROFILES = List.of(
            new TagProfile(BlockPhysicsTags.LEVITATING, LEVITATING),
            new TagProfile(BlockPhysicsTags.SLIME, SLIME),
            new TagProfile(BlockPhysicsTags.ICE, ICE),
            new TagProfile(BlockPhysicsTags.SAND, SAND),
            new TagProfile(BlockPhysicsTags.GLASS, GLASS),
            new TagProfile(BlockPhysicsTags.METAL, METAL),
            new TagProfile(BlockPhysicsTags.STONE, STONE),
            new TagProfile(BlockPhysicsTags.WOOD, WOOD),
            new TagProfile(BlockPhysicsTags.SOFT, SOFT),
            new TagProfile(BlockPhysicsTags.LIGHT, LIGHT)
    );

    private static final Object2ObjectOpenHashMap<Block, BlockPhysicsProfile> CACHE = new Object2ObjectOpenHashMap<>();

    private BlockPhysicsProfiles() {
    }

    public static BlockPhysicsProfile profileFor(BlockState state) {
        if (state == null) {
            return DEFAULT;
        }
        Block block = state.getBlock();
        BlockPhysicsProfile cached = CACHE.get(block);
        if (cached != null) {
            return cached;
        }
        BlockPhysicsProfile resolved = resolveProfile(state, block);
        CACHE.put(block, resolved);
        return resolved;
    }

    private static BlockPhysicsProfile resolveProfile(BlockState state, Block block) {
        if (block == Blocks.END_STONE || block == Blocks.END_STONE_BRICKS) {
            return LEVITATING;
        }
        for (TagProfile entry : TAG_PROFILES) {
            if (state.is(entry.tag())) {
                return entry.profile();
            }
        }
        return DEFAULT;
    }

    public static void clearCache() {
        CACHE.clear();
    }

    private record TagProfile(net.minecraft.tags.TagKey<Block> tag, BlockPhysicsProfile profile) {
    }
}
