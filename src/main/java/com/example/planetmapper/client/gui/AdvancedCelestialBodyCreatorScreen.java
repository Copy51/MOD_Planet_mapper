package com.example.planetmapper.client.gui;

import com.example.planetmapper.network.CelestialBodyCreationPacket;
import com.example.planetmapper.space.CelestialBody;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Rebuilt, compact creator with clear sections, hints and presets.
 */
public class AdvancedCelestialBodyCreatorScreen extends Screen {

    private final Vec3 defaultPos;
    private final Vec3 defaultVelocity;

    private CycleButton<CelestialBody.BodyType> typeButton;
    private CelestialBody.BodyType selectedType = CelestialBody.BodyType.PLANET;

    private EditBox nameBox;
    private EditBox xBox, yBox, zBox;
    private EditBox radiusBox, massBox;
    private EditBox vxBox, vyBox, vzBox;
    private EditBox rotationSpeedBox, axialTiltBox;
    private EditBox temperatureBox;
    private EditBox rBox, gBox, bBox;
    private EditBox textureBox;
    private EditBox targetDimensionBox;

    public AdvancedCelestialBodyCreatorScreen(Vec3 defaultPos, Vec3 defaultVelocity) {
        super(Component.literal("Advanced Celestial Body Creator"));
        this.defaultPos = defaultPos;
        this.defaultVelocity = defaultVelocity == null ? Vec3.ZERO : defaultVelocity;
    }

