package com.example.planetmapper.network;

import com.example.planetmapper.PlanetMapper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.network.handling.IPayloadContext;

@EventBusSubscriber(modid = PlanetMapper.MODID, bus = EventBusSubscriber.Bus.MOD)
public class PacketHandler {

    // In NeoForge 1.21, packet registration is a bit different than 1.20.1.
    // We register payloads.

    @SubscribeEvent
    public static void register(final net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar("1");

        registrar.playToServer(
                CelestialBodyCreationPacket.TYPE,
                CelestialBodyCreationPacket.STREAM_CODEC,
                CelestialBodyCreationPacket::handle);

        registrar.playToServer(
                CelestialBodyUpdatePacket.TYPE,
                CelestialBodyUpdatePacket.STREAM_CODEC,
                CelestialBodyUpdatePacket::handle);

        registrar.playToClient(
                CelestialBodySyncPacket.TYPE,
                CelestialBodySyncPacket.STREAM_CODEC,
                CelestialBodySyncPacket::handle);

        registrar.playToClient(
                DynamicColliderSyncPacket.TYPE,
                DynamicColliderSyncPacket.STREAM_CODEC,
                DynamicColliderSyncPacket::handle);

        registrar.playToClient(
                DynamicColliderRemovePacket.TYPE,
                DynamicColliderRemovePacket.STREAM_CODEC,
                DynamicColliderRemovePacket::handle);

        registrar.playToClient(
                PhysicsEntitySyncPacket.TYPE,
                PhysicsEntitySyncPacket.STREAM_CODEC,
                PhysicsEntitySyncPacket::handle);
    }
}
