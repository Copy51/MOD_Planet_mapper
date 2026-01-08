package com.example.planetmapper.item;

import com.example.planetmapper.client.gui.CelestialBodyEditorScreen;
import com.example.planetmapper.space.CelestialBody;
import com.example.planetmapper.space.CelestialBodyRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.Optional;

/**
 * Tool to select and edit an existing celestial body (move/retune).
 */
public class CelestialBodyEditorItem extends Item {

    public CelestialBodyEditorItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand usedHand) {
        if (level.isClientSide) {
            CelestialBody target = findTarget(player);
            if (target != null) {
                Minecraft.getInstance().setScreen(new CelestialBodyEditorScreen(target));
            }
        }
        return InteractionResultHolder.success(player.getItemInHand(usedHand));
    }

    private CelestialBody findTarget(Player player) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle().normalize();
        CelestialBodyRegistry registry = CelestialBodyRegistry.getClientInstance();

        Optional<CelestialBody> best = registry.getAllBodies().stream()
                .min(Comparator.comparingDouble(body -> {
                    Vec3 toBody = body.getPosition().subtract(eye);
                    double along = toBody.dot(look);
                    if (along < 0) {
                        return Double.MAX_VALUE;
                    }
                    Vec3 closest = eye.add(look.scale(along));
                    double distSq = closest.distanceToSqr(body.getPosition());
                    // weigh with distance to prefer closer aligned targets
                    return distSq + along * 0.01;
                }));

        return best.orElse(null);
    }
}
