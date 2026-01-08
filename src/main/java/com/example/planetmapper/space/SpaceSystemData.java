package com.example.planetmapper.space;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.datafix.DataFixTypes; // Note: Check if needed for SavedData
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.server.level.ServerLevel;

/**
 * Persists celestial bodies to the world data.
 */
public class SpaceSystemData extends SavedData {

    private static final String DATA_NAME = "planetmapper_space";

    public static SpaceSystemData load(CompoundTag tag, HolderLookup.Provider provider) {
        SpaceSystemData data = new SpaceSystemData();
        CelestialBodyRegistry.getServerInstance().load(tag);
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        return CelestialBodyRegistry.getServerInstance().save();
    }

    public static SpaceSystemData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(
                        SpaceSystemData::new,
                        SpaceSystemData::load,
                        DataFixTypes.LEVEL
                // In NeoForge 1.21 this signature might slightly vary,
                // but Factory usually takes Supplier<T>, Deserializer, DataFixTypes
                ),
                DATA_NAME);
    }

    public void setDirty() {
        super.setDirty();
    }
}
