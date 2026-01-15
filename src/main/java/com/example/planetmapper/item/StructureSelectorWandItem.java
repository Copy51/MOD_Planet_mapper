package com.example.planetmapper.item;

import com.example.planetmapper.physics.structure.StructureSelection;
import com.example.planetmapper.physics.structure.StructureSelectionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

public class StructureSelectorWandItem extends Item {

    public StructureSelectorWandItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }

        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }

        BlockPos pos = context.getClickedPos();
        ResourceKey<Level> dimension = level.dimension();

        boolean secondary = player.isShiftKeyDown();
        StructureSelection selection = secondary
                ? StructureSelectionManager.setPos2(player, dimension, pos)
                : StructureSelectionManager.setPos1(player, dimension, pos);

        if (selection == null) {
            player.sendSystemMessage(Component.literal("Selection failed: both points must be in the same dimension."));
            return InteractionResult.SUCCESS;
        }

        String label = secondary ? "Pos2" : "Pos1";
        player.sendSystemMessage(Component.literal(label + " set to " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()));

        if (selection.isComplete()) {
            long volume = selection.getVolume();
            BlockPos min = selection.getMin();
            BlockPos max = selection.getMax();
            int sizeX = max.getX() - min.getX() + 1;
            int sizeY = max.getY() - min.getY() + 1;
            int sizeZ = max.getZ() - min.getZ() + 1;
            player.sendSystemMessage(Component.literal("Selection size: " + sizeX + "x" + sizeY + "x" + sizeZ + " (volume: " + volume + ")"));
        }

        return InteractionResult.SUCCESS;
    }
}
