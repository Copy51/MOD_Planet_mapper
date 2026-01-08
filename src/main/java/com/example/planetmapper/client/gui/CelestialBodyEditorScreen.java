package com.example.planetmapper.client.gui;

import com.example.planetmapper.network.CelestialBodyUpdatePacket;
import com.example.planetmapper.space.CelestialBody;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Minimal editor for an existing celestial body with position drag-style fields.
 */
public class CelestialBodyEditorScreen extends Screen {

    private final CelestialBody source;

    private CycleButton<CelestialBody.BodyType> typeButton;
    private CelestialBody.BodyType selectedType;

    private EditBox nameBox;
    private EditBox xBox, yBox, zBox;
    private EditBox radiusBox, massBox;
    private EditBox vxBox, vyBox, vzBox;
    private EditBox rotationBox, tiltBox;
    private EditBox tempBox;
    private EditBox rBox, gBox, bBox;
    private EditBox textureBox;

    public CelestialBodyEditorScreen(CelestialBody source) {
        super(Component.literal("Edit Celestial Body"));
        this.source = source;
        this.selectedType = source.getType();
    }

    @Override
    protected void init() {
        super.init();
        int center = this.width / 2;
        int colL = center - 110;
        int colM = center - 35;
        int colR = center + 40;
        int y = 28;
        int gap = 22;

        nameBox = new EditBox(this.font, colL, y, 220, 20, Component.literal("Name"));
        nameBox.setValue(source.getName());
        addRenderableWidget(nameBox);

        typeButton = CycleButton.builder((CelestialBody.BodyType t) -> Component.literal(t.name()))
                .withValues(CelestialBody.BodyType.values())
                .withInitialValue(selectedType)
                .create(colL, y + gap, 220, 20, Component.literal("Type"), (b, t) -> selectedType = t);
        addRenderableWidget(typeButton);

        xBox = new EditBox(this.font, colL, y + gap * 2, 70, 20, Component.literal("X"));
        xBox.setValue(fmt(source.getPosition().x));
        addRenderableWidget(xBox);
        yBox = new EditBox(this.font, colM, y + gap * 2, 70, 20, Component.literal("Y"));
        yBox.setValue(fmt(source.getPosition().y));
        addRenderableWidget(yBox);
        zBox = new EditBox(this.font, colR, y + gap * 2, 70, 20, Component.literal("Z"));
        zBox.setValue(fmt(source.getPosition().z));
        addRenderableWidget(zBox);

        radiusBox = new EditBox(this.font, colL, y + gap * 3, 105, 20, Component.literal("Radius"));
        radiusBox.setValue(fmt(source.getRadius()));
        addRenderableWidget(radiusBox);
        massBox = new EditBox(this.font, colR - 105, y + gap * 3, 105, 20, Component.literal("Mass"));
        massBox.setValue(fmt(source.getMass()));
        addRenderableWidget(massBox);

        vxBox = new EditBox(this.font, colL, y + gap * 4, 70, 20, Component.literal("Vx"));
        vxBox.setValue(fmt(source.getVelocity().x));
        addRenderableWidget(vxBox);
        vyBox = new EditBox(this.font, colM, y + gap * 4, 70, 20, Component.literal("Vy"));
        vyBox.setValue(fmt(source.getVelocity().y));
        addRenderableWidget(vyBox);
        vzBox = new EditBox(this.font, colR, y + gap * 4, 70, 20, Component.literal("Vz"));
        vzBox.setValue(fmt(source.getVelocity().z));
        addRenderableWidget(vzBox);

        rotationBox = new EditBox(this.font, colL, y + gap * 5, 105, 20, Component.literal("Rot deg/tick"));
        rotationBox.setValue(fmt(source.getRotationSpeed()));
        addRenderableWidget(rotationBox);
        tiltBox = new EditBox(this.font, colR - 105, y + gap * 5, 105, 20, Component.literal("Tilt"));
        tiltBox.setValue(fmt(source.getAxialTilt()));
        addRenderableWidget(tiltBox);

        tempBox = new EditBox(this.font, colL, y + gap * 6, 220, 20, Component.literal("Temperature K (0=manual)"));
        tempBox.setValue(fmt(source.getTemperatureK()));
        addRenderableWidget(tempBox);

        rBox = new EditBox(this.font, colL, y + gap * 7, 70, 20, Component.literal("R"));
        rBox.setValue(fmt(source.getColorR()));
        addRenderableWidget(rBox);
        gBox = new EditBox(this.font, colM, y + gap * 7, 70, 20, Component.literal("G"));
        gBox.setValue(fmt(source.getColorG()));
        addRenderableWidget(gBox);
        bBox = new EditBox(this.font, colR, y + gap * 7, 70, 20, Component.literal("B"));
        bBox.setValue(fmt(source.getColorB()));
        addRenderableWidget(bBox);

        textureBox = new EditBox(this.font, colL, y + gap * 8, 220, 20,
                Component.literal("Texture namespace:path (optional)"));
        textureBox.setValue(source.getTexture() == null ? "" : source.getTexture().toString());
        addRenderableWidget(textureBox);

        addRenderableWidget(Button.builder(Component.literal("Apply"), b -> apply())
                .bounds(colL, y + gap * 9 + 6, 220, 20)
                .build());
    }

