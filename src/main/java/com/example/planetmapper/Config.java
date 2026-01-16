package com.example.planetmapper;

import net.neoforged.neoforge.common.ModConfigSpec;

public class Config {
    public static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.IntValue WORLD_RADIUS;
    public static final ModConfigSpec.IntValue PHYSICS_MAX_SELECTION_VOLUME;
    public static final ModConfigSpec.IntValue PHYSICS_BLOCKS_PER_TICK;
    public static final ModConfigSpec.IntValue PHYSICS_COLLISION_BLOCKS_PER_TICK;
    public static final ModConfigSpec.IntValue PHYSICS_COLLISION_REBUILD_DELAY_TICKS;

    static {
        BUILDER.push("general");
        WORLD_RADIUS = BUILDER.comment("The radius of the world (axis center to edge). Coordinates beyond this wrap.")
                .defineInRange("worldRadius", 15000, 100, 30000000);
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
        BUILDER.pop();
        SPEC = BUILDER.build();
    }
}
