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

public record StructureSyncPacket(int entityId, CompoundTag structure) implements CustomPacketPayload {

    public static final Type<StructureSyncPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(PlanetMapper.MODID, "structure_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StructureSyncPacket> STREAM_CODEC = StreamCodec.composite(
            net.minecraft.network.codec.ByteBufCodecs.VAR_INT,
            StructureSyncPacket::entityId,
            net.minecraft.network.codec.ByteBufCodecs.COMPOUND_TAG,
            StructureSyncPacket::structure,
            StructureSyncPacket::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(final StructureSyncPacket packet, final IPayloadContext context) {
        context.enqueueWork(() -> {
            Entity entity = Minecraft.getInstance().level.getEntity(packet.entityId);
            if (entity instanceof PhysicsStructureEntity structureEntity) {
                com.example.planetmapper.PlanetMapper.LOGGER.info("Received structure sync for entity {}. Blocks: {}", packet.entityId, packet.structure.getList("Structure", 10).size());
                structureEntity.readStructureData(packet.structure);
            } else {
                com.example.planetmapper.PlanetMapper.LOGGER.warn("Received structure sync for unknown or non-structure entity {}", packet.entityId);
            }
        });
    }
}
