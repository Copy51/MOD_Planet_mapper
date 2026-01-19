package com.example.planetmapper.handler;

import com.example.planetmapper.PlanetMapper;
import com.example.planetmapper.entity.PhysicsStructureEntity;
import com.example.planetmapper.physics.structure.StructurePhysicsManager;
import com.example.planetmapper.shipyard.ShipyardManager;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

@EventBusSubscriber(modid = PlanetMapper.MODID, bus = EventBusSubscriber.Bus.GAME)
public class ShipyardEntityHandler {

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (!(event.getEntity() instanceof PhysicsStructureEntity structure)) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        ServerLevel shipyard = ShipyardManager.getShipyardLevel(level.getServer());
        if (shipyard == null) {
            return;
        }
        long bodyId = structure.getBodyId();
        if (bodyId > 0 && StructurePhysicsManager.getStructure(bodyId) != null) {
            ShipyardManager.bindBodyId(structure.getUUID(), bodyId, shipyard);
            return;
        }

        ShipyardManager.ShipyardRegion region = ShipyardManager.getRegion(shipyard, structure.getUUID());
        if (region == null) {
            return;
        }
        ShipyardManager.bindBodyId(structure.getUUID(), 0L, shipyard);
        StructurePhysicsManager.restoreStructureFromShipyard(level, shipyard, structure, region);
    }
}
