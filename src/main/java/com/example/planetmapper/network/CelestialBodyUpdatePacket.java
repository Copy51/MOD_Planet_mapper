package com.example.planetmapper.network;

import com.example.planetmapper.PlanetMapper;
import com.example.planetmapper.space.CelestialBody;
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

/**
 * Updates an existing celestial body (move/edit).
 */
public record CelestialBodyUpdatePacket(
        UUID id,
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
        double temperatureK) implements CustomPacketPayload {

    public static final Type<CelestialBodyUpdatePacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(PlanetMapper.MODID, "update_body"));

    public static final StreamCodec<ByteBuf, CelestialBodyUpdatePacket> STREAM_CODEC = StreamCodec.of(
            (ByteBuf buf, CelestialBodyUpdatePacket val) -> {
                buf.writeLong(val.id.getMostSignificantBits());
                buf.writeLong(val.id.getLeastSignificantBits());
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
            },
            (ByteBuf buf) -> new CelestialBodyUpdatePacket(
                    new UUID(buf.readLong(), buf.readLong()),
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
                    ByteBufCodecs.DOUBLE.decode(buf)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(CelestialBodyUpdatePacket payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            CelestialBodyRegistry registry = CelestialBodyRegistry.getServerInstance();
            CelestialBody existing = registry.getBody(payload.id);
            if (existing == null) {
                return;
            }

            CelestialBody.BodyType type = CelestialBody.BodyType.valueOf(payload.typeName);

            existing.setName(payload.name);
            existing.setPosition(new Vec3(payload.x, payload.y, payload.z));
            existing.setRadius(payload.radius);
            existing.setMass(payload.mass);
            existing.setVelocity(new Vec3(payload.vx, payload.vy, payload.vz));
            existing.setRotationSpeed(payload.rotationSpeed);
            existing.setAxialTilt(payload.axialTilt);
            existing.setType(type);
            existing.setTemperatureK(payload.temperatureK);

            if (type == CelestialBody.BodyType.STAR && payload.temperatureK > 0) {
                float[] color = CelestialBody.temperatureToRGB(payload.temperatureK);
                existing.setColor(color[0], color[1], color[2]);
            } else {
                existing.setColor(payload.r, payload.g, payload.b);
            }

            if (!StringUtil.isNullOrEmpty(payload.texturePath)) {
                ResourceLocation tex = ResourceLocation.tryParse(payload.texturePath);
                if (tex != null) {
                    existing.setTexture(tex);
                }
            }

            registry.markDirty();

            net.neoforged.neoforge.network.PacketDistributor.sendToAllPlayers(
                    new CelestialBodySyncPacket(false, existing.save()));
        });
    }
}
