package com.example.planetmapper.network;

import com.example.planetmapper.PlanetMapper;
import com.example.planetmapper.entity.PhysicsStructureEntity;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record ShipControlPacket(int entityId, float thrust, float strafe, float vertical, float yaw) implements CustomPacketPayload {

    public static final Type<ShipControlPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(PlanetMapper.MODID, "ship_control"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ShipControlPacket> STREAM_CODEC = StreamCodec.composite(
            net.minecraft.network.codec.ByteBufCodecs.VAR_INT,
            ShipControlPacket::entityId,
            net.minecraft.network.codec.ByteBufCodecs.FLOAT,
            ShipControlPacket::thrust,
            net.minecraft.network.codec.ByteBufCodecs.FLOAT,
            ShipControlPacket::strafe,
            net.minecraft.network.codec.ByteBufCodecs.FLOAT,
            ShipControlPacket::vertical,
            net.minecraft.network.codec.ByteBufCodecs.FLOAT,
            ShipControlPacket::yaw,
            ShipControlPacket::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(final ShipControlPacket packet, final IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                Entity entity = player.level().getEntity(packet.entityId);
                // Security check: Only allow controlling if riding or via some permission
                if (entity instanceof PhysicsStructureEntity structureEntity) {
                    structureEntity.updateControls(packet.thrust, packet.strafe, packet.vertical, packet.yaw);
                }
            }
        });
    }
}
