package com.example.planetmapper.client;

import com.example.planetmapper.PlanetMapper;
import com.example.planetmapper.entity.PhysicsStructureEntity;
import com.example.planetmapper.network.ShipControlPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

@EventBusSubscriber(modid = PlanetMapper.MODID, value = Dist.CLIENT)
public class ClientInputHandler {

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        if (mc.player.getVehicle() instanceof PhysicsStructureEntity entity) {
            Input input = mc.player.input;
            
            float thrust = input.forwardImpulse; // W/S
            float strafe = input.leftImpulse;    // A/D
            boolean jump = input.jumping;      // Space
            boolean shift = input.shiftKeyDown; // Shift
            
            float vertical = 0;
            if (jump) vertical += 1.0f;
            if (shift) vertical -= 1.0f;
            
            float yaw = mc.player.getYRot(); // We might want relative yaw or just raw yaw
            
            // Only send if inputs are non-zero or changed?
            // Sending every tick is fine for smooth control (20Hz)
            
            PacketDistributor.sendToServer(new ShipControlPacket(
                    entity.getId(),
                    thrust,
                    strafe,
                    vertical,
                    yaw
            ));
        }
    }
}
