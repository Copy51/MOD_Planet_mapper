package com.example.planetmapper.mixin;

import com.example.planetmapper.Config;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.neoforged.fml.ModList;

@Mixin(Entity.class)
public abstract class EntityMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void planetmapper$checkBorder(CallbackInfo ci) {
        Entity entity = (Entity) (Object) this;
        // Run only on server side to manage actual position
        if (entity.level().isClientSide)
            return;

        // If Immersive Portals is loaded, let it handle the wrapping (seamless)
        if (ModList.get().isLoaded("imm_ptl") || ModList.get().isLoaded("immersive_portals")
                || ModList.get().isLoaded("imm_ptl_core"))
            return;

        // Skip if entity is removed or passengers (passengers wrap with vehicle)
        if (entity.isRemoved() || entity.isPassenger())
            return;

        double x = entity.getX();
        double z = entity.getZ();
        int radius = Config.WORLD_RADIUS.get();

        boolean changed = false;
        double newX = x;
        double newZ = z;

        if (Math.abs(x) > radius) {
            newX = -1 * Math.signum(x) * (radius - 5); // Wrap to other side
            changed = true;
        }

        if (Math.abs(z) > radius) {
            newZ = -1 * Math.signum(z) * (radius - 5); // Wrap to other side
            changed = true;
        }

        if (changed) {
            // Teleport entity.
            // teleportTo handles dimension checks and ticket loading in 1.21
            entity.teleportTo(newX, entity.getY(), newZ);
        }
    }
}
