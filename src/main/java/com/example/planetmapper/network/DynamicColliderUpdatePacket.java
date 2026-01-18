package com.example.planetmapper.network;

import com.example.planetmapper.PlanetMapper;
import com.example.planetmapper.physics.PhysicsColliderManager;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

public record DynamicColliderUpdatePacket(ResourceLocation dimensionId, long bodyId, List<AABB> boxes) implements CustomPacketPayload {

    public static final Type<DynamicColliderUpdatePacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(PlanetMapper.MODID, "update_collider"));

    public static final StreamCodec<ByteBuf, DynamicColliderUpdatePacket> STREAM_CODEC = StreamCodec.of(
            (ByteBuf buf, DynamicColliderUpdatePacket val) -> {
                ByteBufCodecs.STRING_UTF8.encode(buf, val.dimensionId.toString());
                ByteBufCodecs.VAR_LONG.encode(buf, val.bodyId);
                ByteBufCodecs.VAR_INT.encode(buf, val.boxes.size());
                for (AABB box : val.boxes) {
                    writeAABB(buf, box);
                }
            },
            (ByteBuf buf) -> {
                ResourceLocation dim = ResourceLocation.parse(ByteBufCodecs.STRING_UTF8.decode(buf));
                long id = ByteBufCodecs.VAR_LONG.decode(buf);
                int size = ByteBufCodecs.VAR_INT.decode(buf);
                List<AABB> boxes = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    boxes.add(readAABB(buf));
                }
                return new DynamicColliderUpdatePacket(dim, id, boxes);
            });

    private static void writeAABB(ByteBuf buf, AABB box) {
        buf.writeDouble(box.minX);
        buf.writeDouble(box.minY);
        buf.writeDouble(box.minZ);
        buf.writeDouble(box.maxX);
        buf.writeDouble(box.maxY);
        buf.writeDouble(box.maxZ);
    }

    private static AABB readAABB(ByteBuf buf) {
        return new AABB(
                buf.readDouble(), buf.readDouble(), buf.readDouble(),
                buf.readDouble(), buf.readDouble(), buf.readDouble()
        );
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(DynamicColliderUpdatePacket payload, IPayloadContext context) {
        context.enqueueWork(() -> PhysicsColliderManager.updateDynamicBody(payload.bodyId, payload.boxes));
    }
}
