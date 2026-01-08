package com.example.planetmapper.handler;

import com.example.planetmapper.Config;
import com.example.planetmapper.PlanetMapper;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * Wraps entities that leave the configured world square (±worldRadius) and plays a
 * lightweight "portal" effect to show the transition.
 */
@EventBusSubscriber(modid = PlanetMapper.MODID, bus = EventBusSubscriber.Bus.GAME)
public class WorldBoundaryHandler {

    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Post event) {
        Entity entity = event.getEntity();
        if (entity.level().isClientSide()) {
            return;
        }

        // Only wrap players (extend later if needed)
        if (!(entity instanceof Player)) {
            return;
        }

        Level level = entity.level();
        // Apply to overworld and space dimension
        if (level.dimension() != Level.OVERWORLD && level.dimension() != PlanetMapper.SPACE_LEVEL) {
            return;
        }

        // Limit rate to reduce spam
        if (entity.tickCount % 5 != 0) {
            return;
        }

        double radius = Config.WORLD_RADIUS.get();
        double max = radius;
        double x = entity.getX();
        double z = entity.getZ();

        boolean wrapped = false;
        if (x > max) {
            x = -max + (x - max);
            wrapped = true;
        } else if (x < -max) {
            x = max + (x + max);
            wrapped = true;
        }

        if (z > max) {
            z = -max + (z - max);
            wrapped = true;
        } else if (z < -max) {
            z = max + (z + max);
            wrapped = true;
        }

        if (wrapped && level instanceof ServerLevel serverLevel) {
            entity.teleportTo(x, entity.getY(), z);
            if (entity instanceof ServerPlayer sp) {
                sp.connection.resetPosition();
            }
            // Portal feedback
            serverLevel.sendParticles(ParticleTypes.PORTAL, x, entity.getY() + 1.0, z, 40, 0.5, 1.0, 0.5, 0.1);
            serverLevel.playSound(null, x, entity.getY(), z, SoundEvents.PORTAL_TRAVEL, SoundSource.AMBIENT, 0.55f, 1.1f);
        }
    }
}
