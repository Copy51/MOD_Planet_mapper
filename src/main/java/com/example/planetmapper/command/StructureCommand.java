package com.example.planetmapper.command;

import com.example.planetmapper.physics.structure.StructureBuildManager;
import com.example.planetmapper.physics.structure.StructureSelection;
import com.example.planetmapper.physics.structure.StructureSelectionManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public class StructureCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("pm_structure_create")
                .requires(s -> s.hasPermission(2))
                .executes(StructureCommand::create));

        dispatcher.register(Commands.literal("pm_structure_clear")
                .requires(s -> s.hasPermission(2))
                .executes(StructureCommand::clear));
    }

    private static int create(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("This command must be run by a player."));
            return 0;
        }

        StructureSelection selection = StructureSelectionManager.get(player);
        if (selection == null || !selection.isComplete()) {
            source.sendFailure(Component.literal("Selection is incomplete. Use the selector wand to set Pos1/Pos2."));
            return 0;
        }

        ServerLevel level = player.getServer().getLevel(selection.getDimension());
        if (level == null) {
            source.sendFailure(Component.literal("Selected dimension is not loaded."));
            return 0;
        }

        boolean started = StructureBuildManager.enqueue(player, level, selection);
        if (started) {
            StructureSelectionManager.clear(player);
            return 1;
        }

        return 0;
    }

    private static int clear(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("This command must be run by a player."));
            return 0;
        }

        StructureSelectionManager.clear(player);
        source.sendSuccess(() -> Component.literal("Selection cleared."), false);
        return 1;
    }
}