    private void apply() {
        try {
            String name = nameBox.getValue();
            double x = parse(xBox, source.getPosition().x);
            double y = parse(yBox, source.getPosition().y);
            double z = parse(zBox, source.getPosition().z);
            float radius = (float) parse(radiusBox, source.getRadius());
            double mass = parse(massBox, source.getMass());
            double vx = parse(vxBox, source.getVelocity().x);
            double vy = parse(vyBox, source.getVelocity().y);
            double vz = parse(vzBox, source.getVelocity().z);
            float rotation = (float) parse(rotationBox, source.getRotationSpeed());
            float tilt = (float) parse(tiltBox, source.getAxialTilt());
            double temp = parse(tempBox, source.getTemperatureK());
            float r = (float) parse(rBox, source.getColorR());
            float g = (float) parse(gBox, source.getColorG());
            float b = (float) parse(bBox, source.getColorB());
            String texture = textureBox.getValue().trim();

            if (selectedType == CelestialBody.BodyType.STAR && temp > 0) {
                float[] c = CelestialBody.temperatureToRGB(temp);
                r = c[0];
                g = c[1];
                b = c[2];
                rBox.setValue(fmt(r));
                gBox.setValue(fmt(g));
                bBox.setValue(fmt(b));
            }

            CelestialBodyUpdatePacket packet = new CelestialBodyUpdatePacket(
                    source.getId(), name, x, y, z, radius, mass, vx, vy, vz,
                    selectedType.name(), r, g, b, rotation, tilt, texture, temp);
            PacketDistributor.sendToServer(packet);
            onClose();
        } catch (NumberFormatException ignored) {
        }
    }

    private String fmt(double v) {
        return String.format("%.3f", v);
    }

    private double parse(EditBox box, double fallback) {
        try {
            return Double.parseDouble(box.getValue());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        renderBackground(gui, mouseX, mouseY, partialTick);
        super.render(gui, mouseX, mouseY, partialTick);
        gui.drawCenteredString(this.font, this.title, this.width / 2, 8, 0xFFFFFF);
        gui.drawString(this.font, "Position", this.width / 2 - 110, 28 + 22 * 2 - 12, 0xAAAAAA);
        gui.drawString(this.font, "Velocity", this.width / 2 - 110, 28 + 22 * 4 - 12, 0xAAAAAA);
        gui.drawString(this.font, "Rotation", this.width / 2 - 110, 28 + 22 * 5 - 12, 0xAAAAAA);
        gui.drawString(this.font, "Visuals", this.width / 2 - 110, 28 + 22 * 7 - 12, 0xAAAAAA);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
