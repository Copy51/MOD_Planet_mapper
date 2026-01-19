package com.example.planetmapper.client.renderer;

import com.example.planetmapper.PlanetMapper;
import com.example.planetmapper.space.CelestialBody;
import com.example.planetmapper.space.CelestialBodyRegistry;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * Renders all celestial bodies in the space dimension.
 * Uses CelestialBodyRegistry for modular, data-driven rendering.
 */
@EventBusSubscriber(modid = PlanetMapper.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public class SpaceSkyRenderer {

    // Cached textures for procedural generation
    private static ResourceLocation sunTexture = null;
    private static ResourceLocation sunGlowTexture = null;
    private static ResourceLocation moonTexture = null;
    private static boolean texturesInitialized = false;

    @SubscribeEvent
    public static void onRenderGui(net.neoforged.neoforge.client.event.RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.level.dimension() != PlanetMapper.SPACE_LEVEL || mc.player == null) {
            return;
        }

        CelestialBodyRegistry registry = CelestialBodyRegistry.getClientInstance();
        Vec3 pos = mc.player.position();

        // Find nearest body for altitude calculation
        CelestialBody nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (CelestialBody body : registry.getAllBodies()) {
            Vec3 bodyPos = body.getWorldPosition(registry);
            double dist = pos.distanceTo(bodyPos) - body.getRadius();
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = body;
            }
        }

        long dayTime = mc.level.getDayTime() % 24000;

        event.getGuiGraphics().drawString(mc.font, "SPACE", 10, 10, 0xFF00FF00);
        event.getGuiGraphics().drawString(mc.font, "Time: " + dayTime, 10, 20, 0xFFFFFFFF);
        event.getGuiGraphics().drawString(mc.font, "Bodies: " + registry.getBodyCount(), 10, 30, 0xFF00FFFF);

        if (nearest != null) {
            event.getGuiGraphics().drawString(mc.font, "Near: " + nearest.getName() + " (" + (int) nearestDist + "m)",
                    10, 40, 0xFFFFFF00);
        }

        event.getGuiGraphics().drawString(mc.font, "Pos: " + (int) pos.x + ", " + (int) pos.y + ", " + (int) pos.z, 10,
                50, 0xFFAAAAAA);
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.level.dimension() != PlanetMapper.SPACE_LEVEL) {
            return;
        }

        // Initialize textures once
        if (!texturesInitialized) {
            sunTexture = PlanetTextureGenerator.generateSunTexture();
            sunGlowTexture = PlanetTextureGenerator.generateSunGlowTexture();
            moonTexture = PlanetTextureGenerator.generateMoonTexture();
            texturesInitialized = true;
        }
        // Hot-swap safe: new fields can be null even when texturesInitialized is true
        if (sunGlowTexture == null) {
            sunGlowTexture = PlanetTextureGenerator.generateSunGlowTexture();
        }

        // Initialize registry with default system if empty
        CelestialBodyRegistry registry = CelestialBodyRegistry.getClientInstance();
        if (registry.getBodyCount() == 0) {
            registry.initializeDefaultSystem();
            // Assign textures to default bodies
            for (CelestialBody body : registry.getAllBodies()) {
                assignDefaultTexture(body);
            }
        }

        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 cam = camera.getPosition();
        Quaternionf viewRotation = new Quaternionf(camera.rotation()).conjugate();

        // Render distance limit for scaling
        float renderDist = mc.options.renderDistance().get() * 16.0f;
        float maxRenderingDist = renderDist * 0.9f;

        // Setup render state
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.setShaderFogStart(Float.MAX_VALUE);
        RenderSystem.setShaderFogEnd(Float.MAX_VALUE);

        PoseStack ps = event.getPoseStack();

        // Move into view space so bodies stay fixed when camera rotates
        ps.pushPose();
        ps.mulPose(viewRotation);

        // Render all celestial bodies
        for (CelestialBody body : registry.getAllBodies()) {
            ensureDefaultTexture(body);
            renderCelestialBody(ps, cam, body, registry, maxRenderingDist);
        }

        ps.popPose();

        // Restore render state
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
    }

    private static void assignDefaultTexture(CelestialBody body) {
        switch (body.getType()) {
            case STAR:
                body.setTexture(sunTexture);
                break;
            case PLANET:
                body.setTexture(generateSeededPlanetTexture(body));
                break;
            case MOON:
            case ASTEROID:
                body.setTexture(moonTexture);
                break;
        }
    }

    private static void renderCelestialBody(PoseStack ps, Vec3 cam, CelestialBody body, CelestialBodyRegistry registry,
            float maxDist) {
        Vec3 worldPos = body.getWorldPosition(registry);
        Vec3 rel = worldPos.subtract(cam);
        double dist = rel.length();
        double scale = 1.0;

        // Scale down if too far
        if (dist > maxDist) {
            scale = maxDist / dist;
            rel = rel.scale(scale);
        }

        ps.pushPose();
        ps.translate(rel.x, rel.y, rel.z);
        ps.scale((float) scale, (float) scale, (float) scale);

        if (body.getType() == CelestialBody.BodyType.STAR) {
            drawStarGlow(ps, body.getRadius(), body.getColorR(), body.getColorG(), body.getColorB());
        }

        // Apply axial tilt
        if (body.getAxialTilt() != 0) {
            ps.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(body.getAxialTilt()));
        }

        // Apply rotation
        if (body.getCurrentRotation() != 0) {
            ps.mulPose(com.mojang.math.Axis.YP.rotationDegrees(body.getCurrentRotation()));
        }

        // Ensure clean render state for sphere
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);

        // Setup shader
        RenderSystem.setShader(GameRenderer::getPositionTexShader);

        ResourceLocation tex = body.getTexture();
        if (tex != null) {
            RenderSystem.setShaderTexture(0, tex);
        }

        RenderSystem.setShaderColor(body.getColorR(), body.getColorG(), body.getColorB(), 1.0f);

        // Draw main body sphere
        drawSphere(ps, body.getRadius());

        ps.popPose();

        // Reset color to avoid tinting other renders (GUI etc.)
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
    }

    private static void drawSphere(PoseStack ps, float radius) {
        Matrix4f mat = ps.last().pose();
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        int stacks = 64;
        int slices = 64;
        for (int i = 0; i < stacks; i++) {
            double lat0 = Math.PI * (-0.5 + (double) i / stacks);
            double lat1 = Math.PI * (-0.5 + (double) (i + 1) / stacks);
            double sLat0 = Math.sin(lat0);
            double cLat0 = Math.cos(lat0);
            double sLat1 = Math.sin(lat1);
            double cLat1 = Math.cos(lat1);
            for (int j = 0; j < slices; j++) {
                double lng0 = 2 * Math.PI * (double) j / slices;
                double lng1 = 2 * Math.PI * (double) (j + 1) / slices;
                double cLng0 = Math.cos(lng0);
                double sLng0 = Math.sin(lng0);
                double cLng1 = Math.cos(lng1);
                double sLng1 = Math.sin(lng1);
                float u0 = (float) j / slices;
                float u1 = (float) (j + 1) / slices;
                float v0 = (float) i / stacks;
                float v1 = (float) (i + 1) / stacks;
                float x00 = (float) (cLat0 * cLng0) * radius;
                float y00 = (float) sLat0 * radius;
                float z00 = (float) (cLat0 * sLng0) * radius;
                float x01 = (float) (cLat1 * cLng0) * radius;
                float y01 = (float) sLat1 * radius;
                float z01 = (float) (cLat1 * sLng0) * radius;
                float x10 = (float) (cLat0 * cLng1) * radius;
                float y10 = (float) sLat0 * radius;
                float z10 = (float) (cLat0 * sLng1) * radius;
                float x11 = (float) (cLat1 * cLng1) * radius;
                float y11 = (float) sLat1 * radius;
                float z11 = (float) (cLat1 * sLng1) * radius;
                bb.addVertex(mat, x00, y00, z00).setUv(u0, v0);
                bb.addVertex(mat, x01, y01, z01).setUv(u0, v1);
                bb.addVertex(mat, x11, y11, z11).setUv(u1, v1);
                bb.addVertex(mat, x10, y10, z10).setUv(u1, v0);
            }
        }
        BufferUploader.drawWithShader(bb.buildOrThrow());
    }

    /**
     * If a body has no explicit texture, assign a generated default based on type.
     */
    private static void ensureDefaultTexture(CelestialBody body) {
        if (body.getTexture() != null) {
            return;
        }
        switch (body.getType()) {
            case STAR -> body.setTexture(sunTexture);
            case PLANET -> body.setTexture(generateSeededPlanetTexture(body));
            case MOON, ASTEROID -> body.setTexture(moonTexture);
        }
    }

    private static ResourceLocation generateSeededPlanetTexture(CelestialBody body) {
        long worldSeed = getWorldSeed(Minecraft.getInstance());
        long seed = mixSeed(worldSeed, body.getId().getMostSignificantBits(), body.getId().getLeastSignificantBits());
        return PlanetTextureGenerator.generateEarthTexture(seed);
    }

    private static long getWorldSeed(Minecraft mc) {
        if (mc.getSingleplayerServer() != null) {
            return mc.getSingleplayerServer().getWorldData().worldGenOptions().seed();
        }
        return 0L;
    }

    private static long mixSeed(long a, long b, long c) {
        long result = a;
        result ^= (b + 0x9E3779B97F4A7C15L + (result << 6) + (result >> 2));
        result ^= (c + 0x9E3779B97F4A7C15L + (result << 6) + (result >> 2));
        return result;
    }

    private static void drawStarGlow(PoseStack ps, float radius, float r, float g, float b) {
        // Soft radial glow (billboard in view space). Drawn before the star sphere, without writing depth.
        float glowRadius = radius * 5.0f;

        RenderSystem.enableBlend();
        RenderSystem.blendFunc(
                com.mojang.blaze3d.platform.GlStateManager.SourceFactor.SRC_ALPHA,
                com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE);
        RenderSystem.depthMask(false);
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, sunGlowTexture != null ? sunGlowTexture : sunTexture);
        RenderSystem.setShaderColor(r, g, b, 0.75f);

        Matrix4f mat = ps.last().pose();
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        bb.addVertex(mat, -glowRadius, -glowRadius, 0).setUv(0, 0);
        bb.addVertex(mat, -glowRadius, glowRadius, 0).setUv(0, 1);
        bb.addVertex(mat, glowRadius, glowRadius, 0).setUv(1, 1);
        bb.addVertex(mat, glowRadius, -glowRadius, 0).setUv(1, 0);
        BufferUploader.drawWithShader(bb.buildOrThrow());

        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(true);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
    }
}
