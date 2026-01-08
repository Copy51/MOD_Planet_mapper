package com.example.planetmapper.command;

import com.example.planetmapper.PlanetMapper;
import com.example.planetmapper.handler.ImmersivePortalsHandler;
import com.example.planetmapper.space.CelestialBody;
import com.example.planetmapper.space.CelestialBodyRegistry;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

public class LandingCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("land")
                .executes(LandingCommand::execute));
    }

    private static int execute(CommandContext<CommandSourceStack> context) {
        try {
            CommandSourceStack source = context.getSource();
            ServerPlayer player = source.getPlayerOrException();

            if (player.level().dimension() != PlanetMapper.SPACE_LEVEL) {
                source.sendFailure(Component.literal("You must be in space to use this command."));
                return 0;
            }

            CelestialBodyRegistry registry = CelestialBodyRegistry.getServerInstance();
            CelestialBody nearest = ImmersivePortalsHandler.findNearestPlanet(player.position(), registry);

            if (nearest == null) {
                source.sendFailure(Component.literal("No planet nearby to land on."));
                return 0;
            }

            ResourceKey<Level> destKey = ImmersivePortalsHandler.getTargetDimensionKey(nearest);
            if (destKey == null) {
                source.sendFailure(Component.literal("This planet has no destination dimension."));
                return 0;
            }
            
            ServerLevel destLevel = source.getServer().getLevel(destKey);
            if (destLevel == null) {
                source.sendFailure(Component.literal("Destination dimension not found."));
                return 0;
            }


            Vec3 planetCenter = nearest.getPosition();
            Vec3 rel = player.position().subtract(planetCenter);
            Vec3 dirWorld = rel.normalize();

            Quaternionf planetRotation = ImmersivePortalsHandler.buildPlanetRotation(nearest);
            // conjugate represents inverse rotation for purely rotational quaternions
            Vec3 dirLocal = ImmersivePortalsHandler.rotateVec(new Quaternionf(planetRotation).conjugate(), dirWorld);

            Vec3 destPos = ImmersivePortalsHandler.mapDirToSurface(dirLocal);

            player.teleportTo(destLevel, destPos.x, beatHeight(destLevel, destPos), destPos.z, player.getYRot(), player.getXRot());
            source.sendSuccess(() -> Component.literal("Landing on " + nearest.getName() + "..."), true);

            return 1;
        } catch (Exception e) {
            context.getSource().sendFailure(Component.literal("An error occurred: " + e.getMessage()));
            e.printStackTrace();
            return 0;
        }
    }

    private static double beatHeight(ServerLevel level, Vec3 pos) {
        // Simple height check, or just spawn high up. 
        // DESTINATION_Y is 310 in handler, so stick to that or use world height.
        // But safer to verify surface height? For now use the handler's Y.
        return pos.y;
    }
}
