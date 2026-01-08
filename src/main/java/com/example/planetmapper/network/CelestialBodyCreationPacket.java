package com.example.planetmapper.network;

import com.example.planetmapper.PlanetMapper;
import com.example.planetmapper.space.CelestialBody;
import com.example.planetmapper.space.CelestialBody.BodyType;
import com.example.planetmapper.space.CelestialBodyRegistry;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.StringUtil;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

public record CelestialBodyCreationPacket(
        String name,
        double x, double y, double z,
        float radius,
        double mass,
        double vx, double vy, double vz,
        String typeName,
        float r, float g, float b,
        float rotationSpeed,
        float axialTilt,
        String texturePath,
        double temperatureK,
        String targetDimension) implements CustomPacketPayload {

    public static final Type<CelestialBodyCreationPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(PlanetMapper.MODID, "create_body"));

    public static final StreamCodec<ByteBuf, CelestialBodyCreationPacket> STREAM_CODEC = StreamCodec.of(
            (ByteBuf buf, CelestialBodyCreationPacket val) -> {
                ByteBufCodecs.STRING_UTF8.encode(buf, val.name);
                ByteBufCodecs.DOUBLE.encode(buf, val.x);
                ByteBufCodecs.DOUBLE.encode(buf, val.y);
                ByteBufCodecs.DOUBLE.encode(buf, val.z);
                ByteBufCodecs.FLOAT.encode(buf, val.radius);
                ByteBufCodecs.DOUBLE.encode(buf, val.mass);
                ByteBufCodecs.DOUBLE.encode(buf, val.vx);
                ByteBufCodecs.DOUBLE.encode(buf, val.vy);
                ByteBufCodecs.DOUBLE.encode(buf, val.vz);
                ByteBufCodecs.STRING_UTF8.encode(buf, val.typeName);
                ByteBufCodecs.FLOAT.encode(buf, val.r);
                ByteBufCodecs.FLOAT.encode(buf, val.g);
                ByteBufCodecs.FLOAT.encode(buf, val.b);
                ByteBufCodecs.FLOAT.encode(buf, val.rotationSpeed);
                ByteBufCodecs.FLOAT.encode(buf, val.axialTilt);
                ByteBufCodecs.STRING_UTF8.encode(buf, val.texturePath);
                ByteBufCodecs.DOUBLE.encode(buf, val.temperatureK);
                ByteBufCodecs.STRING_UTF8.encode(buf, val.targetDimension);
            },
            (ByteBuf buf) -> new CelestialBodyCreationPacket(
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.DOUBLE.decode(buf),
                    ByteBufCodecs.DOUBLE.decode(buf),
                    ByteBufCodecs.DOUBLE.decode(buf),
                    ByteBufCodecs.FLOAT.decode(buf),
                    ByteBufCodecs.DOUBLE.decode(buf),
                    ByteBufCodecs.DOUBLE.decode(buf),
                    ByteBufCodecs.DOUBLE.decode(buf),
                    ByteBufCodecs.DOUBLE.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.FLOAT.decode(buf),
                    ByteBufCodecs.FLOAT.decode(buf),
                    ByteBufCodecs.FLOAT.decode(buf),
                    ByteBufCodecs.FLOAT.decode(buf),
                    ByteBufCodecs.FLOAT.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.DOUBLE.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(CelestialBodyCreationPacket payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            CelestialBodyRegistry registry = CelestialBodyRegistry.getServerInstance();

            BodyType type = BodyType.valueOf(payload.typeName);
            Vec3 pos = new Vec3(payload.x, payload.y, payload.z);

            CelestialBody body = new CelestialBody(UUID.randomUUID(), payload.name, pos, payload.radius, payload.mass,
                    type);
            body.setVelocity(new Vec3(payload.vx, payload.vy, payload.vz));
            body.setRotationSpeed(payload.rotationSpeed);
            body.setAxialTilt(payload.axialTilt);
            body.setTemperatureK(payload.temperatureK);
            if (!StringUtil.isNullOrEmpty(payload.targetDimension)) {
                body.setTargetDimension(ResourceLocation.tryParse(payload.targetDimension));
            }

            if (type == BodyType.STAR && payload.temperatureK > 0) {
                float[] color = CelestialBody.temperatureToRGB(payload.temperatureK);
                body.setColor(color[0], color[1], color[2]);
            } else {
                body.setColor(payload.r, payload.g, payload.b);
            }

            if (!StringUtil.isNullOrEmpty(payload.texturePath)) {
                ResourceLocation tex = ResourceLocation.tryParse(payload.texturePath);
                if (tex != null) {
                    body.setTexture(tex);
                }
            }

            registry.addBody(body);

            net.neoforged.neoforge.network.PacketDistributor.sendToAllPlayers(
                    new CelestialBodySyncPacket(false, body.save()));
        });
    }
}
