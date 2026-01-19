package com.example.planetmapper.network;

import com.example.planetmapper.entity.PhysicsStructureEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record StructureSyncPacket(int entityId, byte[] payload) implements CustomPacketPayload {

    public static final Type<StructureSyncPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(com.example.planetmapper.PlanetMapper.MODID, "structure_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StructureSyncPacket> STREAM_CODEC = StreamCodec.composite(
            net.minecraft.network.codec.ByteBufCodecs.VAR_INT,
            StructureSyncPacket::entityId,
            net.minecraft.network.codec.ByteBufCodecs.BYTE_ARRAY,
            StructureSyncPacket::payload,
            StructureSyncPacket::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(final StructureSyncPacket packet, final IPayloadContext context) {
        context.enqueueWork(() -> {
            var tag = StructureSyncCodec.decode(packet.payload);
            if (tag == null) {
                return;
            }
            if (Minecraft.getInstance().level == null) {
                return;
            }
            Entity entity = Minecraft.getInstance().level.getEntity(packet.entityId);
            if (entity instanceof PhysicsStructureEntity structureEntity) {
                com.example.planetmapper.PlanetMapper.LOGGER.info("Received structure sync for entity {}. Blocks: {}", packet.entityId, tag.getList("Structure", 10).size());
                structureEntity.readStructureData(tag);
            } else {
                com.example.planetmapper.PlanetMapper.LOGGER.warn("Received structure sync for unknown or non-structure entity {}", packet.entityId);
            }
        });
    }
}
