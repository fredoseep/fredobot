package com.fredoseep;

import com.fredoseep.excutor.PathExecutor;
import com.fredoseep.excutor.BotEngine;
import com.fredoseep.algorithm.planning.PlannerDebug;
import com.fredoseep.logging.GlobalStateLogger;
import com.fredoseep.render.BotStatusHud;
import com.fredoseep.preflight.TreasureLootValidation;
import com.fredoseep.preflight.EarlyResetPreflight;
import com.fredoseep.testing.CreativeWorldTestRunner;
import net.minecraft.client.MinecraftClient;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import com.fredoseep.render.PathRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public class FredoBotClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(EarlyResetPreflight::pump);
        ClientTickEvents.END_CLIENT_TICK.register(CreativeWorldTestRunner::tick);
        ClientTickEvents.END_CLIENT_TICK.register(TreasureLootValidation::pump);
        ClientTickEvents.END_CLIENT_TICK.register(GlobalStateLogger::tick);
        HudRenderCallback.EVENT.register((matrices,tickDelta) -> {
            MinecraftClient client=MinecraftClient.getInstance();
            EarlyResetPreflight.pump(client);
            TreasureLootValidation.pump(client);
            if(client.world==null || client.player==null) return;
            BotEngine engine=BotEngine.getInstance();
            PathExecutor path=engine.getModule(PathExecutor.class);
            int y=8; // top left, clear of the chat panel
            int width=Math.max(1,client.getWindow().getScaledWidth()-16);
            for(String line:BotStatusHud.lines(engine,client.player)) {
                client.textRenderer.drawWithShadow(matrices,client.textRenderer.trimToWidth(line,width),8,y,0xFFFFFF);
                y+=12;
            }
            for(String line:CreativeWorldTestRunner.hudLines()) {
                client.textRenderer.drawWithShadow(matrices,client.textRenderer.trimToWidth(line,width),8,y,0x55FFFF);
                y+=12;
            }
            PlannerDebug.View view=path.getSearchView();
            if(view==null || (!engine.isRunning() && !path.isBusy()))return;
            y+=4;
            client.textRenderer.drawWithShadow(matrices,client.textRenderer.trimToWidth("寻路："+view.phase,width),8,y,0xFFFF55);
            client.textRenderer.drawWithShadow(matrices,client.textRenderer.trimToWidth(view.detail,width),8,y+12,0xFFFFFF);
            client.textRenderer.drawWithShadow(matrices,"expanded="+view.expanded+" open="+view.open+" known="+view.known,8,y+24,0x55FFFF);
        });
    }
}
