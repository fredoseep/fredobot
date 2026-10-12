package com.fredoseep.algorithm.planning;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.LiteralText;

/** Vanilla pause semantics: singleplayer integrated server pauses; LAN/multiplayer does not. */
public final class PlannerPauseScreen extends Screen {
    private final Runnable cancel, ready;
    private int frames;
    public PlannerPauseScreen(Runnable cancel, Runnable ready) {
        super(new LiteralText("FredoBot path search")); this.cancel = cancel; this.ready = ready;
    }
    @Override public boolean isPauseScreen() { return true; }
    @Override public void onClose() { cancel.run(); super.onClose(); }
    @Override public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        renderBackground(matrices);
        drawCenteredString(matrices, textRenderer, "FredoBot: searching paths...", width / 2, height / 2 - 12, 0xFFFFFF);
        drawCenteredString(matrices, textRenderer, "Esc: cancel", width / 2, height / 2 + 12, 0xAAAAAA);
        super.render(matrices, mouseX, mouseY, delta);
        // Allow vanilla's pause flag to settle before capturing the world.
        if (++frames == 2) ready.run();
    }
}
