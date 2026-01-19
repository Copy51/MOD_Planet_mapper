package com.example.planetmapper.network;

import com.example.planetmapper.PlanetMapper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

public final class StructureSyncCodec {
    private StructureSyncCodec() {
    }

    public static byte[] encode(CompoundTag tag) {
        if (tag == null) {
            return new byte[0];
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            NbtIo.writeCompressed(tag, out);
            return out.toByteArray();
        } catch (IOException e) {
            PlanetMapper.LOGGER.error("Failed to encode structure sync NBT", e);
            return new byte[0];
        }
    }

    public static CompoundTag decode(byte[] data) {
        if (data == null || data.length == 0) {
            return new CompoundTag();
        }
        try (ByteArrayInputStream in = new ByteArrayInputStream(data)) {
            return NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
        } catch (IOException e) {
            PlanetMapper.LOGGER.error("Failed to decode structure sync NBT", e);
            return null;
        }
    }
}
