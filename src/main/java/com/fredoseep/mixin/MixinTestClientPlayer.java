package com.fredoseep.mixin;

import com.fredoseep.testing.CreativeWorldTestRunner;
import com.fredoseep.motion.MotionCoordinator;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayerEntity.class)
public class MixinTestClientPlayer {
    @Inject(method = "sendChatMessage", at = @At("HEAD"), cancellable = true)
    private void fredobot$localTestCommand(String message, CallbackInfo callback) {
        if (MotionCoordinator.command(message) || CreativeWorldTestRunner.command(message)) callback.cancel();
    }
}
