package com.example.planetmapper.handler;

import com.example.planetmapper.PlanetMapper;
import com.example.planetmapper.physics.PhysicsColliderManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

@EventBusSubscriber(modid = PlanetMapper.MODID, bus = EventBusSubscriber.Bus.GAME)
public class PhysicsPlatformHandler {

    private static final double TICK_SECONDS = 1.0 / 20.0;
    private static final double MAX_PENETRATION_CORRECTION = 0.4;

    private PhysicsPlatformHandler() {
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (player.level().isClientSide()) {
            return;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        if (player.isSpectator() || player.isPassenger() || player.getAbilities().flying) {
            return;
        }

        AABB box = player.getBoundingBox();
        PhysicsColliderManager.PlatformSupport support = PhysicsColliderManager.getPlatformSupport(level, box);
        if (support == null) {
            return;
        }

        double penetration = support.topY() - box.minY;
        if (penetration > 0.0 && penetration <= MAX_PENETRATION_CORRECTION) {
            player.setPos(player.getX(), player.getY() + penetration, player.getZ());
            box = player.getBoundingBox();
        }

        Vec3 platformVelocity = support.velocity();
        if (platformVelocity.lengthSqr() < 1.0E-6) {
            return;
        }

        Vec3 delta = platformVelocity.scale(TICK_SECONDS);
        player.setDeltaMovement(player.getDeltaMovement().add(delta));
    }
}
