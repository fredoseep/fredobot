package com.fredoseep;

import com.fredoseep.excutor.PathExecutor;
import com.fredoseep.excutor.BotEngine;
import com.fredoseep.algorithm.planning.PlannerDebug;
import net.minecraft.client.MinecraftClient;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import com.fredoseep.render.PathRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public class FredoBotClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        HudRenderCallback.EVENT.register((matrices,tickDelta) -> {
            MinecraftClient client=MinecraftClient.getInstance();
            if(client.world==null || client.player==null) return;
            PlannerDebug.View view=BotEngine.getInstance().getModule(PathExecutor.class).getSearchView();
            if(view==null) return;
            int y=8; // top left, clear of the chat panel
            client.textRenderer.drawWithShadow(matrices,"[FredoBot] "+view.phase,8,y,0xFFFF55);
            client.textRenderer.drawWithShadow(matrices,view.detail,8,y+12,0xFFFFFF);
            client.textRenderer.drawWithShadow(matrices,"expanded="+view.expanded+" open="+view.open+" known="+view.known,8,y+24,0x55FFFF);
        });
    }
}