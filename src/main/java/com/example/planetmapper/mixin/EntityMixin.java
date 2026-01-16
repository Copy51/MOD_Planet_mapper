package com.example.planetmapper.mixin;

import com.example.planetmapper.Config;
import com.example.planetmapper.physics.PhysicsColliderManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.fml.ModList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Mixin(Entity.class)
public abstract class EntityMixin {
    @Unique
    private List<VoxelShape> planetmapper$physicsShapes = Collections.emptyList();

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

    @Inject(method = "collide", at = @At("HEAD"))
    private void planetmapper$collectPhysicsColliders(Vec3 movement, CallbackInfoReturnable<Vec3> cir) {
        Entity entity = (Entity) (Object) this;
        this.planetmapper$physicsShapes = PhysicsColliderManager.collectCollisionShapes(entity, movement);
    }

    @ModifyArg(
            method = "collide",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;collideBoundingBox(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/world/level/Level;Ljava/util/List;)Lnet/minecraft/world/phys/Vec3;"
            ),
            index = 4
    )
    private List<VoxelShape> planetmapper$injectPhysicsColliders(List<VoxelShape> original) {
        List<VoxelShape> extra = this.planetmapper$physicsShapes;
        if (extra == null || extra.isEmpty()) {
            return original;
        }
        if (original.isEmpty()) {
            return extra;
        }
        List<VoxelShape> merged = new ArrayList<>(original.size() + extra.size());
        merged.addAll(original);
        merged.addAll(extra);
        return merged;
    }

    @Inject(method = "collide", at = @At("RETURN"))
    private void planetmapper$clearPhysicsColliders(Vec3 movement, CallbackInfoReturnable<Vec3> cir) {
        this.planetmapper$physicsShapes = Collections.emptyList();
    }
}
