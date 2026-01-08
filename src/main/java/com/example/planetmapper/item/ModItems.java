package com.example.planetmapper.item;

import com.example.planetmapper.PlanetMapper;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(BuiltInRegistries.ITEM,
            PlanetMapper.MODID);

    public static final DeferredHolder<Item, CelestialBodyCreatorItem> CREATOR_WAND = ITEMS.register(
            "planet_creator_wand",
            () -> new CelestialBodyCreatorItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<Item, AdvancedCelestialBodyCreatorItem> ADVANCED_CREATOR_WAND = ITEMS.register(
            "advanced_creator_wand",
            () -> new AdvancedCelestialBodyCreatorItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<Item, CelestialBodyEditorItem> EDITOR_WAND = ITEMS.register(
            "celestial_body_editor",
            () -> new CelestialBodyEditorItem(new Item.Properties().stacksTo(1)));

    public static void register(IEventBus eventBus) {
        ITEMS.register(eventBus);
    }
}
