package com.example.planetmapper.physics.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class StructureSelectionManager {
    private static final Map<UUID, StructureSelection> SELECTIONS = new ConcurrentHashMap<>();

    private StructureSelectionManager() {
    }

    public static StructureSelection get(Player player) {
        return SELECTIONS.get(player.getUUID());
    }

    public static StructureSelection getOrCreate(Player player) {
        return SELECTIONS.computeIfAbsent(player.getUUID(), id -> new StructureSelection());
    }

    public static StructureSelection setPos1(Player player, ResourceKey<Level> dimension, BlockPos pos) {
        StructureSelection selection = getOrCreate(player);
        selection.setPos1(dimension, pos);
        return selection;
    }

    public static StructureSelection setPos2(Player player, ResourceKey<Level> dimension, BlockPos pos) {
        StructureSelection selection = getOrCreate(player);
        if (!selection.setPos2(dimension, pos)) {
            return null;
        }
        return selection;
    }

    public static void clear(Player player) {
        StructureSelection selection = SELECTIONS.remove(player.getUUID());
        if (selection != null) {
            selection.clear();
        }
    }
}
