package com.example.planetmapper.client.renderer;

import com.example.planetmapper.entity.PhysicsBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public class PhysicsBlockRenderer extends EntityRenderer<PhysicsBlockEntity> {

    public PhysicsBlockRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(PhysicsBlockEntity entity, float entityYaw, float partialTick, PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight);

        poseStack.pushPose();

        // Apply rotation from physics engine (Quaternion)
        // Physics center is 0.5 blocks above the entity's feet (setPos position)
        poseStack.translate(0, 0.5, 0); 
        poseStack.mulPose(entity.getPhysicsRotation(partialTick));
        
        // Move back to corner to render 1x1x1 block
        poseStack.translate(-0.5, -0.5, -0.5);

        // Render a generic stone block for now. 
        // In the future, PhysicsBlockEntity should store the BlockState it represents.
        BlockState renderState = Blocks.STONE.defaultBlockState();
        
        var blockRenderer = net.minecraft.client.Minecraft.getInstance().getBlockRenderer();
        blockRenderer.renderSingleBlock(renderState, poseStack, buffer, packedLight, 
                net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY);

        poseStack.popPose();
    }

    @Override
    public ResourceLocation getTextureLocation(PhysicsBlockEntity entity) {
        return TextureAtlas.LOCATION_BLOCKS;
    }
}
