package com.example.planetmapper.handler;

import com.example.planetmapper.PlanetMapper;
import com.example.planetmapper.entity.PhysicsBlockEntity;
import com.example.planetmapper.entity.PhysicsStructureEntity;
import com.example.planetmapper.physics.PhysicsColliderManager;
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
        if (event.getTarget() instanceof PhysicsStructureEntity structure) {
            if (!structure.level().isClientSide) {
                PlanetMapper.LOGGER.info("Player {} started tracking ship {}. Sending structure sync.", event.getEntity().getName().getString(), structure.getId());
                structure.syncStructure();
            }
            return;
        }
        if (event.getTarget() instanceof PhysicsBlockEntity physicsEntity) {
            if (!(physicsEntity.level() instanceof ServerLevel level)) {
                return;
            }
            long bodyId = physicsEntity.getBodyId();
            if (bodyId <= 0) {
                return;
            }
            List<AABB> boxes = PhysicsColliderManager.getWorldBoxes(level, bodyId);
            if (boxes.isEmpty()) {
                return;
            }
            PacketDistributor.sendToPlayer(player,
                    new com.example.planetmapper.network.DynamicColliderSyncPacket(level.dimension().location(), bodyId, boxes));
        }
    }
}
