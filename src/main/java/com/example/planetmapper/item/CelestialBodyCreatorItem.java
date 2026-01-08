package com.example.planetmapper.item;

import com.example.planetmapper.client.gui.CelestialBodyCreatorScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public class CelestialBodyCreatorItem extends Item {

    public CelestialBodyCreatorItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand usedHand) {
        if (level.isClientSide) {
            // Raytrace to find spawn position
            HitResult hit = player.pick(512.0d, 0.0f, false);
            Vec3 spawnPos;

            if (hit.getType() == HitResult.Type.MISS) {
                // If miss, place 100 blocks in front
                spawnPos = player.getEyePosition().add(player.getLookAngle().scale(100.0));
            } else {
                spawnPos = hit.getLocation();
            }

            // Open GUI on client thread
            Minecraft.getInstance().setScreen(new CelestialBodyCreatorScreen(spawnPos));
        }

        return InteractionResultHolder.success(player.getItemInHand(usedHand));
    }
}
