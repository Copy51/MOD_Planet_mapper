package com.example.planetmapper.handler;

import com.example.planetmapper.PlanetMapper;
import com.example.planetmapper.physics.PhysicsColliderManager;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

@EventBusSubscriber(modid = PlanetMapper.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public class ClientCleanupHandler {
    private static boolean hadLevel = false;

    private ClientCleanupHandler() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        boolean hasLevel = minecraft.level != null;
        if (!hasLevel && hadLevel) {
            PhysicsColliderManager.resetAll();
        }
        hadLevel = hasLevel;
    }
}
