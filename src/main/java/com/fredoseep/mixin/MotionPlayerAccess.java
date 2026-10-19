package com.fredoseep.mixin;

import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PlayerEntity.class)
public interface MotionPlayerAccess {
    @Accessor("isSubmergedInWater") boolean fredobot$getWaterSubmersion();
    @Accessor("abilityResyncCountdown") int fredobot$getFlightWindow();
    @Accessor("abilityResyncCountdown") void fredobot$setFlightWindow(int value);
}
