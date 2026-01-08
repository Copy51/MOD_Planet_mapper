package com.example.planetmapper.network;

import com.example.planetmapper.PlanetMapper;
import com.example.planetmapper.space.CelestialBody;
import com.example.planetmapper.space.CelestialBodyRegistry;
import com.example.planetmapper.client.renderer.PlanetTextureGenerator;
import io.netty.buffer.ByteBuf;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record CelestialBodySyncPacket(boolean fullSync, CompoundTag data) implements CustomPacketPayload {

    public static final Type<CelestialBodySyncPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(PlanetMapper.MODID, "sync_body"));

    public static final StreamCodec<ByteBuf, CelestialBodySyncPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, CelestialBodySyncPacket::fullSync,
            ByteBufCodecs.COMPOUND_TAG, CelestialBodySyncPacket::data,
            CelestialBodySyncPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(CelestialBodySyncPacket payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (payload.fullSync()) {
                PlanetTextureGenerator.clearCache();
                CelestialBodyRegistry.getClientInstance().load(payload.data());
            } else {
                CelestialBody body = CelestialBody.load(payload.data());
                CelestialBodyRegistry registry = CelestialBodyRegistry.getClientInstance();
                CelestialBody existing = registry.getBody(body.getId());
                if (existing != null) {
                    copyInto(existing, body);
                } else {
                    registry.addBody(body);
                }
            }
        });
    }

    private static void copyInto(CelestialBody target, CelestialBody src) {
        target.setName(src.getName());
        target.setPosition(src.getPosition());
        target.setVelocity(src.getVelocity());
        target.setRadius(src.getRadius());
        target.setMass(src.getMass());
        target.setType(src.getType());
        target.setRotationSpeed(src.getRotationSpeed());
        target.setAxialTilt(src.getAxialTilt());
        target.setTemperatureK(src.getTemperatureK());
        target.setColor(src.getColorR(), src.getColorG(), src.getColorB());
        target.setTexture(src.getTexture());
    }
}
