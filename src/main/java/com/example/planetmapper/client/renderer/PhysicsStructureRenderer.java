package com.example.planetmapper.client.renderer;

import com.example.planetmapper.entity.PhysicsStructureEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;

public class PhysicsStructureRenderer extends EntityRenderer<PhysicsStructureEntity> {

    public PhysicsStructureRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(PhysicsStructureEntity entity, float entityYaw, float partialTick, PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight);

        Map<BlockPos, BlockState> structure = entity.getStructure();
        if (structure == null || structure.isEmpty()) {
            return;
        }

        poseStack.pushPose();

        // Apply rotation from physics engine (Quaternion)
        poseStack.translate(0, 0.5, 0); 
        poseStack.mulPose(entity.getPhysicsRotation(partialTick));
        
        var blockRenderer = net.minecraft.client.Minecraft.getInstance().getBlockRenderer();
        var dispatcher = net.minecraft.client.Minecraft.getInstance().getBlockRenderer();

        for (Map.Entry<BlockPos, BlockState> entry : structure.entrySet()) {
            BlockPos pos = entry.getKey();
            BlockState state = entry.getValue();

            poseStack.pushPose();
            // Offset to match structure scanner's center logic
            poseStack.translate(pos.getX(), pos.getY(), pos.getZ());
            poseStack.translate(-0.5, -0.5, -0.5);

            dispatcher.renderSingleBlock(state, poseStack, buffer, packedLight, OverlayTexture.NO_OVERLAY);
            
            poseStack.popPose();
        }

        poseStack.popPose();
    }

    @Override
    public ResourceLocation getTextureLocation(PhysicsStructureEntity entity) {
        return TextureAtlas.LOCATION_BLOCKS;
    }
}
