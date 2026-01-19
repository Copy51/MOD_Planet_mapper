package com.example.planetmapper;

import net.neoforged.neoforge.common.ModConfigSpec;

public class Config {
    public static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.IntValue WORLD_RADIUS;
    public static final ModConfigSpec.BooleanValue WORLD_WRAP_ENABLED;
    public static final ModConfigSpec.IntValue PHYSICS_MAX_SELECTION_VOLUME;
    public static final ModConfigSpec.IntValue PHYSICS_BLOCKS_PER_TICK;
    public static final ModConfigSpec.IntValue PHYSICS_COLLISION_BLOCKS_PER_TICK;
    public static final ModConfigSpec.IntValue PHYSICS_COLLISION_REBUILD_DELAY_TICKS;
    public static final ModConfigSpec.IntValue PHYSICS_SUBSTEPS;
    public static final ModConfigSpec.BooleanValue PHYSICS_WORLD_PHYSICS_ENABLED;
    public static final ModConfigSpec.IntValue PHYSICS_EXPLOSION_MAX_BLOCKS;
    public static final ModConfigSpec.IntValue PHYSICS_EXPLOSION_COLLAPSE_HEIGHT;

    static {
        BUILDER.push("general");
        WORLD_RADIUS = BUILDER.comment("The radius of the world (axis center to edge). Used when wrapping is enabled.")
                .defineInRange("worldRadius", 15000, 100, 30000000);
        WORLD_WRAP_ENABLED = BUILDER.comment("Enable world edge wrapping via teleport/portals.")
                .define("worldWrapEnabled", false);
        BUILDER.pop();

        BUILDER.push("physics");
        PHYSICS_MAX_SELECTION_VOLUME = BUILDER
                .comment("Maximum selection volume for physics structures (blocks).")
                .defineInRange("maxSelectionVolume", 10000000, 1000, Integer.MAX_VALUE);
        PHYSICS_BLOCKS_PER_TICK = BUILDER
                .comment("How many blocks to process per tick when building a structure.")
                .defineInRange("blocksPerTick", 2000, 100, 200000);
        PHYSICS_COLLISION_BLOCKS_PER_TICK = BUILDER
                .comment("How many blocks to scan per tick for chunk collision.")
                .defineInRange("collisionBlocksPerTick", 8000, 500, 500000);
        PHYSICS_COLLISION_REBUILD_DELAY_TICKS = BUILDER
                .comment("Delay (ticks) after block changes before rebuilding chunk collision.")
                .defineInRange("collisionRebuildDelayTicks", 20, 0, 1200);
        PHYSICS_SUBSTEPS = BUILDER
                .comment("Physics substeps per server tick. Higher is more stable but slower.")
                .defineInRange("substeps", 3, 1, 8);
        PHYSICS_WORLD_PHYSICS_ENABLED = BUILDER
                .comment("Enable physics conversion for world events (explosions/collapse).")
                .define("worldPhysicsEnabled", true);
        PHYSICS_EXPLOSION_MAX_BLOCKS = BUILDER
                .comment("Maximum number of blocks converted into physics bodies per explosion.")
                .defineInRange("explosionMaxBlocks", 4096, 128, 1000000);
        PHYSICS_EXPLOSION_COLLAPSE_HEIGHT = BUILDER
                .comment("Additional unsupported blocks to pull from above an explosion. Set to 0 to disable.")
                .defineInRange("explosionCollapseHeight", 16, 0, 128);
        BUILDER.pop();
        SPEC = BUILDER.build();
    }
}
