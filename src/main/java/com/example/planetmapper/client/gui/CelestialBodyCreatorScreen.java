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

public class CelestialBodyCreatorScreen extends Screen {

    private final Vec3 defaultPos;

    private EditBox nameBox;
    private EditBox xBox, yBox, zBox;
    private EditBox radiusBox;
    private EditBox massBox; // New Mass field

    // Visual Color
    private EditBox rBox, gBox, bBox;
    private EditBox targetDimensionBox;

    private CycleButton<CelestialBody.BodyType> typeButton;
    private CelestialBody.BodyType selectedType = CelestialBody.BodyType.PLANET;

    public CelestialBodyCreatorScreen(Vec3 defaultPos) {
        super(Component.literal("Create Celestial Body"));
        this.defaultPos = defaultPos;
    }

    @Override
    protected void init() {
        super.init();

        int centerX = this.width / 2;
        int startY = 40;
        int gap = 25;

        // Name
        this.nameBox = new EditBox(this.font, centerX - 100, startY, 200, 20, Component.literal("Name"));
        this.nameBox.setHint(Component.literal("Body Name"));
        this.addRenderableWidget(this.nameBox);

        // Type
        this.typeButton = CycleButton.builder((CelestialBody.BodyType type) -> Component.literal(type.name()))
                .withValues(CelestialBody.BodyType.values())
                .withInitialValue(selectedType)
                .create(centerX - 100, startY + gap, 200, 20, Component.literal("Type"), (cycle, type) -> {
                    this.selectedType = type;
                    // Auto-adjust mass suggestion based on type
                    if (massBox != null) {
                        if (type == CelestialBody.BodyType.STAR)
                            massBox.setValue("100000.0");
                        else if (type == CelestialBody.BodyType.PLANET)
                            massBox.setValue("100.0");
                        else if (type == CelestialBody.BodyType.MOON)
                            massBox.setValue("10.0");
                        else
                            massBox.setValue("1.0");
                    }
                });
        this.addRenderableWidget(this.typeButton);

        // Position
        this.xBox = new EditBox(this.font, centerX - 100, startY + gap * 2, 60, 20, Component.literal("X"));
        this.xBox.setValue(String.format("%.1f", defaultPos.x));
        this.addRenderableWidget(this.xBox);

        this.yBox = new EditBox(this.font, centerX - 30, startY + gap * 2, 60, 20, Component.literal("Y"));
        this.yBox.setValue(String.format("%.1f", defaultPos.y));
        this.addRenderableWidget(this.yBox);

        this.zBox = new EditBox(this.font, centerX + 40, startY + gap * 2, 60, 20, Component.literal("Z"));
        this.zBox.setValue(String.format("%.1f", defaultPos.z));
        this.addRenderableWidget(this.zBox);

        // Radius
        this.radiusBox = new EditBox(this.font, centerX - 100, startY + gap * 3, 95, 20, Component.literal("Radius"));
        this.radiusBox.setValue("100.0");
        this.addRenderableWidget(this.radiusBox);

        // Mass
        this.massBox = new EditBox(this.font, centerX + 5, startY + gap * 3, 95, 20, Component.literal("Mass"));
        this.massBox.setValue("100.0");
        this.addRenderableWidget(this.massBox);

        // Color (RGB 0-1)
        this.rBox = new EditBox(this.font, centerX - 100, startY + gap * 4, 60, 20, Component.literal("R"));
        this.rBox.setValue("1.0");
        this.addRenderableWidget(this.rBox);

        this.gBox = new EditBox(this.font, centerX - 30, startY + gap * 4, 60, 20, Component.literal("G"));
        this.gBox.setValue("1.0");
        this.addRenderableWidget(this.gBox);

        this.bBox = new EditBox(this.font, centerX + 40, startY + gap * 4, 60, 20, Component.literal("B"));
        this.bBox.setValue("1.0");
        this.addRenderableWidget(this.bBox);

        // Target Dimension
        this.targetDimensionBox = new EditBox(this.font, centerX - 100, startY + gap * 5, 200, 20, Component.literal("Target Dimension"));
        this.targetDimensionBox.setHint(Component.literal("minecraft:overworld"));
        this.addRenderableWidget(this.targetDimensionBox);

        // Create Button
        this.addRenderableWidget(Button.builder(Component.literal("Create Body"), button -> create())
                .bounds(centerX - 100, startY + gap * 6 + 10, 200, 20)
                .build());
    }

    private void create() {
        try {
            String name = nameBox.getValue();
            if (name.isEmpty())
                name = "Unnamed";

            double x = Double.parseDouble(xBox.getValue());
            double y = Double.parseDouble(yBox.getValue());
            double z = Double.parseDouble(zBox.getValue());
            float radius = Float.parseFloat(radiusBox.getValue());
            double mass = Double.parseDouble(massBox.getValue());

            float r = Float.parseFloat(rBox.getValue());
            float g = Float.parseFloat(gBox.getValue());
            float b = Float.parseFloat(bBox.getValue());

            // Auto Velocity Logic (Keplerian estimation)
            double vx = 0, vy = 0, vz = 0;
            float rotationSpeed = 0.0f;
            float axialTilt = 0.0f;
            String texturePath = "";
            double temperatureK = 0.0;

            // Only apply auto-velocity if it's a planet/moon and sufficiently far from
            // center
            // Assuming Center (0,0,0) is the "Sun"
            if (selectedType == CelestialBody.BodyType.PLANET || selectedType == CelestialBody.BodyType.MOON) {
                double dist = Math.sqrt(x * x + z * z);
                if (dist > 500) { // Only if far enough
                    // v = sqrt(G*M / r)
                    // G = 0.5 (PhysicsHandler)
                    // M = 100,000 (Standard Star mass)
                    double G = 0.5;
                    double M = 100000.0;
                    double v = Math.sqrt(G * M / dist);

                    // Tangent Vector: (-z, 0, x) / dist
                    vx = (-z / dist) * v;
                    vz = (x / dist) * v;
                }
            }

            CelestialBodyCreationPacket packet = new CelestialBodyCreationPacket(
                    name, x, y, z, radius, mass, vx, vy, vz, selectedType.name(), r, g, b, rotationSpeed, axialTilt,
                    texturePath, temperatureK, targetDimensionBox.getValue());

            PacketDistributor.sendToServer(packet);

            this.onClose();

        } catch (NumberFormatException e) {
            // Ignore invalid input
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;
        guiGraphics.drawCenteredString(this.font, this.title, centerX, 20, 0xFFFFFF);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
