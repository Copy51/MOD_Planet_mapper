package com.example.planetmapper.client.renderer;

import com.example.planetmapper.space.CelestialBody;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class PlanetTextureGenerator {

    /**
     * Procedurally generated planet material cache per body (client-side).
     */
    private static final Map<String, PlanetMaterial> PLANET_CACHE = new ConcurrentHashMap<>();
    private static final int MAX_CACHE = 64;
    private static final int PLANET_TEXTURE_WIDTH = 1024; // Increased resolution
    private static final int PLANET_TEXTURE_HEIGHT = 1024;

    public static ResourceLocation generateEarthTexture(long seed) {
        int width = 256;
        int height = 256;
        NativeImage image = new NativeImage(width, height, false);

        RandomSource random = RandomSource.create(seed);
        // Correct constructor: (RandomSource, List<Integer> octaves)
        List<Integer> octaves = IntStream.range(0, 4).boxed().collect(Collectors.toList());
        PerlinNoise noise = PerlinNoise.create(random, octaves);

        float[] dir = new float[3];
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                double u = ((double) x + 0.5) / width;
                double v = ((double) y + 0.5) / height;
                double lon = u * 2.0 * Math.PI;
                double lat = v * Math.PI - (Math.PI / 2.0);
                dir[0] = (float) (Math.cos(lat) * Math.cos(lon));
                dir[1] = (float) Math.sin(lat);
                dir[2] = (float) (Math.cos(lat) * Math.sin(lon));

                double nx = dir[0] * 2.0;
                double ny = dir[1] * 2.0;
                double nz = dir[2] * 2.0;

                double value = noise.getValue(nx, ny, nz);

                int color = getColorForHeight(value);
                image.setPixelRGBA(x, y, color);
            }
        }

        DynamicTexture dynamicTexture = new DynamicTexture(image);
        return Minecraft.getInstance().getTextureManager().register("planet_earth_" + seed, dynamicTexture);
    }

    public static ResourceLocation generateSunTexture() {
        int width = 128; // Increased resolution for better detail
        int height = 128;
        NativeImage image = new NativeImage(width, height, false);
        
        RandomSource random = RandomSource.create();
        List<Integer> octaves = IntStream.range(0, 3).boxed().collect(Collectors.toList());
        PerlinNoise noise = PerlinNoise.create(random, octaves);

        float[] dir = new float[3];
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                double u = ((double) x + 0.5) / width;
                double v = ((double) y + 0.5) / height;
                double lon = u * 2.0 * Math.PI;
                double lat = v * Math.PI - (Math.PI / 2.0);
                dir[0] = (float) (Math.cos(lat) * Math.cos(lon));
                dir[1] = (float) Math.sin(lat);
                dir[2] = (float) (Math.cos(lat) * Math.sin(lon));

                // Sample 3D noise for perfect seamlessness
                double noiseVal = noise.getValue(dir[0] * 5.0, dir[1] * 5.0, dir[2] * 5.0);
                
                // Hot plasma look: higher contrast
                float brightness = 0.65f + (float) ((noiseVal + 1.0) * 0.22);
                
                // Add some localized "hot spots"
                if (brightness > 0.92f) brightness *= 1.15f;
                
                brightness = Math.max(0.6f, Math.min(1.0f, brightness));
                
                int b = (int) (brightness * 255.0f);
                int color = (0xFF << 24) | (b << 16) | (b << 8) | b;
                image.setPixelRGBA(x, y, color);
            }
        }
        DynamicTexture texture = new DynamicTexture(image);
        return Minecraft.getInstance().getTextureManager().register("planet_sun", texture);
    }

    public static ResourceLocation generateAtmosphereTexture() {
        int size = 256;
        NativeImage image = new NativeImage(size, size, false);
        float center = size / 2.0f;
        float maxRadius = size / 2.0f;

        // Radius of the planet in the texture (roughly 0.75 of the frame)
        float planetRadius = maxRadius * 0.85f;

        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                float dx = x - center;
                float dy = y - center;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);

                if (dist < planetRadius) {
                    image.setPixelRGBA(x, y, 0); // Transparent inside the planet
                    continue;
                }

                // Distance into the atmosphere (0.0 at horizon, 1.0 at outer edge)
                float h = (dist - planetRadius) / (maxRadius - planetRadius);
                h = clamp(h, 0.0f, 1.0f);

                // Scattering profile - very steep near horizon, then soft fade
                // Match the "Thin Blue Line" look
                float density = (float) Math.exp(-h * 12.0f); // Exponential falloff
                
                // Color mapping from photo:
                // Horizon: White/Bright Cyan
                // Mid: Neon Blue
                // Top: Deep Blue
                
                float r, g, b;
                if (h < 0.1f) {
                    // Blinding white/cyan core
                    float t = h / 0.1f;
                    r = lerp(1.0f, 0.2f, t);
                    g = lerp(1.0f, 0.8f, t);
                    b = 1.0f;
                } else if (h < 0.4f) {
                    // Transitions to neon blue
                    float t = (h - 0.1f) / 0.3f;
                    r = lerp(0.2f, 0.05f, t);
                    g = lerp(0.8f, 0.45f, t);
                    b = 1.0f;
                } else {
                    // Fades to deep blue
                    float t = (h - 0.4f) / 0.6f;
                    r = 0.05f;
                    g = lerp(0.45f, 0.1f, t);
                    b = lerp(1.0f, 0.6f, t);
                }

                // Smoothly fade transparency
                float alpha = density * 0.95f;
                if (h > 0.8f) alpha *= (1.0f - h) / 0.2f; // Soft outer edge

                int ir = (int) (r * 255);
                int ig = (int) (g * 255);
                int ib = (int) (b * 255);
                int ia = (int) (alpha * 255);

                // ABGR format
                image.setPixelRGBA(x, y, (ia << 24) | (ib << 16) | (ig << 8) | ir);
            }
        }
        DynamicTexture texture = new DynamicTexture(image);
        return Minecraft.getInstance().getTextureManager().register("planet_atmosphere_shader", texture);
    }

    /**
     * Soft radial gradient used for star glow (billboard). White with alpha falloff.
     */
    public static ResourceLocation generateSunGlowTexture() {
        int size = 256;
        NativeImage image = new NativeImage(size, size, false);

        float center = (size - 1) / 2.0f;
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                float dx = x - center;
                float dy = y - center;
                float dist = (float) Math.sqrt(dx * dx + dy * dy) / center;

                float a = 1.0f - dist;
                if (a < 0.0f) {
                    a = 0.0f;
                }

                // Smooth falloff
                a = a * a * a;

                int alpha = (int) (a * 255.0f);
                int color = (alpha << 24) | 0x00FFFFFF; // ABGR: A + white
                image.setPixelRGBA(x, y, color);
            }
        }

        DynamicTexture texture = new DynamicTexture(image);
        return Minecraft.getInstance().getTextureManager().register("planet_sun_glow", texture);
    }

    public static ResourceLocation generateMoonTexture() {
        int width = 64;
        int height = 64;
        NativeImage image = new NativeImage(width, height, false);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                image.setPixelRGBA(x, y, 0xFFAAAAAA); // Gray (ABGR)
            }
        }
        DynamicTexture texture = new DynamicTexture(image);
        return Minecraft.getInstance().getTextureManager().register("planet_moon", texture);
    }

    /**
     * Get or build a procedural planet material for this body and dimension seed.
     */
    public static PlanetMaterial getOrCreatePlanetMaterial(CelestialBody body, long dimensionSeed, String dimensionKey) {
        String cacheKey = buildCacheKey(body, dimensionSeed, dimensionKey);
        PlanetMaterial cached = PLANET_CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        PlanetMaterial material = generatePlanetMaterial(body, dimensionSeed, dimensionKey);
        PLANET_CACHE.put(cacheKey, material);
        trimCache();
        return material;
    }

    public static void clearCache() {
        PLANET_CACHE.clear();
    }

    private static String buildCacheKey(CelestialBody body, long dimensionSeed, String dimensionKey) {
        return body.getId().toString() + "|" + dimensionKey + "|" + dimensionSeed;
    }

    private static void trimCache() {
        int over = PLANET_CACHE.size() - MAX_CACHE;
        if (over <= 0) {
            return;
        }
        var it = PLANET_CACHE.keySet().iterator();
        while (over > 0 && it.hasNext()) {
            it.next();
            it.remove();
            over--;
        }
    }

    private static PlanetMaterial generatePlanetMaterial(CelestialBody body, long dimensionSeed, String dimensionKey) {
        int width = PLANET_TEXTURE_WIDTH;
        int height = PLANET_TEXTURE_HEIGHT;

        NativeImage albedo = new NativeImage(width, height, false);
        NativeImage normal = new NativeImage(width, height, false);

        float[][] heights = new float[width][height];
        float[][] temps = new float[width][height];
        float[][] humids = new float[width][height];

        long seed = mixSeed(dimensionSeed, dimensionKey.hashCode(), body.getId().getLeastSignificantBits());
        RandomSource random = RandomSource.create(seed);

        PerlinNoise continentNoise = PerlinNoise.create(random, IntStream.range(0, 8).boxed().collect(Collectors.toList())); 
        PerlinNoise ridgeNoise = PerlinNoise.create(random, IntStream.range(1, 9).boxed().collect(Collectors.toList()));
        PerlinNoise detailNoise = PerlinNoise.create(random, IntStream.range(4, 12).boxed().collect(Collectors.toList()));
        PerlinNoise climateTempNoise = PerlinNoise.create(random, IntStream.range(0, 4).boxed().collect(Collectors.toList()));
        PerlinNoise climateHumidNoise = PerlinNoise.create(random, IntStream.range(1, 5).boxed().collect(Collectors.toList()));
        PerlinNoise warpNoise = PerlinNoise.create(random, IntStream.range(0, 3).boxed().collect(Collectors.toList()));
        
        float[] dir = new float[3];

        float minH = Float.MAX_VALUE;
        float maxH = -Float.MAX_VALUE;
        double planetRelief = computeReliefScale(body.getRadius());

        // Pass 1: generate height + climate fields
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                double u = ((double) x + 0.5) / (double) width;
                double v = ((double) y + 0.5) / (double) height;
                double lon = u * 2 * Math.PI;
                double lat = v * Math.PI - (Math.PI / 2.0);
                
                double nx0 = Math.cos(lat) * Math.cos(lon);
                double ny0 = Math.sin(lat);
                double nz0 = Math.cos(lat) * Math.sin(lon);

                // --- 2-Step Recursive Domain Warping ---
                // This simulates the complex, non-bloopy movement of biomes/land
                double warpStrength = 0.85;
                double qx = warpNoise.getValue(nx0 * 1.1, ny0 * 1.1, nz0 * 1.1);
                double qy = warpNoise.getValue(nx0 * 1.1 + 10.5, ny0 * 1.1 + 22.1, nz0 * 1.1 - 5.5);
                double qz = warpNoise.getValue(nx0 * 1.1 - 15.2, ny0 * 1.1 + 3.3, nz0 * 1.1 + 44.8);
                
                double wx = nx0 + qx * warpStrength;
                double wy = ny0 + qy * warpStrength;
                double wz = nz0 + qz * warpStrength;
                
                double rx = warpNoise.getValue(wx * 2.2 + 5.1, wy * 2.2 + 1.2, wz * 2.2 + 7.7);
                double ry = warpNoise.getValue(wx * 2.2 - 2.2, wy * 2.2 + 9.1, wz * 2.2 - 3.3);
                double rz = warpNoise.getValue(wx * 2.2 + 8.8, wy * 2.2 - 4.4, wz * 2.2 + 1.1);
                
                double finalX = nx0 + rx * warpStrength;
                double finalY = ny0 + ry * warpStrength;
                double finalZ = nz0 + rz * warpStrength;

                // --- Noise Composition ---
                // Continents (Lower frequency, high octaves)
                double cFreq = 1.15;
                double continents = fbm(continentNoise, finalX * cFreq, finalY * cFreq, finalZ * cFreq, 8, 2.15, 0.55);
                
                // Mountains (Ridged noise for sharp peaks)
                double rFreq = 3.2;
                double ridges = ridged(ridgeNoise, finalX * rFreq, finalY * rFreq, finalZ * rFreq, 6, 2.2, 0.6);
                
                // Fine Detail (High frequency grit)
                double dFreq = 16.0;
                double detail = fbm(detailNoise, nx0 * dFreq, ny0 * dFreq, nz0 * dFreq, 6, 2.5, 0.48);
                
                // Micro-Grain (Pixel-level block simulation)
                double gFreq = 256.0; 
                double grain = (warpNoise.getValue(nx0 * gFreq, ny0 * gFreq, nz0 * gFreq) + 1.0) * 0.5;
                detail += (grain - 0.5) * 0.15; // Perturb surface with block-scale noise

                // Mix landmass (continents + ridges for mountains)
                double heightVal = continents * 0.75 + (ridges * ridges * 0.4) + (detail * 0.1);
                heightVal = heightVal - 0.22; // Bias to create oceans

                // Rivers (Tight ridges thresholded)
                double riverVal = ridged(ridgeNoise, finalX * 7.0, finalY * 7.0, finalZ * 7.0, 4, 2.0, 0.5);
                boolean isRiver = riverVal > 0.88 && heightVal > -0.15; // Simple river mask

                float h = (float) clamp(heightVal, -1.2, 2.0);
                if (isRiver) h = -0.15f; // Carve river into terrain
                
                heights[x][y] = h;
                minH = Math.min(minH, h);
                maxH = Math.max(maxH, h);

                // Climate (Independent warping for realistic biome drift)
                double tempBase = 1.0 - Math.pow(Math.abs(ny0), 1.5); // Smoother latitudinal falloff
                double tempNoise = climateTempNoise.getValue(finalX * 0.5, finalY * 0.5, finalZ * 0.5) * 0.4;
                double temp = clamp(tempBase * 0.82 + tempNoise + 0.15, 0.0, 1.0);

                double humidNoise = climateHumidNoise.getValue(finalX * 0.65, finalY * 0.65, finalZ * 0.65) * 0.5;
                double humidity = clamp(0.5 + humidNoise + detail * 0.05, 0.0, 1.0);

                temps[x][y] = (float) temp;
                humids[x][y] = (float) humidity;
            }
        }

        // Pass 2: color + normal
        long accumR = 0;
        long accumG = 0;
        long accumB = 0;

        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                float h = heights[x][y];

                float slope = maxAbsDelta(heights, x, y);
                float temp = temps[x][y];
                float humid = humids[x][y];

                int color = colorFor(h, temp, humid, slope);
                albedo.setPixelRGBA(x, y, color);

                accumR += (color) & 0xFF;
                accumG += (color >> 8) & 0xFF;
                accumB += (color >> 16) & 0xFF;

                // Simple normal from height derivatives
                float dhx = sampleHeight(heights, x + 1, y) - sampleHeight(heights, x - 1, y);
                float dhy = sampleHeight(heights, x, y + 1) - sampleHeight(heights, x, y - 1);
                double nx = -dhx * planetRelief;
                double ny = -dhy * planetRelief;
                double nz = 1.0;
                double inv = 1.0 / Math.sqrt(nx * nx + ny * ny + nz * nz);
                nx *= inv;
                ny *= inv;
                nz *= inv;

                int nr = (int) (clamp(nx * 0.5 + 0.5, 0.0, 1.0) * 255.0);
                int ng = (int) (clamp(ny * 0.5 + 0.5, 0.0, 1.0) * 255.0);
                int nb = (int) (clamp(nz * 0.5 + 0.5, 0.0, 1.0) * 255.0);
                int packedNormal = (0xFF << 24) | (nb << 16) | (ng << 8) | nr; // ABGR
                normal.setPixelRGBA(x, y, packedNormal);
            }
        }

        DynamicTexture albedoTex = new DynamicTexture(albedo);
        DynamicTexture normalTex = new DynamicTexture(normal);

        String baseKey = "planet_" + body.getId().toString().replace("-", "");
        ResourceLocation albedoLoc = Minecraft.getInstance().getTextureManager().register(baseKey + "_albedo", albedoTex);
        ResourceLocation normalLoc = Minecraft.getInstance().getTextureManager().register(baseKey + "_normal", normalTex);

        int pixels = width * height;
        float avgR = (float) (accumR / (double) pixels) / 255.0f;
        float avgG = (float) (accumG / (double) pixels) / 255.0f;
        float avgB = (float) (accumB / (double) pixels) / 255.0f;

        return new PlanetMaterial(albedoLoc, normalLoc, heights, minH, maxH, avgR, avgG, avgB, (float) planetRelief);
    }

    private static double computeReliefScale(float radius) {
        // Smaller bodies get comparatively higher relief to stay visible
        float scaled = Math.max(8.0f, radius);
        double relative = 0.04 + (40.0 / (40.0 + scaled)) * 0.12;
        return clamp(relative, 0.04, 0.18);
    }

    private static double fbm(PerlinNoise noise, double x, double y, double z, int octaves, double lacunarity, double gain) {
        double amp = 1.0;
        double freq = 1.0;
        double sum = 0.0;
        double norm = 0.0;
        for (int i = 0; i < octaves; i++) {
            sum += noise.getValue(x * freq, y * freq, z * freq) * amp;
            norm += amp;
            amp *= gain;
            freq *= lacunarity;
        }
        return sum / Math.max(1e-6, norm);
    }

    private static double ridged(PerlinNoise noise, double x, double y, double z, int octaves, double lacunarity, double gain) {
        double amp = 1.0;
        double freq = 1.0;
        double sum = 0.0;
        double norm = 0.0;
        for (int i = 0; i < octaves; i++) {
            double n = 1.0 - Math.abs(noise.getValue(x * freq, y * freq, z * freq));
            n *= n;
            sum += n * amp;
            norm += amp;
            amp *= gain;
            freq *= lacunarity;
        }
        return sum / Math.max(1e-6, norm);
    }

    private static int colorFor(float h, float temp, float humid, float slope) {
        // Minecraft Biome Palettes (ABGR format)
        final int OCEAN = 0xFFE4763F;      // #3F76E4
        final int DEEP_OCEAN = 0xFFC35B40; // #405BC3
        final int PLAINS = 0xFF59BD91;     // #91BD59
        final int FOREST = 0xFF2FAB77;     // #77AB2F
        final int DARK_FOREST = 0xFF144A30;// #304A14
        final int JUNGLE = 0xFF097B53;     // #537B09
        final int SAVANNA = 0xFF4EB8BB;    // #BBB84E
        final int DESERT = 0xFF70C9D9;     // #D9C970
        final int BADLANDS = 0xFF1545D9;   // #D94515
        final int SWAMP = 0xFF50632D;      // #2D6350
        final int SNOW = 0xFFF0F2F2;
        final int STONE = 0xFF888888;
        final int FROZEN_OCEAN = 0xFFC09971;

        float seaLevel = -0.1f;
        float snowLevel = 0.65f;
        
        // --- Sea/Water logic ---
        if (h < seaLevel) {
            if (temp < 0.25f) return FROZEN_OCEAN;
            float depth = (seaLevel - h) / 1.0f;
            return depth > 0.35f ? DEEP_OCEAN : OCEAN;
        }

        // --- Land logic ---
        // 1. Permanent Ice/Snow caps
        if (h > snowLevel + (1.0f - temp) * 0.25f) {
            return SNOW;
        }

        // 2. High Cliffs (Stone)
        if (slope > 0.28f && h > 0.35f) {
            return STONE;
        }

        // 3. Triage by Climate (Minecraft-accurate matrix)
        if (temp > 0.82f) {
            if (humid < 0.22f) return DESERT;
            if (humid < 0.40f) return SAVANNA;
            if (humid < 0.55f) return BADLANDS;
            return JUNGLE;
        }
        
        if (temp < 0.35f) {
            if (humid > 0.65f) return SWAMP;
            return SNOW;
        }

        if (humid > 0.75f) return JUNGLE;
        if (humid > 0.55f) return DARK_FOREST;
        if (humid > 0.40f) return FOREST;
        
        return PLAINS;
    }

    private static float maxAbsDelta(float[][] values, int x, int y) {
        float center = sampleHeight(values, x, y);
        float d1 = Math.abs(center - sampleHeight(values, x + 1, y));
        float d2 = Math.abs(center - sampleHeight(values, x - 1, y));
        float d3 = Math.abs(center - sampleHeight(values, x, y + 1));
        float d4 = Math.abs(center - sampleHeight(values, x, y - 1));
        return Math.max(Math.max(d1, d2), Math.max(d3, d4));
    }

    private static float sampleHeight(float[][] values, int x, int y) {
        int w = values.length;
        int h = values[0].length;
        int xi = Math.max(0, Math.min(w - 1, x));
        int yi = Math.max(0, Math.min(h - 1, y));
        return values[xi][yi];
    }


    private static long mixSeed(long a, long b, long c) {
        long result = a;
        result ^= (b + 0x9E3779B97F4A7C15L + (result << 6) + (result >> 2));
        result ^= (c + 0x9E3779B97F4A7C15L + (result << 6) + (result >> 2));
        return result;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /**
     * Planet material bundle: albedo + normal + height map with displacement scale.
     */
    public record PlanetMaterial(ResourceLocation albedo, ResourceLocation normal, float[][] heights,
                                 float minHeight, float maxHeight,
                                 float avgR, float avgG, float avgB,
                                 float displacementScale) {

        public float sampleHeight(float u, float v) {
            float uu = clamp(u, 0.0f, 1.0f);
            float vv = clamp(v, 0.0f, 1.0f);
            float x = uu * (heights.length - 1);
            float y = vv * (heights[0].length - 1);
            int x0 = (int) Math.floor(x);
            int x1 = Math.min(heights.length - 1, x0 + 1);
            int y0 = (int) Math.floor(y);
            int y1 = Math.min(heights[0].length - 1, y0 + 1);
            float tx = x - x0;
            float ty = y - y0;
            float h00 = heights[x0][y0];
            float h10 = heights[x1][y0];
            float h01 = heights[x0][y1];
            float h11 = heights[x1][y1];
            float hx0 = lerp(h00, h10, tx);
            float hx1 = lerp(h01, h11, tx);
            return lerp(hx0, hx1, ty);
        }
    }

    private static int getColorForHeight(double value) {
        // ABGR format (0xAABBGGRR)
        if (value < -0.2)
            return 0xFF8B4513; // Deep Water
        if (value < -0.1)
            return 0xFF880000; // Deep Blue
        if (value < 0.1)
            return 0xFFFF0000; // Blue
        if (value < 0.2)
            return 0xFF00FFFF; // Sand
        if (value < 0.5)
            return 0xFF008800; // Green
        if (value < 0.8)
            return 0xFF004400; // Dark Green
        return 0xFFFFFFFF; // Snow
    }


}
