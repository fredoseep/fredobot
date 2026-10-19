package com.fredoseep.mixin;

import com.fredoseep.motion.MotionCoordinator;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayerEntity.class)
public class MixinMotionClientPlayer {
    @Inject(method="tick",at=@At("HEAD"))
    private void fredobot$beforeMotionTick(CallbackInfo callback) {
        MotionCoordinator.beforePlayerTick((ClientPlayerEntity)(Object)this);
    }
    @Inject(method="tick",at=@At("TAIL"))
    private void fredobot$afterMotionTick(CallbackInfo callback) {
        MotionCoordinator.afterPlayerTick((ClientPlayerEntity)(Object)this);
    }
}
