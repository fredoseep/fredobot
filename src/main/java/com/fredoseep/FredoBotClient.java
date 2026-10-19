package com.fredoseep;

import com.fredoseep.excutor.PathExecutor;
import com.fredoseep.motion.MotionCoordinator;
import com.fredoseep.motion.sim.FusionRuntime;
import com.fredoseep.motion.sim.GroundMotionExperiments;
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
    public static final float STATUS_HUD_SCALE=.8f;

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
            matrices.push();
            try {
                matrices.scale(STATUS_HUD_SCALE,STATUS_HUD_SCALE,1);
                int x=Math.round(8/STATUS_HUD_SCALE),y=x;
                int width=Math.max(1,(int)((client.getWindow().getScaledWidth()-16)/STATUS_HUD_SCALE));
                for(String line:BotStatusHud.lines(engine,client.player)) {
                    client.textRenderer.drawWithShadow(matrices,client.textRenderer.trimToWidth(line,width),x,y,0xFFFFFF);
                    y+=12;
                }
                for(String line:CreativeWorldTestRunner.hudLines()) {
                    client.textRenderer.drawWithShadow(matrices,client.textRenderer.trimToWidth(line,width),x,y,0x55FFFF);
                    y+=12;
                }
                String motionStatus=GroundMotionExperiments.pending()?GroundMotionExperiments.hudStatus():MotionCoordinator.hudStatus();
                if(motionStatus!=null) {
                    client.textRenderer.drawWithShadow(matrices,client.textRenderer.trimToWidth(motionStatus,width),x,y,0x55FF55);
                    y+=12;
                }
                String waterStatus=path.underwaterApproachStatus();
                if(waterStatus!=null){
                    client.textRenderer.drawWithShadow(matrices,client.textRenderer.trimToWidth(waterStatus,width),x,y,0x55FFFF);
                    y+=12;
                }
                for(String line:FusionRuntime.searchHudLines()){
                    client.textRenderer.drawWithShadow(matrices,client.textRenderer.trimToWidth(line,width),x,y,0xFFFF55);
                    y+=12;
                }
                PlannerDebug.View view=path.getSearchView();
                if(view==null || (!engine.isRunning() && !path.isBusy()))return;
                y+=4;
                client.textRenderer.drawWithShadow(matrices,client.textRenderer.trimToWidth("寻路："+view.phase,width),x,y,0xFFFF55);
                client.textRenderer.drawWithShadow(matrices,client.textRenderer.trimToWidth(view.detail,width),x,y+12,0xFFFFFF);
                client.textRenderer.drawWithShadow(matrices,"expanded="+view.expanded+" open="+view.open+" known="+view.known,x,y+24,0x55FFFF);
            } finally {matrices.pop();}
        });
    }
}
