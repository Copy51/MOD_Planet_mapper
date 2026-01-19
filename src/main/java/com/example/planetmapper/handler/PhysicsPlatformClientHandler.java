package com.example.planetmapper.handler;

import com.example.planetmapper.PlanetMapper;
import com.example.planetmapper.physics.PhysicsColliderManager;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

@EventBusSubscriber(modid = PlanetMapper.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public class PhysicsPlatformClientHandler {
    private static final double TICK_SECONDS = 1.0 / 20.0;

    private PhysicsPlatformClientHandler() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        if (minecraft.player.isSpectator() || minecraft.player.isPassenger() || minecraft.player.getAbilities().flying) {
            return;
        }
        if (!minecraft.player.onGround()) {
            return;
        }

        AABB box = minecraft.player.getBoundingBox();
        Vec3 platformVelocity = PhysicsColliderManager.getPlatformVelocityClient(minecraft.level, box);
        if (platformVelocity == null || platformVelocity.lengthSqr() < 1.0E-6) {
            return;
        }

        Vec3 delta = platformVelocity.scale(TICK_SECONDS);
        minecraft.player.setDeltaMovement(minecraft.player.getDeltaMovement().add(delta));
    }
}
