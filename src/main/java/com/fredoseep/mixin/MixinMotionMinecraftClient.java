package com.fredoseep.mixin;

import com.fredoseep.motion.MotionCoordinator;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public class MixinMotionMinecraftClient {
    @Inject(method="handleInputEvents",at=@At("HEAD"))
    private void fredobot$guardMotionInteractions(CallbackInfo callback) {
        MotionCoordinator.beforeInputEvents();
    }
}
