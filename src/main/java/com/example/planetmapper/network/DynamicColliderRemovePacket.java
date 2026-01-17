package com.example.planetmapper.network;

import com.example.planetmapper.PlanetMapper;
import com.example.planetmapper.physics.PhysicsColliderManager;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record DynamicColliderRemovePacket(long bodyId) implements CustomPacketPayload {

    public static final Type<DynamicColliderRemovePacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(PlanetMapper.MODID, "remove_collider"));

    public static final StreamCodec<ByteBuf, DynamicColliderRemovePacket> STREAM_CODEC = StreamCodec.of(
            (ByteBuf buf, DynamicColliderRemovePacket val) -> {
                ByteBufCodecs.VAR_LONG.encode(buf, val.bodyId);
            },
            (ByteBuf buf) -> {
                long id = ByteBufCodecs.VAR_LONG.decode(buf);
                return new DynamicColliderRemovePacket(id);
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(DynamicColliderRemovePacket payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            PhysicsColliderManager.unregisterDynamicBody(payload.bodyId);
        });
    }
}
