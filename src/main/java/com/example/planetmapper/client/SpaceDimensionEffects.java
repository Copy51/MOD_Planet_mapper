package com.example.planetmapper.client;

import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

public class SpaceDimensionEffects extends DimensionSpecialEffects {

    public SpaceDimensionEffects() {
        // CloudHeight=NaN (no clouds), hasGround=false, SkyType=NONE (to disable
        // vanilla sun/moon), forceBright=false, constantAmbient=false
        super(Float.NaN, false, SkyType.NONE, false, false);
    }

    @Override
    public Vec3 getBrightnessDependentFogColor(Vec3 fogColor, float brightness) {
        return Vec3.ZERO; // Black fog
    }

    @Override
    public boolean isFoggyAt(int x, int y) {
        return false;
    }
}
