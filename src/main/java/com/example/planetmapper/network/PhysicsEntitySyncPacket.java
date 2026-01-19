package com.example.planetmapper.network;

import com.example.planetmapper.PlanetMapper;
import com.example.planetmapper.entity.PhysicsBlockEntity;
import com.example.planetmapper.physics.PhysicsColliderManager;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.joml.Quaternionf;

public record PhysicsEntitySyncPacket(int entityId, long bodyId, double x, double y, double z,
                                      float qx, float qy, float qz, float qw,
                                      float vx, float vy, float vz,
                                      float avx, float avy, float avz) implements CustomPacketPayload {

    public static final Type<PhysicsEntitySyncPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(PlanetMapper.MODID, "sync_physics_entity"));

    public static final StreamCodec<ByteBuf, PhysicsEntitySyncPacket> STREAM_CODEC = StreamCodec.of(
            (ByteBuf buf, PhysicsEntitySyncPacket val) -> {
                ByteBufCodecs.VAR_INT.encode(buf, val.entityId);
                ByteBufCodecs.VAR_LONG.encode(buf, val.bodyId);
                ByteBufCodecs.DOUBLE.encode(buf, val.x);
                ByteBufCodecs.DOUBLE.encode(buf, val.y);
                ByteBufCodecs.DOUBLE.encode(buf, val.z);
                ByteBufCodecs.FLOAT.encode(buf, val.qx);
                ByteBufCodecs.FLOAT.encode(buf, val.qy);
                ByteBufCodecs.FLOAT.encode(buf, val.qz);
                ByteBufCodecs.FLOAT.encode(buf, val.qw);
                ByteBufCodecs.FLOAT.encode(buf, val.vx);
                ByteBufCodecs.FLOAT.encode(buf, val.vy);
                ByteBufCodecs.FLOAT.encode(buf, val.vz);
                ByteBufCodecs.FLOAT.encode(buf, val.avx);
                ByteBufCodecs.FLOAT.encode(buf, val.avy);
                ByteBufCodecs.FLOAT.encode(buf, val.avz);
            },
            (ByteBuf buf) -> new PhysicsEntitySyncPacket(
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_LONG.decode(buf),
                    ByteBufCodecs.DOUBLE.decode(buf),
                    ByteBufCodecs.DOUBLE.decode(buf),
                    ByteBufCodecs.DOUBLE.decode(buf),
                    ByteBufCodecs.FLOAT.decode(buf),
                    ByteBufCodecs.FLOAT.decode(buf),
                    ByteBufCodecs.FLOAT.decode(buf),
                    ByteBufCodecs.FLOAT.decode(buf),
                    ByteBufCodecs.FLOAT.decode(buf),
                    ByteBufCodecs.FLOAT.decode(buf),
                    ByteBufCodecs.FLOAT.decode(buf),
                    ByteBufCodecs.FLOAT.decode(buf),
                    ByteBufCodecs.FLOAT.decode(buf),
                    ByteBufCodecs.FLOAT.decode(buf)
            ));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PhysicsEntitySyncPacket payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            net.minecraft.world.level.Level level = Minecraft.getInstance().level;
            if (level != null) {
                Entity entity = level.getEntity(payload.entityId);
                if (entity instanceof PhysicsBlockEntity physicsEntity) {
                    Quaternionf rot = new Quaternionf(payload.qx, payload.qy, payload.qz, payload.qw);
                    physicsEntity.updateFromPacket(payload.x, payload.y, payload.z, rot);
                    
                    // Also update the static collision body on the client so the player doesn't phase/jitter
                    PhysicsColliderManager.updateBodyState(payload.bodyId, (float) payload.x, (float) payload.y, (float) payload.z,
                            rot, payload.vx, payload.vy, payload.vz, payload.avx, payload.avy, payload.avz);
                }
            }
        });
    }
}
