package com.example.planetmapper.client;

import com.example.planetmapper.PlanetMapper;
import com.example.planetmapper.client.gui.PlanetMapperWorldSettingsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

@EventBusSubscriber(modid = PlanetMapper.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public class WorldSettingsButtonHandler {

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        if (!(screen instanceof CreateWorldScreen)) {
            return;
        }

        int x = screen.width / 2 + 110;
        int y = 20;
        event.addListener(Button.builder(Component.literal("Planet Mapper"),
                b -> Minecraft.getInstance().setScreen(new PlanetMapperWorldSettingsScreen(screen)))
                .bounds(x, y, 120, 20)
                .build());
    }
}
