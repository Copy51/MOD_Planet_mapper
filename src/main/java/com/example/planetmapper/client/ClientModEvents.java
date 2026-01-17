package com.example.planetmapper.client;

import com.example.planetmapper.PlanetMapper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterDimensionSpecialEffectsEvent;

@EventBusSubscriber(modid = PlanetMapper.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientModEvents {

    @SubscribeEvent
    public static void registerDimensionEffects(RegisterDimensionSpecialEffectsEvent event) {
        PlanetMapper.LOGGER.info("Registering Space Dimension Effects");
        event.register(ResourceLocation.fromNamespaceAndPath(PlanetMapper.MODID, "space"), new SpaceDimensionEffects());
    }

    @SubscribeEvent
    public static void registerRenderers(net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(com.example.planetmapper.entity.ModEntities.PHYSICS_BLOCK.get(), 
                com.example.planetmapper.client.renderer.PhysicsBlockRenderer::new);
    }
}
