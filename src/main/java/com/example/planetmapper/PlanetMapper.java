
package com.example.planetmapper;

import com.example.planetmapper.item.ModItems;
import com.example.planetmapper.handler.ImmersivePortalsHandler;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import com.example.planetmapper.command.LandingCommand;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(PlanetMapper.MODID)
public class PlanetMapper {
    public static final String MODID = "planetmapper";
    public static final Logger LOGGER = LoggerFactory.getLogger(MODID);

    public static final ResourceKey<Level> SPACE_LEVEL = ResourceKey.create(Registries.DIMENSION,
            ResourceLocation.fromNamespaceAndPath(MODID, "space"));

    public PlanetMapper(IEventBus modEventBus, ModContainer modContainer) {
        // Register Items
        ModItems.register(modEventBus);
        // Register Entities
        com.example.planetmapper.entity.ModEntities.register(modEventBus);

        // Register the commonSetup method for networking
        // PacketHandler.register is static @SubscribeEvent, so it auto-registers if
        // class is @EventBusSubscriber(bus = MOD)
        // We assume PacketHandler is correctly annotated.

        modEventBus.addListener(this::addCreative);

        if (ModList.get().isLoaded("imm_ptl")) {
            NeoForge.EVENT_BUS.register(new ImmersivePortalsHandler());
            LOGGER.info("Immersive Portals integration enabled.");
        }
        
        NeoForge.EVENT_BUS.addListener(this::onServerStarting);

        LOGGER.info("Planet Mapper Initialized");
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(ModItems.CREATOR_WAND.get());
            event.accept(ModItems.ADVANCED_CREATOR_WAND.get());
            event.accept(ModItems.EDITOR_WAND.get());
            event.accept(ModItems.STRUCTURE_SELECTOR_WAND.get());
        }
    }

    @SubscribeEvent
    public void onServerStarting(RegisterCommandsEvent event) {
        LandingCommand.register(event.getDispatcher());
        com.example.planetmapper.command.PhysicsTestCommand.register(event.getDispatcher());
        com.example.planetmapper.command.StructureCommand.register(event.getDispatcher());
        
        com.example.planetmapper.physics.PhysicsWorldManager.init();
    }
}
