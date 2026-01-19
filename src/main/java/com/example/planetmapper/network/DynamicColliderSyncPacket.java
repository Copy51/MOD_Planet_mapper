package com.example.planetmapper.network;

import com.example.planetmapper.PlanetMapper;
import com.example.planetmapper.physics.PhysicsColliderManager;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

public record DynamicColliderSyncPacket(ResourceLocation dimensionId, long bodyId, List<AABB> boxes,
                                        double centerX, double centerY, double centerZ,
                                        float avx, float avy, float avz) implements CustomPacketPayload {

    public static final Type<DynamicColliderSyncPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(PlanetMapper.MODID, "sync_collider"));

    public static final StreamCodec<ByteBuf, DynamicColliderSyncPacket> STREAM_CODEC = StreamCodec.of(
            (ByteBuf buf, DynamicColliderSyncPacket val) -> {
                ByteBufCodecs.STRING_UTF8.encode(buf, val.dimensionId.toString());
                ByteBufCodecs.VAR_LONG.encode(buf, val.bodyId);
                ByteBufCodecs.DOUBLE.encode(buf, val.centerX);
                ByteBufCodecs.DOUBLE.encode(buf, val.centerY);
                ByteBufCodecs.DOUBLE.encode(buf, val.centerZ);
                ByteBufCodecs.FLOAT.encode(buf, val.avx);
                ByteBufCodecs.FLOAT.encode(buf, val.avy);
                ByteBufCodecs.FLOAT.encode(buf, val.avz);
                ByteBufCodecs.VAR_INT.encode(buf, val.boxes.size());
                for (AABB box : val.boxes) {
                    writeAABB(buf, box);
                }
            },
            (ByteBuf buf) -> {
                ResourceLocation dim = ResourceLocation.parse(ByteBufCodecs.STRING_UTF8.decode(buf));
                long id = ByteBufCodecs.VAR_LONG.decode(buf);
                double cx = buf.readDouble();
                double cy = buf.readDouble();
                double cz = buf.readDouble();
                float avx = buf.readFloat();
                float avy = buf.readFloat();
                float avz = buf.readFloat();
                int size = ByteBufCodecs.VAR_INT.decode(buf);
                List<AABB> boxes = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    boxes.add(readAABB(buf));
                }
                return new DynamicColliderSyncPacket(dim, id, boxes, cx, cy, cz, avx, avy, avz);
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

    public static void handle(DynamicColliderSyncPacket payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            ResourceKey<net.minecraft.world.level.Level> dimKey = ResourceKey.create(Registries.DIMENSION, payload.dimensionId);
            org.joml.Vector3f center = new org.joml.Vector3f((float) payload.centerX, (float) payload.centerY, (float) payload.centerZ);
            PhysicsColliderManager.registerDynamicBody(dimKey, payload.bodyId, payload.boxes, center);
            PhysicsColliderManager.updateBodyState(payload.bodyId, (float)payload.centerX, (float)payload.centerY, (float)payload.centerZ, 
                    new org.joml.Quaternionf(), 0, 0, 0, payload.avx, payload.avy, payload.avz);
        });
    }
}
