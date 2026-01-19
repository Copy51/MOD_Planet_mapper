package com.example.planetmapper.shipyard;

import com.example.planetmapper.PlanetMapper;
import com.example.planetmapper.physics.structure.StructureBlockData;
import com.example.planetmapper.physics.structure.StructurePhysicsManager;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class ShipyardManager {
    private static final int BASE_Y = 64;
    private static final int PADDING = 4;
    private static final int MAX_ROW_WIDTH = 4096;
    private static final int UPDATES_PER_TICK = 2000;

    private static final Map<UUID, ShipyardRegion> REGIONS_BY_OWNER = new HashMap<>();
    private static final Long2ObjectOpenHashMap<ShipyardRegion> REGIONS_BY_BODY = new Long2ObjectOpenHashMap<>();
    private static final ArrayDeque<PendingUpdate> PENDING_UPDATES = new ArrayDeque<>();
    private static int nextX = 0;
    private static int nextZ = 0;
    private static int rowDepth = 0;
    private static boolean loaded = false;
    private static final ThreadLocal<Boolean> SUPPRESS_UPDATES = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private ShipyardManager() {
    }

    public static ServerLevel getShipyardLevel(MinecraftServer server) {
        if (server == null) {
            return null;
        }
        return server.getLevel(PlanetMapper.SHIPYARD_LEVEL);
    }

    public static boolean isShipyardLevel(ServerLevel level) {
        return level != null && level.dimension() == PlanetMapper.SHIPYARD_LEVEL;
    }

    public static ShipyardRegion getRegion(long bodyId) {
        synchronized (REGIONS_BY_BODY) {
            return REGIONS_BY_BODY.get(bodyId);
        }
    }

    public static ShipyardRegion getRegion(UUID ownerId) {
        if (ownerId == null) {
            return null;
        }
        synchronized (REGIONS_BY_OWNER) {
            return REGIONS_BY_OWNER.get(ownerId);
        }
    }

    public static ShipyardRegion getRegion(ServerLevel shipyard, UUID ownerId) {
        if (ownerId == null) {
            return null;
        }
        ensureLoaded(shipyard);
        return getRegion(ownerId);
    }

    public static boolean ensureRegionChunksLoaded(ServerLevel shipyard, ShipyardRegion region) {
        if (shipyard == null || region == null) {
            return false;
        }
        ChunkPos min = region.minChunk();
        ChunkPos max = region.maxChunk();
        for (int cx = min.x; cx <= max.x; cx++) {
            for (int cz = min.z; cz <= max.z; cz++) {
                if (!shipyard.hasChunk(cx, cz)) {
                    shipyard.getChunk(cx, cz);
                }
                if (!shipyard.hasChunk(cx, cz)) {
                    return false;
                }
            }
        }
        return true;
    }

    public static ExpansionResult expandRegion(ServerLevel shipyard, ShipyardRegion region, BlockPos localPos) {
        if (shipyard == null || region == null || localPos == null) {
            return null;
        }
        int minX = Math.min(0, localPos.getX());
        int minY = Math.min(0, localPos.getY());
        int minZ = Math.min(0, localPos.getZ());
        int maxX = Math.max(region.sizeX - 1, localPos.getX());
        int maxY = Math.max(region.sizeY - 1, localPos.getY());
        int maxZ = Math.max(region.sizeZ - 1, localPos.getZ());
        if (minX == 0 && minY == 0 && minZ == 0
                && maxX == region.sizeX - 1
                && maxY == region.sizeY - 1
                && maxZ == region.sizeZ - 1) {
            return null;
        }

        BlockPos shift = new BlockPos(-minX, -minY, -minZ);
        BlockPos oldOrigin = region.origin;
        ChunkPos oldMin = region.minChunk;
        ChunkPos oldMax = region.maxChunk;

        BlockPos newOrigin = new BlockPos(oldOrigin.getX() + minX, oldOrigin.getY() + minY, oldOrigin.getZ() + minZ);
        int newSizeX = maxX - minX + 1;
        int newSizeY = maxY - minY + 1;
        int newSizeZ = maxZ - minZ + 1;
        BlockPos newMax = new BlockPos(newOrigin.getX() + newSizeX - 1, newOrigin.getY() + newSizeY - 1, newOrigin.getZ() + newSizeZ - 1);
        ChunkPos newMin = new ChunkPos(newOrigin);
        ChunkPos newMaxChunk = new ChunkPos(newMax);

        region.origin = newOrigin;
        region.sizeX = newSizeX;
        region.sizeY = newSizeY;
        region.sizeZ = newSizeZ;
        region.minChunk = newMin;
        region.maxChunk = newMaxChunk;

        if (shipyard != null) {
            for (int cx = oldMin.x; cx <= oldMax.x; cx++) {
                for (int cz = oldMin.z; cz <= oldMax.z; cz++) {
                    shipyard.setChunkForced(cx, cz, false);
                }
            }
            for (int cx = newMin.x; cx <= newMaxChunk.x; cx++) {
                for (int cz = newMin.z; cz <= newMaxChunk.z; cz++) {
                    shipyard.setChunkForced(cx, cz, true);
                }
            }
            ShipyardSavedData data = ShipyardSavedData.get(shipyard);
            data.putRegion(region.ownerId, new ShipyardSavedData.RegionData(newOrigin, newSizeX, newSizeY, newSizeZ));
        }

        return new ExpansionResult(shift, region);
    }

    public static ShipyardRegion findRegion(BlockPos worldPos) {
        if (worldPos == null) {
            return null;
        }
        synchronized (REGIONS_BY_OWNER) {
            for (ShipyardRegion region : REGIONS_BY_OWNER.values()) {
                BlockPos origin = region.origin();
                if (worldPos.getX() < origin.getX()
                        || worldPos.getY() < origin.getY()
                        || worldPos.getZ() < origin.getZ()) {
                    continue;
                }
                if (worldPos.getX() > origin.getX() + region.sizeX() - 1
                        || worldPos.getY() > origin.getY() + region.sizeY() - 1
                        || worldPos.getZ() > origin.getZ() + region.sizeZ() - 1) {
                    continue;
                }
                return region;
            }
        }
        return null;
    }

    public static BlockPos toLocal(ShipyardRegion region, BlockPos worldPos) {
        if (region == null || worldPos == null) {
            return null;
        }
        BlockPos origin = region.origin();
        return new BlockPos(worldPos.getX() - origin.getX(),
                worldPos.getY() - origin.getY(),
                worldPos.getZ() - origin.getZ());
    }

    public static BlockPos toWorld(ShipyardRegion region, BlockPos localPos) {
        if (region == null || localPos == null) {
            return null;
        }
        BlockPos origin = region.origin();
        return new BlockPos(origin.getX() + localPos.getX(),
                origin.getY() + localPos.getY(),
                origin.getZ() + localPos.getZ());
    }

    public static ShipyardRegion ensureRegion(ServerLevel shipyard, UUID ownerId, long bodyId, int sizeX, int sizeY, int sizeZ) {
        if (shipyard == null || ownerId == null) {
            return null;
        }
        ensureLoaded(shipyard);
        synchronized (REGIONS_BY_OWNER) {
            ShipyardRegion existing = REGIONS_BY_OWNER.get(ownerId);
            if (existing != null) {
                if (existing.sizeX != sizeX || existing.sizeY != sizeY || existing.sizeZ != sizeZ) {
                    removeRegionByOwner(shipyard, ownerId);
                } else {
                    bindBodyId(ownerId, bodyId, shipyard);
                    forceChunks(shipyard, existing, true);
                    return existing;
                }
            }
            ShipyardRegion region = allocateRegion(ownerId, bodyId, sizeX, sizeY, sizeZ);
            registerRegion(shipyard, region, true);
            return region;
        }
    }

    public static void placeBlocks(ServerLevel shipyard, ShipyardRegion region, Long2ObjectOpenHashMap<StructureBlockData> blocks) {
        if (shipyard == null || region == null || blocks == null || blocks.isEmpty()) {
            return;
        }
        runWithSuppressedUpdates(() -> {
            int flags = Block.UPDATE_NONE | Block.UPDATE_SUPPRESS_DROPS;
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            for (Long2ObjectMap.Entry<StructureBlockData> entry : blocks.long2ObjectEntrySet()) {
                StructureBlockData data = entry.getValue();
                if (data == null) {
                    continue;
                }
                BlockPos local = BlockPos.of(entry.getLongKey());
                cursor.set(region.origin().getX() + local.getX(),
                        region.origin().getY() + local.getY(),
                        region.origin().getZ() + local.getZ());
                BlockState state = data.state();
                shipyard.setBlock(cursor, state, flags);
                if (data.blockEntityTag() != null && !data.blockEntityTag().isEmpty()) {
                    BlockEntity blockEntity = BlockEntity.loadStatic(cursor, state, data.blockEntityTag(), shipyard.registryAccess());
                    if (blockEntity != null) {
                        shipyard.setBlockEntity(blockEntity);
                    }
                }
            }
        });
    }

    public static void queueNeighborUpdates(ShipyardRegion region, Long2ObjectOpenHashMap<StructureBlockData> blocks) {
        if (region == null || blocks == null || blocks.isEmpty()) {
            return;
        }
        LongArrayList keys = new LongArrayList(blocks.size());
        for (Long2ObjectMap.Entry<StructureBlockData> entry : blocks.long2ObjectEntrySet()) {
            StructureBlockData data = entry.getValue();
            if (data != null && !data.state().isAir()) {
                keys.add(entry.getLongKey());
            }
        }
        if (keys.isEmpty()) {
            return;
        }
        synchronized (PENDING_UPDATES) {
            PENDING_UPDATES.add(new PendingUpdate(region.ownerId, keys));
        }
    }

    public static void clearRegion(ServerLevel shipyard, ShipyardRegion region) {
        if (shipyard == null || region == null) {
            return;
        }
        runWithSuppressedUpdates(() -> {
            BlockPos min = region.origin();
            BlockPos max = region.getMax();
            int flags = Block.UPDATE_NONE | Block.UPDATE_SUPPRESS_DROPS;
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            for (int x = min.getX(); x <= max.getX(); x++) {
                for (int y = min.getY(); y <= max.getY(); y++) {
                    for (int z = min.getZ(); z <= max.getZ(); z++) {
                        cursor.set(x, y, z);
                        shipyard.setBlock(cursor, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), flags);
                    }
                }
            }
        });
    }

    public static void removeRegion(ServerLevel shipyard, long bodyId) {
        ShipyardRegion region;
        synchronized (REGIONS_BY_BODY) {
            region = REGIONS_BY_BODY.remove(bodyId);
        }
        if (region == null) {
            return;
        }
        synchronized (REGIONS_BY_OWNER) {
            REGIONS_BY_OWNER.remove(region.ownerId);
        }
        clearRegion(shipyard, region);
        forceChunks(shipyard, region, false);
        if (shipyard != null) {
            ShipyardSavedData data = ShipyardSavedData.get(shipyard);
            data.removeRegion(region.ownerId);
        }
    }

    public static void removeRegionByOwner(ServerLevel shipyard, UUID ownerId) {
        if (ownerId == null) {
            return;
        }
        ShipyardRegion region;
        synchronized (REGIONS_BY_OWNER) {
            region = REGIONS_BY_OWNER.remove(ownerId);
        }
        if (region == null) {
            return;
        }
        synchronized (REGIONS_BY_BODY) {
            if (region.bodyId > 0) {
                REGIONS_BY_BODY.remove(region.bodyId);
            }
        }
        clearRegion(shipyard, region);
        forceChunks(shipyard, region, false);
        if (shipyard != null) {
            ShipyardSavedData data = ShipyardSavedData.get(shipyard);
            data.removeRegion(ownerId);
        }
    }

    public static void handleBlockChange(ServerLevel shipyard, BlockPos pos, BlockState newState) {
        if (!isShipyardLevel(shipyard) || isSuppressed()) {
            return;
        }
        ShipyardRegion region = findRegion(pos);
        if (region == null) {
            return;
        }
        if (region.bodyId <= 0) {
            return;
        }
        BlockPos localPos = toLocal(region, pos);
        if (localPos == null) {
            return;
        }
        boolean collidable = newState != null && !newState.isAir()
                && !newState.getCollisionShape(shipyard, pos).isEmpty();
        BlockEntity blockEntity = newState != null && !newState.isAir() ? shipyard.getBlockEntity(pos) : null;
        net.minecraft.nbt.CompoundTag beTag = blockEntity != null ? blockEntity.saveWithId(shipyard.registryAccess()) : null;
        StructurePhysicsManager.applyShipyardUpdate(shipyard, region.bodyId(), localPos, newState, beTag, collidable);
    }

    public static void bindBodyId(UUID ownerId, long bodyId, ServerLevel shipyard) {
        if (ownerId == null) {
            return;
        }
        ensureLoaded(shipyard);
        ShipyardRegion region;
        synchronized (REGIONS_BY_OWNER) {
            region = REGIONS_BY_OWNER.get(ownerId);
        }
        if (region == null) {
            return;
        }
        if (bodyId > 0) {
            region.bodyId = bodyId;
            synchronized (REGIONS_BY_BODY) {
                REGIONS_BY_BODY.put(bodyId, region);
            }
        }
        forceChunks(shipyard, region, true);
    }

    public static void tick(ServerLevel shipyard) {
        if (shipyard == null || !isShipyardLevel(shipyard)) {
            return;
        }
        ensureLoaded(shipyard);
        int budget = UPDATES_PER_TICK;
        while (budget > 0) {
            PendingUpdate task;
            synchronized (PENDING_UPDATES) {
                task = PENDING_UPDATES.peek();
            }
            if (task == null) {
                break;
            }
            ShipyardRegion region = getRegion(task.ownerId);
            if (region == null) {
                synchronized (PENDING_UPDATES) {
                    PENDING_UPDATES.poll();
                }
                continue;
            }
            LongIterator iterator = task.iterator;
            while (budget > 0 && iterator.hasNext()) {
                long key = iterator.nextLong();
                BlockPos local = BlockPos.of(key);
                BlockPos worldPos = toWorld(region, local);
                if (worldPos == null) {
                    budget--;
                    continue;
                }
                BlockState state = shipyard.getBlockState(worldPos);
                if (!state.isAir()) {
                    shipyard.updateNeighborsAt(worldPos, state.getBlock());
                    shipyard.updateNeighbourForOutputSignal(worldPos, state.getBlock());
                }
                budget--;
            }
            if (!iterator.hasNext()) {
                synchronized (PENDING_UPDATES) {
                    PENDING_UPDATES.poll();
                }
            }
        }
    }

    public static void reset() {
        synchronized (REGIONS_BY_OWNER) {
            REGIONS_BY_OWNER.clear();
        }
        synchronized (REGIONS_BY_BODY) {
            REGIONS_BY_BODY.clear();
        }
        synchronized (PENDING_UPDATES) {
            PENDING_UPDATES.clear();
        }
        loaded = false;
        nextX = 0;
        nextZ = 0;
        rowDepth = 0;
    }

    private static void runWithSuppressedUpdates(Runnable task) {
        SUPPRESS_UPDATES.set(Boolean.TRUE);
        try {
            task.run();
        } finally {
            SUPPRESS_UPDATES.set(Boolean.FALSE);
        }
    }

    private static boolean isSuppressed() {
        return Boolean.TRUE.equals(SUPPRESS_UPDATES.get());
    }

    private static void ensureLoaded(ServerLevel shipyard) {
        if (loaded || shipyard == null) {
            return;
        }
        ShipyardSavedData data = ShipyardSavedData.get(shipyard);
        for (Map.Entry<UUID, ShipyardSavedData.RegionData> entry : data.getRegions().entrySet()) {
            UUID ownerId = entry.getKey();
            ShipyardSavedData.RegionData region = entry.getValue();
            ShipyardRegion loadedRegion = new ShipyardRegion(ownerId, 0L,
                    region.origin(), region.sizeX(), region.sizeY(), region.sizeZ(),
                    new ChunkPos(region.origin()),
                    new ChunkPos(region.getMax()));
            REGIONS_BY_OWNER.put(ownerId, loadedRegion);
        }
        recomputeAllocator();
        loaded = true;
    }

    private static void recomputeAllocator() {
        int maxZ = 0;
        for (ShipyardRegion region : REGIONS_BY_OWNER.values()) {
            BlockPos max = region.getMax();
            maxZ = Math.max(maxZ, max.getZ() + PADDING);
        }
        nextX = 0;
        nextZ = maxZ;
        rowDepth = 0;
    }

    private static ShipyardRegion allocateRegion(UUID ownerId, long bodyId, int sizeX, int sizeY, int sizeZ) {
        int paddedX = sizeX + PADDING * 2;
        int paddedZ = sizeZ + PADDING * 2;
        if (nextX + paddedX > MAX_ROW_WIDTH) {
            nextX = 0;
            nextZ += rowDepth;
            rowDepth = 0;
        }
        BlockPos origin = new BlockPos(nextX + PADDING, BASE_Y, nextZ + PADDING);
        nextX += paddedX;
        rowDepth = Math.max(rowDepth, paddedZ);

        BlockPos max = new BlockPos(origin.getX() + sizeX - 1, origin.getY() + sizeY - 1, origin.getZ() + sizeZ - 1);
        ChunkPos minChunk = new ChunkPos(origin);
        ChunkPos maxChunk = new ChunkPos(max);
        return new ShipyardRegion(ownerId, bodyId, origin, sizeX, sizeY, sizeZ, minChunk, maxChunk);
    }

    private static void forceChunks(ServerLevel shipyard, ShipyardRegion region, boolean force) {
        if (shipyard == null || region == null) {
            return;
        }
        ChunkPos min = region.minChunk();
        ChunkPos max = region.maxChunk();
        for (int cx = min.x; cx <= max.x; cx++) {
            for (int cz = min.z; cz <= max.z; cz++) {
                shipyard.setChunkForced(cx, cz, force);
            }
        }
    }

    private static void registerRegion(ServerLevel shipyard, ShipyardRegion region, boolean persist) {
        if (region == null) {
            return;
        }
        synchronized (REGIONS_BY_OWNER) {
            REGIONS_BY_OWNER.put(region.ownerId, region);
        }
        if (region.bodyId > 0) {
            synchronized (REGIONS_BY_BODY) {
                REGIONS_BY_BODY.put(region.bodyId, region);
            }
        }
        forceChunks(shipyard, region, true);
        if (persist && shipyard != null) {
            ShipyardSavedData data = ShipyardSavedData.get(shipyard);
            data.putRegion(region.ownerId, new ShipyardSavedData.RegionData(region.origin, region.sizeX, region.sizeY, region.sizeZ));
        }
    }

    public static final class ShipyardRegion {
        private final UUID ownerId;
        private long bodyId;
        private BlockPos origin;
        private int sizeX;
        private int sizeY;
        private int sizeZ;
        private ChunkPos minChunk;
        private ChunkPos maxChunk;

        private ShipyardRegion(UUID ownerId, long bodyId, BlockPos origin, int sizeX, int sizeY, int sizeZ,
                               ChunkPos minChunk, ChunkPos maxChunk) {
            this.ownerId = ownerId;
            this.bodyId = bodyId;
            this.origin = origin;
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            this.minChunk = minChunk;
            this.maxChunk = maxChunk;
        }

        public UUID ownerId() {
            return ownerId;
        }

        public long bodyId() {
            return bodyId;
        }

        public BlockPos origin() {
            return origin;
        }

        public int sizeX() {
            return sizeX;
        }

        public int sizeY() {
            return sizeY;
        }

        public int sizeZ() {
            return sizeZ;
        }

        public ChunkPos minChunk() {
            return minChunk;
        }

        public ChunkPos maxChunk() {
            return maxChunk;
        }

        public BlockPos getMax() {
            return new BlockPos(origin.getX() + sizeX - 1, origin.getY() + sizeY - 1, origin.getZ() + sizeZ - 1);
        }
    }

    public record ExpansionResult(BlockPos shift, ShipyardRegion region) {
    }

    private record PendingUpdate(UUID ownerId, LongIterator iterator) {
        private PendingUpdate(UUID ownerId, LongArrayList keys) {
            this(ownerId, keys.iterator());
        }
    }
}
