package com.example.planetmapper.client.renderer;

import com.example.planetmapper.entity.PhysicsStructureEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import org.joml.Vector3f;

import java.lang.reflect.Field;
import java.util.Map;

public class PhysicsStructureRenderer extends EntityRenderer<PhysicsStructureEntity> {
    private static final Field PISTON_PROGRESS_FIELD;
    private static final Field PISTON_PROGRESS_OLD_FIELD;

    static {
        Field progress = null;
        Field progressOld = null;
        try {
            progress = PistonMovingBlockEntity.class.getDeclaredField("progress");
            progressOld = PistonMovingBlockEntity.class.getDeclaredField("progressO");
            progress.setAccessible(true);
            progressOld.setAccessible(true);
        } catch (ReflectiveOperationException ignored) {
        }
        PISTON_PROGRESS_FIELD = progress;
        PISTON_PROGRESS_OLD_FIELD = progressOld;
    }

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
        poseStack.translate(0, entity.getBodyYOffset(), 0); 
        poseStack.mulPose(entity.getPhysicsRotation(partialTick));
        Vector3f offset = entity.getOriginOffset();
        poseStack.translate(offset.x, offset.y, offset.z);
        
        Minecraft minecraft = Minecraft.getInstance();
        var dispatcher = minecraft.getBlockRenderer();
        BlockEntityRenderDispatcher blockEntityRenderer = minecraft.getBlockEntityRenderDispatcher();

        for (Map.Entry<BlockPos, BlockState> entry : structure.entrySet()) {
            BlockPos pos = entry.getKey();
            BlockState state = entry.getValue();

            poseStack.pushPose();
            // Offset to match structure scanner's center logic
            poseStack.translate(pos.getX(), pos.getY(), pos.getZ());

            if (state.getBlock() instanceof MovingPistonBlock
                    && minecraft.level != null
                    && renderMovingPiston(entity, state, pos, minecraft, blockEntityRenderer, partialTick, poseStack, buffer)) {
                poseStack.popPose();
                continue;
            }

            dispatcher.renderSingleBlock(state, poseStack, buffer, packedLight, OverlayTexture.NO_OVERLAY);
            
            poseStack.popPose();
        }

        poseStack.popPose();
    }

    @Override
    public ResourceLocation getTextureLocation(PhysicsStructureEntity entity) {
        return TextureAtlas.LOCATION_BLOCKS;
    }

    private static boolean renderMovingPiston(PhysicsStructureEntity entity, BlockState movingState, BlockPos localPos,
                                              Minecraft minecraft, BlockEntityRenderDispatcher blockEntityRenderer,
                                              float partialTick, PoseStack poseStack, MultiBufferSource buffer) {
        if (PISTON_PROGRESS_FIELD == null || PISTON_PROGRESS_OLD_FIELD == null) {
            return false;
        }

        CompoundTag tag = entity.getBlockEntityTag(localPos);
        BlockState movedState = Blocks.AIR.defaultBlockState();
        Direction direction = Direction.UP;
        boolean extending = true;
        boolean source = true;

        if (movingState.hasProperty(MovingPistonBlock.FACING)) {
            direction = movingState.getValue(MovingPistonBlock.FACING);
        }
        if (tag != null && !tag.isEmpty()) {
            if (tag.contains("blockState", 10)) {
                movedState = NbtUtils.readBlockState(
                        minecraft.level.holderLookup(Registries.BLOCK),
                        tag.getCompound("blockState")
                );
            }
            if (tag.contains("facing", 3)) {
                direction = Direction.from3DDataValue(tag.getInt("facing"));
            }
            if (tag.contains("extending", 1)) {
                extending = tag.getBoolean("extending");
            }
            if (tag.contains("source", 1)) {
                source = tag.getBoolean("source");
            }
        }

        BlockPos worldPos = BlockPos.containing(entity.getWorldCenterForLocal(localPos, partialTick));
        PistonMovingBlockEntity piston = new PistonMovingBlockEntity(worldPos, movingState, movedState, direction, extending, source);
        piston.setLevel(minecraft.level);

        long startTick = entity.getPistonStartTick(localPos);
        float ticks = (minecraft.level.getGameTime() - startTick) + partialTick;
        float progress = Mth.clamp((float) (ticks * PistonMovingBlockEntity.TICK_MOVEMENT), 0.0f, 1.0f);
        float progressOld = Math.max(0.0f, progress - (float) PistonMovingBlockEntity.TICK_MOVEMENT);
        if (!setPistonProgress(piston, progress, progressOld)) {
            return false;
        }

        blockEntityRenderer.render(piston, partialTick, poseStack, buffer);
        return true;
    }

    private static boolean setPistonProgress(PistonMovingBlockEntity piston, float progress, float progressOld) {
        try {
            PISTON_PROGRESS_FIELD.setFloat(piston, progress);
            PISTON_PROGRESS_OLD_FIELD.setFloat(piston, progressOld);
            return true;
        } catch (IllegalAccessException ignored) {
            return false;
        }
    }
}
