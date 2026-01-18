package com.example.planetmapper.handler;

import com.example.planetmapper.PlanetMapper;
import com.example.planetmapper.physics.structure.StructurePhysicsManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

@EventBusSubscriber(modid = PlanetMapper.MODID, bus = EventBusSubscriber.Bus.GAME)
public class StructurePhysicsHandler {

    @SubscribeEvent
    public static void onAttackEntity(AttackEntityEvent event) {
        if (event.getEntity().level().isClientSide()) {
            return;
        }
        if (event.getEntity() instanceof ServerPlayer player) {
            if (StructurePhysicsManager.handleAttack(player, event.getTarget())) {
                event.setCanceled(true);
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity().level().isClientSide()) {
            return;
        }
        if (event.getEntity() instanceof ServerPlayer player) {
            StructurePhysicsManager.onPlayerTick(player);
        }
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (event.getLevel() instanceof ServerLevel level) {
            StructurePhysicsManager.onLevelTick(level);
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        StructurePhysicsManager.shutdown();
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        StructurePhysicsManager.reset();
    }
}
