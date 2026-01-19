package com.example.planetmapper.shipyard;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.server.level.ServerLevel;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ShipyardSavedData extends SavedData {
    private static final String DATA_NAME = "planetmapper_shipyard";
    private final Map<UUID, RegionData> regions = new HashMap<>();

    public static ShipyardSavedData load(CompoundTag tag, HolderLookup.Provider provider) {
        ShipyardSavedData data = new ShipyardSavedData();
        ListTag list = tag.getList("Regions", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            UUID owner = entry.getUUID("Owner");
            int x = entry.getInt("X");
            int y = entry.getInt("Y");
            int z = entry.getInt("Z");
            int sizeX = entry.getInt("SizeX");
            int sizeY = entry.getInt("SizeY");
            int sizeZ = entry.getInt("SizeZ");
            data.regions.put(owner, new RegionData(new BlockPos(x, y, z), sizeX, sizeY, sizeZ));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, RegionData> entry : regions.entrySet()) {
            CompoundTag regionTag = new CompoundTag();
            regionTag.putUUID("Owner", entry.getKey());
            RegionData region = entry.getValue();
            BlockPos origin = region.origin();
            regionTag.putInt("X", origin.getX());
            regionTag.putInt("Y", origin.getY());
            regionTag.putInt("Z", origin.getZ());
            regionTag.putInt("SizeX", region.sizeX());
            regionTag.putInt("SizeY", region.sizeY());
            regionTag.putInt("SizeZ", region.sizeZ());
            list.add(regionTag);
        }
        tag.put("Regions", list);
        return tag;
    }

    public static ShipyardSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(
                        ShipyardSavedData::new,
                        ShipyardSavedData::load,
                        DataFixTypes.LEVEL
                ),
                DATA_NAME);
    }

    public Map<UUID, RegionData> getRegions() {
        return regions;
    }

    public void putRegion(UUID ownerId, RegionData region) {
        regions.put(ownerId, region);
        setDirty();
    }

    public void removeRegion(UUID ownerId) {
        if (regions.remove(ownerId) != null) {
            setDirty();
        }
    }

    public record RegionData(BlockPos origin, int sizeX, int sizeY, int sizeZ) {
        public BlockPos getMax() {
            return new BlockPos(origin.getX() + sizeX - 1, origin.getY() + sizeY - 1, origin.getZ() + sizeZ - 1);
        }
    }
}
