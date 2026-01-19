package com.example.planetmapper.physics.structure;

import com.example.planetmapper.physics.NativePhysicsEngine;
import com.example.planetmapper.physics.PhysicsColliderManager;
import com.example.planetmapper.physics.PhysicsWorldManager;
import com.example.planetmapper.physics.VoxelShapeOptimizer;
import com.example.planetmapper.physics.WorldCollisionManager;
import com.example.planetmapper.shipyard.ShipyardManager;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.util.Mth;
import com.example.planetmapper.entity.PhysicsBlockEntity;
import com.example.planetmapper.entity.PhysicsStructureEntity;
import com.example.planetmapper.network.StructureBlockUpdatePacket;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class StructurePhysicsManager {
    private static final Map<Long, PhysicsStructure> STRUCTURES = new HashMap<>();
    private static final Map<UUID, MiningState> MINING = new HashMap<>();
    private static final Object EXECUTOR_LOCK = new Object();
    private static ExecutorService optimizerExecutor = createExecutor();
    private static volatile boolean acceptingTasks = true;

    private StructurePhysicsManager() {
    }

    public static void registerStructure(PhysicsStructure structure) {
        if (!acceptingTasks) {
            return;
        }
        STRUCTURES.put(structure.getBodyId(), structure);
    }

    public static void unregisterStructure(long bodyId) {
        STRUCTURES.remove(bodyId);
    }

    public static PhysicsStructure getStructure(long bodyId) {
        return STRUCTURES.get(bodyId);
    }

    public static boolean restoreStructureFromShipyard(ServerLevel level, ServerLevel shipyard, PhysicsStructureEntity entity,
                                                       ShipyardManager.ShipyardRegion region) {
        if (!acceptingTasks || level == null || shipyard == null || entity == null || region == null) {
            return false;
        }
        if (!PhysicsWorldManager.isNativeAvailable()) {
            return false;
        }
        NativePhysicsEngine engine = PhysicsWorldManager.getEngine();
        if (engine == null) {
            return false;
        }
        if (!ShipyardManager.ensureRegionChunksLoaded(shipyard, region)) {
            return false;
        }

        Long2ObjectOpenHashMap<StructureBlockData> blocks = new Long2ObjectOpenHashMap<>();
        LongOpenHashSet collidableBlocks = new LongOpenHashSet();
        BlockPos origin = region.origin();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = 0; x < region.sizeX(); x++) {
            for (int y = 0; y < region.sizeY(); y++) {
                for (int z = 0; z < region.sizeZ(); z++) {
                    cursor.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    BlockState state = shipyard.getBlockState(cursor);
                    if (state.isAir()) {
                        continue;
                    }
                    long key = BlockPos.asLong(x, y, z);
                    BlockEntity blockEntity = shipyard.getBlockEntity(cursor);
                    CompoundTag blockEntityTag = blockEntity != null ? blockEntity.saveWithId(shipyard.registryAccess()) : null;
                    boolean collidable = !state.getCollisionShape(shipyard, cursor).isEmpty();
                    blocks.put(key, new StructureBlockData(state, blockEntityTag, collidable));
                    if (collidable) {
                        collidableBlocks.add(key);
                    }
                }
            }
        }

        if (blocks.isEmpty() || collidableBlocks.isEmpty()) {
            return false;
        }

        List<AABB> localBoxes = VoxelShapeOptimizer.optimizeLongSet(collidableBlocks);
        Vector3f bodyPos = new Vector3f((float) entity.getX(), (float) (entity.getY() + entity.getBodyYOffset()), (float) entity.getZ());
        Vector3f originOffset = new Vector3f(entity.getOriginOffset());
        Vector3f worldOrigin = new Vector3f(originOffset).add(bodyPos);

        List<AABB> worldBoxes = offsetBoxes(localBoxes, worldOrigin);
        if (worldBoxes.isEmpty()) {
            return false;
        }
        StructurePhysicsProperties physicsProperties = StructurePhysicsProperties.fromBlocks(blocks);
        StructurePhysicsProperties.MaterialSummary material = physicsProperties.snapshot();
        float mass = Math.max(1.0f, material.mass());
        long bodyId = engine.createRigidBody(worldBoxes, material);
        if (bodyId <= 0) {
            return false;
        }

        entity.setBodyId(bodyId);
        PhysicsWorldManager.registerEntity(entity);

        PhysicsStructure structure = new PhysicsStructure(level.dimension(),
                bodyId,
                BlockPos.containing(worldOrigin.x, worldOrigin.y, worldOrigin.z),
                originOffset,
                blocks,
                collidableBlocks,
                physicsProperties);
        structure.setEntityId(entity.getId());
        registerStructure(structure);

        List<AABB> bodyLocal = offsetBoxes(localBoxes, originOffset);
        PhysicsColliderManager.registerAndSyncBody(level, bodyId, worldBoxes, bodyPos);
        PhysicsColliderManager.updateAndSyncBody(level, bodyId, bodyLocal);
        PhysicsColliderManager.updateBodyTransform(bodyId, bodyPos.x, bodyPos.y, bodyPos.z, new Quaternionf());

        ShipyardManager.bindBodyId(entity.getUUID(), bodyId, shipyard);
        return true;
    }

    public static void applyShipyardUpdate(ServerLevel shipyard, long bodyId, BlockPos localPos,
                                           BlockState state, CompoundTag blockEntityTag, boolean collidable) {
        if (!acceptingTasks) {
            return;
        }
        PhysicsStructure structure = STRUCTURES.get(bodyId);
        if (structure == null) {
            return;
        }
        long key = BlockPos.asLong(localPos.getX(), localPos.getY(), localPos.getZ());
        StructureBlockData old = structure.getBlock(key);
        boolean oldCollidable = old != null && old.collidable();
        StructurePhysicsProperties physicsProperties = structure.getPhysicsProperties();
        if (old != null) {
            physicsProperties.removeState(old.state());
        }

        if (state == null || state.isAir()) {
            structure.removeBlock(key);
        } else {
            structure.putBlock(key, new StructureBlockData(state, blockEntityTag, collidable));
            physicsProperties.addState(state);
        }

        boolean newCollidable = state != null && !state.isAir() && collidable;
        if (oldCollidable != newCollidable) {
            structure.markDirty();
        }

        ServerLevel structureLevel = shipyard.getServer().getLevel(structure.getDimension());
        if (structureLevel != null) {
            BlockState particleState = (state == null || state.isAir())
                    ? (old != null ? old.state() : null)
                    : state;
            boolean breaking = state == null || state.isAir();
            syncBlockChange(structureLevel, structure, localPos, state, particleState, breaking);
            syncBodyMaterial(structureLevel, structure);
        }
    }

    public static boolean handleAttack(ServerPlayer player, Entity target) {
        if (!acceptingTasks) {
            return false;
        }
        if (!(target instanceof PhysicsBlockEntity physicsEntity)) {
            return false;
        }
        long bodyId = physicsEntity.getBodyId();
        if (bodyId <= 0) {
            return false;
        }
        PhysicsStructure structure = STRUCTURES.get(bodyId);
        if (structure == null) {
            return false;
        }
        if (structure.getDimension() != player.level().dimension()) {
            return false;
        }

        NativePhysicsEngine engine = PhysicsWorldManager.getEngine();
        if (engine == null) {
            return false;
        }

        StructureHit hit = raycastStructure(player, structure, engine);
        if (hit == null) {
            return true;
        }

        if (player.getAbilities().instabuild) {
            breakBlock(player, structure, hit);
            return true;
        }

        MiningState state = MINING.get(player.getUUID());
        if (state == null || state.bodyId != bodyId || state.blockKey != hit.blockKey) {
            state = new MiningState(bodyId, hit.blockKey, hit.data.state());
            MINING.put(player.getUUID(), state);
        }
        state.lastTick = player.level().getGameTime();
        return true;
    }

    public static void onPlayerTick(ServerPlayer player) {
        if (!acceptingTasks) {
            return;
        }
        MiningState state = MINING.get(player.getUUID());
        if (state == null) {
            return;
        }
        long now = player.level().getGameTime();
        if (now - state.lastTick > 20L) {
            MINING.remove(player.getUUID());
            return;
        }

        PhysicsStructure structure = STRUCTURES.get(state.bodyId);
        if (structure == null || structure.getDimension() != player.level().dimension()) {
            MINING.remove(player.getUUID());
            return;
        }

        NativePhysicsEngine engine = PhysicsWorldManager.getEngine();
        if (engine == null) {
            MINING.remove(player.getUUID());
            return;
        }

        StructureHit hit = raycastStructure(player, structure, engine);
        if (hit == null || hit.blockKey != state.blockKey) {
            MINING.remove(player.getUUID());
            return;
        }

        BlockPos worldPos = hit.worldBlockPos;
        float progress = state.progress + state.state.getDestroyProgress(player, player.level(), worldPos);
        state.progress = progress;
        state.lastTick = now;

        if (progress >= 1.0f) {
            breakBlock(player, structure, hit);
            MINING.remove(player.getUUID());
        }
    }

    public static InteractionResult handleUse(ServerPlayer player, Entity target, InteractionHand hand) {
        if (!acceptingTasks) {
            return InteractionResult.PASS;
        }
        if (!(target instanceof PhysicsBlockEntity physicsEntity)) {
            return InteractionResult.PASS;
        }
        long bodyId = physicsEntity.getBodyId();
        if (bodyId <= 0) {
            return InteractionResult.PASS;
        }
        PhysicsStructure structure = STRUCTURES.get(bodyId);
        if (structure == null || structure.getDimension() != player.level().dimension()) {
            return InteractionResult.PASS;
        }

        NativePhysicsEngine engine = PhysicsWorldManager.getEngine();
        if (engine == null) {
            return InteractionResult.PASS;
        }

        StructureHit hit = raycastStructure(player, structure, engine);
        if (hit == null) {
            return InteractionResult.PASS;
        }

        ItemStack stack = player.getItemInHand(hand);
        if (stack.getItem() instanceof BlockItem blockItem) {
            if (hit.face == null) {
                return InteractionResult.CONSUME;
            }
            return placeOnStructure(player, structure, hit, hand, stack, blockItem);
        }

        return useStructureBlock(player, structure, hit, hand, stack);
    }

    private static InteractionResult placeOnStructure(ServerPlayer player, PhysicsStructure structure, StructureHit hit,
                                                      InteractionHand hand, ItemStack stack, BlockItem blockItem) {
        BlockPos localPlacePos = hit.localPos.relative(hit.face);

        ServerLevel shipyard = ShipyardManager.getShipyardLevel(player.server);
        ShipyardManager.ShipyardRegion region = ShipyardManager.getRegion(structure.getBodyId());
        if (shipyard != null && region != null) {
            ShipyardManager.ExpansionResult expansion = ShipyardManager.expandRegion(shipyard, region, localPlacePos);
            if (expansion != null) {
                BlockPos shift = expansion.shift();
                if (shift.getX() != 0 || shift.getY() != 0 || shift.getZ() != 0) {
                    structure.shiftLocal(shift.getX(), shift.getY(), shift.getZ());
                    PhysicsStructureEntity structureEntity = getStructureEntity((ServerLevel) player.level(), structure);
                    if (structureEntity != null) {
                        structureEntity.setOriginOffset(structure.getOriginOffset());
                        structureEntity.setStructure(buildRenderBlocks(structure), buildRenderBlockEntities(structure));
                    }
                }
                region = expansion.region();
                localPlacePos = localPlacePos.offset(shift);
            }

            if (localPlacePos.getX() < 0 || localPlacePos.getY() < 0 || localPlacePos.getZ() < 0
                    || localPlacePos.getX() >= region.sizeX()
                    || localPlacePos.getY() >= region.sizeY()
                    || localPlacePos.getZ() >= region.sizeZ()) {
                return InteractionResult.CONSUME;
            }
            BlockPos shipyardPos = ShipyardManager.toWorld(region, localPlacePos);
            if (shipyardPos == null || !shipyard.getBlockState(shipyardPos).isAir()) {
                return InteractionResult.CONSUME;
            }

            Vec3 shipyardCenter = new Vec3(shipyardPos.getX() + 0.5, shipyardPos.getY() + 0.5, shipyardPos.getZ() + 0.5);
            BlockHitResult shipyardHit = new BlockHitResult(shipyardCenter, hit.face, shipyardPos, false);
            UseOnContext baseContext = new UseOnContext(shipyard, player, hand, stack, shipyardHit);
            BlockPlaceContext placeContext = new BlockPlaceContext(baseContext);
            InteractionResult result = blockItem.place(placeContext);
            if (result.consumesAction()) {
                ServerLevel level = (ServerLevel) player.level();
                BlockState placed = shipyard.getBlockState(shipyardPos);
                level.playSound(null, hit.worldCenter.x, hit.worldCenter.y, hit.worldCenter.z,
                        placed.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1.0f, 1.0f);
                return InteractionResult.CONSUME;
            }
            return InteractionResult.PASS;
        }

        BlockPos worldPlacePos = hit.worldBlockPos.relative(hit.face);
        BlockState placeState = blockItem.getBlock().defaultBlockState();
        BlockHitResult hitResult = new BlockHitResult(hit.worldCenter, hit.face, worldPlacePos, false);
        BlockPlaceContext context = new BlockPlaceContext(player, hand, stack, hitResult);
        BlockState placement = blockItem.getBlock().getStateForPlacement(context);
        if (placement != null) {
            placeState = placement;
        }
        long placeKey = BlockPos.asLong(localPlacePos.getX(), localPlacePos.getY(), localPlacePos.getZ());
        if (structure.getBlocks().containsKey(placeKey)) {
            return InteractionResult.CONSUME;
        }
        boolean collidable = !placeState.getCollisionShape(player.level(), worldPlacePos).isEmpty();
        structure.putBlock(placeKey, new StructureBlockData(placeState, null, collidable));
        structure.getPhysicsProperties().addState(placeState);
        if (collidable) {
            structure.markDirty();
        }
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        ServerLevel level = (ServerLevel) player.level();
        level.playSound(null, hit.worldCenter.x, hit.worldCenter.y, hit.worldCenter.z,
                placeState.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1.0f, 1.0f);
        syncBlockChange(level, structure, localPlacePos, placeState, placeState, false);
        syncBodyMaterial(level, structure);
        return InteractionResult.CONSUME;
    }

    private static InteractionResult useStructureBlock(ServerPlayer player, PhysicsStructure structure, StructureHit hit,
                                                       InteractionHand hand, ItemStack stack) {
        ServerLevel shipyard = ShipyardManager.getShipyardLevel(player.server);
        ShipyardManager.ShipyardRegion region = ShipyardManager.getRegion(structure.getBodyId());
        if (shipyard == null || region == null) {
            return InteractionResult.PASS;
        }
        BlockPos localPos = hit.localPos;
        if (localPos.getX() < 0 || localPos.getY() < 0 || localPos.getZ() < 0
                || localPos.getX() >= region.sizeX()
                || localPos.getY() >= region.sizeY()
                || localPos.getZ() >= region.sizeZ()) {
            return InteractionResult.PASS;
        }
        BlockPos shipyardPos = ShipyardManager.toWorld(region, localPos);
        if (shipyardPos == null) {
            return InteractionResult.PASS;
        }
        BlockState state = shipyard.getBlockState(shipyardPos);
        if (state.isAir()) {
            return InteractionResult.PASS;
        }

        Direction face = hit.face != null ? hit.face : Direction.UP;
        Vec3 shipyardCenter = new Vec3(shipyardPos.getX() + 0.5, shipyardPos.getY() + 0.5, shipyardPos.getZ() + 0.5);
        BlockHitResult shipyardHit = new BlockHitResult(shipyardCenter, face, shipyardPos, false);
        ItemInteractionResult itemResult = state.useItemOn(stack, shipyard, player, hand, shipyardHit);
        if (itemResult.consumesAction()) {
            return itemResult.result();
        }
        if (itemResult == ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
                && hand == InteractionHand.MAIN_HAND) {
            InteractionResult blockResult = state.useWithoutItem(shipyard, player, shipyardHit);
            if (blockResult.consumesAction()) {
                return blockResult;
            }
        }
        if (!stack.isEmpty()) {
            UseOnContext useContext = new UseOnContext(shipyard, player, hand, stack, shipyardHit);
            InteractionResult stackResult = stack.useOn(useContext);
            if (stackResult.consumesAction()) {
                return stackResult;
            }
        }
        return InteractionResult.PASS;
    }

    public static void onLevelTick(ServerLevel level) {
        if (!acceptingTasks) {
            return;
        }
        if (!PhysicsWorldManager.isNativeAvailable()) {
            return;
        }

        applyLevitation(level);

        for (PhysicsStructure structure : STRUCTURES.values()) {
            if (structure.getDimension() != level.dimension()) {
                continue;
            }
            if (!structure.isDirty() || structure.isRebuildRunning()) {
                continue;
            }
            startRebuild(level, structure);
        }

        ensureWorldColliders(level);
    }

    public static void shutdown() {
        acceptingTasks = false;
        ExecutorService executorToShutdown;
        synchronized (EXECUTOR_LOCK) {
            executorToShutdown = optimizerExecutor;
            optimizerExecutor = null;
        }
        if (executorToShutdown != null) {
            executorToShutdown.shutdownNow();
        }
        STRUCTURES.clear();
        MINING.clear();
    }

    public static void reset() {
        acceptingTasks = true;
        getExecutor();
    }

    private static void startRebuild(ServerLevel level, PhysicsStructure structure) {
        structure.setRebuildRunning(true);
        structure.clearDirty();
        LongOpenHashSet snapshot = structure.snapshotCollidableBlocks();
        CompletableFuture
                .supplyAsync(() -> VoxelShapeOptimizer.optimizeLongSet(snapshot), getExecutor())
                .thenAccept(boxes -> level.getServer().execute(() -> applyRebuild(level, structure, boxes)))
                .exceptionally(ex -> {
                    structure.setRebuildRunning(false);
                    return null;
                });
    }

    private static void applyRebuild(ServerLevel level, PhysicsStructure structure, List<AABB> localBoxes) {
        NativePhysicsEngine engine = PhysicsWorldManager.getEngine();
        if (engine == null) {
            structure.setRebuildRunning(false);
            return;
        }

        List<AABB> bodyLocal = offsetBoxes(localBoxes, structure.getOriginOffset());
        if (bodyLocal.isEmpty()) {
            structure.setRebuildRunning(false);
            return;
        }

        engine.updateBodyShape(structure.getBodyId(), bodyLocal);
        PhysicsColliderManager.updateAndSyncBody(level, structure.getBodyId(), bodyLocal);
        syncBodyMaterial(level, structure);
        structure.setRebuildRunning(false);
    }

    private static void ensureWorldColliders(ServerLevel level) {
        int budget = 32;
        for (PhysicsStructure structure : STRUCTURES.values()) {
            if (structure.getDimension() != level.dimension()) {
                continue;
            }
            AABB bounds = PhysicsColliderManager.getBodyBounds(structure.getBodyId());
            if (bounds == null) {
                continue;
            }
            int minChunkX = Mth.floor(bounds.minX) >> 4;
            int minChunkZ = Mth.floor(bounds.minZ) >> 4;
            int maxChunkX = Mth.floor(bounds.maxX) >> 4;
            int maxChunkZ = Mth.floor(bounds.maxZ) >> 4;
            for (int cx = minChunkX; cx <= maxChunkX; cx++) {
                for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                    if (!level.hasChunk(cx, cz)) {
                        continue;
                    }
                    WorldCollisionManager.ensureChunkCollider(level, new ChunkPos(cx, cz));
                    budget--;
                    if (budget <= 0) {
                        return;
                    }
                }
            }
        }
    }

    private static void applyLevitation(ServerLevel level) {
        NativePhysicsEngine engine = PhysicsWorldManager.getEngine();
        if (engine == null) {
            return;
        }
        for (PhysicsStructure structure : STRUCTURES.values()) {
            if (structure.getDimension() != level.dimension()) {
                continue;
            }
            StructurePhysicsProperties.MaterialSummary material = structure.getPhysicsProperties().snapshot();
            if (material.mass() <= 0.0f || material.levitationAccel() <= 0.0f) {
                continue;
            }
            engine.applyForce(structure.getBodyId(), new Vector3f(0.0f, material.mass() * material.levitationAccel(), 0.0f));
            engine.activateBody(structure.getBodyId());
        }
    }

    private static List<AABB> offsetBoxes(List<AABB> boxes, Vector3f offset) {
        if (boxes.isEmpty()) {
            return List.of();
        }
        List<AABB> result = new ArrayList<>(boxes.size());
        for (AABB box : boxes) {
            result.add(new AABB(
                    box.minX + offset.x, box.minY + offset.y, box.minZ + offset.z,
                    box.maxX + offset.x, box.maxY + offset.y, box.maxZ + offset.z
            ));
        }
        return result;
    }

    private static StructureHit raycastStructure(Player player, PhysicsStructure structure, NativePhysicsEngine engine) {
        float[] state = structure.getStateBuffer();
        engine.getBodyState(structure.getBodyId(), state);
        Vector3f bodyPos = new Vector3f(state[0], state[1], state[2]);
        Quaternionf rotation = new Quaternionf(state[3], state[4], state[5], state[6]);

        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1.0f);
        double reach = player.blockInteractionRange();

        Vector3f originWorld = new Vector3f(structure.getOriginOffset());
        rotation.transform(originWorld);
        originWorld.add(bodyPos);

        Quaternionf inverse = new Quaternionf(rotation).invert();
        Vector3f localOrigin = new Vector3f((float) (eye.x - originWorld.x), (float) (eye.y - originWorld.y), (float) (eye.z - originWorld.z));
        inverse.transform(localOrigin);

        Vector3f localDir = new Vector3f((float) look.x, (float) look.y, (float) look.z);
        if (localDir.lengthSquared() < 1.0E-6f) {
            return null;
        }
        localDir.normalize();
        inverse.transform(localDir);

        LocalRayHit localHit = raycastLocalBlocks(structure, localOrigin, localDir, reach);
        if (localHit == null) {
            return null;
        }

        BlockPos localPos = localHit.pos();
        StructureBlockData data = structure.getBlock(BlockPos.asLong(localPos.getX(), localPos.getY(), localPos.getZ()));
        if (data == null) {
            return null;
        }

        Vec3 worldCenter = structure.localToWorldCenter(localPos, bodyPos, rotation);
        BlockPos worldBlockPos = BlockPos.containing(worldCenter);
        return new StructureHit(localPos, BlockPos.asLong(localPos.getX(), localPos.getY(), localPos.getZ()), localHit.face(), worldCenter, worldBlockPos, data);
    }

    private static LocalRayHit raycastLocalBlocks(PhysicsStructure structure, Vector3f origin, Vector3f direction, double reach) {
        double ox = origin.x;
        double oy = origin.y;
        double oz = origin.z;

        int x = (int) Math.floor(ox);
        int y = (int) Math.floor(oy);
        int z = (int) Math.floor(oz);

        int stepX = direction.x > 0 ? 1 : (direction.x < 0 ? -1 : 0);
        int stepY = direction.y > 0 ? 1 : (direction.y < 0 ? -1 : 0);
        int stepZ = direction.z > 0 ? 1 : (direction.z < 0 ? -1 : 0);

        double tMaxX = stepX == 0 ? Double.POSITIVE_INFINITY : intBound(ox, direction.x, stepX);
        double tMaxY = stepY == 0 ? Double.POSITIVE_INFINITY : intBound(oy, direction.y, stepY);
        double tMaxZ = stepZ == 0 ? Double.POSITIVE_INFINITY : intBound(oz, direction.z, stepZ);

        double tDeltaX = stepX == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / direction.x);
        double tDeltaY = stepY == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / direction.y);
        double tDeltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / direction.z);

        double t = 0.0;
        Direction lastFace = null;
        while (t <= reach) {
            long key = BlockPos.asLong(x, y, z);
            if (structure.getBlocks().containsKey(key)) {
                return new LocalRayHit(new BlockPos(x, y, z), lastFace);
            }

            if (tMaxX < tMaxY) {
                if (tMaxX < tMaxZ) {
                    x += stepX;
                    t = tMaxX;
                    tMaxX += tDeltaX;
                    if (stepX != 0) {
                        lastFace = stepX > 0 ? Direction.WEST : Direction.EAST;
                    }
                } else {
                    z += stepZ;
                    t = tMaxZ;
                    tMaxZ += tDeltaZ;
                    if (stepZ != 0) {
                        lastFace = stepZ > 0 ? Direction.NORTH : Direction.SOUTH;
                    }
                }
            } else {
                if (tMaxY < tMaxZ) {
                    y += stepY;
                    t = tMaxY;
                    tMaxY += tDeltaY;
                    if (stepY != 0) {
                        lastFace = stepY > 0 ? Direction.DOWN : Direction.UP;
                    }
                } else {
                    z += stepZ;
                    t = tMaxZ;
                    tMaxZ += tDeltaZ;
                    if (stepZ != 0) {
                        lastFace = stepZ > 0 ? Direction.NORTH : Direction.SOUTH;
                    }
                }
            }
        }

        return null;
    }

    private static double intBound(double s, double ds, int step) {
        if (ds == 0.0) {
            return Double.POSITIVE_INFINITY;
        }
        if (step > 0) {
            return (Math.floor(s + 1.0) - s) / ds;
        }
        return (s - Math.floor(s)) / -ds;
    }

    private static void breakBlock(ServerPlayer player, PhysicsStructure structure, StructureHit hit) {
        ServerLevel shipyard = ShipyardManager.getShipyardLevel(player.server);
        ShipyardManager.ShipyardRegion region = ShipyardManager.getRegion(structure.getBodyId());
        if (shipyard == null || region == null) {
            breakBlockFallback(player, structure, hit);
            return;
        }
        BlockPos shipyardPos = ShipyardManager.toWorld(region, hit.localPos);
        if (shipyardPos == null) {
            return;
        }
        BlockState state = shipyard.getBlockState(shipyardPos);
        if (state.isAir()) {
            return;
        }
        BlockEntity blockEntity = shipyard.getBlockEntity(shipyardPos);

        ServerLevel level = (ServerLevel) player.level();
        if (!player.getAbilities().instabuild) {
            BlockStateDrops drops = collectDrops(level, hit.worldBlockPos, new StructureBlockData(state,
                    blockEntity != null ? blockEntity.saveWithId(shipyard.registryAccess()) : null,
                    !state.getCollisionShape(shipyard, shipyardPos).isEmpty()), player);
            for (ItemStack stack : drops.items()) {
                Block.popResource(level, hit.worldBlockPos, stack);
            }
            if (drops.dropContents() && drops.blockEntity() instanceof net.minecraft.world.Container container) {
                Containers.dropContents(level, hit.worldBlockPos, container);
            }
        }

        shipyard.setBlock(shipyardPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

        level.playSound(null, hit.worldCenter.x, hit.worldCenter.y, hit.worldCenter.z,
                state.getSoundType().getBreakSound(), SoundSource.BLOCKS, 1.0f, 1.0f);

        if (!player.getAbilities().instabuild) {
            ItemStack tool = player.getMainHandItem();
            if (!tool.isEmpty()) {
                tool.hurtAndBreak(1, player, EquipmentSlot.MAINHAND);
            }
        }
    }

    private static void breakBlockFallback(ServerPlayer player, PhysicsStructure structure, StructureHit hit) {
        StructureBlockData data = structure.removeBlock(hit.blockKey);
        if (data == null) {
            return;
        }
        structure.getPhysicsProperties().removeState(data.state());

        if (data.collidable()) {
            structure.markDirty();
        }

        ServerLevel level = (ServerLevel) player.level();
        if (!player.getAbilities().instabuild) {
            BlockStateDrops drops = collectDrops(level, hit.worldBlockPos, data, player);
            for (ItemStack stack : drops.items()) {
                Block.popResource(level, hit.worldBlockPos, stack);
            }
            if (drops.dropContents() && drops.blockEntity() instanceof net.minecraft.world.Container container) {
                Containers.dropContents(level, hit.worldBlockPos, container);
            }
        }

        level.playSound(null, hit.worldCenter.x, hit.worldCenter.y, hit.worldCenter.z,
                data.state().getSoundType().getBreakSound(), SoundSource.BLOCKS, 1.0f, 1.0f);

        if (!player.getAbilities().instabuild) {
            ItemStack tool = player.getMainHandItem();
            if (!tool.isEmpty()) {
                tool.hurtAndBreak(1, player, EquipmentSlot.MAINHAND);
            }
        }

        syncBlockChange(level, structure, hit.localPos, null, data.state(), true);
        syncBodyMaterial(level, structure);
    }

    private static PhysicsStructureEntity getStructureEntity(ServerLevel level, PhysicsStructure structure) {
        if (level == null || structure == null) {
            return null;
        }
        int entityId = structure.getEntityId();
        if (entityId <= 0) {
            return null;
        }
        Entity entity = level.getEntity(entityId);
        return entity instanceof PhysicsStructureEntity structureEntity ? structureEntity : null;
    }

    private static Map<BlockPos, BlockState> buildRenderBlocks(PhysicsStructure structure) {
        Map<BlockPos, BlockState> renderBlocks = new HashMap<>(structure.getBlocks().size());
        structure.getBlocks().long2ObjectEntrySet().forEach(entry -> {
            renderBlocks.put(BlockPos.of(entry.getLongKey()), entry.getValue().state());
        });
        return renderBlocks;
    }

    private static Map<BlockPos, CompoundTag> buildRenderBlockEntities(PhysicsStructure structure) {
        Map<BlockPos, CompoundTag> renderBlockEntities = new HashMap<>();
        structure.getBlocks().long2ObjectEntrySet().forEach(entry -> {
            CompoundTag tag = entry.getValue().blockEntityTag();
            if (tag != null && !tag.isEmpty()) {
                renderBlockEntities.put(BlockPos.of(entry.getLongKey()), tag);
            }
        });
        return renderBlockEntities;
    }

    private static BlockStateDrops collectDrops(ServerLevel level, BlockPos worldPos, StructureBlockData data, Player player) {
        BlockEntity blockEntity = null;
        CompoundTag tag = data.blockEntityTag();
        if (tag != null && !tag.isEmpty()) {
            blockEntity = BlockEntity.loadStatic(worldPos, data.state(), tag, level.registryAccess());
        }

        ItemStack tool = player.getMainHandItem();
        List<ItemStack> drops = Block.getDrops(data.state(), level, worldPos, blockEntity, player, tool);

        boolean dropContents = blockEntity != null && !(data.state().getBlock() instanceof ShulkerBoxBlock);
        return new BlockStateDrops(blockEntity, drops, dropContents);
    }

    private static void syncBlockChange(ServerLevel level, PhysicsStructure structure, BlockPos localPos, BlockState state,
                                        BlockState particleState, boolean breaking) {
        int entityId = structure.getEntityId();
        if (entityId <= 0) {
            return;
        }
        Entity entity = level.getEntity(entityId);
        if (!(entity instanceof PhysicsStructureEntity structureEntity)) {
            return;
        }
        boolean hasState = state != null && !state.isAir();
        CompoundTag stateTag = hasState ? NbtUtils.writeBlockState(state) : new CompoundTag();
        CompoundTag blockEntityTag = new CompoundTag();
        if (hasState) {
            long key = BlockPos.asLong(localPos.getX(), localPos.getY(), localPos.getZ());
            StructureBlockData data = structure.getBlock(key);
            if (data != null && data.blockEntityTag() != null && !data.blockEntityTag().isEmpty()) {
                blockEntityTag = data.blockEntityTag();
            }
        }
        structureEntity.applyBlockUpdate(localPos, state, blockEntityTag);
        if (particleState != null && !particleState.isAir()) {
            spawnStructureParticles(level, structure, localPos, particleState, breaking);
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingEntityAndSelf(
                structureEntity,
                new StructureBlockUpdatePacket(entityId, localPos.asLong(), hasState, stateTag, blockEntityTag)
        );
    }

    private static void syncBodyMaterial(ServerLevel level, PhysicsStructure structure) {
        NativePhysicsEngine engine = PhysicsWorldManager.getEngine();
        if (engine == null) {
            return;
        }
        StructurePhysicsProperties.MaterialSummary material = structure.getPhysicsProperties().snapshot();
        engine.setBodyMaterial(structure.getBodyId(), material);
        if (material.levitationAccel() > 0.0f) {
            engine.activateBody(structure.getBodyId());
        }
    }

    private static void spawnStructureParticles(ServerLevel level, PhysicsStructure structure, BlockPos localPos,
                                                BlockState state, boolean breaking) {
        NativePhysicsEngine engine = PhysicsWorldManager.getEngine();
        if (engine == null) {
            return;
        }
        float[] buffer = structure.getStateBuffer();
        engine.getBodyState(structure.getBodyId(), buffer);
        Vector3f bodyPos = new Vector3f(buffer[0], buffer[1], buffer[2]);
        Quaternionf rotation = new Quaternionf(buffer[3], buffer[4], buffer[5], buffer[6]);
        Vec3 worldCenter = structure.localToWorldCenter(localPos, bodyPos, rotation);

        int count = breaking ? 12 : 6;
        double spread = 0.25;
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state),
                worldCenter.x, worldCenter.y, worldCenter.z,
                count, spread, spread, spread, 0.02);
    }


    private static ExecutorService getExecutor() {
        synchronized (EXECUTOR_LOCK) {
            if (optimizerExecutor == null || optimizerExecutor.isShutdown() || optimizerExecutor.isTerminated()) {
                optimizerExecutor = createExecutor();
            }
            return optimizerExecutor;
        }
    }

    private static ExecutorService createExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Physics-Structure-Rebuild");
            t.setDaemon(true);
            return t;
        });
    }

    private record LocalRayHit(BlockPos pos, Direction face) {
    }

    private record StructureHit(BlockPos localPos, long blockKey, Direction face, Vec3 worldCenter, BlockPos worldBlockPos, StructureBlockData data) {
    }

    private record BlockStateDrops(BlockEntity blockEntity, List<ItemStack> items, boolean dropContents) {
    }

    private static class MiningState {
        private final long bodyId;
        private final long blockKey;
        private final net.minecraft.world.level.block.state.BlockState state;
        private float progress = 0.0f;
        private long lastTick = 0L;

        private MiningState(long bodyId, long blockKey, net.minecraft.world.level.block.state.BlockState state) {
            this.bodyId = bodyId;
            this.blockKey = blockKey;
            this.state = state;
        }
    }

}
