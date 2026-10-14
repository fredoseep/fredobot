package com.fredoseep.mixin;

import com.fredoseep.preflight.EarlyResetPreflight;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.registry.RegistryTracker;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.level.LevelInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public class MixinPreflightWorldCreation {
    @Inject(method = "method_29607", at = @At("HEAD"))
    private void fredobot$recordActualCreationSeed(String directory, LevelInfo info, RegistryTracker.Modifiable registry,
                                                  GeneratorOptions options, CallbackInfo callback) {
        EarlyResetPreflight.creatingWorld(directory, options.getSeed());
    }
}
