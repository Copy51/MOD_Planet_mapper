package com.example.planetmapper.network;

import com.example.planetmapper.PlanetMapper;
import com.example.planetmapper.entity.PhysicsStructureEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public record StructureSyncChunkPacket(int entityId, int index, int total, byte[] payload) implements CustomPacketPayload {
    public static final Type<StructureSyncChunkPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(PlanetMapper.MODID, "structure_sync_chunk"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StructureSyncChunkPacket> STREAM_CODEC = StreamCodec.composite(
            net.minecraft.network.codec.ByteBufCodecs.VAR_INT,
            StructureSyncChunkPacket::entityId,
            net.minecraft.network.codec.ByteBufCodecs.VAR_INT,
            StructureSyncChunkPacket::index,
            net.minecraft.network.codec.ByteBufCodecs.VAR_INT,
            StructureSyncChunkPacket::total,
            net.minecraft.network.codec.ByteBufCodecs.BYTE_ARRAY,
            StructureSyncChunkPacket::payload,
            StructureSyncChunkPacket::new
    );

    private static final long BUFFER_TTL_MS = 30_000L;
    private static final Map<Integer, ChunkBuffer> BUFFERS = new HashMap<>();

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(final StructureSyncChunkPacket packet, final IPayloadContext context) {
        context.enqueueWork(() -> {
            if (packet.total <= 0 || packet.index < 0 || packet.index >= packet.total) {
                PlanetMapper.LOGGER.warn("Ignoring invalid structure sync chunk {}/{} for entity {}", packet.index, packet.total, packet.entityId);
                return;
            }
            cleanupExpired();

            ChunkBuffer buffer = BUFFERS.computeIfAbsent(packet.entityId, id -> new ChunkBuffer(packet.total));
            if (buffer.total != packet.total) {
                buffer = new ChunkBuffer(packet.total);
                BUFFERS.put(packet.entityId, buffer);
            }

            if (buffer.parts[packet.index] == null) {
                buffer.parts[packet.index] = packet.payload;
                buffer.received++;
                buffer.lastUpdated = System.currentTimeMillis();
            }

            if (buffer.received < buffer.total) {
                return;
            }

            BUFFERS.remove(packet.entityId);
            byte[] assembled = assemble(buffer.parts);
            CompoundTag tag = StructureSyncCodec.decode(assembled);
            if (tag == null) {
                return;
            }
            if (Minecraft.getInstance().level == null) {
                return;
            }
            Entity entity = Minecraft.getInstance().level.getEntity(packet.entityId);
            if (entity instanceof PhysicsStructureEntity structureEntity) {
                PlanetMapper.LOGGER.info("Received chunked structure sync for entity {}. Blocks: {}", packet.entityId, tag.getList("Structure", 10).size());
                structureEntity.readStructureData(tag);
            } else {
                PlanetMapper.LOGGER.warn("Received chunked structure sync for unknown or non-structure entity {}", packet.entityId);
            }
        });
    }

    private static byte[] assemble(byte[][] parts) {
        int totalSize = 0;
        for (byte[] part : parts) {
            if (part != null) {
                totalSize += part.length;
            }
        }
        byte[] result = new byte[totalSize];
        int offset = 0;
        for (byte[] part : parts) {
            if (part == null || part.length == 0) {
                continue;
            }
            System.arraycopy(part, 0, result, offset, part.length);
            offset += part.length;
        }
        return result;
    }

    private static void cleanupExpired() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<Integer, ChunkBuffer>> iterator = BUFFERS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, ChunkBuffer> entry = iterator.next();
            if (now - entry.getValue().lastUpdated > BUFFER_TTL_MS) {
                iterator.remove();
            }
        }
    }

    private static final class ChunkBuffer {
        private final int total;
        private final byte[][] parts;
        private int received;
        private long lastUpdated;

        private ChunkBuffer(int total) {
            this.total = total;
            this.parts = new byte[total][];
            this.lastUpdated = System.currentTimeMillis();
        }
    }
}
