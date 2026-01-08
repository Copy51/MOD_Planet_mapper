package com.example.planetmapper.item;

import com.example.planetmapper.client.gui.AdvancedCelestialBodyCreatorScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Tool item that opens the advanced celestial body creation GUI.
 */
public class AdvancedCelestialBodyCreatorItem extends Item {

    public AdvancedCelestialBodyCreatorItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand usedHand) {
        if (level.isClientSide) {
            HitResult hit = player.pick(512.0d, 0.0f, false);
            Vec3 spawnPos;

            if (hit.getType() == HitResult.Type.MISS) {
                spawnPos = player.getEyePosition().add(player.getLookAngle().scale(100.0));
            } else {
                spawnPos = hit.getLocation();
            }

            Vec3 suggestedVelocity = computeOrbitVelocity(spawnPos);

            Minecraft.getInstance().setScreen(new AdvancedCelestialBodyCreatorScreen(spawnPos, suggestedVelocity));
        }

        return InteractionResultHolder.success(player.getItemInHand(usedHand));
    }

    private Vec3 computeOrbitVelocity(Vec3 position) {
        double dist = Math.sqrt(position.x * position.x + position.z * position.z);
        if (dist < 1.0e-3) {
            return Vec3.ZERO;
        }

        double G = 0.5;
        double M = 100000.0;
        double v = Math.sqrt(G * M / dist);

        double vx = (-position.z / dist) * v;
        double vz = (position.x / dist) * v;
        return new Vec3(vx, 0.0, vz);
    }
}
