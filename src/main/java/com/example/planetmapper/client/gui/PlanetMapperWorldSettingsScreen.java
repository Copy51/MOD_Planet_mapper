package com.example.planetmapper.client.gui;

import com.example.planetmapper.Config;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Simple submenu attached to Create World screen for tweaking PlanetMapper settings.
 */
public class PlanetMapperWorldSettingsScreen extends Screen {
    private final Screen parent;
    private EditBox worldRadiusBox;

    public PlanetMapperWorldSettingsScreen(Screen parent) {
        super(Component.literal("Planet Mapper Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        int center = this.width / 2;
        int y = this.height / 2 - 20;

        worldRadiusBox = new EditBox(this.font, center - 80, y, 160, 20, Component.literal("World radius"));
        worldRadiusBox.setValue(Integer.toString(Config.WORLD_RADIUS.get()));
        addRenderableWidget(worldRadiusBox);

        addRenderableWidget(Button.builder(Component.literal("Save"), b -> save())
                .bounds(center - 80, y + 30, 75, 20)
                .build());

        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(center + 5, y + 30, 75, 20)
                .build());
    }

    private void save() {
        try {
            int val = Integer.parseInt(worldRadiusBox.getValue());
            val = Math.max(100, Math.min(30000000, val));
            Config.WORLD_RADIUS.set(val);
            onClose();
        } catch (NumberFormatException ignored) {
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        renderBackground(gui, mouseX, mouseY, partialTick);
        super.render(gui, mouseX, mouseY, partialTick);
        gui.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 45, 0xFFFFFF);
        gui.drawString(this.font, "World radius (edge wrap). Default 15000 for 30k x 30k.", this.width / 2 - 120,
                this.height / 2 - 25 - 12, 0xAAAAAA);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
