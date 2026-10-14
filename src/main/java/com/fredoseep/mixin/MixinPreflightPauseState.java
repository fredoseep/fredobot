package com.fredoseep.mixin;

import com.fredoseep.preflight.EarlyResetPreflight;
import net.minecraft.server.integrated.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.function.BooleanSupplier;

@Mixin(IntegratedServer.class)
public class MixinPreflightPauseState {
    @Shadow private boolean paused;
    @Inject(method = "tick", at = @At("RETURN"))
    private void fredobot$publishPause(BooleanSupplier shouldKeepTicking, CallbackInfo callback) {
        EarlyResetPreflight.serverPause((IntegratedServer) (Object) this, paused);
    }
}
