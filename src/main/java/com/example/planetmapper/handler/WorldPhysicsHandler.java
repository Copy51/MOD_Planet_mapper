package com.example.planetmapper.handler;

import com.example.planetmapper.Config;
import com.example.planetmapper.PlanetMapper;
import com.example.planetmapper.physics.structure.WorldStructureSpawner;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ExplosionEvent;

import java.util.List;

@EventBusSubscriber(modid = PlanetMapper.MODID, bus = EventBusSubscriber.Bus.GAME)
public class WorldPhysicsHandler {

    @SubscribeEvent
    public static void onExplosionDetonate(ExplosionEvent.Detonate event) {
        if (!Config.PHYSICS_WORLD_PHYSICS_ENABLED.get()) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        List<BlockPos> affected = event.getAffectedBlocks();
        if (affected == null || affected.isEmpty()) {
            return;
        }

        int maxBlocks = Config.PHYSICS_EXPLOSION_MAX_BLOCKS.get();
        int collapseHeight = Config.PHYSICS_EXPLOSION_COLLAPSE_HEIGHT.get();

        LongOpenHashSet selection = new LongOpenHashSet();
        for (BlockPos pos : affected) {
            if (selection.size() >= maxBlocks) {
                break;
            }
            if (isConvertible(level, pos)) {
                selection.add(pos.asLong());
            }
        }

        if (collapseHeight > 0 && !selection.isEmpty() && selection.size() < maxBlocks) {
            LongOpenHashSet base = new LongOpenHashSet(selection);
            for (long key : base) {
                if (selection.size() >= maxBlocks) {
                    break;
                }
                int baseX = BlockPos.getX(key);
                int baseY = BlockPos.getY(key);
                int baseZ = BlockPos.getZ(key);
                for (int dy = 1; dy <= collapseHeight && selection.size() < maxBlocks; dy++) {
                    BlockPos above = new BlockPos(baseX, baseY + dy, baseZ);
                    if (!isConvertible(level, above)) {
                        continue;
                    }
                    BlockPos below = above.below();
                    if (!selection.contains(below.asLong()) && !level.getBlockState(below).isAir()) {
                        break;
                    }
                    selection.add(above.asLong());
                }
            }
        }

        if (!selection.isEmpty()) {
            affected.removeIf(pos -> selection.contains(pos.asLong()));
            WorldStructureSpawner.spawnFromWorld(level, selection);
        }
    }

    private static boolean isConvertible(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return false;
        }
        if (state.is(Blocks.BEDROCK) || state.is(Blocks.BARRIER)) {
            return false;
        }
        return state.getDestroySpeed(level, pos) >= 0.0f;
    }
}
