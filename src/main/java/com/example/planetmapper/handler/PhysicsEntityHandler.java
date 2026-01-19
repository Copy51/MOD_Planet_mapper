package com.example.planetmapper.handler;

import com.example.planetmapper.PlanetMapper;
import com.example.planetmapper.entity.PhysicsBlockEntity;
import com.example.planetmapper.entity.PhysicsStructureEntity;
import com.example.planetmapper.physics.NativePhysicsEngine;
import com.example.planetmapper.physics.PhysicsColliderManager;
import com.example.planetmapper.physics.PhysicsWorldManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

@EventBusSubscriber(modid = PlanetMapper.MODID)
public class PhysicsEntityHandler {

    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!(event.getTarget() instanceof PhysicsBlockEntity physicsEntity)) {
            return;
        }
        if (!(physicsEntity.level() instanceof ServerLevel level)) {
            return;
        }
        long bodyId = physicsEntity.getBodyId();
        if (bodyId <= 0) {
            return;
        }

        if (physicsEntity instanceof PhysicsStructureEntity structure) {
            PlanetMapper.LOGGER.info("Player {} started tracking ship {}. Sending structure sync.", event.getEntity().getName().getString(), structure.getId());
            structure.syncStructure();
        }

        NativePhysicsEngine engine = PhysicsWorldManager.getEngine();
        if (engine != null) {
            float[] state = new float[13];
            engine.getBodyState(bodyId, state);
            PacketDistributor.sendToPlayer(player,
                    new com.example.planetmapper.network.PhysicsEntitySyncPacket(
                            physicsEntity.getId(),
                            bodyId,
                            state[0], state[1], state[2],
                            state[3], state[4], state[5], state[6],
                            state[7], state[8], state[9],
                            state[10], state[11], state[12]
                    ));
        }

        List<AABB> boxes = PhysicsColliderManager.getWorldBoxes(level, bodyId);
        org.joml.Vector3f center = PhysicsColliderManager.getBodyCenter(bodyId);
        if (!boxes.isEmpty() && center != null) {
            float[] state = new float[13];
            if (engine != null) {
                engine.getBodyState(bodyId, state);
            }
            PacketDistributor.sendToPlayer(player,
                    new com.example.planetmapper.network.DynamicColliderSyncPacket(level.dimension().location(), bodyId, boxes,
                            center.x, center.y, center.z, state[10], state[11], state[12]));
        }
    }
}
