package com.example.planetmapper.handler;

import com.example.planetmapper.PlanetMapper;
import com.example.planetmapper.network.CelestialBodySyncPacket;
import com.example.planetmapper.space.CelestialBodyRegistry;
import com.example.planetmapper.space.SpaceSystemData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

@EventBusSubscriber(modid = PlanetMapper.MODID, bus = EventBusSubscriber.Bus.GAME)
public class PersistenceHandler {

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        CelestialBodyRegistry.resetServer(); // Clean slate
        ServerLevel level = event.getServer().getLevel(PlanetMapper.SPACE_LEVEL);
        if (level != null) {
            // Force load data
            SpaceSystemData data = SpaceSystemData.get(level);
            // The get() call triggers load() if file exists
        }
    }

    @SubscribeEvent
    public static void onLevelSave(LevelEvent.Save event) {
        if (event.getLevel() instanceof ServerLevel level && level.dimension() == PlanetMapper.SPACE_LEVEL) {
            SpaceSystemData data = SpaceSystemData.get(level);
            if (CelestialBodyRegistry.getServerInstance().isDirty()) {
                data.setDirty();
                CelestialBodyRegistry.getServerInstance().markClean();
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            // Synce all bodies to the new player
            PacketDistributor.sendToPlayer(player,
                    new CelestialBodySyncPacket(true, CelestialBodyRegistry.getServerInstance().save()));
        }
    }

    @SubscribeEvent
    public static void onPlayerChangeDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getTo() == PlanetMapper.SPACE_LEVEL && event.getEntity() instanceof ServerPlayer player) {
            // Sync when entering space
            PacketDistributor.sendToPlayer(player,
                    new CelestialBodySyncPacket(true, CelestialBodyRegistry.getServerInstance().save()));
        }
    }
}
