package com.example.planetmapper.network;

import com.example.planetmapper.PlanetMapper;
import com.example.planetmapper.entity.PhysicsStructureEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record StructureBlockUpdatePacket(int entityId, long localPos, boolean hasState, CompoundTag stateTag,
                                         CompoundTag blockEntityTag) implements CustomPacketPayload {

    public static final Type<StructureBlockUpdatePacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(PlanetMapper.MODID, "structure_block_update"));

    public static final StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, StructureBlockUpdatePacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, StructureBlockUpdatePacket::entityId,
                    ByteBufCodecs.VAR_LONG, StructureBlockUpdatePacket::localPos,
                    ByteBufCodecs.BOOL, StructureBlockUpdatePacket::hasState,
                    ByteBufCodecs.COMPOUND_TAG, StructureBlockUpdatePacket::stateTag,
                    ByteBufCodecs.COMPOUND_TAG, StructureBlockUpdatePacket::blockEntityTag,
                    StructureBlockUpdatePacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(StructureBlockUpdatePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (Minecraft.getInstance().level == null) {
                return;
            }
            Entity entity = Minecraft.getInstance().level.getEntity(packet.entityId);
            if (!(entity instanceof PhysicsStructureEntity structureEntity)) {
                return;
            }
            BlockPos localPos = BlockPos.of(packet.localPos);
            if (!packet.hasState) {
                structureEntity.applyBlockUpdate(localPos, null, null);
                return;
            }
            BlockState state = NbtUtils.readBlockState(
                    Minecraft.getInstance().level.holderLookup(Registries.BLOCK),
                    packet.stateTag
            );
            structureEntity.applyBlockUpdate(localPos, state, packet.blockEntityTag);
        });
    }
}