    @Override
    protected void init() {
        super.init();

        int centerX = this.width / 2;
        int colL = centerX - 110;
        int colM = centerX - 35;
        int colR = centerX + 40;
        int startY = 28;
        int gap = 22;

        // Basics
        nameBox = new EditBox(this.font, colL, startY, 220, 20, Component.literal("Name"));
        nameBox.setHint(Component.literal("Body name (e.g. Terra)"));
        addRenderableWidget(nameBox);

        typeButton = CycleButton.builder((CelestialBody.BodyType type) -> Component.literal(type.name()))
                .withValues(CelestialBody.BodyType.values())
                .withInitialValue(selectedType)
                .create(colL, startY + gap, 220, 20, Component.literal("Type"), (cycle, type) -> {
                    selectedType = type;
                    applyPresetForType(type);
                });
        addRenderableWidget(typeButton);

        // Presets row
        addRenderableWidget(Button.builder(Component.literal("Star"), b -> setPreset(CelestialBody.BodyType.STAR))
                .bounds(colL, startY + gap * 2, 52, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Planet"),
                b -> setPreset(CelestialBody.BodyType.PLANET)).bounds(colL + 56, startY + gap * 2, 52, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Moon"), b -> setPreset(CelestialBody.BodyType.MOON))
                .bounds(colL + 112, startY + gap * 2, 52, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Asteroid"),
                b -> setPreset(CelestialBody.BodyType.ASTEROID)).bounds(colL + 168, startY + gap * 2, 52, 20).build());

        // Position
        xBox = new EditBox(this.font, colL, startY + gap * 3, 70, 20, Component.literal("X"));
        xBox.setValue(String.format("%.1f", defaultPos.x));
        addRenderableWidget(xBox);

        yBox = new EditBox(this.font, colM, startY + gap * 3, 70, 20, Component.literal("Y"));
        yBox.setValue(String.format("%.1f", defaultPos.y));
        addRenderableWidget(yBox);

        zBox = new EditBox(this.font, colR, startY + gap * 3, 70, 20, Component.literal("Z"));
        zBox.setValue(String.format("%.1f", defaultPos.z));
        addRenderableWidget(zBox);

        // Size / Mass
        radiusBox = new EditBox(this.font, colL, startY + gap * 4, 105, 20, Component.literal("Radius"));
        radiusBox.setHint(Component.literal("10..5000"));
        radiusBox.setValue("100");
        addRenderableWidget(radiusBox);

        massBox = new EditBox(this.font, colR - 105, startY + gap * 4, 105, 20, Component.literal("Mass"));
        massBox.setHint(Component.literal("1..100000"));
        massBox.setValue("100");
        addRenderableWidget(massBox);

        // Velocity
        vxBox = new EditBox(this.font, colL, startY + gap * 5, 70, 20, Component.literal("Vx"));
        vxBox.setHint(Component.literal("rec < 50"));
        vxBox.setValue(String.format("%.3f", defaultVelocity.x));
        addRenderableWidget(vxBox);

        vyBox = new EditBox(this.font, colM, startY + gap * 5, 70, 20, Component.literal("Vy"));
        vyBox.setHint(Component.literal("-50..50"));
        vyBox.setValue(String.format("%.3f", defaultVelocity.y));
        addRenderableWidget(vyBox);

        vzBox = new EditBox(this.font, colR, startY + gap * 5, 70, 20, Component.literal("Vz"));
        vzBox.setHint(Component.literal("rec < 50"));
        vzBox.setValue(String.format("%.3f", defaultVelocity.z));
        addRenderableWidget(vzBox);

        addRenderableWidget(Button.builder(Component.literal("Auto Orbit"), b -> fillOrbitVelocity())
                .bounds(colL, startY + gap * 6, 105, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Zero Velocity"), b -> zeroVelocity())
                .bounds(colR - 105, startY + gap * 6, 105, 20).build());

        // Rotation
        rotationSpeedBox = new EditBox(this.font, colL, startY + gap * 7, 105, 20, Component.literal("Rot deg/tick"));
        rotationSpeedBox.setHint(Component.literal("0..30"));
        rotationSpeedBox.setValue("0");
        addRenderableWidget(rotationSpeedBox);

        axialTiltBox = new EditBox(this.font, colR - 105, startY + gap * 7, 105, 20, Component.literal("Tilt deg"));
        axialTiltBox.setHint(Component.literal("0..180"));
        axialTiltBox.setValue("0");
        addRenderableWidget(axialTiltBox);

        // Visuals
        temperatureBox = new EditBox(this.font, colL, startY + gap * 8, 220, 20, Component.literal("Temperature K"));
        temperatureBox.setHint(Component.literal("stars: 3000..30000, 0 = manual color"));
        temperatureBox.setValue("5500");
        addRenderableWidget(temperatureBox);

        rBox = new EditBox(this.font, colL, startY + gap * 9, 70, 20, Component.literal("R 0-1"));
        rBox.setValue("1.0");
        addRenderableWidget(rBox);

        gBox = new EditBox(this.font, colM, startY + gap * 9, 70, 20, Component.literal("G 0-1"));
        gBox.setValue("1.0");
        addRenderableWidget(gBox);

        bBox = new EditBox(this.font, colR, startY + gap * 9, 70, 20, Component.literal("B 0-1"));
        bBox.setValue("1.0");
        addRenderableWidget(bBox);

        addRenderableWidget(Button.builder(Component.literal("Apply Temp Color"), b -> applyTemperatureColor())
                .bounds(colL, startY + gap * 10, 220, 20).build());

        textureBox = new EditBox(this.font, colL, startY + gap * 11, 220, 20,
                Component.literal("Texture namespace:path (optional)"));
        textureBox.setHint(Component.literal("empty = auto"));
        addRenderableWidget(textureBox);

        targetDimensionBox = new EditBox(this.font, colL, startY + gap * 12, 220, 20, Component.literal("Target Dimension"));
        targetDimensionBox.setHint(Component.literal("minecraft:overworld"));
        addRenderableWidget(targetDimensionBox);

        addRenderableWidget(Button.builder(Component.literal("Create Body"), b -> create())
                .bounds(colL, startY + gap * 13 + 6, 220, 20).build());
    }

    private void setPreset(CelestialBody.BodyType type) {
        selectedType = type;
        typeButton.setValue(type);
        applyPresetForType(type);
    }

    private void applyPresetForType(CelestialBody.BodyType type) {
        switch (type) {
            case STAR -> {
                massBox.setValue("100000");
                radiusBox.setValue("300");
                temperatureBox.setValue("5500");
                applyTemperatureColor();
            }
            case PLANET -> {
                massBox.setValue("100");
                radiusBox.setValue("200");
                temperatureBox.setValue("0");
                rBox.setValue("0.4");
                gBox.setValue("0.6");
                bBox.setValue("1.0");
            }
            case MOON -> {
                massBox.setValue("10");
                radiusBox.setValue("80");
                temperatureBox.setValue("0");
                rBox.setValue("0.8");
                gBox.setValue("0.8");
                bBox.setValue("0.8");
            }
            case ASTEROID -> {
                massBox.setValue("1");
                radiusBox.setValue("30");
                temperatureBox.setValue("0");
                rBox.setValue("0.5");
                gBox.setValue("0.45");
                bBox.setValue("0.4");
            }
        }
    }

    private void fillOrbitVelocity() {
        double x = parseDouble(xBox, defaultPos.x);
        double z = parseDouble(zBox, defaultPos.z);
        double dist = Math.sqrt(x * x + z * z);
        if (dist < 1.0) {
            vxBox.setValue("0.0");
            vzBox.setValue("0.0");
            return;
        }

        double G = 0.5;
        double M = 100000.0;
        double v = Math.sqrt(G * M / dist);

        double vx = (-z / dist) * v;
        double vz = (x / dist) * v;

        vxBox.setValue(String.format("%.3f", vx));
        vzBox.setValue(String.format("%.3f", vz));
        vyBox.setValue("0.0");
    }

    private void zeroVelocity() {
        vxBox.setValue("0.0");
        vyBox.setValue("0.0");
        vzBox.setValue("0.0");
    }

    private void applyTemperatureColor() {
        double temperatureK = parseDouble(temperatureBox, 0.0);
        if (temperatureK <= 0) {
            return;
        }
        float[] color = CelestialBody.temperatureToRGB(temperatureK);
        rBox.setValue(String.format("%.3f", color[0]));
        gBox.setValue(String.format("%.3f", color[1]));
        bBox.setValue(String.format("%.3f", color[2]));
    }

    private void create() {
        try {
            String name = nameBox.getValue();
            if (name.isEmpty()) {
                name = "Unnamed";
            }

            double x = parseDouble(xBox, defaultPos.x);
            double y = parseDouble(yBox, defaultPos.y);
            double z = parseDouble(zBox, defaultPos.z);
            float radius = (float) parseDouble(radiusBox, 100.0);
            double mass = parseDouble(massBox, 100.0);

            double vx = parseDouble(vxBox, 0.0);
            double vy = parseDouble(vyBox, 0.0);
            double vz = parseDouble(vzBox, 0.0);

            float rotationSpeed = (float) parseDouble(rotationSpeedBox, 0.0);
            float axialTilt = (float) parseDouble(axialTiltBox, 0.0);

            double temperatureK = parseDouble(temperatureBox, 0.0);

            float r = (float) parseDouble(rBox, 1.0);
            float g = (float) parseDouble(gBox, 1.0);
            float b = (float) parseDouble(bBox, 1.0);

            if (selectedType == CelestialBody.BodyType.STAR && temperatureK > 0) {
                float[] color = CelestialBody.temperatureToRGB(temperatureK);
                r = color[0];
                g = color[1];
                b = color[2];
                rBox.setValue(String.format("%.3f", r));
                gBox.setValue(String.format("%.3f", g));
                bBox.setValue(String.format("%.3f", b));
            }

            String texturePath = textureBox.getValue().trim();

            CelestialBodyCreationPacket packet = new CelestialBodyCreationPacket(
                    name, x, y, z, radius, mass, vx, vy, vz, selectedType.name(), r, g, b, rotationSpeed, axialTilt,
                    texturePath, temperatureK, targetDimensionBox.getValue());

            PacketDistributor.sendToServer(packet);
            onClose();
        } catch (NumberFormatException ignored) {
            // Keep screen open for correction
        }
    }

    private double parseDouble(EditBox box, double fallback) {
        try {
            return Double.parseDouble(box.getValue());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;
        guiGraphics.drawCenteredString(this.font, this.title, centerX, 10, 0xFFFFFF);

        int leftText = centerX - 110;
        guiGraphics.drawString(this.font, "Position", leftText, 28 + 22 * 3 - 12, 0xAAAAAA);
        guiGraphics.drawString(this.font, "Velocity", leftText, 28 + 22 * 5 - 12, 0xAAAAAA);
        guiGraphics.drawString(this.font, "Rotation", leftText, 28 + 22 * 7 - 12, 0xAAAAAA);
        guiGraphics.drawString(this.font, "Visuals", leftText, 28 + 22 * 8 - 12, 0xAAAAAA);

        guiGraphics.drawString(this.font, "Ranges: Radius 10-5000; Mass 1-100000; Rot 0-30 deg/tick", leftText,
                28 + 22 * 14, 0x55FF55);
        guiGraphics.drawString(this.font, "Stars: Temp 3000-30000 K; Vel ~<50 for stability; Texture empty = auto",
                leftText, 28 + 22 * 15, 0xAAAAAA);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
