package com.example.planetmapper;

import net.neoforged.neoforge.common.ModConfigSpec;

public class Config {
    public static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.IntValue WORLD_RADIUS;

    static {
        BUILDER.push("general");
        WORLD_RADIUS = BUILDER.comment("The radius of the world (axis center to edge). Coordinates beyond this wrap.")
                .defineInRange("worldRadius", 15000, 100, 30000000);
        BUILDER.pop();
        SPEC = BUILDER.build();
    }
}
