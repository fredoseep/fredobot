package com.fredoseep.mixin;

import com.fredoseep.BtPosContext;
import com.fredoseep.TreasureSeedProvider;
import com.fredoseep.preflight.SelectedTreasureSeed;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** External mod methods are not Minecraft mapping targets. */
@Mixin(value = TreasureSeedProvider.class, remap = false)
public class MixinTreasureSeedProvider {
    @Inject(method = "checkSeed(J)Z", at = @At("RETURN"), remap = false)
    private void fredobot$captureSelectedSeed(long seed, CallbackInfoReturnable<Boolean> callback) {
        if (callback.getReturnValue()) {
            SelectedTreasureSeed.accepted(seed, BtPosContext.btXPos, BtPosContext.btZPos);
        }
    }
}
